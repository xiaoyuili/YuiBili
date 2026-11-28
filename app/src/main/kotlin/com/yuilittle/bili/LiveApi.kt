package com.yuilittle.bili

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 直播间列表项（来自直播分区房间列表接口）。 */
internal data class LiveRoom(
    val roomId: Long,
    val uid: Long,
    val title: String,
    val owner: String,
    val cover: String,
    val face: String,
    val areaName: String,
    val online: Long
)

/** 主播开播状态（空间页「直播中」标签与「进入直播间」按钮使用）。 */
internal data class LiveRoomStatus(
    val live: Boolean,
    val roomId: Long,
    val title: String,
    val cover: String,
    val online: Long
)

/** 直播清晰度档位（qn + 展示名，来自接口的 quality_description）。 */
internal data class LiveQuality(val qn: Int, val label: String)

/**
 * 直播间播放信息。
 *
 * [qn] 是服务端实际下发的清晰度（可能低于请求值：未实名/未登录/主播只推了一路都会降级），
 * [qualities] 是当前会话可选的档位，[requestedQn] 记录用户点的那一档用于降级提示。
 */
internal data class LivePlayInfo(
    val urls: List<String>,
    val qualityLabel: String,
    val qn: Int,
    val qualities: List<LiveQuality>,
    val requestedQn: Int = qn,
    /** 本次接口已拿到的全部档位地址，切档时可立即复用，不必再请求。 */
    val streamsByQn: Map<Int, List<String>> = emptyMap()
)

/** 弹幕长连接参数：token + 可用弹幕服务器域名。 */
internal data class LiveDanmuInfo(
    val token: String,
    val hosts: List<String>
)

/**
 * 直播相关接口。
 *
 * 房间列表使用 `area/getRoomList`（匿名可用，按人气排序）；
 * 播放地址使用 `room/v1/Room/playUrl`（platform=web 返回 FLV durl，含多个备用 CDN）。
 * 两者都要求 Referer 指向 live.bilibili.com，否则部分 CDN 会 403。
 */
internal object LiveApi {
    private const val LIST_URL =
        "https://api.live.bilibili.com/room/v1/area/getRoomList?platform=web&parent_area_id=0&area_id=0&sort_type=online"
    /**
     * web 端现行播放地址接口。
     *
     * 旧的 `room/v1/Room/playUrl` 无论 qn 传什么都会返回同一路 current_qn=250 的流，
     * 画质切换看似成功、实际画面不变；v2 接口才会按 qn 下发对应码率的 stream，
     * 并在 g_qn_desc 里给出全部档位、accept_qn 里给出当前会话真正可用的档位。
     */
    private const val PLAY_INFO_V2 =
        "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?platform=web&ptype=8&dolby=5&panorama=1&protocol=0,1&format=0,1,2&codec=0,1&room_id="
    private const val DANMU_INFO_URL = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?"
    private const val PAGE_SIZE = 30

    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }

    /**
     * 查询主播开播状态。
     *
     * 未开播的 UP 也可能返回 roomid（直播间存在但没推流），必须用 [LiveRoomStatus.live]
     * 决定「进入直播间」按钮是否可点，不能只看 roomid。
     */
    fun fetchRoomStatus(mid: Long, callback: (LiveRoomStatus?, String?) -> Unit): BiliRequest {
        val request = BiliRequest()
        if (mid <= 0L) {
            mainHandler.post { callback(null, "无效的用户") }
            return request
        }
        val call = jsonCall("https://api.live.bilibili.com/room/v1/Room/getRoomInfoOld?mid=$mid")
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
                        ensureSuccess(root)
                        parseRoomStatus(root)
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

    private fun parseRoomStatus(root: JSONObject): LiveRoomStatus {
        val data = root.optJSONObject("data") ?: JSONObject()
        return LiveRoomStatus(
            live = data.optInt("liveStatus", 0) == 1,
            roomId = data.optLong("roomid", 0L),
            title = cleanHtml(data.optString("title")),
            cover = normalizeUrl(data.optString("cover")),
            online = data.optLong("online", 0L)
        )
    }

    /** 拉取人气直播间分页；返回空列表视为没有更多。 */
    fun fetchRooms(page: Int, callback: (List<LiveRoom>?, String?) -> Unit): BiliRequest {
        val request = BiliRequest()
        val index = page.coerceAtLeast(1)
        val call = jsonCall("$LIST_URL&page=$index&page_size=$PAGE_SIZE")
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
                        ensureSuccess(root)
                        val array = roomArray(root)
                        val rooms = ArrayList<LiveRoom>(array.length())
                        for (i in 0 until array.length()) {
                            array.optJSONObject(i)?.let { roomFromJson(it) }?.let(rooms::add)
                        }
                        rooms
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

    /** 拉取直播间播放地址（含备用 CDN 与真实清晰度），失败时回调可读错误。 */
    fun fetchPlayInfo(roomId: Long, qn: Int, callback: (LivePlayInfo?, String?) -> Unit): BiliRequest {
        val request = BiliRequest()
        if (roomId <= 0L) {
            mainHandler.post { callback(null, "无效的直播间") }
            return request
        }
        val call = jsonCall(PLAY_INFO_V2 + roomId + "&qn=" + qn.coerceAtLeast(1))
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
                        ensureSuccess(root)
                        parsePlayInfo(root, qn)
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

    /**
     * 获取弹幕长连接参数（token + 服务器列表）。
     * 该接口带风控，必须走 WBI 签名；签名失败时回落默认弹幕服务器，仍可匿名收发弹幕。
     */
    fun fetchDanmuInfo(roomId: Long, callback: (LiveDanmuInfo?, String?) -> Unit): BiliRequest {
        val request = BiliRequest()
        if (roomId <= 0L) {
            mainHandler.post { callback(null, "无效的直播间") }
            return request
        }
        val signed = runCatching {
            BiliWbiSign.signParams(mapOf("id" to roomId.toString(), "type" to "0"))
        }.getOrElse { "id=$roomId&type=0" }
        val call = jsonCall(DANMU_INFO_URL + signed)
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
                        ensureSuccess(root)
                        parseDanmuInfo(root)
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

    private fun roomArray(root: JSONObject): JSONArray = when (val data = root.opt("data")) {
        // 实测 data 直接就是数组；兼容文档形态 data.list。
        is JSONArray -> data
        is JSONObject -> data.optJSONArray("list") ?: JSONArray()
        else -> JSONArray()
    }

    private fun roomFromJson(obj: JSONObject): LiveRoom? {
        val roomId = obj.optLong("roomid").takeIf { it > 0L } ?: return null
        val cover = normalizeUrl(
            obj.optString("user_cover").ifBlank {
                obj.optString("cover").ifBlank { obj.optString("system_cover") }
            }
        )
        return LiveRoom(
            roomId = roomId,
            uid = obj.optLong("uid"),
            title = cleanHtml(obj.optString("title")).ifBlank { "直播间 $roomId" },
            owner = cleanHtml(obj.optString("uname")).ifBlank { "哔哩哔哩主播" },
            cover = cover,
            face = normalizeUrl(obj.optString("face")),
            areaName = cleanHtml(
                obj.optString("area_v2_name").ifBlank {
                    obj.optString("area_name").ifBlank { obj.optString("parent_name") }
                }
            ),
            online = obj.optLong("online").coerceAtLeast(0L)
        )
    }

    private fun parsePlayInfo(root: JSONObject, requestedQn: Int): LivePlayInfo {
        val data = root.optJSONObject("data") ?: error("直播间暂未开播")
        if (data.optInt("live_status", 1) == 0) {
            error("主播暂未开播")
        }
        val playUrl = data.optJSONObject("playurl_info")?.optJSONObject("playurl")
            ?: error("直播间暂未开播或该直播不支持网页端观看")

        // g_qn_desc 是全量档位表，accept_qn 才是当前会话真正能取到的档位。
        val descMap = LinkedHashMap<Int, String>()
        val descArray = playUrl.optJSONArray("g_qn_desc") ?: JSONArray()
        for (i in 0 until descArray.length()) {
            val entry = descArray.optJSONObject(i) ?: continue
            val qn = entry.optInt("qn")
            if (qn <= 0) continue
            descMap[qn] = cleanHtml(entry.optString("desc")).ifBlank { qualityLabelOf(qn) }
        }

        // 服务端可能一次只下发一档（未实名/未登录/主播单码率推流），
        // 因此按 qn 归并已拿到的地址：同一 qn 多编码/多协议时保留兼容性最好的那一条。
        val bestByQn = LinkedHashMap<Int, Pair<Int, List<String>>>()
        var acceptQn: List<Int> = emptyList()
        val streams = playUrl.optJSONArray("stream") ?: JSONArray()
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val protocol = stream.optString("protocol_name")
            val formats = stream.optJSONArray("format") ?: JSONArray()
            for (j in 0 until formats.length()) {
                val format = formats.optJSONObject(j) ?: continue
                val formatName = format.optString("format_name")
                val codecs = format.optJSONArray("codec") ?: JSONArray()
                for (k in 0 until codecs.length()) {
                    val codec = codecs.optJSONObject(k) ?: continue
                    val qn = codec.optInt("current_qn", 0)
                    if (qn <= 0) continue
                    if (acceptQn.isEmpty()) {
                        acceptQn = parseIntArray(codec.optJSONArray("accept_qn"))
                    }
                    val urls = buildStreamUrls(codec.optString("base_url"), codec.optJSONArray("url_info"))
                    if (urls.isEmpty()) continue
                    val codecName = codec.optString("codec_name")
                    // FLV + AVC 兼容性最好；HEVC 依赖硬解，HLS 作为最后兜底。
                    val score = when {
                        protocol == "http_stream" && formatName == "flv" && codecName == "avc" -> 5
                        protocol == "http_stream" && formatName == "flv" -> 4
                        protocol == "http_hls" && formatName == "ts" && codecName == "avc" -> 2
                        else -> 1
                    }
                    val previous = bestByQn[qn]
                    if (previous == null || score > previous.first) {
                        bestByQn[qn] = score to urls
                    }
                }
            }
        }
        if (bestByQn.isEmpty()) {
            error("直播间暂未开播或该直播不支持网页端观看")
        }

        val currentQn = if (requestedQn in bestByQn) requestedQn else bestByQn.keys.max()
        // 可选档位取 accept_qn（本次会话允许的档位）；服务端仍可能降级，由界面提示并剔除。
        val selectable = if (acceptQn.isNotEmpty()) {
            acceptQn.filter { it == currentQn || descMap.containsKey(it) || it in bestByQn }
        } else {
            descMap.keys.toList()
        }
        val qualities = (selectable + currentQn).distinct()
            .sortedDescending()
            .map { qn -> LiveQuality(qn, descMap[qn] ?: qualityLabelOf(qn)) }
        val streamsByQn = bestByQn.mapValues { it.value.second }

        val label = descMap[currentQn] ?: qualityLabelOf(currentQn)
        return LivePlayInfo(
            urls = streamsByQn.getValue(currentQn),
            qualityLabel = label,
            qn = currentQn,
            qualities = qualities,
            requestedQn = requestedQn,
            streamsByQn = streamsByQn
        )
    }

    /** codec 里 base_url 是相对路径，需要和每个 CDN 的 host + extra 拼成完整地址。 */
    private fun buildStreamUrls(baseUrl: String, urlInfo: JSONArray?): List<String> {
        if (baseUrl.isBlank() || urlInfo == null) return emptyList()
        val base = baseUrl.trimEnd('?')
        val out = LinkedHashSet<String>()
        for (i in 0 until urlInfo.length()) {
            val info = urlInfo.optJSONObject(i) ?: continue
            val rawHost = info.optString("host").trim()
            if (rawHost.isBlank()) continue
            val host = when {
                rawHost.startsWith("https://") || rawHost.startsWith("http://") -> normalizeUrl(rawHost)
                rawHost.startsWith("//") -> "https:$rawHost"
                else -> "https://$rawHost"
            }
            val extra = info.optString("extra").trimStart('?')
            val url = if (extra.isBlank()) host + base else "$host$base?$extra"
            if (url.startsWith("https://")) out += url
        }
        return out.toList()
    }

    private fun parseIntArray(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        val values = ArrayList<Int>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optInt(i)
            if (value > 0) values += value
        }
        return values
    }

    private fun parseDanmuInfo(root: JSONObject): LiveDanmuInfo {
        val data = root.optJSONObject("data") ?: error("弹幕服务器暂不可用")
        val token = data.optString("token")
        val hosts = ArrayList<String>(3)
        val list = data.optJSONArray("host_list") ?: JSONArray()
        for (i in 0 until list.length()) {
            val host = list.optJSONObject(i)?.optString("host").orEmpty()
            if (host.isNotBlank() && !hosts.contains(host)) hosts += host
        }
        return LiveDanmuInfo(token = token, hosts = hosts)
    }

    private fun qualityLabelOf(qn: Int): String = when (qn) {
        30000 -> "杜比"
        20000 -> "4K"
        10000 -> "原画"
        401 -> "蓝光"
        400 -> "蓝光"
        250 -> "超清"
        150 -> "高清"
        80 -> "流畅"
        else -> if (qn >= 10000) "原画" else "高清"
    }

    private fun jsonCall(address: String): Call {
        val cookie = BiliSessionStore.cookie()
        val builder = BiliHttp.request(address)
            .header("Referer", "https://live.bilibili.com/")
            .header("Origin", "https://live.bilibili.com")
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return BiliHttp.client.newCall(builder.build())
    }

    private fun ensureSuccess(root: JSONObject) {
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException(root.optString("message").ifBlank { root.optString("msg").ifBlank { "直播接口暂不可用" } })
        }
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

    private fun readableError(error: Exception): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) "网络暂不可用，请稍后再试" else message
    }
}
