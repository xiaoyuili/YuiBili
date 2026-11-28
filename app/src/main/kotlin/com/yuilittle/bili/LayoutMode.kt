package com.yuilittle.bili

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 全局「外观适配」模式管理：
 * - 默认为手机端（PHONE），确保不破坏既有手机体验；
 * - 用户在「我的 → 外观 → 外观适配」右侧下拉菜单切换为平板端（TABLET）后，
 *   全应用启用平板专属交互（左侧边栏一体化多页工作台、4列×3行首页网格、横屏双栏播放、双栏收藏/历史/下载/音乐台与统一弹窗宽度治理）。
 */
internal object LayoutMode {

    enum class Mode(val key: String, val label: String) {
        PHONE("phone", "手机端"),
        TABLET("tablet", "平板端");

        companion object {
            fun fromKey(raw: String?): Mode =
                entries.firstOrNull { it.key == raw } ?: PHONE
        }
    }

    private const val PREFS = Theme.PREFS
    private const val KEY_MODE = "layout_mode"

    @Volatile
    var current: Mode = Mode.PHONE
        private set

    val isTablet: Boolean
        get() = current == Mode.TABLET

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        current = Mode.fromKey(prefs.getString(KEY_MODE, Mode.PHONE.key))
    }

    fun setMode(context: Context, mode: Mode): Boolean {
        if (current == mode) return false
        current = mode
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.key).apply()
        return true
    }

    /** 屏幕当前是否足够宽以展开左右双栏（平板模式下横屏或宽屏 >= 600dp 生效）。 */
    fun isWideScreen(context: Context): Boolean {
        if (!isTablet) return false
        val dm = context.resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return widthDp >= 600f
    }

    enum class ScreenDirection(val key: String, val label: String, val orientation: Int) {
        SYSTEM("system", "跟随系统", ActivityInfo.SCREEN_ORIENTATION_USER),
        PORTRAIT("portrait", "锁定竖屏", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        LANDSCAPE("landscape", "锁定横屏", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    }

    fun screenDirection(context: Context): ScreenDirection {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("screen_direction", null)
        return ScreenDirection.entries.firstOrNull { it.key == key } ?: ScreenDirection.SYSTEM
    }

    fun setScreenDirection(activity: Activity, direction: ScreenDirection) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("screen_direction", direction.key).apply()
        applyScreenOrientation(activity)
    }

    internal fun playbackOrientation(tablet: Boolean, fullscreen: Boolean, direction: ScreenDirection): Int =
        if (!tablet && fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else direction.orientation

    fun applyScreenOrientation(activity: Activity, fullscreen: Boolean = false) {
        val target = playbackOrientation(isTablet, fullscreen, screenDirection(activity))
        if (activity.requestedOrientation != target) activity.requestedOrientation = target
    }

    /**
     * 计算首页/搜索视频网格列数：
     * - 手机端恒定 2 列；
     * - 平板端横屏（可用宽 >= 720dp，如 1920px@336dpi 约 830dp）直接 4 列，竖屏 3 列。
     */
    fun gridColumns(context: Context, availableWidthPx: Int): Int {
        if (!isTablet) return 2
        val widthDp = availableWidthPx / context.resources.displayMetrics.density
        return when {
            widthDp >= 720f -> 4
            widthDp >= 520f -> 3
            else -> 2
        }
    }

    /**
     * 计算列表/内容区在平板宽屏下的安全最大宽度（px）：
     * 严格取 `min(availableWidth, maxDp)`，避免在带左侧导航栏的容器内超出父容器导致左右被裁切！
     */
    fun centeredMaxWidthPx(
        context: Context,
        maxDp: Int = 920,
        parentAvailablePx: Int = context.resources.displayMetrics.widthPixels
    ): Int {
        if (!isTablet) return parentAvailablePx
        return minOf(parentAvailablePx, context.dp(maxDp))
    }

    /**
     * 平板宽屏居中容器：手机端原样返回；平板端用 `MATCH_PARENT` 配合外层左右对称留白或限宽，
     * 绝不把固定超出父容器的像素塞给子 View，彻底杜绝左/右边缘裁切。
     */
    fun wrapCenteredForTablet(
        context: Context,
        content: View,
        maxDp: Int = 920,
        horizontalPaddingDp: Int = 20,
        parentAvailablePx: Int = context.resources.displayMetrics.widthPixels
    ): View {
        if (!isTablet) return content
        val host = FrameLayout(context)
        val safeW = (parentAvailablePx - context.dp(horizontalPaddingDp * 2)).coerceAtLeast(context.dp(320))
        val targetW = minOf(safeW, context.dp(maxDp))
        host.addView(
            content,
            FrameLayout.LayoutParams(
                targetW,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL
            )
        )
        return host
    }

    /**
     * 统一规范全应用 Dialog 宽度：
     * - 手机端按 [phoneWidthFraction] 或减边距宽度；
     * - 平板端限制在舒适的 `maxDp`（普通弹窗 420dp / 底部表单 520dp），防止拉成全屏长条。
     */
    fun dialogWidthPx(
        context: Context,
        maxTabletDp: Int = 440,
        phoneMarginDp: Int = 48
    ): Int {
        val screenW = context.resources.displayMetrics.widthPixels
        val phoneW = (screenW - context.dp(phoneMarginDp)).coerceAtLeast(context.dp(260))
        return if (isTablet) minOf(phoneW, context.dp(maxTabletDp)) else phoneW
    }

    fun applyDialogWidth(
        dialog: Dialog,
        context: Context,
        maxTabletDp: Int = 440,
        phoneMarginDp: Int = 48,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT
    ) {
        dialog.window?.setLayout(dialogWidthPx(context, maxTabletDp, phoneMarginDp), height)
    }

    /** 显示锚定在指定控件下方的轻量下拉选择面板（供「外观适配」手机端/平板端切换使用）。 */
    fun showModeDropdown(
        context: Context,
        anchor: View,
        onSelected: (Mode) -> Unit
    ): PopupWindow {
        val c = Ds.colors
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(
                context,
                fill = c.surface,
                border = c.line,
                radiusDp = Ds.Radius.control,
                borderPx = Ds.hairline(context)
            )
            setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            elevation = context.dp(8).toFloat()
        }
        val popup = PopupWindow(
            panel,
            context.dp(156),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = context.dp(8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        Mode.entries.forEach { mode ->
            val selected = mode == current
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
                if (selected) {
                    background = Ds.card(context, c.accentSoft, null, Ds.Radius.chip, 0)
                }
                isClickable = true
                isFocusable = true
                Ds.bindPressFeedback(this, 0.96f)
                setOnClickListener {
                    popup.dismiss()
                    onSelected(mode)
                }
            }
            row.addView(TextView(context).apply {
                text = mode.label
                textSize = 14f
                typeface = Ds.body(selected)
                setTextColor(if (selected) c.accent else c.text)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) {
                row.addView(
                    Ds.checkIcon(context, c.accent, 1.9f),
                    LinearLayout.LayoutParams(context.dp(15), context.dp(15))
                )
            }
            panel.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val xOff = anchor.width - context.dp(156)
        popup.showAsDropDown(anchor, xOff, context.dp(6))
        return popup
    }

    /** 「外观适配」左侧设备图标（平板轮廓）与下拉小箭头，纯矢量绘制。 */
    fun deviceAdaptIcon(context: Context, color: Int): View = object : View(context) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(w * 0.10f, h * 0.16f, w * 0.86f, h * 0.78f)
            canvas.drawRoundRect(rect, 2.6f * d, 2.6f * d, p)
            canvas.drawLine(w * 0.36f, h * 0.88f, w * 0.60f, h * 0.88f, p)
            canvas.drawLine(w * 0.32f, h * 0.16f, w * 0.32f, h * 0.78f, p)
        }
    }

    fun dropdownChevronIcon(context: Context, color: Int): View = object : View(context) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            path.rewind()
            path.moveTo(w * 0.22f, h * 0.36f)
            path.lineTo(w * 0.50f, h * 0.66f)
            path.lineTo(w * 0.78f, h * 0.36f)
            canvas.drawPath(path, p)
        }
    }
}
