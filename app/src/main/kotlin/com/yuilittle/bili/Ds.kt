package com.yuilittle.bili

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * YuiBili 统一设计系统（极简柔和现代风）：
 * - 单一品牌强调色（柔和玫瑰粉）+ 中性暖白 / 碳墨阶梯底色，绝不堆砌杂色。
 * - 统一圆角体系（小标签 8dp / 控件 12dp / 卡片 16dp / 弹窗 22dp / 胶囊 999dp）。
 * - 零实时模糊、零重投影，仅靠轻盈的底色层级、微描边和硬件加速的圆角裁剪构建质感。
 * - 全套物理弹簧动效（按压回弹、平滑入场、指示器跟随）。
 */
internal object Ds {

    data class Palette(
        val bg: Int,
        val surface: Int,
        val surfaceRaised: Int,
        val text: Int,
        val muted: Int,
        val subtle: Int,
        val accent: Int,
        val accentPressed: Int,
        val accentSoft: Int,
        val cold: Int,
        val line: Int,
        val lineSoft: Int,
        val cover: Int,
        val onAccent: Int,
        val success: Int,
        val warning: Int,
        val danger: Int,
        val dangerSoft: Int,
        val scrim: Int,
        val paperShadow: Int,
        val isDark: Boolean,
    )

    // 深色：柔和碳黑底 + 柔雾玫瑰粉（低饱和不过刺眼）
    private val dark = Palette(
        bg = 0xFF101114.toInt(),
        surface = 0xFF181A1F.toInt(),
        surfaceRaised = 0xFF21242B.toInt(),
        text = 0xFFF2F3F5.toInt(),
        muted = 0xFF9EA3AE.toInt(),
        subtle = 0xFF6B707B.toInt(),
        accent = 0xFFF27D9E.toInt(),
        accentPressed = 0xFFE06488.toInt(),
        accentSoft = 0x26F27D9E,
        cold = 0xFF7EA6E8.toInt(),
        line = 0xFF2D313A.toInt(),
        lineSoft = 0xFF23262E.toInt(),
        cover = 0xFF1D2026.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        success = 0xFF70C493.toInt(),
        warning = 0xFFE5B464.toInt(),
        danger = 0xFFEB6F79.toInt(),
        dangerSoft = 0x24EB6F79,
        scrim = 0xB8000000.toInt(),
        paperShadow = 0xFF000000.toInt(),
        isDark = true,
    )

    // 浅色：纯净暖白底 + 柔和浅灰卡片 + 清雅玫瑰粉
    private val light = Palette(
        bg = 0xFFFAFAFC.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceRaised = 0xFFF2F3F7.toInt(),
        text = 0xFF18191C.toInt(),
        muted = 0xFF6B707A.toInt(),
        subtle = 0xFF9BA0AA.toInt(),
        accent = 0xFFE25582.toInt(),
        accentPressed = 0xFFC8416C.toInt(),
        accentSoft = 0x18E25582,
        cold = 0xFF4B7EC9.toInt(),
        line = 0xFFE3E5EC.toInt(),
        lineSoft = 0xFFEEEFF4.toInt(),
        cover = 0xFFECEEF3.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        success = 0xFF2D8A56.toInt(),
        warning = 0xFFB0781B.toInt(),
        danger = 0xFFD94A56.toInt(),
        dangerSoft = 0x1AD94A56,
        scrim = 0x80000000.toInt(),
        paperShadow = 0xFF000000.toInt(),
        isDark = false,
    )

    val colors: Palette
        get() = palette(Theme.isDark)

    fun palette(darkMode: Boolean): Palette = if (darkMode) dark else light

    object Space {
        const val xs = 4
        const val sm = 8
        const val md = 12
        const val lg = 16
        const val xl = 24
        const val xxl = 32
        const val huge = 48
    }

    object Radius {
        const val square = 0
        const val hairline = 8
        const val chip = 10
        const val control = 12
        const val card = 16
        const val dialog = 22
        const val round = 999
    }

    private val sans: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    private val sansMedium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val sansBold: Typeface by lazy { Typeface.create("sans-serif", Typeface.BOLD) }

    /** 标题层级：清晰现代的无衬线粗体，确保中英文混排视觉一致。 */
    fun display(bold: Boolean = true): Typeface = if (bold) sansBold else sansMedium

    /** 阅读层级：正文字体，medium 用于标签与次级标题。 */
    fun body(bold: Boolean = false): Typeface = if (bold) sansMedium else sans

    fun hairline(context: Context): Int =
        (1f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    fun dp(context: Context, dp: Int): Float = dp * context.resources.displayMetrics.density

    /** 统一柔和卡片与表面容器：默认 14dp 圆角，可选微描边。 */
    fun plate(
        fill: Int,
        border: Int? = null,
        borderWidth: Int = 1,
        radiusPx: Float = 28f
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radiusPx
        if (border != null && borderWidth > 0) setStroke(borderWidth, border)
    }

    /** 按 dp 构造圆角矩形。 */
    fun card(
        context: Context,
        fill: Int = colors.surface,
        border: Int? = colors.lineSoft,
        radiusDp: Int = Radius.card,
        borderPx: Int = hairline(context)
    ): GradientDrawable = plate(fill, border, borderPx, dp(context, radiusDp))

    /** 胶囊圆角背景。 */
    fun pill(
        context: Context,
        fill: Int,
        border: Int? = null,
        borderPx: Int = hairline(context)
    ): GradientDrawable = plate(fill, border, borderPx, dp(context, Radius.round))

    /** 圆形控件背景（开关滑块、头像底、播控圆钮）。 */
    fun circle(fill: Int, border: Int? = null, borderWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (border != null && borderWidth > 0) setStroke(borderWidth, border)
        }

    /** 硬件加速的圆角裁剪，零额外内存与 CPU 离屏开销。 */
    fun clipRounded(view: View, radiusDp: Int) {
        val r = dp(view.context, radiusDp)
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
    }

    /** 轻按物理回弹效果，直接绑定在可点击卡片/行/按钮上，不吞点击事件。 */
    fun bindPressFeedback(view: View, depth: Float = 0.965f) {
        view.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> DsSpring.pressLift(v, true, depth)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> DsSpring.pressLift(v, false, depth)
            }
            false
        }
    }

    /**
     * 统一品牌“丝带 Y-Play”几何绘法（供顶栏徽标 brandMark 与加载器 BrandSpinnerView 共用），
     * 按 108 网格坐标缩放，保证与桌面应用图标一脉相承。
     */
    fun drawBrandRibbonEmblem(
        canvas: Canvas,
        u: Float,
        emblemColor: Int,
        fillPaint: Paint,
        pathA: Path,
        pathB: Path,
        pathC: Path,
        pathD: Path,
    ) {
        val alphaBack = (Color.alpha(emblemColor) * 0.72f).toInt().coerceIn(0, 255)
        val backRibbonColor = Color.argb(
            alphaBack,
            Color.red(emblemColor),
            Color.green(emblemColor),
            Color.blue(emblemColor),
        )
        val wingAlpha = (Color.alpha(emblemColor) * 0.84f).toInt().coerceIn(0, 255)
        val wingColor = Color.argb(
            wingAlpha,
            Color.red(emblemColor),
            Color.green(emblemColor),
            Color.blue(emblemColor),
        )

        // 1. 底层右斜丝带（贯穿右上至左下收尾）
        pathA.rewind()
        pathA.moveTo(64.2f * u, 26.6f * u)
        pathA.cubicTo(65.4f * u, 24.7f * u, 67.8f * u, 23.8f * u, 69.9f * u, 24.5f * u)
        pathA.lineTo(80.2f * u, 27.8f * u)
        pathA.cubicTo(83.2f * u, 28.8f * u, 84.4f * u, 32.4f * u, 82.6f * u, 35.0f * u)
        pathA.lineTo(52.6f * u, 79.0f * u)
        pathA.cubicTo(50.3f * u, 82.4f * u, 45.3f * u, 82.3f * u, 43.2f * u, 78.8f * u)
        pathA.lineTo(39.8f * u, 73.3f * u)
        pathA.cubicTo(38.7f * u, 71.5f * u, 38.9f * u, 69.3f * u, 40.1f * u, 67.6f * u)
        pathA.close()
        fillPaint.color = backRibbonColor
        canvas.drawPath(pathA, fillPaint)

        // 2. 右侧柔顺过渡的 Play 翼角
        pathB.rewind()
        pathB.moveTo(56.6f * u, 45.8f * u)
        pathB.lineTo(82.2f * u, 44.8f * u)
        pathB.cubicTo(86.2f * u, 44.7f * u, 88.6f * u, 49.4f * u, 86.2f * u, 52.8f * u)
        pathB.lineTo(76.0f * u, 66.8f * u)
        pathB.cubicTo(73.6f * u, 70.0f * u, 69.0f * u, 70.5f * u, 66.2f * u, 67.8f * u)
        pathB.lineTo(55.4f * u, 57.2f * u)
        pathB.cubicTo(53.2f * u, 55.0f * u, 53.7f * u, 48.0f * u, 56.6f * u, 45.8f * u)
        pathB.close()
        fillPaint.color = wingColor
        canvas.drawPath(pathB, fillPaint)

        // 3. 顶层左斜丝带主臂
        pathC.rewind()
        pathC.moveTo(26.6f * u, 26.4f * u)
        pathC.cubicTo(25.2f * u, 23.8f * u, 27.1f * u, 20.7f * u, 30.0f * u, 20.7f * u)
        pathC.lineTo(40.4f * u, 20.7f * u)
        pathC.cubicTo(42.6f * u, 20.7f * u, 44.7f * u, 22.0f * u, 45.6f * u, 24.0f * u)
        pathC.lineTo(58.2f * u, 50.0f * u)
        pathC.cubicTo(59.0f * u, 51.7f * u, 58.9f * u, 53.6f * u, 57.8f * u, 55.2f * u)
        pathC.lineTo(46.2f * u, 72.8f * u)
        pathC.cubicTo(44.3f * u, 75.6f * u, 40.0f * u, 75.3f * u, 38.6f * u, 72.2f * u)
        pathC.lineTo(35.6f * u, 65.9f * u)
        pathC.cubicTo(34.9f * u, 64.4f * u, 35.1f * u, 62.5f * u, 36.1f * u, 61.0f * u)
        pathC.lineTo(43.2f * u, 50.8f * u)
        pathC.close()
        fillPaint.color = emblemColor
        canvas.drawPath(pathC, fillPaint)

        // 4. 中心提亮播放角标
        pathD.rewind()
        pathD.moveTo(65.2f * u, 45.4f * u)
        pathD.lineTo(79.2f * u, 53.2f * u)
        pathD.cubicTo(81.4f * u, 54.4f * u, 81.4f * u, 57.6f * u, 79.2f * u, 58.8f * u)
        pathD.lineTo(65.2f * u, 66.6f * u)
        pathD.cubicTo(63.0f * u, 67.8f * u, 60.4f * u, 66.2f * u, 60.4f * u, 63.7f * u)
        pathD.lineTo(60.4f * u, 48.3f * u)
        pathD.cubicTo(60.4f * u, 45.8f * u, 63.0f * u, 44.2f * u, 65.2f * u, 45.4f * u)
        pathD.close()
        fillPaint.color = emblemColor
        canvas.drawPath(pathD, fillPaint)
    }

    /** 统一品牌丝带 Y-Play 迷你徽标：用于首页顶栏、页面标头，和桌面图标、加载器一脉相承。 */
    fun brandMark(
        context: Context,
        fillColor: Int = colors.accent,
        emblemColor: Int = Color.WHITE
    ): View = object : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val p1 = Path()
        private val p2 = Path()
        private val p3 = Path()
        private val p4 = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = minOf(width, height).toFloat()
            if (s <= 0f) return
            val u = s / 108f
            val save = canvas.save()
            canvas.translate((width - s) / 2f, (height - s) / 2f)

            bgPaint.color = fillColor
            rect.set(6f * u, 6f * u, 102f * u, 102f * u)
            canvas.drawRoundRect(rect, 28f * u, 28f * u, bgPaint)

            drawBrandRibbonEmblem(canvas, u, emblemColor, bgPaint, p1, p2, p3, p4)

            canvas.restoreToCount(save)
        }
    }

    /** 统一极简返回图标（圆角折线）。 */
    fun backIcon(context: Context, color: Int = colors.text, onClick: () -> Unit): View =
        object : View(context) {
            private val density = resources.displayMetrics.density
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = 2.0f * density
            }
            private val path = Path()

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = color
                val cx = width / 2f
                val cy = height / 2f
                val arm = 6.5f * density
                path.rewind()
                path.moveTo(cx + arm * 0.45f, cy - arm)
                path.lineTo(cx - arm * 0.45f, cy)
                path.lineTo(cx + arm * 0.45f, cy + arm)
                canvas.drawPath(path, paint)
            }
        }.apply {
            contentDescription = "返回"
            isClickable = true
            isFocusable = true
            bindPressFeedback(this, 0.90f)
            setOnClickListener { onClick() }
        }

    /** 统一极简搜索图标（单线放大镜）。 */
    fun searchIcon(context: Context, color: Int = colors.muted, strokeDp: Float = 1.8f): View =
        object : View(context) {
            private val density = resources.displayMetrics.density
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = strokeDp * density
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = color
                val r = minOf(width, height) * 0.31f
                val cx = width * 0.44f
                val cy = height * 0.44f
                canvas.drawCircle(cx, cy, r, paint)
                val offset = r * 0.72f
                canvas.drawLine(
                    cx + offset, cy + offset,
                    width * 0.88f, height * 0.88f,
                    paint
                )
            }
        }

    /** 统一圆角右箭头图标（替代生硬的字符 ›）。 */
    fun chevronRightIcon(context: Context, color: Int = colors.subtle, strokeDp: Float = 1.8f): View =
        object : View(context) {
            private val density = resources.displayMetrics.density
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = strokeDp * density
            }
            private val path = Path()

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = color
                val cx = width / 2f
                val cy = height / 2f
                val arm = minOf(width, height) * 0.24f
                path.rewind()
                path.moveTo(cx - arm * 0.45f, cy - arm)
                path.lineTo(cx + arm * 0.55f, cy)
                path.lineTo(cx - arm * 0.45f, cy + arm)
                canvas.drawPath(path, paint)
            }
        }

    /** 统一圆角清除/关闭叉号图标（替代字符 ×）。 */
    fun closeIcon(context: Context, color: Int = colors.muted, strokeDp: Float = 1.75f): View =
        object : View(context) {
            private val density = resources.displayMetrics.density
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = strokeDp * density
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = color
                val cx = width / 2f
                val cy = height / 2f
                val arm = minOf(width, height) * 0.24f
                canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, paint)
                canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, paint)
            }
        }

    /** 回到顶部图标：顶部横杠 + 向上箭头，与全站单线图标一致。 */
    fun scrollTopIcon(context: Context, color: Int = colors.text, strokeDp: Float = 1.85f): View =
        object : View(context) {
            private val density = resources.displayMetrics.density
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = strokeDp * density
            }
            private val path = Path()

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = color
                val w = width.toFloat()
                val h = height.toFloat()
                val cx = w * 0.5f
                // 顶杠
                canvas.drawLine(w * 0.24f, h * 0.20f, w * 0.76f, h * 0.20f, paint)
                // 竖干
                canvas.drawLine(cx, h * 0.82f, cx, h * 0.36f, paint)
                // 箭头
                path.rewind()
                path.moveTo(w * 0.26f, h * 0.56f)
                path.lineTo(cx, h * 0.34f)
                path.lineTo(w * 0.74f, h * 0.56f)
                canvas.drawPath(path, paint)
            }
        }

    /** 加号图标（创建/新增入口）。 */
    fun plusIcon(context: Context, color: Int = colors.text, strokeDp: Float = 1.9f): View =
        object : View(context) {
            private val density = resources.displayMetrics.density
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = strokeDp * density
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = color
                val cx = width / 2f
                val cy = height / 2f
                val arm = minOf(width, height) * 0.26f
                canvas.drawLine(cx - arm, cy, cx + arm, cy, paint)
                canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
            }
        }

    /** 统一圆角向下/向上切换箭头图标（支持颜色切换与旋转）。 */
    fun toggleChevron(
        context: Context,
        color: Int = colors.muted,
        strokeDp: Float = 1.65f,
        expanded: Boolean = false
    ): View = object : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = strokeDp * density
        }
        private val path = Path()

        init {
            rotation = if (expanded) 180f else 0f
            tag = color
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = (tag as? Int) ?: color
            val cx = width / 2f
            val cy = height / 2f
            val arm = minOf(width, height) * 0.28f
            path.rewind()
            path.moveTo(cx - arm, cy - arm * 0.42f)
            path.lineTo(cx, cy + arm * 0.55f)
            path.lineTo(cx + arm, cy - arm * 0.42f)
            canvas.drawPath(path, paint)
        }
    }

    /** 统一极简三横线筛选图标。 */
    fun filterIcon(context: Context, color: Int = colors.muted, strokeDp: Float = 1.65f): View =
        object : View(context) {
            private val density = resources.displayMetrics.density
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = strokeDp * density
            }

            init {
                tag = color
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = (tag as? Int) ?: color
                val cy1 = height * 0.28f
                val cy2 = height * 0.52f
                val cy3 = height * 0.76f
                canvas.drawLine(width * 0.16f, cy1, width * 0.84f, cy1, paint)
                canvas.drawLine(width * 0.28f, cy2, width * 0.72f, cy2, paint)
                canvas.drawLine(width * 0.40f, cy3, width * 0.60f, cy3, paint)
            }
        }

    /** 统一极简对勾图标。 */
    fun checkIcon(context: Context, color: Int = colors.accent, strokeDp: Float = 2.0f): View =
        object : View(context) {
            private val density = resources.displayMetrics.density
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = strokeDp * density
            }
            private val path = Path()

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = color
                path.rewind()
                path.moveTo(width * 0.22f, height * 0.52f)
                path.lineTo(width * 0.42f, height * 0.72f)
                path.lineTo(width * 0.78f, height * 0.30f)
                canvas.drawPath(path, paint)
            }
        }

    /** 合集排序图标：默认 / 从旧到新 / 从新到旧三态共用一套极简线性画法。 */
    fun sortOrderIcon(
        context: Context,
        order: DownloadManager.SeasonSortOrder,
        color: Int = colors.muted,
        strokeDp: Float = 1.65f
    ): View = SortOrderIconView(context, order, color, strokeDp)

    /** 播放模式图标：顺序播放 / 单集循环 / 随机播放。 */
    fun playModeIcon(
        context: Context,
        mode: DownloadManager.LocalPlayMode,
        color: Int = colors.muted,
        strokeDp: Float = 1.65f
    ): View = PlayModeIconView(context, mode, color, strokeDp)

    fun applySystemBars(activity: Activity, lightContent: Boolean = false) {
        val window = activity.window
        val c = colors
        window.statusBarColor = if (lightContent) Color.BLACK else c.bg
        window.navigationBarColor = c.bg
        @Suppress("DEPRECATION")
        var flags = window.decorView.systemUiVisibility and
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
            0x00000010.inv()
        if (!c.isDark) {
            if (!lightContent) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            flags = flags or 0x00000010
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }
}

/** 合集排序图标（默认 / 从旧到新 / 从新到旧），三处复用。 */
internal class SortOrderIconView(
    context: Context,
    private var order: DownloadManager.SeasonSortOrder,
    private var tint: Int,
    private val strokeDp: Float
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = strokeDp * density
    }
    private val path = Path()

    fun update(nextOrder: DownloadManager.SeasonSortOrder, nextTint: Int) {
        order = nextOrder
        tint = nextTint
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = tint
        val w = width.toFloat()
        val h = height.toFloat()
        when (order) {
            DownloadManager.SeasonSortOrder.DEFAULT -> {
                canvas.drawLine(w * 0.16f, h * 0.28f, w * 0.84f, h * 0.28f, paint)
                canvas.drawLine(w * 0.16f, h * 0.50f, w * 0.66f, h * 0.50f, paint)
                canvas.drawLine(w * 0.16f, h * 0.72f, w * 0.48f, h * 0.72f, paint)
            }
            DownloadManager.SeasonSortOrder.OLDEST_FIRST -> {
                canvas.drawLine(w * 0.14f, h * 0.28f, w * 0.34f, h * 0.28f, paint)
                canvas.drawLine(w * 0.14f, h * 0.50f, w * 0.44f, h * 0.50f, paint)
                canvas.drawLine(w * 0.14f, h * 0.72f, w * 0.54f, h * 0.72f, paint)
                canvas.drawLine(w * 0.74f, h * 0.76f, w * 0.74f, h * 0.24f, paint)
                path.rewind()
                path.moveTo(w * 0.60f, h * 0.38f)
                path.lineTo(w * 0.74f, h * 0.24f)
                path.lineTo(w * 0.88f, h * 0.38f)
                canvas.drawPath(path, paint)
            }
            DownloadManager.SeasonSortOrder.NEWEST_FIRST -> {
                canvas.drawLine(w * 0.14f, h * 0.28f, w * 0.54f, h * 0.28f, paint)
                canvas.drawLine(w * 0.14f, h * 0.50f, w * 0.44f, h * 0.50f, paint)
                canvas.drawLine(w * 0.14f, h * 0.72f, w * 0.34f, h * 0.72f, paint)
                canvas.drawLine(w * 0.74f, h * 0.24f, w * 0.74f, h * 0.76f, paint)
                path.rewind()
                path.moveTo(w * 0.60f, h * 0.62f)
                path.lineTo(w * 0.74f, h * 0.76f)
                path.lineTo(w * 0.88f, h * 0.62f)
                canvas.drawPath(path, paint)
            }
        }
    }
}

/** 播放模式图标（顺序播放 / 单集循环 / 随机播放）。 */
internal class PlayModeIconView(
    context: Context,
    private var mode: DownloadManager.LocalPlayMode,
    private var tint: Int,
    private val strokeDp: Float
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = strokeDp * density
    }
    private val path = Path()
    private val arcRect = RectF()

    fun update(nextMode: DownloadManager.LocalPlayMode, nextTint: Int) {
        mode = nextMode
        tint = nextTint
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = tint
        val w = width.toFloat()
        val h = height.toFloat()
        path.rewind()
        when (mode) {
            DownloadManager.LocalPlayMode.SEASON_SEQUENCE -> {
                canvas.drawLine(w * 0.14f, h * 0.28f, w * 0.86f, h * 0.28f, paint)
                canvas.drawLine(w * 0.14f, h * 0.50f, w * 0.86f, h * 0.50f, paint)
                canvas.drawLine(w * 0.14f, h * 0.72f, w * 0.58f, h * 0.72f, paint)
                path.moveTo(w * 0.72f, h * 0.61f)
                path.lineTo(w * 0.86f, h * 0.72f)
                path.lineTo(w * 0.72f, h * 0.83f)
                canvas.drawPath(path, paint)
            }
            DownloadManager.LocalPlayMode.SINGLE_LOOP -> {
                // 单集循环：圆形单箭头闭环 + 居中有底座的字形「1」
                arcRect.set(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f)
                canvas.drawArc(arcRect, -35f, 305f, false, paint)
                path.moveTo(w * 0.64f, h * 0.20f)
                path.lineTo(w * 0.77f, h * 0.27f)
                path.lineTo(w * 0.68f, h * 0.38f)
                canvas.drawPath(path, paint)
                path.rewind()
                path.moveTo(w * 0.44f, h * 0.42f)
                path.lineTo(w * 0.51f, h * 0.36f)
                path.lineTo(w * 0.51f, h * 0.64f)
                path.moveTo(w * 0.44f, h * 0.64f)
                path.lineTo(w * 0.58f, h * 0.64f)
                canvas.drawPath(path, paint)
            }
            DownloadManager.LocalPlayMode.LIST_LOOP -> {
                // 列表循环：扁平圆角矩形双箭头跑道（与单集循环的圆环外形截然不同）
                path.moveTo(w * 0.16f, h * 0.44f)
                path.lineTo(w * 0.16f, h * 0.36f)
                path.quadTo(w * 0.16f, h * 0.26f, w * 0.28f, h * 0.26f)
                path.lineTo(w * 0.80f, h * 0.26f)
                path.moveTo(w * 0.69f, h * 0.16f)
                path.lineTo(w * 0.82f, h * 0.26f)
                path.lineTo(w * 0.69f, h * 0.36f)
                canvas.drawPath(path, paint)
                path.rewind()
                path.moveTo(w * 0.84f, h * 0.56f)
                path.lineTo(w * 0.84f, h * 0.64f)
                path.quadTo(w * 0.84f, h * 0.74f, w * 0.72f, h * 0.74f)
                path.lineTo(w * 0.20f, h * 0.74f)
                path.moveTo(w * 0.31f, h * 0.64f)
                path.lineTo(w * 0.18f, h * 0.74f)
                path.lineTo(w * 0.31f, h * 0.84f)
                canvas.drawPath(path, paint)
            }
            else -> {
                // 随机播放：平滑交叉曲线 + 双右向箭头
                path.moveTo(w * 0.14f, h * 0.28f)
                path.lineTo(w * 0.32f, h * 0.28f)
                path.cubicTo(w * 0.46f, h * 0.28f, w * 0.54f, h * 0.72f, w * 0.68f, h * 0.72f)
                path.lineTo(w * 0.84f, h * 0.72f)
                path.moveTo(w * 0.14f, h * 0.72f)
                path.lineTo(w * 0.32f, h * 0.72f)
                path.cubicTo(w * 0.46f, h * 0.72f, w * 0.54f, h * 0.28f, w * 0.68f, h * 0.28f)
                path.lineTo(w * 0.84f, h * 0.28f)
                canvas.drawPath(path, paint)
                path.rewind()
                path.moveTo(w * 0.74f, h * 0.18f)
                path.lineTo(w * 0.86f, h * 0.28f)
                path.lineTo(w * 0.74f, h * 0.38f)
                path.moveTo(w * 0.74f, h * 0.62f)
                path.lineTo(w * 0.86f, h * 0.72f)
                path.lineTo(w * 0.74f, h * 0.82f)
                canvas.drawPath(path, paint)
            }
        }
    }
}

/** 物理弹簧动效核心：可打断、速度连续、遵循系统减弱动画设置。 */
internal object DsSpring {
    const val SNAP_STIFFNESS = 680f
    const val SNAP_DAMPING = 0.78f
    const val SETTLE_STIFFNESS = 320f
    const val SETTLE_DAMPING = 0.86f
    const val STAGE_STIFFNESS = 200f
    const val STAGE_DAMPING = 0.90f

    fun force(finalPosition: Float, stiffness: Float, damping: Float): SpringForce =
        SpringForce(finalPosition).apply {
            this.stiffness = stiffness
            this.dampingRatio = damping
        }

    fun animate(
        target: View,
        property: DynamicAnimation.ViewProperty,
        to: Float,
        stiffness: Float = SNAP_STIFFNESS,
        damping: Float = SNAP_DAMPING,
        startVelocity: Float? = null,
        onEnd: (() -> Unit)? = null,
    ): SpringAnimation {
        val key = when (property) {
            DynamicAnimation.SCALE_X -> R.id.ds_spring_scale_x
            DynamicAnimation.SCALE_Y -> R.id.ds_spring_scale_y
            DynamicAnimation.TRANSLATION_X -> R.id.ds_spring_tx
            DynamicAnimation.TRANSLATION_Y -> R.id.ds_spring_ty
            else -> 0
        }
        if (key != 0) {
            (target.getTag(key) as? SpringAnimation)?.cancel()
        }
        val animation = SpringAnimation(target, property)
        animation.spring = force(to, stiffness, damping)
        if (startVelocity != null) animation.setStartVelocity(startVelocity)
        if (onEnd != null) {
            animation.addEndListener { _, canceled, _, _ -> if (!canceled) onEnd() }
        }
        if (key != 0) target.setTag(key, animation)
        animation.start()
        return animation
    }

    /** 轻按压缩并在松开时弹性回弹。 */
    fun pressLift(view: View, pressed: Boolean, depth: Float = 0.965f) {
        if (MotionTokens.isReduced(view.context)) return
        val target = if (pressed) depth else 1f
        animate(view, DynamicAnimation.SCALE_X, target, SNAP_STIFFNESS, SNAP_DAMPING)
        animate(view, DynamicAnimation.SCALE_Y, target, SNAP_STIFFNESS, SNAP_DAMPING)
    }

    /** 错峰微上浮入场：只动 alpha + translationY（GPU 合成层），零触发布局重算。 */
    fun enter(view: View, index: Int = 0, reduced: Boolean = false, liftPx: Float = 14f) {
        view.animate().cancel()
        if (reduced) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = liftPx
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index.coerceAtMost(6) * 26L))
            .setDuration(240L)
            .setInterpolator(MotionTokens.easeOut)
            .start()
    }
}

internal class ApertureLayout(context: Context) : android.widget.FrameLayout(context) {
    private val clipPath = Path()
    private val bounds = RectF()
    private var radius = -1f
    private var originX = 0f
    private var originY = 0f

    fun setAperture(cx: Float, cy: Float, r: Float) {
        originX = cx
        originY = cy
        radius = r
        invalidate()
    }

    fun open(cx: Float, cy: Float, reduced: Boolean, onEnd: (() -> Unit)? = null) {
        originX = cx
        originY = cy
        if (reduced) {
            radius = -1f
            invalidate()
            onEnd?.invoke()
            return
        }
        val maxRadius = maxRadius(cx, cy)
        radius = 0f
        invalidate()
        val animation = SpringAnimation(this, RADIUS, maxRadius)
        animation.spring = DsSpring.force(maxRadius, DsSpring.STAGE_STIFFNESS, DsSpring.STAGE_DAMPING)
        animation.addEndListener { _, canceled, _, _ ->
            if (!canceled) {
                radius = -1f
                invalidate()
                onEnd?.invoke()
            }
        }
        animation.start()
    }

    fun close(reduced: Boolean, onEnd: (() -> Unit)? = null) {
        if (reduced) {
            onEnd?.invoke()
            return
        }
        val animation = SpringAnimation(this, RADIUS, 0f)
        animation.spring = DsSpring.force(0f, 220f, 0.9f)
        animation.addEndListener { _, canceled, _, _ -> if (!canceled) onEnd?.invoke() }
        animation.start()
    }

    private fun maxRadius(cx: Float, cy: Float): Float {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        return hypot(maxOf(cx, w - cx).toDouble(), maxOf(cy, h - cy).toDouble()).toFloat() + 2f
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (radius < 0f) {
            super.dispatchDraw(canvas)
            return
        }
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.rewind()
        clipPath.addCircle(originX, originY, radius.coerceAtLeast(0f), Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clipPath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    private companion object {
        val RADIUS = object : androidx.dynamicanimation.animation.FloatPropertyCompat<ApertureLayout>("apertureRadius") {
            override fun getValue(target: ApertureLayout): Float = target.radius
            override fun setValue(target: ApertureLayout, value: Float) {
                target.radius = value
                target.invalidate()
            }
        }
    }
}

/** 胶囊滑动指示器，替代生硬的方框角标。 */
internal class PillIndicatorView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    var fillColor: Int = Ds.colors.accentSoft
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = fillColor
        val r = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
    }
}

/**
 * 品牌图标翻滚加载动效控件：
 * 替代传统的系统转圈 ProgressBar，以 YuiBili 丝带 Y-Play 徽标做连贯的
 * 360° 轻盈翻滚、跃起回落与微倾斜弹性呼吸；支持下拉进度驱动与自动启停，
 * 在不可见或脱离窗口时零消耗释放 Animator，并兼容系统减弱动画偏好。
 */
internal class BrandSpinnerView @JvmOverloads constructor(
    context: Context,
    private var badgeFillColor: Int = Ds.colors.accent,
    private var emblemTintColor: Int = Color.WHITE,
    private var flatBadge: Boolean = false
) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val p1 = Path()
    private val p2 = Path()
    private val p3 = Path()
    private val p4 = Path()
    private val rect = RectF()
    private var animator: ValueAnimator? = null
    private var phase = 0f
    private var manualAngle = 0f
    private var manualProgress = 0f
    private var isManualMode = false

    var spinColor: Int
        get() = badgeFillColor
        set(value) {
            badgeFillColor = value
            invalidate()
        }

    var iconTint: Int
        get() = emblemTintColor
        set(value) {
            emblemTintColor = value
            invalidate()
        }

    /** 下拉阶段由外部手势进度驱动旋转和弹性微缩放（0f..1f）。 */
    fun setPullProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1.35f)
        isManualMode = true
        stopAnim()
        manualProgress = p
        manualAngle = -24f + p * 360f
        invalidate()
    }

    /** 恢复自动翻滚加载态。 */
    fun startSpinning() {
        isManualMode = false
        if (visibility == VISIBLE && isAttachedToWindow) {
            ensureAnim()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isManualMode && visibility == VISIBLE) ensureAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE && !isManualMode && isAttachedToWindow) {
            ensureAnim()
        } else if (visibility != VISIBLE) {
            stopAnim()
        }
    }

    private fun ensureAnim() {
        if (animator?.isRunning == true) return
        if (MotionTokens.isReduced(context)) {
            phase = 0.25f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1050L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                phase = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val size = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f

        val angle: Float
        val hopY: Float
        val squashX: Float
        val squashY: Float
        val shadowAlpha: Int
        val shadowScale: Float

        if (isManualMode) {
            angle = manualAngle
            val p = manualProgress.coerceIn(0f, 1f)
            hopY = 0f
            val s = 0.74f + 0.26f * p
            squashX = s
            squashY = s
            shadowAlpha = (36 * p).toInt()
            shadowScale = 0.7f + 0.3f * p
        } else if (MotionTokens.isReduced(context)) {
            angle = 0f
            hopY = 0f
            squashX = 1f
            squashY = 1f
            shadowAlpha = 24
            shadowScale = 1f
        } else {
            // 非线性翻滚曲线：前半段快速旋转并跃起，落地时轻微弹性压缩
            val t = phase
            val twoPi = (Math.PI * 2.0).toFloat()
            val eased = t - sin(t * twoPi) / (twoPi * 1.45f)
            angle = eased * 360f
            val arc = abs(sin(t * Math.PI.toFloat()))
            hopY = -size * 0.08f * arc
            val pulse = cos(t * twoPi)
            squashX = 1f + 0.055f * pulse
            squashY = 1f - 0.055f * pulse
            shadowAlpha = (34 - 16 * arc).toInt().coerceAtLeast(10)
            shadowScale = 1f - 0.22f * arc
        }

        // 底部柔和落地投影
        if (!flatBadge) {
            shadowPaint.color = Color.argb(shadowAlpha, 0, 0, 0)
            val sw = size * 0.28f * shadowScale
            val sh = (size * 0.055f).coerceAtLeast(1.5f)
            rect.set(cx - sw, cy + size * 0.36f - sh, cx + sw, cy + size * 0.36f + sh)
            canvas.drawOval(rect, shadowPaint)
        }

        val save = canvas.save()
        canvas.translate(cx, cy + hopY)
        canvas.rotate(angle)
        canvas.scale(squashX, squashY)

        // 缩放基准：按 108 网格几何比例绘制丝带 Y-Play 徽标
        val u = (size * 0.76f) / 108f
        canvas.translate(-54f * u, -54f * u)

        if (!flatBadge) {
            bgPaint.color = badgeFillColor
            rect.set(6f * u, 6f * u, 102f * u, 102f * u)
            canvas.drawRoundRect(rect, 28f * u, 28f * u, bgPaint)
        }

        val fgColor = if (flatBadge) badgeFillColor else emblemTintColor
        Ds.drawBrandRibbonEmblem(canvas, u, fgColor, bgPaint, p1, p2, p3, p4)

        canvas.restoreToCount(save)
    }
}
