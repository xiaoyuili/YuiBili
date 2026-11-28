package com.yuilittle.bili

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 平板端左侧固定导航栏（Navigation Rail，78dp 宽）：
 * - 顶部：粉色 YuiBili 品牌 Logo；
 * - 主导航区：首页 / 搜索 / 下载 / 我的收藏 / 历史记录（全部在右侧主舞台内嵌垂直平滑滚动切换，绝不跳走）；
 * - 底部锚定区：「我的」+ 夜间模式快捷切换图标（支持原圆形揭示转场）。
 */
internal class TabletRailNav(
    context: Context,
    private val onSelectDestination: (Int) -> Unit,
    private val onToggleTheme: (View) -> Unit
) : LinearLayout(context) {

    companion object {
        const val RAIL_WIDTH_DP = 78
        const val DEST_HOME = 0
        const val DEST_SEARCH = 1
        const val DEST_DYNAMIC = 2
        const val DEST_DOWNLOADS = 3
        const val DEST_FAVORITES = 4
        const val DEST_HISTORY = 5
        const val DEST_MESSAGE = 6
        const val DEST_PROFILE = 7
        const val DEST_COUNT = 8
    }

    private var selectedDest = DEST_HOME
    private val items = arrayOfNulls<RailItemView>(DEST_COUNT)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val c = Ds.colors
        setBackgroundColor(c.surface)
        setPadding(dp(8), dp(14), dp(8), dp(14))

        // 顶部品牌徽标
        val brand = Ds.brandMark(context, COLOR_ROSE, Color.WHITE).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "YuiBili 首页"
            Ds.bindPressFeedback(this, 0.90f)
            setOnClickListener { onSelectDestination(DEST_HOME) }
        }
        addView(brand, LayoutParams(dp(38), dp(38)))
        addView(View(context), LayoutParams(0, dp(16)))

        fun addNavItem(dest: Int, kind: Int, label: String, topDp: Int = 6) {
            val view = RailItemView(context, kind, label).apply {
                setOnClickListener { onSelectDestination(dest) }
            }
            items[dest] = view
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply {
                if (topDp > 0) topMargin = dp(topDp)
            })
        }

        addNavItem(DEST_HOME, RailItemView.KIND_HOME, "首页", topDp = 0)
        addNavItem(DEST_SEARCH, RailItemView.KIND_SEARCH, "搜索")
        addNavItem(DEST_DYNAMIC, RailItemView.KIND_DYNAMIC, "动态")
        addNavItem(DEST_DOWNLOADS, RailItemView.KIND_DOWNLOAD, "下载")
        addNavItem(DEST_FAVORITES, RailItemView.KIND_FAVORITE, "收藏")
        addNavItem(DEST_HISTORY, RailItemView.KIND_HISTORY, "历史")

        // 弹性留白：将「消息 / 我的」置于「夜间模式」正上方
        addView(View(context), LayoutParams(0, 0, 1f))

        addNavItem(DEST_MESSAGE, RailItemView.KIND_MESSAGE, "消息", topDp = 0)
        addNavItem(DEST_PROFILE, RailItemView.KIND_PROFILE, "我的")

        val themeBtn = FrameLayout(context).apply {
            background = Ds.card(context, c.surfaceRaised, c.lineSoft, Ds.Radius.control, Ds.hairline(context))
            isClickable = true
            isFocusable = true
            contentDescription = if (Theme.isDark) "切换浅色模式" else "切换夜间模式"
            Ds.bindPressFeedback(this, 0.90f)
            addView(ThemeQuickIcon(context, Theme.isDark), FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER))
            setOnClickListener { onToggleTheme(this) }
        }
        addView(themeBtn, LayoutParams(dp(44), dp(42)).apply {
            topMargin = dp(8)
        })

        setSelectedDestination(DEST_HOME)
    }

    fun setSelectedDestination(dest: Int) {
        selectedDest = dest
        for (i in items.indices) {
            items[i]?.setActive(i == dest)
        }
    }

    private class RailItemView(
        context: Context,
        private val kind: Int,
        labelText: String
    ) : LinearLayout(context) {

        companion object {
            const val KIND_HOME = 0
            const val KIND_SEARCH = 1
            const val KIND_DOWNLOAD = 2
            const val KIND_FAVORITE = 3
            const val KIND_HISTORY = 4
            const val KIND_PROFILE = 5
            const val KIND_DYNAMIC = 6
            const val KIND_MESSAGE = 7
        }

        private val iconView: RailIconCanvas
        private val labelView: TextView

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = labelText
            Ds.bindPressFeedback(this, 0.93f)

            iconView = RailIconCanvas(context, kind)
            addView(iconView, LayoutParams(dp(20), dp(20)))

            labelView = TextView(context).apply {
                text = labelText
                textSize = 10.5f
                typeface = Ds.body(false)
                includeFontPadding = false
                gravity = Gravity.CENTER
            }
            addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            setActive(false)
        }

        fun setActive(isActive: Boolean) {
            val c = Ds.colors
            background = if (isActive) {
                Ds.card(context, c.accentSoft, null, Ds.Radius.control, 0)
            } else {
                null
            }
            val color = if (isActive) c.accent else c.muted
            iconView.setTint(color, isActive)
            labelView.setTextColor(color)
            labelView.typeface = Ds.body(isActive)
        }
    }

    private class RailIconCanvas(context: Context, private val kind: Int) : View(context) {
        private val d = resources.displayMetrics.density
        private var tint = Ds.colors.muted
        private var bold = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun setTint(color: Int, active: Boolean) {
            tint = color
            bold = active
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = tint
            paint.strokeWidth = (if (bold) 1.9f else 1.6f) * d
            val cx = width * 0.5f
            val cy = height * 0.5f
            when (kind) {
                RailItemView.KIND_HOME -> {
                    val side = 7.0f * d
                    val roof = cy - 6.2f * d
                    val floor = cy + 6.2f * d
                    path.rewind()
                    path.moveTo(cx - side, cy - 0.5f * d)
                    path.lineTo(cx, roof)
                    path.lineTo(cx + side, cy - 0.5f * d)
                    path.moveTo(cx - 5.2f * d, cy + 1.0f * d)
                    path.lineTo(cx - 5.2f * d, floor - 1.4f * d)
                    path.quadTo(cx - 5.2f * d, floor, cx - 3.8f * d, floor)
                    path.lineTo(cx + 3.8f * d, floor)
                    path.quadTo(cx + 5.2f * d, floor, cx + 5.2f * d, floor - 1.4f * d)
                    path.lineTo(cx + 5.2f * d, cy + 1.0f * d)
                    canvas.drawPath(path, paint)
                    canvas.drawLine(cx, floor - 3.2f * d, cx, floor, paint)
                }
                RailItemView.KIND_SEARCH -> {
                    val r = 5.0f * d
                    val ccx = cx - 1.1f * d
                    val ccy = cy - 1.1f * d
                    canvas.drawCircle(ccx, ccy, r, paint)
                    canvas.drawLine(ccx + r * 0.72f, ccy + r * 0.72f, cx + 6.2f * d, cy + 6.2f * d, paint)
                }
                RailItemView.KIND_DOWNLOAD -> {
                    canvas.drawLine(cx, cy - 6.0f * d, cx, cy + 2.4f * d, paint)
                    path.rewind()
                    path.moveTo(cx - 3.5f * d, cy - 0.8f * d)
                    path.lineTo(cx, cy + 2.8f * d)
                    path.lineTo(cx + 3.5f * d, cy - 0.8f * d)
                    canvas.drawPath(path, paint)
                    path.rewind()
                    path.moveTo(cx - 5.8f * d, cy + 3.4f * d)
                    path.lineTo(cx - 5.8f * d, cy + 5.2f * d)
                    path.quadTo(cx - 5.8f * d, cy + 6.4f * d, cx - 4.5f * d, cy + 6.4f * d)
                    path.lineTo(cx + 4.5f * d, cy + 6.4f * d)
                    path.quadTo(cx + 5.8f * d, cy + 6.4f * d, cx + 5.8f * d, cy + 5.2f * d)
                    path.lineTo(cx + 5.8f * d, cy + 3.4f * d)
                    canvas.drawPath(path, paint)
                }
                RailItemView.KIND_FAVORITE -> {
                    // 五角星轮廓
                    val outer = 6.8f * d
                    val inner = 3.1f * d
                    path.rewind()
                    for (i in 0 until 10) {
                        val angle = Math.toRadians(-90.0 + i * 36.0)
                        val r = if (i % 2 == 0) outer else inner
                        val px = cx + (r * kotlin.math.cos(angle)).toFloat()
                        val py = cy + (r * kotlin.math.sin(angle)).toFloat()
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                RailItemView.KIND_HISTORY -> {
                    // 时钟表盘 + 指针
                    canvas.drawCircle(cx, cy, 6.2f * d, paint)
                    path.rewind()
                    path.moveTo(cx, cy - 3.4f * d)
                    path.lineTo(cx, cy + 0.2f * d)
                    path.lineTo(cx + 2.8f * d, cy + 1.8f * d)
                    canvas.drawPath(path, paint)
                }
                RailItemView.KIND_DYNAMIC -> {
                    // 闪电轮廓：与手机底栏「动态」图标保持一致
                    path.rewind()
                    path.moveTo(cx + 1.6f * d, cy - 6.6f * d)
                    path.lineTo(cx - 4.2f * d, cy + 1.2f * d)
                    path.lineTo(cx - 0.6f * d, cy + 1.2f * d)
                    path.lineTo(cx - 1.6f * d, cy + 6.6f * d)
                    path.lineTo(cx + 4.2f * d, cy - 1.2f * d)
                    path.lineTo(cx + 0.6f * d, cy - 1.2f * d)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                RailItemView.KIND_PROFILE -> {
                    canvas.drawCircle(cx, cy - 3.4f * d, 3.0f * d, paint)
                    path.rewind()
                    path.moveTo(cx - 6.0f * d, cy + 6.2f * d)
                    path.cubicTo(
                        cx - 5.4f * d, cy + 1.2f * d,
                        cx + 5.4f * d, cy + 1.2f * d,
                        cx + 6.0f * d, cy + 6.2f * d
                    )
                    canvas.drawPath(path, paint)
                }
                RailItemView.KIND_MESSAGE -> {
                    // 信封轮廓 + 封口折线
                    val left = cx - 6.4f * d
                    val right = cx + 6.4f * d
                    val top = cy - 4.6f * d
                    val bottom = cy + 4.6f * d
                    val corner = 1.4f * d
                    path.rewind()
                    path.moveTo(left + corner, top)
                    path.lineTo(right - corner, top)
                    path.quadTo(right, top, right, top + corner)
                    path.lineTo(right, bottom - corner)
                    path.quadTo(right, bottom, right - corner, bottom)
                    path.lineTo(left + corner, bottom)
                    path.quadTo(left, bottom, left, bottom - corner)
                    path.lineTo(left, top + corner)
                    path.quadTo(left, top, left + corner, top)
                    path.close()
                    canvas.drawPath(path, paint)
                    path.rewind()
                    path.moveTo(left + 0.4f * d, top + 0.9f * d)
                    path.lineTo(cx, cy + 1.2f * d)
                    path.lineTo(right - 0.4f * d, top + 0.9f * d)
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    private class ThemeQuickIcon(context: Context, private val dark: Boolean) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) COLOR_ROSE else Ds.colors.muted
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val cx = width * 0.5f
            val cy = height * 0.5f
            if (dark) {
                canvas.drawCircle(cx, cy, 3.4f * d, paint)
                for (i in 0 until 8) {
                    val rad = Math.toRadians(i * 45.0)
                    val c = kotlin.math.cos(rad).toFloat()
                    val s = kotlin.math.sin(rad).toFloat()
                    canvas.drawLine(cx + c * 5.2f * d, cy + s * 5.2f * d, cx + c * 7.0f * d, cy + s * 7.0f * d, paint)
                }
            } else {
                val r = 6.0f * d
                path.rewind()
                path.addArc(RectF(cx - r, cy - r, cx + r, cy + r), 50f, 235f)
                path.cubicTo(
                    cx - 1.5f * d, cy - 2.1f * d,
                    cx + 1.9f * d, cy + 2.1f * d,
                    cx + r * kotlin.math.cos(Math.toRadians(50.0)).toFloat(),
                    cy + r * kotlin.math.sin(Math.toRadians(50.0)).toFloat()
                )
                canvas.drawPath(path, paint)
            }
        }
    }
}
