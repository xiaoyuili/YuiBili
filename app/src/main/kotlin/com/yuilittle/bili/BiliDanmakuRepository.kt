package com.yuilittle.bili

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * 弹幕网络与内存缓存仓库：
 * - 主路径走 /x/v2/dm/wbi/web/seg.so，可选拉取 /x/v2/dm/web/view 元数据，
 *   并在明确接口不兼容时提供单次有界 XML 兼容回退。
 * - 每段独立 BiliRequest，支持在途合并、统一尝试预算、风控共享冷却与带字节/条数/段数上限的全局 LRU。
 */
internal object BiliDanmakuRepository {

    private const val TAG = "YuiBiliDanmaku"

    private const val MAX_CACHE_BYTES = 12 * 1024 * 1024
    private const val MAX_CACHE_SEGMENTS = 6
    private const val MAX_CACHE_ITEMS = 40_000
    private const val NON_EMPTY_TTL_MS = 10 * 60_000L
    private const val EMPTY_TTL_MS = 60_000L
    private const val FAILURE_COOLDOWN_MS = 30_000L
    private const val BASE_RISK_COOLDOWN_MS = 60_000L
    private const val MAX_RISK_COOLDOWN_MS = 5 * 60_000L

    private const val MAX_LOGIC_ATTEMPTS = 3

    enum class SourceKind { PROTO_SEG, XML_FALLBACK }

    data class SegmentData(
        val cid: Long,
        val segmentIndex: Int,
        val items: List<DanmakuItem>,
        val source: SourceKind,
        val closed: Boolean,
        val fetchedAtMs: Long,
        val estimatedBytes: Int
    )

    sealed class FetchOutcome {
        data class Ready(val segment: SegmentData) : FetchOutcome()
        data class Empty(val segment: SegmentData) : FetchOutcome()
        object Closed : FetchOutcome()
        data class AuthOrPermission(val httpCode: Int, val biliCode: Int) : FetchOutcome()
        data class RiskLimited(val cooldownUntilMs: Long, val httpCode: Int, val biliCode: Int) : FetchOutcome()
        data class Failed(val reason: String, val retryable: Boolean) : FetchOutcome()
    }

    private data class CacheKey(
        val cid: Long,
        val segmentIndex: Int,
        val authEpoch: Long
    )

    private data class MetaKey(val cid: Long, val authEpoch: Long)

    private data class CachedMeta(
        val meta: DanmakuViewMeta,
        val fetchedAtMs: Long
    )

    private class InFlightEntry(
        val identity: Long,
        val request: BiliRequest,
        val isPrefetch: Boolean
    ) {
        private val callbacks = ArrayList<(FetchOutcome) -> Unit>()
        @Volatile var finished = false

        @Synchronized
        fun addCallback(cb: (FetchOutcome) -> Unit): Boolean {
            if (finished) return false
            callbacks.add(cb)
            return true
        }

        @Synchronized
        fun removeCallback(cb: (FetchOutcome) -> Unit): Int {
            callbacks.remove(cb)
            return callbacks.size
        }

        @Synchronized
        fun complete(outcome: FetchOutcome): List<(FetchOutcome) -> Unit> {
            finished = true
            val list = ArrayList(callbacks)
            callbacks.clear()
            return list
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerSeq = AtomicInteger(1)
    private val requestIdSeq = AtomicLong(1L)
    private val authEpochSeq = AtomicLong(1L)
    @Volatile private var lastObservedSession: String? = null

    private val danmakuClient: OkHttpClient = BiliHttp.client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    // 2 个专用 worker + 有界队列 6，避免挤占视频取流线程，绝不使用 CallerRunsPolicy
    private val workers = ThreadPoolExecutor(
        2, 2,
        20L, TimeUnit.SECONDS,
        LinkedBlockingQueue(6)
    ) { r ->
        Thread(r, "yui-danmaku-${workerSeq.getAndIncrement()}").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    private val cacheLock = Any()
    private val segmentCache = LinkedHashMap<CacheKey, SegmentData>(16, 0.75f, true)
    private var cachedBytes = 0
    private var cachedItems = 0

    private val metaCache = LinkedHashMap<MetaKey, CachedMeta>(8, 0.75f, true)
    private val inFlight = HashMap<CacheKey, InFlightEntry>()
    private val failedUntil = HashMap<CacheKey, Long>()
    private val xmlAttempted = HashSet<MetaKey>()

    @Volatile private var riskCooldownUntilMs = 0L
    @Volatile private var consecutiveRiskHits = 0

    /** 当前登录态对应的隔离版本号；账号变化时递增并清理旧缓存。 */
    fun currentAuthEpoch(): Long {
        val session = BiliSessionStore.cookie()
        val prev = lastObservedSession
        if (prev == null) {
            synchronized(cacheLock) {
                if (lastObservedSession == null) lastObservedSession = session
            }
            return authEpochSeq.get()
        }
        if (prev != session) {
            synchronized(cacheLock) {
                if (lastObservedSession != session) {
                    lastObservedSession = session
                    val next = authEpochSeq.incrementAndGet()
                    clearCacheLocked()
                    return next
                }
            }
        }
        return authEpochSeq.get()
    }

    fun isInRiskCooldown(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        nowMs < riskCooldownUntilMs

    fun riskCooldownRemainingMs(nowMs: Long = SystemClock.elapsedRealtime()): Long =
        (riskCooldownUntilMs - nowMs).coerceAtLeast(0L)

    fun getCachedSegment(cid: Long, segmentIndex: Int, authEpoch: Long): SegmentData? {
        if (cid <= 0L || segmentIndex <= 0) return null
        val key = CacheKey(cid, segmentIndex, authEpoch)
        val now = SystemClock.elapsedRealtime()
        synchronized(cacheLock) {
            val poolKey = key.copy(segmentIndex = 0)
            val hitKey = if (segmentCache.containsKey(poolKey)) poolKey else key
            val hit = segmentCache[hitKey] ?: return null
            val ttl = if (hit.items.isEmpty() && !hit.closed) EMPTY_TTL_MS else NON_EMPTY_TTL_MS
            if (now - hit.fetchedAtMs > ttl) {
                removeSegmentLocked(hitKey)
                return null
            }
            return hit
        }
    }

    fun getCachedMeta(cid: Long, authEpoch: Long): DanmakuViewMeta? {
        val now = SystemClock.elapsedRealtime()
        synchronized(cacheLock) {
            val hit = metaCache[MetaKey(cid, authEpoch)] ?: return null
            if (now - hit.fetchedAtMs > NON_EMPTY_TTL_MS) {
                metaCache.remove(MetaKey(cid, authEpoch))
                return null
            }
            return hit.meta
        }
    }

    fun isSegmentInFailureCooldown(cid: Long, segmentIndex: Int, authEpoch: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(cacheLock) {
            val until = failedUntil[CacheKey(cid, segmentIndex, authEpoch)] ?: return false
            if (now >= until) {
                failedUntil.remove(CacheKey(cid, segmentIndex, authEpoch))
                return false
            }
            return true
        }
    }

    /** 拉取可选元数据（DmWebViewReply）；失败静默回调 null，不阻塞分段。 */
    fun fetchViewMeta(
        cid: Long,
        aid: Long,
        authEpoch: Long,
        referer: String,
        callback: (DanmakuViewMeta?) -> Unit
    ): BiliRequest {
        val handle = BiliRequest()
        if (cid <= 0L) {
            postMain { if (!handle.cancelled) callback(null) }
            return handle
        }
        getCachedMeta(cid, authEpoch)?.let { cached ->
            postMain { if (!handle.cancelled) callback(cached) }
            return handle
        }
        if (isInRiskCooldown()) {
            postMain { if (!handle.cancelled) callback(null) }
            return handle
        }
        val sessionSnapshot = BiliSessionStore.cookie()
        val task = Runnable {
            if (handle.cancelled) return@Runnable
            val result = try {
                val cookie = BiliPlayUrl.cookieHeader(session = sessionSnapshot)
                if (handle.cancelled) return@Runnable
                val url = buildString {
                    append("https://api.bilibili.com/x/v2/dm/web/view?type=1&oid=").append(cid)
                    if (aid > 0L) append("&pid=").append(aid)
                }
                val req = buildDanmakuRequest(url, referer, cookie)
                val call = danmakuClient.newCall(req)
                if (!handle.attach(call)) {
                    call.cancel()
                    return@Runnable
                }
                call.execute().use { resp ->
                    if (resp.code == 412 || resp.code == 429) {
                        recordRiskCooldown(resp)
                        null
                    } else if (resp.code == 200) {
                        val contentLen = resp.body?.contentLength() ?: -1L
                        if (contentLen > DanmakuParser.MAX_META_PAYLOAD_BYTES) {
                            null
                        } else {
                            val bytes = resp.body?.byteStream()?.use {
                                DanmakuParser.readBoundedBytes(it, DanmakuParser.MAX_META_PAYLOAD_BYTES)
                            } ?: ByteArray(0)
                            if (looksLikeJsonOrHtmlError(resp, bytes)) {
                                classifyJsonError(bytes)?.let { biliCode ->
                                    if (biliCode == -412) recordRiskCooldown(resp)
                                }
                                null
                            } else {
                                DanmakuParser.parseViewMetaProto(bytes)
                            }
                        }
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "meta fetch skipped cid=$cid: ${e.message}")
                null
            }
            if (result != null) {
                synchronized(cacheLock) {
                    if (handle.cancelled || authEpoch != authEpochSeq.get()) return@Runnable
                    if (metaCache.size >= 12) {
                        val firstKey = metaCache.entries.firstOrNull()?.key
                        if (firstKey != null) metaCache.remove(firstKey)
                    }
                    metaCache[MetaKey(cid, authEpoch)] = CachedMeta(result, SystemClock.elapsedRealtime())
                }
            }
            postMain { if (!handle.cancelled) callback(result) }
        }
        handle.onCancel { workers.remove(task) }
        try {
            workers.execute(task)
        } catch (_: RejectedExecutionException) {
            postMain { if (!handle.cancelled) callback(null) }
        }
        return handle
    }

    /** 拉取指定分段弹幕（1-based）；同 key 在途请求自动合并，取消订阅不会误杀仍在用的订阅者。 */
    fun fetchSegment(
        cid: Long,
        aid: Long,
        segmentIndex: Int,
        authEpoch: Long,
        referer: String,
        isPrefetch: Boolean,
        pinCurrentSegment: Int,
        callback: (FetchOutcome) -> Unit
    ): BiliRequest {
        val callerHandle = BiliRequest()
        if (cid <= 0L || segmentIndex <= 0) {
            postMain { if (!callerHandle.cancelled) callback(FetchOutcome.Failed("无效弹幕参数", false)) }
            return callerHandle
        }

        getCachedSegment(cid, segmentIndex, authEpoch)?.let { cached ->
            val outcome = when {
                cached.closed -> FetchOutcome.Closed
                cached.items.isEmpty() -> FetchOutcome.Empty(cached)
                else -> FetchOutcome.Ready(cached)
            }
            postMain { if (!callerHandle.cancelled) callback(outcome) }
            return callerHandle
        }

        val now = SystemClock.elapsedRealtime()
        if (now < riskCooldownUntilMs) {
            postMain {
                if (!callerHandle.cancelled) {
                    callback(FetchOutcome.RiskLimited(riskCooldownUntilMs, 412, -412))
                }
            }
            return callerHandle
        }

        val key = CacheKey(cid, segmentIndex, authEpoch)
        val wrappedCallback: (FetchOutcome) -> Unit = { outcome ->
            if (!callerHandle.cancelled) callback(outcome)
        }

        var entryToStart: InFlightEntry? = null
        synchronized(cacheLock) {
            val existing = inFlight[key]
            if (existing != null && existing.addCallback(wrappedCallback)) {
                callerHandle.onCancel {
                    synchronized(cacheLock) {
                        if (existing.removeCallback(wrappedCallback) == 0 && inFlight[key] === existing) {
                            inFlight.remove(key)
                            existing.request.cancel()
                        }
                    }
                }
                return callerHandle
            }
            val id = requestIdSeq.getAndIncrement()
            val netReq = BiliRequest()
            val entry = InFlightEntry(id, netReq, isPrefetch)
            entry.addCallback(wrappedCallback)
            inFlight[key] = entry
            entryToStart = entry
            callerHandle.onCancel {
                synchronized(cacheLock) {
                    if (entry.removeCallback(wrappedCallback) == 0 && inFlight[key] === entry) {
                        inFlight.remove(key)
                        entry.request.cancel()
                    }
                }
            }
        }

        val entry = entryToStart!!
        val sessionSnapshot = BiliSessionStore.cookie()
        val runnable = Runnable {
            val outcome = executeSegmentWithBudget(
                key = key,
                aid = aid,
                referer = referer,
                sessionSnapshot = sessionSnapshot,
                request = entry.request,
                pinCurrentSegment = pinCurrentSegment
            )
            val subscribers = synchronized(cacheLock) {
                if (inFlight[key] === entry && inFlight[key]?.identity == entry.identity) {
                    inFlight.remove(key)
                }
                entry.complete(outcome)
            }
            if (subscribers.isNotEmpty()) {
                postMain { subscribers.forEach { it(outcome) } }
            }
        }

        entry.request.onCancel { workers.remove(runnable) }
        try {
            workers.execute(runnable)
        } catch (_: RejectedExecutionException) {
            val subscribers = synchronized(cacheLock) {
                if (inFlight[key] === entry) inFlight.remove(key)
                entry.complete(FetchOutcome.Failed("弹幕加载队列繁忙", retryable = !isPrefetch))
            }
            postMain {
                subscribers.forEach {
                    it(FetchOutcome.Failed("弹幕加载队列繁忙", retryable = !isPrefetch))
                }
            }
        }
        return callerHandle
    }

    private fun executeSegmentWithBudget(
        key: CacheKey,
        aid: Long,
        referer: String,
        sessionSnapshot: String,
        request: BiliRequest,
        pinCurrentSegment: Int
    ): FetchOutcome {
        if (request.cancelled) return FetchOutcome.Failed("已取消", false)
        val startMs = SystemClock.elapsedRealtime()
        val cookie = try {
            BiliPlayUrl.cookieHeader(session = sessionSnapshot)
        } catch (_: Exception) {
            sessionSnapshot
        }
        if (request.cancelled) return FetchOutcome.Failed("已取消", false)

        var lastOutcome: FetchOutcome = FetchOutcome.Failed("弹幕加载失败", retryable = true)
        var wbiRetried = false
        var legacyTried = false

        for (attempt in 1..MAX_LOGIC_ATTEMPTS) {
            if (request.cancelled) return FetchOutcome.Failed("已取消", false)
            if (attempt > 1) {
                val backoffMs = (if (attempt == 2) 800L else 2000L) + Random.nextLong(0L, 350L)
                if (!sleepInterruptibly(backoffMs, request)) {
                    return FetchOutcome.Failed("已取消", false)
                }
            }

            val useLegacy = legacyTried
            val step = fetchSingleProtoAttempt(
                key = key,
                aid = aid,
                referer = referer,
                cookie = cookie,
                request = request,
                useLegacyEndpoint = useLegacy
            )
            if (request.cancelled && step !is AttemptResult.RiskLimited) return FetchOutcome.Failed("已取消", false)
            when (step) {
                is AttemptResult.Success -> {
                    consecutiveRiskHits = 0
                    val parsed = step.parsed
                    val seg = SegmentData(
                        cid = key.cid,
                        segmentIndex = key.segmentIndex,
                        items = parsed.items,
                        source = SourceKind.PROTO_SEG,
                        closed = parsed.closed,
                        fetchedAtMs = SystemClock.elapsedRealtime(),
                        estimatedBytes = parsed.estimatedBytes
                    )
                    if (parsed.closed) {
                        putCache(key, seg, pinCurrentSegment, request)
                        return FetchOutcome.Closed
                    }
                    putCache(key, seg, pinCurrentSegment, request)
                    Log.d(
                        TAG,
                        "seg ok cid=${key.cid} idx=${key.segmentIndex} items=${parsed.items.size} " +
                            "raw=${parsed.rawCount} drop=${parsed.droppedByBudget} " +
                            "cost=${SystemClock.elapsedRealtime() - startMs}ms"
                    )
                    return if (parsed.items.isEmpty()) FetchOutcome.Empty(seg) else FetchOutcome.Ready(seg)
                }
                is AttemptResult.WbiSignExpired -> {
                    if (!wbiRetried) {
                        wbiRetried = true
                        BiliWbiSign.invalidateKey(step.keyVersion)
                        lastOutcome = FetchOutcome.Failed("签名失效", true)
                        continue
                    }
                    markFailedCooldown(key, request)
                    return FetchOutcome.Failed("弹幕签名校验失败", false)
                }
                is AttemptResult.EndpointIncompatible -> {
                    if (!legacyTried) {
                        legacyTried = true
                        lastOutcome = FetchOutcome.Failed("切换兼容分段接口", true)
                        continue
                    }
                    // 仅在两个 protobuf 分段路径都明确协议不兼容时，对该 (cid, authEpoch) 最多尝试一次 XML 兜底
                    val xml = tryXmlFallbackOnce(key, referer, cookie, request, pinCurrentSegment)
                    if (xml != null) return xml
                    markFailedCooldown(key, request)
                    return FetchOutcome.Failed(step.reason, false)
                }
                is AttemptResult.RiskLimited -> {
                    val until = recordRiskCooldown(step.retryAfterSec)
                    return FetchOutcome.RiskLimited(until, step.httpCode, step.biliCode)
                }
                is AttemptResult.AuthOrPermission -> {
                    markFailedCooldown(key, request)
                    return FetchOutcome.AuthOrPermission(step.httpCode, step.biliCode)
                }
                is AttemptResult.RetryableError -> {
                    lastOutcome = FetchOutcome.Failed(step.reason, retryable = true)
                    continue
                }
                is AttemptResult.FatalError -> {
                    markFailedCooldown(key, request)
                    return FetchOutcome.Failed(step.reason, retryable = false)
                }
            }
        }
        markFailedCooldown(key, request)
        return lastOutcome
    }

    private sealed class AttemptResult {
        data class Success(val parsed: DanmakuSegmentParseResult) : AttemptResult()
        data class WbiSignExpired(val keyVersion: Long) : AttemptResult()
        data class EndpointIncompatible(val reason: String) : AttemptResult()
        data class RiskLimited(val httpCode: Int, val biliCode: Int, val retryAfterSec: Long?) : AttemptResult()
        data class AuthOrPermission(val httpCode: Int, val biliCode: Int) : AttemptResult()
        data class RetryableError(val reason: String) : AttemptResult()
        data class FatalError(val reason: String) : AttemptResult()
    }

    private fun fetchSingleProtoAttempt(
        key: CacheKey,
        aid: Long,
        referer: String,
        cookie: String,
        request: BiliRequest,
        useLegacyEndpoint: Boolean
    ): AttemptResult {
        val params = LinkedHashMap<String, String>(4).apply {
            put("type", "1")
            put("oid", key.cid.toString())
            if (aid > 0L) put("pid", aid.toString())
            put("segment_index", key.segmentIndex.toString())
        }
        val url: String
        var keyVersion = -1L
        if (useLegacyEndpoint) {
            val q = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            url = "https://api.bilibili.com/x/v2/dm/web/seg.so?$q"
        } else {
            val signed = try {
                BiliWbiSign.signParamsWithVersion(params)
            } catch (e: Exception) {
                return AttemptResult.RetryableError("WBI 签名失败：${e.message ?: "网络异常"}")
            }
            if (request.cancelled) return AttemptResult.FatalError("已取消")
            keyVersion = signed.second
            url = "https://api.bilibili.com/x/v2/dm/wbi/web/seg.so?${signed.first}"
        }

        val httpReq = buildDanmakuRequest(url, referer, cookie)
        val call = danmakuClient.newCall(httpReq)
        if (!request.attach(call)) {
            call.cancel()
            return AttemptResult.FatalError("已取消")
        }

        return try {
            call.execute().use { resp ->
                val code = resp.code
                val retryAfter = parseRetryAfterSeconds(resp.header("Retry-After"))
                when {
                    code == 412 || code == 429 -> AttemptResult.RiskLimited(code, -412, retryAfter)
                    code == 401 || code == 403 -> AttemptResult.AuthOrPermission(code, -code)
                    code == 304 -> AttemptResult.FatalError("未携带条件头却返回 304")
                    code == 404 -> AttemptResult.FatalError("分段不存在（HTTP 404）")
                    code == 410 || code == 405 -> AttemptResult.EndpointIncompatible("接口返回 HTTP $code")
                    code in 500..599 -> AttemptResult.RetryableError("服务端暂时异常（HTTP $code）")
                    code != 200 -> AttemptResult.FatalError("弹幕接口异常（HTTP $code）")
                    else -> {
                        val contentLen = resp.body?.contentLength() ?: -1L
                        if (contentLen > DanmakuParser.MAX_SEG_PAYLOAD_BYTES) {
                            return AttemptResult.FatalError("弹幕包体超过安全上限")
                        }
                        val bytes = resp.body?.byteStream()?.use {
                            DanmakuParser.readBoundedBytes(it, DanmakuParser.MAX_SEG_PAYLOAD_BYTES)
                        } ?: ByteArray(0)

                        if (bytes.isEmpty()) {
                            return AttemptResult.Success(
                                DanmakuSegmentParseResult(emptyList(), closed = false, 0, 0, 0, 0)
                            )
                        }
                        if (looksLikeJsonOrHtmlError(resp, bytes)) {
                            val biliCode = classifyJsonError(bytes)
                            return when (biliCode) {
                                -412, -352 -> AttemptResult.RiskLimited(code, biliCode, retryAfter)
                                -101, -403 -> AttemptResult.AuthOrPermission(code, biliCode)
                                -400, -401 -> {
                                    if (!useLegacyEndpoint && keyVersion > 0L) {
                                        AttemptResult.WbiSignExpired(keyVersion)
                                    } else {
                                        AttemptResult.FatalError("弹幕请求参数被拒绝（code=$biliCode）")
                                    }
                                }
                                -404 -> AttemptResult.EndpointIncompatible("业务返回 -404")
                                else -> AttemptResult.FatalError("弹幕返回非二进制内容（code=${biliCode ?: -1}）")
                            }
                        }
                        val parsed = DanmakuParser.parseSegmentProto(bytes) { request.cancelled }
                        AttemptResult.Success(parsed)
                    }
                }
            }
        } catch (e: DanmakuParseException) {
            AttemptResult.FatalError(e.message ?: "弹幕解析失败")
        } catch (e: IOException) {
            if (request.cancelled) AttemptResult.FatalError("已取消")
            else AttemptResult.RetryableError(e.message ?: "网络异常")
        }
    }

    /** 受控单次 XML 兜底（同 cid + authEpoch 至多一次），使用有界 deflate 解压与 XmlPullParser。 */
    private fun tryXmlFallbackOnce(
        key: CacheKey,
        referer: String,
        cookie: String,
        request: BiliRequest,
        pinCurrentSegment: Int
    ): FetchOutcome? {
        val metaKey = MetaKey(key.cid, key.authEpoch)
        synchronized(cacheLock) {
            if (!xmlAttempted.add(metaKey)) return null
            if (xmlAttempted.size > 32) xmlAttempted.clear()
        }
        val url = "https://api.bilibili.com/x/v1/dm/list.so?oid=${key.cid}"
        val httpReq = buildDanmakuRequest(url, referer, cookie)
        val call = danmakuClient.newCall(httpReq)
        if (!request.attach(call)) {
            call.cancel()
            return null
        }
        return try {
            call.execute().use { resp ->
                if (resp.code == 412 || resp.code == 429) {
                    val until = recordRiskCooldown(resp)
                    return FetchOutcome.RiskLimited(until, resp.code, -412)
                }
                if (resp.code != 200) return null
                val raw = resp.body?.byteStream()?.use {
                    DanmakuParser.readBoundedBytes(it, DanmakuParser.MAX_SEG_PAYLOAD_BYTES)
                } ?: return null
                val parsed = DanmakuParser.parseXmlDanmaku(raw, resp.header("Content-Encoding")) {
                    request.cancelled
                }
                val seg = SegmentData(
                    cid = key.cid,
                    segmentIndex = key.segmentIndex,
                    items = parsed.items,
                    source = SourceKind.XML_FALLBACK,
                    closed = false,
                    fetchedAtMs = SystemClock.elapsedRealtime(),
                    estimatedBytes = parsed.estimatedBytes
                )
                putCache(key, seg, pinCurrentSegment, request)
                if (seg.items.isEmpty()) FetchOutcome.Empty(seg) else FetchOutcome.Ready(seg)
            }
        } catch (e: Exception) {
            Log.d(TAG, "xml fallback failed cid=${key.cid}: ${e.message}")
            null
        }
    }

    private fun buildDanmakuRequest(url: String, referer: String, cookie: String): Request {
        val builder = BiliHttp.request(url)
            .header("Accept", "application/octet-stream, */*")
        if (referer.isNotBlank()) builder.header("Referer", referer)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return builder.build()
    }

    /**
     * 先基于 Content-Type 与首字节判断是否为 JSON/HTML 错误响应。
     * 注意：绝不对二进制正文调用 String.trim()，因为合法 Protobuf 常以 0x0A（换行符）开头。
     */
    private fun looksLikeJsonOrHtmlError(resp: Response, bytes: ByteArray): Boolean {
        val ct = resp.header("Content-Type")?.lowercase(Locale.US).orEmpty()
        if (ct.contains("application/json") || ct.contains("text/html")) return true
        if (bytes.isEmpty()) return false
        val first = bytes[0].toInt() and 0xFF
        if (first == '{'.code) return true
        if (first == '<'.code && bytes.size >= 5) {
            val head = String(bytes, 0, minOf(bytes.size, 32), Charsets.US_ASCII).lowercase(Locale.US)
            if (head.startsWith("<!doc") || head.startsWith("<html") || head.startsWith("<head")) {
                return true
            }
        }
        return false
    }

    private fun classifyJsonError(bytes: ByteArray): Int? {
        if (bytes.isEmpty() || bytes.size > 64 * 1024) return null
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (json.has("code")) json.optInt("code") else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRetryAfterSeconds(header: String?): Long? {
        val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return raw.toLongOrNull()?.coerceIn(10L, 600L)
    }

    private fun recordRiskCooldown(resp: Response): Long =
        recordRiskCooldown(parseRetryAfterSeconds(resp.header("Retry-After")))

    private fun recordRiskCooldown(retryAfterSec: Long?): Long {
        val hits = (++consecutiveRiskHits).coerceAtMost(4)
        val cooldownMs = retryAfterSec?.times(1000L)
            ?: (BASE_RISK_COOLDOWN_MS * (1L shl (hits - 1))).coerceAtMost(MAX_RISK_COOLDOWN_MS)
        val until = SystemClock.elapsedRealtime() + cooldownMs
        riskCooldownUntilMs = maxOf(riskCooldownUntilMs, until)
        Log.w(TAG, "danmaku risk cooldown ${cooldownMs / 1000}s")
        return riskCooldownUntilMs
    }

    private fun markFailedCooldown(key: CacheKey, request: BiliRequest) {
        synchronized(cacheLock) {
            if (request.cancelled || key.authEpoch != authEpochSeq.get() || inFlight[key]?.request !== request) return
            if (failedUntil.size > 64) failedUntil.clear()
            failedUntil[key] = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
        }
    }

    private fun putCache(key: CacheKey, seg: SegmentData, pinCurrentSegment: Int, request: BiliRequest) {
        synchronized(cacheLock) {
            if (request.cancelled || key.authEpoch != authEpochSeq.get() || inFlight[key]?.request !== request) return
            val cacheKey = if (seg.source == SourceKind.XML_FALLBACK) key.copy(segmentIndex = 0) else key
            removeSegmentLocked(cacheKey)
            segmentCache[cacheKey] = seg
            cachedBytes += seg.estimatedBytes
            cachedItems += seg.items.size
            evictCacheLocked(pinCid = key.cid, pinSegment = pinCurrentSegment)
        }
    }

    private fun removeSegmentLocked(key: CacheKey) {
        val old = segmentCache.remove(key) ?: return
        cachedBytes = (cachedBytes - old.estimatedBytes).coerceAtLeast(0)
        cachedItems = (cachedItems - old.items.size).coerceAtLeast(0)
    }

    private fun evictCacheLocked(pinCid: Long, pinSegment: Int) {
        while (
            segmentCache.size > MAX_CACHE_SEGMENTS ||
            cachedBytes > MAX_CACHE_BYTES ||
            cachedItems > MAX_CACHE_ITEMS
        ) {
            // 优先淘汰非当前段（LRU 最旧）
            val victim = segmentCache.keys.firstOrNull {
                !(it.cid == pinCid && it.segmentIndex == pinSegment)
            } ?: segmentCache.keys.firstOrNull() ?: break
            removeSegmentLocked(victim)
            if (segmentCache.size <= 1) break
        }
    }

    private fun clearCacheLocked() {
        segmentCache.clear()
        cachedBytes = 0
        cachedItems = 0
        metaCache.clear()
        failedUntil.clear()
        xmlAttempted.clear()
    }

    private fun sleepInterruptibly(ms: Long, request: BiliRequest): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ms
        while (true) {
            if (request.cancelled) return false
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0L) return true
            try {
                Thread.sleep(minOf(left, 100L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun postMain(block: () -> Unit) {
        // 缓存命中/队列拒绝也须在调用方登记请求句柄之后交付。
        mainHandler.post(block)
    }
}
