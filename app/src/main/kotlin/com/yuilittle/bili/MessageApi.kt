package com.yuilittle.bili

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 消息中心分类。 */
internal enum class MessageKind { SESSION, REPLY, AT, LIKE, FAN }

/** 消息中心列表项（私信会话与各类通知共用一套渲染）。 */
internal data class MessageItem(
    val id: String,
    val kind: MessageKind,
    val name: String,
    val face: String,
    val content: String,
    val timeText: String,
    val cover: String = "",
    val unread: Int = 0,
    val uri: String = "",
    /** 会话对端 / 通知发送者的 uid，用于打开网页版对应页面。 */
    val mid: Long = 0L,
    /** 毫秒时间戳，用于统一排序（0 表示接口未给时间）。 */
    val ts: Long = 0L,
    /** 系统消息会话（无用户名片时用「系统通知」兜底）。 */
    val system: Boolean = false,
    /** 通知对应的评论 rpid（网页版 #reply 锚点，用于跳到具体回复/@ 位置）。 */
    val replyId: Long = 0L
)

/**
 * 消息中心接口。
 *
 * 全部依赖登录 cookie：私信走 `session_svr/get_sessions`，
 * 通知类走 `x/msgfeed/reply|at|like`，新增粉丝走 `x/relation/fans?act=1`（需 WBI 签名）。
 * 未登录时统一回调「请先登录」由页面展示，不额外弹登录框。
 */
internal object MessageApi {

    private const val REFERER = "https://message.bilibili.com/"
    private const val SESSION_URL =
        "https://api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions" +
            "?session_type=1&group_id=0&build=0&mobi_app=web"

    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }

    fun fetchSessions(callback: (List<MessageItem>?, String?) -> Unit): BiliRequest {
        val request = BiliRequest()
        if (!BiliSessionStore.isLoggedIn()) {
            mainHandler.post { callback(null, "请先登录后查看消息") }
            return request
        }
        val call = buildCall(SESSION_URL)
        if (!request.attach(call)) return request
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                request.detach(call)
                mainHandler.post { if (!request.cancelled) callback(null, readableError(e)) }
            }

            override fun onResponse(call: Call, response: Response) {
                val sessions = try {
                    response.use {
                        val root = BiliHttp.json(it)
                        if (root.optInt("code", -1) != 0) {
                            error(root.optString("message").ifBlank { "私信加载失败" })
                        }
                        parseSessions(root)
                    }
                } catch (error: Exception) {
                    request.detach(call)
                    mainHandler.post { if (!request.cancelled) callback(null, readableError(error)) }
                    return
                }
                request.detach(call)
                if (request.cancelled) return
                // 私信接口只返回 talker_id，昵称与头像要再批量拉一次用户名片。
                val missing = sessions
                    .filter { it.face.isBlank() || it.name.isBlank() }
                    .map { it.mid }
                    .filter { it > 0L }
                    .distinct()
                if (missing.isEmpty()) {
                    deliverSessions(request, callback, sessions, emptyMap())
                } else {
                    loadCards(request, missing) { cards ->
                        deliverSessions(request, callback, sessions, cards)
                    }
                }
            }
        })
        return request
    }

    private fun deliverSessions(
        request: BiliRequest,
        callback: (List<MessageItem>?, String?) -> Unit,
        sessions: List<MessageItem>,
        cards: Map<Long, UserCard>
    ) {
        val merged = sessions.map { item ->
            val card = cards[item.mid]
            item.copy(
                name = item.name.ifBlank { card?.name.orEmpty() }
                    .ifBlank { if (item.system) "系统通知" else "用户${item.mid}" },
                face = item.face.ifBlank { card?.face.orEmpty() }
            )
        }.sortedWith(
            compareByDescending<MessageItem> { it.unread > 0 }.thenByDescending { it.ts }
        )
        mainHandler.post { if (!request.cancelled) callback(merged, null) }
    }

    private data class UserCard(val name: String, val face: String)

    /** 批量取用户名片（api.vc 接口，不需要 WBI 签名）。失败就当没取到，不影响列表。 */
    private fun loadCards(
        request: BiliRequest,
        uids: List<Long>,
        done: (Map<Long, UserCard>) -> Unit
    ) {
        val call = buildCall(
            "https://api.vc.bilibili.com/account/v1/user/cards?uids=" + uids.joinToString(",")
        )
        if (!request.attach(call)) return
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                request.detach(call)
                done(emptyMap())
            }

            override fun onResponse(call: Call, response: Response) {
                val cards = try {
                    response.use { parseCards(BiliHttp.json(it)) }
                } catch (error: Exception) {
                    emptyMap()
                }
                request.detach(call)
                done(cards)
            }
        })
    }

    private fun parseCards(root: JSONObject): Map<Long, UserCard> {
        if (root.optInt("code", -1) != 0) return emptyMap()
        val out = HashMap<Long, UserCard>()
        fun put(card: JSONObject?, fallbackMid: Long) {
            card ?: return
            val mid = card.optLong("mid", fallbackMid)
            if (mid <= 0L) return
            val name = cleanText(card.optString("name").orEmpty())
            val face = normalizeUrl(card.optString("face").orEmpty())
            if (name.isNotBlank() || face.isNotBlank()) out[mid] = UserCard(name, face)
        }
        when (val data = root.opt("data")) {
            is JSONObject -> {
                val keys = data.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(data.optJSONObject(key), key.toLongOrNull() ?: 0L)
                }
            }
            is JSONArray -> {
                for (i in 0 until data.length()) put(data.optJSONObject(i), 0L)
            }
        }
        return out
    }

    fun fetchReplies(callback: (List<MessageItem>?, String?) -> Unit): BiliRequest =
        request("https://api.bilibili.com/x/msgfeed/reply?platform=web&build=0&mobi_app=web", callback) { root ->
            parseFeed(root, MessageKind.REPLY, timeKey = "reply_time")
        }

    fun fetchAts(callback: (List<MessageItem>?, String?) -> Unit): BiliRequest =
        request("https://api.bilibili.com/x/msgfeed/at?platform=web&build=0&mobi_app=web", callback) { root ->
            parseFeed(root, MessageKind.AT, timeKey = "at_time")
        }

    fun fetchLikes(callback: (List<MessageItem>?, String?) -> Unit): BiliRequest =
        request("https://api.bilibili.com/x/msgfeed/like?platform=web&build=0&mobi_app=web", callback) { root ->
            parseFeed(root, MessageKind.LIKE, timeKey = "like_time")
        }

    /** 新增粉丝：粉丝列表按最近关注排序（需要 WBI 签名与登录态）。 */
    fun fetchNewFans(callback: (List<MessageItem>?, String?) -> Unit): BiliRequest {
        val request = BiliRequest()
        if (!BiliSessionStore.isLoggedIn()) {
            mainHandler.post { callback(null, "请先登录后查看消息") }
            return request
        }
        val params = linkedMapOf(
            "vmid" to (BiliSessionStore.cachedProfile()?.uid ?: 0L).toString(),
            "pn" to "1",
            "ps" to "30",
            "order" to "desc",
            "act" to "1"
        )
        val query = runCatching { BiliWbiSign.signParams(params) }.getOrNull()
        if (query.isNullOrBlank()) {
            mainHandler.post { callback(null, "消息加载失败，请稍后重试") }
            return request
        }
        val call = buildCall("https://api.bilibili.com/x/relation/fans?$query")
        if (!request.attach(call)) return request
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                request.detach(call)
                mainHandler.post { if (!request.cancelled) callback(null, readableError(e)) }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val root = BiliHttp.json(it)
                        if (root.optInt("code", -1) != 0) {
                            error(root.optString("message").ifBlank { "消息加载失败" })
                        }
                        parseFans(root)
                    }
                } catch (error: Exception) {
                    mainHandler.post { if (!request.cancelled) callback(null, readableError(error)) }
                    return
                }
                request.detach(call)
                mainHandler.post { if (!request.cancelled) callback(result, null) }
            }
        })
        return request
    }

    // ── 请求骨架 ────────────────────────────────────────────────────────────

    private fun request(
        address: String,
        callback: (List<MessageItem>?, String?) -> Unit,
        parse: (JSONObject) -> List<MessageItem>
    ): BiliRequest {
        val request = BiliRequest()
        if (!BiliSessionStore.isLoggedIn()) {
            mainHandler.post { callback(null, "请先登录后查看消息") }
            return request
        }
        val call = buildCall(address)
        if (!request.attach(call)) return request
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                request.detach(call)
                mainHandler.post { if (!request.cancelled) callback(null, readableError(e)) }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val root = BiliHttp.json(it)
                        if (root.optInt("code", -1) != 0) {
                            error(root.optString("message").ifBlank { "消息加载失败" })
                        }
                        parse(root)
                    }
                } catch (error: Exception) {
                    mainHandler.post { if (!request.cancelled) callback(null, readableError(error)) }
                    return
                }
                request.detach(call)
                mainHandler.post { if (!request.cancelled) callback(result, null) }
            }
        })
        return request
    }

    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private fun buildCall(address: String): Call {
        val builder = BiliHttp.request(address)
            .header("Referer", REFERER)
            .header("User-Agent", WEB_UA)
        val cookie = BiliSessionStore.cookie()
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return BiliHttp.client.newCall(builder.build())
    }

    private fun readableError(error: Throwable): String = when {
        error.message.isNullOrBlank() -> "消息加载失败"
        else -> error.message.orEmpty()
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    private fun parseSessions(root: JSONObject): List<MessageItem> {
        val data = root.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("session_list") ?: return emptyList()
        val out = ArrayList<MessageItem>(list.length())
        for (i in 0 until list.length()) {
            val session = list.optJSONObject(i) ?: continue
            val talker = session.optLong("talker_id", 0L)
            if (talker <= 0L) continue
            val systemType = session.optInt("system_msg_type", 0)
            val account = session.optJSONObject("account_info")
            val lastMsg = session.optJSONObject("last_msg")
            // session_ts 是会话最后活跃时间；at_time 在部分会话里为 0，
            // 再兜底用最后一条消息的毫秒时间戳。
            val time = session.optLong("session_ts", 0L).takeIf { it > 0L }
                ?: session.optLong("at_time", 0L).takeIf { it > 0L }
                ?: lastMsg?.optLong("timestamp", 0L)
                ?: 0L
            out += MessageItem(
                id = "session_$talker",
                kind = MessageKind.SESSION,
                name = cleanText(account?.optString("name").orEmpty()),
                face = normalizeUrl(account?.optString("face").orEmpty()),
                content = parseSessionPreview(lastMsg?.optString("content").orEmpty()),
                timeText = formatTime(time),
                unread = session.optInt("unread_count", 0),
                mid = talker,
                ts = normalizeTs(time),
                system = systemType > 0
            )
        }
        return out
    }

    private fun parseSessionPreview(rawJson: String): String {
        if (rawJson.isBlank()) return "[消息]"
        return runCatching {
            val obj = JSONObject(rawJson)
            val text = cleanText(obj.optString("content").orEmpty())
            if (text.isNotBlank()) return@runCatching text
            if (obj.has("picture")) return@runCatching "[图片]"
            val title = cleanText(obj.optString("title").orEmpty())
            val desc = cleanText(obj.optString("desc").orEmpty())
            when {
                title.isNotBlank() -> "[分享] $title"
                desc.isNotBlank() -> desc
                obj.has("notify") -> "[通知]"
                obj.has("reply") -> "[回复]"
                else -> ""
            }
        }.getOrDefault("")
            .ifBlank { "[消息]" }
    }

    private fun parseFeed(root: JSONObject, kind: MessageKind, timeKey: String): List<MessageItem> {
        val data = root.optJSONObject("data") ?: return emptyList()
        val items = data.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<MessageItem>(items.length())
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            val user = entry.optJSONObject("user")
            val item = entry.optJSONObject("item")
            val name = cleanText(user?.optString("nickname").orEmpty()).ifBlank { "哔哩哔哩用户" }
            val face = normalizeUrl(user?.optString("avatar").orEmpty())
            val title = cleanText(item?.optString("title").orEmpty())
            val source = cleanText(item?.optString("source_content").orEmpty())
            val uri = normalizeUrl(item?.optString("uri").orEmpty())
            val cover = normalizeUrl(item?.optString("cover").orEmpty())
            val content = when (kind) {
                MessageKind.LIKE -> buildString {
                    val target = title.ifBlank { source }
                    append(if (target.isNotBlank()) "赞了你的「$target」" else "赞了你的内容")
                }
                MessageKind.AT -> source.ifBlank { title }.ifBlank { "@了你" }
                else -> source.ifBlank { title }.ifBlank { "回复了你的评论" }
            }
            val time = feedTime(entry, item, timeKey)
            // msgfeed 的 target_id / root_id / source_id 分别对应回复、根评论、来源评论，
            // 网页版只要在内容页后面拼 #reply{rpid} 就能滚到那条评论。
            val replyId = (item?.optLong("target_id", 0L) ?: 0L).takeIf { it > 0L }
                ?: (item?.optLong("root_id", 0L) ?: 0L).takeIf { it > 0L }
                ?: item?.optLong("source_id", 0L) ?: 0L
            out += MessageItem(
                id = "${kind.name}_${entry.optLong("id", 0L)}_$i",
                kind = kind,
                name = name,
                face = face,
                content = content,
                timeText = formatTime(time),
                cover = cover,
                uri = uri,
                mid = user?.optLong("mid", 0L) ?: 0L,
                ts = normalizeTs(time),
                replyId = replyId
            )
        }
        // 接口给的是「按类型分页」的混合顺序，统一按时间倒序排一遍。
        out.sortByDescending { it.ts }
        return out
    }

    /** 通知接口的时间字段名在不同端点里不一样，逐个兜底取。 */
    private fun feedTime(entry: JSONObject, item: JSONObject?, timeKey: String): Long {
        val keys = arrayOf(timeKey, "reply_time", "at_time", "like_time", "ctime", "timestamp", "pub_time", "time")
        for (key in keys) {
            val value = entry.optLong(key, 0L)
            if (value > 0L) return value
        }
        return item?.optLong("reply_time", 0L) ?: 0L
    }

    private fun parseFans(root: JSONObject): List<MessageItem> {
        val data = root.optJSONObject("data") ?: return emptyList()
        val list: JSONArray = data.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<MessageItem>(list.length())
        for (i in 0 until list.length()) {
            val fan = list.optJSONObject(i) ?: continue
            val mid = fan.optLong("mid", 0L)
            val mtime = fan.optLong("mtime", 0L)
            out += MessageItem(
                id = "fan_$mid",
                kind = MessageKind.FAN,
                name = cleanText(fan.optString("uname").orEmpty()).ifBlank { "新粉丝" },
                face = normalizeUrl(fan.optString("face").orEmpty()),
                content = "关注了你",
                timeText = formatTime(mtime),
                mid = mid,
                ts = normalizeTs(mtime)
            )
        }
        out.sortByDescending { it.ts }
        return out
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun cleanText(value: String): String =
        value.replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeUrl(value: String): String {
        val raw = value.trim()
        return when {
            raw.isBlank() -> ""
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") -> "https://" + raw.removePrefix("http://")
            else -> raw
        }
    }

    /** 秒 / 毫秒 / 微秒混用，统一成毫秒。 */
    private fun normalizeTs(value: Long): Long {
        if (value <= 0L) return 0L
        var ts = value
        // 微秒、纳秒（会话游标）先压到毫秒
        while (ts > 100_000_000_000L) ts /= 1000L
        // 10 位以内按秒处理
        if (ts < 10_000_000_000L) ts *= 1000L
        return ts
    }

    private fun formatTime(raw: Long): String {
        val ts = normalizeTs(raw)
        if (ts <= 0L) return ""
        val now = System.currentTimeMillis()
        val diff = (now - ts).coerceAtLeast(0L)
        if (diff < 60_000L) return "刚刚"
        if (diff < 3_600_000L) return "${diff / 60_000L}分钟前"
        if (diff < 24 * 3_600_000L) return "${diff / 3_600_000L}小时前"
        val target = Calendar.getInstance().apply { timeInMillis = ts }
        val today = Calendar.getInstance()
        return if (target.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
            SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ts))
        } else {
            SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(ts))
        }
    }
}
