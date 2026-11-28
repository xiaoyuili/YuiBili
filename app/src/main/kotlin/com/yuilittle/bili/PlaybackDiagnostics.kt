package com.yuilittle.bili

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackDiagnostics(context: Context, private val kind: String) : AnalyticsListener {
    private val enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val id = ids.incrementAndGet()
    private var generation = 0
    private var bufferStartedAt = -1L
    private var readyOnce = false
    private var lastSampleAt = 0L
    private var loadCount = 0
    private var buffering = false
    private val loadGenerations = object : LinkedHashMap<Long, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>?): Boolean = size > 32
    }

    init { debugProcess = enabled }

    fun bind(stream: BiliPlayUrl.DashStream?) {
        if (!enabled) return
        generation++
        readyOnce = false
        bufferStartedAt = -1L
        buffering = false
        lastSampleAt = 0L
        loadCount = 0
        val video = stream?.videoTracks?.firstOrNull()
        val audio = stream?.audioTracks?.firstOrNull()
        line("source dash=${stream?.isDash} qn=${stream?.actualQuality} codec=${safeCodec(video?.codecs)} " +
            "videoBps=${video?.bandwidth ?: 0} audioBps=${audio?.bandwidth ?: 0} " +
            "videoCandidates=${video?.let { 1 + it.backups.size } ?: 0} audioCandidates=${audio?.let { 1 + it.backups.size } ?: 0} " +
            "videoIndex=${video?.indexRange != null} audioIndex=${audio?.indexRange != null}")
    }

    fun sample(player: Player) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt < 5_000L) return
        lastSampleAt = now
        line("sample state=${player.playbackState} pos=${player.currentPosition} item=${player.currentMediaItemIndex} " +
            "ahead=${player.totalBufferedDuration} loading=${player.isLoading} want=${player.playWhenReady} " +
            "speed=${player.playbackParameters.speed} danmaku=${DanmakuSettingsStore.snapshot().enabled}")
    }

    fun recovering(positionMs: Long, bufferedMs: Long, retry: Int) {
        if (enabled) line("stall-recover pos=$positionMs buffered=$bufferedMs retry=$retry")
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (!enabled) return
        if (state == Player.STATE_BUFFERING) {
            if (!buffering) {
                bufferStartedAt = eventTime.realtimeMs
                line("buffer-start resumed=$readyOnce pos=${eventTime.currentPlaybackPositionMs} ahead=${eventTime.totalBufferedDurationMs}")
            }
            buffering = true
        } else {
            if (buffering) line("buffer-end next=$state elapsed=${eventTime.realtimeMs - bufferStartedAt} ahead=${eventTime.totalBufferedDurationMs}")
            buffering = false
            if (state == Player.STATE_READY) readyOnce = true
        }
    }

    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        if (!enabled) return
        loadGenerations[loadEventInfo.loadTaskId] = generation
        if (loadCount < 6 || buffering) logLoad("start", loadEventInfo, mediaLoadData)
    }

    override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        if (!enabled) return
        logLoad("cancel", loadEventInfo, mediaLoadData)
        loadGenerations.remove(loadEventInfo.loadTaskId)
    }

    override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (enabled) line("position-change reason=$reason oldItem=${oldPosition.mediaItemIndex} oldPos=${oldPosition.positionMs} " +
            "newItem=${newPosition.mediaItemIndex} newPos=${newPosition.positionMs}")
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
        if (enabled) line("player-error code=${error.errorCode} pos=${eventTime.currentPlaybackPositionMs} ahead=${eventTime.totalBufferedDurationMs}")
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        if (!enabled) return
        loadCount++
        if (loadCount <= 6 || buffering || loadCount % 10 == 0 || loadEventInfo.loadDurationMs >= 1_500L) {
            logLoad("done", loadEventInfo, mediaLoadData)
        }
        loadGenerations.remove(loadEventInfo.loadTaskId)
    }

    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        if (enabled) logLoad("error:${error.javaClass.simpleName}:cancel=$wasCanceled", loadEventInfo, mediaLoadData)
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        if (enabled) line("first-frame pos=${eventTime.currentPlaybackPositionMs} ahead=${eventTime.totalBufferedDurationMs}")
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        if (enabled) line("video-dropped count=$droppedFrames elapsed=$elapsedMs")
    }

    private fun logLoad(result: String, event: LoadEventInfo, data: MediaLoadData) {
        val headers = event.responseHeaders
        val range = headers.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
            ?.let(MediaTransportPolicy::parseContentRange)
        val mediaMs = if (data.mediaEndTimeMs != C.TIME_UNSET && data.mediaStartTimeMs != C.TIME_UNSET)
            data.mediaEndTimeMs - data.mediaStartTimeMs else -1L
        line("load result=$result loadGen=${loadGenerations[event.loadTaskId] ?: -1} loadId=${event.loadTaskId} track=${data.trackType} type=${data.dataType} " +
            "host=${event.uri.host.orEmpty()} from=${event.dataSpec.position} requested=${event.dataSpec.length} " +
            "bytes=${event.bytesLoaded} elapsed=${event.loadDurationMs} mediaMs=$mediaMs " +
            "responseStart=${range?.start ?: -1L} responseEnd=${range?.end ?: -1L}")
    }

    private fun line(message: String) { Log.i(TAG, "player=$id kind=$kind gen=$generation $message") }

    companion object {
        private const val TAG = "YuiBiliPerf"
        private val ids = AtomicInteger()
        private val httpCount = AtomicInteger()
        @Volatile private var debugProcess = false

        private fun safeCodec(codec: String?): String = codec.orEmpty().take(48).filter { it.isLetterOrDigit() || it == '.' || it == '-' }

        fun response(response: Response, elapsedMs: Long) {
            if (!debugProcess) return
            val request = response.request
            val range = request.header("Range").orEmpty().removePrefix("bytes=")
            val start = range.substringBefore('-').toLongOrNull() ?: -1L
            val end = range.substringAfter('-', "").toLongOrNull() ?: -1L
            val content = MediaTransportPolicy.parseContentRange(response.header("Content-Range"))
            val n = httpCount.incrementAndGet()
            val abnormal = !response.isSuccessful || response.code == 200 && start > 0L ||
                response.code == 206 && (content == null || content.start != start)
            if (n <= 8 || n % 10 == 0 || abnormal || elapsedMs >= 1_500L) {
                Log.i(TAG, "http host=${request.url.host} status=${response.code} headersMs=$elapsedMs from=$start to=$end " +
                    "responseStart=${content?.start ?: -1L} responseEnd=${content?.end ?: -1L} " +
                    "total=${content?.total ?: -1L} contentLength=${response.header("Content-Length")?.toLongOrNull() ?: -1L}")
            }
        }
    }
}
