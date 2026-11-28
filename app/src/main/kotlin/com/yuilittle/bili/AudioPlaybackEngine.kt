package com.yuilittle.bili

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink

/** 当前生效的曲目标识：本地下载任务或在线分集。 */
internal sealed class TrackKey {
    data class Local(val taskId: Long) : TrackKey()
    data class Online(val bvid: String, val cid: Long) : TrackKey()
}

/** 播放引擎快照，供 UI 渲染。 */
internal data class PlaybackSnapshot(
    val isOnline: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val coverUrl: String = "",
    val localTaskId: Long = 0L,
    val onlineEpisode: UgcEpisode? = null,
    val seasonTitle: String = "",
    val queueSize: Int = 0,
    val queueIndex: Int = -1,
    val durationMs: Long = 0L,
    val sortOrder: DownloadManager.SeasonSortOrder = DownloadManager.SeasonSortOrder.DEFAULT,
    val playMode: DownloadManager.LocalPlayMode = DownloadManager.LocalPlayMode.SEASON_SEQUENCE,
    val loading: Boolean = false,
    val error: String = ""
)

/** 播放引擎命令回调，避免 UI 直接持有 ExoPlayer。 */
internal interface EngineListener {
    fun onSnapshotChanged(snapshot: PlaybackSnapshot)
    fun onIsPlayingChanged(isPlaying: Boolean)
    fun onPlaybackEnded()
}

/**
 * 音频播放引擎：唯一持有 ExoPlayer 与本地/在线双队列。
 * 服务与 Activity 同进程共享同一实例，Activity 只发命令、只读状态。
 */
@UnstableApi
internal class AudioPlaybackEngine(private val context: Context) {

    val player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<EngineListener>()

    private var isOnline = false
    private var localQueue = ArrayList<DownloadManager.Task>()
    private var onlineSourceQueue = ArrayList<UgcEpisode>()
    private var onlineQueue = ArrayList<UgcEpisode>()
    private var currentLocalId = 0L
    private var currentEpisode: UgcEpisode? = null
    private var onlineLaunch: OnlineAudioLaunch? = null
    private var sortOrder = DownloadManager.SeasonSortOrder.DEFAULT
    private var playMode = DownloadManager.LocalPlayMode.SEASON_SEQUENCE
    private var pendingStartMs = 0L
    private var generation = 0
    private var request: BiliRequest? = null
    private var loading = false

    /** BUFFERING 看门狗：超过阈值仍未就绪则重置 loading 并强制重取。 */
    private val stallWatchdog = Runnable {
        if (stopped) return@Runnable
        val state = player.playbackState
        if (state != Player.STATE_BUFFERING) return@Runnable
        Log.w("YuiBiliAudio", "stall watchdog: BUFFERING 超过 ${STALL_TIMEOUT_MS}ms，强制重试")
        loading = false
        if (isOnline) {
            loadOnline(currentEpisode, 0L)
        } else {
            localQueue.firstOrNull { it.id == currentLocalId }?.let { prepareLocal(it) }
        }
    }
    private var lastError = ""
    private var stopped = false

    @Volatile
    var energyOverall = 0f
        private set
    @Volatile
    var energyBass = 0f
        private set

    init {
        val analyzer = EnergyAudioProcessor { overall, bass ->
            energyOverall = overall
            energyBass = bass
        }
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(ctx)
                .setAudioProcessors(arrayOf(analyzer))
                .build()
        }.setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(context, renderers)
            .setLoadControl(
                DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 16_000, 200, 400).build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        notifyIsPlaying(playing)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // 播放器报错必须立即复位 loading，否则 UI 永远停在加载态
                        handler.removeCallbacks(stallWatchdog)
                        loading = false
                        lastError = "播放失败：${error.errorCodeName}"
                        Log.w("YuiBiliAudio", "player error ${error.errorCodeName}: ${error.message}")
                        notifySnapshot()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> {
                                loading = false
                                handler.removeCallbacks(stallWatchdog)
                                notifySnapshot()
                            }
                            Player.STATE_BUFFERING -> {
                                handler.removeCallbacks(stallWatchdog)
                                handler.postDelayed(stallWatchdog, STALL_TIMEOUT_MS)
                            }
                            Player.STATE_ENDED -> {
                                handler.removeCallbacks(stallWatchdog)
                                notifyPlaybackEnded()
                                playNext(auto = true)
                            }
                        }
                    }
                })
            }
    }

    fun addListener(listener: EngineListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: EngineListener) {
        listeners.remove(listener)
    }

    fun setPlayMode(mode: DownloadManager.LocalPlayMode) {
        playMode = mode
    }

    fun getPlayMode(): DownloadManager.LocalPlayMode = playMode

    fun setSortOrder(order: DownloadManager.SeasonSortOrder) {
        sortOrder = order
        if (isOnline) {
            // 收藏夹队列保持原顺序，不参与排序
            if (onlineLaunch?.favorite == null) {
                onlineQueue = ArrayList(DownloadManager.sortEpisodes(onlineSourceQueue, sortOrder))
            }
        } else {
            localQueue = ArrayList(DownloadManager.sortTasks(localQueue, sortOrder))
        }
        notifySnapshot()
    }

    fun getSortOrder(): DownloadManager.SeasonSortOrder = sortOrder

    /** 本地音频入口：按合集/保存列表组队。 */
    fun startLocal(taskId: Long, sortOrderName: String, autoSave: Boolean) {
        isOnline = false
        onlineLaunch = null
        currentEpisode = null
        sortOrder = runCatching { DownloadManager.SeasonSortOrder.valueOf(sortOrderName) }
            .getOrDefault(DownloadManager.SeasonSortOrder.DEFAULT)
        val snap = DownloadManager.snapshot()
        val entry = snap.firstOrNull { it.id == taskId && it.type == DownloadManager.TYPE_AUDIO }
            ?.takeIf { it.playable() }
            ?: run {
                lastError = "本地音频文件不存在"
                notifySnapshot()
                return
            }
        localQueue = ArrayList()
        currentLocalId = entry.id
        if (entry.groupId.isNotBlank()) {
            val siblings = snap.filter {
                it.groupId == entry.groupId && it.type == DownloadManager.TYPE_AUDIO &&
                    it.playable()
            }
            localQueue.addAll(
                DownloadManager.sortTasks(siblings, sortOrder).ifEmpty { listOf(entry) }
            )
        } else {
            localQueue.add(entry)
        }
        pendingStartMs = 0L
        prepareLocal(entry)
    }

    /** 在线入口：视频交接过来，从指定毫秒继续。 */
    fun startOnline(launch: OnlineAudioLaunch) {
        isOnline = true
        onlineLaunch = launch
        currentLocalId = 0L
        localQueue = ArrayList()
        currentEpisode = launch.entry
        pendingStartMs = launch.startPositionMs.coerceAtLeast(0L)
        // 收藏夹队列优先：保持收藏夹原顺序，不应用排序
        val favorite = launch.favorite
        if (favorite != null) {
            onlineSourceQueue = ArrayList(favorite.entries)
            if (onlineSourceQueue.none { it.sameAs(launch.entry) }) {
                onlineSourceQueue.add(0, launch.entry)
            }
            onlineQueue = ArrayList(onlineSourceQueue)
        } else {
            onlineSourceQueue = ArrayList(launch.season?.episodes.orEmpty())
            if (onlineSourceQueue.none { it.sameAs(launch.entry) }) {
                onlineSourceQueue.add(0, launch.entry)
            }
            onlineQueue = ArrayList(DownloadManager.sortEpisodes(onlineSourceQueue, sortOrder))
        }
        loadOnline(launch.entry, pendingStartMs)
    }

    /** 收藏夹队列的 cid 可能为 0，优先按 bvid 比较。 */
    private fun UgcEpisode.sameAs(other: UgcEpisode): Boolean =
        if (bvid.isNotBlank() && other.bvid.isNotBlank()) bvid == other.bvid
        else if (cid > 0L) cid == other.cid
        else aid > 0L && aid == other.aid

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun prepareLocal(task: DownloadManager.Task) {
        val metadata = MediaMetadata.Builder()
            .setTitle(task.title.ifBlank { "未命名音频" })
            .setArtist(task.owner)
            .setArtworkUri(task.coverUrl.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) })
            .build()
        val sources = try {
            task.localMediaSources(context, metadata)
        } catch (_: Exception) { emptyList() }
        if (sources.isEmpty()) {
            player.stop()
            lastError = "本地音频不可用，请检查文件位置和存储权限"
            loading = false
            notifySnapshot()
            return
        }
        loading = false
        lastError = ""
        player.setMediaSources(sources, true)
        player.playWhenReady = true
        player.prepare()
        notifySnapshot()
        return
    }

    private fun loadOnline(episode: UgcEpisode?, startMs: Long) {
        val target = episode ?: return
        if (target.bvid.isBlank()) {
            lastError = "该分集暂无可用音源"
            loading = false
            notifySnapshot()
            return
        }
        // 收藏夹队列的 cid 为 0，需先拉详情补全才能取流
        if (target.cid <= 0L) {
            loading = true
            lastError = ""
            notifySnapshot()
            BiliApi.fetchDetail(target.bvid, target.aid) { item, _, error ->
                handler.post {
                    if (stopped) return@post
                    val cid = item?.cid ?: 0L
                    if (cid <= 0L) {
                        loading = false
                        lastError = error ?: "该分集暂无可用音源"
                        notifySnapshot()
                        return@post
                    }
                    val resolved = target.copy(cid = cid, cover = target.cover.ifBlank { item?.cover.orEmpty() })
                    // 同步回队列，避免后续切集重复拉详情
                    val idx = onlineQueue.indexOfFirst { it.bvid == resolved.bvid }
                    if (idx >= 0) onlineQueue[idx] = resolved
                    val srcIdx = onlineSourceQueue.indexOfFirst { it.bvid == resolved.bvid }
                    if (srcIdx >= 0) onlineSourceQueue[srcIdx] = resolved
                    loadOnline(resolved, startMs)
                }
            }
            return
        }
        request?.cancel()
        val gen = ++generation
        currentEpisode = target
        loading = true
        lastError = ""
        notifySnapshot()
        request = BiliPlayUrl.fetchPlayUrlDetailed(
            target.bvid, target.cid, BiliPlayUrl.QUALITY_360,
            allowProgressiveFallback = false,
            bypassCache = false
        ) { stream, error, _ ->
            handler.post {
                if (gen != generation || stopped) return@post
                request = null
                val audio = stream?.let { BiliPlayUrl.selectAudioOnlyStream(it) }
                if (audio == null) {
                    loading = false
                    lastError = error ?: "该视频暂无可用的在线音轨"
                    notifySnapshot()
                    return@post
                }
                val source = OnlinePlaybackSource.createAudioOnly(
                    audio,
                    "online:${target.bvid}:${target.cid}",
                    MediaMetadata.Builder()
                        .setTitle(target.title.ifBlank { "未命名音频" })
                        .setArtist(onlineLaunch?.season?.title.orEmpty())
                        .setArtworkUri(
                            target.cover.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) }
                        )
                        .build()
                )
                if (source == null) {
                    loading = false
                    lastError = "音源构建失败"
                    notifySnapshot()
                    return@post
                }
                val safeStart = startMs.coerceIn(0L, (audio.durationMs - 1L).coerceAtLeast(0L))
                player.setMediaSource(source, safeStart)
                player.playWhenReady = true
                player.prepare()
                pendingStartMs = 0L
                notifySnapshot()
            }
        }
    }

    fun play() {
        player.playWhenReady = true
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    fun pause() {
        player.playWhenReady = false
        player.pause()
    }

    /** 用户离开音频页时彻底停止：清空媒体与队列，回到空闲态。 */
    fun stopPlayback() {
        generation++
        request?.cancel()
        request = null
        loading = false
        lastError = ""
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        localQueue = ArrayList()
        onlineQueue = ArrayList()
        onlineSourceQueue = ArrayList()
        currentLocalId = 0L
        currentEpisode = null
        onlineLaunch = null
        notifySnapshot()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun playNext(auto: Boolean = false) {
        if (isOnline) {
            if (onlineQueue.isEmpty()) return
            val idx = onlineIndex()
            val target = when {
                playMode == DownloadManager.LocalPlayMode.SINGLE_LOOP && auto -> currentEpisode
                playMode == DownloadManager.LocalPlayMode.SEASON_SEQUENCE ->
                    if (idx >= 0 && idx < onlineQueue.lastIndex) onlineQueue[idx + 1] else null
                playMode == DownloadManager.LocalPlayMode.SHUFFLE -> {
                    val pool = onlineQueue.filter { currentEpisode?.let { cur -> !it.sameAs(cur) } != false }
                    pool.randomOrNull() ?: currentEpisode
                }
                else -> if (idx >= 0) onlineQueue[(idx + 1) % onlineQueue.size]
                else onlineQueue.firstOrNull()
            }
            if (target == null) {
                notifySnapshot()
                return
            }
            if (target == currentEpisode) {
                player.seekTo(0L)
                play()
                return
            }
            currentEpisode = target
            loadOnline(target, 0L)
            return
        }
        if (localQueue.isEmpty()) return
        val idx = localQueue.indexOfFirst { it.id == currentLocalId }
        val nextId = DownloadManager.resolveNextPlayableTaskId(localQueue, currentLocalId, playMode)
        if (nextId == null) {
            notifySnapshot()
            return
        }
        if (nextId == currentLocalId) {
            player.seekTo(0L)
            play()
            return
        }
        currentLocalId = nextId
        localQueue.firstOrNull { it.id == nextId }?.let { prepareLocal(it) }
    }

    fun playPrevious() {
        if (isOnline) {
            if (onlineQueue.size <= 1) return
            val idx = onlineIndex()
            if (idx < 0) return
            val target = when (playMode) {
                DownloadManager.LocalPlayMode.SEASON_SEQUENCE -> if (idx > 0) onlineQueue[idx - 1] else return
                else -> onlineQueue[(idx - 1 + onlineQueue.size) % onlineQueue.size]
            }
            currentEpisode = target
            loadOnline(target, 0L)
            return
        }
        if (localQueue.size <= 1) return
        val navMode = if (playMode == DownloadManager.LocalPlayMode.SINGLE_LOOP) {
            DownloadManager.LocalPlayMode.LIST_LOOP
        } else playMode
        val prevId = DownloadManager.resolvePrevPlayableTaskId(localQueue, currentLocalId, navMode) ?: return
        if (prevId == currentLocalId) return
        currentLocalId = prevId
        localQueue.firstOrNull { it.id == prevId }?.let { prepareLocal(it) }
    }

    fun selectLocal(taskId: Long) {
        if (isOnline || taskId == currentLocalId) return
        val task = localQueue.firstOrNull { it.id == taskId } ?: return
        currentLocalId = taskId
        prepareLocal(task)
    }

    fun selectOnline(episode: UgcEpisode) {
        if (!isOnline || currentEpisode?.sameAs(episode) == true) return
        currentEpisode = episode
        loadOnline(episode, 0L)
    }

    private fun onlineIndex(): Int =
        onlineQueue.indexOfFirst { currentEpisode?.let { cur -> it.sameAs(cur) } == true }

    fun canSkipNext(): Boolean {
        if (isOnline) {
            val idx = onlineIndex()
            return onlineQueue.size > 1 &&
                (playMode != DownloadManager.LocalPlayMode.SEASON_SEQUENCE || idx < onlineQueue.lastIndex)
        }
        val idx = localQueue.indexOfFirst { it.id == currentLocalId }
        return localQueue.size > 1 &&
            (playMode != DownloadManager.LocalPlayMode.SEASON_SEQUENCE || idx < localQueue.lastIndex)
    }

    fun canSkipPrevious(): Boolean {
        if (isOnline) {
            val idx = onlineIndex()
            return onlineQueue.size > 1 &&
                (playMode != DownloadManager.LocalPlayMode.SEASON_SEQUENCE || idx > 0)
        }
        val idx = localQueue.indexOfFirst { it.id == currentLocalId }
        return localQueue.size > 1 &&
            (playMode != DownloadManager.LocalPlayMode.SEASON_SEQUENCE || idx > 0)
    }

    /** 在线队列标题：收藏夹播放态显示收藏夹名，否则显示合集名。 */
    private fun onlineQueueTitle(): String =
        onlineLaunch?.favorite?.folderName?.takeIf { it.isNotBlank() }
            ?: onlineLaunch?.season?.title.orEmpty()

    fun snapshot(): PlaybackSnapshot {
        if (isOnline) {
            val ep = currentEpisode
            val idx = onlineIndex()
            return PlaybackSnapshot(
                isOnline = true,
                title = ep?.title.orEmpty().ifBlank { "未命名音频" },
                subtitle = onlineLaunch?.season?.title.orEmpty(),
                coverUrl = ep?.cover.orEmpty(),
                onlineEpisode = ep,
                seasonTitle = onlineLaunch?.season?.title.orEmpty(),
                queueSize = onlineQueue.size,
                queueIndex = idx,
                durationMs = ep?.duration?.toLong()?.times(1000L) ?: 0L,
                sortOrder = sortOrder,
                playMode = playMode,
                loading = loading,
                error = lastError
            )
        }
        val task = localQueue.firstOrNull { it.id == currentLocalId }
        val idx = localQueue.indexOfFirst { it.id == currentLocalId }
        return PlaybackSnapshot(
            isOnline = false,
            title = task?.title.orEmpty().ifBlank { "未命名音频" },
            subtitle = listOfNotNull(
                task?.owner?.takeIf { it.isNotBlank() },
                task?.groupTitle?.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            coverUrl = task?.coverUrl.orEmpty(),
            localTaskId = currentLocalId,
            queueSize = localQueue.size,
            queueIndex = idx,
            durationMs = task?.durationMs ?: 0L,
            sortOrder = sortOrder,
            playMode = playMode,
            loading = loading,
            error = lastError
        )
    }

    fun localQueueSnapshot(): List<DownloadManager.Task> = ArrayList(localQueue)

    fun onlineQueueSnapshot(): List<UgcEpisode> = ArrayList(onlineQueue)

    fun isOnlineMode(): Boolean = isOnline

    fun currentLocalTaskId(): Long = currentLocalId

    fun currentOnlineEpisode(): UgcEpisode? = currentEpisode

    fun positionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    fun durationMs(): Long = player.duration.takeIf { it > 0L } ?: snapshot().durationMs

    fun isPlaying(): Boolean = player.isPlaying

    fun playWhenReady(): Boolean = player.playWhenReady

    /** 释放资源，仅在服务销毁时调用。 */
    fun release() {
        stopped = true
        handler.removeCallbacks(stallWatchdog)
        generation++
        request?.cancel()
        request = null
        handler.removeCallbacksAndMessages(null)
        listeners.clear()
        player.release()
    }

    private fun notifySnapshot() {
        val snap = snapshot()
        listeners.toList().forEach { it.onSnapshotChanged(snap) }
    }

    private fun notifyIsPlaying(playing: Boolean) {
        listeners.toList().forEach { it.onIsPlayingChanged(playing) }
    }

    private fun notifyPlaybackEnded() {
        listeners.toList().forEach { it.onPlaybackEnded() }
    }

    /** 供 Session 层订阅队列变化的轻量时间戳。 */
    var lastChangeAt: Long = SystemClock.elapsedRealtime()
        private set

    fun markChanged() {
        lastChangeAt = SystemClock.elapsedRealtime()
        notifySnapshot()
    }

    private companion object {
        /** 卡在 BUFFERING 多久后强制重试。 */
        const val STALL_TIMEOUT_MS = 20_000L
    }
}
