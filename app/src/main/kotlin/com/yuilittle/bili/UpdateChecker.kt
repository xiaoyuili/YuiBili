package com.yuilittle.bili

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 检查 GitHub Releases 上的新版本。
 *
 * 数据源是本仓库（改版作者 suqingan 的 YuiBili）的 Releases 列表：
 * 逐个比对 versionName，取语义版本最高的一个；支持 beta / rc 之类的预发布后缀，
 * 正式版永远高于同号预发布版（1.1.0 > 1.1.0beta2）。
 */
internal object UpdateChecker {

    /** 默认下载渠道：蓝奏云网盘（不依赖 GitHub 访问）。 */
    const val PAN_URL = "https://wwasg.lanzouv.com/b0kph8kgh"
    const val PAN_PASSWORD = "4zzg"

    private const val PREFS = "yuibili_settings"
    private const val KEY_AUTO_CHECK = "update_auto_check"
    private const val KEY_LAST_TS = "update_last_check_ts"
    private const val KEY_DISMISSED = "update_dismissed_tag"
    private const val REPO = "suqingan/YuiBili"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"

    /** 自动检查的最小间隔：一天。 */
    private const val AUTO_INTERVAL_MS = 24L * 60L * 60L * 1000L

    data class Release(
        val tag: String,
        val name: String,
        val body: String,
        val pageUrl: String,
        val apkUrl: String?,
        val publishedAt: String
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 自动检查开关，默认开启。 */
    fun isAutoCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun lastCheckTime(context: Context): Long = prefs(context).getLong(KEY_LAST_TS, 0L)

    private fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_TS, System.currentTimeMillis()).apply()
    }

    /** 用户选择「忽略此版本」后，不再对同一版本弹自动提示。 */
    fun dismissedTag(context: Context): String? = prefs(context).getString(KEY_DISMISSED, null)

    fun dismiss(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_DISMISSED, tag).apply()
    }

    fun shouldAutoCheck(context: Context): Boolean =
        isAutoCheckEnabled(context) &&
            System.currentTimeMillis() - lastCheckTime(context) >= AUTO_INTERVAL_MS

    /**
     * 拉取最新版本。callback 在主线程回调；失败时 release 为 null、error 有值。
     */
    fun check(context: Context, callback: (release: Release?, error: String?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            var release: Release? = null
            var error: String? = null
            try {
                val request = Request.Builder()
                    .url(API)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "YuiBili-Android")
                    .build()
                BiliHttp.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error = "服务器返回 HTTP ${response.code}"
                        return@use
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        error = "更新信息为空"
                        return@use
                    }
                    val array = org.json.JSONArray(body)
                    var best: JSONObject? = null
                    var bestVer: Version? = null
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        if (item.optBoolean("draft", false)) continue
                        val tag = item.optString("tag_name").orEmpty()
                        val ver = Version.parse(tag)
                        if (ver == null) continue
                        val prev = bestVer
                        if (prev == null || ver > prev) {
                            bestVer = ver
                            best = item
                        }
                    }
                    if (best == null) {
                        error = "暂未获取到版本信息"
                        return@use
                    }
                    val tag = best.optString("tag_name")
                    val apk = best.optJSONArray("assets")?.let { assets ->
                        (0 until assets.length())
                            .mapNotNull { assets.optJSONObject(it) }
                            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                            ?.optString("browser_download_url")
                    }
                    release = Release(
                        tag = tag,
                        name = best.optString("name").ifBlank { tag },
                        body = best.optString("body").orEmpty(),
                        pageUrl = best.optString("html_url")
                            .ifBlank { "https://github.com/$REPO/releases" },
                        apkUrl = apk?.takeIf { it.isNotBlank() },
                        publishedAt = best.optString("published_at").orEmpty()
                    )
                    // 记录本次检查时间，避免每次启动都重复请求
                    markChecked(context)
                }
            } catch (e: Exception) {
                error = e.message ?: "网络请求失败"
            }
            handler.post { callback(release, error) }
        }.apply { isDaemon = true }.start()
    }

    fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** 是否比当前版本新。 */
    fun isNewer(release: Release, currentVersion: String): Boolean {
        val latest = Version.parse(release.tag) ?: return false
        val current = Version.parse(currentVersion) ?: return false
        return latest > current
    }

    /**
     * 宽松的语义版本号：主次修订 + 预发布标识。
     * 支持 "1.0.1beta5"、"v1.1.0"、"1.2"、"1.2.3-rc1" 等写法。
     * 正式版优先于同号预发布版。
     */
    data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preKind: String?,   // null 表示正式版
        val preNumber: Int
    ) : Comparable<Version> {

        override fun compareTo(other: Version): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)
            // 同号：正式版 > 预发布版
            val a = preKind
            val b = other.preKind
            if (a == null && b == null) return 0
            if (a == null) return 1
            if (b == null) return -1
            // beta > alpha > rc 的常见顺序
            val rank = mapOf("alpha" to 0, "beta" to 1, "rc" to 2)
            val ra = rank[a] ?: 0
            val rb = rank[b] ?: 0
            if (ra != rb) return ra.compareTo(rb)
            return preNumber.compareTo(other.preNumber)
        }

        companion object {

            fun parse(raw: String?): Version? {
                var text = raw?.trim().orEmpty()
                if (text.isEmpty()) return null
                if (text[0] == 'v' || text[0] == 'V') text = text.substring(1)

                // 手写扫描：不依赖 java.util.regex，避免个别 Android 运行时的匹配差异
                val parts = ArrayList<Int>(3)
                val buffer = StringBuilder()
                var i = 0
                while (i < text.length) {
                    val c = text[i]
                    if (c in '0'..'9') {
                        buffer.append(c)
                        i++
                    } else if (c == '.' && buffer.isNotEmpty()) {
                        parts.add(buffer.toString().toInt())
                        buffer.setLength(0)
                        i++
                    } else {
                        break
                    }
                }
                if (buffer.isNotEmpty()) parts.add(buffer.toString().toInt())
                if (parts.isEmpty()) return null

                // 跳过可能存在的分隔符：- _ .
                if (i < text.length && (text[i] == '-' || text[i] == '_' || text[i] == '.')) i++

                val kindBuilder = StringBuilder()
                while (i < text.length && text[i].isLetter()) {
                    kindBuilder.append(text[i])
                    i++
                }
                val numBuilder = StringBuilder()
                while (i < text.length && text[i] in '0'..'9') {
                    numBuilder.append(text[i])
                    i++
                }
                val kind = kindBuilder.toString().lowercase().ifBlank { null }
                val num = numBuilder.toString().toIntOrNull() ?: 0

                return Version(
                    major = parts.getOrElse(0) { 0 },
                    minor = parts.getOrElse(1) { 0 },
                    patch = parts.getOrElse(2) { 0 },
                    preKind = kind,
                    preNumber = num
                )
            }
        }
    }
}
