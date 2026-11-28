package com.yuilittle.bili

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.dynamicanimation.animation.SpringAnimation
import kotlin.math.abs

/**
 * 紧凑悬浮胶囊底栏：居中圆角胶囊 Dock + 微描边与轻阴影 + 滑动柔粉选中胶囊。
 * 全自绘单 Canvas 渲染，支持点击、长按拖拽切换，以及随页面滑动实时联动进度。
 */
class FrostedBottomNavigation @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dockBounds = RectF()
    private val shadowBounds = RectF()
    private val pillRect = RectF()
    private val path = Path()

    private var selectedIndex = 0
    /** 连续高亮中心：0.5f = 首页，1.5f = 动态，2.5f = 我的。 */
    private var frameCenter = 0.5f
    private var pressScale = 1f
    private var frameAnimation: SpringAnimation? = null
    private var pressAnimation: SpringAnimation? = null
    private var destinationListener: ((Int) -> Unit)? = null
    /** 再次点击当前已选中项时的回调（首页重复点击回顶用）。 */
    private var destinationReselectedListener: ((Int) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var longPressArmed = false
    private var scrubMode = false
    private var gestureMoved = false
    private var scrubIndex = -1
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val longPressRunnable = Runnable {
        if (!longPressArmed) return@Runnable
        if (lastTouchY < -dp(24) || lastTouchY > height + dp(24)) return@Runnable
        scrubMode = true
        scrubIndex = itemAt(lastTouchX)
        selectDestination(scrubIndex)
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "底部导航"
        frameCenter = 0.5f
    }

    fun setOnDestinationSelectedListener(listener: (Int) -> Unit) {
        destinationListener = listener
    }

    fun setOnDestinationReselectedListener(listener: (Int) -> Unit) {
        destinationReselectedListener = listener
    }

    fun setSelectedIndex(index: Int) {
        if (index !in 0 until ITEM_COUNT) return
        selectedIndex = index
        val target = index + 0.5f
        if (abs(frameCenter - target) < 0.002f && frameAnimation?.isRunning != true) {
            if (frameCenter != target) {
                frameCenter = target
                invalidate()
            }
            return
        }
        frameAnimation?.cancel()
        if (MotionTokens.isReduced(context)) {
            frameCenter = target
            invalidate()
            return
        }
        frameAnimation = SpringAnimation(this, FRAME_CENTER, target).apply {
            spring = DsSpring.force(
                target,
                if (scrubMode) DsSpring.SNAP_STIFFNESS else DsSpring.SETTLE_STIFFNESS,
                if (scrubMode) DsSpring.SNAP_DAMPING else DsSpring.SETTLE_DAMPING
            )
            start()
        }
    }

    /** 跟随页面滑动进度（0f..ITEM_COUNT-1）实时同步胶囊位置与高亮权重。 */
    fun setScrollProgress(progress: Float) {
        val target = progress.coerceIn(0f, (ITEM_COUNT - 1).toFloat()) + 0.5f
        if (frameAnimation?.isRunning == true) frameAnimation?.cancel()
        if (abs(frameCenter - target) < 0.001f) return
        frameCenter = target
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val c = Ds.colors
        val insetH = dp(4f)
        val insetV = dp(4f)
        dockBounds.set(insetH, insetV, width - insetH, height - insetV)
        val radius = dockBounds.height() / 2f

        // 轻盈落地投影：用极低透明度柔化悬浮边缘，不用模糊渲染
        shadowBounds.set(
            dockBounds.left,
            dockBounds.top + dp(2f),
            dockBounds.right,
            dockBounds.bottom + dp(3f)
        )
        shadowPaint.color = if (c.isDark) 0x55000000 else 0x12000000
        canvas.drawRoundRect(shadowBounds, radius, radius, shadowPaint)

        // 悬浮胶囊底板 + 细微描边
        platePaint.style = Paint.Style.FILL
        platePaint.color = if (c.isDark) 0xF51A1C22.toInt() else 0xF7FFFFFF.toInt()
        canvas.drawRoundRect(dockBounds, radius, radius, platePaint)

        platePaint.style = Paint.Style.STROKE
        platePaint.strokeWidth = Ds.hairline(context).toFloat()
        platePaint.color = if (c.isDark) c.line else c.lineSoft
        canvas.drawRoundRect(dockBounds, radius, radius, platePaint)

        // 内部左右等分区域
        val innerPad = dp(4f)
        val contentLeft = dockBounds.left + innerPad
        val contentWidth = (dockBounds.width() - innerPad * 2f).coerceAtLeast(1f)
        val itemWidth = contentWidth / ITEM_COUNT
        val cy = dockBounds.centerY()

        // 选中项柔粉胶囊指示器
        drawPillIndicator(canvas, contentLeft + itemWidth * frameCenter, itemWidth, dockBounds.height() - innerPad * 2f, cy)

        for (item in 0 until ITEM_COUNT) {
            val centerX = contentLeft + itemWidth * (item + 0.5f)
            val dist = abs(frameCenter - (item + 0.5f))
            val activeWeight = (1f - dist * 2f).coerceIn(0f, 1f)
            val active = dist < 0.5f

            val save = canvas.save()
            val scale = if (item == selectedIndex) pressScale else 1f
            if (scale != 1f) canvas.scale(scale, scale, centerX, cy)
            drawIcon(canvas, item, centerX, cy - dp(7.5f), active, activeWeight)
            drawLabel(canvas, item, centerX, cy + dp(13.5f), active, activeWeight)
            canvas.restoreToCount(save)
        }
    }

    private fun drawPillIndicator(
        canvas: Canvas,
        centerX: Float,
        itemWidth: Float,
        pillHeight: Float,
        cy: Float
    ) {
        val c = Ds.colors
        val halfWidth = (itemWidth - dp(4f)) / 2f
        val halfHeight = pillHeight / 2f
        pillRect.set(
            centerX - halfWidth,
            cy - halfHeight,
            centerX + halfWidth,
            cy + halfHeight
        )
        pillPaint.color = c.accentSoft
        canvas.drawRoundRect(pillRect, halfHeight, halfHeight, pillPaint)
    }

    private fun drawIcon(
        canvas: Canvas,
        item: Int,
        x: Float,
        y: Float,
        active: Boolean,
        activeWeight: Float
    ) {
        val c = Ds.colors
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeCap = Paint.Cap.ROUND
        iconPaint.strokeJoin = Paint.Join.ROUND
        iconPaint.strokeWidth = (1.6f + 0.35f * activeWeight) * density
        iconPaint.color = mixColor(c.muted, c.accent, activeWeight)
        when (item) {
            0 -> drawHomeIcon(canvas, x, y)
            1 -> drawDynamicIcon(canvas, x, y)
            else -> drawProfileIcon(canvas, x, y)
        }
    }

    private fun drawLabel(
        canvas: Canvas,
        item: Int,
        x: Float,
        y: Float,
        active: Boolean,
        activeWeight: Float
    ) {
        val c = Ds.colors
        labelPaint.style = Paint.Style.FILL
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = 10f * resources.displayMetrics.scaledDensity
        labelPaint.typeface = Ds.body(active)
        labelPaint.color = mixColor(c.muted, c.accent, activeWeight)
        canvas.drawText(LABELS[item], x, y, labelPaint)
    }

    /** 动态：闪电轮廓，呼应「关注动态」的信息流语义。 */
    private fun drawDynamicIcon(canvas: Canvas, x: Float, y: Float) {
        path.rewind()
        path.moveTo(x + dp(1.6f), y - dp(6.6f))
        path.lineTo(x - dp(4.2f), y + dp(1.2f))
        path.lineTo(x - dp(0.6f), y + dp(1.2f))
        path.lineTo(x - dp(1.6f), y + dp(6.6f))
        path.lineTo(x + dp(4.2f), y - dp(1.2f))
        path.lineTo(x + dp(0.6f), y - dp(1.2f))
        path.close()
        canvas.drawPath(path, iconPaint)
    }

    private fun drawHomeIcon(canvas: Canvas, x: Float, y: Float) {
        val side = dp(7.6f)
        val roof = y - dp(6.8f)
        val floor = y + dp(6.8f)
        path.rewind()
        path.moveTo(x - side, y - dp(0.6f))
        path.lineTo(x, roof)
        path.lineTo(x + side, y - dp(0.6f))
        path.moveTo(x - dp(5.8f), y + dp(1.1f))
        path.lineTo(x - dp(5.8f), floor - dp(1.5f))
        path.quadTo(x - dp(5.8f), floor, x - dp(4.3f), floor)
        path.lineTo(x + dp(4.3f), floor)
        path.quadTo(x + dp(5.8f), floor, x + dp(5.8f), floor - dp(1.5f))
        path.lineTo(x + dp(5.8f), y + dp(1.1f))
        canvas.drawPath(path, iconPaint)
        // 居中门洞/播放小横竖感提升辨识度
        canvas.drawLine(x, floor - dp(3.6f), x, floor, iconPaint)
    }

    private fun drawProfileIcon(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y - dp(3.8f), dp(3.3f), iconPaint)
        path.rewind()
        path.moveTo(x - dp(6.8f), y + dp(6.8f))
        path.cubicTo(
            x - dp(6.0f), y + dp(1.4f),
            x + dp(6.0f), y + dp(1.4f),
            x + dp(6.8f), y + dp(6.8f)
        )
        canvas.drawPath(path, iconPaint)
    }

    private fun animatePress(pressed: Boolean) {
        if (MotionTokens.isReduced(context)) return
        pressAnimation?.cancel()
        val target = if (pressed) 0.94f else 1f
        pressAnimation = SpringAnimation(this, PRESS_SCALE, target).apply {
            spring = DsSpring.force(target, DsSpring.SNAP_STIFFNESS, DsSpring.SNAP_DAMPING)
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y < 0f || event.y > height) return false
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                longPressArmed = true
                scrubMode = false
                gestureMoved = false
                scrubIndex = itemAt(event.x)
                animatePress(true)
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = event.x
                lastTouchY = event.y
                if (!scrubMode) {
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                        gestureMoved = true
                        animatePress(false)
                    }
                    if (event.y < -dp(24) || event.y > height + dp(24)) {
                        longPressArmed = false
                        removeCallbacks(longPressRunnable)
                    }
                } else {
                    val destination = itemAt(event.x)
                    if (destination != scrubIndex) {
                        scrubIndex = destination
                        selectDestination(destination)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                animatePress(false)
                if (!scrubMode && longPressArmed && !gestureMoved) {
                    performClick()
                    val tapped = itemAt(event.x)
                    if (tapped == selectedIndex) {
                        destinationReselectedListener?.invoke(tapped)
                    } else {
                        selectDestination(tapped)
                    }
                }
                finishGesture()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                animatePress(false)
                finishGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
    private fun itemAt(x: Float): Int {
        val contentLeft = dp(8f)
        val contentWidth = (width - contentLeft * 2f).coerceAtLeast(1f)
        return (((x - contentLeft) / (contentWidth / ITEM_COUNT)).toInt()).coerceIn(0, ITEM_COUNT - 1)
    }

    private fun selectDestination(destination: Int) {
        if (destination !in 0 until ITEM_COUNT || destination == selectedIndex) return
        destinationListener?.invoke(destination) ?: setSelectedIndex(destination)
    }

    private fun finishGesture() {
        longPressArmed = false
        scrubMode = false
        gestureMoved = false
        scrubIndex = -1
    }

    override fun onDetachedFromWindow() {
        frameAnimation?.cancel()
        pressAnimation?.cancel()
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    private fun mixColor(from: Int, to: Int, t: Float): Int {
        val ratio = t.coerceIn(0f, 1f)
        val a = (android.graphics.Color.alpha(from) + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * ratio).toInt()
        val r = (android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * ratio).toInt()
        val g = (android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * ratio).toInt()
        val b = (android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * ratio).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    private fun dp(value: Float): Float = value * density

    private companion object {
        const val ITEM_COUNT = 3
        val LABELS = arrayOf("首页", "动态", "我的")

        val FRAME_CENTER = object : androidx.dynamicanimation.animation.FloatPropertyCompat<FrostedBottomNavigation>("frameCenter") {
            override fun getValue(target: FrostedBottomNavigation): Float = target.frameCenter
            override fun setValue(target: FrostedBottomNavigation, value: Float) {
                target.frameCenter = value
                target.invalidate()
            }
        }
        val PRESS_SCALE = object : androidx.dynamicanimation.animation.FloatPropertyCompat<FrostedBottomNavigation>("pressScale") {
            override fun getValue(target: FrostedBottomNavigation): Float = target.pressScale
            override fun setValue(target: FrostedBottomNavigation, value: Float) {
                target.pressScale = value
                target.invalidate()
            }
        }
    }
}
