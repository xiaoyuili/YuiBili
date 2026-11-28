package com.yuilittle.bili

import android.content.Context

internal object PreferredVideoQuality {
    const val REMEMBER = 0
    const val HIGHEST = -1
    private const val MAX_REQUEST_QUALITY = 127
    val choices = listOf(REMEMBER, HIGHEST, 16, 32, 64, 80, 112, 116, 120, 125, 126, 127)
    private const val FALLBACK = 64
    private fun prefs(context: Context) = context.getSharedPreferences("bili_player", Context.MODE_PRIVATE)

    fun setting(context: Context): Int = prefs(context).getInt("preferred_quality", REMEMBER).takeIf { it in choices } ?: REMEMBER
    fun set(context: Context, qn: Int) { prefs(context).edit().putInt("preferred_quality", qn).apply() }
    fun onManualSelection(context: Context, qn: Int) {
        if (qn > 0 && qn in choices) prefs(context).edit().putInt("last_quality", qn).apply()
    }
    fun initial(context: Context): Int = resolve(setting(context), prefs(context).getInt("last_quality", FALLBACK))
    fun resolve(fixed: Int, last: Int): Int = if (fixed == HIGHEST) HIGHEST
        else sequenceOf(fixed, last).firstOrNull { it > 0 && it in choices } ?: FALLBACK

    fun requestQuality(preference: Int, loggedIn: Boolean): Int {
        val quality = if (preference == HIGHEST) MAX_REQUEST_QUALITY else preference
        return if (loggedIn) quality else minOf(quality, BiliPlayUrl.QUALITY_480)
    }

    fun resumeRequestQuality(preference: Int, lastRequest: Int, loggedIn: Boolean, sessionChanged: Boolean = false): Int {
        val target = requestQuality(preference, loggedIn)
        return if (preference == HIGHEST && !sessionChanged && lastRequest > 0) minOf(target, lastRequest) else target
    }

    // 自动最高只用于首选请求，解码失败后显式降档仍按本次请求选轨。
    fun selectionQuality(preference: Int, requested: Int): Int =
        if (preference == HIGHEST && requested == MAX_REQUEST_QUALITY) HIGHEST else requested

    fun label(qn: Int): String = when (qn) {
        REMEMBER -> "记住上次手选"
        HIGHEST -> "当前视频最高画质"
        else -> formatQualityButtonText(qn)
    }
}
