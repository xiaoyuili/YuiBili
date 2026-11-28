package com.yuilittle.bili

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private class CompatDeferred<T> {
    private val latch = CountDownLatch(1)
    @Volatile private var value: T? = null
    @Volatile private var error: Throwable? = null

    fun complete(result: T) {
        value = result
        latch.countDown()
    }

    fun completeExceptionally(t: Throwable) {
        error = t
        latch.countDown()
    }

    @Suppress("UNCHECKED_CAST")
    fun await(): T {
        latch.await()
        error?.let { throw it }
        return value as T
    }
}

internal class BiliProtocolException(val code: Int, message: String) : IOException(message) {
    val isPermissionDenied: Boolean get() = code in setOf(-101, -400, -404, -10403, 87007, 87008)
    val isSignatureInvalid: Boolean get() = code in setOf(-3, -403)
}

internal class PlayHttpException(val statusCode: Int, message: String) : IOException(message)

/** WBI signing compatible with Bilibili's public web playurl request. */
object BiliWbiSign {
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    private data class Keys(val mixin: String, val day: Long, val version: Long)
    private val keyStateLock = Any()
    private val keyVersionSeq = AtomicLong(0L)
    @Volatile private var keys: Keys? = null
    @Volatile private var inFlightKeyRefresh: CompatDeferred<Keys>? = null
    private val illegalCharacters = Regex("[!'()*]")

    /** Returns an already URL-encoded query containing exactly one wts and one w_rid. */
    fun signParams(params: Map<String, String>): String = signParamsWithVersion(params).first

    internal fun signParamsWithVersion(params: Map<String, String>): Pair<String, Long> {
        val loaded = ensureKeyWithVersion()
        return signWithMixin(params, loaded.mixin, System.currentTimeMillis() / 1000L) to loaded.version
    }

    internal fun signWithMixin(params: Map<String, String>, mixin: String, timestampSeconds: Long): String {
        val raw = java.util.TreeMap<String, String>()
        params.forEach { (key, value) -> raw[key] = filterIllegalChars(value) }
        raw["wts"] = timestampSeconds.toString()
        val query = raw.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        val wRid = md5Hex(query + mixin)
        return "$query&w_rid=$wRid"
    }

    internal fun hasFreshKey(): Boolean = keys?.day == day()

    internal fun invalidateKey(expectedVersion: Long? = null) {
        synchronized(keyStateLock) {
            val current = keys
            if (expectedVersion == null || current == null || current.version == expectedVersion) {
                keys = null
            }
        }
    }

    private fun day(): Long = System.currentTimeMillis() / 86_400_000L

    internal fun ensureKey(): String = ensureKeyWithVersion().mixin

    private fun ensureKeyWithVersion(): Keys {
        val today = day()
        keys?.takeIf { it.day == today }?.let { return it }
        val future: CompatDeferred<Keys>
        val creator: Boolean
        synchronized(keyStateLock) {
            keys?.takeIf { it.day == today }?.let { return it }
            val existing = inFlightKeyRefresh
            if (existing != null) {
                future = existing
                creator = false
            } else {
                future = CompatDeferred()
                inFlightKeyRefresh = future
                creator = true
            }
        }
        if (creator) {
            try {
                val request = BiliHttp.request("https://api.bilibili.com/x/web-interface/nav").build()
                val root = BiliPlayUrl.bootstrapHttp.newCall(request).execute().use(BiliHttp::json)
                val images = root.optJSONObject("data")?.optJSONObject("wbi_img")
                val img = images?.optString("img_url").orEmpty().substringAfterLast('/').substringBeforeLast('.')
                val sub = images?.optString("sub_url").orEmpty().substringAfterLast('/').substringBeforeLast('.')
                if (img.length < 32 || sub.length < 32) throw IOException("未获取到播放接口签名参数")
                val raw = img + sub
                val mixin = buildString(32) {
                    for (index in 0 until 32) append(raw[MIXIN_KEY_ENC_TAB[index]])
                }
                val loaded = Keys(mixin, day(), keyVersionSeq.incrementAndGet())
                synchronized(keyStateLock) {
                    keys = loaded
                    if (inFlightKeyRefresh === future) inFlightKeyRefresh = null
                }
                future.complete(loaded)
                return loaded
            } catch (t: Throwable) {
                synchronized(keyStateLock) {
                    if (inFlightKeyRefresh === future) inFlightKeyRefresh = null
                }
                future.completeExceptionally(t)
                throw when (t) {
                    is IOException -> t
                    is RuntimeException -> t
                    else -> IOException(t.message ?: "获取签名参数失败", t)
                }
            }
        }
        return try {
            future.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("获取签名参数已中断", e)
        } catch (t: Throwable) {
            throw when (t) {
                is IOException -> t
                is RuntimeException -> t
                else -> IOException(t.message ?: "获取签名参数失败", t)
            }
        }
    }

    private fun filterIllegalChars(value: String): String = value.replace(illegalCharacters, "")

    private fun urlEncode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val c = byte.toInt() and 0xff
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code ||
                c == '.'.code || c == '~'.code
            ) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(String.format(Locale.US, "%02X", c))
            }
        }
        return out.toString()
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
    }
}

/**
 * Bilibili playurl client.
 *
 * The primary path is the normal Web/WBI DASH API. It does not manufacture
 * quality permissions: anonymous and logged-in users receive exactly the
 * tracks Bilibili returns for their session.
 */
object BiliPlayUrl {
    data class DashVideoTrack(
        val id: Int,
        val url: String,
        val backups: List<String>,
        val bandwidth: Int,
        val mimeType: String,
        val codecs: String,
        val width: Int,
        val height: Int,
        val frameRate: String,
        val initializationRange: String?,
        val indexRange: String?
    )

    /**
     * 投影出「只含一路音轨」的 DASH 流，供在线音频页使用。
     * 优先 AAC/mp4a，同类取最高带宽；无法取到音轨时返回 null。
     */
    internal fun selectAudioOnlyStream(stream: DashStream): DashStream? {
        if (!stream.isDash || stream.audioTracks.isEmpty()) return null
        val track = stream.audioTracks
            .sortedWith(
                compareByDescending<DashAudioTrack> {
                    it.codecs.contains("mp4a", ignoreCase = true) || it.mimeType.contains("mp4a", ignoreCase = true)
                }.thenByDescending { it.bandwidth }
            )
            .firstOrNull() ?: return null
        return stream.copy(
            videoTracks = emptyList(),
            audioTracks = listOf(track),
            fallbackUrls = emptyList(),
            durlSegments = emptyList()
        )
    }

    data class DashAudioTrack(
        val id: Int,
        val url: String,
        val backups: List<String>,
        val bandwidth: Int,
        val mimeType: String,
        val codecs: String,
        val initializationRange: String?,
        val indexRange: String?
    )

    data class DurlSegment(
        val urls: List<String>,
        val lengthMs: Long,
        val sizeBytes: Long
    )

    data class DashStream(
        val videoTracks: List<DashVideoTrack>,
        val audioTracks: List<DashAudioTrack>,
        val durationMs: Long,
        val actualQuality: Int,
        val acceptedQualities: List<Int>,
        val acceptedDescriptions: List<String>,
        val isDash: Boolean,
        /** Compatibility alias for the first classic DURL segment's CDN candidates. */
        val fallbackUrls: List<String> = emptyList(),
        /** Classic DURL videos may contain multiple consecutive media segments. */
        val durlSegments: List<DurlSegment> = emptyList()
    ) {
        val videoUrl: String get() = videoTracks.firstOrNull()?.url.orEmpty()
            .ifBlank { durlSegments.firstOrNull()?.urls?.firstOrNull().orEmpty() }
            .ifBlank { fallbackUrls.firstOrNull().orEmpty() }
        val audioUrl: String get() = audioTracks.firstOrNull()?.url.orEmpty()
        val qualityLabel: String get() = BiliPlayUrl.qualityLabel(actualQuality)
    }

    /** PGC 取流结果：stream + 是否试看。试看由接口 is_preview 显式判定。 */
    data class PgcPlayResult(
        val stream: DashStream,
        val isPreview: Boolean,
        val isDrm: Boolean
    )

    internal val bootstrapHttp = BiliHttp.client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()
    private val playHttp = BiliHttp.client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .build()
    // 前台播放与后台下载使用独立执行池，避免批量下载挤占起播队列。
    private val playbackQueue = LinkedBlockingQueue<Runnable>(8)
    private val playbackRequests = ThreadPoolExecutor(3, 3, 20L, TimeUnit.SECONDS, playbackQueue).apply {
        allowCoreThreadTimeOut(true)
    }
    private val downloadQueue = LinkedBlockingQueue<Runnable>(12)
    private val downloadRequests = ThreadPoolExecutor(2, 2, 20L, TimeUnit.SECONDS, downloadQueue).apply {
        allowCoreThreadTimeOut(true)
    }
    private val warmup = ThreadPoolExecutor(2, 2, 15L, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
        allowCoreThreadTimeOut(true)
    }
    private val warmingKey = AtomicBoolean()
    private val warmingFingerprint = AtomicBoolean()
    private val fingerprintStateLock = Any()
    private data class Fingerprint(val cookie: String, val expiresAt: Long)
    @Volatile private var fingerprint: Fingerprint? = null
    @Volatile private var inFlightFingerprint: CompatDeferred<String>? = null

    private data class PlayUrlCacheKey(
        val bvid: String,
        val cid: Long,
        val quality: Int,
        val fnval: Int,
        val sessionHash: Int
    )
    private data class CachedStream(val stream: DashStream, val expiresAtMs: Long)
    private const val MAX_STREAM_CACHE_TTL_MS = 20_000L
    private const val URL_DEADLINE_SAFETY_MARGIN_MS = 30_000L
    private val streamCache = ConcurrentHashMap<PlayUrlCacheKey, CachedStream>()
    private val inFlightStreams = ConcurrentHashMap<PlayUrlCacheKey, CompatDeferred<DashStream?>>()

    private fun freshFingerprint(): Fingerprint? =
        fingerprint?.takeIf { SystemClock.elapsedRealtime() < it.expiresAt }

    /** Gets the anonymous device fingerprint used by Bilibili's normal web flow (without holding lock during I/O). */
    fun buvidCookie(): String {
        freshFingerprint()?.let { return it.cookie }
        val future: CompatDeferred<String>
        val creator: Boolean
        synchronized(fingerprintStateLock) {
            freshFingerprint()?.let { return it.cookie }
            val existing = inFlightFingerprint
            if (existing != null) {
                future = existing
                creator = false
            } else {
                future = CompatDeferred()
                inFlightFingerprint = future
                creator = true
            }
        }
        if (creator) {
            val cookie = try {
                val request = BiliHttp.request("https://api.bilibili.com/x/frontend/finger/spi").build()
                val data = bootstrapHttp.newCall(request).execute().use(BiliHttp::json).optJSONObject("data")
                val b3 = data?.optString("b_3").orEmpty()
                val b4 = data?.optString("b_4").orEmpty()
                if (b3.isBlank()) "" else "buvid3=$b3; buvid4=$b4; b_nut=${System.currentTimeMillis() / 1_000L}"
            } catch (_: IOException) {
                ""
            } catch (_: org.json.JSONException) {
                ""
            }
            val ttl = if (cookie.isBlank()) 30_000L else 6L * 60 * 60_000L
            synchronized(fingerprintStateLock) {
                fingerprint = Fingerprint(cookie, SystemClock.elapsedRealtime() + ttl)
                if (inFlightFingerprint === future) inFlightFingerprint = null
            }
            future.complete(cookie)
            return cookie
        }
        return try {
            future.await()
        } catch (_: Exception) {
            ""
        }
    }

    fun cookieHeader(fnval: Int = 4048, session: String = BiliSessionStore.cookie()): String =
        cookieHeader(fnval, session, buvidCookie())

    private fun cookieHeader(fnval: Int, session: String, fingerprint: String, quality: Int = 0): String {
        val values = LinkedHashMap<String, String>()
        fun add(cookie: String) {
            cookie.split(';').forEach { part ->
                val name = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isNotBlank() && value.isNotBlank()) values[name] = value
            }
        }
        add(fingerprint)
        add(session)
        values["CURRENT_FNVAL"] = fnval.toString()
        if (quality > 0) values["CURRENT_QUALITY"] = quality.toString()
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Cookie for CDN media segment requests only. CDN hosts must NOT receive
     * CURRENT_FNVAL or SESSDATA: nodes reject requests that carry those values
     * without a valid buvid3 with 403/503. The signed URL already authorizes the
     * request, so only the anonymous buvid fingerprint is sent (or nothing at all
     * when the fingerprint could not be obtained).
     */
    fun cdnCookieHeader(): String = freshFingerprint()?.cookie.orEmpty()

    internal fun fetchPlayUrl(
        bvid: String,
        cid: Long,
        targetQuality: Int,
        allowProgressiveFallback: Boolean = false,
        forDownload: Boolean = false,
        bypassCache: Boolean = false,
        callback: (DashStream?, String?) -> Unit
    ): BiliRequest = fetchStream(bvid, cid, targetQuality, 4048, allowProgressiveFallback, forDownload, bypassCache, callback)

    /**
     * PGC（番剧/电影）取流：走 pgc/player/web/playurl。
     * 试看由 result.is_preview 显式判定；试看时 timelength 是正片全长，
     * 因此可播时长改用 durl 分段实际长度，避免进度条显示 2 小时却只能播 6 分钟。
     */
    internal fun fetchPgcPlayUrl(
        bvid: String,
        cid: Long,
        targetQuality: Int,
        callback: (PgcPlayResult?, String?) -> Unit
    ): BiliRequest {
        require(bvid.isNotBlank() && cid > 0L)
        val request = BiliRequest()
        val session = BiliSessionStore.cookie()
        val runnable = Runnable {
            if (request.cancelled) return@Runnable
            try {
                val params = linkedMapOf(
                    "bvid" to bvid,
                    "cid" to cid.toString(),
                    "qn" to targetQuality.toString(),
                    "fnval" to "4048",
                    "fnver" to "0",
                    "fourk" to "1"
                )
                val query = BiliWbiSign.signParams(params)
                val url = "https://api.bilibili.com/pgc/player/web/playurl?$query"
                val call = playHttp.newCall(
                    BiliHttp.request(url).apply {
                        if (session.isNotBlank()) header("Cookie", session)
                        header("Referer", "https://www.bilibili.com/")
                    }.build()
                )
                if (!request.attach(call)) return@Runnable
                val root = call.execute().use(BiliHttp::json)
                val code = root.optInt("code", -1)
                if (code != 0) {
                    callback(null, root.optString("message").ifBlank { "取流失败（$code）" })
                    return@Runnable
                }
                val data = root.optJSONObject("result")
                if (data == null) {
                    callback(null, "没有返回播放地址")
                    return@Runnable
                }
                val isPreview = data.optInt("is_preview", 0) == 1
                val isDrm = data.optBoolean("is_drm", false)
                val stream = parsePgcStream(data, isPreview)
                if (stream == null) {
                    callback(null, "当前内容没有可播放的视频流")
                    return@Runnable
                }
                callback(PgcPlayResult(stream, isPreview, isDrm), null)
            } catch (e: IOException) {
                if (!request.cancelled) callback(null, e.message ?: "网络请求失败")
            } catch (_: org.json.JSONException) {
                if (!request.cancelled) callback(null, "播放接口返回的数据无效")
            }
        }
        request.onCancel { playbackQueue.remove(runnable) }
        try {
            playbackRequests.execute(runnable)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            callback(null, "播放请求较多，请稍后重试")
        }
        return request
    }

    /** PGC 流解析：试看用 durl 分段实际长度当可播时长，正片沿用 timelength。 */
    private fun parsePgcStream(data: JSONObject, isPreview: Boolean): DashStream? {
        val accepted = intList(data.optJSONArray("accept_quality"))
        val descriptions = stringList(data.optJSONArray("accept_description"))
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val videos = parseVideoTracks(dash.optJSONArray("video"))
            val audios = parseAudioTracks(dash.optJSONArray("audio"))
            if (videos.isNotEmpty()) {
                val durationMs = data.optLong("timelength", 0L).takeIf { it > 0L }
                    ?: dash.optLong("duration", 0L).takeIf { it > 0L }?.times(1000L)
                    ?: 0L
                if (durationMs <= 0L) return null
                return DashStream(
                    videoTracks = videos, audioTracks = audios, durationMs = durationMs,
                    actualQuality = videos.maxOfOrNull { it.id } ?: data.optInt("quality", 0),
                    acceptedQualities = accepted, acceptedDescriptions = descriptions, isDash = true
                )
            }
        }
        // 试看是 MP4 durl 分段；用分段长度之和作为实际可播时长
        val durls = data.optJSONArray("durl")
        val segments = if (durls != null) parseDurlSegments(durls) else emptyList()
        if (segments.isEmpty()) return null
        val segmentDuration = segments.sumOf { it.lengthMs }.takeIf { it > 0L }
        val durationMs = if (isPreview) {
            segmentDuration ?: 0L
        } else {
            data.optLong("timelength", 0L).takeIf { it > 0L } ?: segmentDuration ?: 0L
        }
        if (durationMs <= 0L) return null
        return DashStream(
            videoTracks = emptyList(), audioTracks = emptyList(), durationMs = durationMs,
            actualQuality = data.optInt("quality", QUALITY_360),
            acceptedQualities = accepted, acceptedDescriptions = descriptions, isDash = false,
            fallbackUrls = segments.first().urls, durlSegments = segments
        )
    }

    internal fun fetchDurlPlayUrl(
        bvid: String,
        cid: Long,
        targetQuality: Int,
        forDownload: Boolean = false,
        bypassCache: Boolean = false,
        callback: (DashStream?, String?) -> Unit
    ): BiliRequest = fetchStream(bvid, cid, targetQuality, 0, false, forDownload, bypassCache, callback)

    private fun fetchStream(
        bvid: String,
        cid: Long,
        quality: Int,
        fnval: Int,
        allowProgressiveFallback: Boolean,
        forDownload: Boolean,
        bypassCache: Boolean,
        callback: (DashStream?, String?) -> Unit
    ): BiliRequest = fetchStreamDetailed(
        bvid = bvid,
        cid = cid,
        quality = quality,
        fnval = fnval,
        allowProgressiveFallback = allowProgressiveFallback,
        forDownload = forDownload,
        bypassCache = bypassCache
    ) { stream, error, _ -> callback(stream, error) }

    internal fun fetchPlayUrlDetailed(
        bvid: String,
        cid: Long,
        targetQuality: Int,
        allowProgressiveFallback: Boolean = false,
        forDownload: Boolean = false,
        bypassCache: Boolean = false,
        callback: (DashStream?, String?, Boolean) -> Unit
    ): BiliRequest = fetchStreamDetailed(
        bvid = bvid,
        cid = cid,
        quality = targetQuality,
        fnval = 4048,
        allowProgressiveFallback = allowProgressiveFallback,
        forDownload = forDownload,
        bypassCache = bypassCache,
        callback = callback
    )

    private fun fetchStreamDetailed(
        bvid: String,
        cid: Long,
        quality: Int,
        fnval: Int,
        allowProgressiveFallback: Boolean,
        forDownload: Boolean,
        bypassCache: Boolean,
        callback: (DashStream?, String?, Boolean) -> Unit
    ): BiliRequest {
        require(bvid.isNotBlank() && cid > 0L)
        val request = BiliRequest()
        val session = BiliSessionStore.cookie()
        val key = PlayUrlCacheKey(bvid, cid, quality, fnval, session.hashCode())
        if (!bypassCache) {
            val nowMs = System.currentTimeMillis()
            val cached = streamCache[key]
            if (cached != null && nowMs < cached.expiresAtMs && !isStreamNearExpiry(cached.stream, nowMs)) {
                callback(cached.stream, null, false)
                return request
            } else if (cached != null) {
                streamCache.remove(key, cached)
            }
        } else {
            streamCache.remove(key)
        }
        prewarm()
        val executor = if (forDownload) downloadRequests else playbackRequests
        val queue = if (forDownload) downloadQueue else playbackQueue
        val runnable = Runnable {
            if (request.cancelled) return@Runnable
            val stream = try {
                loadCoalescedStream(request, key, bvid, cid, quality, fnval, allowProgressiveFallback, session, bypassCache)
            } catch (error: BiliProtocolException) {
                if (!request.cancelled) callback(null, error.message ?: "播放接口错误", error.isPermissionDenied)
                return@Runnable
            } catch (error: IOException) {
                if (!request.cancelled) callback(null, error.message ?: "网络请求失败", false)
                return@Runnable
            } catch (_: org.json.JSONException) {
                if (!request.cancelled) callback(null, "播放接口返回的数据无效", false)
                return@Runnable
            }
            if (!request.cancelled) {
                when {
                    session != BiliSessionStore.cookie() -> callback(null, "登录状态已变化，请重试", false)
                    stream == null -> callback(null, "当前账号没有可用的视频流", false)
                    else -> callback(stream, null, false)
                }
            }
        }
        request.onCancel { queue.remove(runnable) }
        try {
            executor.execute(runnable)
        } catch (_: RejectedExecutionException) {
            callback(null, "播放请求较多，请稍后重试", false)
        }
        return request
    }

    private fun loadCoalescedStream(
        request: BiliRequest,
        key: PlayUrlCacheKey,
        bvid: String,
        cid: Long,
        quality: Int,
        fnval: Int,
        allowProgressiveFallback: Boolean,
        session: String,
        bypassCache: Boolean
    ): DashStream? {
        if (bypassCache) {
            val fresh = requestStreamWithRetry(request, bvid, cid, quality, fnval, session)
                ?: if (allowProgressiveFallback && !request.cancelled) {
                    requestStreamWithRetry(request, bvid, cid, minOf(quality, QUALITY_1080), 0, session)
                } else null
            if (fresh != null) cacheStream(key, fresh)
            return fresh
        }
        val existing = inFlightStreams[key]
        if (existing != null) {
            val shared = awaitSharedStream(existing)
            if (shared != null || request.cancelled) return shared
        }
        val created = CompatDeferred<DashStream?>()
        val winner = inFlightStreams.putIfAbsent(key, created)
        if (winner != null) {
            val shared = awaitSharedStream(winner)
            if (shared != null || request.cancelled) return shared
            // 若在途请求失败，则本请求可回退直连再试一次
        }
        try {
            val stream = requestStreamWithRetry(request, bvid, cid, quality, fnval, session)
                ?: if (allowProgressiveFallback && !request.cancelled) {
                    requestStreamWithRetry(request, bvid, cid, minOf(quality, QUALITY_1080), 0, session)
                } else null
            if (stream != null) cacheStream(key, stream)
            created.complete(stream)
            return stream
        } catch (t: Throwable) {
            created.completeExceptionally(t)
            throw t
        } finally {
            inFlightStreams.remove(key, created)
        }
    }

    private fun awaitSharedStream(future: CompatDeferred<DashStream?>): DashStream? = try {
        future.await()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("播放请求已中断", e)
    } catch (t: Throwable) {
        throw when (t) {
            is IOException -> t
            is org.json.JSONException -> t
            is RuntimeException -> t
            else -> IOException(t.message ?: "播放请求失败", t)
        }
    }

    private fun cacheStream(key: PlayUrlCacheKey, stream: DashStream) {
        val now = System.currentTimeMillis()
        val deadlineMs = earliestUrlDeadlineMs(stream)
        val ttlEnd = if (deadlineMs != null) {
            minOf(now + MAX_STREAM_CACHE_TTL_MS, deadlineMs - URL_DEADLINE_SAFETY_MARGIN_MS)
        } else {
            now + MAX_STREAM_CACHE_TTL_MS
        }
        if (ttlEnd > now) {
            streamCache[key] = CachedStream(stream, ttlEnd)
        }
    }

    internal fun isStreamNearExpiry(stream: DashStream, nowMs: Long = System.currentTimeMillis()): Boolean {
        val deadline = earliestUrlDeadlineMs(stream) ?: return false
        return nowMs >= deadline - URL_DEADLINE_SAFETY_MARGIN_MS
    }

    internal fun earliestUrlDeadlineMs(stream: DashStream): Long? {
        val urls = buildList {
            stream.videoTracks.forEach { add(it.url); addAll(it.backups) }
            stream.audioTracks.forEach { add(it.url); addAll(it.backups) }
            stream.durlSegments.forEach { addAll(it.urls) }
            addAll(stream.fallbackUrls)
        }
        var minDeadline: Long? = null
        for (raw in urls) {
            val httpUrl = raw.toHttpUrlOrNull() ?: continue
            val sec = httpUrl.queryParameter("deadline")?.toLongOrNull()
                ?: httpUrl.queryParameter("expires")?.toLongOrNull()
                ?: continue
            if (sec > 1_000_000_000L) {
                val ms = sec * 1000L
                minDeadline = if (minDeadline == null) ms else minOf(minDeadline, ms)
            }
        }
        return minDeadline
    }

    private fun requestStreamWithRetry(
        request: BiliRequest,
        bvid: String,
        cid: Long,
        quality: Int,
        fnval: Int,
        session: String
    ): DashStream? {
        return try {
            requestStream(request, bvid, cid, quality, fnval, session)
        } catch (error: BiliProtocolException) {
            if (error.isSignatureInvalid && !request.cancelled) {
                requestStream(request, bvid, cid, quality, fnval, session)
            } else {
                throw error
            }
        }
    }

    private fun requestStream(request: BiliRequest, bvid: String, cid: Long, quality: Int,
                              fnval: Int, session: String): DashStream? {
        val params = linkedMapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "qn" to quality.toString(),
            "fnval" to fnval.toString(),
            "fnver" to "0",
            "fourk" to "1",
            "gaia_source" to "pre-load",
            "isGaiaAvoided" to "true",
            "web_location" to "1315873"
        )
        if (fnval != 0) params["voice_balance"] = "1"
        if (!BiliSessionStore.isLoggedIn(session)) params["try_look"] = "1"
        val fingerprintCookie = buvidCookie()
        if (request.cancelled) return null
        val (query, keyVersion) = BiliWbiSign.signParamsWithVersion(params)
        if (request.cancelled) return null
        val cookie = cookieHeader(fnval, session, fingerprintCookie, quality)
        val call = playHttp.newCall(BiliHttp.request("https://api.bilibili.com/x/player/wbi/playurl?$query")
            .header("User-Agent", CDN_USER_AGENT)
            .header("Origin", "https://www.bilibili.com")
            .header("Cookie", cookie)
            .build())
        if (!request.attach(call)) return null
        try {
            return call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw PlayHttpException(response.code, "播放网络响应异常（HTTP ${response.code}）")
                }
                val root = BiliHttp.json(response)
                val code = root.optInt("code", -1)
                if (code != 0) {
                    if (code in setOf(-3, -403)) BiliWbiSign.invalidateKey(keyVersion)
                    throw BiliProtocolException(code, readablePlayError(code, root.optString("message")))
                }
                val data = root.optJSONObject("data")
                val parsed = data?.let { parseStream(it, includeDash = fnval != 0) }
                if (data != null && parsed != null) {
                    val tracksSummary = parsed.videoTracks.joinToString(",") {
                        "${it.id}:${it.width}x${it.height}:${it.codecs.substringBefore('.')}"
                    }
                    android.util.Log.i(
                        "YuiBiliPlayUrl",
                        "playurl reqQn=$quality fnval=$fnval loggedIn=${BiliSessionStore.isLoggedIn(session)} " +
                            "respQuality=${data.optInt("quality", -1)} accept=${parsed.acceptedQualities} " +
                            "dashTracks=[$tracksSummary] durl=${parsed.durlSegments.size}"
                    )
                }
                parsed
            }
        } finally {
            request.detach(call)
        }
    }

    internal fun selectDashStreamTrack(
        stream: DashStream,
        requestedQuality: Int,
        excludedTracks: Set<Pair<Int, String>> = emptySet()
    ): DashStream {
        if (!stream.isDash || stream.videoTracks.isEmpty()) return stream
        val activeTracks = stream.videoTracks.filterNot { (it.id to it.codecs) in excludedTracks }
            .ifEmpty { stream.videoTracks }
        val selectedQuality = if (requestedQuality == PreferredVideoQuality.HIGHEST) activeTracks.maxOf { it.id }
            else activeTracks.filter { it.id <= requestedQuality }.maxOfOrNull { it.id } ?: activeTracks.minOf { it.id }
        val qualityTracks = activeTracks.filter { it.id == selectedQuality }
        val video = qualityTracks.minWithOrNull(
            compareBy<DashVideoTrack> { codecStabilityRank(it.codecs) }
                .thenByDescending { it.bandwidth }
        ) ?: activeTracks.first()
        val audio = stream.audioTracks
            .filter { it.codecs.startsWith("mp4a", true) }
            .maxByOrNull { it.bandwidth }
            ?: stream.audioTracks.maxByOrNull { it.bandwidth }
        return stream.copy(
            videoTracks = listOf(video),
            audioTracks = listOfNotNull(audio),
            actualQuality = video.id
        )
    }

    private fun parseStream(data: JSONObject, includeDash: Boolean): DashStream? {
        val accepted = intList(data.optJSONArray("accept_quality"))
        val descriptions = stringList(data.optJSONArray("accept_description"))
        val dash = if (includeDash) data.optJSONObject("dash") else null
        if (dash != null) {
            val videos = parseVideoTracks(dash.optJSONArray("video"))
            val audios = parseAudioTracks(dash.optJSONArray("audio"))
            if (videos.isNotEmpty()) {
                val timeLengthMs = data.optLong("timelength", 0L).takeIf { it > 0L }
                val dashDurationMs = dash.optLong("duration", 0L).takeIf { it > 0L }?.times(1000L)
                val durationMs = timeLengthMs ?: dashDurationMs ?: 0L
                if (durationMs <= 0L) return null
                return DashStream(
                    videoTracks = videos,
                    audioTracks = audios,
                    durationMs = durationMs,
                    actualQuality = videos.maxOfOrNull { it.id } ?: data.optInt("quality", 0),
                    acceptedQualities = accepted,
                    acceptedDescriptions = descriptions,
                    isDash = true
                )
            }
        }

        // Some old/limited videos expose only durl. Keep this as a standards-compliant fallback.
        val durls = data.optJSONArray("durl") ?: return null
        val segments = parseDurlSegments(durls)
        if (segments.isEmpty()) return null
        val durationMs = data.optLong("timelength", 0L).takeIf { it > 0L }
            ?: segments.sumOf { it.lengthMs }.takeIf { it > 0L }
            ?: return null
        return DashStream(
            videoTracks = emptyList(), audioTracks = emptyList(),
            durationMs = durationMs,
            actualQuality = data.optInt("quality", QUALITY_360),
            acceptedQualities = accepted,
            acceptedDescriptions = descriptions,
            isDash = false,
            fallbackUrls = segments.first().urls,
            durlSegments = segments
        )
    }

    private fun parseDurlSegments(array: JSONArray): List<DurlSegment> {
        val segments = ArrayList<DurlSegment>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val candidates = ArrayList<String>()
            mediaUrl(item.optString("url"))?.let { candidates += it }
            val backups = item.optJSONArray("backup_url") ?: item.optJSONArray("backupUrl")
            if (backups != null) for (i in 0 until backups.length()) {
                val backup = mediaUrl(backups.optString(i)) ?: continue
                if (backup !in candidates) candidates += backup
            }
            if (candidates.isNotEmpty()) {
                segments += DurlSegment(
                    urls = candidates,
                    lengthMs = item.optLong("length", 0L).coerceAtLeast(0L),
                    sizeBytes = item.optLong("size", 0L).coerceAtLeast(0L)
                )
            }
        }
        return segments
    }

    private fun parseVideoTracks(array: JSONArray?): List<DashVideoTrack> {
        if (array == null) return emptyList()
        val result = ArrayList<DashVideoTrack>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = mediaUrl(firstString(item, "baseUrl", "base_url")) ?: continue
            val segment = item.optJSONObject("segment_base") ?: item.optJSONObject("segmentBase") ?: item.optJSONObject("SegmentBase")
            val initRange = segment?.let { firstString(it, "initialization", "Initialization").takeIf(String::isNotBlank) }
            val indexRange = segment?.let { firstString(it, "index_range", "indexRange").takeIf(String::isNotBlank) }
            if (initRange != null && MediaTransportPolicy.parseByteRangeSpec(initRange) == null) continue
            if (indexRange != null && MediaTransportPolicy.parseByteRangeSpec(indexRange) == null) continue
            result += DashVideoTrack(
                id = item.optInt("id", 0),
                url = url,
                backups = backupUrls(item),
                bandwidth = item.optInt("bandwidth", 0).coerceAtLeast(0),
                mimeType = firstString(item, "mime_type", "mimeType").ifBlank { "video/mp4" },
                codecs = item.optString("codecs", "avc1"),
                width = item.optInt("width", 0).coerceAtLeast(0),
                height = item.optInt("height", 0).coerceAtLeast(0),
                frameRate = firstString(item, "frame_rate", "frameRate"),
                initializationRange = initRange,
                indexRange = indexRange
            )
        }
        return result.sortedWith(compareByDescending<DashVideoTrack> { it.id }.thenBy { codecRank(it.codecs) })
    }

    private fun parseAudioTracks(array: JSONArray?): List<DashAudioTrack> {
        if (array == null) return emptyList()
        val result = ArrayList<DashAudioTrack>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = mediaUrl(firstString(item, "baseUrl", "base_url")) ?: continue
            val segment = item.optJSONObject("segment_base") ?: item.optJSONObject("segmentBase") ?: item.optJSONObject("SegmentBase")
            val initRange = segment?.let { firstString(it, "initialization", "Initialization").takeIf(String::isNotBlank) }
            val indexRange = segment?.let { firstString(it, "index_range", "indexRange").takeIf(String::isNotBlank) }
            if (initRange != null && MediaTransportPolicy.parseByteRangeSpec(initRange) == null) continue
            if (indexRange != null && MediaTransportPolicy.parseByteRangeSpec(indexRange) == null) continue
            result += DashAudioTrack(
                id = item.optInt("id", 0),
                url = url,
                backups = backupUrls(item),
                bandwidth = item.optInt("bandwidth", 0).coerceAtLeast(0),
                mimeType = firstString(item, "mime_type", "mimeType").ifBlank { "audio/mp4" },
                codecs = item.optString("codecs", "mp4a.40.2"),
                initializationRange = initRange,
                indexRange = indexRange
            )
        }
        return result.sortedByDescending { it.bandwidth }
    }

    private fun backupUrls(item: JSONObject): List<String> {
        val array = item.optJSONArray("backupUrl") ?: item.optJSONArray("backup_url") ?: return emptyList()
        val result = ArrayList<String>()
        for (index in 0 until array.length()) {
            val url = mediaUrl(array.optString(index)) ?: continue
            if (url !in result) result += url
        }
        return result
    }

    private fun mediaUrl(value: String): String? {
        val url = if (value.startsWith("//")) "https:$value" else value
        return url.takeIf { it.toHttpUrlOrNull() != null }
    }

    private fun firstString(item: JSONObject, vararg names: String): String {
        for (name in names) item.optString(name).takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    private fun intList(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).toIntOrNull() ?: array.optInt(index).takeIf { it > 0 }
        }.distinct().sortedDescending()
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }

    internal fun codecRank(codec: String): Int = codecStabilityRank(codec)

    private fun codecStabilityRank(codec: String): Int = when {
        codec.startsWith("avc", true) -> 0
        codec.startsWith("hev", true) || codec.startsWith("hvc", true) -> 1
        codec.startsWith("av01", true) -> 2
        else -> 3
    }

    private fun readablePlayError(code: Int, message: String): String = when (code) {
        -101, -400 -> "请先按哔哩哔哩要求登录后再播放该画质（$message）"
        -10403, -404 -> "当前画质需要登录或大会员，已按站方权限处理（$message）"
        else -> "播放接口错误 $code${message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}"
    }

    /**
     * Pre-warm WBI keys and buvid cookie so the first fetchPlayUrl call
     * doesn't have to wait for these two extra HTTP round-trips.
     * Safe to call from any thread; results are cached internally.
     */
    fun prewarm() {
        if (!BiliWbiSign.hasFreshKey() && warmingKey.compareAndSet(false, true)) {
            warmup.execute {
                try {
                    BiliWbiSign.ensureKey()
                } catch (_: IOException) {
                    // The playback request reports an error if the key still cannot be loaded.
                } catch (_: org.json.JSONException) {
                } finally {
                    warmingKey.set(false)
                }
            }
        }
        if (freshFingerprint() == null && warmingFingerprint.compareAndSet(false, true)) {
            warmup.execute {
                try {
                    buvidCookie()
                } finally {
                    warmingFingerprint.set(false)
                }
            }
        }
    }

    fun qualityLabel(id: Int): String = when (id) {
        127 -> "8K"
        126 -> "杜比视界"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P+"
        80 -> "1080P"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> if (id > 0) "${id}档" else "未知画质"
    }

    const val QUALITY_360 = 16
    const val QUALITY_480 = 32
    const val QUALITY_720 = 64
    const val QUALITY_1080 = 80
    const val QUALITY_1080_PLUS = 112
    const val QUALITY_4K = 120
}
