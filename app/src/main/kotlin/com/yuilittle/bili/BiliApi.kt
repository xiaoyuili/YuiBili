package com.yuilittle.bili

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class VideoItem(
    val bvid: String,
    val title: String,
    val owner: String,
    val cover: String,
    val views: Long,
    val duration: Int,
    val description: String,
    /** Bilibili pubdate/ctime, in epoch seconds; 0 means the source omitted it. */
    val publishedAt: Long = 0L,
    val cid: Long = 0L,
    val aid: Long = 0L,
    val ownerMid: Long = 0L,
    val ownerAvatar: String = "",
    /** Total comment count (detail stat.reply / search review), 0 when unknown. */
    val replyCount: Long = 0L,
    /** Danmaku count (stat.danmaku / rcmd stat.danmaku), 0 when unknown. */
    val danmakuCount: Long = 0L,
    /** Recommendation reason badge from feed API (e.g. "2万点赞"). */
    val rcmdReason: String = "",
    /** Like count (detail stat.like), 0 when unknown. */
    val likes: Long = 0L,
    /** Whether the signed-in account already liked this video (req_user.like). */
    val liked: Boolean = false,
    /**
     * Whether the signed-in account already follows this UP.
     * Filled asynchronously via relation API (null/false until queried).
     */
    val following: Boolean = false,
    /** 视频所属合集；仅详情接口(view)返回 ugc_season 时非空，无合集为 null。 */
    val season: UgcSeason? = null,
    /** 是否充电（付费）视频：仅接受接口明确给出的专属/付费标记，不以流时长比例猜测。 */
    val charge: Boolean = false,
    /** 历史记录观看进度（秒）：-1 表示已看完，0 表示未记录或刚开头，>0 为已观看秒数。 */
    val watchProgressSec: Long = 0L
)

/** 判断评论/回复作者是否为视频 UP 主（服务端标记优先，其次按 mid 匹配）。 */
internal fun isCommentFromUp(comment: VideoComment, videoOwnerMid: Long): Boolean {
    if (comment.isUp) return true
    return videoOwnerMid > 0L && comment.userMid == videoOwnerMid
}

/** A single comment (top-level or in-floor) from the reply/main endpoint. */
data class VideoComment(
    val id: Long,
    val user: String,
    val avatar: String,
    val content: String,
    val likes: Long,
    val publishedAt: Long,
    /** 评论作者 mid（来自 reply.mid / reply.member.mid）。 */
    val userMid: Long = 0L,
    /** IP 属地（来自 reply.reply_control.location，如 "浙江"，可能为空）。 */
    val location: String = "",
    /** Official Bilibili emote images inside the message: placeholder name -> image URL. */
    val emotes: Map<String, String> = emptyMap(),
    /** User-uploaded comment images (pictures array) with intrinsic size. */
    val pictures: List<CommentPicture> = emptyList(),
    /** In-floor (sub) replies already returned with this comment. */
    val replies: List<VideoComment> = emptyList(),
    /** Total count of sub replies (rcount); 0 means no replies at all. */
    val replyCount: Long = 0L,
    /** 评论里的跳转链接（B 站富文本）：显示文案 → 目标 URL。 */
    val jumpLinks: Map<String, String> = emptyMap(),
    /** Whether the signed-in account already liked this comment (action == 1). */
    val liked: Boolean = false,
    /** Whether this comment is pinned at the top by the UP主 (data.top.upper / data.top_replies). */
    val isPinned: Boolean = false,
    /** Whether this comment author is the video's UP主 (matched by data.upper.mid or reply.reply_control.is_up_flag). */
    val isUp: Boolean = false,
    val mentions: Map<String, Long> = emptyMap(),
    val requestedAtNanos: Long = 0L
)

/** A user-uploaded comment image with its intrinsic width/height in pixels. */
data class CommentPicture(
    val url: String,
    val width: Int = 0,
    val height: Int = 0
)

internal data class CommentPage(
    val items: List<VideoComment>,
    val nextOffset: String?,
    val hasMore: Boolean,
    val failureReason: String? = null
)

/** A user-created favorite folder (from x/v3/fav/folder/created/list-all). */
data class FavoriteFolder(
    val id: Long,
    val name: String,
    val count: Long,
    /** 创建者昵称（来自 upper.name），可能为空。 */
    val owner: String = "",
    /** 是否公开（attr 为 0 表示公开，非 0 为私密）。 */
    val isPublic: Boolean = true,
    /** 收藏夹自定义封面 URL，为空表示未设置。 */
    val cover: String = ""
)

/** 合集（ugc_season）中的一集，来自 view 接口 data.ugc_season.sections[].episodes[]。 */
data class UgcEpisode(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val title: String,
    val duration: Int,
    /** 分集独立封面；接口未给（如多P pages 形态）时为空串，由调用方回退主视频封面。 */
    val cover: String = "",
    /** 分集发布时间戳（秒，来自 ugc_season.episodes[].arc.pubdate / ctime），0 表示未知（如多P）。 */
    val publishedAt: Long = 0L,
    /** 分集本身是否带有明确的付费/充电专属标记。 */
    val charge: Boolean = false,
    /** UP 主昵称（收藏夹队列列表需要展示）。 */
    val owner: String = ""
)

/** 视频所属合集（来自 view 接口 data.ugc_season）；episodes 按接口顺序展平。 */
data class UgcSeason(
    val id: Long,
    val title: String,
    val cover: String,
    val episodes: List<UgcEpisode>,
    /** true 表示同一 BV 的 pages 多 P，不是跨 BV 的 ugc_season。 */
    val isMultiPage: Boolean = false
)

/**
 * PGC 条目（番剧/电影/国创/纪录片等），来自搜索 media_bangumi / media_ft。
 * seasonType 与 B 站 show_season_type 一致：1 番剧 2 电影 3 纪录片 4 国创 5 电视剧 7 综艺。
 */
data class PgcMedia(
    val mediaId: Long,
    val seasonId: Long,
    val title: String,
    val cover: String,
    val seasonType: Int,
    val typeName: String,
    val score: String = "",
    val epCount: Int = 0,
    val areas: String = "",
    val desc: String = ""
)

/** PGC 分集，来自 pgc/view/web/season 的 episodes[]；duration 单位是毫秒。 */
data class PgcEpisode(
    val epId: Long,
    val cid: Long,
    val aid: Long,
    val bvid: String,
    val title: String,
    val durationMs: Long,
    val cover: String = "",
    val badge: String = "",
    /** 1 表示预告/花絮，不是正片。 */
    val sectionType: Int = 0
)

/** PGC 同系列其他季/版本，来自 pgc/view/web/season 的 seasons[]。 */
data class PgcSeasonRef(
    val seasonId: Long,
    val title: String,
    val badge: String = ""
)

/** PGC 详情（season 接口），episodes 仅含正片与可播条目。 */
data class PgcSeasonDetail(
    val media: PgcMedia,
    val evaluate: String,
    val episodes: List<PgcEpisode>,
    val isFinish: Boolean,
    val seasons: List<PgcSeasonRef> = emptyList()
)

/** One downloadable quality entry (qn + label + required privilege). */
data class DownloadQuality(
    val qn: Int,
    val label: String,
    val note: String,
    val needsVip: Boolean
)

/** A successfully parsed feed page; protocol failures use the error callback. */
data class FeedPage(
    val items: List<VideoItem>,
    val endReached: Boolean
)

internal object BiliHttp {
    val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(28, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    val mediaClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun request(address: String): Request.Builder = Request.Builder().url(address)
        .header("User-Agent", NETWORK_USER_AGENT)
        .header("Referer", "https://www.bilibili.com/")
        .header("Accept", "application/json, text/plain, */*")

    fun json(response: Response): JSONObject {
        val body = response.body?.string().orEmpty()
        if (body.isBlank()) throw IOException("网络请求没有返回内容（HTTP ${response.code}）")
        return JSONObject(body)
    }
}

internal class BiliRequest {
    private data class CancelState(val call: Call?, val task: Runnable?, val executor: ThreadPoolExecutor?, val hook: (() -> Unit)?)

    @Volatile var cancelled = false
        private set
    private var call: Call? = null
    private var cancelHook: (() -> Unit)? = null
    private var queuedTask: Runnable? = null
    private var queuedExecutor: ThreadPoolExecutor? = null

    fun onCancel(hook: () -> Unit) {
        val invoke = synchronized(this) {
            if (cancelled) true else { cancelHook = hook; false }
        }
        if (invoke) hook()
    }

    fun enqueue(executor: ThreadPoolExecutor, task: Runnable): Boolean {
        synchronized(this) {
            if (cancelled) return false
            queuedTask = task
            queuedExecutor = executor
        }
        return try {
            executor.execute(task)
            synchronized(this) {
                if (cancelled) { executor.remove(task); false } else true
            }
        } catch (_: RejectedExecutionException) {
            synchronized(this) {
                if (queuedTask === task) { queuedTask = null; queuedExecutor = null }
            }
            false
        }
    }

    fun isActive(): Boolean = !cancelled

    fun startTask(task: Runnable): Boolean = synchronized(this) {
        if (queuedTask === task) { queuedTask = null; queuedExecutor = null }
        !cancelled
    }

    fun ensureActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw IOException("评论请求已取消")
    }

    fun attach(next: Call): Boolean = synchronized(this) {
        if (cancelled) false else { call = next; true }
    }

    fun detach(completed: Call) = synchronized(this) { if (call === completed) call = null }

    fun cancel() {
        val state = synchronized(this) {
            if (cancelled) return
            cancelled = true
            CancelState(call, queuedTask, queuedExecutor, cancelHook).also {
                call = null
                queuedTask = null
                queuedExecutor = null
                cancelHook = null
            }
        }
        if (state.task != null) state.executor?.remove(state.task)
        state.call?.cancel()
        state.hook?.invoke()
    }
}

object BiliApi {
    private const val PAGE_SIZE = FeedPagingPolicy.PAGE_SIZE
    private const val RECOMMEND_URL = "https://api.bilibili.com/x/web-interface/index/top/feed/rcmd?y_num=$PAGE_SIZE&fresh_type=3&feed_version=V8&ps=$PAGE_SIZE"
    private const val POPULAR_URL = "https://api.bilibili.com/x/web-interface/popular?ps=$PAGE_SIZE"
    private const val DETAIL_URL = "https://api.bilibili.com/x/web-interface/view?bvid="
    private const val SEARCH_URL = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword="
    private const val SUGGEST_URL = "https://s.search.bilibili.com/main/suggest?term="
    private const val FINGERPRINT_URL = "https://api.bilibili.com/x/frontend/finger/spi"
    private const val MAX_NETWORK_QUEUE = 16
    private const val MAX_SEARCH_QUEUE = 8
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val networkWorkers = ThreadPoolExecutor(
        3,
        3,
        20L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(MAX_NETWORK_QUEUE),
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    // 收藏夹封面元数据走独立池，避免被主请求队列（3 线程 + 16 有界队列）拒绝。
    private val folderMetaWorkers = ThreadPoolExecutor(
        2, 2, 20L, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(128), ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    // Suggestions and searches share one serial lane. A submitted search can no
    // longer race an in-flight suggestion and trip Bilibili's anonymous rate limit.
    private val searchWorkers = ThreadPoolExecutor(
        1,
        1,
        20L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(MAX_SEARCH_QUEUE),
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    // PGC 搜索走独立池，避免被 UGC 搜索/联想词的单线程队列阻塞或 AbortPolicy 拒绝。
    private val pgcSearchWorkers = ThreadPoolExecutor(
        2, 2, 20L, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(64), ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    private val suggestionGeneration = AtomicInteger(0)
    @Volatile private var anonymousSearchCookie = ""

    /** Bump the generation so any in-flight suggestion callback is ignored. */
    fun cancelSuggestions() {
        suggestionGeneration.incrementAndGet()
    }

    fun fetchRecommend(page: Int, token: Long, callback: (FeedPage?, String?) -> Unit): Call {
        val index = page.coerceAtLeast(1)
        return requestFeed("$RECOMMEND_URL&pn=$index&fresh_idx=$index&_=$token", callback) { root ->
            root.optJSONObject("data")?.optJSONArray("item")
        }
    }

    fun fetchPopular(page: Int, token: Long, callback: (FeedPage?, String?) -> Unit): Call {
        return requestFeed("$POPULAR_URL&pn=${page.coerceAtLeast(1)}&_=$token", callback, checkNoMore = true) { root ->
            root.optJSONObject("data")?.optJSONArray("list")
        }
    }

    fun suggest(keyword: String, callback: (List<String>?, String?) -> Unit) {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val requestGeneration = suggestionGeneration.incrementAndGet()
        executeSearch(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            if (requestGeneration != suggestionGeneration.get()) return@executeSearch
            try {
                val anon = ensureAnonymousSearchCookie()
                val root = searchJson(SUGGEST_URL + encoded, anon)
                ensureSuccess(root)
                if (requestGeneration != suggestionGeneration.get()) return@executeSearch
                val tags = root.optJSONObject("result")?.optJSONArray("tag") ?: JSONArray()
                val values = LinkedHashSet<String>()
                for (index in 0 until tags.length()) {
                    val tag = tags.optJSONObject(index) ?: continue
                    val value = cleanHtml(tag.optString("value").ifBlank { tag.optString("term") })
                    if (value.isNotBlank()) values += value
                    if (values.size >= 8) break
                }
                mainHandler.post { callback(values.toList(), null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    fun search(keyword: String, page: Int = 1, order: String = "", duration: Int = 0, callback: (List<VideoItem>?, String?) -> Unit) {
        cancelSuggestions()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val params = StringBuilder(SEARCH_URL + encoded + "&page=${page.coerceAtLeast(1)}&__refresh__=true")
        if (order.isNotBlank()) params.append("&order=").append(order)
        if (duration > 0) params.append("&duration=").append(duration)
        executeSearch(onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }) {
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    val anon = ensureAnonymousSearchCookie()
                    val root = searchJson(params.toString(), anon)
                    ensureSuccess(root)
                    val array = root.optJSONObject("data")?.optJSONArray("result") ?: JSONArray()
                    val videos = ArrayList<VideoItem>()
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { videos += videoFromJson(it) }
                    }
                    mainHandler.post { callback(videos, null) }
                    return@executeSearch
                } catch (error: Exception) {
                    lastError = error
                    if (attempt == 0 && isRequestBanned(error)) {
                        anonymousSearchCookie = ""
                        Thread.sleep(650L)
                    } else {
                        mainHandler.post { callback(null, readableError(error)) }
                        return@executeSearch
                    }
                }
            }
            mainHandler.post { callback(null, readableError(lastError ?: IllegalStateException("搜索暂不可用"))) }
        }
    }

    /**
     * PGC 搜索（番剧 media_bangumi / 影视 media_ft），WBI 签名 + 匿名 buvid3。
     * 与 UGC 搜索共用匿名 cookie 与限流通道，签名参数走项目统一的 BiliWbiSign。
     */
    fun searchPgc(keyword: String, searchType: String, callback: (List<PgcMedia>?, String?) -> Unit) {
        try {
            pgcSearchWorkers.execute {
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    val anon = ensureAnonymousSearchCookie()
                    val url = "https://api.bilibili.com/x/web-interface/search/type" +
                        "?search_type=" + URLEncoder.encode(searchType, "UTF-8") +
                        "&keyword=" + URLEncoder.encode(keyword, "UTF-8") + "&page=1"
                    val root = searchJson(url, anon)
                    ensureSuccess(root)
                    val data = root.optJSONObject("data")
                        ?: throw IllegalStateException("搜索响应缺少数据")
                    // 风控验证码响应：HTTP 200 + code=0 但 data 只带 v_voucher 凭证，
                    // 必须在取列表前显式识别，否则会被当成“成功但 0 条”而静默无结果。
                    val classified = classifySearchOutcome(data)
                    when (classified) {
                        SearchOutcome.GAIA_VERIFICATION ->
                            throw IllegalStateException("v_voucher:${data.optString("v_voucher")}")
                        SearchOutcome.EMPTY -> {
                            mainHandler.post { callback(emptyList(), null) }
                            return@execute
                        }
                        SearchOutcome.HAS_RESULT -> {}
                    }
                    val array = data.optJSONArray("result") ?: JSONArray()
                    android.util.Log.d("YuiPgc", "searchPgc $searchType kw=$keyword code=${root.optInt("code")} result=${array.length()}")
                    val list = ArrayList<PgcMedia>()
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val seasonId = item.optLong("season_id", 0L)
                        if (seasonId <= 0L) continue
                        val type = item.optInt("season_type", item.optInt("media_type", 0))
                        val scoreObj = item.optJSONObject("media_score")
                        val score = scoreObj?.optDouble("score", 0.0)?.takeIf { it > 0 }
                            ?.let { String.format(Locale.ROOT, "%.1f", it) }.orEmpty()
                        val desc = cleanHtml(item.optString("desc"))
                        list += PgcMedia(
                            mediaId = item.optLong("media_id", 0L),
                            seasonId = seasonId,
                            title = cleanHtml(item.optString("title")).ifBlank { "未命名" },
                            cover = normalizeUrl(item.optString("cover")),
                            seasonType = type,
                            typeName = typeNameOf(type).ifBlank { item.optString("season_type_name") },
                            score = score,
                            epCount = item.optInt("ep_size", 0),
                            areas = item.optString("areas"),
                            desc = desc
                        )
                    }
                    mainHandler.post { callback(list, null) }
                    return@execute
                } catch (error: Exception) {
                    lastError = error
                    android.util.Log.w("YuiPgc", "searchPgc $searchType ERROR ${error.message}")
                    if (attempt == 0 && isRequestBanned(error)) {
                        anonymousSearchCookie = ""
                        Thread.sleep(650L)
                    } else {
                        mainHandler.post { callback(null, readableError(error)) }
                        return@execute
                    }
                }
            }
            mainHandler.post { callback(null, readableError(lastError ?: IllegalStateException("搜索暂不可用"))) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            mainHandler.post { callback(null, "PGC 搜索排队已满") }
        }
    }

    private fun typeNameOf(type: Int): String = when (type) {
        1 -> "番剧"
        2 -> "电影"
        3 -> "纪录片"
        4 -> "国创"
        5 -> "电视剧"
        7 -> "综艺"
        else -> ""
    }

    /** PGC season 详情：返回体在 result 而非 data；episodes 的 duration 单位为毫秒。 */
    fun fetchPgcSeason(seasonId: Long, callback: (PgcSeasonDetail?, String?) -> Unit) {
        if (seasonId <= 0L) { callback(null, "剧集不存在"); return }
        executeNetwork(onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }) {
            try {
                val root = getJson(
                    "https://api.bilibili.com/pgc/view/web/season?season_id=$seasonId",
                    mapOf("Cookie" to BiliSessionStore.cookie())
                )
                ensureSuccess(root)
                val r = root.optJSONObject("result") ?: run {
                    mainHandler.post { callback(null, "剧集不存在") }
                    return@executeNetwork
                }
                val type = r.optInt("type", r.optInt("show_season_type", 0))
                val scoreObj = r.optJSONObject("rating")
                val score = scoreObj?.optDouble("score", 0.0)?.takeIf { it > 0 }
                    ?.let { String.format(Locale.ROOT, "%.1f", it) }.orEmpty()
                val styles = (r.optJSONArray("styles")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                }.orEmpty()).joinToString(" / ")
                val media = PgcMedia(
                    mediaId = r.optLong("media_id", 0L),
                    seasonId = r.optLong("season_id", seasonId),
                    title = r.optString("season_title").ifBlank { r.optString("title") },
                    cover = normalizeUrl(r.optString("cover")),
                    seasonType = type,
                    typeName = typeNameOf(type),
                    score = score,
                    epCount = r.optJSONArray("episodes")?.length() ?: 0,
                    areas = styles,
                    desc = r.optString("evaluate")
                )
                val eps = ArrayList<PgcEpisode>()
                val epArray = r.optJSONArray("episodes") ?: JSONArray()
                for (i in 0 until epArray.length()) {
                    val e = epArray.optJSONObject(i) ?: continue
                    val cid = e.optLong("cid", 0L)
                    if (cid <= 0L) continue
                    eps += PgcEpisode(
                        // PGC 接口通常用 id 表示分集 id，少数返回 ep_id；两者都兼容。
                        epId = e.optLong("ep_id", 0L).takeIf { it > 0L } ?: e.optLong("id", 0L),
                        cid = cid,
                        aid = e.optLong("aid", 0L),
                        bvid = e.optString("bvid"),
                        title = e.optString("show_title").ifBlank {
                            e.optString("long_title").ifBlank { e.optString("title") }
                        },
                        durationMs = e.optLong("duration", 0L),
                        cover = normalizeUrl(e.optString("cover")),
                        badge = e.optString("badge"),
                        sectionType = e.optInt("section_type", 0)
                    )
                }
                val isFinish = r.optJSONObject("publish")?.optInt("is_finish", 0) == 1
                val seasonRefs = ArrayList<PgcSeasonRef>()
                val seasonsArray = r.optJSONArray("seasons")
                if (seasonsArray != null) for (i in 0 until seasonsArray.length()) {
                    val s = seasonsArray.optJSONObject(i) ?: continue
                    val refId = s.optLong("season_id", 0L)
                    if (refId <= 0L) continue
                    seasonRefs += PgcSeasonRef(
                        seasonId = refId,
                        title = s.optString("season_title").ifBlank { s.optString("title") },
                        badge = s.optString("badge")
                    )
                }
                mainHandler.post {
                    callback(PgcSeasonDetail(media, r.optString("evaluate"), eps, isFinish, seasonRefs), null)
                }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    internal fun fetchDetail(bvid: String, aid: Long, callback: (VideoItem?, List<VideoPart>, String?) -> Unit): BiliRequest {
        val request = BiliRequest()
        val session = BiliSessionStore.cookie()
        val addresses = buildList {
            if (bvid.isNotBlank()) add(DETAIL_URL + URLEncoder.encode(bvid, "UTF-8"))
            if (aid > 0L) add("https://api.bilibili.com/x/web-interface/view?aid=$aid")
        }
        fun deliver(video: VideoItem?, parts: List<VideoPart>, error: String?) {
            mainHandler.post { if (!request.cancelled) callback(video, parts, error) }
        }
        fun fetch(index: Int, previousError: String?) {
            if (request.cancelled) return
            val address = addresses.getOrNull(index)
            if (address == null) {
                deliver(null, emptyList(), previousError ?: "无法获取视频信息")
                return
            }
            val call = jsonCall(address, session = session)
            if (!request.attach(call)) return
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    request.detach(call)
                    fetch(index + 1, readableError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val detail = try {
                        response.use {
                            val root = BiliHttp.json(it)
                            ensureSuccess(root)
                            val data = root.optJSONObject("data") ?: error("未返回视频信息")
                            val pages = data.optJSONArray("pages")
                            val parts = ArrayList<VideoPart>(pages?.length() ?: 0)
                            if (pages != null) for (i in 0 until pages.length()) {
                                val page = pages.optJSONObject(i) ?: continue
                                parts += VideoPart(
                                    page.optInt("page", i + 1),
                                    page.optString("part").ifBlank { "第 ${i + 1} P" },
                                    page.optLong("cid"),
                                    page.optInt("duration")
                                )
                            }
                            Pair(videoFromJson(data), parts)
                        }
                    } catch (error: Exception) {
                        request.detach(call)
                        fetch(index + 1, readableError(error))
                        return
                    }
                    request.detach(call)
                    deliver(detail.first, detail.second, null)
                }
            })
        }
        fetch(0, null)
        return request
    }

    /** UP主粉丝数（万级由界面格式化）。mid 无效时直接回调 null。 */
    fun fetchFans(mid: Long, callback: (Long?, String?) -> Unit) {
        if (mid <= 0L) { callback(null, null); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson("https://api.bilibili.com/x/relation/stat?vmid=$mid")
                ensureSuccess(root)
                val fans = root.optJSONObject("data")?.optLong("follower", 0L) ?: 0L
                mainHandler.post { callback(fans, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    /** 视频当前在线观看人数。aid/cid 无效时直接回调 null。 */
    fun fetchOnline(aid: Long, cid: Long, callback: (Long?, String?) -> Unit) {
        if (aid <= 0L || cid <= 0L) { callback(null, null); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson("https://api.bilibili.com/x/player/online/total?aid=$aid&cid=$cid")
                ensureSuccess(root)
                val data = root.optJSONObject("data")
                val online = (data?.optLong("total", 0L) ?: 0L).coerceAtLeast(data?.optLong("count", 0L) ?: 0L)
                mainHandler.post { callback(online, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    private val anonSearchInitLock = Any()

    internal fun fetchCommentPage(
        oid: String,
        commentType: Int,
        sort: Int,
        offset: String?,
        session: BiliSessionStore.SessionSnapshot,
        callback: (CommentPage?, String?) -> Unit
    ): BiliRequest {
        val request = BiliRequest()
        val requestedAt = System.nanoTime()
        val cleanOid = oid.trim()
        if (cleanOid.isEmpty() || commentType <= 0) {
            mainHandler.post { if (request.isActive()) callback(null, "评论信息无效") }
            return request
        }
        executeNetwork(request, onRejected = {
            mainHandler.post { if (request.isActive()) callback(null, "操作过快，请稍后再试") }
        }) {
            try {
                request.ensureActive()
                if (!BiliSessionStore.isCurrent(session)) throw IOException("账号已切换，请刷新评论")
                val signed = BiliWbiSign.signParams(
                    mapOf(
                        "type" to commentType.toString(), "oid" to cleanOid,
                        "mode" to (if (sort == 0) "0" else "2"),
                        "pagination_str" to encodeCommentOffsetJson(offset).let(::quoteCursorForWbi),
                        "plat" to "1", "web_location" to "333.788"
                    )
                )
                request.ensureActive()
                val headers = mapOf("Cookie" to BiliPlayUrl.cookieHeader(session = session.cookie))
                request.ensureActive()
                if (!BiliSessionStore.isCurrent(session)) return@executeNetwork
                val root = getCommentJson("https://api.bilibili.com/x/v2/reply/main?$signed", headers, request)
                request.ensureActive()
                if (!BiliSessionStore.isCurrent(session)) throw IOException("账号已切换，请刷新评论")
                ensureCommentSuccess(root)
                val data = root.optJSONObject("data") ?: throw IllegalStateException("未返回评论数据")
                val upperMid = data.optJSONObject("upper")?.optLong("mid", 0L) ?: 0L
                val list = ArrayList<VideoComment>()
                val seen = HashSet<Long>()
                if (offset == null) {
                    val topUpper = data.optJSONObject("top")?.optJSONObject("upper")
                    if (topUpper != null) {
                        val pinned = parseComment(topUpper, isPinned = true, upperMid = upperMid, requestedAtNanos = requestedAt)
                        if (pinned.id <= 0L || seen.add(pinned.id)) list += pinned
                    }
                    data.optJSONArray("top_replies")?.let { pinnedArray ->
                        for (index in 0 until pinnedArray.length()) {
                            val reply = pinnedArray.optJSONObject(index) ?: continue
                            val pinned = parseComment(reply, isPinned = true, upperMid = upperMid, requestedAtNanos = requestedAt)
                            if (pinned.id <= 0L || seen.add(pinned.id)) list += pinned
                        }
                    }
                }
                val replies = data.optJSONArray("replies") ?: JSONArray()
                for (index in 0 until replies.length()) {
                    val reply = replies.optJSONObject(index) ?: continue
                    val parsed = parseComment(reply, upperMid = upperMid, requestedAtNanos = requestedAt)
                    if (parsed.id <= 0L || seen.add(parsed.id)) list += parsed
                }
                val cursor = data.optJSONObject("cursor")
                val pagination = cursor?.optJSONObject("pagination_reply")
                val rawOffset = (pagination?.opt("next_offset") as? String)?.takeIf { it.isNotBlank() }
                val isEnd = cursor?.opt("is_end") as? Boolean
                val decision = decideCommentCursor(CommentPageCursor(rawOffset, isEnd))
                request.ensureActive()
                if (!BiliSessionStore.isCurrent(session)) throw IOException("账号已切换，请刷新评论")
                val pageResult = CommentPage(
                    list, decision.nextOffset, decision.hasMore, decision.failureReason
                )
                mainHandler.post {
                    if (request.isActive() && BiliSessionStore.isCurrent(session)) callback(pageResult, null)
                }
            } catch (error: Exception) {
                if (!request.isActive()) return@executeNetwork
                mainHandler.post {
                    if (request.isActive() && BiliSessionStore.isCurrent(session)) callback(null, readableError(error))
                }
            }
        }
        return request
    }

    internal fun fetchReplyPage(
        oid: String,
        commentType: Int,
        root: Long,
        page: Int,
        session: BiliSessionStore.SessionSnapshot,
        callback: (List<VideoComment>, Int, String?) -> Unit
    ): BiliRequest {
        val request = BiliRequest()
        val requestedAt = System.nanoTime()
        fun deliver(items: List<VideoComment>, next: Int, error: String?) {
            mainHandler.post {
                if (request.isActive() && BiliSessionStore.isCurrent(session)) callback(items, next, error)
            }
        }
        val cleanOid = oid.trim()
        if (cleanOid.isEmpty() || commentType <= 0 || root <= 0L || page < 1) {
            deliver(emptyList(), -1, "回复信息无效")
            return request
        }
        executeNetwork(request, onRejected = { deliver(emptyList(), -1, "操作过快，请稍后再试") }) {
            try {
                request.ensureActive()
                if (!BiliSessionStore.isCurrent(session)) return@executeNetwork
                val signed = BiliWbiSign.signParams(
                    mapOf(
                        "type" to commentType.toString(), "oid" to cleanOid, "root" to root.toString(),
                        "pn" to page.toString(), "ps" to "20", "plat" to "1"
                    )
                )
                val headers = mapOf("Cookie" to BiliPlayUrl.cookieHeader(session = session.cookie))
                request.ensureActive()
                if (!BiliSessionStore.isCurrent(session)) return@executeNetwork
                val result = getCommentJson("https://api.bilibili.com/x/v2/reply/reply?$signed", headers, request)
                ensureCommentSuccess(result)
                val data = result.optJSONObject("data") ?: error("未返回回复数据")
                val upperMid = data.optJSONObject("upper")?.optLong("mid", 0L) ?: 0L
                val replies = data.optJSONArray("replies") ?: JSONArray()
                val items = ArrayList<VideoComment>(replies.length())
                for (index in 0 until replies.length()) {
                    request.ensureActive()
                    val reply = replies.optJSONObject(index) ?: continue
                    val parsed = parseComment(reply, upperMid = upperMid, requestedAtNanos = requestedAt)
                    if (parsed.id > 0L) items += parsed
                }
                val total = data.optJSONObject("page")?.optLong("count", -1L) ?: -1L
                val more = if (total >= 0L) page * 20L < total else replies.length() >= 20
                deliver(items, if (more) page + 1 else -1, null)
            } catch (error: Exception) {
                if (request.isActive()) deliver(emptyList(), -1, readableError(error))
            }
        }
        return request
    }

    internal fun selectEmoteUrl(gifUrl: String, webpUrl: String, fallbackUrl: String): String {
        val raw = gifUrl.ifBlank { webpUrl }.ifBlank { fallbackUrl }
        return if (raw.isBlank()) "" else normalizeUrl(raw)
    }

    /** Parse one reply object (shared by top-level and in-floor replies). */
    internal fun parseComment(
        reply: JSONObject,
        isPinned: Boolean = false,
        upperMid: Long = 0L,
        requestedAtNanos: Long = 0L
    ): VideoComment {
        val member = reply.optJSONObject("member")
        val content = reply.optJSONObject("content")
        val message = (content?.opt("message") as? String).orEmpty()
        // B 站富文本跳转链接（正片传送门 / 收藏集 / 搜索词等）
        val jumpLinks = HashMap<String, String>()
        content?.optJSONObject("jump_url")?.let { jump ->
            val keys = jump.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = jump.optJSONObject(key) ?: continue
                val url = value.optString("url").ifBlank { value.optString("pc_url") }
                val normalized = normalizeUrl(url)
                if (key.isNotBlank() && CommentLinkParser.isSafeUrl(normalized)) {
                    jumpLinks[key] = normalized
                }
            }
        }
        val memberMid = reply.optLong("mid", 0L).takeIf { it > 0L }
            ?: member?.optString("mid")?.toLongOrNull()
            ?: member?.optLong("mid", 0L)
            ?: 0L
        // Official Bilibili emotes (placeholder name -> url): rendered inline at text size.
        val emotes = HashMap<String, String>()
        content?.optJSONObject("emote")?.let { emote ->
            val names = emote.keys()
            while (names.hasNext()) {
                val name = names.next()
                val value = emote.optJSONObject(name)
                val url = selectEmoteUrl(
                    gifUrl = value?.optString("gif_url").orEmpty(),
                    webpUrl = value?.optString("webp_url").orEmpty(),
                    fallbackUrl = value?.optString("url").orEmpty()
                )
                if (url.isNotBlank()) emotes[name] = url
            }
        }
        // User-uploaded comment images (pictures array, each item has img_src).
        val pictures = ArrayList<CommentPicture>()
        content?.optJSONArray("pictures")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                val url = item?.optString("img_src").orEmpty()
                if (url.isNotBlank()) {
                    val normalized = normalizeUrl(url)
                    if (pictures.none { it.url == normalized }) {
                        pictures += CommentPicture(
                            url = normalized,
                            width = item.optInt("img_width", 0),
                            height = item.optInt("img_height", 0)
                        )
                    }
                }
            }
        }
        // In-floor replies already embedded in the response (up to 3).
        val subs = ArrayList<VideoComment>()
        reply.optJSONArray("replies")?.let { array ->
            for (index in 0 until array.length()) {
                val sub = array.optJSONObject(index) ?: continue
                subs += parseComment(sub, upperMid = upperMid, requestedAtNanos = requestedAtNanos)
            }
        }
        val replyControl = reply.optJSONObject("reply_control")
        val rawLocation = replyControl
            ?.optString("location").orEmpty()
            .removePrefix("IP属地：").removePrefix("IP属地:").trim()
        val isUpFlag = flag(replyControl, "is_up_flag") ||
            flag(reply, "is_up") ||
            (upperMid > 0L && memberMid == upperMid)
        // Keep the "[name]" placeholders in the content: the renderer replaces
        // them with inline emote images. Removing them here made emotes
        // disappear once the separate emote row was replaced by inline spans,
        // and pure-sticker comments used to be mislabelled as deleted.
        return VideoComment(
            id = reply.optLong("rpid", 0L),
            user = member?.optString("uname").orEmpty().ifBlank { "哔哩哔哩用户" },
            avatar = normalizeUrl(member?.optString("avatar").orEmpty()),
            content = if (message.isBlank() && pictures.isEmpty()) "（评论内容暂不可用）" else message,
            likes = reply.optLong("like", 0L),
            publishedAt = reply.optLong("ctime", 0L),
            userMid = memberMid,
            location = rawLocation,
            emotes = emotes,
            pictures = pictures,
            replies = subs,
            replyCount = reply.optLong("rcount", 0L),
            // action: 0=none, 1=liked by the signed-in account.
            liked = reply.optInt("action", 0) == 1,
            isPinned = isPinned,
            isUp = isUpFlag,
            jumpLinks = jumpLinks,
            mentions = parseCommentMentions(content),
            requestedAtNanos = requestedAtNanos
        )
    }

    private fun parseCommentMentions(content: JSONObject?): Map<String, Long> {
        if (content == null) return emptyMap()
        val entries = ArrayList<Pair<String, String>>()
        content.optJSONArray("members")?.let { members ->
            for (index in 0 until members.length()) {
                val member = members.optJSONObject(index) ?: continue
                entries += member.optString("uname") to member.optString("mid")
            }
        }
        for (field in listOf("at_name_to_mid", "at_name_to_mid_str")) {
            content.optJSONObject(field)?.let { names ->
                val keys = names.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    entries += name to names.optString(name)
                }
            }
        }
        return CommentMentionResolver.fromEntries(entries)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 互动接口（预留）：以下接口需要登录 Cookie（SESSDATA + bili_jct）。
    // 当前版本没有登录入口，统一走"未登录"分支返回提示；登录功能实现后
    // 这些方法即可直接使用。详见 docs/BILI_API_INTEGRATION.md。
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 评论点赞/取消赞（需登录）。
     *
     * 官方文档（bilibili-API-collect comment/action.md）：
     *   POST https://api.bilibili.com/x/v2/reply/action
     *   form: type=1, oid=aid, rpid, action=1点赞/0取消, csrf=bili_jct
     *   成功只返回 {code:0}，不带 like/action。
     *
     * 实测（2026-08-02）：
     * - x/v2/reply/info 即使 code=0 也没有 data，不可用。
     * - 登录后 reply/main 的每条评论 action 字段是权威状态：0无 / 1已赞 / 2已踩。
     * - 点赞数 like 会有短暂延迟，不作为“是否成功”的依据。
     * - 评论错误码是 12002/12004/12006/12009/12011 等，不是视频点赞的 65004/65006。
     *
     * 因此本方法：action 成功即返回 true；UI 用“请求态”更新，重进列表时由 reply/main 校正。
     */
    fun postCommentLike(
        oid: Long,
        rpid: Long,
        like: Boolean,
        callback: (Boolean, String?) -> Unit
    ) {
        postCommentLikeForTarget(oid = oid.toString(), commentType = 1, rpid = rpid, like = like, callback = callback)
    }

    /** 通用评论点赞/取消赞（支持任意评论区 oid + commentType）。 */
    fun postCommentLikeForTarget(
        oid: String,
        commentType: Int,
        rpid: Long,
        like: Boolean,
        callback: (Boolean, String?) -> Unit
    ) = postCommentLike(oid, commentType, rpid, like, BiliSessionStore.snapshot(), callback)

    internal fun postCommentLike(
        oid: String,
        commentType: Int,
        rpid: Long,
        like: Boolean,
        session: BiliSessionStore.SessionSnapshot,
        callback: (Boolean, String?) -> Unit
    ) {
        fun deliver(ok: Boolean, error: String?) { mainHandler.post { callback(ok, error) } }
        val cleanOid = oid.trim()
        if (cleanOid.isEmpty() || commentType <= 0 || rpid <= 0L) {
            deliver(false, "评论信息未就绪，请稍后再试")
            return
        }
        if (!BiliSessionStore.isLoggedIn(session.cookie)) { deliver(false, "请先登录后再点赞评论"); return }
        val csrf = csrfToken(session.cookie)
        if (csrf.isBlank()) { deliver(false, "登录凭证不完整"); return }
        executeNetwork(onRejected = { deliver(false, "操作过快，请稍后再试") }) {
            try {
                if (!BiliSessionStore.isCurrent(session)) { deliver(false, "账号已切换"); return@executeNetwork }
                val root = postForm(
                    "https://api.bilibili.com/x/v2/reply/action",
                    mapOf(
                        "type" to commentType.toString(), "oid" to cleanOid,
                        "rpid" to rpid.toString(), "action" to if (like) "1" else "0", "csrf" to csrf
                    ), session = session.cookie, expectedSession = session
                )
                ensureSuccess(root)
                deliver(BiliSessionStore.isCurrent(session), if (BiliSessionStore.isCurrent(session)) null else "账号已切换")
            } catch (error: Exception) {
                deliver(false, readableError(error))
            }
        }
    }

    /** 视频点赞/取消赞（需登录）。like=1 点赞 / 2 取消（B 站协议）。 */
    fun likeVideo(aid: Long, like: Boolean, callback: (Boolean, String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录后再点赞"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = postForm(
                    "https://api.bilibili.com/x/web-interface/archive/like",
                    mapOf("aid" to aid.toString(), "like" to (if (like) "1" else "2"), "csrf" to csrf)
                )
                // code 65006 = already liked; treat as success so UI stays on.
                // code 65004 = already cancelled; treat as success so UI stays off.
                val code = root.optInt("code", -1)
                if (code != 0 && code != 65006 && code != 65004) {
                    throw IllegalStateException(root.optString("message").ifBlank { "点赞失败" })
                }
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /**
     * Authoritative like state for the signed-in account.
     * `x/web-interface/view` often omits `req_user.like`, so detail pages query
     * this endpoint separately: data=1 means liked, data=0 means not.
     */
    fun fetchHasLiked(aid: Long, callback: (Boolean?, String?) -> Unit) {
        if (aid <= 0L || !BiliSessionStore.isLoggedIn()) {
            callback(null, null)
            return
        }
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson("https://api.bilibili.com/x/web-interface/archive/has/like?aid=$aid")
                ensureSuccess(root)
                val liked = when (val data = root.opt("data")) {
                    is Number -> data.toInt() == 1
                    is Boolean -> data
                    else -> root.optInt("data", 0) == 1
                }
                mainHandler.post { callback(liked, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    /** 我的收藏夹列表（需登录）。返回 (id, 名称, 视频数)。 */
    fun fetchFavoriteFolders(callback: (List<FavoriteFolder>, String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(emptyList(), "请先登录后再收藏"); return }
        val mid = BiliSessionStore.cookie().substringAfter("DedeUserID=", "").substringBefore(";").trim()
        if (mid.isBlank()) { callback(emptyList(), "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(emptyList(), "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson(
                    "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid",
                    mapOf("Cookie" to BiliSessionStore.cookie())
                )
                ensureSuccess(root)
                val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
                val folders = ArrayList<FavoriteFolder>(list.length())
                for (index in 0 until list.length()) {
                    val item = list.optJSONObject(index) ?: continue
                    folders += FavoriteFolder(
                        id = item.optLong("id", 0L),
                        name = item.optString("title").ifBlank { "未命名收藏夹" },
                        count = item.optLong("media_count", 0L),
                        // attr 是位掩码，bit0 才是私密；其余位（如 2、22）不代表私密
                        owner = item.optJSONObject("upper")?.optString("name").orEmpty()
                            .ifBlank { BiliSessionStore.cachedProfile()?.uname.orEmpty() },
                        // 注意：created/list-all 不返回 cover，封面需另查 folder/info
                        isPublic = (item.optInt("attr", 0) and 1) == 0
                    )
                }
                mainHandler.post { callback(folders, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), readableError(error)) }
            }
        }
    }

    /**
     * 创建收藏夹（需登录 + CSRF）。
     * POST /x/v3/fav/folder/add，privacy 0=公开 1=私密。
     * cover 需传 B 站图床 URL，暂不支持本地图片上传，故不传。
     */
    fun createFavoriteFolder(
        title: String,
        intro: String,
        isPublic: Boolean,
        cover: String = "",
        callback: (Boolean, String?) -> Unit
    ) {
        val name = title.trim()
        if (name.isBlank()) { callback(false, "请填写收藏夹名称"); return }
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val form = linkedMapOf(
                    "title" to name,
                    "intro" to intro.trim(),
                    "privacy" to if (isPublic) "0" else "1",
                    "csrf" to csrf
                )
                if (cover.isNotBlank()) form["cover"] = cover
                val root = postForm("https://api.bilibili.com/x/v3/fav/folder/add", form)
                val code = root.optInt("code", -1)
                if (code != 0) {
                    mainHandler.post {
                        callback(false, root.optString("message").ifBlank { "创建失败（$code）" })
                    }
                    return@executeNetwork
                }
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /**
     * 上传图片到 B 站图床，返回可直接用于 cover 的 URL。
     * POST /x/dynamic/feed/draw/upload_bfs（multipart），要求宽高均 > 420。
     */
    fun uploadCoverImage(bytes: ByteArray, callback: (String?, String?) -> Unit) {
        if (bytes.isEmpty()) { callback(null, "图片为空"); return }
        if (!BiliSessionStore.isLoggedIn()) { callback(null, "请先登录"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(null, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            try {
                val body = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart(
                        "file_up", "cover.jpg",
                        okhttp3.RequestBody.create("image/jpeg".toMediaTypeOrNull(), bytes)
                    )
                    .addFormDataPart("category", "daily")
                    .addFormDataPart("csrf", csrf)
                    .build()
                val request = BiliHttp.request("https://api.bilibili.com/x/dynamic/feed/draw/upload_bfs")
                    .header("Cookie", BiliPlayUrl.cookieHeader())
                    .header("Referer", "https://www.bilibili.com/")
                    .post(body)
                    .build()
                val root = BiliHttp.client.newCall(request).execute().use(BiliHttp::json)
                val code = root.optInt("code", -1)
                if (code != 0) {
                    mainHandler.post {
                        callback(null, root.optString("message").ifBlank { "上传失败（$code）" })
                    }
                    return@executeNetwork
                }
                val url = root.optJSONObject("data")?.optString("image_url").orEmpty()
                if (url.isBlank()) {
                    mainHandler.post { callback(null, "上传返回地址为空") }
                    return@executeNetwork
                }
                mainHandler.post { callback(normalizeUrl(url), null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    /**
     * 查询单个收藏夹元数据（含自定义封面 cover）。
     * GET /x/v3/fav/folder/info?media_id=
     */
    fun fetchFavoriteFolderInfo(mediaId: Long, callback: (FavoriteFolder?, String?) -> Unit) {
        if (mediaId <= 0L) { callback(null, "收藏夹不存在"); return }
        android.util.Log.d("YuiFavCover", "folder_info $mediaId submit")
        try {
            folderMetaWorkers.execute {
            try {
                val root = getJson(
                    "https://api.bilibili.com/x/v3/fav/folder/info?media_id=$mediaId",
                    mapOf("Cookie" to BiliSessionStore.cookie())
                )
                ensureSuccess(root)
                val data = root.optJSONObject("data")
                if (data == null) {
                    android.util.Log.w("YuiFavCover", "folder_info $mediaId data=null")
                    mainHandler.post { callback(null, "收藏夹不存在") }
                    return@execute
                }
                val coverUrl = normalizeUrl(data.optString("cover"))
                android.util.Log.d("YuiFavCover", "folder_info $mediaId cover='$coverUrl'")
                val folder = FavoriteFolder(
                    id = data.optLong("id", mediaId),
                    name = data.optString("title").ifBlank { "未命名收藏夹" },
                    count = data.optLong("media_count", 0L),
                    owner = data.optJSONObject("upper")?.optString("name").orEmpty(),
                    isPublic = (data.optInt("attr", 0) and 1) == 0,
                    cover = coverUrl
                )
                mainHandler.post { callback(folder, null) }
            } catch (error: Exception) {
                android.util.Log.w("YuiFavCover", "folder_info $mediaId error: ${error.message}")
                mainHandler.post { callback(null, readableError(error)) }
            }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            mainHandler.post { callback(null, "封面查询排队已满") }
        }
    }

    /** 刷新单个收藏夹元数据（主要为了拿到最新 media_count）。 */
    fun refreshFavoriteFolder(folderId: Long, callback: (FavoriteFolder?) -> Unit) {
        if (folderId <= 0L) { callback(null); return }
        fetchFavoriteFolderInfo(folderId) { folder, _ -> callback(folder) }
    }

    /** 编辑收藏夹（需登录 + CSRF）。privacy 0=公开 1=私密。 */
    fun editFavoriteFolder(
        mediaId: Long,
        title: String,
        intro: String,
        isPublic: Boolean,
        cover: String = "",
        callback: (Boolean, String?) -> Unit
    ) {
        val name = title.trim()
        if (mediaId <= 0L) { callback(false, "收藏夹不存在"); return }
        if (name.isBlank()) { callback(false, "请填写收藏夹名称"); return }
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val form = linkedMapOf(
                    "media_id" to mediaId.toString(),
                    "title" to name,
                    "intro" to intro.trim(),
                    "privacy" to if (isPublic) "0" else "1",
                    "csrf" to csrf
                )
                if (cover.isNotBlank()) form["cover"] = cover
                val root = postForm("https://api.bilibili.com/x/v3/fav/folder/edit", form)
                val code = root.optInt("code", -1)
                if (code != 0) {
                    mainHandler.post {
                        callback(false, root.optString("message").ifBlank { "保存失败（$code）" })
                    }
                    return@executeNetwork
                }
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /** 删除收藏夹（需登录 + CSRF）。mediaIds 逗号分隔。 */
    fun deleteFavoriteFolders(mediaIds: String, callback: (Boolean, String?) -> Unit) {
        val ids = mediaIds.trim()
        if (ids.isBlank()) { callback(false, "收藏夹不存在"); return }
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = postForm(
                    "https://api.bilibili.com/x/v3/fav/folder/del",
                    linkedMapOf("media_ids" to ids, "csrf" to csrf)
                )
                val code = root.optInt("code", -1)
                if (code != 0) {
                    mainHandler.post {
                        callback(false, root.optString("message").ifBlank { "删除失败（$code）" })
                    }
                    return@executeNetwork
                }
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /** 批量删除收藏夹内视频（需登录 + CSRF）。resources 形如 "aid:2,aid:2"。 */
    fun batchDeleteFavoriteResources(
        mediaId: Long,
        resources: String,
        callback: (Boolean, String?) -> Unit
    ) {
        if (mediaId <= 0L || resources.isBlank()) { callback(false, "没有可删除的内容"); return }
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = postForm(
                    "https://api.bilibili.com/x/v3/fav/resource/batch-del",
                    linkedMapOf(
                        "resources" to resources,
                        "media_id" to mediaId.toString(),
                        "platform" to "web",
                        "csrf" to csrf
                    )
                )
                val code = root.optInt("code", -1)
                if (code != 0) {
                    mainHandler.post {
                        callback(false, root.optString("message").ifBlank { "删除失败（$code）" })
                    }
                    return@executeNetwork
                }
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /** 清理收藏夹内已失效视频（需登录 + CSRF）。 */
    fun cleanFavoriteResources(mediaId: Long, callback: (Boolean, String?) -> Unit) {
        if (mediaId <= 0L) { callback(false, "收藏夹不存在"); return }
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = postForm(
                    "https://api.bilibili.com/x/v3/fav/resource/clean",
                    linkedMapOf("media_id" to mediaId.toString(), "csrf" to csrf)
                )
                val code = root.optInt("code", -1)
                if (code != 0) {
                    mainHandler.post {
                        callback(false, root.optString("message").ifBlank { "清理失败（$code）" })
                    }
                    return@executeNetwork
                }
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /**
     * 查询视频收藏状态（需登录）：是否已收藏。
     * GET /x/v2/fav/video/favoured 是查询「是否已收藏」最稳的接口（无需 CSRF，已实测）。
     * favIds 恒为空：B 站没有公开的 multi_fav 查询口（deal 为 POST 且必须带增删参数），
     * 弹窗「取消收藏」时由 UI 层用「全部收藏夹 id」兜底。
     */
    fun fetchFavoriteDeal(aid: Long, callback: (isFav: Boolean, favIds: List<Long>, error: String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(false, emptyList(), null); return }
        if (aid <= 0L) { callback(false, emptyList(), null); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, emptyList(), "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson("https://api.bilibili.com/x/v2/fav/video/favoured?aid=$aid")
                ensureSuccess(root)
                val isFav = root.optJSONObject("data")?.optBoolean("favoured", false) == true
                mainHandler.post { callback(isFav, emptyList(), null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, emptyList(), readableError(error)) }
            }
        }
    }

    /** 收藏夹内视频列表（需登录）。默认第一页 20 条；空列表表示收藏夹为空。 */
    fun fetchFavoriteVideos(
        mediaId: Long,
        page: Int = 1,
        pageSize: Int = 20,
        keyword: String = "",
        callback: (List<VideoItem>, String?, Boolean) -> Unit
    ) {
        if (!BiliSessionStore.isLoggedIn()) {
            callback(emptyList(), "请先登录后再查看收藏", true); return
        }
        if (mediaId <= 0L) { callback(emptyList(), "收藏夹不存在", true); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(emptyList(), "操作过快，请稍后再试", true) } }
        ) {
            try {
                val params = linkedMapOf(
                    "media_id" to mediaId.toString(),
                    "pn" to page.coerceAtLeast(1).toString(),
                    "ps" to pageSize.coerceIn(1, 40).toString(),
                    "platform" to "web",
                    "web_location" to "333.788"
                )
                val kw = keyword.trim()
                if (kw.isNotBlank()) params["keyword"] = kw
                val signed = BiliWbiSign.signParams(params)
                val root = getJson(
                    "https://api.bilibili.com/x/v3/fav/resource/list?$signed",
                    mapOf("Cookie" to BiliPlayUrl.cookieHeader())
                )
                ensureSuccess(root)
                val data = root.optJSONObject("data") ?: JSONObject()
                val medias = data.optJSONArray("medias") ?: JSONArray()
                val hasMore = data.optBoolean("has_more", medias.length() >= pageSize)
                val list = ArrayList<VideoItem>(medias.length())
                for (index in 0 until medias.length()) {
                    val item = medias.optJSONObject(index) ?: continue
                    val upper = item.optJSONObject("upper")
                    val bvid = item.optString("bvid")
                    if (bvid.isBlank()) continue
                    list += VideoItem(
                        bvid = bvid,
                        title = item.optString("title").ifBlank { "（无标题）" },
                        owner = upper?.optString("name").orEmpty().ifBlank { "未知UP主" },
                        cover = normalizeUrl(item.optString("cover")),
                        views = item.optJSONObject("cnt_info")?.optLong("play", 0L) ?: 0L,
                        duration = item.optInt("duration", 0),
                        description = "",
                        publishedAt = item.optLong("pubtime", 0L),
                        aid = item.optLong("id", 0L),
                        ownerMid = upper?.optLong("mid", 0L) ?: 0L
                    )
                }
                mainHandler.post { callback(list, null, !hasMore) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), readableError(error), true) }
            }
        }
    }

    /**
     * 跨收藏夹搜索：遍历用户全部自建收藏夹，按关键词拉 resource/list。
     * 结果按 bvid 去重后汇总，适合“搜索所有收藏夹内视频”。
     */
    fun searchAllFavoriteVideos(
        keyword: String,
        callback: (List<VideoItem>, String?) -> Unit
    ) {
        val q = keyword.trim()
        if (q.isBlank()) { callback(emptyList(), null); return }
        val session = BiliSessionStore.cookie()
        if (!BiliSessionStore.isLoggedIn(session)) {
            callback(emptyList(), "请先登录后再搜索收藏"); return
        }
        executeNetwork(
            onRejected = { mainHandler.post { callback(emptyList(), "操作过快，请稍后再试") } }
        ) {
            try {
                val mid = session.substringAfter("DedeUserID=", "").substringBefore(";").trim().toLongOrNull()
                    ?.takeIf { it > 0L } ?: throw IllegalStateException("登录凭证不完整")
                val foldersRoot = getJson(
                    "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid",
                    mapOf("Cookie" to session)
                )
                ensureSuccess(foldersRoot)
                val folders = foldersRoot.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
                val seen = HashSet<String>()
                val merged = ArrayList<VideoItem>()
                for (i in 0 until folders.length()) {
                    if (session != BiliSessionStore.cookie()) throw IOException("登录状态已变化")
                    val folder = folders.optJSONObject(i) ?: continue
                    val mediaId = folder.optLong("id", 0L)
                    if (mediaId <= 0L) continue
                    // 每个收藏夹最多拉 2 页，避免搜索一次打爆网络
                    for (page in 1..2) {
                        if (session != BiliSessionStore.cookie()) throw IOException("登录状态已变化")
                        val signed = BiliWbiSign.signParams(
                            mapOf(
                                "media_id" to mediaId.toString(),
                                "pn" to page.toString(),
                                "ps" to "20",
                                "keyword" to q,
                                "platform" to "web",
                                "web_location" to "333.788"
                            )
                        )
                        val root = getJson(
                            "https://api.bilibili.com/x/v3/fav/resource/list?$signed",
                            mapOf("Cookie" to BiliPlayUrl.cookieHeader(session = session))
                        )
                        ensureSuccess(root)
                        val data = root.optJSONObject("data") ?: JSONObject()
                        val medias = data.optJSONArray("medias") ?: JSONArray()
                        for (j in 0 until medias.length()) {
                            val item = medias.optJSONObject(j) ?: continue
                            val bvid = item.optString("bvid")
                            if (bvid.isBlank() || !seen.add(bvid)) continue
                            val upper = item.optJSONObject("upper")
                            merged += VideoItem(
                                bvid = bvid,
                                title = item.optString("title").ifBlank { "（无标题）" },
                                owner = upper?.optString("name").orEmpty().ifBlank { "未知UP主" },
                                cover = normalizeUrl(item.optString("cover")),
                                views = item.optJSONObject("cnt_info")?.optLong("play", 0L) ?: 0L,
                                duration = item.optInt("duration", 0),
                                description = "",
                                publishedAt = item.optLong("pubtime", 0L),
                                aid = item.optLong("id", 0L),
                                ownerMid = upper?.optLong("mid", 0L) ?: 0L
                            )
                        }
                        if (!data.optBoolean("has_more", false) || medias.length() == 0) break
                    }
                }
                mainHandler.post { callback(merged, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), readableError(error)) }
            }
        }
    }

    /** 拉取 B 站观看历史（需登录）。旧接口 x/v2/history 已废弃（code -400），
     * 改用 x/web-interface/history/cursor 游标分页：
     * 首次 max=0&view_at=0&type=archive；
     * 后续必须把上一页 cursor 的 max / view_at / business 原样回传，
     * 否则会卡在第一段（这是「历史显示不全」的常见根因）。
     *
     * callback: (videos, error, nextMax, nextViewAt, nextBusiness, ended)
     */
    fun fetchHistory(
        max: Long = 0L,
        viewAt: Long = 0L,
        business: String = "archive",
        callback: (List<VideoItem>, String?, Long, Long, String, Boolean) -> Unit
    ) {
        val session = BiliSessionStore.cookie()
        if (!BiliSessionStore.isLoggedIn(session)) {
            callback(emptyList(), "请先登录后再查看历史记录", 0L, 0L, business, true); return
        }
        executeNetwork(
            onRejected = {
                mainHandler.post { callback(emptyList(), "操作过快，请稍后再试", max, viewAt, business, false) }
            }
        ) {
            try {
                val biz = business.ifBlank { "archive" }
                val root = getJson(
                    "https://api.bilibili.com/x/web-interface/history/cursor" +
                        "?max=$max&view_at=$viewAt&business=${URLEncoder.encode(biz, "UTF-8")}&type=archive&ps=30",
                    mapOf("Cookie" to BiliPlayUrl.cookieHeader(session = session))
                )
                ensureSuccess(root)
                val data = root.optJSONObject("data") ?: JSONObject()
                val list = data.optJSONArray("list") ?: JSONArray()
                val cursor = data.optJSONObject("cursor")
                val nextMax = cursor?.optLong("max", max) ?: max
                val nextViewAt = cursor?.optLong("view_at", viewAt) ?: viewAt
                val nextBusiness = cursor?.optString("business").orEmpty().ifBlank { biz }
                val videos = ArrayList<VideoItem>(list.length())
                for (index in 0 until list.length()) {
                    val item = list.optJSONObject(index) ?: continue
                    parseArchiveHistoryRow(item)?.let(videos::add)
                }
                // 结束判定：本页原始 list 为空，或游标不再前进（避免重复分页死循环）
                val ended = isHistoryCursorEnded(
                    listLength = list.length(),
                    prevMax = max,
                    prevViewAt = viewAt,
                    nextMax = nextMax,
                    nextViewAt = nextViewAt
                )
                mainHandler.post { callback(videos, null, nextMax, nextViewAt, nextBusiness, ended) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), readableError(error), max, viewAt, business, false) }
            }
        }
    }

    private fun parseArchiveHistoryRow(item: JSONObject): VideoItem? {
        val history = item.optJSONObject("history")
        val media = archiveHistoryMedia(
            business = history?.optString("business").orEmpty().ifBlank { item.optString("business") },
            epId = maxOf(history?.optLong("epid") ?: 0L, item.optLong("epid"), item.optLong("ep_id")),
            nested = HistoryMediaId(history?.optString("bvid").orEmpty(), history?.optLong("oid") ?: 0L, history?.optLong("cid") ?: 0L),
            flat = HistoryMediaId(item.optString("bvid"), item.optLong("aid").takeIf { it > 0L } ?: item.optLong("oid"), item.optLong("cid"))
        ) ?: return null
        return VideoItem(
            bvid = media.bvid,
            aid = media.aid,
            cid = media.cid,
            title = item.optString("title").ifBlank { "（无标题）" },
            owner = item.optString("author_name").ifBlank { item.optJSONObject("owner")?.optString("name").orEmpty().ifBlank { "未知UP主" } },
            cover = normalizeUrl(item.optString("cover").ifBlank { item.optString("pic") }),
            views = 0L,
            duration = item.optLong("duration").takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt() ?: 0,
            description = "",
            publishedAt = item.optLong("view_at"),
            ownerMid = item.optLong("author_mid"),
            rcmdReason = buildHistoryKid("archive", item.optLong("kid")),
            watchProgressSec = item.optLong("progress")
        )
    }

    internal fun buildHistoryKid(business: String, kid: Long): String =
        if (kid > 0L && business.isNotBlank()) "${business}_$kid" else ""

    internal fun isHistoryCursorEnded(
        listLength: Int,
        prevMax: Long,
        prevViewAt: Long,
        nextMax: Long,
        nextViewAt: Long
    ): Boolean = listLength == 0 ||
        (nextMax == 0L && nextViewAt == 0L) ||
        (prevMax != 0L && nextMax == prevMax && nextViewAt == prevViewAt)

    /** 删除单条历史记录（需登录）。kid 格式为 "<business>_<kid>"。 */
    fun deleteHistoryItem(kid: String, callback: (Boolean, String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录"); return }
        if (kid.isBlank()) { callback(false, "该条记录不支持删除"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = postForm(
                    "https://api.bilibili.com/x/v2/history/delete",
                    mapOf("kid" to kid, "csrf" to csrf)
                )
                ensureSuccess(root)
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /** 清空远端观看历史（需登录）。 */
    fun clearRemoteHistory(callback: (Boolean, String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = postForm(
                    "https://api.bilibili.com/x/v2/history/clear",
                    mapOf("csrf" to csrf)
                )
                ensureSuccess(root)
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /**
     * 搜索观看历史（需登录）。
     * 官方 web 接口：x/web-interface/history/search?keyword=&pn=
     * 只取 archive（视频）结果。
     */
    fun searchHistory(
        keyword: String,
        page: Int = 1,
        callback: (List<VideoItem>, String?, Boolean) -> Unit
    ) {
        val q = keyword.trim()
        if (q.isBlank()) {
            callback(emptyList(), null, true); return
        }
        val session = BiliSessionStore.cookie()
        if (!BiliSessionStore.isLoggedIn(session)) {
            callback(emptyList(), "请先登录后再搜索历史", true); return
        }
        executeNetwork(
            onRejected = { mainHandler.post { callback(emptyList(), "操作过快，请稍后再试", true) } }
        ) {
            try {
                val root = getJson(
                    "https://api.bilibili.com/x/web-interface/history/search" +
                        "?keyword=${URLEncoder.encode(q, "UTF-8")}&pn=$page&business=archive",
                    mapOf("Cookie" to BiliPlayUrl.cookieHeader(session = session))
                )
                ensureSuccess(root)
                val data = root.optJSONObject("data") ?: JSONObject()
                // 兼容 list / raw_list / data.list 多种返回
                val list = data.optJSONArray("list")
                    ?: data.optJSONArray("raw_list")
                    ?: JSONArray()
                val videos = ArrayList<VideoItem>(list.length())
                for (index in 0 until list.length()) {
                    val item = list.optJSONObject(index) ?: continue
                    parseArchiveHistoryRow(item)?.let(videos::add)
                }
                val hasMore = data.optBoolean("has_more", list.length() >= 20)
                mainHandler.post { callback(videos, null, !hasMore || list.length() == 0) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), readableError(error), true) }
            }
        }
    }

    private val historyReports = object {
        private val lock = Any()
        private val queue = LinkedHashMap<String, Pair<String, Map<String, String>>>()
        private val worker = ThreadPoolExecutor(1, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
            allowCoreThreadTimeOut(true)
        }
        private var scheduled = false

        fun offer(session: String, form: Map<String, String>) {
            val key = "${form["aid"]}:${form["cid"]}"
            synchronized(lock) {
                queue[key] = session to form
                if (queue.size > 16) queue.remove(queue.keys.first())
                if (scheduled) return
                scheduled = true
            }
            worker.execute {
                while (true) {
                    val report = synchronized(lock) {
                        if (queue.isEmpty()) {
                            scheduled = false
                            return@execute
                        }
                        requireNotNull(queue.remove(queue.keys.first()))
                    }
                    if (report.first != BiliSessionStore.cookie()) continue
                    try {
                        postForm("https://api.bilibili.com/x/v2/history/report", report.second, report.first)
                    } catch (error: IOException) {
                        android.util.Log.d("BiliApi", "Unable to report playback progress")
                    } catch (_: org.json.JSONException) {
                        android.util.Log.d("BiliApi", "Invalid playback history response")
                    }
                }
            }
        }
    }

    /** 上报观看进度到 B 站历史（需登录）。status: 3=播放中, 2=暂停/退出。 */
    fun reportHistory(aid: Long, cid: Long, progressSeconds: Long, durationSeconds: Long, status: Int) {
        val session = BiliSessionStore.cookie()
        if (aid <= 0L || cid <= 0L || !BiliSessionStore.isLoggedIn(session)) return
        val form = linkedMapOf(
            "aid" to aid.toString(),
            "cid" to cid.toString(),
            "progress" to progressSeconds.toString(),
            "pt" to durationSeconds.toString(),
            "status" to status.toString(),
            "type" to "3",
            "csrf" to csrfToken(session)
        )
        historyReports.offer(session, form)
    }

    /** 收藏/取消收藏视频（需登录）。rid=视频 aid，type=2 固定；addIds/delIds 为逗号分隔的收藏夹 id。 */
    fun favoriteVideo(aid: Long, addIds: String, delIds: String, callback: (Boolean, String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录后再收藏"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = postForm(
                    "https://api.bilibili.com/x/v3/fav/resource/deal",
                    mapOf(
                        "rid" to aid.toString(), "type" to "2",
                        "add_media_ids" to addIds, "del_media_ids" to delIds,
                        "platform" to "web", "csrf" to csrf
                    )
                )
                ensureSuccess(root)
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /**
     * 查询与目标用户的关系（需登录）。
     * 使用 x/space/acc/relation：
     * data.relation.attribute = 我对 TA 的关系
     * 0 无关系 / 1 悄悄关注 / 2 关注 / 6 互粉 / 128 拉黑。
     * 1/2/6 都视为「已关注」。
     */
    fun fetchRelation(mid: Long, callback: (Boolean?, String?) -> Unit) {
        if (mid <= 0L) { callback(null, null); return }
        if (!BiliSessionStore.isLoggedIn()) { callback(false, null); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson("https://api.bilibili.com/x/space/acc/relation?mid=$mid")
                ensureSuccess(root)
                val data = root.optJSONObject("data")
                val attr = data?.optJSONObject("relation")?.optInt("attribute", 0)
                    ?: data?.optInt("attribute", 0)
                    ?: 0
                val following = attr == 1 || attr == 2 || attr == 6
                mainHandler.post { callback(following, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    /** 关注/取关 UP 主（需登录）。fid=目标用户 mid，act=1 关注 / 2 取关。 */
    fun modifyRelation(fid: Long, follow: Boolean, callback: (Boolean, String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录后再关注"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                // re_src 是网页端常规来源位；缺它时部分账号会返回成功但关系未落库。
                val root = postForm(
                    "https://api.bilibili.com/x/relation/modify",
                    mapOf(
                        "fid" to fid.toString(),
                        "act" to (if (follow) "1" else "2"),
                        "re_src" to "11",
                        "csrf" to csrf
                    )
                )
                ensureSuccess(root)
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /**
     * 下载画质策略（预留接口）：未登录仅提供 360P/480P；登录后可解锁 720P/1080P；
     * 1080P60+/4K/杜比/8K 需要大会员。qn 值对应 playurl 的 quality 参数，
     * fnval=4048 请求 DASH 流（参考 BiliTools：DASH 自动合并音视频，支持高画质）。
     */
    fun downloadQualities(): List<DownloadQuality> {
        val loggedIn = BiliSessionStore.isLoggedIn()
        val list = arrayListOf(
            DownloadQuality(16, "360P", "流畅", false),
            DownloadQuality(32, "480P", "清晰", false)
        )
        if (loggedIn) {
            list += DownloadQuality(64, "720P", "高清", false)
            list += DownloadQuality(80, "1080P", "全高清", false)
            list += DownloadQuality(116, "1080P60", "60帧", true)
            list += DownloadQuality(112, "1080P+", "高码率", true)
            list += DownloadQuality(120, "4K", "超清", true)
        }
        return list
    }

    private fun csrfToken(session: String = BiliSessionStore.cookie()): String =
        session
            .substringAfter("bili_jct=", "").substringBefore(";").trim()

    private fun postForm(address: String, form: Map<String, String>,
                         session: String = BiliSessionStore.cookie(),
                         expectedSession: BiliSessionStore.SessionSnapshot? = null): JSONObject {
        val body = okhttp3.FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
        val request = BiliHttp.request(address)
            .header("Origin", "https://www.bilibili.com")
            .header("Cookie", BiliPlayUrl.cookieHeader(session = session))
            .post(body)
            .build()
        if (expectedSession != null && !BiliSessionStore.isCurrent(expectedSession)) throw IOException("账号已切换")
        return BiliHttp.client.newCall(request).execute().use(BiliHttp::json)
    }

    private fun requestFeed(
        address: String,
        callback: (FeedPage?, String?) -> Unit,
        checkNoMore: Boolean = false,
        extractor: (JSONObject) -> JSONArray?
    ): Call {
        val call = jsonCall(address)
        fun deliver(page: FeedPage?, error: String?) {
            mainHandler.post { callback(page, error) }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = deliver(null, readableError(e))

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val root = BiliHttp.json(it)
                        ensureSuccess(root)
                        val array = extractor(root) ?: error("未返回视频列表")
                        val videos = ArrayList<VideoItem>(array.length())
                        for (index in 0 until array.length()) {
                            val entry = array.optJSONObject(index) ?: continue
                            val video = videoFromJson(entry)
                            if (video.bvid.isNotBlank()) videos.add(video)
                        }
                        check(array.length() == 0 || videos.isNotEmpty()) { "未返回有效视频" }
                        val endReached = array.length() == 0 ||
                            (checkNoMore && flag(root.optJSONObject("data"), "no_more"))
                        FeedPage(videos, endReached)
                    }
                } catch (error: Exception) {
                    deliver(null, readableError(error))
                    return
                }
                deliver(result, null)
            }
        })
        return call
    }

    private fun executeNetwork(onRejected: () -> Unit, task: () -> Unit) {
        try {
            networkWorkers.execute(task)
        } catch (_: RejectedExecutionException) {
            onRejected()
        }
    }

    private fun executeNetwork(request: BiliRequest, onRejected: () -> Unit, task: () -> Unit) {
        lateinit var work: Runnable
        work = Runnable { if (request.startTask(work)) task() }
        if (!request.enqueue(networkWorkers, work) && request.isActive()) onRejected()
    }

    private fun getCommentJson(address: String, extraHeaders: Map<String, String>, request: BiliRequest): JSONObject {
        val call = jsonCall(address, extraHeaders, session = "")
        if (!request.attach(call)) throw IOException("评论请求已取消")
        try {
            return call.execute().use { response ->
                request.ensureActive()
                if (!response.isSuccessful) throw IOException("评论接口暂不可用（HTTP ${response.code}）")
                try {
                    BiliHttp.json(response)
                } catch (error: org.json.JSONException) {
                    throw IOException("评论接口返回格式异常（HTTP ${response.code}）", error)
                }
            }
        } finally {
            request.detach(call)
        }
    }

    private fun ensureCommentSuccess(root: JSONObject) {
        val code = root.optInt("code", -1)
        if (code != 0) throw BiliProtocolException(code, "${root.optString("message").ifBlank { "评论接口暂不可用" }}（错误码 $code）")
    }

    private fun executeSearch(onRejected: () -> Unit, task: () -> Unit) {
        try {
            searchWorkers.execute(task)
        } catch (_: RejectedExecutionException) {
            onRejected()
        }
    }

    /** 返回本轮使用的匿名 cookie 快照；带单飞锁防止 UGC/番剧/影视并发首次搜索重复拉取 SPI。 */
    private fun ensureAnonymousSearchCookie(): String {
        val current = anonymousSearchCookie
        if (current.isNotBlank()) return current
        synchronized(anonSearchInitLock) {
            val again = anonymousSearchCookie
            if (again.isNotBlank()) return again
            // SPI 必须无条件匿名请求，不能带登录态
            val root = jsonCall(FINGERPRINT_URL, emptyMap(), session = "").execute().use(BiliHttp::json)
            ensureSuccess(root)
            val data = root.optJSONObject("data") ?: throw IllegalStateException("未建立搜索会话")
            val buvid3 = data.optString("b_3")
            val buvid4 = data.optString("b_4")
            if (buvid3.isBlank()) throw IllegalStateException("未建立搜索会话")
            val cookie = "buvid3=$buvid3; buvid4=$buvid4; CURRENT_FNVAL=4048; CURRENT_QUALITY=80"
            anonymousSearchCookie = cookie
            return cookie
        }
    }

    private fun searchHeaders(anonCookie: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Referer"] = "https://search.bilibili.com/"
        headers["Origin"] = "https://search.bilibili.com"
        headers["Cookie"] = anonCookie
        return headers
    }

    internal fun isRequestBanned(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return message.contains("request was banned", ignoreCase = true) ||
            message.contains("412") ||
            message.contains("拦截") ||
            message.contains("v_voucher") ||
            message.contains("风险") ||
            message.contains("过快")
    }

    /** JVM 单测入口：与 isRequestBanned 同逻辑。 */
    internal fun isRequestBannedForTest(error: Exception): Boolean = isRequestBanned(error)

    /** 搜索响应三分类：正常零命中（numResults=0，无 result 键）不算风控，不能触发重试。 */
    internal enum class SearchOutcome { HAS_RESULT, EMPTY, GAIA_VERIFICATION }

    internal fun classifySearchOutcome(voucher: String, numResults: Long, hasResultKey: Boolean): SearchOutcome {
        if (voucher.isNotBlank()) return SearchOutcome.GAIA_VERIFICATION
        if (numResults == 0L && !hasResultKey) return SearchOutcome.EMPTY
        if (hasResultKey) return SearchOutcome.HAS_RESULT
        return SearchOutcome.GAIA_VERIFICATION
    }

    internal fun classifySearchOutcome(data: JSONObject): SearchOutcome = classifySearchOutcome(
        voucher = data.optString("v_voucher"),
        numResults = data.optLong("numResults", -1L),
        hasResultKey = data.has("result")
    )

    private fun jsonCall(address: String, extraHeaders: Map<String, String> = emptyMap(),
                         session: String = BiliSessionStore.cookie()): Call {
        val request = BiliHttp.request(address).apply {
            if (session.isNotBlank()) header("Cookie", session)
            extraHeaders.forEach { (name, value) -> header(name, value) }
        }.build()
        return BiliHttp.client.newCall(request)
    }

    private fun getJson(address: String, extraHeaders: Map<String, String> = emptyMap()): JSONObject =
        jsonCall(address, extraHeaders).execute().use(BiliHttp::json)

    /** 搜索专用：强制不带登录态 cookie；非 2xx 时保留状态码，避免风控页被当 JSON 解析异常吞掉。 */
    private fun searchJson(address: String, anonCookie: String): JSONObject {
        val response = jsonCall(address, searchHeaders(anonCookie), session = "").execute()
        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("搜索接口没有返回内容（HTTP ${resp.code}）")
            if (!resp.isSuccessful) throw IOException("搜索接口异常（HTTP ${resp.code}）")
            return JSONObject(body)
        }
    }

    private fun ensureSuccess(root: JSONObject) {
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException(root.optString("message").ifBlank { "接口暂不可用" })
        }
    }

    private fun videoFromJson(source: JSONObject): VideoItem {
        val ownerObject = source.optJSONObject("owner")
        val title = cleanHtml(source.optString("title")).ifBlank { "未命名视频" }
        // req_user.like is only present when the request carries a session cookie.
        val reqUser = source.optJSONObject("req_user")
        val liked = when {
            reqUser == null -> false
            reqUser.has("like") -> reqUser.optInt("like", 0) == 1
            else -> false
        }

        // 付费/充电状态只认站方明确标记：rights、UP 主充电专属字段或付费合集字段。
        // 不能用播放流时长与 view.duration 的比例猜测，多 P、DURL、试看/转码都会造成误报。
        val charge = hasExplicitChargeFlag(source)

        // 官方合集：data.ugc_season 的 sections[].episodes[] 展平为有序分集。
        val season = source.optJSONObject("ugc_season")?.let { seasonJson ->
            val sections = seasonJson.optJSONArray("sections") ?: JSONArray()
            val episodes = ArrayList<UgcEpisode>()
            for (sectionIndex in 0 until sections.length()) {
                val section = sections.optJSONObject(sectionIndex) ?: continue
                val epArray = section.optJSONArray("episodes") ?: continue
                for (epIndex in 0 until epArray.length()) {
                    val ep = epArray.optJSONObject(epIndex) ?: continue
                    val bvid = ep.optString("bvid")
                    val aid = ep.optLong("aid")
                    if (bvid.isBlank() && aid <= 0L) continue
                    val arc = ep.optJSONObject("arc")
                    episodes += UgcEpisode(
                        bvid = bvid,
                        aid = aid,
                        cid = ep.optLong("cid"),
                        title = cleanHtml(ep.optString("title"))
                            .ifBlank { "第 ${episodes.size + 1} 集" },
                        // episodes 顶层没有 duration，真实时长在 arc.duration 里。
                        duration = ep.optInt("duration").takeIf { it > 0 }
                            ?: (arc?.optInt("duration") ?: 0),
                        // episodes 顶层一般没有 cover，分集封面在 arc.pic（arc 与 data 对象同构）；
                        // 保留顶层 cover 兼容个别返回该字段的接口形态。
                        cover = normalizeUrl(ep.optString("cover").ifBlank {
                            arc?.optString("pic").orEmpty()
                        }),
                        publishedAt = arc?.optLong("pubdate")?.takeIf { it > 0L }
                            ?: arc?.optLong("ctime")?.takeIf { it > 0L }
                            ?: 0L,
                        charge = hasExplicitChargeFlag(ep) ||
                            hasExplicitChargeFlag(arc)
                    )
                }
            }
            if (episodes.isEmpty()) null else UgcSeason(
                id = seasonJson.optLong("id"),
                title = cleanHtml(seasonJson.optString("title")).ifBlank { "合集" },
                cover = normalizeUrl(seasonJson.optString("cover")),
                episodes = episodes,
                isMultiPage = false
            )
        } ?: run {
            // 另一种常见合集形态：同一个 BV 下的多 P，详情接口只返回 pages[]，
            // 不会返回 ugc_season。它也必须进入合集分集 UI 和下载选择器。
            val pages = source.optJSONArray("pages") ?: JSONArray()
            if (pages.length() <= 1) {
                null
            } else {
                val rootBvid = source.optString("bvid")
                val rootAid = source.optLong("aid")
                val episodes = ArrayList<UgcEpisode>()
                for (index in 0 until pages.length()) {
                    val page = pages.optJSONObject(index) ?: continue
                    episodes += UgcEpisode(
                        bvid = rootBvid,
                        aid = rootAid,
                        cid = page.optLong("cid"),
                        title = cleanHtml(page.optString("part"))
                            .ifBlank { "第 ${page.optInt("page", index + 1)} P" },
                        duration = page.optInt("duration"),
                        // 多P 没有独立稿件封面，用 B 站为该 P 生成的预览帧(first_frame)作封面，
                        // 避免「下载封面」时每一P都回退成视频详情页主封面。
                        cover = normalizeUrl(page.optString("first_frame")),
                        charge = charge
                    )
                }
                if (episodes.isEmpty()) null else UgcSeason(
                    id = rootAid.takeIf { it > 0L }
                        ?: rootBvid.hashCode().toLong(),
                    title = title,
                    cover = normalizeUrl(source.optString("pic")),
                    episodes = episodes,
                    isMultiPage = true
                )
            }
        }

        val normalizedSeason = season?.let { parsed ->
            if (!charge) parsed else parsed.copy(
                episodes = parsed.episodes.map { episode ->
                    if ((episode.cid > 0L && episode.cid == source.optLong("cid")) ||
                        (episode.bvid.isNotBlank() && episode.bvid == source.optString("bvid") &&
                            episode.aid > 0L && episode.aid == source.optLong("aid"))
                    ) episode.copy(charge = true) else episode
                }
            )
        }

        val stat = source.optJSONObject("stat")
        val rcmdReason = cleanHtml(
            source.optJSONObject("rcmd_reason")?.optString("content").orEmpty()
        )
        return VideoItem(
            bvid = source.optString("bvid"),
            title = title,
            owner = source.optString("author").ifBlank { ownerObject?.optString("name").orEmpty() }
                .ifBlank { "哔哩哔哩用户" },
            cover = normalizeUrl(source.optString("pic")),
            views = source.optLong("play", stat?.optLong("view", 0L) ?: 0L),
            duration = durationFrom(source.opt("duration")),
            description = cleanHtml(source.optString("description").ifBlank { source.optString("desc") }),
            publishedAt = source.optLong("pubdate", source.optLong("ctime")),
            cid = source.optLong("cid"),
            aid = source.optLong("aid"),
            ownerMid = ownerObject?.optLong("mid", 0L) ?: 0L,
            ownerAvatar = normalizeUrl(ownerObject?.optString("face").orEmpty()),
            replyCount = stat?.optLong("reply", 0L)
                ?: source.optLong("review", 0L),
            danmakuCount = stat?.optLong("danmaku", 0L)
                ?: source.optLong("video_review", 0L),
            rcmdReason = rcmdReason,
            likes = stat?.optLong("like", 0L) ?: 0L,
            liked = liked,
            season = normalizedSeason ?: season,
            charge = charge || (normalizedSeason?.episodes?.any { it.charge } == true)
        )
    }

    /** Read a Bilibili flag that may be encoded as boolean, integer 0/1, or string 0/1. */
    private fun flag(source: JSONObject?, name: String): Boolean {
        if (source == null || !source.has(name)) return false
        return source.optBoolean(name, false) || source.optInt(name, 0) == 1 ||
            source.optString(name) == "1"
    }

    /**
     * Explicit paid/charging markers from x/web-interface/view. In particular,
     * UP 主 charging-exclusive videos use is_upower_exclusive/is_upower_preview
     * while rights.arc_pay and rights.ugc_pay remain 0.
     */
    private fun hasExplicitChargeFlag(source: JSONObject?): Boolean {
        if (source == null) return false
        val rights = source.optJSONObject("rights")
        val season = source.optJSONObject("ugc_season")
        return flag(rights, "pay") || flag(rights, "arc_pay") ||
            flag(rights, "ugc_pay") || flag(rights, "ugc_pay_preview") ||
            flag(source, "is_chargeable_season") ||
            flag(source, "is_upower_exclusive") || flag(source, "is_upower_preview") ||
            flag(source, "is_upower_exclusive_with_qa") ||
            flag(season, "is_pay_season") || flag(season, "is_chargeable_season")
    }

    private fun durationFrom(value: Any?): Int = when (value) {
        is Number -> value.toInt()
        is String -> {
            val pieces = value.split(":").mapNotNull { it.toIntOrNull() }
            if (pieces.isEmpty()) 0 else pieces.fold(0) { total, part -> total * 60 + part }
        }
        else -> 0
    }

    private fun normalizeUrl(value: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://${value.removePrefix("http://") }"
        else -> value
    }

    private fun cleanHtml(value: String): String = value
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .trim()

    private fun readableError(error: Exception): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) "网络暂不可用，请稍后再试" else message
    }
}

data class VideoPart(val number: Int, val title: String, val cid: Long, val duration: Int)

const val NETWORK_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

/**
 * User-Agent for Bilibili CDN media requests (DASH segments / DURL / downloads).
 * Verified by curl: CDN nodes reject Android-mobile and BiliDroid user agents
 * with HTTP 403 and only serve desktop Web UAs (206). Same finding as
 * suzhelan/BiliCompose commit a968e57 ("fixed request headers for 403").
 */
const val CDN_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
