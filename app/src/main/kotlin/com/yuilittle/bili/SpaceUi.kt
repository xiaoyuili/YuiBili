package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 个人主页与空间搜索共享 UI 体系（极简柔和现代风）：
 * - [SpaceCoordinatorLayout]：共享资料头卡 + 吸顶双标签栏 + 下方双页协调滚动容器（上滑平滑折叠资料卡，切 Tab 不再重复渲染双份资料卡，支持顶部弹性下拉刷新）
 * - [SpaceHeroCard]：精致暖白/碳墨描边圆角资料卡（头像 / 昵称 / LV / 大会员 / UID 点击复制 / 可展开签名 / 关注按钮 / 粉丝·关注·获赞）
 * - [SpaceTabStrip]：吸顶胶囊双标签栏（动态 | 投稿 / 动态 | 视频），支持实时滑块跟随、数量角标与右侧排序胶囊
 * - [SpaceStateView]：内联状态视图（带骨架屏、空状态、错误重试）
 * - [SpacePullListView]：用于空间搜索等无头卡页面的轻量下拉刷新列表
 * - [ChipFlowLayout]：流式胶囊布局（空间独立搜索历史）
 * - [showAnchoredMenu]：带选中态的轻量锚定弹窗菜单
 */
internal object SpaceUi {
    const val TAB_BAR_HEIGHT_DP = 46
}

// ---------------------------------------------------------------------------
// 1. 共享资料头 + 吸顶标签栏 + 双页协调滚动容器
// ---------------------------------------------------------------------------

internal class SpaceCoordinatorLayout(
    context: Context,
    val headerView: View,
    val tabBarView: View,
    val pagerView: RootPager
) : ViewGroup(context) {

    var activeListProvider: (() -> View?)? = null
    var onCollapseProgressChanged: ((progress: Float) -> Unit)? = null
    var onPullChanged: ((distance: Float, armed: Boolean) -> Unit)? = null
    var onRefreshRequested: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null

    private var headerScrollOffset = 0f
    private var headerMaxScroll = 0
    private var pullDistance = 0f
    private var pullArmed = false
    private var refreshing = false

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var verticalDragging = false
    private var pulling = false
    private var horizontalLocked = false

    private val pullThresholdPx = context.dp(56).toFloat()
    private val maxPullPx = context.dp(92).toFloat()

    init {
        clipChildren = false
        clipToPadding = false
        addView(pagerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(headerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(tabBarView, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(SpaceUi.TAB_BAR_HEIGHT_DP)))
    }

    fun setRefreshing(value: Boolean) {
        if (refreshing == value) return
        refreshing = value
        if (value) {
            pulling = false
            pullArmed = false
            pullDistance = context.dp(46).toFloat()
            onPullChanged?.invoke(pullDistance, true)
            applyTransforms()
        } else {
            animatePullReset()
        }
    }

    fun expandHeader(animate: Boolean = false) {
        if (!animate || MotionTokens.isReduced(context)) {
            scroller.forceFinished(true)
            setHeaderOffset(0f)
            return
        }
        scroller.forceFinished(true)
        val start = headerScrollOffset.roundToInt()
        if (start == 0) return
        scroller.startScroll(0, start, 0, -start, 240)
        postInvalidateOnAnimation()
    }

    fun headerVisibleHeight(): Int = (headerMaxScroll - headerScrollOffset.roundToInt()).coerceAtLeast(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val wExact = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)

        headerView.measure(wExact, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val headerH = headerView.measuredHeight
        headerMaxScroll = headerH.coerceAtLeast(0)

        val tabH = context.dp(SpaceUi.TAB_BAR_HEIGHT_DP)
        tabBarView.measure(wExact, MeasureSpec.makeMeasureSpec(tabH, MeasureSpec.EXACTLY))

        val pagerH = (h - tabH).coerceAtLeast(0)
        pagerView.measure(wExact, MeasureSpec.makeMeasureSpec(pagerH, MeasureSpec.EXACTLY))

        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val headerH = headerView.measuredHeight
        val tabH = tabBarView.measuredHeight

        headerView.layout(0, 0, w, headerH)
        tabBarView.layout(0, headerH, w, headerH + tabH)
        // Pager 高度 = 整个容器高度 - TabBar 高度，紧贴 TabBar 下方，并随 headerScrollOffset 平滑上移至吸顶
        pagerView.layout(0, headerH + tabH, w, headerH + tabH + (h - tabH).coerceAtLeast(0))

        headerScrollOffset = headerScrollOffset.coerceIn(0f, headerMaxScroll.toFloat())
        applyTransforms()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val curr = scroller.currY.toFloat()
            setHeaderOffset(curr)
            if (curr <= 0f || curr >= headerMaxScroll.toFloat()) {
                scroller.forceFinished(true)
            } else {
                postInvalidateOnAnimation()
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                verticalDragging = false
                pulling = false
                horizontalLocked = false
                obtainVelocityTracker().addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                obtainVelocityTracker().addMovement(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                obtainVelocityTracker().addMovement(ev)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (pagerView.isTransitioning()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                verticalDragging = false
                pulling = false
                horizontalLocked = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (horizontalLocked) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.15f) {
                    horizontalLocked = true
                    return false
                }
                if (abs(dy) <= touchSlop) return false
                val listAtTop = isActiveListAtTop()
                val headerCollapsed = headerScrollOffset >= headerMaxScroll - 0.5f
                val headerExpanded = headerScrollOffset <= 0.5f

                // 向上滑且头卡还没完全收起：由协调容器先消费收起头卡
                if (dy < 0f && !headerCollapsed) {
                    verticalDragging = true
                    lastY = ev.y
                    return true
                }
                // 向下滑且下方列表已经到顶：先展开头卡，头卡已完全展开则进入下拉刷新
                if (dy > 0f && listAtTop) {
                    if (!headerExpanded) {
                        verticalDragging = true
                        lastY = ev.y
                        return true
                    } else if (!refreshing && onRefreshRequested != null) {
                        verticalDragging = true
                        pulling = true
                        lastY = ev.y
                        return true
                    }
                }
                // 在头卡/标签栏区域内垂直滑动也直接接管，避免拖不动头卡
                val touchInHeaderZone = downY <= (headerMaxScroll - headerScrollOffset + tabBarView.height)
                if (touchInHeaderZone && abs(dy) > touchSlop) {
                    verticalDragging = true
                    if (dy > 0f && headerExpanded && listAtTop && !refreshing && onRefreshRequested != null) {
                        pulling = true
                    }
                    lastY = ev.y
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                verticalDragging = false
                pulling = false
                recycleVelocityTracker()
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = ev.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - lastY
                lastY = ev.y
                handleVerticalDelta(dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val tracker = velocityTracker
                var vy = 0f
                if (tracker != null) {
                    tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    vy = tracker.yVelocity
                }
                recycleVelocityTracker()
                finishVerticalGesture(vy, canceled = false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()
                finishVerticalGesture(0f, canceled = true)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun handleVerticalDelta(dy: Float) {
        if (pulling) {
            if (dy < 0f && pullDistance <= 0.5f) {
                pulling = false
                pullDistance = 0f
                onPullChanged?.invoke(0f, false)
            } else {
                pullDistance = (pullDistance + dy * 0.48f).coerceIn(0f, maxPullPx)
                pullArmed = pullDistance >= pullThresholdPx
                onPullChanged?.invoke(pullDistance, pullArmed)
                applyTransforms()
                return
            }
        }

        if (dy < 0f) {
            // 上滑：先收起头卡，余量传递给当前 ListView 滚动
            val remain = -dy
            val canCollapse = (headerMaxScroll - headerScrollOffset).coerceAtLeast(0f)
            val consume = minOf(remain, canCollapse)
            if (consume > 0f) setHeaderOffset(headerScrollOffset + consume)
            val leftover = (remain - consume).roundToInt()
            if (leftover > 0) {
                scrollActiveTargetBy(leftover)
            }
        } else if (dy > 0f) {
            // 下滑：先让列表回到顶部，再展开头卡，再触发下拉刷新
            val target = activeListProvider?.invoke()
            if (target != null && !isTargetAtTop(target)) {
                scrollActiveTargetBy((-dy).roundToInt())
                return
            }
            val canExpand = headerScrollOffset.coerceAtLeast(0f)
            val consume = minOf(dy, canExpand)
            if (consume > 0f) setHeaderOffset(headerScrollOffset - consume)
            val leftover = dy - consume
            if (leftover > 0.5f && !refreshing && onRefreshRequested != null && headerScrollOffset <= 0.5f) {
                pulling = true
                pullDistance = (pullDistance + leftover * 0.48f).coerceIn(0f, maxPullPx)
                pullArmed = pullDistance >= pullThresholdPx
                onPullChanged?.invoke(pullDistance, pullArmed)
                applyTransforms()
            }
        }
    }

    private fun finishVerticalGesture(velocityY: Float, canceled: Boolean) {
        verticalDragging = false
        if (pulling) {
            val shouldTrigger = !canceled && pullArmed && !refreshing
            pulling = false
            pullArmed = false
            if (shouldTrigger) {
                setRefreshing(true)
                onRefreshRequested?.invoke()
            } else {
                animatePullReset()
            }
            return
        }
        if (!canceled && abs(velocityY) >= minFlingVelocity && headerMaxScroll > 0) {
            if (headerScrollOffset > 0f && headerScrollOffset < headerMaxScroll.toFloat()) {
                scroller.fling(
                    0, headerScrollOffset.roundToInt(),
                    0, (-velocityY).roundToInt(),
                    0, 0,
                    0, headerMaxScroll
                )
                postInvalidateOnAnimation()
            } else if (headerScrollOffset >= headerMaxScroll - 0.5f && velocityY < 0f) {
                flingActiveTarget((-velocityY).roundToInt())
            }
        }
    }

    private fun animatePullReset() {
        val start = pullDistance
        if (start <= 0.5f) {
            pullDistance = 0f
            pullArmed = false
            onPullChanged?.invoke(0f, false)
            applyTransforms()
            return
        }
        pullDistance = 0f
        pullArmed = false
        onPullChanged?.invoke(0f, false)
        val reduced = MotionTokens.isReduced(context)
        if (reduced) {
            applyTransforms()
            return
        }
        val baseY = -headerScrollOffset
        listOf(headerView, tabBarView, pagerView).forEach { v ->
            v.animate().cancel()
            v.animate().translationY(baseY).setDuration(180L).setInterpolator(MotionTokens.easeOut).start()
        }
    }

    private fun setHeaderOffset(value: Float) {
        val clamped = value.coerceIn(0f, headerMaxScroll.toFloat())
        if (abs(clamped - headerScrollOffset) < 0.01f) return
        headerScrollOffset = clamped
        applyTransforms()
        val ratio = if (headerMaxScroll > 0) (clamped / headerMaxScroll.toFloat()).coerceIn(0f, 1f) else 0f
        onCollapseProgressChanged?.invoke(ratio)
    }

    private fun applyTransforms() {
        val shift = -headerScrollOffset + pullDistance
        headerView.animate().cancel()
        tabBarView.animate().cancel()
        pagerView.animate().cancel()
        headerView.translationY = shift
        tabBarView.translationY = shift
        pagerView.translationY = shift
    }

    private fun scrollActiveTargetBy(dy: Int) {
        when (val target = activeListProvider?.invoke()) {
            is ListView -> target.scrollListBy(dy)
            is android.widget.ScrollView -> target.scrollBy(0, dy)
        }
    }

    private fun flingActiveTarget(velocityY: Int) {
        when (val target = activeListProvider?.invoke()) {
            is ListView -> target.fling(velocityY)
            is android.widget.ScrollView -> target.fling(velocityY)
        }
    }

    private fun isActiveListAtTop(): Boolean {
        val target = activeListProvider?.invoke() ?: return true
        return isTargetAtTop(target)
    }

    private fun isTargetAtTop(target: View): Boolean = when (target) {
        is ListView -> {
            if (target.adapter == null || target.childCount == 0) true
            else if (target.firstVisiblePosition > 0) false
            else (target.getChildAt(0)?.top ?: target.paddingTop) >= target.paddingTop
        }
        is android.widget.ScrollView -> target.scrollY <= 0
        else -> !target.canScrollVertically(-1)
    }

    private fun obtainVelocityTracker(): VelocityTracker =
        velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}

// ---------------------------------------------------------------------------
// 2. 顶部资料区（无多余外框堆叠：纯净画布直铺 + 氛围底色 + 横向数字统计 + 轻量简介）
// ---------------------------------------------------------------------------

internal class SpaceHeroCard(context: Context) : FrameLayout(context) {

    var onAvatarClick: (() -> Unit)? = null
    var onFollowClick: (() -> Unit)? = null
    var onLiveClick: (() -> Unit)? = null
    var onFeedback: ((String) -> Unit)? = null

    private val avatar: ImageView
    private val nameView: TextView
    private val liveTag: TextView
    private val liveBtn: TextView
    private val levelBadge: TextView
    private val vipBadge: TextView
    private val uidChip: TextView
    private val followBtn: TextView
    private val signView: TextView
    private val signToggle: TextView
    private val followerValue: TextView
    private val followingValue: TextView
    private val likesValue: TextView

    private var currentMid = 0L
    private var signExpanded = false
    private var signOverflows = false

    init {
        val auraBg = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                if (Theme.isDark) 0xFF1E191E.toInt() else 0xFFFBF3F6.toInt(),
                COLOR_CARD
            )
        )
        setBackgroundColor(COLOR_CARD)
        addView(
            View(context).apply { background = auraBg },
            LayoutParams(LayoutParams.MATCH_PARENT, context.dp(92))
        )

        val rootCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(14))
        }

        // 第一行：头像（带轻柔描边环）+ 右侧昵称 / 徽章 / UID / 关注操作
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val avatarRing = FrameLayout(context).apply {
            background = Ds.circle(COLOR_SURFACE, Ds.colors.line, context.dp(2).coerceAtLeast(1))
            setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
            isClickable = true
            isFocusable = true
            contentDescription = "查看头像大图"
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { onAvatarClick?.invoke() }
        }
        avatar = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ds.circle(COLOR_COVER)
            Ds.clipRounded(this, 999)
        }
        avatarRing.addView(avatar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        topRow.addView(avatarRing, LinearLayout.LayoutParams(context.dp(66), context.dp(66)))

        val identityCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), 0, context.dp(8), 0)
        }
        nameView = TextView(context).apply {
            textSize = 19.5f
            typeface = Ds.display(bold = true)
            letterSpacing = -0.015f
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            // 昵称后要紧跟「直播中」标签，限制最大宽度避免长昵称把标签挤出屏幕。
            maxWidth = context.dp(176)
        }
        // 昵称行：昵称 + 开播标签（未开播时标签隐藏）
        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        liveTag = TextView(context).apply {
            text = "直播中"
            textSize = 10f
            typeface = Ds.body(bold = true)
            setTextColor(0xFFFFFFFF.toInt())
            background = Ds.pill(context, COLOR_ROSE)
            setPadding(context.dp(6), context.dp(2), context.dp(6), context.dp(2))
            includeFontPadding = false
            visibility = GONE
        }
        nameRow.addView(liveTag, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = context.dp(6) })
        identityCol.addView(nameRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val badgeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        levelBadge = TextView(context).apply {
            textSize = 10f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_MUTED)
            background = rounded(COLOR_SOFT_FILL, context.dp(4))
            setPadding(context.dp(5), context.dp(2), context.dp(5), context.dp(2))
            includeFontPadding = false
            visibility = GONE
        }
        badgeRow.addView(levelBadge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = context.dp(5) })

        vipBadge = TextView(context).apply {
            textSize = 10f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_ROSE)
            background = rounded(COLOR_ROSE_SOFT, context.dp(4))
            setPadding(context.dp(6), context.dp(2), context.dp(6), context.dp(2))
            includeFontPadding = false
            visibility = GONE
        }
        badgeRow.addView(vipBadge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = context.dp(6) })

        uidChip = TextView(context).apply {
            textSize = 11f
            typeface = Ds.body()
            fontFeatureSettings = "tnum"
            setTextColor(Ds.colors.subtle)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            setOnClickListener {
                if (currentMid > 0L) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("UID", currentMid.toString()))
                    onFeedback?.invoke("已复制 UID $currentMid")
                }
            }
        }
        badgeRow.addView(uidChip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        identityCol.addView(badgeRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(7) })

        topRow.addView(identityCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 关注按钮左侧：进入直播间（仅主播开播时可点，未开播置灰提示）
        liveBtn = TextView(context).apply {
            text = "进入直播间"
            textSize = 10.5f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(context.dp(8), context.dp(7), context.dp(8), context.dp(7))
            isClickable = true
            isFocusable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener {
                if (isEnabled) onLiveClick?.invoke() else onFeedback?.invoke("主播暂未开播")
            }
        }
        renderLiveState(live = false)
        topRow.addView(liveBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = context.dp(8) })

        followBtn = TextView(context).apply {
            textSize = 12.5f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = context.dp(72)
            setPadding(context.dp(15), context.dp(8), context.dp(15), context.dp(8))
            isClickable = true
            isFocusable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { onFollowClick?.invoke() }
        }
        topRow.addView(followBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        rootCol.addView(topRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 第二行：横向紧密数据条（1,698 粉丝 · 13 关注 · 4.2万 获赞），左对齐与头像对齐，轻巧紧凑不臃肿
        val statsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        followerValue = addInlineStat(statsRow, "粉丝")
        addInlineDot(statsRow)
        followingValue = addInlineStat(statsRow, "关注")
        addInlineDot(statsRow)
        likesValue = addInlineStat(statsRow, "获赞")
        rootCol.addView(statsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(14) })

        // 第三行：个性签名（柔和浅灰短语，点击整行展开/收起，长按复制）
        signView = TextView(context).apply {
            textSize = 12.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(context.dp(3).toFloat(), 1f)
            includeFontPadding = false
            visibility = GONE
            isClickable = true
            setOnClickListener {
                if (signOverflows) {
                    signExpanded = !signExpanded
                    applySignState()
                }
            }
            setOnLongClickListener {
                val text = text?.toString().orEmpty()
                if (text.isBlank()) return@setOnLongClickListener false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("个性签名", text))
                onFeedback?.invoke("已复制个性签名")
                true
            }
        }
        rootCol.addView(signView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(10) })

        signToggle = TextView(context).apply {
            text = "展开"
            textSize = 11.5f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_ROSE)
            includeFontPadding = false
            setPadding(0, context.dp(4), context.dp(8), 0)
            isClickable = true
            visibility = GONE
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener {
                signExpanded = !signExpanded
                applySignState()
            }
        }
        rootCol.addView(signToggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        addView(rootCol, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            View(context).apply { setBackgroundColor(Ds.colors.lineSoft) },
            LayoutParams(LayoutParams.MATCH_PARENT, Ds.hairline(context), Gravity.BOTTOM)
        )
    }

    private fun addInlineStat(parent: LinearLayout, label: String): TextView {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = TextView(context).apply {
            text = "--"
            textSize = 15f
            typeface = Ds.display(bold = true)
            fontFeatureSettings = "tnum"
            setTextColor(COLOR_INK)
            includeFontPadding = false
        }
        group.addView(value)
        group.addView(TextView(context).apply {
            text = label
            textSize = 11.5f
            typeface = Ds.body()
            setTextColor(Ds.colors.subtle)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = context.dp(4) })
        parent.addView(group, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return value
    }

    private fun addInlineDot(parent: LinearLayout) {
        parent.addView(
            View(context).apply { setBackgroundColor(Ds.colors.line) },
            LinearLayout.LayoutParams(Ds.hairline(context), context.dp(11)).apply {
                leftMargin = context.dp(14)
                rightMargin = context.dp(14)
            }
        )
    }

    /**
     * 直播状态渲染：开播时昵称后显示「直播中」，进入直播间按钮点亮；
     * 未开播或查询失败时按钮置灰，点击只提示不跳转。
     */
    fun renderLiveState(live: Boolean, checking: Boolean = false) {
        liveTag.visibility = if (live) VISIBLE else GONE
        liveBtn.isEnabled = live
        liveBtn.alpha = if (live) 1f else 0.6f
        liveBtn.setTextColor(if (live) COLOR_ROSE else COLOR_MUTED)
        liveBtn.background = if (live) {
            Ds.pill(context, COLOR_ROSE_SOFT)
        } else {
            Ds.pill(context, COLOR_SOFT_FILL)
        }
        liveBtn.text = if (checking) "查询中…" else "进入直播间"
    }

    fun bindPlaceholder(mid: Long, initialName: String, initialFace: String) {
        currentMid = mid
        nameView.text = initialName.ifBlank { "加载中…" }
        uidChip.text = if (mid > 0L) "UID $mid" else ""
        if (initialFace.isNotBlank()) {
            CoverLoader.load(avatar, initialFace, cropThumb = false, thumbEdge = 180, animate = false)
        }
        followBtn.visibility = GONE
        renderLiveState(live = false, checking = true)
    }

    fun bindHeader(header: SpaceHeader, fallbackFace: String = "") {
        currentMid = header.mid
        nameView.text = header.name.ifBlank { "哔哩哔哩用户" }
        uidChip.text = if (header.mid > 0L) "UID ${header.mid}" else ""

        val faceUrl = header.face?.takeIf { it.isNotBlank() } ?: fallbackFace
        if (faceUrl.isNotBlank()) {
            CoverLoader.load(avatar, faceUrl, cropThumb = false, thumbEdge = 180, animate = false)
        }

        if (header.level != null) {
            levelBadge.text = "LV${header.level}"
            levelBadge.visibility = VISIBLE
        } else {
            levelBadge.visibility = GONE
        }

        if (!header.vipLabel.isNullOrBlank()) {
            vipBadge.text = header.vipLabel
            vipBadge.visibility = VISIBLE
        } else {
            vipBadge.visibility = GONE
        }

        val signText = header.sign?.trim().orEmpty()
        if (signText.isNotBlank()) {
            if (signView.text?.toString() != signText) {
                signExpanded = false
                signView.maxLines = 2
                signView.ellipsize = TextUtils.TruncateAt.END
                signView.text = signText
            }
            signView.visibility = VISIBLE
            signView.post { updateSignToggle() }
        } else {
            signView.text = ""
            signView.visibility = GONE
            signToggle.visibility = GONE
        }

        followerValue.text = BiliSpaceApi.formatNullableCount(header.follower)
        followingValue.text = BiliSpaceApi.formatNullableCount(header.following)
        likesValue.text = BiliSpaceApi.formatNullableCount(header.likes)
        renderFollow(header.followState, header.isSelf)
    }

    fun renderFollow(state: FollowState, isSelf: Boolean) {
        if (isSelf || state == FollowState.SELF) {
            followBtn.visibility = GONE
            return
        }
        followBtn.visibility = VISIBLE
        followBtn.isClickable = true
        when (state) {
            FollowState.FOLLOWED -> {
                followBtn.text = "已关注"
                followBtn.setTextColor(COLOR_MUTED)
                followBtn.background = Ds.pill(context, COLOR_FOLLOWED_CHIP, Ds.colors.lineSoft)
            }
            FollowState.NOT_FOLLOWED, FollowState.UNKNOWN -> {
                followBtn.text = "+ 关注"
                followBtn.setTextColor(COLOR_ON_ROSE)
                followBtn.background = Ds.pill(context, COLOR_ROSE)
            }
            FollowState.SELF -> followBtn.visibility = GONE
        }
    }

    private fun updateSignToggle() {
        val layout = signView.layout ?: return
        if (!signExpanded) {
            signOverflows = layout.lineCount > 2 ||
                (layout.lineCount == 2 && layout.getEllipsisCount(1) > 0)
        }
        signToggle.visibility = if (signOverflows) VISIBLE else GONE
        signToggle.text = if (signExpanded) "收起" else "展开全文"
    }

    private fun applySignState() {
        signView.maxLines = if (signExpanded) Int.MAX_VALUE else 2
        signView.ellipsize = if (signExpanded) null else TextUtils.TruncateAt.END
        signToggle.text = if (signExpanded) "收起" else "展开全文"
        if (!signExpanded) {
            signView.post { updateSignToggle() }
        } else {
            signToggle.visibility = VISIBLE
        }
    }
}

// ---------------------------------------------------------------------------
// 3. 吸顶双标签栏（极简下划线指示条 + 数量副标 + 右侧排序胶囊）
// ---------------------------------------------------------------------------

internal class SpaceTabStrip(
    context: Context,
    private val baseTitles: List<String>,
    private val accentColor: Int = COLOR_ROSE
) : FrameLayout(context) {

    private val indicator = View(context).apply {
        background = rounded(accentColor, context.dp(2))
    }
    private val row = LinearLayout(context)
    private val labels = ArrayList<TextView>(baseTitles.size)
    private val actionChip: LinearLayout
    private val actionText: TextView

    var onTabSelected: ((Int) -> Unit)? = null
    var onActionClick: ((View) -> Unit)? = null

    private var progress = 0f

    init {
        setBackgroundColor(COLOR_CARD)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(context.dp(10), 0, context.dp(14), 0)

        baseTitles.forEachIndexed { index, title ->
            val label = TextView(context).apply {
                text = title
                textSize = 15f
                typeface = Ds.display(bold = index == 0)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(if (index == 0) COLOR_INK else COLOR_MUTED)
                setPadding(context.dp(12), 0, context.dp(12), context.dp(2))
                isClickable = true
                isFocusable = true
                Ds.bindPressFeedback(this, 0.94f)
                setOnClickListener { onTabSelected?.invoke(index) }
            }
            labels += label
            row.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                if (index > 0) leftMargin = context.dp(6)
            })
        }

        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

        actionChip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10), context.dp(5), context.dp(8), context.dp(5))
            background = Ds.pill(context, COLOR_SURFACE, Ds.colors.lineSoft)
            isClickable = true
            isFocusable = true
            visibility = GONE
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { onActionClick?.invoke(this) }
        }
        actionText = TextView(context).apply {
            textSize = 12f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_INK)
            includeFontPadding = false
        }
        actionChip.addView(actionText)
        actionChip.addView(
            Ds.toggleChevron(context, COLOR_MUTED, 1.6f),
            LinearLayout.LayoutParams(context.dp(13), context.dp(13)).apply {
                leftMargin = context.dp(4)
            }
        )
        row.addView(actionChip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            indicator,
            LayoutParams(context.dp(24), context.dp(3), Gravity.START or Gravity.BOTTOM).apply {
                bottomMargin = Ds.hairline(context)
            }
        )
        addView(
            View(context).apply { setBackgroundColor(Ds.colors.lineSoft) },
            LayoutParams(LayoutParams.MATCH_PARENT, Ds.hairline(context), Gravity.BOTTOM)
        )
    }

    fun setTabBadge(index: Int, suffix: String?) {
        val label = labels.getOrNull(index) ?: return
        val base = baseTitles.getOrNull(index) ?: return
        val next = if (suffix.isNullOrBlank()) base else "$base $suffix"
        if (label.text?.toString() != next) {
            label.text = next
            post { applyIndicator() }
        }
    }

    fun setActionLabel(text: String) {
        actionText.text = text
    }

    fun setActionVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (actionChip.visibility != target) actionChip.visibility = target
    }

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, (labels.size - 1).coerceAtLeast(1).toFloat())
        applyIndicator()
    }

    fun setSelected(index: Int) = setProgress(index.toFloat())

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyIndicator()
    }

    private fun applyIndicator() {
        val first = labels.getOrNull(0) ?: return
        val second = labels.getOrNull(1) ?: return
        if (first.width <= 0 || second.width <= 0) return
        val p = progress.coerceIn(0f, 1f)
        val indicatorW = context.dp(22)
        val firstCenter = first.left + first.width / 2f
        val secondCenter = second.left + second.width / 2f
        val cx = firstCenter + (secondCenter - firstCenter) * p
        if (indicator.layoutParams.width != indicatorW) {
            indicator.layoutParams = indicator.layoutParams.apply { width = indicatorW }
        }
        indicator.translationX = cx - indicatorW / 2f
        labels.forEachIndexed { index, label ->
            val dist = abs(p - index).coerceIn(0f, 1f)
            label.setTextColor(lerpColor(COLOR_INK, COLOR_MUTED, dist))
            label.typeface = Ds.display(bold = dist < 0.5f)
        }
    }

    private fun lerpColor(start: Int, end: Int, t: Float): Int {
        val c = t.coerceIn(0f, 1f)
        val a = (Color.alpha(start) + (Color.alpha(end) - Color.alpha(start)) * c).toInt()
        val r = (Color.red(start) + (Color.red(end) - Color.red(start)) * c).toInt()
        val g = (Color.green(start) + (Color.green(end) - Color.green(start)) * c).toInt()
        val b = (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * c).toInt()
        return Color.argb(a, r, g, b)
    }
}

// ---------------------------------------------------------------------------
// 4. 空间内联状态视图（加载 / 空 / 失败带重试与可选次动作）
// ---------------------------------------------------------------------------

internal class SpaceStateView(context: Context) : LinearLayout(context) {

    private val spinner = BrandSpinnerView(context, COLOR_ROSE, Color.WHITE)
    private val titleView: TextView
    private val subtitleView: TextView
    private val actionView: TextView
    private var actionHandler: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(32), context.dp(48), context.dp(32), context.dp(32))
        visibility = GONE

        spinner.visibility = GONE
        addView(spinner, LayoutParams(context.dp(26), context.dp(26)).apply {
            bottomMargin = context.dp(12)
        })

        titleView = TextView(context).apply {
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_INK)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        addView(titleView)

        subtitleView = TextView(context).apply {
            textSize = 12.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setLineSpacing(context.dp(3).toFloat(), 1f)
            includeFontPadding = false
            visibility = GONE
        }
        addView(subtitleView, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(6) })

        actionView = TextView(context).apply {
            textSize = 13f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_ROSE)
            background = Ds.pill(context, COLOR_ROSE_SOFT)
            setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
            includeFontPadding = false
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            visibility = GONE
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { actionHandler?.invoke() }
        }
        addView(actionView, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(14) })
    }

    fun showLoading(message: String) {
        visibility = VISIBLE
        titleView.text = message
        titleView.setTextColor(COLOR_MUTED)
        subtitleView.visibility = GONE
        actionView.visibility = GONE
        actionHandler = null
        spinner.visibility = VISIBLE
        spinner.startSpinning()
    }

    fun showMessage(
        title: String,
        subtitle: String? = null,
        action: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        visibility = VISIBLE
        spinner.visibility = GONE
        titleView.text = title
        titleView.setTextColor(COLOR_INK)
        if (!subtitle.isNullOrBlank()) {
            subtitleView.text = subtitle
            subtitleView.visibility = VISIBLE
        } else {
            subtitleView.visibility = GONE
        }
        if (action != null && onAction != null) {
            actionView.text = action
            actionView.visibility = VISIBLE
            actionHandler = onAction
        } else {
            actionView.visibility = GONE
            actionHandler = null
        }
    }

    fun hide() {
        visibility = GONE
        spinner.visibility = GONE
    }
}

// ---------------------------------------------------------------------------
// 5. 空间搜索专用下拉刷新列表（可靠收起动画 + 仅在列表到顶时拦截）
// ---------------------------------------------------------------------------

internal class SpacePullListView(context: Context) : ListView(context) {

    var onRefresh: (() -> Unit)? = null
    var onPullChanged: ((distance: Float, armed: Boolean) -> Unit)? = null

    var refreshing = false
        private set

    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var armed = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (refreshing || onRefresh == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pulling = false
                armed = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = event.y - downY
                if (pulling || (isAtTopRow() && dy > touchSlop && dy > dx * 1.2f)) {
                    pulling = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val offset = (dy * 0.44f).coerceIn(0f, context.dp(88).toFloat())
                    translationY = offset
                    armed = offset >= context.dp(52)
                    onPullChanged?.invoke(offset, armed)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pulling) {
                    if (armed) {
                        setRefreshing(true)
                        onRefresh?.invoke()
                    } else {
                        resetPull()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> if (pulling) {
                resetPull()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setRefreshing(value: Boolean) {
        refreshing = value
        if (value) {
            pulling = false
            armed = false
            translationY = context.dp(44).toFloat()
            onPullChanged?.invoke(translationY, true)
        } else {
            resetPull()
        }
    }

    private fun resetPull() {
        pulling = false
        armed = false
        animate().cancel()
        if (translationY != 0f) {
            animate().translationY(0f).setDuration(180L).setInterpolator(MotionTokens.easeOut).start()
        }
        onPullChanged?.invoke(0f, false)
    }

    private fun isAtTopRow(): Boolean {
        if (adapter == null || childCount == 0) return true
        if (firstVisiblePosition != 0) return false
        val child = getChildAt(0) ?: return true
        return child.top >= paddingTop
    }
}

// ---------------------------------------------------------------------------
// 6. 流式胶囊布局（空间独立搜索历史）
// ---------------------------------------------------------------------------

internal class ChipFlowLayout(context: Context) : ViewGroup(context) {

    var gapX: Int = context.dp(8)
    var gapY: Int = context.dp(8)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        var x = 0
        var y = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x > 0 && x + cw > available) {
                x = 0
                y += rowHeight + gapY
                rowHeight = 0
            }
            x += cw + gapX
            rowHeight = max(rowHeight, ch)
        }
        setMeasuredDimension(available, y + rowHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = width.coerceAtLeast(1)
        var x = 0
        var y = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x > 0 && x + cw > available) {
                x = 0
                y += rowHeight + gapY
                rowHeight = 0
            }
            child.layout(x, y, x + cw, y + ch)
            x += cw + gapX
            rowHeight = max(rowHeight, ch)
        }
    }
}

// ---------------------------------------------------------------------------
// 7. 锚定排序菜单、清空图标与页面转场
// ---------------------------------------------------------------------------

internal fun showAnchoredMenu(
    anchor: View,
    items: List<String>,
    selectedIndex: Int = -1,
    onPick: (Int) -> Unit
) {
    if (items.isEmpty()) return
    val context = anchor.context
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ds.card(context, COLOR_CARD, Ds.colors.line, Ds.Radius.card)
        setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
    }
    var width = context.dp(140)
    val rows = ArrayList<LinearLayout>(items.size)
    items.forEachIndexed { index, label ->
        val selected = index == selectedIndex
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            isClickable = true
            isFocusable = true
            background = Ds.plate(
                if (selected) COLOR_ROSE_SOFT else Color.TRANSPARENT,
                null,
                0,
                context.dp(Ds.Radius.chip).toFloat()
            )
            Ds.bindPressFeedback(this, 0.97f)
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Ds.body(bold = selected)
            setTextColor(if (selected) COLOR_ROSE else COLOR_INK)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) {
            row.addView(Ds.checkIcon(context, COLOR_ROSE, 1.9f), LinearLayout.LayoutParams(
                context.dp(14), context.dp(14)
            ).apply { leftMargin = context.dp(8) })
        }
        column.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { if (index > 0) topMargin = context.dp(2) })
        row.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        width = max(width, row.measuredWidth + context.dp(12))
        rows += row
    }

    val popup = PopupWindow(column, width, LinearLayout.LayoutParams.WRAP_CONTENT, true)
    rows.forEachIndexed { index, row ->
        row.setOnClickListener {
            popup.dismiss()
            onPick(index)
        }
    }

    popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    popup.isOutsideTouchable = true
    popup.elevation = context.dp(8).toFloat()
    val xOff = (anchor.width - width).coerceAtMost(0)
    popup.showAsDropDown(anchor, xOff, context.dp(6))
}

/** 轻量垃圾桶/清空图标（空间搜索历史清空按钮）。 */
internal fun createTrashIcon(context: Context, color: Int = COLOR_MUTED): View =
    object : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.45f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.drawLine(w * 0.20f, h * 0.28f, w * 0.80f, h * 0.28f, paint)
            path.rewind()
            path.moveTo(w * 0.36f, h * 0.28f)
            path.lineTo(w * 0.40f, h * 0.16f)
            path.lineTo(w * 0.60f, h * 0.16f)
            path.lineTo(w * 0.64f, h * 0.28f)
            canvas.drawPath(path, paint)
            path.rewind()
            path.moveTo(w * 0.28f, h * 0.28f)
            path.lineTo(w * 0.32f, h * 0.82f)
            path.lineTo(w * 0.68f, h * 0.82f)
            path.lineTo(w * 0.72f, h * 0.28f)
            canvas.drawPath(path, paint)
            canvas.drawLine(w * 0.44f, h * 0.44f, w * 0.45f, h * 0.68f, paint)
            canvas.drawLine(w * 0.56f, h * 0.44f, w * 0.55f, h * 0.68f, paint)
        }
    }

internal fun Activity.spaceFinishWithAnim() {
    finish()
    @Suppress("DEPRECATION")
    overridePendingTransition(R.anim.slide_in_left_soft, R.anim.slide_out_right)
}
