package com.yuilittle.bili

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

internal data class ContentRangeHeader(
    val unit: String,
    val start: Long?,
    val end: Long?,
    val total: Long?
) {
    val isSatisfiableRange: Boolean get() = unit == "bytes" && start != null && end != null && end >= start
    val isUnsatisfiable: Boolean get() = unit == "bytes" && start == null && end == null && total != null && total >= 0L

    fun completesOpenEndedRequest(offset: Long, bytesRead: Long): Boolean =
        isSatisfiableRange && start == offset && total != null && end == total - 1L && bytesRead == total - offset
}

internal object MediaTransportPolicy {
    private const val MAX_REDIRECTS = 3

    private val apiHostSuffixes = listOf("bilibili.com", "biligame.com")
    private val cdnAnonymousCookieHostSuffixes = listOf(
        "bilivideo.com",
        "bilivideo.cn",
        "hdslb.com",
        "biliimg.com",
        "akamaized.net"
    )

    fun isApiHost(host: String): Boolean = matchesHostSuffix(host, apiHostSuffixes)

    fun isTrustedCdnCookieHost(host: String): Boolean = matchesHostSuffix(host, cdnAnonymousCookieHostSuffixes)

    private fun matchesHostSuffix(host: String, suffixes: List<String>): Boolean {
        val normalized = host.trim().lowercase()
        if (normalized.isBlank()) return false
        return suffixes.any { suffix -> normalized == suffix || normalized.endsWith(".$suffix") }
    }

    fun validateMediaUrl(rawUrl: String): HttpUrl {
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw IOException("媒体地址无效")
        if (!url.isHttps) {
            throw IOException("仅允许安全的 HTTPS 媒体连接")
        }
        return url
    }

    fun buildCdnHeaders(url: HttpUrl, anonymousCookie: String, rangeHeader: String? = null): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to CDN_USER_AGENT,
            "Referer" to "https://www.bilibili.com/",
            "Origin" to "https://www.bilibili.com",
            "Accept" to "*/*"
        )
        if (url.isHttps && anonymousCookie.isNotBlank() && isTrustedCdnCookieHost(url.host)) {
            headers["Cookie"] = anonymousCookie
        }
        if (!rangeHeader.isNullOrBlank()) {
            headers["Range"] = rangeHeader
        }
        return headers
    }

    fun executeWithSafeRedirects(
        client: OkHttpClient,
        initialUrl: String,
        anonymousCookie: String,
        rangeHeader: String? = null,
        onCallCreated: ((okhttp3.Call) -> Unit)? = null
    ): Response {
        var current = validateMediaUrl(initialUrl)
        var redirects = 0
        while (true) {
            val requestBuilder = Request.Builder().url(current).get()
            buildCdnHeaders(current, anonymousCookie, rangeHeader).forEach { (name, value) ->
                requestBuilder.header(name, value)
            }
            val call = client.newCall(requestBuilder.build())
            onCallCreated?.invoke(call)
            val response = call.execute()
            if (response.code in 300..399) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) {
                    throw IOException("媒体重定向缺少目标地址（HTTP ${response.code}）")
                }
                if (redirects >= MAX_REDIRECTS) {
                    throw IOException("媒体重定向次数过多")
                }
                val resolved = current.resolve(location)
                    ?: throw IOException("媒体重定向地址无效")
                if (!resolved.isHttps) {
                    throw IOException("禁止从 HTTPS 降级到明文 HTTP 媒体连接")
                }
                current = resolved
                redirects++
                continue
            }
            return response
        }
    }

    fun parseContentRange(rawHeader: String?): ContentRangeHeader? {
        val raw = rawHeader?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val space = raw.indexOf(' ')
        if (space <= 0) return null
        val unit = raw.substring(0, space).trim().lowercase()
        val spec = raw.substring(space + 1).trim()
        val slash = spec.indexOf('/')
        if (slash <= 0) return null
        val rangePart = spec.substring(0, slash).trim()
        val totalPart = spec.substring(slash + 1).trim()
        val total = when (totalPart) {
            "*" -> null
            else -> totalPart.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        }
        if (rangePart == "*") {
            return if (total != null) ContentRangeHeader(unit, null, null, total) else null
        }
        val dash = rangePart.indexOf('-')
        if (dash <= 0) return null
        val start = rangePart.substring(0, dash).toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val end = rangePart.substring(dash + 1).toLongOrNull()?.takeIf { it >= start } ?: return null
        if (total != null && end >= total) return null
        return ContentRangeHeader(unit, start, end, total)
    }

    fun parseByteRangeSpec(spec: String?): LongRange? {
        val raw = spec?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val dash = raw.indexOf('-')
        if (dash <= 0 || dash >= raw.lastIndex) return null
        val start = raw.substring(0, dash).trim().toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val end = raw.substring(dash + 1).trim().toLongOrNull()?.takeIf { it >= start } ?: return null
        return start..end
    }
}
