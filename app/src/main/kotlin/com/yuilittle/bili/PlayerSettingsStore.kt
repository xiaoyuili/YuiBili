package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * 播放相关全局设置：屏幕方向与默认画质。
 *
 * 与项目里其它偏好项保持一致，使用 SharedPreferences 单例范式；
 * 这里刻意不缓存静态状态，确保从任意入口直接启动播放页时也能读到最新设置。
 */
internal object PlayerSettingsStore {

    enum class OrientationMode(val key: String, val label: String, val summary: String) {
        FOLLOW_SYSTEM("system", "跟随系统", "进入播放页后仍可随系统旋转"),
        PORTRAIT("portrait", "锁定竖屏", "播放页与全屏都保持竖屏"),
        LANDSCAPE("landscape", "锁定横屏", "播放页与全屏都保持横屏");

        companion object {
            fun fromKey(raw: String?): OrientationMode =
                entries.firstOrNull { it.key == raw } ?: FOLLOW_SYSTEM
        }
    }

    data class QualityOption(val qn: Int, val label: String, val summary: String)

    /** qn = 0 表示保留原有「跟随上次手动选择」行为。 */
    val QUALITY_OPTIONS: List<QualityOption> = listOf(
        QualityOption(0, "跟随上次选择", "沿用播放页手动切换过的画质"),
        QualityOption(BiliPlayUrl.QUALITY_360, "360P", "较省流量"),
        QualityOption(BiliPlayUrl.QUALITY_480, "480P", "游客可用画质"),
        QualityOption(BiliPlayUrl.QUALITY_720, "720P", "默认推荐画质"),
        QualityOption(BiliPlayUrl.QUALITY_1080, "1080P", "需登录"),
        QualityOption(BiliPlayUrl.QUALITY_1080_PLUS, "1080P+", "需大会员"),
        QualityOption(116, "1080P60", "需大会员"),
        QualityOption(BiliPlayUrl.QUALITY_4K, "4K", "需大会员"),
        QualityOption(125, "HDR", "需设备与大会员支持"),
        QualityOption(126, "杜比视界", "需设备与大会员支持"),
        QualityOption(127, "8K", "需设备与大会员支持")
    )

    private const val PREFS = "yuibili_settings"
    private const val KEY_ORIENTATION = "play_orientation"
    private const val KEY_HOME_ORIENTATION = "home_orientation"
    private const val KEY_LANDSCAPE_AUTO_FULLSCREEN = "landscape_auto_fullscreen"
    private const val KEY_SMART_FULLSCREEN = "smart_fullscreen_by_video_ratio"
    private const val KEY_DEFAULT_QUALITY = "default_quality"
    private const val KEY_LIVE_QUALITY = "live_quality"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun orientation(context: Context): OrientationMode =
        OrientationMode.fromKey(prefs(context).getString(KEY_ORIENTATION, null))

    fun setOrientation(context: Context, mode: OrientationMode) {
        prefs(context).edit().putString(KEY_ORIENTATION, mode.key).apply()
    }

    /** 主页（全局）方向，与播放页方向相互独立。 */
    fun homeOrientation(context: Context): OrientationMode =
        OrientationMode.fromKey(prefs(context).getString(KEY_HOME_ORIENTATION, null))

    fun setHomeOrientation(context: Context, mode: OrientationMode) {
        prefs(context).edit().putString(KEY_HOME_ORIENTATION, mode.key).apply()
    }

    /** 横屏进入播放页时是否自动进入全屏，默认关闭（避免覆盖平板分栏布局）。 */
    fun landscapeAutoFullscreen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LANDSCAPE_AUTO_FULLSCREEN, false)

    fun setLandscapeAutoFullscreen(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LANDSCAPE_AUTO_FULLSCREEN, enabled).apply()
    }

    /** 竖屏点全屏时是否按视频宽高比智能决定全屏方向，默认开启。 */
    fun smartFullscreenByVideoRatio(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMART_FULLSCREEN, true)

    fun setSmartFullscreenByVideoRatio(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMART_FULLSCREEN, enabled).apply()
    }

    /**
     * 计算进入全屏时应请求的屏幕方向。
     *
     * 仅当「播放页屏幕方向 = 跟随系统」、当前处于竖屏且智能全屏开关开启时按视频比例切换：
     * 竖屏视频保持竖屏全屏，横屏视频旋转为横屏全屏；其余情况尊重用户锁定设置。
     */
    fun resolveFullscreenOrientation(activity: Activity, portraitVideo: Boolean): Int {
        val mode = orientation(activity)
        if (mode != OrientationMode.FOLLOW_SYSTEM) return orientationValue(activity, mode)
        val portraitNow = activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        if (!portraitNow || !smartFullscreenByVideoRatio(activity)) return orientationValue(activity, mode)
        return if (portraitVideo) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun defaultQuality(context: Context): Int {
        val qn = prefs(context).getInt(KEY_DEFAULT_QUALITY, 0)
        return if (qn == 0 || QUALITY_OPTIONS.any { it.qn == qn }) qn else 0
    }

    fun setDefaultQuality(context: Context, qn: Int) {
        if (qn == 0 || QUALITY_OPTIONS.any { it.qn == qn }) {
            prefs(context).edit().putInt(KEY_DEFAULT_QUALITY, qn).apply()
        }
    }

    /** 设置里选了具体画质时优先生效；选「跟随上次选择」则回退到播放器原有的 last_quality。 */
    fun resolveQuality(context: Context, fallback: Int): Int {
        val preset = defaultQuality(context)
        return if (preset > 0) preset else fallback
    }

    /** 直播画质（qn），默认 10000 = 原画；实际可用档位以直播间接口返回为准。 */
    fun liveQuality(context: Context): Int {
        val qn = prefs(context).getInt(KEY_LIVE_QUALITY, 10000)
        return if (qn > 0) qn else 10000
    }

    fun setLiveQuality(context: Context, qn: Int) {
        if (qn > 0) prefs(context).edit().putInt(KEY_LIVE_QUALITY, qn).apply()
    }

    /**
     * 统一应用播放页方向，覆盖进入页面、全屏与退出全屏三个时机，
     * 避免全屏按钮把「锁定横屏/竖屏」设置覆盖掉。
     */
    fun applyPlaybackOrientation(activity: Activity) {
        activity.requestedOrientation = orientationValue(activity, orientation(activity))
    }

    /** 把主页方向设置应用到主页/全局页面，与播放页互不影响。 */
    fun applyHomeOrientation(activity: Activity) {
        activity.requestedOrientation = orientationValue(activity, homeOrientation(activity))
    }

    private fun orientationValue(activity: Activity, mode: OrientationMode): Int = when (mode) {
        OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        OrientationMode.FOLLOW_SYSTEM ->
            if (LayoutMode.isTablet) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
