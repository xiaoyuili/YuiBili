package com.yuilittle.bili

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object DownloadMediaLibrary {
    val legacyPermissions get() = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun needsPermission(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        (context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)

    private fun collection(audio: Boolean): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        if (audio) MediaStore.Audio.Media.getContentUri(volume) else MediaStore.Video.Media.getContentUri(volume)
    } else if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun directory(audio: Boolean) = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES

    // 仅接受 MediaStore 单条音视频记录；不接受集合 URI、文件路径、查询参数或其它 provider。
    fun itemUri(value: String, audio: Boolean): Uri? {
        val uri = Uri.parse(value)
        val id = uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        for (volume in listOf("external", "external_primary")) {
            val expected = if (audio) MediaStore.Audio.Media.getContentUri(volume) else MediaStore.Video.Media.getContentUri(volume)
            if (uri == android.content.ContentUris.withAppendedId(expected, id)) return uri
        }
        return null
    }

    fun length(context: Context, uri: Uri): Long =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: throw IOException("无法读取媒体库文件")

    @Suppress("DEPRECATION")
    private fun legacyFile(audio: Boolean, name: String): File = File(
        File(Environment.getExternalStoragePublicDirectory(directory(audio)), "YuiBili"), name)

    @Suppress("DEPRECATION")
    private fun identity(context: Context, audio: Boolean, id: Long, name: String): Pair<String, Array<String>> {
        require(LibraryBatch.validName(name, id, audio)) { "缺少可核实的下载文件标识" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?" to
                arrayOf(context.packageName, "${directory(audio)}/YuiBili/", name)
        } else "${MediaStore.MediaColumns.DATA}=?" to arrayOf(legacyFile(audio, name).absolutePath)
    }

    /** URI 行号在媒体库重建后可能复用；恢复和删除必须同时匹配本任务的目录和文件标识。 */
    @Suppress("DEPRECATION")
    fun find(context: Context, audio: Boolean, id: Long, name: String): Uri? {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) throw IOException("系统媒体库存储不可用")
        val collection = collection(audio)
        val (selection, args) = identity(context, audio, id, name)
        val withPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setIncludePending(collection) else collection
        val cursor = context.contentResolver.query(withPending, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
            ?: throw IOException("系统媒体库暂不可用")
        return cursor.use {
            if (!it.moveToFirst()) null else {
                val row = it.getLong(0)
                if (it.moveToNext()) throw IOException("媒体库文件标识重复，已保留原文件")
                android.content.ContentUris.withAppendedId(collection, row)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun reserve(context: Context, audio: Boolean, id: Long, name: String): Uri {
        require(LibraryBatch.validName(name, id, audio))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.MIME_TYPE, if (audio) "audio/mp4" else "video/mp4")
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.TITLE, name.substringBeforeLast("-yb${id.toString(36)}-"))
            if (audio) put(MediaStore.Audio.AudioColumns.IS_MUSIC, 1)
        }
        var reserved: File? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "${directory(audio)}/YuiBili/")
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        } else {
            if (needsPermission(context)) throw IOException("请授予存储权限后重试")
            val dir = File(Environment.getExternalStoragePublicDirectory(directory(audio)), "YuiBili")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建公共媒体目录")
            // 旧版不提供 IS_PENDING。先独占新文件，不会覆盖同名文件；大数据写入前必须记录其 URI。
            val file = legacyFile(audio, name)
            if (file.createNewFile()) reserved = file
            else if (!file.isFile || file.canonicalFile.parentFile != dir.canonicalFile) throw IOException("无法确认媒体文件归属")
            values.put(MediaStore.MediaColumns.DATA, file.absolutePath)
        }
        return try {
            context.contentResolver.insert(collection(audio), values) ?: throw IOException("媒体库未创建文件")
        } catch (e: Exception) {
            reserved?.delete()
            throw e
        }
    }

    private fun legacyWorkFile(audio: Boolean, name: String): File =
        legacyFile(audio, name).let { File(it.parentFile, ".${it.name}.part") }

    fun stagedLength(context: Context, uri: Uri, audio: Boolean, name: String): Long =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && legacyWorkFile(audio, name).isFile) legacyWorkFile(audio, name).length()
        else length(context, uri)

    fun write(context: Context, uri: Uri, audio: Boolean, name: String, stage: DownloadStage, inputs: List<File>, index: Int,
              checkActive: () -> Unit): Long {
        val resolver = context.contentResolver
        checkActive()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // 旧版没有 IS_PENDING，媒体字节先写同目录的隐藏文件，扫描前一次 rename。
            val target = legacyWorkFile(audio, name)
            if (target.canonicalFile != target.absoluteFile.canonicalFile.parentFile?.let { File(it, target.name) }) throw IOException("下载暂存路径无效")
            if (stage.layout == DownloadLayout.DASH) {
                mergeDashToMp4(inputs, { MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }, checkActive)
                java.io.RandomAccessFile(target, "rw").use { it.fd.sync() }
            } else {
                java.io.FileOutputStream(target).use { out ->
                    val count = copyMedia(inputs[index], out, checkActive)
                    if (count != stage.lengths[index]) throw IOException("媒体分片长度不符")
                    out.fd.sync()
                }
            }
            return target.length().takeIf { it > 0L } ?: throw IOException("公共暂存文件未写完整")
        }
        val expected = if (stage.layout == DownloadLayout.DASH) {
            val fd = resolver.openFileDescriptor(uri, "rwt") ?: throw IOException("无法打开视频库文件")
            fd.use {
                mergeDashToMp4(inputs, { MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }, checkActive)
                it.fileDescriptor.sync()
            }
            null
        } else {
            val src = inputs[index]
            val length = stage.lengths[index]
            val fd = resolver.openFileDescriptor(uri, "wt") ?: throw IOException("无法打开媒体库文件")
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
                if (copyMedia(src, out, checkActive) != length) throw IOException("媒体分片长度不符")
                out.fd.sync()
            }
            length
        }
        checkActive()
        return verifyLength(context, uri, expected)
    }

    private fun copyMedia(src: File, out: java.io.OutputStream, checkActive: () -> Unit): Long = src.inputStream().use { input ->
        val buf = ByteArray(256 * 1024)
        var copied = 0L
        while (true) {
            checkActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            copied += n
        }
        copied
    }

    fun verifyLength(context: Context, uri: Uri, expected: Long?): Long {
        val length = length(context, uri)
        if (length <= 0L || (expected != null && expected != length)) throw IOException("媒体库文件未写完整")
        return length
    }

    fun publish(context: Context, uris: List<Uri>, audio: Boolean, id: Long, names: List<String>): List<Uri> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 同一 MediaProvider 批量提交所有分片，任一行不存在时整批失败。
            val operations = ArrayList(uris.mapIndexed { i, uri ->
                val (selection, args) = identity(context, audio, id, names[i])
                android.content.ContentProviderOperation.newUpdate(uri).withSelection(selection, args)
                    .withValue(MediaStore.MediaColumns.IS_PENDING, 0).withExpectedCount(1).build()
            })
            context.contentResolver.applyBatch(MediaStore.AUTHORITY, operations)
            for (uri in uris) {
                val visible = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
                    it.moveToFirst() && !it.isNull(0) && it.getInt(0) == 0
                }
                if (visible != true) throw IOException("系统未发布媒体文件")
            }
        } else {
            val paths = uris.mapIndexed { i, uri ->
                val path = legacyPath(context, uri) ?: throw IOException("公共文件未登记")
                val dest = legacyFile(audio, names[i])
                require(File(path).canonicalFile == dest.canonicalFile) { "公共文件已移动，已保留原文件" }
                val tmp = legacyWorkFile(audio, names[i])
                if (tmp.isFile) android.system.Os.rename(tmp.absolutePath, dest.absolutePath)
                path
            }
            val latch = CountDownLatch(paths.size)
            val scanned = java.util.concurrent.ConcurrentHashMap<String, Uri>()
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), Array(paths.size) { if (audio) "audio/mp4" else "video/mp4" }) { path, found ->
                if (path != null && found != null) scanned[path] = found
                latch.countDown()
            }
            if (!latch.await(45, TimeUnit.SECONDS) || paths.any { scanned[it]?.authority != MediaStore.AUTHORITY }) {
                throw IOException("系统媒体扫描未确认，点重试保存")
            }
        }
        return names.map { find(context, audio, id, it) ?: throw IOException("系统未登记下载成品") }
    }

    @Suppress("DEPRECATION")
    private fun legacyPath(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?: throw IOException("系统媒体库暂不可用")
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun delete(context: Context, audio: Boolean, id: Long, name: String) {
        val uri = find(context, audio, id, name)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val tmp = legacyWorkFile(audio, name)
            if (tmp.exists() && !tmp.delete()) throw IOException("暂存文件清理失败")
        }
        if (uri == null) return
        val (selection, args) = identity(context, audio, id, name)
        if (context.contentResolver.delete(uri, selection, args) == 0 && find(context, audio, id, name) != null) {
            throw IOException("系统媒体库尚未移除任务文件")
        }
    }

    private fun mergeDashToMp4(inputs: List<File>, createMuxer: () -> MediaMuxer, checkActive: () -> Unit) {
        val extractors = ArrayList<MediaExtractor>()
        var muxer: MediaMuxer? = null
        try {
            val mux = createMuxer()
            muxer = mux
            val tracks = inputs.mapIndexed { index, file ->
                val e = MediaExtractor()
                extractors.add(e)
                e.setDataSource(file.absolutePath)
                val type = if (index == 0) "video/" else "audio/"
                val track = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(type) == true }
                    ?: throw IOException(if (index == 0) "无法读取视频轨" else "无法读取音频轨")
                e.selectTrack(track)
                if (e.sampleTime < 0) throw IOException("媒体轨没有样本")
                mux.addTrack(e.getTrackFormat(track))
            }
            mux.start()
            val maxInput = extractors.maxOf { e -> e.getTrackFormat(e.sampleTrackIndex).let { f ->
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            } }
            var buf = ByteBuffer.allocateDirect(maxOf(2 * 1024 * 1024, maxInput.coerceAtMost(32 * 1024 * 1024)))
            val info = MediaCodec.BufferInfo()
            while (true) {
                checkActive()
                val videoTime = extractors[0].sampleTime
                val audioTime = extractors.getOrNull(1)?.sampleTime ?: -1L
                val i = if (audioTime >= 0 && (videoTime < 0 || audioTime < videoTime)) 1 else if (videoTime >= 0) 0 else break
                val e = extractors[i]
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && e.sampleSize > buf.capacity()) {
                    if (e.sampleSize > 32 * 1024 * 1024) throw IOException("媒体样本过大")
                    buf = ByteBuffer.allocateDirect(e.sampleSize.toInt())
                }
                buf.clear()
                val n = e.readSampleData(buf, 0)
                if (n <= 0 || n > buf.capacity()) throw IOException("媒体样本不完整")
                val flags = if (e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, e.sampleTime, flags)
                mux.writeSampleData(tracks[i], buf, info)
                e.advance()
            }
            mux.stop()
        } finally {
            try { muxer?.release() } finally { extractors.forEach { it.release() } }
        }
    }
}
