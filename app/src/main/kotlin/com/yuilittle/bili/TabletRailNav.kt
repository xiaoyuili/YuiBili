package com.yuilittle.bili

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 平板端左侧固定导航栏（Navigation Rail，78dp 宽）：
 * - 顶部：粉色 YuiBili 品牌 Logo；
 * - 主导航区：首页 / 下载 / 收藏 / 历史记录；
 * - 底部锚定区：日夜切换与「我的」。
 */
internal class TabletRailNav(
    context: Context,
    private val onSelectDestination: (Int) -> Unit,
    private val onToggleTheme: (View, Boolean) -> Unit
) : LinearLayout(context) {

    companion object {
        const val RAIL_WIDTH_DP = 78
        const val DEST_HOME = 0
        const val DEST_SEARCH = 1
        const val DEST_DOWNLOADS = 2
        const val DEST_FAVORITES = 3
        const val DEST_HISTORY = 4
        const val DEST_PROFILE = 5
        const val DEST_HISTORY_SEARCH = 6
        const val DEST_FAVORITES_SEARCH = 7
        const val DEST_COUNT = 8
    }

    private var selectedDest = DEST_HOME
    private val items = arrayOfNulls<RailItemView>(DEST_COUNT)
    private val themeButton = HorizonThemeToggle(context, onToggleTheme)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val c = Ds.colors
        setBackgroundColor(c.bg)
        setPadding(dp(8), dp(14), dp(8), dp(14))

        val primary = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val brand = Ds.brandMark(context, COLOR_ROSE, Color.WHITE).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "YuiBili 首页"
            Ds.bindPressFeedback(this, 0.90f)
            setOnClickListener { onSelectDestination(DEST_HOME) }
        }
        primary.addView(brand, LayoutParams(dp(38), dp(38)))
        primary.addView(View(context), LayoutParams(0, dp(16)))

        fun addNavItem(dest: Int, kind: Int, label: String, topDp: Int = 6, host: LinearLayout = this) {
            val view = RailItemView(context, kind, label).apply {
                setOnClickListener { onSelectDestination(dest) }
            }
            items[dest] = view
            host.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply {
                if (topDp > 0) topMargin = dp(topDp)
            })
        }

        addNavItem(DEST_HOME, RailItemView.KIND_HOME, "首页", topDp = 0, host = primary)
        addNavItem(DEST_DOWNLOADS, RailItemView.KIND_DOWNLOAD, "下载", host = primary)
        addNavItem(DEST_FAVORITES, RailItemView.KIND_FAVORITE, "收藏", host = primary)
        addNavItem(DEST_HISTORY, RailItemView.KIND_HISTORY, "历史", host = primary)

        addView(android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(primary)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(themeButton, LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(8) })
        addNavItem(DEST_PROFILE, RailItemView.KIND_PROFILE, "我的", topDp = 0)

        setSelectedDestination(DEST_HOME)
    }

    fun setSelectedDestination(dest: Int) {
        selectedDest = when (dest) {
            DEST_SEARCH -> DEST_HOME
            DEST_HISTORY_SEARCH -> DEST_HISTORY
            DEST_FAVORITES_SEARCH -> DEST_FAVORITES
            else -> dest
        }
        for (i in items.indices) {
            items[i]?.setActive(i == selectedDest)
        }
    }

    fun setThemeSymbolsHidden(hidden: Boolean) {
        themeButton.symbolsHidden = hidden
        themeButton.invalidate()
    }

    fun drawThemeTransition(canvas: Canvas, root: View, fromDark: Boolean, progress: Float) {
        val origin = IntArray(2)
        val location = IntArray(2)
        root.getLocationOnScreen(origin)
        themeButton.getLocationOnScreen(location)
        val save = canvas.save()
        canvas.translate((location[0] - origin[0]).toFloat(), (location[1] - origin[1]).toFloat())
        themeButton.drawSymbols(canvas, fromDark, progress)
        canvas.restoreToCount(save)
    }

    fun setThemeTransitionLocked(locked: Boolean) { themeButton.isEnabled = !locked }

    private class RailItemView(
        context: Context,
        private val kind: Int,
        labelText: String
    ) : LinearLayout(context) {

        companion object {
            const val KIND_HOME = 0
            const val KIND_DOWNLOAD = 2
            const val KIND_FAVORITE = 3
            const val KIND_HISTORY = 4
            const val KIND_PROFILE = 5
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
            }
        }
    }

    private class HorizonThemeToggle(context: Context, private val onCommit: (View, Boolean) -> Unit) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        var symbolsHidden = false

        init {
            isFocusable = true
            contentDescription = if (Theme.isDark) "切换日间模式" else "切换夜间模式"
            setOnClickListener {
                if (isAttachedToWindow) onCommit(this, !Theme.isDark)
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (!symbolsHidden) drawSymbols(canvas, Theme.isDark, 0f)
        }

        fun drawSymbols(canvas: Canvas, fromDark: Boolean, progress: Float) {
            val from = Ds.palette(fromDark)
            val to = Ds.palette(!fromDark)
            val cx = width * 0.5f
            val horizon = height * 0.73f
            val saved = canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), horizon - d)
            paint.color = themeBlendColor(from.accent, to.accent, progress)
            val outgoing = horizonOrbit(progress, incoming = false)
            val incoming = horizonOrbit(progress, incoming = true)
            drawBody(canvas, fromDark, cx + outgoing.first * 18f * d, horizon + outgoing.second * 14f * d)
            drawBody(canvas, !fromDark, cx + incoming.first * 18f * d, horizon + incoming.second * 14f * d)
            canvas.restoreToCount(saved)
            paint.color = themeBlendColor(from.muted, to.muted, progress)
            canvas.drawLine(cx - 15f * d, horizon, cx + 15f * d, horizon, paint)
        }

        private fun drawBody(canvas: Canvas, moon: Boolean, cx: Float, cy: Float) {
            if (moon) {
                val r = 6f * d
                path.rewind()
                path.addArc(cx - r, cy - r, cx + r, cy + r, 50f, 235f)
                path.cubicTo(cx - 2f * d, cy - 2f * d, cx + 2f * d, cy + 2f * d,
                    cx + r * 0.643f, cy + r * 0.766f)
                path.close()
                canvas.drawPath(path, paint)
            } else {
                canvas.drawCircle(cx, cy, 3.4f * d, paint)
                for (i in 0 until 8) {
                    val rad = Math.toRadians(i * 45.0)
                    val x = kotlin.math.cos(rad).toFloat()
                    val y = kotlin.math.sin(rad).toFloat()
                    canvas.drawLine(cx + x * 5.2f * d, cy + y * 5.2f * d,
                        cx + x * 7f * d, cy + y * 7f * d, paint)
                }
            }
        }
    }
}

internal fun themeBlendColor(from: Int, to: Int, progress: Float): Int {
    val p = progress.coerceIn(0f, 1f)
    var result = 0
    for (shift in intArrayOf(24, 16, 8, 0)) {
        val start = (from ushr shift) and 255
        val end = (to ushr shift) and 255
        result = result or ((start + (end - start) * p).toInt() shl shift)
    }
    return result
}

internal fun horizonOrbit(progress: Float, incoming: Boolean): Pair<Float, Float> {
    val angle = -Math.PI / 2 + Math.PI * progress.coerceIn(0f, 1f) + if (incoming) Math.PI else 0.0
    return kotlin.math.cos(angle).toFloat() to kotlin.math.sin(angle).toFloat()
}
