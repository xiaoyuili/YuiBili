package com.yuilittle.bili

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(UnstableApi::class)
internal object OnlinePlaybackSource {
    private val cdnSecurityInterceptor = Interceptor { chain ->
        val original = chain.request()
        val url = original.url
        if (!url.isHttps) {
            throw IOException("仅允许安全的 HTTPS 媒体连接")
        }
        val builder = original.newBuilder()
            .header("User-Agent", CDN_USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
        val anonCookie = BiliPlayUrl.cdnCookieHeader()
        if (anonCookie.isNotBlank() && MediaTransportPolicy.isTrustedCdnCookieHost(url.host)) {
            builder.header("Cookie", anonCookie)
        } else {
            builder.removeHeader("Cookie")
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val response = chain.proceed(builder.build())
        PlaybackDiagnostics.response(response, android.os.SystemClock.elapsedRealtime() - startedAt)
        validateMediaResponse(response)
        response
    }

    private fun validateMediaResponse(response: Response) {
        if (response.isRedirect) {
            val location = response.header("Location").orEmpty()
            if (location.isNotBlank()) {
                val target = response.request.url.resolve(location)
                if (target != null && !target.isHttps) {
                    response.close()
                    throw IOException("禁止从 HTTPS 降级到明文 HTTP 媒体连接")
                }
            }
            return
        }
        val contentType = response.header("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
        if (response.isSuccessful && (
                contentType.contains("text/html") ||
                    contentType.contains("application/json") ||
                    contentType.contains("text/plain")
                )
        ) {
            val code = response.code
            val headers = response.headers.toMultimap()
            response.close()
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                "媒体节点返回了非媒体内容（$contentType）",
                null,
                headers,
                DataSpec(Uri.parse(response.request.url.toString())),
                byteArrayOf()
            )
        }
    }

    // 共享 BiliHttp.client 的连接池与底层配置，但为 Media3 保留独立 Dispatcher、无限 callTimeout(0) 及 HTTPS/匿名 Cookie 安全拦截器
    private val client = BiliHttp.client.newBuilder()
        .dispatcher(okhttp3.Dispatcher())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .addNetworkInterceptor(cdnSecurityInterceptor)
        .build()

    // 限制单候选重试次数（1 次短退避），并允许超时/连接失败触发 Location Fallback
    private val failures = object : DefaultLoadErrorHandlingPolicy(2) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val http = info.exception as? HttpDataSource.InvalidResponseCodeException
            if (http != null) {
                return when (http.responseCode) {
                    401, 403, 404, 410, 416 -> C.TIME_UNSET
                    429 -> parseRetryAfterMs(http) ?: 1500L
                    in 500..599 -> if (info.errorCount <= 1) 350L else C.TIME_UNSET
                    else -> if (info.errorCount <= 1) 400L else C.TIME_UNSET
                }
            }
            if (info.exception is java.net.SocketTimeoutException ||
                info.exception is java.net.ConnectException ||
                info.exception is javax.net.ssl.SSLException ||
                info.exception is java.io.InterruptedIOException
            ) {
                return if (info.errorCount <= 1) 250L else C.TIME_UNSET
            }
            return if (info.errorCount <= 1) 350L else C.TIME_UNSET
        }

        override fun getFallbackSelectionFor(
            fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): LoadErrorHandlingPolicy.FallbackSelection? {
            val exception = loadErrorInfo.exception
            // OkHttpDataSource 会把网络超时/连接失败包成 HttpDataSourceException，
            // 裸 SocketTimeoutException 判断不到，必须按 reason 识别。
            val networkTimeout = when (exception) {
                is HttpDataSource.HttpDataSourceException ->
                    exception.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        exception.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                else -> false
            }
            val eligible = isEligibleForFallback(exception) || networkTimeout ||
                exception is java.net.SocketTimeoutException ||
                exception is java.net.ConnectException ||
                exception is java.net.UnknownHostException ||
                exception is javax.net.ssl.SSLException ||
                exception is java.io.InterruptedIOException
            if (eligible && fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)) {
                return LoadErrorHandlingPolicy.FallbackSelection(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION,
                    DEFAULT_LOCATION_EXCLUSION_MS
                )
            }
            return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
        }
    }

    private fun parseRetryAfterMs(http: HttpDataSource.InvalidResponseCodeException): Long? {
        val raw = http.headerFields["Retry-After"]?.firstOrNull()
            ?: http.headerFields["retry-after"]?.firstOrNull()
            ?: return null
        return raw.trim().toLongOrNull()?.takeIf { it in 1L..15L }?.times(1000L)
    }

    /**
     * 对 durl 段的多个 CDN 候选 URL 在数据源层依次回退（支持首包及同一偏移续读/换点重试），
     * 避免首节点 403/超时后直接报错重建整个播放器，也能自动遍历到第 3+ 候选。
     */
    private class FallbackDurlDataSource(
        private val delegateFactory: DataSource.Factory,
        private val candidates: List<String>
    ) : BaseDataSource(/* isNetwork= */ true) {
        private var currentSource: DataSource? = null
        private var currentUri: Uri? = null
        private var currentSpec: DataSpec? = null
        private var transferOpen = false
        private val reader = FallbackMediaReader(candidates.size) { index, position, length ->
            val spec = checkNotNull(currentSpec).buildUpon()
                .setUri(Uri.parse(candidates[index]))
                .setPosition(position)
                .setLength(length)
                .build()
            val source = delegateFactory.createDataSource()
            try {
                val openedLength = source.open(spec)
                currentSource = source
                currentUri = source.uri ?: spec.uri
                object : FallbackMediaReader.Source {
                    override val length = openedLength
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)
                    override fun close() {
                        try { source.close() } finally { if (currentSource === source) currentSource = null }
                    }
                }
            } catch (error: IOException) {
                runCatching { source.close() }
                throw error
            }
        }

        override fun open(dataSpec: DataSpec): Long {
            close()
            currentSpec = dataSpec
            transferInitializing(dataSpec)
            val length = reader.open(dataSpec.position, dataSpec.length)
            transferOpen = true
            transferStarted(dataSpec)
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            reader.read(buffer, offset, length).also { if (it > 0) bytesTransferred(it) }

        override fun getUri(): Uri? = currentUri

        override fun getResponseHeaders(): Map<String, List<String>> = currentSource?.responseHeaders.orEmpty()

        override fun close() {
            reader.close()
            currentUri = null
            currentSpec = null
            if (transferOpen) {
                transferOpen = false
                transferEnded()
            }
        }
    }

    /** 只播音频：输入必须是已投影为单音轨的 DASH 流；可附带媒体 ID 与元数据供通知栏展示。 */
    fun createAudioOnly(
        stream: BiliPlayUrl.DashStream,
        mediaId: String = "",
        metadata: MediaMetadata? = null,
        transferListener: androidx.media3.datasource.TransferListener? = null
    ): MediaSource? {
        if (!stream.isDash || stream.audioTracks.size != 1 || stream.videoTracks.isNotEmpty()) return null
        if (mediaId.isBlank() && metadata == null) return create(stream, transferListener).firstOrNull()
        val http = OkHttpDataSource.Factory(client)
            .setUserAgent(CDN_USER_AGENT)
            .setTransferListener(transferListener)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.bilibili.com/",
                    "Origin" to "https://www.bilibili.com"
                )
            )
        val mpd = BiliDashManifest.build(stream).toByteArray(Charsets.UTF_8)
        val manifest = DataSource.Factory { ByteArrayDataSource(mpd) }
        val builder = MediaItem.Builder()
            .setUri(Uri.parse("memory://yuibili/stream.mpd"))
            .setMimeType(MimeTypes.APPLICATION_MPD)
        if (mediaId.isNotBlank()) builder.setMediaId(mediaId)
        if (metadata != null) builder.setMediaMetadata(metadata)
        return DashMediaSource.Factory(DefaultDashChunkSource.Factory(http), manifest)
            .setLoadErrorHandlingPolicy(failures)
            .createMediaSource(builder.build())
    }

    fun create(
        stream: BiliPlayUrl.DashStream,
        transferListener: androidx.media3.datasource.TransferListener? = null
    ): List<MediaSource> {        val http = OkHttpDataSource.Factory(client)
            .setUserAgent(CDN_USER_AGENT)
            .setTransferListener(transferListener)
            .setDefaultRequestProperties(mapOf(
                "Referer" to "https://www.bilibili.com/",
                "Origin" to "https://www.bilibili.com"
            ))
        if (stream.isDash) {
            val mpd = BiliDashManifest.build(stream).toByteArray(Charsets.UTF_8)
            val manifest = DataSource.Factory { ByteArrayDataSource(mpd) }
            val item = MediaItem.Builder().setUri(Uri.parse("memory://yuibili/stream.mpd"))
                .setMimeType(MimeTypes.APPLICATION_MPD).build()
            return listOf(DashMediaSource.Factory(DefaultDashChunkSource.Factory(http), manifest)
                .setLoadErrorHandlingPolicy(failures)
                .createMediaSource(item))
        }
        require(stream.durlSegments.isNotEmpty()) { "没有可用的单文件播放地址" }
        return stream.durlSegments.map { segment ->
            val candidates = segment.urls.filter(String::isNotBlank).distinct()
            require(candidates.isNotEmpty()) { "没有可用的单文件播放地址" }
            val segFactory = DataSource.Factory { FallbackDurlDataSource(http, candidates) }
            val factory = ProgressiveMediaSource.Factory(segFactory).setLoadErrorHandlingPolicy(failures)
            val item = MediaItem.Builder().setUri(candidates.first()).setMimeType(MimeTypes.APPLICATION_MP4).build()
            factory.createMediaSource(item)
        }
    }
}
