package com.yuilittle.bili

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation

/**
 * 平板端右侧主舞台纵向滚动切换容器（对应左侧导航栏的上下排列顺序）：
 * - 切页时沿 Y 轴按目标相对位置做平滑物理弹簧垂直滚动（向下切则新页从下方滑入、向上切则从上方滑入），
 *   同时保持轻柔透明度过渡，不销毁已建页面，避免重复加载与封面闪烁。
 */
internal class TabletVerticalStage(context: Context) : FrameLayout(context) {

    private var currentIndex = -1
    private val pageMap = HashMap<Int, View>()
    private var transitionAnim: SpringAnimation? = null
    private var outgoingView: View? = null
    private var incomingView: View? = null

    init {
        clipChildren = true
        clipToPadding = true
    }

    fun getPage(index: Int): View? = pageMap[index]

    fun showPage(index: Int, provider: () -> View, animate: Boolean = true) {
        val target = pageMap[index] ?: provider().also { v ->
            pageMap[index] = v
            v.visibility = View.GONE
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val prevIndex = currentIndex
        if (prevIndex == index) {
            if (transitionAnim != null) return
            target.visibility = View.VISIBLE
            target.translationY = 0f
            target.alpha = 1f
            return
        }
        currentIndex = index

        // 停止上一次未完成的过渡并落位
        transitionAnim?.cancel()
        transitionAnim = null
        outgoingView?.let { old ->
            if (old !== target) {
                old.visibility = View.GONE
                old.translationY = 0f
                old.alpha = 1f
            }
        }
        incomingView?.let { prevIn ->
            if (prevIn !== target && prevIn !== pageMap[prevIndex]) {
                prevIn.visibility = View.GONE
                prevIn.translationY = 0f
                prevIn.alpha = 1f
            }
        }

        val fromView = if (prevIndex >= 0) pageMap[prevIndex] else null
        val reduced = MotionTokens.isReduced(context)
        if (!animate || reduced || fromView == null || height <= 0) {
            fromView?.visibility = View.GONE
            fromView?.translationY = 0f
            fromView?.alpha = 1f
            target.visibility = View.VISIBLE
            target.translationY = 0f
            target.alpha = 1f
            outgoingView = null
            incomingView = null
            return
        }

        // 方向：向下切页（index > prevIndex）新页从下方滑入，旧页向上滑出；反之从上方滑入
        val direction = if (index > prevIndex) 1f else -1f
        val travel = minOf(height.toFloat() * 0.28f, context.dp(180).toFloat())

        outgoingView = fromView
        incomingView = target
        fromView.visibility = View.VISIBLE
        target.visibility = View.VISIBLE
        target.translationY = direction * travel
        target.alpha = 0f
        target.bringToFront()

        val holder = FloatValueHolder(0f)
        transitionAnim = SpringAnimation(holder, 1f).apply {
            spring = DsSpring.force(1f, stiffness = 420f, damping = 0.88f)
            setMinimumVisibleChange(0.002f)
            addUpdateListener { _, value, _ ->
                val p = value.coerceIn(0f, 1.05f)
                fromView.translationY = -direction * travel * 0.45f * p
                fromView.alpha = (1f - p * 1.15f).coerceIn(0f, 1f)
                target.translationY = direction * travel * (1f - p)
                target.alpha = p.coerceIn(0f, 1f)
            }
            addEndListener { _, canceled, _, _ ->
                if (canceled) return@addEndListener
                fromView.visibility = View.GONE
                fromView.translationY = 0f
                fromView.alpha = 1f
                target.translationY = 0f
                target.alpha = 1f
                outgoingView = null
                incomingView = null
                transitionAnim = null
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        transitionAnim?.cancel()
        transitionAnim = null
        outgoingView = null
        incomingView = null
        pageMap.forEach { (index, page) ->
            page.visibility = if (index == currentIndex) View.VISIBLE else View.GONE
            page.translationY = 0f
            page.alpha = 1f
        }
        super.onDetachedFromWindow()
    }
}
