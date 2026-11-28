package com.yuilittle.bili

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

/**
 * 根页面容器：首页与我的之间左右滑动切换。
 *
 * 两页始终是 VISIBLE，只靠 translationX 把非当前页移出裁剪区。切换可见性会让
 * GridView 重新布局并回收卡片，封面被清空重载，整页会闪一下。
 */
class RootPager(context: Context) : FrameLayout(context) {
    var prioritizeHorizontalGestures = false
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val dragSlop = (touchSlop * 0.75f).coerceAtLeast(1f)
    private val flingVelocityThreshold = maxOf(
        ViewConfiguration.get(context).scaledMinimumFlingVelocity * 2f,
        220f * density
    )
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val minFlingDistance = maxOf(dragSlop, 14f * density)

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var tracking = false
    private var dragging = false
    private var verticalScrollLocked = false
    private var animating = false
    private var current = 0
    /** 连续水平偏移（0 = 第 0 页，pageWidth = 第 1 页）。 */
    private var pagerOffset = 0f
    private var revealedPage = -1

    private var velocityTracker: VelocityTracker? = null
    private var springAnimation: SpringAnimation? = null

    private var onPageSelected: ((Int) -> Unit)? = null
    private var onPageReveal: ((Int) -> Unit)? = null
    private var onPageScroll: ((Float) -> Unit)? = null

    init {
        clipChildren = true
        clipToPadding = false
    }

    fun setOnPageSelectedListener(listener: (Int) -> Unit) {
        onPageSelected = listener
    }

    fun setOnPageRevealListener(listener: (Int) -> Unit) {
        onPageReveal = listener
    }

    fun setOnPageScrollListener(listener: (Float) -> Unit) {
        onPageScroll = listener
    }

    fun addPage(page: View) {
        val index = childCount
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        page.visibility = View.VISIBLE
        val pageWidth = width.toFloat()
        page.translationX = if (pageWidth > 0f) index * pageWidth - pagerOffset
        else if (index == current) 0f else 1f
    }

    fun currentIndex(): Int = current

    fun isTransitioning(): Boolean = animating || dragging

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (prioritizeHorizontalGestures && w == oldw) return
        if (prioritizeHorizontalGestures && (dragging || animating)) {
            dragging = false
            tracking = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
            recycleVelocityTracker()
            parent?.requestDisallowInterceptTouchEvent(false)
        } else if (dragging || animating) return
        settleImmediately(current)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (prioritizeHorizontalGestures && event.actionMasked == MotionEvent.ACTION_DOWN) {
            // 首页内层分页先接收方向判断，外层“首页/我的”不能提前抢走横滑。
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            if (prioritizeHorizontalGestures && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    private fun maxOffset(): Float = ((childCount - 1).coerceAtLeast(0) * width).toFloat()

    private fun applyPagerOffset(offset: Float) {
        val pageWidth = width.toFloat()
        if (pageWidth <= 0f) return
        val clamped = offset.coerceIn(0f, maxOffset())
        pagerOffset = clamped
        for (index in 0 until childCount) {
            getChildAt(index)?.translationX = index * pageWidth - clamped
        }
        onPageScroll?.invoke((clamped / pageWidth).coerceIn(0f, (childCount - 1).coerceAtLeast(0).toFloat()))
    }

    private fun settleImmediately(index: Int) {
        cancelSpring()
        animating = false
        val pageWidth = width.toFloat()
        pagerOffset = if (pageWidth > 0f) index * pageWidth else 0f
        if (pageWidth > 0f) {
            applyPagerOffset(pagerOffset)
        } else {
            for (i in 0 until childCount) {
                getChildAt(i)?.translationX = if (i == index) 0f else 1f
            }
            onPageScroll?.invoke(index.toFloat())
        }
    }

    /** 切换页面；[animate] 为 false 时直接落位（启动与状态恢复）。 */
    fun showPage(index: Int, animate: Boolean) {
        if (index !in 0 until childCount) return
        if (index == current && !animating && !dragging) {
            settleImmediately(index)
            onPageSelected?.invoke(index)
            return
        }
        animateToPage(index, animate = animate, startVelocityX = 0f, notifySelection = true)
    }

    private fun animateToPage(
        index: Int,
        animate: Boolean,
        startVelocityX: Float,
        notifySelection: Boolean
    ) {
        val targetIndex = index.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        current = targetIndex
        if (notifySelection) {
            onPageReveal?.invoke(targetIndex)
            onPageSelected?.invoke(targetIndex)
        }

        val pageWidth = width.toFloat()
        if (!animate || pageWidth <= 0f || MotionTokens.isReduced(context)) {
            settleImmediately(targetIndex)
            return
        }

        val targetOffset = targetIndex * pageWidth
        if (abs(pagerOffset - targetOffset) < 0.5f && abs(startVelocityX) < 10f) {
            settleImmediately(targetIndex)
            return
        }

        cancelSpring()
        animating = true
        val startOffset = pagerOffset
        val holder = FloatValueHolder(startOffset)
        springAnimation = SpringAnimation(holder).apply {
            spring = SpringForce(targetOffset).apply {
                stiffness = PAGE_SPRING_STIFFNESS
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
            setMinimumVisibleChange(0.5f)
            // pagerOffset 与手指横向速度方向相反（左滑 velocityX < 0 时 pagerOffset 增大）
            setStartVelocity((-startVelocityX).coerceIn(-MAX_SPRING_VELOCITY, MAX_SPRING_VELOCITY))
            addUpdateListener { _, value, _ ->
                applyPagerOffset(value)
            }
            addEndListener { _, canceled, _, _ ->
                if (!canceled && current == targetIndex) {
                    animating = false
                    settleImmediately(targetIndex)
                    onPageSelected?.invoke(current)
                }
            }
            start()
        }
    }

    private fun cancelSpring() {
        springAnimation?.cancel()
        springAnimation = null
    }

    private fun obtainVelocityTracker(): VelocityTracker =
        velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun pointerX(event: MotionEvent): Float {
        val index = if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
            event.findPointerIndex(activePointerId)
        } else {
            -1
        }
        return if (index >= 0) event.getX(index) else event.x
    }

    private fun pointerY(event: MotionEvent): Float {
        val index = if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
            event.findPointerIndex(activePointerId)
        } else {
            -1
        }
        return if (index >= 0) event.getY(index) else event.y
    }

    private fun startDragging(x: Float) {
        cancelSpring()
        animating = false
        dragging = true
        lastX = x
        revealedPage = -1
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun updateDrag(x: Float) {
        val dx = x - lastX
        lastX = x
        val nextOffset = (pagerOffset - dx).coerceIn(0f, maxOffset())
        applyPagerOffset(nextOffset)
        val pageWidth = width.toFloat()
        if (pageWidth > 0f) {
            val revealing = when {
                nextOffset > current * pageWidth -> (current + 1).coerceAtMost(childCount - 1)
                nextOffset < current * pageWidth -> (current - 1).coerceAtLeast(0)
                else -> -1
            }
            if (revealing >= 0 && revealing != revealedPage) {
                revealedPage = revealing
                onPageReveal?.invoke(revealing)
            }
        }
    }

    private fun finishDrag(velocityX: Float) {
        dragging = false
        tracking = false
        verticalScrollLocked = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        val pageWidth = width.toFloat().coerceAtLeast(1f)
        val baseOffset = current * pageWidth
        val delta = pagerOffset - baseOffset
        val absDelta = abs(delta)

        val target = when {
            // 快速轻扫：只要滑出微小距离且方向明确，立即切页
            velocityX <= -flingVelocityThreshold &&
                (delta > minFlingDistance || current < childCount - 1 && absDelta == 0f) ->
                (current + 1).coerceAtMost(childCount - 1)
            velocityX >= flingVelocityThreshold &&
                (delta < -minFlingDistance || current > 0 && absDelta == 0f) ->
                (current - 1).coerceAtLeast(0)
            // 慢速拖拽：超过阈值即切页；若中途反向甩回则留在当前页
            delta > pageWidth * SWITCH_FRACTION && velocityX < flingVelocityThreshold ->
                (current + 1).coerceAtMost(childCount - 1)
            delta < -pageWidth * SWITCH_FRACTION && velocityX > -flingVelocityThreshold ->
                (current - 1).coerceAtLeast(0)
            else -> current
        }
        revealedPage = -1
        animateToPage(target, animate = true, startVelocityX = velocityX, notifySelection = true)
    }

    private fun handlePointerUp(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        if (event.getPointerId(pointerIndex) == activePointerId) {
            val newIndex = if (pointerIndex == 0) 1 else 0
            if (newIndex < event.pointerCount) {
                activePointerId = event.getPointerId(newIndex)
                downX = event.getX(newIndex)
                downY = event.getY(newIndex)
                lastX = downX
            }
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (childCount < 2) return super.onInterceptTouchEvent(event)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            tracking = false
            dragging = false
            verticalScrollLocked = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
            recycleVelocityTracker()
            return false
        }
        if (action != MotionEvent.ACTION_DOWN) {
            if (dragging) return true
            if (verticalScrollLocked) return false
        }

        obtainVelocityTracker().addMovement(event)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                tracking = true
                dragging = false
                verticalScrollLocked = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val x = pointerX(event)
                val y = pointerY(event)
                val dx = x - downX
                val dy = y - downY
                val absDx = abs(dx)
                val absDy = abs(dy)
                if (absDx > dragSlop && absDx > absDy) {
                    val canMove = animating ||
                        (dx < 0 && current < childCount - 1) ||
                        (dx > 0 && current > 0)
                    if (canMove) {
                        startDragging(x)
                        return true
                    }
                } else if (absDy > touchSlop && absDy > absDx * 1.25f) {
                    verticalScrollLocked = true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event)
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (childCount < 2) return super.onTouchEvent(event)
        obtainVelocityTracker().addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                tracking = true
                verticalScrollLocked = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val x = pointerX(event)
                val y = pointerY(event)
                if (!dragging && tracking && !verticalScrollLocked) {
                    val dx = x - downX
                    val dy = y - downY
                    if (abs(dx) > dragSlop && abs(dx) > abs(dy)) {
                        val canMove = animating ||
                            (dx < 0 && current < childCount - 1) ||
                            (dx > 0 && current > 0)
                        if (canMove) startDragging(x)
                    } else if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.25f) {
                        verticalScrollLocked = true
                    }
                }
                if (dragging) updateDrag(x)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                activePointerId = event.getPointerId(index)
                lastX = event.getX(index)
                downX = lastX
                downY = event.getY(index)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val tracker = velocityTracker
                val velocityX = if (tracker != null) {
                    tracker.computeCurrentVelocity(1000, maxFlingVelocity)
                    if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                        tracker.getXVelocity(activePointerId)
                    } else {
                        tracker.xVelocity
                    }
                } else 0f
                recycleVelocityTracker()
                if (dragging) {
                    finishDrag(velocityX)
                } else {
                    tracking = false
                    verticalScrollLocked = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()
                if (dragging) {
                    finishDrag(0f)
                } else {
                    tracking = false
                    verticalScrollLocked = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        if (prioritizeHorizontalGestures) parent?.requestDisallowInterceptTouchEvent(false)
        cancelSpring()
        recycleVelocityTracker()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SWITCH_FRACTION = 0.18f
        const val PAGE_SPRING_STIFFNESS = 420f
        const val MAX_SPRING_VELOCITY = 6000f
    }
}
