package com.yuilittle.bili

import android.os.Handler
import android.os.Looper
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Inflater

/** 一条直播弹幕（用户名 + 内容 + 颜色），id 用于叠加层去重。 */
internal data class LiveDanmaku(
    val id: Long,
    val user: String,
    val text: String,
    val color: Int,
    val isAdmin: Boolean = false
)

/**
 * 直播弹幕长连接。
 *
 * 流程：`getDanmuInfo`（WBI 签名）拿到 token 与弹幕服务器 → `wss://<host>/sub` 握手 →
 * 发送认证包（protover=2，服务端回包用 zlib 压缩，Android 自带 Inflater 可解，无需引入 brotli）→
 * 每 30s 发送心跳包维持连接。
 *
 * 断线自动重连（最多 5 次）；页面销毁时调用 [stop] 彻底释放。
 */
internal class LiveDanmakuClient(
    private val roomId: Long,
    private val onDanmaku: (LiveDanmaku) -> Unit,
    private val onState: (connected: Boolean) -> Unit
) {
    companion object {
        private const val DEFAULT_HOST = "broadcastlv.chat.bilibili.com"
        private const val HEARTBEAT_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 4_000L
        private const val MAX_RECONNECT = 5

        private const val OP_HEARTBEAT = 2
        private const val OP_HEARTBEAT_REPLY = 3
        private const val OP_MESSAGE = 5
        private const val OP_AUTH = 7
        private const val OP_AUTH_REPLY = 8

        /**
         * 弹幕长连接专用 client：必须关掉 callTimeout / readTimeout，
         * 否则全局 28s 调用超时会把长连接一并取消。
         */
        private val wsClient by lazy(LazyThreadSafetyMode.NONE) {
            BiliHttp.client.newBuilder()
                .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong(0L)

    private var socket: WebSocket? = null
    private var infoRequest: BiliRequest? = null
    private var token: String = ""
    private var hosts: List<String> = emptyList()
    private var hostIndex = 0
    private var generation = 0
    private var reconnectAttempts = 0
    private var stopped = true

    private val heartbeatTask = object : Runnable {
        override fun run() {
            if (stopped) return
            socket?.send(buildPacket(OP_HEARTBEAT, "[object Object]".toByteArray()).toByteString())
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    fun start() {
        if (!stopped) return
        stopped = false
        reconnectAttempts = 0
        onState(false)
        requestInfoAndConnect()
    }

    fun stop() {
        stopped = true
        generation++
        mainHandler.removeCallbacks(heartbeatTask)
        infoRequest?.cancel()
        infoRequest = null
        socket?.close(1000, "leave")
        socket = null
    }

    private fun requestInfoAndConnect() {
        infoRequest?.cancel()
        infoRequest = LiveApi.fetchDanmuInfo(roomId) { info, _ ->
            if (stopped) return@fetchDanmuInfo
            token = info?.token.orEmpty()
            val list = info?.hosts.orEmpty().filter { it.isNotBlank() }
            hosts = if (list.isEmpty()) listOf(DEFAULT_HOST) else list
            hostIndex = 0
            connect()
        }
    }

    private fun connect() {
        if (stopped) return
        val gen = ++generation
        val host = hosts.getOrNull(hostIndex % hosts.size) ?: DEFAULT_HOST
        val request = BiliHttp.request("wss://$host/sub")
            .header("Origin", "https://live.bilibili.com")
            .header("Referer", "https://live.bilibili.com/$roomId")
            .build()
        socket?.cancel()
        socket = wsClient.newWebSocket(request, listener(gen))
    }

    private fun listener(gen: Int) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (stopped || gen != generation) {
                webSocket.close(1000, "stale")
                return
            }
            webSocket.send(buildPacket(OP_AUTH, buildAuthBody().toByteArray()).toByteString())
            webSocket.send(buildPacket(OP_HEARTBEAT, "[object Object]".toByteArray()).toByteString())
            mainHandler.removeCallbacks(heartbeatTask)
            mainHandler.postDelayed(heartbeatTask, HEARTBEAT_MS)
            reconnectAttempts = 0
            mainHandler.post { if (!stopped) onState(true) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            if (stopped || gen != generation) return
            parseFrames(bytes.toByteArray(), 0)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // 直播弹幕服务端只发二进制帧，文本帧忽略。
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped || gen != generation) return
            mainHandler.post { if (!stopped) onState(false) }
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (stopped || gen != generation) return
            mainHandler.post { if (!stopped) onState(false) }
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        mainHandler.removeCallbacks(heartbeatTask)
        if (reconnectAttempts >= MAX_RECONNECT) return
        reconnectAttempts++
        // 依次尝试其它弹幕服务器域名，全部失败再重新拉取 token。
        if (hosts.size > 1) hostIndex++
        mainHandler.postDelayed({
            if (stopped) return@postDelayed
            if (reconnectAttempts % hosts.size == 0) {
                requestInfoAndConnect()
            } else {
                connect()
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun buildAuthBody(): String {
        val uid = BiliSessionStore.cookie()
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("DedeUserID=") }
            ?.substringAfter('=')
            ?.toLongOrNull() ?: 0L
        val body = JSONObject()
            .put("uid", uid)
            .put("roomid", roomId)
            .put("protover", 2)
            .put("platform", "web")
            .put("type", 2)
            .put("key", token)
        return body.toString()
    }

    private fun buildPacket(operation: Int, body: ByteArray): ByteArray {
        val length = 16 + body.size
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(length)
        buffer.putShort(16)
        buffer.putShort(1)
        buffer.putInt(operation)
        buffer.putInt(1)
        buffer.put(body)
        return buffer.array()
    }

    /** 递归解析粘包：version=2 的包体是 zlib 压缩的多包合集。 */
    private fun parseFrames(data: ByteArray, depth: Int) {
        if (depth > 4) return
        var offset = 0
        while (offset + 16 <= data.size) {
            val packetLength = readInt(data, offset)
            val headerLength = readShort(data, offset + 4)
            val version = readShort(data, offset + 6)
            val operation = readInt(data, offset + 8)
            if (packetLength < 16 || offset + packetLength > data.size) break
            val bodyStart = offset + headerLength
            val bodyEnd = offset + packetLength
            if (bodyStart in 0..bodyEnd && bodyEnd <= data.size) {
                val body = data.copyOfRange(bodyStart, bodyEnd)
                when {
                    version == 2 -> inflate(body)?.let { parseFrames(it, depth + 1) }
                    operation == OP_MESSAGE -> handleMessage(body)
                    operation == OP_AUTH_REPLY || operation == OP_HEARTBEAT_REPLY -> Unit
                }
            }
            offset += packetLength
        }
    }

    private fun handleMessage(body: ByteArray) {
        if (body.isEmpty()) return
        val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return
        val cmd = json.optString("cmd")
        if (!cmd.startsWith("DANMU_MSG")) return

        val info = json.optJSONArray("info") ?: return
        val text = info.optString(1).trim()
        if (text.isEmpty()) return
        val userArray = info.optJSONArray(2)
        val user = userArray?.optString(1).orEmpty().ifBlank { "匿名用户" }
        val meta = info.optJSONArray(0)
        val admin = (meta?.optInt(2, 0) ?: 0) == 1
        val rawColor = meta?.optInt(3, 0xFFFFFF) ?: 0xFFFFFF
        val color = if (rawColor == 0) 0xFFFFFF else (rawColor and 0xFFFFFF)

        val item = LiveDanmaku(
            id = sequence.incrementAndGet(),
            user = user,
            text = text,
            color = color,
            isAdmin = admin
        )
        mainHandler.post { if (!stopped) onDanmaku(item) }
    }

    private fun inflate(body: ByteArray): ByteArray? {
        if (body.isEmpty()) return null
        val inflater = Inflater()
        return try {
            inflater.setInput(body)
            val out = ByteArrayOutputStream(body.size * 4)
            val buffer = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read <= 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun readShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}
