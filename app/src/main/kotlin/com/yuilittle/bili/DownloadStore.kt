package com.yuilittle.bili

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 本地下载队列与断点续传。
 *
 * 视频采用 B 站 DASH 流（fnval=4048）：视频轨(.m4s)与音频轨(.m4s)各自按
 * initialization/index 区间分三段下载并拼回原文件布局，随后生成指向本地文件的
 * MPD，由 Media3 的 DashMediaSource 直接本地播放（音画同步、可拖动）。
 * 纯音频任务只下音频轨；单文件流(durl)任务整体下载。封面作为每个视频任务的第一步。
 */
object DownloadManager {

    const val TYPE_VIDEO = 0
    const val TYPE_AUDIO = 1
    const val TYPE_COVER = 2
    const val TYPE_COMMENT = 3

    const val STATE_QUEUED = 0
    const val STATE_RUNNING = 1
    const val STATE_PAUSED = 2
    const val STATE_COMPLETED = 3
    const val STATE_FAILED = 4

    /** 正在“进行中”Tab 展示的状态集合（排队/下载/暂停/失败）。 */
    val ACTIVE_STATES = setOf(STATE_QUEUED, STATE_RUNNING, STATE_PAUSED, STATE_FAILED)

    enum class SeasonSortOrder { DEFAULT, OLDEST_FIRST, NEWEST_FIRST }

    enum class LocalPlayMode {
        SEASON_SEQUENCE,
        SHUFFLE,
        LIST_LOOP,
        SINGLE_LOOP;

        fun label(): String = when (this) {
            SEASON_SEQUENCE -> "顺序播放"
            SHUFFLE -> "随机播放"
            LIST_LOOP -> "列表循环"
            SINGLE_LOOP -> "单集循环"
        }

        fun nextMode(): LocalPlayMode = when (this) {
            SEASON_SEQUENCE -> SHUFFLE
            SHUFFLE -> LIST_LOOP
            LIST_LOOP -> SINGLE_LOOP
            SINGLE_LOOP -> SEASON_SEQUENCE
        }

        /** 本地合集播放页仅提供顺序播放、单集循环与随机播放三档。 */
        fun nextSeasonMode(): LocalPlayMode = when (this) {
            SEASON_SEQUENCE -> SINGLE_LOOP
            SINGLE_LOOP -> SHUFFLE
            else -> SEASON_SEQUENCE
        }
    }

    data class StorageBreakdown(
        val deviceTotalBytes: Long,
        val deviceFreeBytes: Long,
        val deviceUsedBytes: Long,
        val downloadsBytes: Long,
        val appOtherBytes: Long,
        val appTotalBytes: Long,
        val otherUsedBytes: Long
    )

    class Task {
        var id: Long = 0L
        var type: Int = TYPE_VIDEO
        var bvid: String = ""
        var cid: Long = 0L
        var aid: Long = 0L
        var title: String = ""
        var owner: String = ""
        var coverUrl: String = ""
        var coverPath: String = ""
        /** 合集聚合元数据；空 groupId 表示旧任务/普通单视频。 */
        var groupId: String = ""
        var groupTitle: String = ""
        var groupCoverUrl: String = ""
        var episodeNo: Int = 0
        /** 分集或视频发布时间戳（秒），0 表示未知。 */
        var publishedAt: Long = 0L
        var quality: Int = 0
        var qualityLabel: String = ""
        var durationMs: Long = 0L
        @Volatile var state: Int = STATE_QUEUED
        @Volatile var bytesDone: Long = 0L
        @Volatile var bytesTotal: Long = 0L
        @Volatile var speedBytes: Long = 0L
        private var speedSampleAt = 0L
        private var speedSampleBytes = 0L

        internal fun sampleSpeed() {
            val now = SystemClock.elapsedRealtime()
            val bytes = bytesDone
            if (speedSampleAt == 0L) {
                speedSampleAt = now
                speedSampleBytes = bytes
                return
            }
            val elapsed = now - speedSampleAt
            if (elapsed >= 1000L) {
                speedBytes = (bytes - speedSampleBytes).coerceAtLeast(0L) * 1000L / elapsed
                speedSampleAt = now
                speedSampleBytes = bytes
            }
        }

        internal fun resetSpeed() {
            speedSampleAt = 0L
            speedSampleBytes = 0L
            speedBytes = 0L
        }

        var error: String = ""
        var createdAt: Long = 0L
        var finishedAt: Long = 0L
        /** 断点：DASH 下已写好的媒体段字节数；单文件流下已写文件长度。 */
        var resumeOffset: Long = 0L
        /** DASH 任务：本地 MPD 路径（存在即走本地 DashMediaSource）。 */
        var mpdPath: String = ""
        /** 纯音频任务：本地音频文件路径。 */
        var audioPath: String = ""
        /** 单文件流(durl)任务：各分片本地路径（多于1个时用 ConcatenatingMediaSource）。 */
        val singlePaths = CopyOnWriteArrayList<String>()
        val galleryExports = CopyOnWriteArrayList<String>() // 旧版导出记录，不作为播放源。
        var destination = DownloadDestination.PRIVATE
            internal set
        @Volatile internal var stage: DownloadStage? = null
        @Volatile internal var libraryNames: List<String> = emptyList()
        @Volatile internal var libraryLengths: List<Long> = emptyList()
        internal var libraryVersion: String = ""
        @Volatile var publicUris: List<String> = emptyList()
            internal set
        internal var cleanupPending = false
        /** 下载中置 true；暂停/删除时置 true 让 worker 快速退出。 */
        @Volatile var cancelRequested: Boolean = false
        /** 是否正在被 worker 执行。 */
        @Volatile var running: Boolean = false
        @Volatile var activeCall: okhttp3.Call? = null

        fun playable(): Boolean {
            if (state != STATE_COMPLETED) return false
            if (destination == DownloadDestination.LIBRARY) {
                // 构建离线队列时不逐集访问 MediaProvider；真正打开时校验对应文件。
                return publicUris.isNotEmpty() && publicUris.size == libraryNames.size &&
                    libraryLengths.size == libraryNames.size && libraryLengths.all { it > 0L }
            }
            return when {
                mpdPath.isNotBlank() -> File(mpdPath).exists()
                audioPath.isNotBlank() -> File(audioPath).exists()
                singlePaths.isNotEmpty() -> singlePaths.all { File(it).exists() }
                else -> false
            }
        }
    }

    private var appContext: Context? = null
    @Volatile private var initialized = false

    private val cleanupIo = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "YuiBiliDownloadCleanup").apply { isDaemon = true }
    }
    private val taskIo = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 15L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue()
    ).apply { allowCoreThreadTimeOut(true) }
    private var writePending = false
    private var writerScheduled = false

    private val lock = java.lang.Object()
    private val tasks = LinkedHashMap<Long, Task>()
    // 删除意图与任务记录原子落盘，进程中断后依然能按精确 URI 收尾。
    private val removedTasks = LinkedHashMap<Long, Task>()
    private val queue = LinkedHashSet<Long>()
    private val listeners = CopyOnWriteArrayList<(List<Task>) -> Unit>()
    private val main: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Handler(Looper.getMainLooper()) }
    private val deliveryPosted = AtomicBoolean()
    private val deliverUpdates = Runnable {
        deliveryPosted.set(false)
        if (listeners.isNotEmpty()) {
            val current = snapshot()
            listeners.forEach { it(current) }
        }
    }
    private val idSeq = AtomicLong(System.currentTimeMillis())
    private val workers = Array(3) { index ->
        Thread(::workerLoop, "YuiBiliDownloader-$index").apply { isDaemon = true }
    }
    private var workersStarted = false

    private val lastNotifyAt = AtomicLong(0L)
    private val cleanupWork = AtomicInteger()
    private var downloadDir: File? = null
    private var notificationManager: NotificationManager? = null

    private const val NOTIFICATION_CHANNEL_ID = "yuibili_downloads"
    private const val NOTIFICATION_ID = 0x5942
    /** 下载目录（供 UI 统计占用、清理残留等）。 */
    fun downloadDir(): File? = downloadDir

    fun init(context: Context): Boolean {
        if (initialized) return true
        val hasPendingTransfers = synchronized(lock) {
            if (initialized) return true
            val application = context.applicationContext
            BiliSessionStore.init(application)
            appContext = application
            downloadDir = File(application.filesDir, "downloads").apply { mkdirs() }
            notificationManager = application.getSystemService(NotificationManager::class.java)
            createNotificationChannel()
            if (!load()) return false
            idSeq.set(maxOf(idSeq.get(), tasks.keys.maxOrNull() ?: 0L, removedTasks.keys.maxOrNull() ?: 0L))
            if (removedTasks.isNotEmpty()) cleanRemovedTasks(removedTasks.values.toList())
            for (t in tasks.values) {
                if (t.cleanupPending && t.destination == DownloadDestination.LIBRARY && t.state == STATE_COMPLETED) {
                    scheduleCleanup { cleanCommittedStage(t) }
                }
            }
            initialized = true
            queue.isNotEmpty()
        }
        if (hasPendingTransfers) {
            if (context !is DownloadService) startDownloadService(context)
            kick()
        }
        return true
    }

    // ── 对外 API ────────────────────────────────────────────────

    fun addListener(listener: (List<Task>) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (List<Task>) -> Unit) { listeners.remove(listener) }

    fun snapshot(): List<Task> = synchronized(lock) { tasks.values.toList() }

    fun task(id: Long): Task? = synchronized(lock) { tasks[id] }

    fun defaultDestination(context: Context): DownloadDestination = DownloadDestination.fromKey(
        context.getSharedPreferences("download_storage", Context.MODE_PRIVATE).getString("destination", null)
    )

    fun setDefaultDestination(context: Context, destination: DownloadDestination) {
        context.getSharedPreferences("download_storage", Context.MODE_PRIVATE).edit()
            .putString("destination", destination.key).apply()
    }

    fun enqueueVideo(
        context: Context,
        item: VideoItem,
        quality: Int,
        label: String,
        groupId: String = "",
        groupTitle: String = "",
        groupCoverUrl: String = "",
        episodeNo: Int = 0
    ) {
        if (!init(context) || item.charge) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_VIDEO && it.bvid == item.bvid && it.cid == item.cid &&
                    it.quality == quality && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_VIDEO; destination = defaultDestination(context)
                bvid = item.bvid; cid = item.cid; aid = item.aid
                title = item.title; owner = item.owner; coverUrl = item.cover
                this.quality = quality; qualityLabel = label
                this.groupId = groupId; this.groupTitle = groupTitle
                this.groupCoverUrl = groupCoverUrl; this.episodeNo = episodeNo
                publishedAt = item.publishedAt
                durationMs = item.duration * 1000L
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    fun enqueueAudio(
        context: Context,
        item: VideoItem,
        groupId: String = "",
        groupTitle: String = "",
        groupCoverUrl: String = "",
        episodeNo: Int = 0
    ) {
        if (!init(context) || item.charge) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_AUDIO && it.bvid == item.bvid && it.cid == item.cid && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_AUDIO; destination = defaultDestination(context)
                bvid = item.bvid; cid = item.cid; aid = item.aid
                title = item.title; owner = item.owner; coverUrl = item.cover
                qualityLabel = "仅音频"
                this.groupId = groupId; this.groupTitle = groupTitle
                this.groupCoverUrl = groupCoverUrl; this.episodeNo = episodeNo
                publishedAt = item.publishedAt
                durationMs = item.duration * 1000L
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    fun enqueueCover(
        context: Context,
        item: VideoItem,
        groupId: String = "",
        groupTitle: String = "",
        groupCoverUrl: String = "",
        episodeNo: Int = 0
    ) {
        if (!init(context)) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_COVER && it.bvid == item.bvid && it.cid == item.cid && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_COVER
                bvid = item.bvid; cid = item.cid; aid = item.aid
                title = item.title + "（封面）"; owner = item.owner; coverUrl = item.cover
                qualityLabel = "封面"
                this.groupId = groupId; this.groupTitle = groupTitle
                this.groupCoverUrl = groupCoverUrl; this.episodeNo = episodeNo
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    /** 评论图片（评论区图片直链）下载任务。 */
    fun enqueueComment(context: Context, title: String, url: String, owner: String = "") {
        if (!init(context) || url.isBlank()) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_COMMENT && it.coverUrl == url && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_COMMENT
                this.title = title.ifBlank { "评论图片" }; this.owner = owner
                coverUrl = url
                qualityLabel = "图片"
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    /** 按直链下载封面图片（合集封面等非主视频封面场景）。 */
    fun enqueueCoverUrl(context: Context, title: String, url: String) {
        if (!init(context) || url.isBlank()) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_COVER && it.coverUrl == url && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_COVER
                this.title = title.ifBlank { "封面" }
                coverUrl = url
                qualityLabel = "封面"
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    /** 重命名任务标题（不改本地文件名，仅展示用）。 */
    fun rename(id: Long, title: String) {
        val t = task(id) ?: return
        val name = title.trim()
        if (name.isBlank()) return
        synchronized(t) { t.title = name }
        save()
        notifyListeners()
    }

    /** 重命名合集标题：该 groupId 下所有任务的 groupTitle 一起改（各自分集标题不动）。 */
    fun renameGroup(groupId: String, title: String) {
        if (groupId.isBlank()) return
        val name = title.trim()
        if (name.isBlank()) return
        synchronized(lock) {
            var changed = false
            tasks.values.filter { it.groupId == groupId }.forEach { t ->
                synchronized(t) { t.groupTitle = name }
                changed = true
            }
            if (changed) saveLocked()
        }
        notifyListeners()
    }

    fun pause(id: Long) {
        var callToCancel: okhttp3.Call? = null
        synchronized(lock) {
            val task = tasks[id] ?: return
            synchronized(task) {
                if (task.state != STATE_RUNNING && task.state != STATE_QUEUED) return
                task.cancelRequested = true
                callToCancel = task.activeCall
                if (task.state == STATE_QUEUED) {
                    task.state = STATE_PAUSED
                    queue.remove(id)
                }
                saveLocked()
            }
        }
        callToCancel?.cancel()
        notifyListeners()
    }

    fun resume(id: Long) = requeue(id, STATE_PAUSED)

    fun retry(id: Long) = requeue(id, STATE_FAILED)

    private fun requeue(id: Long, previousState: Int) {
        synchronized(lock) {
            val task = tasks[id] ?: return
            synchronized(task) {
                if (task.state != previousState) return
                task.state = STATE_QUEUED
                task.cancelRequested = false
                task.error = ""
            }
            queue.add(id)
            saveLocked()
        }
        notifyListeners()
        appContext?.let(::startDownloadService)
        kick()
    }

    fun delete(id: Long) = deleteMany(listOf(id))

    fun deleteMany(ids: List<Long>) {
        val idleTasks = ArrayList<Task>()
        val callsToCancel = ArrayList<okhttp3.Call>()
        synchronized(lock) {
            var changed = false
            for (id in ids) {
                val task = tasks.remove(id) ?: continue
                removedTasks[id] = task
                queue.remove(id)
                synchronized(task) {
                    task.cancelRequested = true
                    task.activeCall?.let(callsToCancel::add)
                    if (!task.running) idleTasks.add(task)
                }
                changed = true
            }
            if (!changed) return
            saveLocked()
            if (idleTasks.isNotEmpty()) cleanRemovedTasks(idleTasks)
        }
        callsToCancel.forEach { it.cancel() }
        notifyListeners()
    }

    private fun cleanRemovedTasks(removed: List<Task>) = scheduleCleanup {
        val ctx = checkNotNull(appContext)
        val dir = checkNotNull(downloadDir)
        persistCheckpoint()
        for (t in removed) {
            try {
                // 旧记录没有按任务生成的公共文件标识；用户自己的同名文件不得随缓存删除。
                t.libraryNames.forEach { name -> DownloadMediaLibrary.delete(ctx, t.type == TYPE_AUDIO, t.id, name) }
                if (DownloadPrivateFiles.clean(dir, t.id, includeCover = true)) synchronized(lock) {
                    removedTasks.remove(t.id)
                    saveLocked()
                }
            } catch (e: Exception) {
                Log.w("Downloads", "Unable to finish deleting task ${t.id}", e)
            }
        }
    }

    private fun scheduleCleanup(work: () -> Unit) {
        cleanupWork.incrementAndGet()
        cleanupIo.execute {
            try { work() } catch (e: Exception) { Log.w("Downloads", "Download cleanup deferred", e) }
            finally { cleanupWork.decrementAndGet(); notifyListeners() }
        }
    }

    /** 清空某个已完成/未完成任务在下载目录下的关联媒体文件但保留任务（暂未用）。 */
    fun computeDownloadProgress(bytesDone: Long, bytesTotal: Long): Float =
        if (bytesTotal > 0L) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

    /** 计算本软件真实物理磁盘占用与设备空间拆分（不含系统其它应用）。 */
    fun measureStorageBreakdown(context: Context): StorageBreakdown {
        init(context)
        val app = context.applicationContext
        val (total, used, free) = try {
            val dir = Environment.getDataDirectory()
            val stat = android.os.StatFs(dir.path)
            val block = stat.blockSizeLong
            val t = stat.blockCountLong * block
            val f = stat.availableBlocksLong * block
            Triple(t, (t - f).coerceAtLeast(0L), f)
        } catch (_: Throwable) {
            Triple(0L, 0L, 0L)
        }
        val dlDir = downloadDir
        refreshTaskDiskBytesMap()
        val downloadsBytes = computeDirectoryBytes(dlDir)
        val dataBytes = computeDirectoryBytes(File(app.applicationInfo.dataDir))
        val cacheBytes = computeDirectoryBytes(app.externalCacheDir)
        val extFilesBytes = computeDirectoryBytes(app.getExternalFilesDir(null))
        val apkBytes = listOfNotNull(app.applicationInfo.sourceDir)
            .plus(app.applicationInfo.splitSourceDirs?.toList() ?: emptyList())
            .filter { it.isNotBlank() }
            .sumOf { p -> File(p).takeIf { it.exists() }?.length() ?: 0L }
        val rawAppTotal = (dataBytes + cacheBytes + extFilesBytes + apkBytes).coerceAtLeast(downloadsBytes)
        val appOtherBytes = (rawAppTotal - downloadsBytes).coerceAtLeast(0L)
        val otherUsedBytes = (used - rawAppTotal).coerceAtLeast(0L)
        return StorageBreakdown(
            deviceTotalBytes = total,
            deviceFreeBytes = free,
            deviceUsedBytes = used,
            downloadsBytes = downloadsBytes,
            appOtherBytes = appOtherBytes,
            appTotalBytes = rawAppTotal,
            otherUsedBytes = otherUsedBytes
        )
    }

    private fun computeDirectoryBytes(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    /** 后台单次聚合扫描下载目录（taskId -> 实际磁盘字节），避免主线程逐卡 listFiles。 */
    @Volatile
    private var cachedTaskDiskMap: Map<Long, Long> = emptyMap()

    fun refreshTaskDiskBytesMap(): Map<Long, Long> {
        val dir = downloadDir
        if (dir == null || !dir.exists()) {
            cachedTaskDiskMap = emptyMap()
            return emptyMap()
        }
        val map = HashMap<Long, Long>()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val id = file.name.substringBefore('_').removeSuffix(".mpd").toLongOrNull() ?: return@forEach
            map[id] = (map[id] ?: 0L) + file.length()
        }
        cachedTaskDiskMap = map
        return map
    }

    /** 读取单任务实际磁盘占用（O(1) 命中后台聚合缓存，零主线程目录遍历）。 */
    fun taskDiskBytes(task: Task): Long {
        if (task.destination == DownloadDestination.LIBRARY && task.state == STATE_COMPLETED) return task.bytesTotal
        cachedTaskDiskMap[task.id]?.takeIf { it > 0L }?.let { return it }
        return when {
            task.bytesTotal > 0L && task.state == STATE_COMPLETED -> task.bytesTotal
            task.bytesDone > 0L -> task.bytesDone
            else -> 0L
        }
    }

    /** 判断某批已完成任务所属合集/排序。 */
    fun sortEpisodes(episodes: List<UgcEpisode>, order: SeasonSortOrder): List<UgcEpisode> = when (order) {
        SeasonSortOrder.DEFAULT -> episodes
        SeasonSortOrder.OLDEST_FIRST -> episodes.withIndex()
            .sortedWith(
                compareBy<IndexedValue<UgcEpisode>> {
                    if (it.value.publishedAt > 0L) it.value.publishedAt else Long.MAX_VALUE
                }.thenBy { it.index }
            ).map { it.value }
        SeasonSortOrder.NEWEST_FIRST -> episodes.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<UgcEpisode>> {
                    if (it.value.publishedAt > 0L) it.value.publishedAt else Long.MIN_VALUE
                }.thenBy { it.index }
            ).map { it.value }
    }

    /** 本地已下载合集内分集排序。 */
    fun sortTasks(tasks: List<Task>, order: SeasonSortOrder): List<Task> = when (order) {
        SeasonSortOrder.DEFAULT -> tasks.sortedWith(
            compareBy<Task> { if (it.episodeNo > 0) it.episodeNo else Int.MAX_VALUE }
                .thenBy { it.createdAt }
        )
        SeasonSortOrder.OLDEST_FIRST -> tasks.sortedWith(
            compareBy<Task> {
                val ts = if (it.publishedAt > 0L) it.publishedAt else it.createdAt / 1000L
                ts
            }.thenBy { if (it.episodeNo > 0) it.episodeNo else Int.MAX_VALUE }
        )
        SeasonSortOrder.NEWEST_FIRST -> tasks.sortedWith(
            compareByDescending<Task> {
                val ts = if (it.publishedAt > 0L) it.publishedAt else it.createdAt / 1000L
                ts
            }.thenBy { if (it.episodeNo > 0) it.episodeNo else Int.MAX_VALUE }
        )
    }

    /** 离线合集下一集调度（支持顺序播放、随机播放、列表循环、单集循环）。 */
    fun resolveNextPlayableTaskId(
        playlist: List<Task>,
        currentTaskId: Long,
        mode: LocalPlayMode,
        randomIndexProvider: (Int) -> Int = { size -> kotlin.random.Random.nextInt(size) }
    ): Long? {
        if (playlist.isEmpty()) return null
        val curIdx = playlist.indexOfFirst { it.id == currentTaskId }
        if (curIdx < 0) return playlist.first().id
        return when (mode) {
            LocalPlayMode.SINGLE_LOOP -> playlist[curIdx].id
            LocalPlayMode.SEASON_SEQUENCE -> playlist.getOrNull(curIdx + 1)?.id
            LocalPlayMode.LIST_LOOP -> playlist[(curIdx + 1) % playlist.size].id
            LocalPlayMode.SHUFFLE -> {
                if (playlist.size == 1) playlist[0].id
                else {
                    val candidates = playlist.filter { it.id != currentTaskId }
                    val pick = randomIndexProvider(candidates.size).coerceIn(0, candidates.lastIndex)
                    candidates[pick].id
                }
            }
        }
    }

    /** 离线合集上一集调度（单集循环/顺序/列表循环退回上一集，随机也优先历史/上一项）。 */
    fun resolvePrevPlayableTaskId(
        playlist: List<Task>,
        currentTaskId: Long,
        mode: LocalPlayMode
    ): Long? {
        if (playlist.isEmpty()) return null
        val curIdx = playlist.indexOfFirst { it.id == currentTaskId }
        if (curIdx < 0) return playlist.first().id
        return when (mode) {
            LocalPlayMode.SINGLE_LOOP -> playlist[curIdx].id
            LocalPlayMode.SEASON_SEQUENCE -> if (curIdx > 0) playlist[curIdx - 1].id else null
            LocalPlayMode.LIST_LOOP, LocalPlayMode.SHUFFLE ->
                playlist[(curIdx - 1 + playlist.size) % playlist.size].id
        }
    }

    /** 查询指定任务同一合集+同类型的全部任务（用于离线播放页合集列表）。 */
    fun siblingTasksForPlayback(task: Task): List<Task> {
        if (task.groupId.isBlank()) return emptyList()
        val snap = snapshot()
        return sortTasks(
            snap.filter { it.groupId == task.groupId && it.type == task.type && it.playable() },
            SeasonSortOrder.DEFAULT
        )
    }

    /** 音乐播放器的随机下一曲解析：仅在多首时尽量避开当前曲目。 */
    fun mergeAudioPlaylist(currentIds: List<Long>, addedIds: List<Long>): List<Long> {
        val result = ArrayList<Long>(currentIds.size + addedIds.size)
        val seen = HashSet<Long>()
        for (id in currentIds) {
            if (id > 0L && seen.add(id)) result.add(id)
        }
        for (id in addedIds) {
            if (id > 0L && seen.add(id)) result.add(id)
        }
        return result
    }

    /** 基于任务 ID 列表过滤当前仍存在且可播的纯音频任务，并保持有序去重。 */
    fun resolveSavedAudioPlaylist(savedIds: List<Long>, allTasks: List<Task>): List<Task> {
        val byId = allTasks.associateBy { it.id }
        val result = ArrayList<Task>(savedIds.size)
        val seen = HashSet<Long>()
        for (id in savedIds) {
            if (!seen.add(id)) continue
            val t = byId[id] ?: continue
            if (t.type == TYPE_AUDIO && t.playable()) result.add(t)
        }
        return result
    }

    // ── 内部：队列调度 ──────────────────────────────────────────

    private fun addTaskLocked(t: Task) {
        tasks[t.id] = t
        queue.add(t.id)
        saveLocked()
    }

    private fun kick() {
        synchronized(lock) {
            if (!workersStarted) {
                workers.forEach { it.start() }
                workersStarted = true
            }
            lock.notifyAll()
        }
    }

    private fun workerLoop() {
        while (true) {
            val task = synchronized(lock) {
                var next = claimTaskLocked()
                while (next == null) {
                    lock.wait()
                    next = claimTaskLocked()
                }
                next
            }
            try {
                notifyListeners()
                execute(task)
            } finally {
                synchronized(lock) {
                    task.activeCall = null
                    task.running = false
                    if (task.state != STATE_QUEUED) queue.remove(task.id)
                    if (queue.isNotEmpty()) lock.notifyAll()
                    if (tasks[task.id] !== task) cleanRemovedTasks(listOf(task))
                }
                notifyListeners()
            }
        }
    }

    private fun claimTaskLocked(): Task? {
        for (id in queue) {
            val task = tasks[id] ?: continue
            synchronized(task) {
                if (task.state == STATE_QUEUED && !task.running) {
                    task.cancelRequested = false
                    task.resetSpeed()
                    task.running = true
                    task.state = STATE_RUNNING
                    return task
                }
            }
        }
        return null
    }

    // ── 内部：执行 ──────────────────────────────────────────────

    private fun execute(t: Task) {
        val dir = downloadDir ?: run { fail(t, "存储不可用"); return }
        try {
            if (t.destination == DownloadDestination.LIBRARY) {
                checkNotCancelled(t)
                if (DownloadMediaLibrary.needsPermission(checkNotNull(appContext))) throw IOException("请授予存储权限后重试")
                val staged = t.stage
                if (staged != null && adoptLibraryBatch(t, staged)) return
                if (staged != null && staged.isIntact(dir, t.id)) {
                    saveToLibrary(t, staged, dir)
                    return
                }
                t.stage = null
            }
            when (t.type) {
                TYPE_COVER -> runCover(t, dir)
                TYPE_AUDIO -> runAudio(t, dir)
                TYPE_COMMENT -> runComment(t, dir)
                else -> runVideo(t, dir)
            }
        } catch (e: Exception) {
            fail(t, e.message?.takeIf { it.isNotBlank() } ?: "下载失败")
        }
    }

    private fun runCover(t: Task, dir: File) {
        checkNotCancelled(t)
        val url = t.coverUrl
        if (url.isBlank()) { fail(t, "封面地址为空"); return }
        val ext = url.substringAfterLast('.', "jpg").substringBefore('?').takeIf { it.length in 2..5 } ?: "jpg"
        val out = File(dir, "${t.id}_cover.$ext")
        t.coverPath = out.absolutePath
        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(url), out, t) { done ->
            t.bytesDone = done
            t.sampleSpeed()
            if (t.bytesTotal > 0L) maybeNotify()
        }
        t.bytesDone = out.length()
        t.bytesTotal = out.length()
        complete(t)
    }

    private fun runComment(t: Task, dir: File) {
        checkNotCancelled(t)
        val url = t.coverUrl
        if (url.isBlank()) { fail(t, "图片地址为空"); return }
        val ext = url.substringAfterLast('.', "jpg").substringBefore('?').takeIf { it.length in 2..5 } ?: "jpg"
        val out = File(dir, "${t.id}_comment.$ext")
        t.coverPath = out.absolutePath
        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(url), out, t) { done ->
            t.bytesDone = done
            t.sampleSpeed()
            if (t.bytesTotal > 0L) maybeNotify()
        }
        t.bytesDone = out.length()
        t.bytesTotal = out.length()
        complete(t)
    }

    private fun runAudio(t: Task, dir: File) {
        // 第一步：封面（失败不阻塞音频本体，用于下载管理与本地播放器封面展示）
        if (t.coverPath.isBlank() && t.coverUrl.isNotBlank()) {
            runCatching {
                val ext = t.coverUrl.substringAfterLast('.', "jpg").substringBefore('?')
                    .takeIf { it.length in 2..5 } ?: "jpg"
                val cover = File(dir, "${t.id}_cover.$ext")
                downloadFullQuiet(t.coverUrl, cover)
                t.coverPath = cover.absolutePath
            }
        }
        checkNotCancelled(t)
        t.qualityLabel = "仅音频"
        save(); notifyListeners()
        // 防下错：cid 缺失时 B 站播放接口会返回该 bvid 默认分集（主视频）的流，导致合集分集音频静默下错
        if (t.cid <= 0L || t.bvid.isBlank()) {
            fail(t, "音频任务缺少分集标识，请删除后重新添加")
            return
        }
        val stream = fetchStream(t) ?: return
        val audio = pickAudio(stream) ?: run { fail(t, "该视频没有可下载的音频流"); return }
        val out = File(dir, "${t.id}_audio.m4s")
        t.audioPath = out.absolutePath
        t.durationMs = stream.durationMs.coerceAtLeast(t.durationMs)
        // 整段下载（BiliTools 同款）：DASH baseUrl 直接 Range 下载即为完整 m4s，
        // 避免 init/media 分段拼接在部分 CDN 节点上出错
        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(audio.url) + audio.backups, out, t) { done ->
            t.bytesDone = done
            t.sampleSpeed()
            maybeNotify()
        }
        t.bytesDone = out.length()
        t.bytesTotal = out.length()
        t.resumeOffset = 0L
        complete(t, DownloadStage(DownloadLayout.AUDIO, listOf(out.length())))
    }

    private fun runVideo(t: Task, dir: File) {
        checkNotCancelled(t)
        // 第一步：封面（失败不阻塞视频本体）
        if (t.coverPath.isBlank() && t.coverUrl.isNotBlank()) {
            runCatching {
                val ext = t.coverUrl.substringAfterLast('.', "jpg").substringBefore('?')
                    .takeIf { it.length in 2..5 } ?: "jpg"
                val cover = File(dir, "${t.id}_cover.$ext")
                downloadFullQuiet(t.coverUrl, cover)
                t.coverPath = cover.absolutePath
            }
        }
        checkNotCancelled(t)
        save(); notifyListeners()

        val stream = fetchStream(t) ?: return
        t.durationMs = stream.durationMs.coerceAtLeast(t.durationMs)

        if (!stream.isDash) {
            // 单文件流(durl)：逐段下载，已存在的分片从断点续传（网络中断/重试不再从头）
            val segments = stream.durlSegments.ifEmpty {
                listOf(BiliPlayUrl.DurlSegment(
                    stream.fallbackUrls.ifEmpty { listOf(stream.videoUrl) },
                    stream.durationMs, 0L
                ))
            }
            t.mpdPath = ""
            t.singlePaths.clear()
            var segIndex = 0
            var segDone = 0L
            for (segment in segments) {
                checkNotCancelled(t)
                val urls = segment.urls.filter { it.isNotBlank() }
                if (urls.isEmpty()) throw IOException("第 ${segIndex + 1} 个视频分片缺少下载地址")
                val segFile = File(dir, "${t.id}_seg${segIndex++}.mp4")
                t.singlePaths.add(segFile.absolutePath)
                t.bytesTotal = -1L
                save(); notifyListeners()
                downloadFullResume(urls, segFile, t, base = segDone) { done ->
                    // done 已含 base（前序分片字节），即累计已下载字节
                    t.bytesDone = done
                    t.sampleSpeed()
                    maybeNotify()
                }
                if (segment.sizeBytes > 0L && segFile.length() != segment.sizeBytes) throw IOException("第 $segIndex 个视频分片长度不符")
                segDone += segFile.length()
            }
            t.bytesTotal = t.singlePaths.sumOf { File(it).length() }
            t.bytesDone = t.bytesTotal
            t.resumeOffset = 0L
            complete(t, DownloadStage(DownloadLayout.SEGMENTS, t.singlePaths.map { File(it).length() }))
            return
        }

        // DASH：视频轨 + 音频轨（选 <= 目标画质的最高档，AVC 优先）
        // 整段下载（BiliTools 同款）：baseUrl 直接 Range 下载即为完整可播放 m4s，
        // 不拆 init/index/media 分段，避免部分 CDN 节点 range 语义差异导致几 KB 假文件
        val video = pickVideo(stream, t.quality)
        val audio = pickAudio(stream)
        if (video == null) { fail(t, "没有可用的视频流"); return }

        val videoFile = File(dir, "${t.id}_video.m4s")
        val audioFile = File(dir, "${t.id}_audio.m4s")
        t.singlePaths.clear()

        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(video.url) + video.backups, videoFile, t) { done ->
            t.bytesDone = done
            t.sampleSpeed()
            maybeNotify()
        }

        if (audio != null) {
            val videoDone = videoFile.length()
            downloadFullResume(listOf(audio.url) + audio.backups, audioFile, t, base = videoDone) { done ->
                // done 已含 base（视频轨字节），即累计已下载字节
                t.bytesDone = done
                t.sampleSpeed()
                maybeNotify()
            }
        }

        checkNotCancelled(t)
        t.bytesDone = videoFile.length() + (if (audio != null) audioFile.length() else 0L)
        t.bytesTotal = t.bytesDone
        t.resumeOffset = 0L
        t.qualityLabel = BiliPlayUrl.qualityLabel(video.id).replace("P", "p")
        val stage = DownloadStage(DownloadLayout.DASH, listOfNotNull(videoFile.length(), audio?.let { audioFile.length() }))
        if (t.destination == DownloadDestination.LIBRARY) {
            complete(t, stage)
            return
        }

        // 生成本地 MPD：BaseURL 指向本地文件，区间与原始布局一致
        val mpdFile = File(dir, "${t.id}.mpd")
        val localVideo = BiliPlayUrl.DashVideoTrack(
            id = video.id, url = Uri.fromFile(videoFile).toString(), backups = emptyList(),
            bandwidth = video.bandwidth, mimeType = video.mimeType, codecs = video.codecs,
            width = video.width, height = video.height, frameRate = video.frameRate,
            initializationRange = video.initializationRange, indexRange = video.indexRange
        )
        val localAudio = audio?.let {
            BiliPlayUrl.DashAudioTrack(
                id = it.id, url = Uri.fromFile(audioFile).toString(), backups = emptyList(),
                bandwidth = it.bandwidth, mimeType = it.mimeType, codecs = it.codecs,
                initializationRange = it.initializationRange, indexRange = it.indexRange
            )
        }
        val streamForMpd = BiliPlayUrl.DashStream(
            videoTracks = listOf(localVideo),
            audioTracks = listOfNotNull(localAudio),
            durationMs = t.durationMs.coerceAtLeast(1L),
            actualQuality = video.id,
            acceptedQualities = emptyList(),
            acceptedDescriptions = emptyList(),
            isDash = true
        )
        mpdFile.writeText(BiliDashManifest.build(streamForMpd))
        t.mpdPath = mpdFile.absolutePath
        complete(t, stage)
    }

    // ── 内部：流获取与轨道选择 ─────────────────────────────────

    private fun fetchStream(t: Task): BiliPlayUrl.DashStream? {
        val latch = CountDownLatch(1)
        var result: BiliPlayUrl.DashStream? = null
        var err: String? = null
        val quality = if (t.type == TYPE_AUDIO) BiliPlayUrl.QUALITY_720 else t.quality
        if (t.bvid.isBlank() || t.cid <= 0L) {
            fail(t, "任务缺少分集标识，请删除后重新添加")
            return null
        }
        val request = BiliPlayUrl.fetchPlayUrl(t.bvid, t.cid, quality, forDownload = true) { stream, error ->
            result = stream
            err = error
            latch.countDown()
        }
        val limit = SystemClock.elapsedRealtime() + 30_000L
        while (!latch.await(300L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (t.cancelRequested || SystemClock.elapsedRealtime() >= limit) {
                request.cancel()
                checkNotCancelled(t)
                fail(t, "获取视频流超时")
                return null
            }
        }
        checkNotCancelled(t)
        if (result == null) { fail(t, err ?: "获取视频流失败"); return null }
        return result
    }

    private fun pickVideo(stream: BiliPlayUrl.DashStream, targetQn: Int): BiliPlayUrl.DashVideoTrack? {
        val candidates = stream.videoTracks.filter { it.id <= targetQn }.ifEmpty { stream.videoTracks }
        val bestQn = candidates.maxOfOrNull { it.id } ?: return null
        return candidates
            .filter { it.id == bestQn }
            .minWithOrNull(compareBy<BiliPlayUrl.DashVideoTrack> { codecRank(it.codecs) }
                .thenByDescending { it.bandwidth })
    }

    private fun pickAudio(stream: BiliPlayUrl.DashStream): BiliPlayUrl.DashAudioTrack? =
        stream.audioTracks
            .filter { it.codecs.startsWith("mp4a", true) }
            .maxByOrNull { it.bandwidth }
            ?: stream.audioTracks.maxByOrNull { it.bandwidth }

    private fun codecRank(codec: String): Int = when {
        codec.startsWith("avc", true) -> 0
        codec.startsWith("hev", true) || codec.startsWith("hvc", true) -> 1
        codec.startsWith("av01", true) -> 2
        else -> 3
    }

    private class CancelledException : Exception()

    private fun checkNotCancelled(t: Task) {
        if (t.cancelRequested) throw CancelledException()
    }

    /**
     * 整体下载 + 断点续传：已存在的文件从 [length] 处继续（服务端支持 Range 则 206 追加，
     * 否则 200 从头覆盖）。onProgress 回调当前文件已写字节数（不含续传起点）。
     * 多个候选 URL 依次尝试，403/截断等异常自动切换下一节点。
     */
    /**
     * 断点续传前校验已有文件头：旧版本可能把 CDN 403 错误页（HTML，几 KB）写入
     * 了文件头。媒体文件以 ftyp（mp4/m4s）、JPEG/PNG/WebP/GIF 等 magic 开头，
     * 不是这些就视为坏文件删除，避免断点续传把坏头拼进成品。
     */
    private fun discardCorruptedPart(out: File) {
        if (!out.exists() || out.length() == 0L) return
        val magic = ByteArray(12)
        try {
            RandomAccessFile(out, "r").use { raf -> raf.readFully(magic) }
        } catch (_: Exception) {
            return
        }
        val b = { i: Int -> magic[i] }
        val isFtyp = magic.size >= 8 && b(4) == 'f'.code.toByte() && b(5) == 't'.code.toByte() &&
            b(6) == 'y'.code.toByte() && b(7) == 'p'.code.toByte()
        val isJpeg = magic.size >= 3 && (b(0).toInt() and 0xFF) == 0xFF && (b(1).toInt() and 0xFF) == 0xD8 &&
            (b(2).toInt() and 0xFF) == 0xFF
        val isPng = magic.size >= 8 && b(0) == 0x89.toByte() && b(1) == 'P'.code.toByte() &&
            b(2) == 'N'.code.toByte() && b(3) == 'G'.code.toByte()
        val isWebp = magic.size >= 12 && b(0) == 'R'.code.toByte() && b(1) == 'I'.code.toByte() &&
            b(2) == 'F'.code.toByte() && b(3) == 'F'.code.toByte() && b(8) == 'W'.code.toByte()
        val isGif = magic.size >= 6 && b(0) == 'G'.code.toByte() && b(1) == 'I'.code.toByte() &&
            b(2) == 'F'.code.toByte() && b(3) == '8'.code.toByte()
        if (!(isFtyp || isJpeg || isPng || isWebp || isGif)) {
            out.delete()
        }
    }

    // 长媒体流必须显式清除从 BiliHttp.client 继承的 28 秒整次 callTimeout，
    // 否则大视频持续下载到 28 秒会被 OkHttp 强行中断；仅保留 connect/read 超时 + 暂停真实 Call 取消。
    private val downloadClient: OkHttpClient = BiliHttp.client.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun downloadFullResume(
        urls: List<String>,
        out: File,
        t: Task,
        /** 本次下载在任务总量中的起始偏移（多轨任务：视频轨完成后音频轨从 videoSize 起算）。 */
        base: Long = 0L,
        onProgress: ((Long) -> Unit)? = null
    ) {
        var lastError: Exception? = null
        val anonCookie = BiliPlayUrl.buvidCookie()
        for (u in urls.filter { it.isNotBlank() }) {
            try {
                discardCorruptedPart(out)
                val start = if (out.exists()) out.length() else 0L
                executeMediaGet(u, start, null, anonCookie, task = t).use { response ->
                    val code = response.code
                    if (code == 416 && start > 0L) {
                        val rangeSpec = MediaTransportPolicy.parseContentRange(response.header("Content-Range"))
                        if (rangeSpec != null && rangeSpec.isUnsatisfiable && rangeSpec.total == start) {
                            val absolute = base + start
                            if (absolute > 0L && absolute != t.bytesTotal) {
                                t.bytesTotal = absolute
                                save(); notifyListeners()
                            }
                            onProgress?.invoke(absolute)
                            return
                        }
                        out.delete()
                        throw IllegalStateException("续传偏移越界（HTTP 416），已重置分片")
                    }
                    requireMediaResponse(response, code)
                    val body = response.body ?: throw IOException("媒体响应体为空")
                    val contentRange = if (code == 206) {
                        val range = MediaTransportPolicy.parseContentRange(response.header("Content-Range"))
                            ?: throw IOException("206 续传缺少合法 Content-Range")
                        if (!range.isSatisfiableRange || range.start != start) throw IOException("206 续传偏移不匹配")
                        range
                    } else null
                    val append = contentRange != null && start > 0L
                    val bodyLength = body.contentLength()
                    val fullLength = if (contentRange == null) bodyLength else contentRange.total ?: -1L
                    if (fullLength > 0L) {
                        val absolute = base + fullLength
                        if (absolute != t.bytesTotal) {
                            t.bytesTotal = absolute
                            save(); notifyListeners()
                        }
                    }
                    val stream = body.byteStream()
                    val fos = FileOutputStream(out, append)
                    try {
                        val buf = ByteArray(256 * 1024)
                        var written = 0L
                        while (true) {
                            checkNotCancelled(t)
                            val n = stream.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                fos.write(buf, 0, n)
                                written += n
                                onProgress?.invoke(if (append) base + start + written else base + written)
                            }
                        }
                        fos.flush()
                        if (bodyLength >= 0L && written < bodyLength) {
                            throw IllegalStateException("响应被截断（$written/$bodyLength 字节）")
                        }
                        if (contentRange != null && !contentRange.completesOpenEndedRequest(start, written)) {
                            throw IOException("分片尚未确认完整，已保留断点（${start + written}/${contentRange.total ?: "未知"}）")
                        }
                        if (t.destination == DownloadDestination.LIBRARY) fos.fd.sync()
                        return
                    } finally {
                        fos.close()
                    }
                }
            } catch (c: CancelledException) {
                throw c
            } catch (e: Exception) {
                checkNotCancelled(t)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("无可用下载地址")
    }

    private fun downloadFullQuiet(url: String, out: File, onProgress: ((Long) -> Unit)? = null) {
        discardCorruptedPart(out)
        val anonCookie = BiliPlayUrl.buvidCookie()
        executeMediaGet(url, null, null, anonCookie).use { response ->
            val code = response.code
            requireMediaResponse(response, code)
            val body = response.body ?: throw IOException("媒体响应体为空")
            val total = body.contentLength()
            val input = body.byteStream()
            val fos = FileOutputStream(out)
            try {
                val buf = ByteArray(256 * 1024)
                var written = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        fos.write(buf, 0, n)
                        written += n
                        onProgress?.invoke(written)
                    }
                }
                fos.flush()
                if (total >= 0L && written < total) {
                    throw IllegalStateException("响应被截断（$written/$total 字节）")
                }
            } finally {
                fos.close()
            }
        }
    }

    private fun executeMediaGet(
        url: String,
        start: Long?,
        end: Long?,
        anonymousCookie: String,
        task: Task? = null
    ): Response {
        val range = if (start != null) {
            if (end != null) "bytes=$start-$end" else "bytes=$start-"
        } else {
            null
        }
        return MediaTransportPolicy.executeWithSafeRedirects(
            client = downloadClient,
            initialUrl = url,
            anonymousCookie = anonymousCookie,
            rangeHeader = range,
            onCallCreated = { call ->
                if (task != null) {
                    task.activeCall = call
                    if (task.cancelRequested) call.cancel()
                }
            }
        )
    }

    /**
     * 校验媒体响应：非 2xx（403/416/503 等）或返回 HTML/JSON 错误页时直接抛异常，
     * 由上层切换到备用 CDN 节点。这是「下载到十几 KB 假完成」问题的关键防线。
     */
    private fun requireMediaResponse(response: Response, code: Int) {
        if (code !in 200..299) {
            throw IllegalStateException("媒体服务器响应异常（HTTP $code）")
        }
        val type = response.header("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
        if (type.contains("text/html") || type.contains("application/json") || type.contains("text/plain")) {
            throw IllegalStateException("媒体服务器返回了非媒体内容（HTTP $code）")
        }
    }

    // ── 内部：状态落库 ──────────────────────────────────────────

    private fun complete(t: Task, stage: DownloadStage? = null) {
        if (t.destination == DownloadDestination.LIBRARY) {
            t.stage = checkNotNull(stage)
            saveToLibrary(t, stage, checkNotNull(downloadDir))
            return
        }
        synchronized(t) {
            checkNotCancelled(t)
            if (t.state != STATE_RUNNING) return
            t.state = STATE_COMPLETED
            t.finishedAt = System.currentTimeMillis()
            t.error = ""
            t.resumeOffset = 0L
        }
        t.resetSpeed()
        save()
        notifyListeners()
    }

    private fun fail(t: Task, message: String) {
        synchronized(lock) {
            if (tasks[t.id] !== t || t.state != STATE_RUNNING) return
            if (t.cancelRequested) {
                t.state = STATE_PAUSED
                t.cancelRequested = false
            } else {
                t.state = STATE_FAILED
                t.error = message
                t.finishedAt = System.currentTimeMillis()
            }
            t.resetSpeed()
            saveLocked()
        }
        notifyListeners()
    }

    private fun saveToLibrary(t: Task, stage: DownloadStage, dir: File) {
        val ctx = checkNotNull(appContext)
        val audio = t.type == TYPE_AUDIO
        val inputs = stage.inputs(dir, t.id)
        checkNotCancelled(t)
        if (DownloadMediaLibrary.needsPermission(ctx)) throw IOException("请授予存储权限后重试")
        require(stage.isIntact(dir, t.id)) { "下载分片不完整，已保留可恢复文件" }
        if (t.libraryNames.isEmpty()) {
            val token = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            t.libraryNames = LibraryBatch.names(t.title, t.id, token, stage.outputCount, audio)
        }
        val names = t.libraryNames
        require(names.size == stage.outputCount) { "媒体分片结构已变化，已保留原下载" }
        t.libraryLengths = emptyList()
        // 创建目标前记录稳定身份。中断后找到原条目，不重复创建公共成品。
        persistCheckpoint()
        val targets = names.mapIndexed { i, name ->
            checkNotCancelled(t)
            DownloadMediaLibrary.find(ctx, audio, t.id, name) ?: run {
                if (t.publicUris.getOrNull(i) != null) throw IOException("已登记的公共文件不在原位置，已保留下载分片")
                DownloadMediaLibrary.reserve(ctx, audio, t.id, name)
            }
        }
        t.publicUris = targets.map { it.toString() }
        persistCheckpoint()
        val sizes = try {
            targets.mapIndexed { i, uri ->
                DownloadMediaLibrary.write(ctx, uri, audio, names[i], stage, inputs, i) { checkNotCancelled(t) }
            }
        } catch (e: Exception) {
            // 写入未成功时释放半成品，保留可直接重试的原始分片。
            if (stage.isIntact(dir, t.id)) try {
                names.forEach { DownloadMediaLibrary.delete(ctx, audio, t.id, it) }
                t.publicUris = emptyList()
                persistCheckpoint()
            } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
        t.libraryLengths = sizes
        persistCheckpoint()
        if (!adoptLibraryBatch(t, stage)) throw IOException("公共文件校验失败，已保留下载分片")
    }

    /** 也用于进程恢复：只接管一整批已写完且与持久化长度一致的、不混属其它任务的公共产物。 */
    private fun adoptLibraryBatch(t: Task, stage: DownloadStage): Boolean {
        val names = t.libraryNames
        val lengths = t.libraryLengths
        if (names.size != stage.outputCount || lengths.size != names.size) return false
        val ctx = checkNotNull(appContext)
        val audio = t.type == TYPE_AUDIO
        val uris = names.map { DownloadMediaLibrary.find(ctx, audio, t.id, it) ?: return false }
        if (!LibraryBatch.canAdopt(lengths, uris.mapIndexed { i, uri ->
                DownloadMediaLibrary.stagedLength(ctx, uri, audio, names[i])
            })) return false
        checkNotCancelled(t)
        val published = DownloadMediaLibrary.publish(ctx, uris, audio, t.id, names)
        published.forEachIndexed { i, uri -> DownloadMediaLibrary.verifyLength(ctx, uri, lengths[i]) }
        t.libraryVersion = android.provider.MediaStore.getVersion(ctx).orEmpty()
        commitLibraryResult(t, published.map { it.toString() }, lengths.sum())
        cleanCommittedStage(t)
        notifyListeners()
        return true
    }

    private fun cleanCommittedStage(t: Task) {
        // 也用于重启收尾：若用户已移走文件/撤销权限，不删除仅存的可恢复输入。
        if (!t.playable()) return
        val ctx = checkNotNull(appContext)
        val lengths = t.libraryNames.map { name -> DownloadMediaLibrary.find(ctx, t.type == TYPE_AUDIO, t.id, name)?.let { DownloadMediaLibrary.length(ctx, it) } }
        if (!LibraryBatch.canAdopt(t.libraryLengths, lengths)) return
        // 即使目录 fsync 失败，也不能回滚已经 rename 成功的公共结果：保留临时轨，重启再收尾。
        syncDownloadDirectory()
        if (DownloadPrivateFiles.clean(checkNotNull(downloadDir), t.id, includeCover = false)) synchronized(lock) {
            t.cleanupPending = false
            t.stage = null
            saveLocked()
        }
    }

    private fun maybeNotify() {
        val previous = lastNotifyAt.get()
        val now = SystemClock.elapsedRealtime()
        if (now - previous >= 250L && lastNotifyAt.compareAndSet(previous, now)) notifyListeners()
    }

    private fun notifyListeners() {
        if (listeners.isNotEmpty() && deliveryPosted.compareAndSet(false, true)) main.post(deliverUpdates)
    }

    // ── 后台通知/保活 ─────────────────────────────────────────

    private fun startDownloadService(context: Context) {
        val intent = Intent(context, DownloadService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (error: IllegalStateException) {
            Log.w("Downloads", "Unable to start foreground download service", error)
        } catch (error: SecurityException) {
            Log.w("Downloads", "Foreground download service permission denied", error)
        }
    }

    /** 在独立前台服务中托管同一 DownloadManager 单例，避免 Activity 退出后进程被回收。 */
    class DownloadService : Service() {
        private val main = Handler(Looper.getMainLooper())
        private var lastStartId = 0
        @Volatile private var observing = false
        private val updatePosted = AtomicBoolean()
        private val reconcile = Runnable {
            updatePosted.set(false)
            if (!observing || lastStartId == 0) return@Runnable
            val active = snapshot().filter { it.running || it.state == STATE_QUEUED }
            val writing = synchronized(lock) { writerScheduled }
            if (active.isEmpty() && cleanupWork.get() == 0 && !writing) {
                if (stopSelfResult(lastStartId)) {
                    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
                    else @Suppress("DEPRECATION") stopForeground(true)
                }
            } else if (active.isNotEmpty()) {
                buildDownloadNotification(active)?.let { notificationManager?.notify(NOTIFICATION_ID, it) }
            } else {
                notificationManager?.notify(NOTIFICATION_ID, emptyServiceNotification(this))
            }
        }
        private fun scheduleUpdate() {
            if (observing && updatePosted.compareAndSet(false, true)) main.post(reconcile)
        }

        private val listener: (List<Task>) -> Unit = { scheduleUpdate() }

        override fun onCreate() {
            super.onCreate()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL_ID, "下载任务", NotificationManager.IMPORTANCE_LOW))
            }
            val notification = DownloadManager.emptyServiceNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            DownloadManager.init(this)
            observing = true
            addListener(listener)
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            lastStartId = startId
            scheduleUpdate()
            return START_STICKY
        }

        override fun onDestroy() {
            observing = false
            removeListener(listener)
            main.removeCallbacks(reconcile)
            super.onDestroy()
        }

        override fun onBind(intent: Intent?) = null
    }

    private fun emptyServiceNotification(context: Context): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        return builder.setSmallIcon(com.yuilittle.bili.R.drawable.ic_launcher)
            .setContentTitle("YuiBili 下载")
            .setContentText("下载服务已启动")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "下载任务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "YuiBili 后台下载进度"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notificationIntent(): PendingIntent? {
        val ctx = appContext ?: return null
        val intent = Intent(ctx, DownloadManagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
    }

    private fun buildDownloadNotification(active: List<Task>): Notification? {
        val ctx = appContext ?: return null
        val running = active.filter { it.state == STATE_RUNNING }
        val totalDone = active.sumOf { it.bytesDone.coerceAtLeast(0L) }
        val total = active.sumOf { it.bytesTotal.takeIf { b -> b > 0L } ?: 0L }
        val title = when {
            running.size == 1 -> "正在下载：${running.first().title.ifBlank { "视频" }}"
            running.isNotEmpty() -> "正在下载 ${running.size} 个视频"
            else -> "下载任务等待中"
        }
        val text = when {
            total > 0L -> "${formatBytesForNotification(totalDone)} / ${formatBytesForNotification(total)} · 最多同时 3 个"
            else -> "${active.size} 个任务 · 最多同时 3 个"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(ctx)
        }
        builder.setSmallIcon(com.yuilittle.bili.R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(notificationIntent())
            .setOngoing(running.isNotEmpty())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (total > 0L) {
            builder.setProgress(100, ((totalDone * 100L) / total).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun formatBytesForNotification(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fGB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024f / 1024f)
        bytes >= 1024L -> String.format(Locale.US, "%.0fKB", bytes / 1024f)
        else -> "${bytes}B"
    }


    private fun save() {
        synchronized(lock) { saveLocked() }
    }

    private fun saveLocked() {
        writePending = true
        if (writerScheduled) return
        writerScheduled = true
        taskIo.execute(::persistTaskChanges)
    }

    private fun persistTaskChanges() {
        try {
            val snapshot = synchronized(lock) { writePending = false; snapshotJsonLocked() }
            persistTaskSnapshot(snapshot)
        } catch (e: Exception) {
            Log.w("Downloads", "Unable to persist downloads", e)
        } finally {
            synchronized(lock) {
                if (writePending) taskIo.execute(::persistTaskChanges) else writerScheduled = false
            }
            notifyListeners()
        }
    }

    private fun snapshotJsonLocked(replacement: Task? = null): JSONObject = JSONObject().apply {
        put("tasks", JSONArray().apply { tasks.values.forEach { put(toJson(if (replacement != null && it.id == replacement.id) replacement else it)) } })
        put("removing", JSONArray().apply { removedTasks.values.forEach { put(toJson(it)) } })
    }

    // 与异步进度保存共用一个写线程，旧快照无法在提交后覆盖新的 URI/删除记录。
    private fun onTaskWriter(work: () -> Unit) {
        try { taskIo.submit { work() }.get() }
        catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception ?: e) }
    }

    private fun persistCheckpoint() = onTaskWriter {
        persistTaskSnapshot(synchronized(lock) { snapshotJsonLocked() })
        syncDownloadDirectory()
    }

    private fun commitLibraryResult(t: Task, result: List<String>, bytes: Long) = onTaskWriter {
        synchronized(lock) {
            checkNotCancelled(t)
            if (tasks[t.id] !== t || t.state != STATE_RUNNING) throw CancelledException()
            val finished = System.currentTimeMillis()
            check(result.isNotEmpty() && result.size == t.stage?.outputCount && result.distinct().size == result.size)
            fun markComplete(task: Task) {
                task.publicUris = result
                task.cleanupPending = true
                task.mpdPath = ""; task.audioPath = ""; task.singlePaths.clear()
                task.bytesDone = bytes; task.bytesTotal = bytes; task.resumeOffset = 0L
                task.finishedAt = finished; task.error = ""; task.resetSpeed()
                task.state = STATE_COMPLETED
            }
            // 只投影完成记录；写入失败时 UI 和 worker 仍持有原始的可恢复任务。
            val projected = fromJson(toJson(t)).also(::markComplete)
            persistTaskSnapshot(snapshotJsonLocked(projected))
            markComplete(t)
        }
    }

    private fun persistTaskSnapshot(snapshot: JSONObject) {
        val dir = checkNotNull(downloadDir)
        val tmp = File(dir, "tasks.json.tmp")
        // AtomicFile.finishWrite 在部分系统上仅记录 rename 失败，不向调用方报错。
        FileOutputStream(tmp).use { out ->
            out.write(snapshot.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        android.system.Os.rename(tmp.absolutePath, File(dir, "tasks.json").absolutePath)
    }

    private fun syncDownloadDirectory() {
        val fd = android.system.Os.open(checkNotNull(downloadDir).absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }

    private fun load(): Boolean {
        val dir = downloadDir ?: return false
        val base = File(dir, "tasks.json")
        try {
            val root = JSONObject(android.util.AtomicFile(base).readFully().toString(Charsets.UTF_8))
            fun records(array: JSONArray): Map<Long, Task> = buildMap {
                for (i in 0 until array.length()) {
                    val t = fromJson(array.getJSONObject(i))
                    require(t.id > 0 && put(t.id, t) == null) { "无效的下载记录" }
                }
            }
            val active = records(root.getJSONArray("tasks"))
            val removed = if (root.has("removing")) records(root.getJSONArray("removing")) else emptyMap()
            require(removed.keys.none(active::containsKey)) { "任务同时存在于下载和删除列表" }
            // 读到半份清单时绝不能启动 worker 或以空表覆盖原下载记录。
            tasks.putAll(active)
            removedTasks.putAll(removed)
            for (t in active.values) {
                if (t.state != STATE_RUNNING && t.state != STATE_QUEUED) continue
                t.state = if (t.cancelRequested || t.resumeOffset > 0L) STATE_PAUSED else STATE_QUEUED
                t.running = false
                t.cancelRequested = false
                if (t.state == STATE_QUEUED) queue.add(t.id)
            }
            return true
        } catch (_: java.io.FileNotFoundException) {
            return dir.isDirectory && dir.canWrite() && !base.exists() && !File(dir, "tasks.json.bak").exists()
        } catch (error: Exception) {
            Log.w("Downloads", "Unable to restore download index; keeping original files", error)
            return false
        }
    }

    private fun toJson(t: Task): JSONObject = JSONObject().apply {
        put("id", t.id); put("type", t.type)
        put("bvid", t.bvid); put("cid", t.cid); put("aid", t.aid)
        put("title", t.title); put("owner", t.owner)
        put("coverUrl", t.coverUrl); put("coverPath", t.coverPath)
        put("groupId", t.groupId); put("groupTitle", t.groupTitle)
        put("groupCoverUrl", t.groupCoverUrl); put("episodeNo", t.episodeNo)
        put("publishedAt", t.publishedAt)
        put("quality", t.quality); put("qualityLabel", t.qualityLabel)
        put("durationMs", t.durationMs)
        put("state", t.state)
        put("bytesDone", t.bytesDone); put("bytesTotal", t.bytesTotal)
        put("error", t.error)
        put("createdAt", t.createdAt); put("finishedAt", t.finishedAt)
        put("resumeOffset", t.resumeOffset)
        put("cancelRequested", t.cancelRequested)
        put("mpdPath", t.mpdPath); put("audioPath", t.audioPath)
        put("singlePaths", JSONArray(t.singlePaths))
        put("galleryExports", JSONArray(t.galleryExports))
        put("destination", t.destination.key)
        put("publicUris", JSONArray(t.publicUris))
        put("libraryNames", JSONArray(t.libraryNames)); put("libraryLengths", JSONArray(t.libraryLengths)); put("libraryVersion", t.libraryVersion)
        put("cleanupPending", t.cleanupPending)
        t.stage?.let { stage ->
            put("layout", stage.layout.name); put("stageLengths", JSONArray(stage.lengths))
        }
    }

    private fun fromJson(o: JSONObject): Task = Task().apply {
        id = o.optLong("id"); type = o.optInt("type")
        destination = DownloadDestination.fromKey(o.optString("destination"))
        fun strings(key: String) = o.optJSONArray(key)?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        publicUris = strings("publicUris"); libraryNames = strings("libraryNames"); libraryVersion = o.optString("libraryVersion")
        libraryLengths = o.optJSONArray("libraryLengths")?.let { a -> List(a.length()) { a.optLong(it) } }.orEmpty()
        cleanupPending = o.optBoolean("cleanupPending")
        o.optJSONArray("stageLengths")?.let { a ->
            stage = runCatching { DownloadStage(DownloadLayout.valueOf(o.optString("layout")), List(a.length()) { a.getLong(it) }) }.getOrNull()
        }
        bvid = o.optString("bvid"); cid = o.optLong("cid"); aid = o.optLong("aid")
        title = o.optString("title"); owner = o.optString("owner")
        coverUrl = o.optString("coverUrl"); coverPath = o.optString("coverPath")
        groupId = o.optString("groupId"); groupTitle = o.optString("groupTitle")
        groupCoverUrl = o.optString("groupCoverUrl"); episodeNo = o.optInt("episodeNo")
        publishedAt = o.optLong("publishedAt", 0L)
        quality = o.optInt("quality"); qualityLabel = o.optString("qualityLabel")
        durationMs = o.optLong("durationMs")
        state = o.optInt("state")
        bytesDone = o.optLong("bytesDone"); bytesTotal = o.optLong("bytesTotal")
        error = o.optString("error")
        createdAt = o.optLong("createdAt"); finishedAt = o.optLong("finishedAt")
        resumeOffset = o.optLong("resumeOffset")
        cancelRequested = o.optBoolean("cancelRequested")
        mpdPath = o.optString("mpdPath"); audioPath = o.optString("audioPath")
        val paths = o.optJSONArray("singlePaths")
        if (paths != null) for (i in 0 until paths.length()) singlePaths.add(paths.optString(i))
        val gExports = o.optJSONArray("galleryExports")
        if (gExports != null) for (i in 0 until gExports.length()) galleryExports.add(gExports.optString(i))
    }
}
