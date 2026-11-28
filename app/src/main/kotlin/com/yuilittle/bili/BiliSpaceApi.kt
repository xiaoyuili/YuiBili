package com.yuilittle.bili

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Call
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class FollowState { FOLLOWED, NOT_FOLLOWED, UNKNOWN, SELF }

enum class DynamicKind { TEXT, IMAGE, VIDEO, LIVE, UNKNOWN }

/** 动态里的直播开播信息（MAJOR_TYPE_LIVE_RCMD / LIVE）。 */
data class DynamicLive(
    val roomId: Long,
    val title: String,
    val cover: String,
    val areaName: String,
    val onlineText: String
)

data class SpaceHeader(
    val mid: Long,
    val name: String,
    val face: String?,
    val sign: String?,
    val level: Int?,
    val vipLabel: String?,
    val follower: Long?,
    val following: Long?,
    val likes: Long?,
    val followState: FollowState,
    val isSelf: Boolean
)

data class SpaceVideo(
    val aid: Long?,
    val bvid: String?,
    val title: String,
    val cover: String?,
    val duration: String?,
    val publishedAt: Long?,
    val play: Long?,
    val danmaku: Long?,
    val comments: Long?,
    val jumpUrl: String?
)

data class SpaceImage(
    val url: String,
    val width: Int?,
    val height: Int?
)

data class RichNode(
    val text: String,
    val type: String,
    val emojiUrl: String? = null,
    val jumpUrl: String? = null
)

data class DynamicStats(
    val comment: Long?,
    val like: Long?
)

data class SpaceDynamic(
    val id: String,
    val kind: DynamicKind,
    val authorMid: Long?,
    val authorName: String?,
    val authorFace: String?,
    val pubTs: Long?,
    val pubTime: String?,
    val text: String,
    val richNodes: List<RichNode>,
    val images: List<SpaceImage>,
    val video: SpaceVideo?,
    val stats: DynamicStats,
    val isPinned: Boolean,
    val visible: Boolean,
    val jumpUrl: String?,
    val title: String? = null,
    val commentOid: String? = null,
    val commentType: Int? = null,
    val supportsDetail: Boolean = false,
    /** 直播开播动态的直播间信息，kind == LIVE 时非空。 */
    val live: DynamicLive? = null
)

data class DynamicSearchCheckpoint(
    val mid: Long,
    val sessionScope: String,
    val keyword: String,
    val nextOffset: String,
    val pendingItems: List<SpaceDynamic> = emptyList(),
    val pendingStartIndex: Int = 0
)

data class CursorPage<T>(
    val items: List<T>,
    val nextOffset: String?,
    val hasMore: Boolean,
    val limited: Boolean = false,
    val scannedItems: Int = 0,
    val checkpoint: DynamicSearchCheckpoint? = null
)

data class VideoPage(
    val items: List<SpaceVideo>,
    val page: Int,
    val total: Long?,
    val hasMore: Boolean,
    val usedFallback: Boolean = false
)

/** 空间排序选项，与 /x/space/wbi/arc/search 的 order 参数对应。 */
enum class SpaceVideoOrder(val label: String, val param: String) {
    PUBDATE("最新发布", "pubdate"),
    CLICK("最多播放", "click"),
    STOW("最多收藏", "stow")
}

internal class BiliCodeException(val code: Int, override val message: String) : IOException(message)

/**
 * 用户空间页与空间内搜索后端接口层：
 * - 独立调度并发（限流 2 并发防止 -352 / -799 风控）
 * - 集中封装 WBI、Cookie+buvid3/4、Referer、UA 与可更新 dm_* 参数
 * - 短时内存缓存（按 mid + 登录态隔离）与 -412 本地冷却保护
 * - 所有异步请求均返回 [BiliRequest] 句柄，支持页面退出/切换查询时即时 cancel
 */
object BiliSpaceApi {

    private const val PROFILE_CACHE_TTL_MS = 5 * 60_000L
    private const val VIDEO_CACHE_TTL_MS = 5 * 60_000L
    private const val DYNAMIC_CACHE_TTL_MS = 45_000L
    private const val SEARCH_CACHE_TTL_MS = 90_000L
    private const val RISK_COOLDOWN_412_MS = 10 * 60_000L

    private const val DYNAMIC_SEARCH_MAX_PAGES = 5
    private const val DYNAMIC_SEARCH_MAX_ITEMS = 100
    private const val DYNAMIC_SEARCH_MAX_RESULTS = 20
    private const val DYNAMIC_SEARCH_BUDGET_MS = 9_000L
    private const val DYNAMIC_SEARCH_MAX_KEYWORD_CP = 32

    private const val DYNAMIC_FEATURES =
        "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,forwardListHidden,decorationCard,commentsNewVersion,onlyfansAssetsV2,ugcDelete,onlyfansQaCard"

    // 集中封装网页指纹参数，避免在各请求点散落写死常量
    @Volatile
    private var dmFingerprintParams: Map<String, String> = mapOf(
        "dm_img_list" to "[]",
        "dm_img_str" to "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ",
        "dm_cover_img_str" to "QU5HTEUgKEdvb2dsZSwgU3dpZnRTaGFkZXIgU3ViWmVybyk"
    )

    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val spaceWorkers = ThreadPoolExecutor(
        2,
        2,
        20L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(16),
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }

    private data class CacheEntry<T>(val value: T, val savedAtMs: Long)

    private val profileCache = ConcurrentHashMap<String, CacheEntry<SpaceHeader>>()
    private val videoCache = ConcurrentHashMap<String, CacheEntry<VideoPage>>()
    private val dynamicCache = ConcurrentHashMap<String, CacheEntry<CursorPage<SpaceDynamic>>>()
    private val dynamicDetailCache = ConcurrentHashMap<String, CacheEntry<SpaceDynamic>>()
    private val searchVideoCache = ConcurrentHashMap<String, CacheEntry<VideoPage>>()
    @Volatile private var riskBlockedUntilMs = 0L

    private fun currentViewerUid(): Long {
        val raw = BiliSessionStore.cookie()
            .substringAfter("DedeUserID=", "")
            .substringBefore(";")
            .trim()
        return raw.toLongOrNull() ?: 0L
    }

    private fun sessionScopeKey(): String =
        if (!BiliSessionStore.isLoggedIn()) "guest"
        else "u_${currentViewerUid().takeIf { it > 0L } ?: BiliSessionStore.cookie().hashCode()}"

    private fun checkRiskCooldown(): String? {
        val now = SystemClock.elapsedRealtime()
        val remain = riskBlockedUntilMs - now
        if (remain > 0L) {
            val mins = ((remain + 59_999L) / 60_000L).coerceAtLeast(1L)
            return "请求受限，请约 ${mins} 分钟后再试"
        }
        return null
    }

    private fun markRisk412() {
        riskBlockedUntilMs = SystemClock.elapsedRealtime() + RISK_COOLDOWN_412_MS
    }

    /** 缓存写入前按上限裁剪过期或最旧条目，防止长期浏览多个 UP 主空间时内存只增不减。 */
    private fun <T> putBoundedCache(
        map: ConcurrentHashMap<String, CacheEntry<T>>,
        key: String,
        value: T,
        maxEntries: Int,
        ttlMs: Long
    ) {
        val now = SystemClock.elapsedRealtime()
        map[key] = CacheEntry(value, now)
        if (map.size > maxEntries) {
            val iter = map.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (now - entry.value.savedAtMs >= ttlMs) iter.remove()
            }
            if (map.size > maxEntries) {
                val oldest = map.entries.minByOrNull { it.value.savedAtMs }?.key
                if (oldest != null && oldest != key) map.remove(oldest)
            }
        }
    }

    /** 关注/取关成功后失效当前空间相关短缓存，保证详情/空间信息立即可见。 */
    fun invalidateProfileCache(mid: Long) {
        val prefix = "$mid|"
        // minSdk 23 上 Collection.removeIf 不可用（API 24+），改用迭代器显式移除
        val iterator = profileCache.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().startsWith(prefix)) iterator.remove()
        }
    }

    /**
     * 聚合拉取空间头部资料（acc/info 或 card 降级 + relation/stat + upstat + relation 补全）。
     * - 任一子接口失败不拖垮基础资料展示
     * - [onFastReady] 可在主资料返回时先刷新一次 UI，再补全获赞/关系等慢项
     */
    internal fun fetchSpaceHeader(
        mid: Long,
        forceRefresh: Boolean = false,
        onFastReady: ((SpaceHeader) -> Unit)? = null,
        callback: (SpaceHeader?, String?) -> Unit
    ): BiliRequest {
        val req = BiliRequest()
        if (mid <= 0L) {
            mainHandler.post { if (!req.cancelled) callback(null, "无效的用户 ID") }
            return req
        }
        val cacheKey = "$mid|${sessionScopeKey()}"
        if (!forceRefresh) {
            profileCache[cacheKey]?.takeIf { SystemClock.elapsedRealtime() - it.savedAtMs < PROFILE_CACHE_TTL_MS }
                ?.let { cached ->
                    val resolved = applyFollowBridge(cached.value)
                    mainHandler.post { if (!req.cancelled) callback(resolved, null) }
                    return req
                }
        }
        checkRiskCooldown()?.let { msg ->
            mainHandler.post { if (!req.cancelled) callback(null, msg) }
            return req
        }

        executeSpace(req, onRejected = { callback(null, "请求过于频繁，请稍后再试") }) {
            val viewerUid = currentViewerUid()
            val isSelf = BiliSessionStore.isLoggedIn() && viewerUid > 0L && viewerUid == mid
            val loggedIn = BiliSessionStore.isLoggedIn()
            val referer = spaceReferer(mid)

            var baseHeader: SpaceHeader? = null
            var baseError: String? = null
            var needExtraRelation = false

            // 1. 拉取主资料：/x/space/wbi/acc/info，遇 -352/-401 刷新一次 WBI 重试，再失败降级 card
            val accResult = fetchAccInfoWithRetry(mid, referer, req)
            if (req.cancelled) return@executeSpace
            if (accResult.first != null) {
                val parsed = parseAccInfo(accResult.first!!, mid, isSelf, loggedIn)
                baseHeader = parsed.first
                needExtraRelation = parsed.second
            } else {
                baseError = accResult.second
                val cardJson = getSpaceJsonSafely("https://api.bilibili.com/x/web-interface/card?mid=$mid", referer, req)
                if (req.cancelled) return@executeSpace
                if (cardJson != null) {
                    baseHeader = parseCardFallback(cardJson, mid, isSelf)
                    needExtraRelation = loggedIn && !isSelf
                }
            }

            if (baseHeader == null) {
                mainHandler.post { if (!req.cancelled) callback(null, baseError ?: "无法获取空间资料") }
                return@executeSpace
            }

            val firstShot = applyFollowBridge(baseHeader)
            mainHandler.post { if (!req.cancelled) onFastReady?.invoke(firstShot) }

            // 2. 补充精确关系数（/x/relation/stat）
            var follower = baseHeader.follower
            var following = baseHeader.following
            val statRoot = getSpaceJsonSafely(
                "https://api.bilibili.com/x/relation/stat?vmid=$mid",
                referer,
                req
            )
            if (req.cancelled) return@executeSpace
            statRoot?.optJSONObject("data")?.let { stat ->
                stat.optNullableLong("follower")?.let { follower = it }
                stat.optNullableLong("following")?.let { following = it }
            }

            // 3. 补充获赞数（/x/space/upstat，仅登录有效，缺失/失败保持 null）
            var likes: Long? = null
            if (loggedIn) {
                val upstatRoot = getSpaceJsonSafely(
                    "https://api.bilibili.com/x/space/upstat?mid=$mid",
                    referer,
                    req
                )
                if (req.cancelled) return@executeSpace
                likes = parseUpstatLikes(upstatRoot)
            }

            // 4. 若主资料来自 card 降级或关注状态未知，且处于登录态看他人空间，补充精确关注关系
            var followState = baseHeader.followState
            if (needExtraRelation && loggedIn && !isSelf) {
                val relState = fetchWbiRelationState(mid, referer, req)
                if (req.cancelled) return@executeSpace
                if (relState != null) followState = relState
            }

            val finalHeader = applyFollowBridge(
                baseHeader.copy(
                    follower = follower,
                    following = following,
                    likes = likes,
                    followState = followState
                )
            )
            putBoundedCache(profileCache, cacheKey, finalHeader, maxEntries = 32, ttlMs = PROFILE_CACHE_TTL_MS)
            mainHandler.post { if (!req.cancelled) callback(finalHeader, null) }
        }
        return req
    }

    /** 拉取空间动态分页（cursor/offset 游标驱动）。 */
    internal fun fetchSpaceDynamics(
        mid: Long,
        offset: String = "",
        forceRefresh: Boolean = false,
        callback: (CursorPage<SpaceDynamic>?, String?) -> Unit
    ): BiliRequest {
        val req = BiliRequest()
        if (mid <= 0L) {
            mainHandler.post { if (!req.cancelled) callback(null, "无效的用户 ID") }
            return req
        }
        val cleanOffset = offset.trim()
        val cacheKey = "$mid|${sessionScopeKey()}|$cleanOffset"
        if (!forceRefresh) {
            dynamicCache[cacheKey]?.takeIf { SystemClock.elapsedRealtime() - it.savedAtMs < DYNAMIC_CACHE_TTL_MS }
                ?.let { cached ->
                    mainHandler.post { if (!req.cancelled) callback(cached.value, null) }
                    return req
                }
        }
        checkRiskCooldown()?.let { msg ->
            mainHandler.post { if (!req.cancelled) callback(null, msg) }
            return req
        }

        executeSpace(req, onRejected = { callback(null, "请求过于频繁，请稍后再试") }) {
            try {
                val page = fetchSpaceDynamicPageBlocking(mid, cleanOffset, req)
                if (req.cancelled) return@executeSpace
                putDynamicCache(cacheKey, page)
                mainHandler.post { if (!req.cancelled) callback(page, null) }
            } catch (error: Exception) {
                if (!req.cancelled) {
                    mainHandler.post { if (!req.cancelled) callback(null, readableSpaceError(error)) }
                }
            }
        }
        return req
    }

    /** 拉取「关注」动态流（需登录）。游标 offset 由上一页返回，空串表示首页。 */
    internal fun fetchFollowingDynamics(
        offset: String = "",
        callback: (CursorPage<SpaceDynamic>?, String?) -> Unit
    ): BiliRequest {
        val req = BiliRequest()
        if (!BiliSessionStore.isLoggedIn()) {
            mainHandler.post { if (!req.cancelled) callback(null, "登录后可查看关注动态") }
            return req
        }
        checkRiskCooldown()?.let { msg ->
            mainHandler.post { if (!req.cancelled) callback(null, msg) }
            return req
        }
        val cleanOffset = offset.trim()
        executeSpace(req, onRejected = { callback(null, "请求过于频繁，请稍后再试") }) {
            try {
                val page = fetchFollowingDynamicPageBlocking(cleanOffset, req)
                if (req.cancelled) return@executeSpace
                mainHandler.post { if (!req.cancelled) callback(page, null) }
            } catch (error: Exception) {
                if (!req.cancelled) {
                    mainHandler.post { if (!req.cancelled) callback(null, readableSpaceError(error)) }
                }
            }
        }
        return req
    }

    private fun fetchFollowingDynamicPageBlocking(offset: String, req: BiliRequest): CursorPage<SpaceDynamic> {
        val params = LinkedHashMap<String, String>().apply {
            put("type", "all")
            put("page", "1")
            if (offset.isNotEmpty()) put("offset", offset)
            put("timezone_offset", "-480")
            put("platform", "web")
            put("features", DYNAMIC_FEATURES)
            put("web_location", "333.999")
        }
        // feed/all 对部分账号会校验 WBI；签名失败时退回普通查询，避免整页不可用。
        val query = runCatching { BiliWbiSign.signParams(params) }.getOrElse { encodeQuery(params) }
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?$query"
        val root = getSpaceJson(url, "https://t.bilibili.com/", req)
        val code = root.optInt("code", -1)
        if (code != 0) {
            throw BiliCodeException(code, readableBiliCode(code, root.optString("message")))
        }
        return parseDynamicFeedPage(root)
    }

    /** 拉取单条动态详情（/x/polymer/web-dynamic/v1/detail），用于详情页补齐全文、title 与 comment_id_str / comment_type。 */
    internal fun fetchDynamicDetail(
        dynamicId: String,
        forceRefresh: Boolean = false,
        callback: (SpaceDynamic?, String?) -> Unit
    ): BiliRequest {
        val req = BiliRequest()
        val cleanId = dynamicId.trim()
        if (cleanId.isEmpty()) {
            mainHandler.post { if (!req.cancelled) callback(null, "无效的动态 ID") }
            return req
        }
        val cacheKey = "$cleanId|${sessionScopeKey()}"
        if (!forceRefresh) {
            dynamicDetailCache[cacheKey]
                ?.takeIf { SystemClock.elapsedRealtime() - it.savedAtMs < DYNAMIC_CACHE_TTL_MS }
                ?.let { cached ->
                    mainHandler.post { if (!req.cancelled) callback(cached.value, null) }
                    return req
                }
        }
        checkRiskCooldown()?.let { msg ->
            mainHandler.post { if (!req.cancelled) callback(null, msg) }
            return req
        }

        executeSpace(req, onRejected = { callback(null, "请求过于频繁，请稍后再试") }) {
            try {
                val params = linkedMapOf(
                    "id" to cleanId,
                    "timezone_offset" to "-480",
                    "platform" to "web",
                    "features" to DYNAMIC_FEATURES,
                    "web_location" to "333.1369"
                )
                val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?${encodeQuery(params)}"
                val root = getSpaceJson(url, "https://t.bilibili.com/$cleanId", req)
                if (req.cancelled) return@executeSpace
                val code = root.optInt("code", -1)
                if (code == -412) {
                    markRisk412()
                    throw BiliCodeException(code, readableBiliCode(code, root.optString("message")))
                }
                if (code != 0) {
                    throw BiliCodeException(code, readableBiliCode(code, root.optString("message")))
                }
                val itemObj = root.optJSONObject("data")?.optJSONObject("item")
                    ?: throw IOException("未返回动态详情")
                val parsed = parseDynamicItem(itemObj) ?: throw IOException("该动态暂不可查看")
                if (dynamicDetailCache.size >= 40) dynamicDetailCache.clear()
                dynamicDetailCache[cacheKey] = CacheEntry(parsed, SystemClock.elapsedRealtime())
                mainHandler.post { if (!req.cancelled) callback(parsed, null) }
            } catch (error: Exception) {
                if (!req.cancelled) {
                    mainHandler.post { if (!req.cancelled) callback(null, readableSpaceError(error)) }
                }
            }
        }
        return req
    }

    private fun putDynamicCache(key: String, page: CursorPage<SpaceDynamic>) {
        if (dynamicCache.size >= 40) {
            val now = SystemClock.elapsedRealtime()
            val it = dynamicCache.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value.savedAtMs >= DYNAMIC_CACHE_TTL_MS) it.remove()
            }
            if (dynamicCache.size >= 40) dynamicCache.clear()
        }
        dynamicCache[key] = CacheEntry(page, SystemClock.elapsedRealtime())
    }

    /** 拉取空间投稿列表或按关键词搜索空间内视频（/x/space/wbi/arc/search）。 */
    internal fun fetchSpaceVideos(
        mid: Long,
        page: Int = 1,
        order: SpaceVideoOrder = SpaceVideoOrder.PUBDATE,
        keyword: String = "",
        forceRefresh: Boolean = false,
        callback: (VideoPage?, String?) -> Unit
    ): BiliRequest {
        val req = BiliRequest()
        if (mid <= 0L) {
            mainHandler.post { if (!req.cancelled) callback(null, "无效的用户 ID") }
            return req
        }
        val safePage = page.coerceAtLeast(1)
        val trimmedKeyword = keyword.trim()
        val cacheKey = "$mid|${sessionScopeKey()}|${order.param}|$trimmedKeyword|$safePage"
        val ttl = if (trimmedKeyword.isNotEmpty()) SEARCH_CACHE_TTL_MS else VIDEO_CACHE_TTL_MS
        val store = if (trimmedKeyword.isNotEmpty()) searchVideoCache else videoCache
        if (!forceRefresh) {
            store[cacheKey]?.takeIf { SystemClock.elapsedRealtime() - it.savedAtMs < ttl }
                ?.let { cached ->
                    mainHandler.post { if (!req.cancelled) callback(cached.value, null) }
                    return req
                }
        }
        checkRiskCooldown()?.let { msg ->
            mainHandler.post { if (!req.cancelled) callback(null, msg) }
            return req
        }

        executeSpace(req, onRejected = { callback(null, "请求过于频繁，请稍后再试") }) {
            try {
                val referer = spaceReferer(mid)
                val result = fetchSpaceVideosWithRetry(
                    mid = mid,
                    page = safePage,
                    pageSize = 20,
                    order = order,
                    keyword = trimmedKeyword,
                    referer = referer,
                    req = req
                )
                if (req.cancelled) return@executeSpace
                if (!result.usedFallback) {
                    putBoundedCache(store, cacheKey, result, maxEntries = 32, ttlMs = ttl)
                }
                mainHandler.post { if (!req.cancelled) callback(result, null) }
            } catch (error: Exception) {
                if (!req.cancelled) {
                    mainHandler.post { if (!req.cancelled) callback(null, readableSpaceError(error)) }
                }
            }
        }
        return req
    }

    /**
     * 空间内动态关键词检索（分页串行拉取 + 短时分页缓存复用 + 本地过滤 + 严格页数/条数/时长上限）。
     * 支持通过 [resume] 检查点从上次停下的页内位置或下一页继续向更早动态检索，避免命中满 20 条时漏检半页。
     */
    internal fun searchSpaceDynamics(
        mid: Long,
        rawKeyword: String,
        resume: DynamicSearchCheckpoint? = null,
        forceRefresh: Boolean = false,
        callback: (CursorPage<SpaceDynamic>?, String?) -> Unit
    ): BiliRequest {
        val req = BiliRequest()
        val keyword = limitUnicodeCodePoints(rawKeyword.trim(), DYNAMIC_SEARCH_MAX_KEYWORD_CP)
        if (mid <= 0L) {
            mainHandler.post { if (!req.cancelled) callback(null, "无效的用户 ID") }
            return req
        }
        if (keyword.isEmpty()) {
            mainHandler.post {
                if (!req.cancelled) callback(CursorPage(emptyList(), null, hasMore = false, limited = false), null)
            }
            return req
        }
        checkRiskCooldown()?.let { msg ->
            mainHandler.post { if (!req.cancelled) callback(null, msg) }
            return req
        }

        val scope = sessionScopeKey()
        val validResume = resume?.takeIf {
            !forceRefresh && it.mid == mid && it.sessionScope == scope && it.keyword == keyword
        }

        executeSpace(req, onRejected = { callback(null, "请求过于频繁，请稍后再试") }) {
            val startAt = SystemClock.elapsedRealtime()
            val matched = ArrayList<SpaceDynamic>()
            val seenIds = HashSet<String>()
            var offset = validResume?.nextOffset.orEmpty().trim()
            var nextCheckpoint: DynamicSearchCheckpoint? = null
            var pagesScanned = 0
            var itemsScanned = 0
            var reachedEnd = false
            var hitLimit = false
            var fatalError: String? = null

            // 先消费上一轮因命中达到上限而停在页中的剩余条目，避免漏检半页
            if (validResume != null && validResume.pendingStartIndex < validResume.pendingItems.size) {
                val pending = validResume.pendingItems
                var idx = validResume.pendingStartIndex
                while (idx < pending.size && !req.cancelled) {
                    val item = pending[idx]
                    idx++
                    if (item.id.isNotBlank() && seenIds.add(item.id)) {
                        itemsScanned++
                        if (matchesDynamicKeyword(item, keyword)) {
                            matched += item
                            if (matched.size >= DYNAMIC_SEARCH_MAX_RESULTS) {
                                hitLimit = true
                                if (idx < pending.size) {
                                    nextCheckpoint = DynamicSearchCheckpoint(
                                        mid = mid,
                                        sessionScope = scope,
                                        keyword = keyword,
                                        nextOffset = offset,
                                        pendingItems = pending,
                                        pendingStartIndex = idx
                                    )
                                } else if (offset.isNotEmpty()) {
                                    nextCheckpoint = DynamicSearchCheckpoint(mid, scope, keyword, offset)
                                } else {
                                    reachedEnd = true
                                }
                                break
                            }
                        }
                    }
                }
                if (!hitLimit && offset.isEmpty()) {
                    reachedEnd = true
                }
            }

            while (!req.cancelled && !hitLimit && !reachedEnd) {
                val elapsed = SystemClock.elapsedRealtime() - startAt
                if (pagesScanned >= DYNAMIC_SEARCH_MAX_PAGES ||
                    itemsScanned >= DYNAMIC_SEARCH_MAX_ITEMS ||
                    matched.size >= DYNAMIC_SEARCH_MAX_RESULTS ||
                    elapsed >= DYNAMIC_SEARCH_BUDGET_MS
                ) {
                    hitLimit = true
                    if (offset.isNotEmpty()) {
                        nextCheckpoint = DynamicSearchCheckpoint(mid, scope, keyword, offset)
                    }
                    break
                }
                val cacheKey = "$mid|$scope|$offset"
                val cachedPage = if (!forceRefresh) {
                    dynamicCache[cacheKey]?.takeIf { SystemClock.elapsedRealtime() - it.savedAtMs < DYNAMIC_CACHE_TTL_MS }?.value
                } else null
                val fromNetwork = cachedPage == null
                val page = if (cachedPage != null) {
                    cachedPage
                } else {
                    try {
                        val remainMs = (DYNAMIC_SEARCH_BUDGET_MS - elapsed).coerceAtLeast(1_500L)
                        val fetched = fetchSpaceDynamicPageBlocking(mid, offset, req, callTimeoutMs = remainMs)
                        putDynamicCache(cacheKey, fetched)
                        fetched
                    } catch (e: Exception) {
                        if (matched.isEmpty()) fatalError = readableSpaceError(e)
                        else {
                            hitLimit = true
                            if (offset.isNotEmpty()) {
                                nextCheckpoint = DynamicSearchCheckpoint(mid, scope, keyword, offset)
                            }
                        }
                        break
                    }
                }
                pagesScanned++
                val next = page.nextOffset?.trim().orEmpty()
                val pageHasValidNext = page.hasMore && page.items.isNotEmpty() && next.isNotEmpty() && next != offset
                var stoppedMidPage = false
                for (index in page.items.indices) {
                    val item = page.items[index]
                    if (item.id.isNotBlank() && seenIds.add(item.id)) {
                        itemsScanned++
                        if (matchesDynamicKeyword(item, keyword)) {
                            matched += item
                            if (matched.size >= DYNAMIC_SEARCH_MAX_RESULTS) {
                                hitLimit = true
                                val nextIndex = index + 1
                                if (nextIndex < page.items.size) {
                                    stoppedMidPage = true
                                    nextCheckpoint = DynamicSearchCheckpoint(
                                        mid = mid,
                                        sessionScope = scope,
                                        keyword = keyword,
                                        nextOffset = if (pageHasValidNext) next else "",
                                        pendingItems = page.items,
                                        pendingStartIndex = nextIndex
                                    )
                                } else if (pageHasValidNext) {
                                    nextCheckpoint = DynamicSearchCheckpoint(mid, scope, keyword, next)
                                } else {
                                    reachedEnd = true
                                }
                                break
                            }
                        }
                    }
                }
                if (stoppedMidPage || hitLimit) break
                if (!pageHasValidNext) {
                    reachedEnd = true
                    nextCheckpoint = null
                    break
                }
                offset = next
                nextCheckpoint = DynamicSearchCheckpoint(mid, scope, keyword, next)
                if (fromNetwork && pagesScanned < DYNAMIC_SEARCH_MAX_PAGES) {
                    try {
                        Thread.sleep(160L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            if (req.cancelled) return@executeSpace
            if (fatalError != null && matched.isEmpty()) {
                mainHandler.post { if (!req.cancelled) callback(null, fatalError) }
            } else {
                val canContinue = !reachedEnd && nextCheckpoint != null
                val pageResult = CursorPage(
                    items = matched,
                    nextOffset = if (canContinue) nextCheckpoint?.nextOffset else null,
                    hasMore = canContinue,
                    limited = hitLimit && canContinue,
                    scannedItems = itemsScanned,
                    checkpoint = if (canContinue) nextCheckpoint else null
                )
                mainHandler.post { if (!req.cancelled) callback(pageResult, null) }
            }
        }
        return req
    }

    // ── 内部请求执行与风控重签 ─────────────────────────────────────

    private fun fetchAccInfoWithRetry(
        mid: Long,
        referer: String,
        req: BiliRequest
    ): Pair<JSONObject?, String?> {
        var lastError: String? = null
        repeat(2) { attempt ->
            if (req.cancelled) return null to null
            try {
                val params = LinkedHashMap<String, String>().apply {
                    put("mid", mid.toString())
                    put("token", "")
                    put("platform", "web")
                    put("web_location", "1550101")
                    putAll(dmFingerprintParams)
                }
                val (signedQuery, keyVersion) = BiliWbiSign.signParamsWithVersion(params)
                val root = getSpaceJson(
                    "https://api.bilibili.com/x/space/wbi/acc/info?$signedQuery",
                    referer,
                    req
                )
                val code = root.optInt("code", -1)
                if (code == 0) return root to null
                if (code == -412) {
                    markRisk412()
                    return null to readableBiliCode(code, root.optString("message"))
                }
                val hasVoucher = root.optJSONObject("data")?.optString("v_voucher")?.isNotBlank() == true
                if (attempt == 0 && (code == -352 || code == -403 || code == -401 || hasVoucher)) {
                    BiliWbiSign.invalidateKey(keyVersion)
                    lastError = readableBiliCode(code, root.optString("message"))
                    return@repeat
                }
                return null to readableBiliCode(code, root.optString("message"))
            } catch (e: Exception) {
                lastError = readableSpaceError(e)
                if (attempt == 0 && e is BiliCodeException && e.code in setOf(-352, -403, -401)) {
                    BiliWbiSign.invalidateKey()
                } else {
                    return null to lastError
                }
            }
        }
        return null to lastError
    }

    private fun fetchWbiRelationState(mid: Long, referer: String, req: BiliRequest): FollowState? {
        return try {
            val (signedQuery, _) = BiliWbiSign.signParamsWithVersion(
                linkedMapOf(
                    "mid" to mid.toString(),
                    "web_location" to "1550101"
                )
            )
            val root = getSpaceJsonSafely(
                "https://api.bilibili.com/x/space/wbi/acc/relation?$signedQuery",
                referer,
                req
            ) ?: getSpaceJsonSafely(
                "https://api.bilibili.com/x/space/acc/relation?mid=$mid",
                referer,
                req
            ) ?: return null
            if (root.optInt("code", -1) != 0) return null
            val data = root.optJSONObject("data") ?: return null
            val rel = data.optJSONObject("relation")
            val attr = rel?.optInt("attribute", -1) ?: data.optInt("attribute", -1)
            when (attr) {
                1, 2, 6 -> FollowState.FOLLOWED
                0, 128 -> FollowState.NOT_FOLLOWED
                else -> {
                    if (data.has("is_followed")) {
                        if (flag(data, "is_followed")) FollowState.FOLLOWED else FollowState.NOT_FOLLOWED
                    } else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchSpaceDynamicPageBlocking(
        mid: Long,
        offset: String,
        req: BiliRequest,
        callTimeoutMs: Long = 0L
    ): CursorPage<SpaceDynamic> {
        val referer = spaceReferer(mid)
        val params = LinkedHashMap<String, String>().apply {
            put("host_mid", mid.toString())
            if (offset.isNotEmpty()) put("offset", offset)
            put("timezone_offset", "-480")
            put("platform", "web")
            put("features", DYNAMIC_FEATURES)
            put("web_location", "333.1387")
        }
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?${encodeQuery(params)}"
        val root = getSpaceJson(url, referer, req, callTimeoutMs = callTimeoutMs)
        val code = root.optInt("code", -1)
        if (code == -412) {
            markRisk412()
            throw BiliCodeException(code, readableBiliCode(code, root.optString("message")))
        }
        if (code != 0) {
            throw BiliCodeException(code, readableBiliCode(code, root.optString("message")))
        }
        return parseDynamicFeedPage(root)
    }

    private fun fetchSpaceVideosWithRetry(
        mid: Long,
        page: Int,
        pageSize: Int,
        order: SpaceVideoOrder,
        keyword: String,
        referer: String,
        req: BiliRequest
    ): VideoPage {
        var lastException: Exception? = null
        repeat(2) { attempt ->
            if (req.cancelled) throw IOException("已取消")
            val params = LinkedHashMap<String, String>().apply {
                put("mid", mid.toString())
                put("ps", pageSize.toString())
                put("tid", "0")
                put("pn", page.toString())
                put("keyword", keyword)
                put("order", order.param)
                put("order_avoided", "true")
                put("platform", "web")
                put("web_location", "1550101")
                putAll(dmFingerprintParams)
            }
            val (signedQuery, keyVersion) = BiliWbiSign.signParamsWithVersion(params)
            val root = getSpaceJson(
                "https://api.bilibili.com/x/space/wbi/arc/search?$signedQuery",
                referer,
                req
            )
            val code = root.optInt("code", -1)
            if (code == 0) return parseSpaceVideoPage(root, requestedPage = page, requestedSize = pageSize)
            if (code == -412) {
                markRisk412()
                throw BiliCodeException(code, readableBiliCode(code, root.optString("message")))
            }
            val hasVoucher = root.optJSONObject("data")?.optString("v_voucher")?.isNotBlank() == true
            if (attempt == 0 && (code == -352 || code == -403 || code == -401 || hasVoucher)) {
                BiliWbiSign.invalidateKey(keyVersion)
                lastException = BiliCodeException(code, readableBiliCode(code, root.optString("message")))
                return@repeat
            }
            // -352/-401 刷新重签仍失败时，仅对非搜索、默认排序、首屏尝试公开 series 兜底，避免混源与排序错乱
            if (code in setOf(-352, -401, -403) &&
                keyword.isEmpty() &&
                order == SpaceVideoOrder.PUBDATE &&
                page == 1
            ) {
                val fallback = trySeriesFallback(mid, page, pageSize, keyword, referer, req)
                if (fallback != null) return fallback
            }
            throw BiliCodeException(code, readableBiliCode(code, root.optString("message")))
        }
        throw lastException ?: IOException("投稿加载失败")
    }

    private fun trySeriesFallback(
        mid: Long,
        page: Int,
        pageSize: Int,
        keyword: String,
        referer: String,
        req: BiliRequest
    ): VideoPage? {
        return try {
            val query = encodeQuery(
                linkedMapOf(
                    "mid" to mid.toString(),
                    "keywords" to keyword,
                    "pn" to page.toString(),
                    "ps" to pageSize.toString()
                )
            )
            val root = getSpaceJsonSafely(
                "https://api.bilibili.com/x/series/recArchivesByKeywords?$query",
                referer,
                req
            ) ?: return null
            if (root.optInt("code", -1) != 0) return null
            parseSeriesFallbackPage(root, page, pageSize)
        } catch (_: Exception) {
            null
        }
    }

    private fun getSpaceJsonSafely(url: String, referer: String, req: BiliRequest): JSONObject? =
        try {
            getSpaceJson(url, referer, req)
        } catch (_: Exception) {
            null
        }

    private fun getSpaceJson(
        url: String,
        referer: String,
        req: BiliRequest,
        callTimeoutMs: Long = 0L
    ): JSONObject {
        checkRiskCooldown()?.let { msg -> throw BiliCodeException(-412, msg) }
        val cookie = BiliPlayUrl.cookieHeader()
        val request: Request = BiliHttp.request(url)
            .header("Referer", referer)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        val call: Call = BiliHttp.client.newCall(request)
        if (callTimeoutMs > 0L) {
            call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        }
        if (!req.attach(call)) throw IOException("已取消")
        return try {
            call.execute().use { resp ->
                if (resp.code == 412) {
                    markRisk412()
                    throw BiliCodeException(-412, readableBiliCode(-412, ""))
                }
                BiliHttp.json(resp)
            }
        } finally {
            req.detach(call)
        }
    }

    private fun executeSpace(req: BiliRequest, onRejected: () -> Unit, task: () -> Unit) {
        try {
            spaceWorkers.execute {
                if (!req.cancelled) task()
            }
        } catch (_: RejectedExecutionException) {
            mainHandler.post { if (!req.cancelled) onRejected() }
        }
    }

    private fun spaceReferer(mid: Long): String = "https://space.bilibili.com/$mid/"

    private fun applyFollowBridge(header: SpaceHeader): SpaceHeader {
        if (header.isSelf) return header.copy(followState = FollowState.SELF)
        if (!BiliSessionStore.isLoggedIn()) return header
        val baseBool = when (header.followState) {
            FollowState.FOLLOWED -> true
            FollowState.NOT_FOLLOWED -> false
            else -> return header
        }
        val resolved = LikeStateBridge.resolveFollow(header.mid, baseBool)
        return header.copy(
            followState = if (resolved) FollowState.FOLLOWED else FollowState.NOT_FOLLOWED
        )
    }

    // ── 纯解析方法（供运行时与单元测试共同覆盖） ───────────────────────────

    internal fun parseAccInfo(
        root: JSONObject,
        mid: Long,
        isSelf: Boolean,
        loggedIn: Boolean
    ): Pair<SpaceHeader, Boolean> {
        val data = root.optJSONObject("data") ?: JSONObject()
        val parsedMid = data.optNullableLong("mid")?.takeIf { it > 0L } ?: mid
        val name = cleanHtml(data.optSafeString("name")).ifBlank { "哔哩哔哩用户" }
        val face = normalizeUrl(data.optSafeString("face")).ifBlank { null }
        val sign = cleanHtml(data.optSafeString("sign")).ifBlank { null }
        val levelInfo = data.optJSONObject("level_info")
        val level = clampSpaceLevel(levelInfo?.optNullableInt("current_level") ?: data.optNullableInt("level"))

        val vipObj = data.optJSONObject("vip")
        val vipStatus = vipObj?.optNullableInt("status") ?: data.optNullableInt("vip_status") ?: 0
        val vipLabel = resolveVipLabel(
            vipStatus = vipStatus,
            vipType = vipObj?.optNullableInt("type") ?: 0,
            labelText = cleanHtml(vipObj?.optJSONObject("label")?.optSafeString("text").orEmpty())
        )

        val hasFollowedField = data.has("is_followed") && !data.isNull("is_followed")
        val followState = resolveAccFollowState(
            isSelf = isSelf,
            loggedIn = loggedIn,
            hasFollowedField = hasFollowedField,
            isFollowed = flag(data, "is_followed")
        )
        val needExtraRelation = loggedIn && !isSelf && !hasFollowedField
        return SpaceHeader(
            mid = parsedMid,
            name = name,
            face = face,
            sign = sign,
            level = level,
            vipLabel = vipLabel,
            follower = data.optNullableLong("fans") ?: data.optNullableLong("follower"),
            following = data.optNullableLong("attention") ?: data.optNullableLong("following"),
            likes = null,
            followState = followState,
            isSelf = isSelf
        ) to needExtraRelation
    }

    internal fun parseCardFallback(root: JSONObject, mid: Long, isSelf: Boolean): SpaceHeader? {
        if (root.optInt("code", -1) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val card = data.optJSONObject("card") ?: return null
        val parsedMid = card.optSafeString("mid").toLongOrNull()?.takeIf { it > 0L }
            ?: card.optNullableLong("mid")?.takeIf { it > 0L }
            ?: mid
        val levelInfo = card.optJSONObject("level_info")
        val level = levelInfo?.optNullableInt("current_level")?.coerceIn(0, 6)
        val vipObj = card.optJSONObject("vip")
        val vipStatus = vipObj?.optNullableInt("status") ?: vipObj?.optNullableInt("vipStatus") ?: 0
        val vipLabel = if (vipStatus == 1) {
            cleanHtml(vipObj?.optJSONObject("label")?.optSafeString("text").orEmpty())
                .ifBlank { if ((vipObj?.optNullableInt("type") ?: 0) == 2) "年度大会员" else "大会员" }
        } else null
        return SpaceHeader(
            mid = parsedMid,
            name = cleanHtml(card.optSafeString("name")).ifBlank { "哔哩哔哩用户" },
            face = normalizeUrl(card.optSafeString("face")).ifBlank { null },
            sign = cleanHtml(card.optSafeString("sign")).ifBlank { null },
            level = level,
            vipLabel = vipLabel,
            follower = data.optNullableLong("follower") ?: card.optNullableLong("fans"),
            following = card.optNullableLong("attention") ?: card.optNullableLong("friend"),
            likes = data.optNullableLong("like_num"),
            followState = if (isSelf) FollowState.SELF else FollowState.UNKNOWN,
            isSelf = isSelf
        )
    }

    internal fun parseUpstatLikes(root: JSONObject?): Long? {
        if (root == null || root.optInt("code", -1) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        return data.optNullableLong("likes")
    }

    internal fun parseDynamicFeedPage(root: JSONObject): CursorPage<SpaceDynamic> {
        val data = root.optJSONObject("data") ?: JSONObject()
        val itemsArr = data.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<SpaceDynamic>(itemsArr.length())
        for (i in 0 until itemsArr.length()) {
            val obj = itemsArr.optJSONObject(i) ?: continue
            parseDynamicItem(obj)?.let { items += it }
        }
        val nextOffset = data.optSafeString("offset").ifBlank { null }
        val hasMore = flag(data, "has_more") && !nextOffset.isNullOrBlank() && items.isNotEmpty()
        return CursorPage(items = items, nextOffset = nextOffset, hasMore = hasMore)
    }

    internal fun parseDynamicItem(obj: JSONObject): SpaceDynamic? {
        val id = obj.optSafeString("id_str").ifBlank {
            obj.optNullableLong("id")?.toString().orEmpty()
        }
        val typeStr = obj.optSafeString("type")
        // 彻底移除转发动态：列表与解析层直接忽略转发动态条目
        if (typeStr == "DYNAMIC_TYPE_FORWARD" || (obj.has("orig") && !obj.isNull("orig"))) {
            return null
        }
        val visible = if (obj.has("visible") && !obj.isNull("visible")) obj.optBoolean("visible", true) else true
        val modules = obj.optJSONObject("modules") ?: JSONObject()

        val basic = obj.optJSONObject("basic")
        val rawCommentOid = basic?.optSafeString("comment_id_str")?.trim()?.ifBlank { null }
        val rawCommentType = basic?.optNullableInt("comment_type")?.takeIf { it > 0 }

        val author = modules.optJSONObject("module_author")
        val authorMid = author?.optNullableLong("mid")
        val authorName = author?.optSafeString("name")?.let(::cleanHtml)?.ifBlank { null }
        val authorFace = author?.optSafeString("face")?.let(::normalizeUrl)?.ifBlank { null }
        val pubTs = author?.optNullableLong("pub_ts")?.takeIf { it > 0L }
        val pubTime = author?.optSafeString("pub_time")?.let(::cleanHtml)?.ifBlank { null }

        // 置顶识别：优先 module_tag.text == "置顶"，兼容 is_top 与 module_more
        val tagText = modules.optJSONObject("module_tag")?.optSafeString("text").orEmpty().trim()
        val isPinned = tagText == "置顶" ||
            flag(obj, "is_top") ||
            flag(modules.optJSONObject("module_tag"), "is_top")

        val statMod = modules.optJSONObject("module_stat")
        val stats = DynamicStats(
            comment = parseStatCount(statMod?.optJSONObject("comment")),
            like = parseStatCount(statMod?.optJSONObject("like"))
        )

        val dynMod = modules.optJSONObject("module_dynamic")
        val descObj = dynMod?.optJSONObject("desc")
        var text = descObj?.optSafeString("text").orEmpty().trim()
        var richNodes = parseRichNodes(descObj?.optJSONArray("rich_text_nodes"))

        val major = dynMod?.optJSONObject("major")
        val majorType = major?.optSafeString("type").orEmpty()
        val images = ArrayList<SpaceImage>()
        var video: SpaceVideo? = null
        var jumpUrl: String? = null
        var opusTitle: String? = null
        var live: DynamicLive? = null

        val kind: DynamicKind
        when {
            !visible || majorType == "MAJOR_TYPE_NONE" -> {
                kind = DynamicKind.UNKNOWN
                if (text.isBlank()) {
                    text = major?.optJSONObject("none")?.optSafeString("tips")
                        ?.takeIf { it.isNotBlank() } ?: "该动态已失效"
                }
            }
            majorType == "MAJOR_TYPE_OPUS" -> {
                val opus = major?.optJSONObject("opus")
                val summary = opus?.optJSONObject("summary")
                if (text.isBlank()) {
                    text = summary?.optSafeString("text").orEmpty().trim()
                }
                if (richNodes.isEmpty()) {
                    richNodes = parseRichNodes(summary?.optJSONArray("rich_text_nodes"))
                }
                opusTitle = opus?.optSafeString("title").orEmpty().trim().ifBlank { null }
                opus?.optJSONArray("pics")?.let { pics ->
                    for (idx in 0 until pics.length()) {
                        val pic = pics.optJSONObject(idx) ?: continue
                        val url = normalizeUrl(pic.optSafeString("url"))
                        if (url.isNotBlank()) {
                            images += SpaceImage(
                                url = url,
                                width = pic.optNullableInt("width"),
                                height = pic.optNullableInt("height")
                            )
                        }
                    }
                }
                jumpUrl = opus?.optSafeString("jump_url")?.let(::normalizeUrl)?.ifBlank { null }
                kind = if (images.isNotEmpty()) DynamicKind.IMAGE else DynamicKind.TEXT
            }
            majorType == "MAJOR_TYPE_DRAW" -> {
                major?.optJSONObject("draw")?.optJSONArray("items")?.let { drawItems ->
                    for (idx in 0 until drawItems.length()) {
                        val pic = drawItems.optJSONObject(idx) ?: continue
                        val url = normalizeUrl(pic.optSafeString("src"))
                        if (url.isNotBlank()) {
                            images += SpaceImage(
                                url = url,
                                width = pic.optNullableInt("width"),
                                height = pic.optNullableInt("height")
                            )
                        }
                    }
                }
                kind = if (images.isNotEmpty()) DynamicKind.IMAGE else DynamicKind.TEXT
            }
            majorType == "MAJOR_TYPE_ARCHIVE" -> {
                val arc = major?.optJSONObject("archive")
                if (arc != null) {
                    val stat = arc.optJSONObject("stat")
                    val bvid = arc.optSafeString("bvid").ifBlank { null }
                    val aid = arc.optNullableLong("aid") ?: arc.optSafeString("aid").toLongOrNull()
                    val vJump = arc.optSafeString("jump_url").let(::normalizeUrl).ifBlank { null }
                    video = SpaceVideo(
                        aid = aid,
                        bvid = bvid,
                        title = cleanHtml(arc.optSafeString("title")).ifBlank { "未命名视频" },
                        cover = normalizeUrl(arc.optSafeString("cover")).ifBlank { null },
                        duration = arc.optSafeString("duration_text").ifBlank { null },
                        publishedAt = pubTs,
                        play = parseFlexibleCount(stat?.opt("play")),
                        danmaku = parseFlexibleCount(stat?.opt("danmaku")),
                        comments = null,
                        jumpUrl = vJump
                    )
                    jumpUrl = vJump
                }
                kind = if (video != null) DynamicKind.VIDEO else DynamicKind.UNKNOWN
            }
            majorType == "MAJOR_TYPE_LIVE_RCMD" || majorType == "MAJOR_TYPE_LIVE" -> {
                // 开播动态：解析直播间信息，卡片改为「xxx 开启了直播」并给一键进入直播间入口
                live = major?.let { parseDynamicLive(it) }
                kind = if (live != null) DynamicKind.LIVE else DynamicKind.UNKNOWN
                if (live != null && text.isBlank()) {
                    text = "${authorName ?: "TA"} 开启了直播"
                }
            }
            typeStr == "DYNAMIC_TYPE_WORD" || major == null -> {
                kind = DynamicKind.TEXT
            }
            else -> {
                kind = DynamicKind.UNKNOWN
                val summaryTitle = extractUnknownMajorTitle(major, majorType)
                jumpUrl = extractUnknownMajorJumpUrl(major, majorType)
                if (text.isBlank() && !summaryTitle.isNullOrBlank()) {
                    text = summaryTitle
                }
            }
        }

        if (jumpUrl.isNullOrBlank()) {
            jumpUrl = author?.optSafeString("jump_url")?.let(::normalizeUrl)?.ifBlank { null }
        }

        // 若 summary/desc 正文为空但有 paragraphs，用 paragraphs 兜底；否则保留详情页全文
        if (majorType == "MAJOR_TYPE_OPUS") {
            val opus = major?.optJSONObject("opus")
            val (paragraphsText, paragraphNodes) = extractOpusParagraphsText(opus?.optJSONArray("paragraphs"))
            if (paragraphsText.isNotBlank() && paragraphsText.length >= text.length) {
                text = paragraphsText
                if (paragraphNodes.isNotEmpty()) {
                    richNodes = (richNodes + paragraphNodes).distinctBy { "${it.type}|${it.text}|${it.emojiUrl}" }
                }
            }
        }

        val isPureVideo = kind == DynamicKind.VIDEO && text.isBlank() && opusTitle.isNullOrBlank() && images.isEmpty()
        val resolvedId = id.ifBlank { "${authorMid ?: 0}_${pubTs ?: 0}_${text.hashCode()}" }
        // 若接口返回了 comment_id_str / comment_type 则直接透传；无 basic 时：纯视频归属 type=1(aid)，其余（图文/文字/配文引用视频）兜底 type=17(dynamicId)。
        val resolvedCommentOid = rawCommentOid ?: when {
            isPureVideo -> video?.aid?.takeIf { it > 0L }?.toString()
            else -> resolvedId.takeIf { it.all(Char::isDigit) }
        }
        val resolvedCommentType = rawCommentType ?: when {
            isPureVideo && video?.aid != null && video.aid > 0L -> 1
            else -> 17
        }
        val supportsDynamicDetail = kind != DynamicKind.UNKNOWN && !isPureVideo && visible

        return SpaceDynamic(
            id = resolvedId,
            kind = kind,
            authorMid = authorMid,
            authorName = authorName,
            authorFace = authorFace,
            pubTs = pubTs,
            pubTime = pubTime,
            text = text,
            richNodes = richNodes,
            images = images,
            video = video,
            stats = stats,
            isPinned = isPinned,
            visible = visible,
            jumpUrl = jumpUrl,
            title = opusTitle,
            commentOid = resolvedCommentOid,
            commentType = resolvedCommentType,
            supportsDetail = supportsDynamicDetail,
            live = live
        )
    }

    /** 解析开播动态里的直播间信息；兼容 live_rcmd 的 JSON 字符串与 live 对象两种下发。 */
    private fun parseDynamicLive(major: JSONObject): DynamicLive? {
        val holder = major.optJSONObject("live_rcmd") ?: major.optJSONObject("live")
        val raw = holder?.optSafeString("content").orEmpty()
        val payload = if (raw.startsWith("{")) {
            runCatching { JSONObject(raw) }.getOrNull()
        } else {
            null
        }
        val info = payload?.optJSONObject("live_play_info") ?: payload ?: holder
        val roomId = info?.optNullableLong("room_id")
            ?: info?.optNullableLong("roomid")
            ?: holder?.optNullableLong("room_id")
            ?: 0L
        if (roomId <= 0L) return null
        val title = cleanHtml(info?.optSafeString("title").orEmpty())
        val cover = normalizeUrl(
            info?.optSafeString("cover").orEmpty().ifBlank {
                info?.optSafeString("cover_from_user").orEmpty()
            }
        )
        val area = cleanHtml(info?.optSafeString("area_name").orEmpty())
        val watched = info?.optJSONObject("watched_show")
        val online = watched?.optSafeString("text_large").orEmpty()
            .ifBlank { watched?.optSafeString("text_small").orEmpty() }
            .ifBlank { info?.optNullableLong("online")?.let(::formatNullableCount).orEmpty() }
        return DynamicLive(
            roomId = roomId,
            title = title,
            cover = cover,
            areaName = area,
            onlineText = online
        )
    }

    internal fun parseSpaceVideoPage(
        root: JSONObject,
        requestedPage: Int,
        requestedSize: Int
    ): VideoPage {
        val data = root.optJSONObject("data") ?: JSONObject()
        val listObj = data.optJSONObject("list")
        val vlist = listObj?.optJSONArray("vlist") ?: JSONArray()
        val items = ArrayList<SpaceVideo>(vlist.length())
        for (i in 0 until vlist.length()) {
            val item = vlist.optJSONObject(i) ?: continue
            val bvid = item.optSafeString("bvid").ifBlank { null }
            val aid = item.optNullableLong("aid")
            if (bvid == null && (aid == null || aid <= 0L)) continue
            items += SpaceVideo(
                aid = aid,
                bvid = bvid,
                title = cleanHtml(item.optSafeString("title")).ifBlank { "未命名视频" },
                cover = normalizeUrl(item.optSafeString("pic")).ifBlank { null },
                duration = item.optSafeString("length").ifBlank { null },
                publishedAt = item.optNullableLong("created"),
                play = parseFlexibleCount(item.opt("play")),
                danmaku = parseFlexibleCount(item.opt("video_review")),
                comments = parseFlexibleCount(item.opt("comment")),
                jumpUrl = null
            )
        }
        val pageObj = data.optJSONObject("page")
        val serverPn = pageObj?.optNullableInt("pn") ?: requestedPage
        val serverPs = pageObj?.optNullableInt("ps") ?: requestedSize
        val total = pageObj?.optNullableLong("count")
        val hasMore = computeVideoHasMore(serverPn, serverPs, total, items.size)
        return VideoPage(items = items, page = serverPn, total = total, hasMore = hasMore)
    }

    internal fun clampSpaceLevel(raw: Int?): Int? = raw?.coerceIn(0, 6)

    internal fun resolveVipLabel(vipStatus: Int, vipType: Int, labelText: String): String? {
        if (vipStatus != 1) return null
        val cleaned = labelText.trim()
        if (cleaned.isNotEmpty()) return cleaned
        return when (vipType) {
            2 -> "年度大会员"
            else -> "大会员"
        }
    }

    internal fun resolveAccFollowState(
        isSelf: Boolean,
        loggedIn: Boolean,
        hasFollowedField: Boolean,
        isFollowed: Boolean
    ): FollowState = when {
        isSelf -> FollowState.SELF
        !loggedIn -> FollowState.UNKNOWN
        hasFollowedField -> if (isFollowed) FollowState.FOLLOWED else FollowState.NOT_FOLLOWED
        else -> FollowState.UNKNOWN
    }

    internal fun computeVideoHasMore(
        serverPn: Int,
        serverPs: Int,
        total: Long?,
        itemCount: Int
    ): Boolean {
        if (itemCount <= 0) return false
        return if (total != null && total >= 0L) {
            serverPn.toLong() * serverPs.toLong() < total
        } else {
            itemCount >= serverPs
        }
    }

    internal fun normalizeSpaceUrl(value: String): String = normalizeUrl(value)

    private fun parseSeriesFallbackPage(root: JSONObject, requestedPage: Int, requestedSize: Int): VideoPage {
        val data = root.optJSONObject("data") ?: JSONObject()
        val archives = data.optJSONArray("archives") ?: JSONArray()
        val items = ArrayList<SpaceVideo>(archives.length())
        for (i in 0 until archives.length()) {
            val arc = archives.optJSONObject(i) ?: continue
            val bvid = arc.optSafeString("bvid").ifBlank { null }
            val aid = arc.optNullableLong("aid")
            if (bvid == null && (aid == null || aid <= 0L)) continue
            val durSec = arc.optNullableInt("duration") ?: 0
            val stat = arc.optJSONObject("stat")
            items += SpaceVideo(
                aid = aid,
                bvid = bvid,
                title = cleanHtml(arc.optSafeString("title")).ifBlank { "未命名视频" },
                cover = normalizeUrl(arc.optSafeString("pic")).ifBlank { null },
                duration = if (durSec > 0) formatDuration(durSec) else null,
                publishedAt = arc.optNullableLong("pubdate") ?: arc.optNullableLong("ctime"),
                play = parseFlexibleCount(stat?.opt("view")),
                danmaku = parseFlexibleCount(stat?.opt("danmaku")),
                comments = parseFlexibleCount(stat?.opt("reply")),
                jumpUrl = null
            )
        }
        val pageObj = data.optJSONObject("page")
        val total = pageObj?.optNullableLong("total") ?: pageObj?.optNullableLong("count")
        val hasMore = if (total != null) {
            requestedPage.toLong() * requestedSize.toLong() < total && items.isNotEmpty()
        } else {
            items.size >= requestedSize
        }
        return VideoPage(items = items, page = requestedPage, total = total, hasMore = hasMore, usedFallback = true)
    }

    internal fun matchesDynamicKeyword(item: SpaceDynamic, keyword: String): Boolean {
        val needle = keyword.trim()
        if (needle.isEmpty()) return false
        if (item.title?.contains(needle, ignoreCase = true) == true) return true
        if (item.text.contains(needle, ignoreCase = true)) return true
        if (item.video?.title?.contains(needle, ignoreCase = true) == true) return true
        return false
    }

    internal fun limitUnicodeCodePoints(input: String, maxCodePoints: Int): String {
        if (input.codePointCount(0, input.length) <= maxCodePoints) return input
        val endIndex = input.offsetByCodePoints(0, maxCodePoints)
        return input.substring(0, endIndex)
    }

    internal fun spaceVideoToVideoItem(video: SpaceVideo, ownerName: String, ownerMid: Long): VideoItem {
        val durationSec = parseDurationStringToSec(video.duration)
        return VideoItem(
            bvid = video.bvid.orEmpty(),
            title = video.title,
            owner = ownerName.ifBlank { "哔哩哔哩用户" },
            cover = video.cover.orEmpty(),
            views = video.play ?: 0L,
            duration = durationSec,
            description = "",
            publishedAt = video.publishedAt ?: 0L,
            aid = video.aid ?: 0L,
            ownerMid = ownerMid,
            replyCount = video.comments ?: 0L,
            danmakuCount = video.danmaku ?: 0L
        )
    }

    internal fun parseDurationStringToSec(raw: String?): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return 0
        return parts.fold(0) { acc, part -> acc * 60 + part }
    }

    private fun extractOpusParagraphsText(paragraphs: JSONArray?): Pair<String, List<RichNode>> {
        if (paragraphs == null || paragraphs.length() == 0) return "" to emptyList()
        val sb = StringBuilder()
        val extraNodes = ArrayList<RichNode>()
        for (i in 0 until paragraphs.length()) {
            val p = paragraphs.optJSONObject(i) ?: continue
            val textObj = p.optJSONObject("text") ?: continue
            val nodes = textObj.optJSONArray("nodes") ?: continue
            val line = StringBuilder()
            for (j in 0 until nodes.length()) {
                val n = nodes.optJSONObject(j) ?: continue
                val type = n.optSafeString("type")
                val wordObj = n.optJSONObject("word")
                val emojiObj = n.optJSONObject("emoji")
                val richObj = n.optJSONObject("rich")
                val wordText = wordObj?.optSafeString("words").orEmpty()
                val emojiText = emojiObj?.optSafeString("text").orEmpty()
                val emojiUrl = emojiObj?.optSafeString("icon_url")?.let(::normalizeUrl)?.ifBlank { null }
                val richText = richObj?.optSafeString("text").orEmpty()
                val richType = richObj?.optSafeString("type").orEmpty()
                val richJump = richObj?.optSafeString("jump_url")?.let(::normalizeUrl)?.ifBlank { null }
                when {
                    emojiText.isNotEmpty() || emojiUrl != null -> {
                        line.append(emojiText)
                        if (emojiText.isNotEmpty() && emojiUrl != null) {
                            extraNodes += RichNode(text = emojiText, type = type, emojiUrl = emojiUrl)
                        }
                    }
                    richText.isNotEmpty() -> {
                        line.append(richText)
                        extraNodes += RichNode(
                            text = richText,
                            type = richType.ifBlank { type },
                            jumpUrl = richJump
                        )
                    }
                    wordText.isNotEmpty() -> line.append(wordText)
                }
            }
            if (line.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        }
        return sb.toString().trim() to extraNodes
    }

    private fun parseRichNodes(array: JSONArray?): List<RichNode> {
        if (array == null || array.length() == 0) return emptyList()
        val list = ArrayList<RichNode>(array.length())
        for (i in 0 until array.length()) {
            val node = array.optJSONObject(i) ?: continue
            val type = node.optSafeString("type")
            val text = node.optSafeString("orig_text").ifBlank { node.optSafeString("text") }
            val emojiUrl = node.optJSONObject("emoji")?.optSafeString("icon_url")
                ?.let(::normalizeUrl)?.ifBlank { null }
            val jump = node.optSafeString("jump_url").let(::normalizeUrl).ifBlank { null }
            if (text.isNotEmpty() || emojiUrl != null) {
                list += RichNode(
                    text = text,
                    type = type,
                    emojiUrl = emojiUrl,
                    jumpUrl = jump
                )
            }
        }
        return list
    }

    private fun parseStatCount(statObj: JSONObject?): Long? {
        if (statObj == null) return null
        if (flag(statObj, "forbidden")) return null
        return statObj.optNullableLong("count")
    }

    private fun extractUnknownMajorTitle(major: JSONObject?, majorType: String): String? {
        if (major == null) return null
        val sub = when (majorType) {
            "MAJOR_TYPE_ARTICLE" -> major.optJSONObject("article")
            "MAJOR_TYPE_PGC" -> major.optJSONObject("pgc")
            "MAJOR_TYPE_LIVE" -> major.optJSONObject("live")
            "MAJOR_TYPE_LIVE_RCMD" -> major.optJSONObject("live_rcmd")
            "MAJOR_TYPE_COURSES" -> major.optJSONObject("courses")
            "MAJOR_TYPE_MUSIC" -> major.optJSONObject("music")
            "MAJOR_TYPE_COMMON" -> major.optJSONObject("common")
            "MAJOR_TYPE_UGC_SEASON" -> major.optJSONObject("ugc_season")
            else -> null
        } ?: return null
        val direct = cleanHtml(sub.optSafeString("title")).ifBlank {
            cleanHtml(sub.optSafeString("desc"))
        }
        return direct.ifBlank { null }
    }

    private fun extractUnknownMajorJumpUrl(major: JSONObject?, majorType: String): String? {
        if (major == null) return null
        val sub = when (majorType) {
            "MAJOR_TYPE_ARTICLE" -> major.optJSONObject("article")
            "MAJOR_TYPE_PGC" -> major.optJSONObject("pgc")
            "MAJOR_TYPE_LIVE" -> major.optJSONObject("live")
            "MAJOR_TYPE_COURSES" -> major.optJSONObject("courses")
            "MAJOR_TYPE_MUSIC" -> major.optJSONObject("music")
            "MAJOR_TYPE_COMMON" -> major.optJSONObject("common")
            "MAJOR_TYPE_UGC_SEASON" -> major.optJSONObject("ugc_season")
            else -> null
        } ?: return null
        return sub.optSafeString("jump_url").let(::normalizeUrl).ifBlank { null }
    }

    private fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

    private fun formatSpaceError(code: Int, rawMsg: String): String = readableBiliCode(code, rawMsg)

    /** 关注/取关 UP（携带对应空间页 Referer 与明确错误码映射，不自动重试）。 */
    fun modifyRelationForSpace(fid: Long, follow: Boolean, callback: (Boolean, String?) -> Unit) {
        if (fid <= 0L) { callback(false, "无效的用户 ID"); return }
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录后再关注"); return }
        val csrf = BiliSessionStore.cookie()
            .substringAfter("bili_jct=", "")
            .substringBefore(";")
            .trim()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整，请重新登录"); return }
        val req = BiliRequest()
        executeSpace(req, onRejected = { callback(false, "操作过快，请稍后再试") }) {
            try {
                val body = okhttp3.FormBody.Builder()
                    .add("fid", fid.toString())
                    .add("act", if (follow) "1" else "2")
                    .add("re_src", "11")
                    .add("csrf", csrf)
                    .build()
                val request = BiliHttp.request("https://api.bilibili.com/x/relation/modify")
                    .header("Origin", "https://space.bilibili.com")
                    .header("Referer", spaceReferer(fid))
                    .header("Cookie", BiliPlayUrl.cookieHeader())
                    .post(body)
                    .build()
                val root = BiliHttp.client.newCall(request).execute().use(BiliHttp::json)
                val code = root.optInt("code", -1)
                if (code == 0 || (follow && code == 22014)) {
                    invalidateProfileCache(fid)
                    mainHandler.post { callback(true, null) }
                } else {
                    mainHandler.post { callback(false, formatSpaceError(code, root.optString("message"))) }
                }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableSpaceError(error)) }
            }
        }
    }

    private fun readableBiliCode(code: Int, rawMsg: String): String = when (code) {
        -101 -> "登录已过期，请重新登录"
        -111 -> "登录凭证校验失败，请重新登录"
        -352 -> "触发安全校验，请稍后再试"
        -401 -> "接口访问受限，请稍后再试"
        -403 -> "暂无访问权限"
        -412 -> "请求受限，请约 10 分钟后再试"
        -799 -> "请求过于频繁，请稍后再试"
        22001 -> "不能关注自己"
        22002 -> "对方已设置隐私保护，暂无法关注"
        22013 -> "该账号已注销"
        else -> rawMsg.trim().ifBlank { "请求失败（$code）" }
    }

    private fun readableSpaceError(error: Exception): String = when (error) {
        is BiliCodeException -> error.message
        else -> error.message?.trim().orEmpty().ifBlank { "网络暂不可用，请稍后再试" }
    }

    private fun normalizeUrl(value: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://${value.removePrefix("http://")}"
        else -> value
    }

    private fun cleanHtml(value: String): String = value
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .trim()

    private fun flag(source: JSONObject?, name: String): Boolean {
        if (source == null || !source.has(name) || source.isNull(name)) return false
        return source.optBoolean(name, false) ||
            source.optInt(name, 0) == 1 ||
            source.optString(name) == "1"
    }

    private fun JSONObject.optSafeString(key: String): String {
        if (!has(key) || isNull(key)) return ""
        return optString(key, "").let { if (it == "null") "" else it }
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    internal fun parseFlexibleCount(value: Any?): Long? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toLong()
        is String -> {
            val s = value.trim()
            when {
                s.isEmpty() || s == "--" || s == "-" -> null
                s.endsWith("万") -> s.removeSuffix("万").toDoubleOrNull()?.let { (it * 10_000.0).toLong() }
                s.endsWith("亿") -> s.removeSuffix("亿").toDoubleOrNull()?.let { (it * 100_000_000.0).toLong() }
                else -> s.toLongOrNull()
            }
        }
        else -> null
    }

    /** 将点赞/转发/关注/粉丝/获赞等可空计数格式化为克制紧凑文本；null 返回 "--"。 */
    internal fun formatNullableCount(value: Long?): String {
        if (value == null) return "--"
        if (value == 0L) return "0"
        return when {
            value >= 100_000_000L -> compactOneDecimal(value / 100_000_000.0) + "亿"
            value >= 10_000L -> compactOneDecimal(value / 10_000.0) + "万"
            else -> value.toString()
        }
    }

    private fun compactOneDecimal(raw: Double): String {
        val rounded = Math.round(raw * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString()
        else String.format(Locale.CHINA, "%.1f", rounded)
    }
}
