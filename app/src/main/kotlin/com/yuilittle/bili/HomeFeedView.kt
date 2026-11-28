package com.yuilittle.bili

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.Call
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HomeFeedView(context: Context, refreshOnEntry: Boolean, restoredState: Bundle?, private val onSearch: (() -> Unit)? = null) : LinearLayout(context) {
    private val tabs = arrayOfNulls<TextView>(2)
    private val panels = arrayOfNulls<FeedPanel>(2)
    private lateinit var headerRow: View
    private lateinit var tabIndicator: View
    private var selectedTab = restoredState?.getInt(STATE_SELECTED_TAB, TAB_RECOMMEND)
        ?.coerceIn(TAB_RECOMMEND, TAB_POPULAR) ?: TAB_RECOMMEND
    private var active = false
    private var headerCollapsed = false
    private val warmOtherTab = Runnable { if (active) panels[1 - selectedTab]?.warmTabInBackground() }
    private var entrancePlayed = false

    private val isTabletLayout = LayoutMode.isTablet
    private lateinit var feedPager: RootPager
    private var tabProgress = selectedTab.toFloat()
    private var tabletTopButton: ScrollToTopButton? = null
    private val feedAvailableWidthPx: Int
        get() = if (isTabletLayout) {
            (resources.displayMetrics.widthPixels - dp(TabletRailNav.RAIL_WIDTH_DP + 28)).coerceAtLeast(dp(320))
        } else {
            resources.displayMetrics.widthPixels
        }
    private val gridColumns: Int
        get() = LayoutMode.gridColumns(context, feedAvailableWidthPx)

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BACKGROUND)
        val sidePad = dp(if (isTabletLayout) 14 else CARD_SPACING_DP)
        setPadding(sidePad, dp(if (isTabletLayout) 8 else Ds.Space.sm), sidePad, 0)
        clipChildren = false
        clipToPadding = false
        if (isTabletLayout) {
            headerRow = FrameLayout(context).apply {
                addView(createTabs(), FrameLayout.LayoutParams(-1, -1).apply { marginEnd = dp(48) })
                addView(FrameLayout(context).apply {
                    isFocusable = true
                    contentDescription = "搜索"
                    addView(Ds.searchIcon(context, COLOR_INK, 1.8f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                    Ds.bindPressFeedback(this, 0.92f)
                    setOnClickListener { onSearch?.invoke() }
                }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
            }
            addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(4)
            })
        } else {
            headerRow = createHeaderRow()
            val tabsView = createTabs()
            addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(HEADER_HEIGHT_DP)).apply {
                leftMargin = dp(Ds.Space.xs)
                rightMargin = dp(Ds.Space.xs)
            })
            addView(tabsView, LayoutParams(LayoutParams.MATCH_PARENT, dp(38)).apply {
                topMargin = dp(2)
            })
        }

        val host = FrameLayout(context)
        feedPager = RootPager(context).apply {
            prioritizeHorizontalGestures = true
            setOnPageSelectedListener { idx ->
                if (selectedTab != idx) selectTab(idx, fromPager = true)
                syncTabletTopButton()
            }
            setOnPageRevealListener { idx ->
                panels[idx]?.loadSnapshot()
                panels[idx]?.prepareVisible()
            }
            setOnPageScrollListener(::syncTabIndicatorProgress)
        }
        for (tab in 0..1) {
            val panel = FeedPanel(context, tab, refreshOnEntry).apply {
                restoredState?.let { restoreAnchor(it, tab) }
            }
            panels[tab] = panel
            feedPager.addPage(panel)
        }
        feedPager.showPage(selectedTab, animate = false)
        host.addView(feedPager, FrameLayout.LayoutParams(-1, -1))
        if (isTabletLayout) {
            val topBtn = ScrollToTopButton(context) { scrollToTop() }
            tabletTopButton = topBtn
            host.addView(topBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(12)
                bottomMargin = dp(20)
            })
        }
        addView(host, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        selectTab(selectedTab)
        post {
            if (entrancePlayed) return@post
            entrancePlayed = true
            if (MotionTokens.isReduced(context)) return@post
            DsSpring.enter(headerRow, 0, liftPx = 8f)
        }
    }

    private fun syncTabIndicatorProgress(progress: Float) {
        tabProgress = progress.coerceIn(0f, 1f)
        val t0 = tabs[0] ?: return
        val t1 = tabs[1] ?: return
        val stripLeft = (t0.parent as? View)?.left ?: 0
        val x0 = (stripLeft + t0.left + (t0.width - tabIndicator.width) / 2f).coerceAtLeast(0f)
        val x1 = (stripLeft + t1.left + (t1.width - tabIndicator.width) / 2f).coerceAtLeast(0f)
        tabIndicator.translationX = homeTabIndicatorOffset(x0, x1, tabProgress)
    }

    private fun syncTabletTopButton() {
        val btn = tabletTopButton ?: return
        val panel = panels[selectedTab] ?: return
        val firstVisible = panel.firstVisibleRowPosition()
        btn.setShown(nextScrollTopVisible(btn.visibility == View.VISIBLE, firstVisible, showAt = 4, hideAt = 1))
    }

    /** 顶栏单行：左侧品牌徽标，右侧胶囊搜索框（精简去掉文字标题）。 */
    private fun createHeaderRow(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Ds.brandMark(context, COLOR_ROSE, Color.WHITE), LayoutParams(dp(BRAND_MARK_DP), dp(BRAND_MARK_DP)).apply {
            rightMargin = dp(Ds.Space.md)
        })
        addView(createSearchBar(), LayoutParams(0, dp(SEARCH_BOX_HEIGHT_DP), 1f))
    }

    private fun createSearchBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ds.pill(context, COLOR_SURFACE, Ds.colors.lineSoft)
        setPadding(dp(14), 0, dp(14), 0)
        isClickable = true
        isFocusable = true
        contentDescription = "搜索内容"
        transitionName = SEARCH_BOX_TRANSITION_NAME
        Ds.bindPressFeedback(this, 0.975f)
        addView(Ds.searchIcon(context, COLOR_MUTED, 1.8f), LayoutParams(dp(16), dp(16)).apply {
            rightMargin = dp(Ds.Space.sm)
        })
        addView(TextView(context).apply {
            text = "搜索公开视频"
            textSize = SEARCH_BOX_TEXT_SIZE_SP
            typeface = Ds.body()
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(COLOR_MUTED)
            includeFontPadding = false
            isClickable = false
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        setOnClickListener {
            val intent = Intent(context, SearchActivity::class.java)
            val activity = context as? Activity
            if (activity != null) {
                val options = ActivityOptions.makeSceneTransitionAnimation(
                    activity,
                    this,
                    SEARCH_BOX_TRANSITION_NAME
                )
                activity.startActivity(intent, options.toBundle())
            } else {
                context.startActivity(intent)
            }
        }
    }

    private fun createTabs(): View = FrameLayout(context).apply {
        val strip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.Space.xs), 0, dp(Ds.Space.xs), 0)
        }
        tabIndicator = View(context).apply {
            background = rounded(COLOR_ROSE, dp(2))
        }
        addView(tabIndicator, FrameLayout.LayoutParams(dp(TAB_INDICATOR_WIDTH), dp(3), Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(2)
        })
        listOf("推荐", "热门").forEachIndexed { index, label ->
            val tab = TextView(context).apply {
                text = label
                textSize = 15f
                typeface = Ds.body()
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(index) }
            }
            tabs[index] = tab
            strip.addView(tab, LinearLayout.LayoutParams(dp(74), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                if (index == TAB_POPULAR) leftMargin = dp(Ds.Space.sm)
            })
        }
        addView(strip, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            if (isTabletLayout) Gravity.START else Gravity.CENTER_HORIZONTAL
        ))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncTabIndicatorProgress(tabProgress)
        }
    }

    /** Saves only lightweight UI anchors; feed content/cursors live in FeedStore. */
    fun saveState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab)
        panels.forEachIndexed { tab, panel -> panel?.saveState(outState, tab) }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            panels[selectedTab]?.activate()
            postDelayed(warmOtherTab, 350L)
        } else {
            removeCallbacks(warmOtherTab)
            val panel = panels[selectedTab]
            panel?.pauseRequests()
        }
    }

    /** 预热与恢复封面加载，避免首页作为相邻页被拖出/切回时出现空白或滞后。 */
    fun prepareVisible() {
        panels[selectedTab]?.prepareVisible()
    }

    /** 当前选中的首页 Tab 快速平滑回顶；已在顶部时返回 false（静默不响应）。 */
    fun scrollToTop(): Boolean {
        setHeaderCollapsed(false)
        return panels[selectedTab]?.scrollToTop() == true
    }

    private fun setHeaderCollapsed(collapsed: Boolean) {
        // 平板端顶栏包含「推荐 / 热门」主标签，保持常驻不折叠，避免滚动中丢失标签栏
        if (isTabletLayout) return
        if (headerCollapsed == collapsed) return
        headerCollapsed = collapsed
        val targetHeight = if (collapsed) 0 else dp(HEADER_HEIGHT_DP)
        val targetAlpha = if (collapsed) 0f else 1f
        val currentHeight = headerRow.layoutParams.height
        val reduced = MotionTokens.isReduced(context)
        (headerRow.getTag(R.id.ds_spring_ty) as? android.animation.ValueAnimator)?.cancel()
        headerRow.animate().cancel()
        if (reduced) {
            headerRow.layoutParams = (headerRow.layoutParams as LayoutParams).apply {
                height = targetHeight
            }
            headerRow.alpha = targetAlpha
            headerRow.visibility = if (collapsed) View.GONE else View.VISIBLE
            return
        }
        if (!collapsed) headerRow.visibility = View.VISIBLE
        val animator = android.animation.ValueAnimator.ofInt(currentHeight, targetHeight).apply {
            duration = 200L
            interpolator = MotionTokens.easeOut
            addUpdateListener { anim ->
                val lp = headerRow.layoutParams as LayoutParams
                lp.height = anim.animatedValue as Int
                headerRow.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (headerCollapsed) headerRow.visibility = View.GONE
                }
            })
        }
        headerRow.setTag(R.id.ds_spring_ty, animator)
        animator.start()
        headerRow.animate()
            .alpha(targetAlpha)
            .setDuration(160L)
            .setInterpolator(MotionTokens.easeOut)
            .start()
    }

    private fun selectTab(tab: Int, fromPager: Boolean = false) {
        if (tab !in 0..1) return
        val previous = selectedTab
        val changed = previous != tab
        selectedTab = tab
        if (changed) {
            panels[previous]?.cancelScrollToTop()
            if (panels[tab]?.isAtTop() == true) {
                setHeaderCollapsed(false)
            }
        }
        for (index in 0..1) {
            tabs[index]?.let { label ->
                val selected = index == tab
                label.setTextColor(if (selected) COLOR_ROSE else COLOR_MUTED)
                label.typeface = Ds.body(selected)
                label.background = null
                label.isSelected = selected
                label.animate().cancel()
                label.animate()
                    .alpha(if (selected) 1f else 0.78f)
                    .setDuration(TAB_SWITCH_DURATION_MS)
                    .setInterpolator(MotionTokens.easeOut)
                    .start()
            }
        }
        if (!fromPager) feedPager.showPage(tab, animate = changed && !MotionTokens.isReduced(context))
        if (changed) panels[previous]?.pauseRequests()
        if (active) panels[tab]?.activate()
        syncTabletTopButton()
    }

    private data class ScrollAnchor(val bvid: String?, val position: Int, val offset: Int)

    private inner class FeedPanel(context: Context, private val tab: Int, refreshOnEntry: Boolean) : FrameLayout(context) {
        private val adapter = VideoAdapter(context)
        private val grid = PullGridView(context)
        private val topSpinner = BrandSpinnerView(context, COLOR_ROSE, Color.WHITE)
        private val bottomSpinner = BrandSpinnerView(context, COLOR_ROSE, Color.WHITE)
        private val notice = TextView(context)
        private var page = 0
        private var lastScannedPage = 0
        private var generation = 0
        private var refreshing = false
        private var manuallyRefreshing = false
        private var loadingMore = false
        private var activeCall: Call? = null
        private var snapshotLoaded = false
        private var snapshotLoading = false
        private var cachedSnapshotVideos: List<VideoItem> = emptyList()
        private var cachedSnapshotPage = 0
        private var cachedSnapshotScannedPage = 0
        private var cachedKnownIds: Set<String> = emptySet()
        private var bestFallbackItems: List<VideoItem> = emptyList()
        private var bestFallbackPage = 0
        private var latestScannedCursor = 0
        private var hasLatestScannedCursor = false
        private var refreshDeadlineMs = 0L
        private var refreshBudgetRunnable: Runnable? = null
        private var cancelSnapshot: (() -> Unit)? = null
        private var refreshPending = refreshOnEntry
        private var userRefreshPending = false
        private var pendingAnchor: ScrollAnchor? = null
        private var reachedEnd = false
        private var userHasScrolled = false
        private var paginationPaused = false
        private val skeletonGrid = FrameLayout(context)
        private val topScroller by lazy(LazyThreadSafetyMode.NONE) {
            AbsListSmoothTopScroller(
                grid, nearPosition = 14,
                onStart = { setHeaderCollapsed(false) }
            )
        }
        fun scrollToTop(): Boolean = topScroller.scrollToTop()
        fun cancelScrollToTop() = topScroller.cancel()
        fun firstVisibleRowPosition(): Int = grid.firstVisiblePosition
        private val appendWhenNearEnd = Runnable {
            if (active && selectedTab == tab && !paginationPaused &&
                (userHasScrolled || visiblePageIsUnderfilled()) &&
                grid.lastVisiblePosition >= adapter.count - LOAD_AHEAD_ITEMS - 1
            ) loadMore()
        }

        init {
            topSpinner.apply {
                alpha = 0f
                visibility = View.GONE
                contentDescription = "正在刷新"
            }
            bottomSpinner.apply {
                visibility = View.GONE
                contentDescription = "正在加载更多"
            }
            grid.apply {
                numColumns = gridColumns
                horizontalSpacing = dp(CARD_SPACING_DP)
                verticalSpacing = dp(CARD_SPACING_DP)
                setPadding(
                    dp(CARD_SHADOW_INSET_DP),
                    dp(3),
                    dp(CARD_SHADOW_INSET_DP),
                    dp(if (isTabletLayout) 28 else 88)
                )
                clipToPadding = false
                clipChildren = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setVerticalScrollBarEnabled(false)
                setHorizontalScrollBarEnabled(false)
                setSelector(android.R.color.transparent)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                adapter = this@FeedPanel.adapter
                setRecyclerListener { recycled ->
                    (recycled.tag as? VideoCardUi.Holder)?.let { CoverLoader.clear(it.cover) }
                }
                setOnItemClickListener { _, _, position, _ ->
                    this@FeedPanel.adapter.itemOrNull(position)?.let { video ->
                        VideoDetailActivity.open(context, video.bvid, video.aid, video.cid)
                    }
                }
                onRefreshRequested = { refresh(fromUser = true) }
                onPullChanged = { distance, _ -> updateTopSpinner(distance) }
                onScrollDirection = { dy ->
                    if (selectedTab == tab) {
                        if (dy > dp(2) && !isAtTop()) {
                            setHeaderCollapsed(true)
                        } else if (dy < -dp(6) || isAtTop()) {
                            setHeaderCollapsed(false)
                        }
                    }
                }
                setOnScrollListener(object : AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                        if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL ||
                            scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING
                        ) {
                            userHasScrolled = true
                            if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                                paginationPaused = false
                                postOnAnimation(appendWhenNearEnd)
                            }
                        }
                    }

                    override fun onScroll(
                        view: AbsListView?,
                        firstVisibleItem: Int,
                        visibleItemCount: Int,
                        totalItemCount: Int
                    ) {
                        if (selectedTab == tab) {
                            if (isTabletLayout) {
                                syncTabletTopButton()
                            }
                            if (firstVisibleItem == 0 && (getChildAt(0)?.top ?: paddingTop) >= paddingTop - dp(4)) {
                                setHeaderCollapsed(false)
                            } else if (isScrollingUp && (firstVisibleItem > 0 || (getChildAt(0)?.top ?: paddingTop) < paddingTop - dp(12))) {
                                setHeaderCollapsed(true)
                            }
                        }
                        if (!loadingMore && !refreshing && !paginationPaused && !topScroller.isRunning &&
                            totalItemCount > 0 &&
                            (userHasScrolled || visiblePageIsUnderfilled()) &&
                            firstVisibleItem + visibleItemCount >= totalItemCount - LOAD_AHEAD_ITEMS
                        ) {
                            removeCallbacks(appendWhenNearEnd)
                            postOnAnimation(appendWhenNearEnd)
                        }
                    }
                })
            }
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            skeletonGrid.apply {
                visibility = View.GONE
                isClickable = false
                addView(VideoCardUi.createSkeletonLayout(context, this, gridColumns, feedAvailableWidthPx))
            }
            addView(skeletonGrid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(topSpinner, LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(4)
            })
            addView(notice, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(46)
            })
            addView(bottomSpinner, LayoutParams(dp(30), dp(30), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(92)
            })
            notice.apply {
                textSize = 12f
                typeface = Ds.body(true)
                gravity = Gravity.CENTER
                setTextColor(COLOR_INK)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = Ds.pill(context, COLOR_SURFACE, Ds.colors.lineSoft)
                visibility = View.GONE
                isClickable = true
            }
        }

        private fun visiblePageIsUnderfilled(): Boolean =
            adapter.count in 1 until FeedPagingPolicy.PAGE_SIZE && grid.childCount == adapter.count &&
                !grid.canScrollVertically(1)

        fun isAtTop(): Boolean =
            adapter.count == 0 ||
                (grid.firstVisiblePosition == 0 && (grid.getChildAt(0)?.top ?: grid.paddingTop) >= grid.paddingTop - dp(4))

        fun activate() {
            loadSnapshot()
            startEntryRefresh()
        }

        fun prepareVisible() {
            if (!refreshPending) loadSnapshot()
            for (i in 0 until grid.childCount) {
                val child = grid.getChildAt(i) ?: continue
                val holder = child.tag as? VideoCardUi.Holder ?: continue
                val video = holder.boundVideo ?: continue
                if (holder.cover.drawable == null && video.cover.isNotBlank()) {
                    CoverLoader.load(holder.cover, video.cover, animate = false)
                }
            }
        }

        fun warmTabInBackground() {
            if (!refreshPending) loadSnapshot()
        }

        private var feedPrefetchHandle: CoverLoader.PrefetchHandle? = null

        private fun prefetchFirstScreenCovers(items: List<VideoItem>) {
            feedPrefetchHandle?.cancel()
            val maxPrefetch = if (isTabletLayout) 12 else 6
            val specs = items.take(maxPrefetch).mapNotNull { v ->
                if (v.cover.isBlank()) null
                else CoverLoader.PrefetchSpec(
                    rawUrl = v.cover,
                    cropThumb = true,
                    thumbEdge = 480,
                    priority = CoverLoader.PRIORITY_NORMAL
                )
            }
            feedPrefetchHandle = if (specs.isNotEmpty()) CoverLoader.prefetchBatch(context, specs) else null
        }

        fun loadSnapshot() {
            if (snapshotLoading || snapshotLoaded) return
            snapshotLoading = true
            if (refreshPending && adapter.count == 0) {
                showSkeleton(true)
            }
            val requestGeneration = generation
            cancelSnapshot = FeedStore.loadState(context, tab) { state ->
                if (requestGeneration != generation) return@loadState
                cancelSnapshot = null
                snapshotLoading = false
                snapshotLoaded = true
                page = if (state.videos.isEmpty()) 0 else state.lastSuccessfulPage
                lastScannedPage = if (state.videos.isEmpty()) 0 else state.lastScannedPage
                cachedSnapshotVideos = state.videos
                cachedSnapshotPage = page
                cachedSnapshotScannedPage = lastScannedPage
                cachedKnownIds = state.videos.mapTo(HashSet(state.videos.size)) { it.bvid }
                if (state.videos.isNotEmpty()) {
                    // 磁盘快照就绪立即铺首屏（即使稍后触发自动刷新，也先让用户秒看内容、零骨架空白等待）
                    showSkeleton(false)
                    prefetchFirstScreenCovers(state.videos)
                    adapter.replace(state.videos, animateNew = false)
                    pendingAnchor?.let { anchor ->
                        val found = adapter.indexOf(anchor.bvid)
                        val position = if (found >= 0) found else anchor.position.coerceIn(0, adapter.count - 1)
                        grid.setSelectionFromTop(position, anchor.offset)
                    }
                    pendingAnchor = null
                    if (!refreshPending) {
                        cachedSnapshotVideos = emptyList()
                        cachedSnapshotPage = 0
                        cachedSnapshotScannedPage = 0
                        cachedKnownIds = emptySet()
                    }
                }
                startEntryRefresh()
            }
        }

        private fun startEntryRefresh() {
            if (!active || selectedTab != tab || !snapshotLoaded) return
            if (userRefreshPending || refreshPending || adapter.count == 0) {
                val manual = userRefreshPending
                userRefreshPending = false
                refresh(fromUser = manual)
            }
        }

        fun saveState(outState: Bundle, slot: Int) {
            val position = grid.firstVisiblePosition.coerceAtLeast(0)
            val anchor = pendingAnchor ?: ScrollAnchor(
                adapter.itemOrNull(position)?.bvid,
                position,
                (grid.getChildAt(0)?.top ?: grid.paddingTop) - grid.paddingTop
            )
            outState.putString(scrollIdKey(slot), anchor.bvid)
            outState.putInt(scrollPositionKey(slot), anchor.position)
            outState.putInt(scrollOffsetKey(slot), anchor.offset)
        }

        fun restoreAnchor(state: Bundle, slot: Int) {
            if (!state.containsKey(scrollPositionKey(slot))) return
            pendingAnchor = ScrollAnchor(
                state.getString(scrollIdKey(slot)),
                state.getInt(scrollPositionKey(slot)),
                state.getInt(scrollOffsetKey(slot)) - if (state.containsKey(scrollIdKey(slot))) 0 else grid.paddingTop
            )
        }

        fun pauseRequests() {
            feedPrefetchHandle?.cancel()
            feedPrefetchHandle = null
            if (activeCall != null) {
                generation++
                activeCall?.cancel()
                activeCall = null
            }
            cancelRefreshBudgetTimer()
            if (refreshing) {
                refreshPending = true
                userRefreshPending = userRefreshPending || manuallyRefreshing
                showSkeleton(false)
                if (adapter.count == 0) {
                    page = cachedSnapshotPage
                    lastScannedPage = cachedSnapshotScannedPage
                }
            }
            finishRefresh()
            cancelAppendUiState()
            grid.removeCallbacks(appendWhenNearEnd)
        }

        override fun onDetachedFromWindow() {
            pauseRequests()
            cancelRefreshBudgetTimer()
            generation++
            cancelSnapshot?.invoke()
            cancelSnapshot = null
            snapshotLoading = false
            hideNotice()
            super.onDetachedFromWindow()
        }

        private fun refresh(fromUser: Boolean) {
            if (!snapshotLoaded) {
                userRefreshPending = userRefreshPending || fromUser
                loadSnapshot()
                return
            }
            if (refreshing && (!fromUser || manuallyRefreshing)) return
            refreshing = true
            manuallyRefreshing = fromUser
            refreshPending = false
            activeCall?.cancel()
            activeCall = null
            cancelRefreshBudgetTimer()
            cancelAppendUiState()
            reachedEnd = false
            userHasScrolled = false
            paginationPaused = false
            bestFallbackItems = emptyList()
            bestFallbackPage = 0
            latestScannedCursor = 0
            hasLatestScannedCursor = false
            refreshDeadlineMs = android.os.SystemClock.elapsedRealtime() + REFRESH_BUDGET_MS
            val requestGeneration = ++generation
            hideNotice()
            if (fromUser) grid.setRefreshing(true)
            if (adapter.count == 0) {
                showSkeleton(true)
                hideTopSpinner()
            } else if (fromUser) {
                showTopSpinner()
            }
            scheduleRefreshBudgetTimer(requestGeneration, fromUser)
            loadFreshPage(FeedPagingPolicy.nextRefreshPage(lastScannedPage), requestGeneration,
                fromUser, attempts = 0, didWrap = false)
        }

        private fun cancelRefreshBudgetTimer() {
            refreshBudgetRunnable?.let { removeCallbacks(it) }
            refreshBudgetRunnable = null
        }

        private fun scheduleRefreshBudgetTimer(requestGeneration: Int, fromUser: Boolean) {
            val task = Runnable {
                refreshBudgetRunnable = null
                if (requestGeneration != generation || !refreshing) return@Runnable
                generation++
                activeCall?.cancel()
                activeCall = null
                if (commitBestFallbackIfAvailable()) {
                    return@Runnable
                }
                val usedCache = restoreCachedFallbackIfEmpty()
                showSkeleton(false)
                finishRefresh()
                showTopNotice(
                    when {
                        usedCache -> "加载超时，已显示上次缓存"
                        adapter.count == 0 -> "加载超时，点这里重试"
                        else -> "刷新超时，已保留当前内容"
                    },
                    clickable = true
                ) { refresh(fromUser = fromUser) }
            }
            refreshBudgetRunnable = task
            postDelayed(task, REFRESH_BUDGET_MS)
        }

        private fun loadFreshPage(
            candidatePage: Int,
            requestGeneration: Int,
            fromUser: Boolean,
            attempts: Int,
            didWrap: Boolean
        ) {
            requestPage(candidatePage) callback@{ result, _ ->
                if (requestGeneration != generation) return@callback
                if (result == null) {
                    cancelRefreshBudgetTimer()
                    val usedCandidate = commitBestFallbackIfAvailable()
                    if (!usedCandidate) {
                        val usedCache = restoreCachedFallbackIfEmpty()
                        showSkeleton(false)
                        finishRefresh()
                        showTopNotice(
                            when {
                                usedCache -> "网络加载失败，已显示上次缓存"
                                adapter.count == 0 -> "加载失败，点这里重试"
                                else -> "刷新失败，已保留当前内容"
                            },
                            clickable = true
                        ) { refresh(fromUser = fromUser) }
                    }
                    return@callback
                }

                if (!fromUser && adapter.count > 0 && (userHasScrolled || grid.canScrollVertically(-1))) {
                    cancelRefreshBudgetTimer()
                    bestFallbackItems = emptyList()
                    bestFallbackPage = 0
                    hasLatestScannedCursor = false
                    showSkeleton(false)
                    finishRefresh()
                    grid.postOnAnimation(appendWhenNearEnd)
                    return@callback
                }
                val nextCursor = FeedPagingPolicy.scannedCursor(candidatePage, result.endReached, tab == TAB_POPULAR)
                latestScannedCursor = nextCursor
                hasLatestScannedCursor = true
                val distinctItems = result.items.distinctBy { it.bvid }
                if (distinctItems.isNotEmpty() && bestFallbackItems.isEmpty()) {
                    bestFallbackItems = distinctItems
                    bestFallbackPage = candidatePage
                }
                // 若当前屏幕尚未展示任何视频，第一份非空网络结果立即上屏，不再继续多页空等扫描
                if (adapter.count == 0 && distinctItems.isNotEmpty()) {
                    cancelRefreshBudgetTimer()
                    commitFreshList(distinctItems, candidatePage, nextCursor)
                    return@callback
                }
                val hasUnseen = if (adapter.count > 0) {
                    adapter.newItems(distinctItems).isNotEmpty()
                } else {
                    distinctItems.any { it.bvid !in cachedKnownIds }
                }
                if (hasUnseen) {
                    cancelRefreshBudgetTimer()
                    commitFreshList(distinctItems, candidatePage, nextCursor)
                    return@callback
                }

                val withinBudget = android.os.SystemClock.elapsedRealtime() < refreshDeadlineMs
                val canScanAgain = withinBudget && attempts + 1 < FeedPagingPolicy.MAX_REFRESH_SCAN_PAGES
                when {
                    canScanAgain && result.endReached && !didWrap ->
                        loadFreshPage(1, requestGeneration, fromUser, attempts + 1, didWrap = true)
                    canScanAgain && !result.endReached ->
                        loadFreshPage(candidatePage + 1, requestGeneration, fromUser, attempts + 1, didWrap)
                    else -> {
                        cancelRefreshBudgetTimer()
                        if (commitBestFallbackIfAvailable()) {
                            return@callback
                        }
                        val usedCache = restoreCachedFallbackIfEmpty()
                        showSkeleton(false)
                        if (!usedCache) {
                            lastScannedPage = nextCursor
                            saveSnapshot()
                        }
                        finishRefresh()
                        if (adapter.count == 0) {
                            showTopNotice("暂无内容，点这里重试", clickable = true) {
                                refresh(fromUser = true)
                            }
                        } else {
                            showTopNotice(
                                if (usedCache) "暂无新内容，已显示上次内容" else "暂未发现新的内容",
                                clickable = false
                            )
                        }
                    }
                }
            }
        }

        private fun commitFreshList(items: List<VideoItem>, candidatePage: Int, scannedCursor: Int) {
            page = candidatePage
            lastScannedPage = scannedCursor
            cachedSnapshotVideos = emptyList()
            cachedSnapshotPage = 0
            cachedSnapshotScannedPage = 0
            cachedKnownIds = emptySet()
            bestFallbackItems = emptyList()
            bestFallbackPage = 0
            hasLatestScannedCursor = false
            pendingAnchor = null
            showSkeleton(false)
            prefetchFirstScreenCovers(items)
            adapter.replace(items, animateNew = true)
            grid.setSelectionFromTop(0, 0)
            saveSnapshot()
            finishRefresh()
            // 平板 4 列 × 3 行一屏展示 12 张，但 B 站推荐接口单次常只回 10~11 条（过滤广告/直播后）；
            // 若提交后不足一整屏（12 张），立即静默补拉下一页填满 3 行，不再留空缺等用户下滑。
            val minFullScreenCount = if (isTabletLayout) gridColumns * 3 else 6
            if (!reachedEnd && adapter.count in 1 until minFullScreenCount) {
                grid.post { if (active && selectedTab == tab) loadMore() }
            } else {
                grid.postOnAnimation(appendWhenNearEnd)
            }
        }

        private fun commitBestFallbackIfAvailable(): Boolean {
            if (adapter.count > 0 || bestFallbackItems.isEmpty()) return false
            val items = bestFallbackItems
            val p = bestFallbackPage
            val cursor = if (hasLatestScannedCursor) latestScannedCursor else p
            commitFreshList(items, p, cursor)
            return true
        }

        private fun restoreCachedFallbackIfEmpty(): Boolean {
            if (adapter.count > 0 || cachedSnapshotVideos.isEmpty()) return false
            val fallback = cachedSnapshotVideos
            page = cachedSnapshotPage
            lastScannedPage = cachedSnapshotScannedPage
            cachedSnapshotVideos = emptyList()
            cachedSnapshotPage = 0
            cachedSnapshotScannedPage = 0
            cachedKnownIds = emptySet()
            bestFallbackItems = emptyList()
            bestFallbackPage = 0
            hasLatestScannedCursor = false
            pendingAnchor = null
            adapter.replace(fallback, animateNew = false)
            grid.setSelectionFromTop(0, 0)
            return true
        }

        private fun showSkeleton(show: Boolean) {
            VideoCardUi.toggleSkeleton(skeletonGrid, show)
        }

        private fun loadMore() {
            if (refreshing || loadingMore || reachedEnd || adapter.count == 0) return
            paginationPaused = false
            loadingMore = true
            hideNotice()
            showBottomLoader(true)
            loadMorePage(FeedPagingPolicy.nextRefreshPage(lastScannedPage), skippedPages = 0, didWrap = false)
        }

        private fun loadMorePage(candidatePage: Int, skippedPages: Int, didWrap: Boolean) {
            val requestGeneration = generation
            requestPage(candidatePage) callback@{ result, _ ->
                if (requestGeneration != generation) return@callback
                if (result == null) {
                    paginationPaused = true
                    cancelAppendUiState()
                    showTopNotice("加载失败，点这里重试", clickable = true) { loadMore() }
                    return@callback
                }

                val popular = tab == TAB_POPULAR
                lastScannedPage = FeedPagingPolicy.scannedCursor(candidatePage, result.endReached, popular)
                val additions = adapter.newItems(result.items)
                if (additions.isEmpty()) {
                    when {
                        popular && result.endReached && !didWrap ->
                            loadMorePage(1, skippedPages, didWrap = true)
                        !result.endReached && skippedPages < FeedPagingPolicy.MAX_APPEND_SKIP_PAGES ->
                            loadMorePage(candidatePage + 1, skippedPages + 1, didWrap)
                        else -> {
                            reachedEnd = result.endReached && !popular
                            saveSnapshot()
                            paginationPaused = true
                            cancelAppendUiState()
                            val message = when {
                                reachedEnd -> "已经到底了"
                                result.endReached -> "暂时没有新的热门内容"
                                else -> "本页内容重复，继续下滑重试"
                            }
                            showTopNotice(message, clickable = false)
                        }
                    }
                    return@callback
                }

                val firstVisible = grid.firstVisiblePosition
                val topOffset = (grid.getChildAt(0)?.top ?: grid.paddingTop) - grid.paddingTop
                val removed = adapter.appendWindowed(additions)
                if (removed > 0) {
                    grid.setSelectionFromTop((firstVisible - removed).coerceAtLeast(0), topOffset)
                }
                page = candidatePage
                reachedEnd = result.endReached && !popular
                saveSnapshot()
                cancelAppendUiState()
            }
        }

        private fun requestPage(page: Int, callback: (FeedPage?, String?) -> Unit) {
            val token = System.currentTimeMillis()
            val requestGeneration = generation
            val deliver: (FeedPage?, String?) -> Unit = { result, error ->
                if (requestGeneration == generation) {
                    activeCall = null
                    callback(result, error)
                }
            }
            activeCall = if (tab == TAB_RECOMMEND) {
                BiliApi.fetchRecommend(page, token, deliver)
            } else {
                BiliApi.fetchPopular(page, token, deliver)
            }
        }

        private fun saveSnapshot() = FeedStore.saveState(context, tab, adapter.snapshot(), page, lastScannedPage)

        private fun cancelAppendUiState() {
            loadingMore = false
            showBottomLoader(false)
        }

        private fun finishRefresh() {
            refreshing = false
            manuallyRefreshing = false
            grid.setRefreshing(false)
            hideTopSpinner()
        }

        private fun updateTopSpinner(distance: Float) {
            if (refreshing) {
                showTopSpinner()
                return
            }
            if (distance <= 0f) {
                hideTopSpinner()
                return
            }
            val progress = distance / dp(PULL_REFRESH_DISTANCE).toFloat()
            topSpinner.visibility = View.VISIBLE
            topSpinner.alpha = progress.coerceIn(0.25f, 1f)
            topSpinner.setPullProgress(progress)
        }

        private fun showTopSpinner() {
            topSpinner.animate().cancel()
            topSpinner.visibility = View.VISIBLE
            topSpinner.alpha = 1f
            topSpinner.startSpinning()
        }

        private fun hideTopSpinner() {
            topSpinner.animate().cancel()
            topSpinner.visibility = View.GONE
            topSpinner.alpha = 0f
        }

        private fun showBottomLoader(show: Boolean) {
            bottomSpinner.visibility = if (show) View.VISIBLE else View.GONE
            if (show) bottomSpinner.startSpinning()
        }

        private fun showTopNotice(text: String, clickable: Boolean, action: (() -> Unit)? = null) {
            notice.removeCallbacks(hideNotice)
            notice.animate().cancel()
            notice.alpha = 1f
            notice.text = text
            notice.isClickable = clickable
            notice.setOnClickListener(if (clickable && action != null) View.OnClickListener { action() } else null)
            notice.visibility = View.VISIBLE
            if (!clickable) notice.postDelayed(hideNotice, NOTICE_DURATION_MS)
        }

        private fun hideNotice() {
            notice.removeCallbacks(hideNotice)
            notice.animate().cancel()
            notice.visibility = View.GONE
            notice.alpha = 1f
        }

        private val hideNotice = Runnable {
            notice.animate().alpha(0f).setDuration(150L).withEndAction {
                notice.visibility = View.GONE
                notice.alpha = 1f
            }.start()
        }
    }

    private inner class PullGridView(context: Context) : GridView(context) {
        var onRefreshRequested: (() -> Unit)? = null
        var onPullChanged: ((Float, Boolean) -> Unit)? = null
        var onScrollDirection: ((Float) -> Unit)? = null
        private var downY = 0f
        private var lastMoveY = 0f
        private var pullDistance = 0f
        private var pulling = false
        private var armed = false
        private var refreshing = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var isScrollingUp = false
            private set

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (refreshing) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    lastMoveY = event.y
                    pullDistance = 0f
                    pulling = false
                    armed = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = lastMoveY - event.y
                    lastMoveY = event.y
                    val distance = (event.y - downY).coerceAtLeast(0f)
                    if (pulling || (isAtTop() && distance > touchSlop)) {
                        pulling = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        pullDistance = distance
                        armed = pullDistance >= dp(PULL_REFRESH_DISTANCE)
                        translationY = minOf(pullDistance * PULL_DRAG_RATIO, dp(PULL_MAX_OFFSET).toFloat())
                        onPullChanged?.invoke(translationY, armed)
                        return true
                    } else if (kotlin.math.abs(deltaY) > dp(2)) {
                        isScrollingUp = deltaY > 0f
                        onScrollDirection?.invoke(deltaY)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (pulling) {
                        if (armed) {
                            setRefreshing(true)
                            onRefreshRequested?.invoke()
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
                translationY = dp(PULL_REFRESH_HOLD).toFloat()
                onPullChanged?.invoke(translationY, true)
            } else if (translationY != 0f) {
                resetPull()
            }
        }

        private fun resetPull() {
            pulling = false
            armed = false
            pullDistance = 0f
            animate().cancel()
            animate().translationY(0f).setDuration(180L).setInterpolator(MOTION_EASE).start()
            onPullChanged?.invoke(0f, false)
        }

        private fun isAtTop(): Boolean =
            adapter?.isEmpty != false || firstVisiblePosition == 0 && (getChildAt(0)?.top ?: paddingTop) >= paddingTop
    }

    private inner class VideoAdapter(private val context: Context) : BaseAdapter() {
        private val videos = ArrayList<VideoItem>()
        private val ids = HashSet<String>()
        private val pendingAnimations = HashSet<String>()
        val reduced = MotionTokens.isReduced(context)
        private val cols = gridColumns
        private val cardWidth = VideoCardUi.cardWidth(context, cols, feedAvailableWidthPx)
        // 平板端采用紧凑 16:9 封面 + 62dp 底部元信息区，确保 1920x1200 横屏下清晰呈现 3 行 × 4 列 = 12 张视频卡片
        private val coverHeight = VideoCardUi.coverHeight(cardWidth, compactTablet = isTabletLayout)
        private val cardHeight = VideoCardUi.cardHeight(coverHeight, context, compactTablet = isTabletLayout)

        override fun getCount(): Int = videos.size
        override fun getItem(position: Int): VideoItem = videos[position]
        override fun getItemId(position: Int): Long = videos[position].bvid.hashCode().toLong()
        override fun hasStableIds(): Boolean = true

        fun itemOrNull(position: Int): VideoItem? = videos.getOrNull(position)
        fun indexOf(bvid: String?): Int = videos.indexOfFirst { it.bvid == bvid }
        fun snapshot(): List<VideoItem> = videos.toList()

        fun newItems(items: List<VideoItem>): List<VideoItem> {
            val seen = HashSet<String>(items.size)
            return items.filter { it.bvid !in ids && seen.add(it.bvid) }
        }

        fun replace(items: List<VideoItem>, animateNew: Boolean) {
            videos.clear()
            videos.addAll(items)
            pendingAnimations.clear()
            if (animateNew && !reduced) {
                items.filter { it.bvid !in ids }.mapTo(pendingAnimations) { it.bvid }
            }
            ids.clear()
            items.mapTo(ids) { it.bvid }
            notifyDataSetChanged()
        }

        /** Appends a real page and trims only old history when the window grows too large. */
        fun appendWindowed(items: List<VideoItem>): Int {
            if (items.isEmpty()) return 0
            val oldSize = videos.size
            videos.addAll(items)
            items.mapTo(ids) { it.bvid }
            if (!reduced) items.mapTo(pendingAnimations) { it.bvid }
            val removed = FeedPagingPolicy.trimFromStart(oldSize, items.size)
            if (removed > 0) {
                val dropped = videos.subList(0, removed)
                for (item in dropped) {
                    ids.remove(item.bvid)
                    pendingAnimations.remove(item.bvid)
                }
                dropped.clear()
            }
            notifyDataSetChanged()
            return removed
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val card: LinearLayout
            val holder: VideoCardUi.Holder
            if (convertView == null) {
                card = VideoCardUi.createCard(context, cardWidth, coverHeight, cardHeight)
                holder = card.tag as VideoCardUi.Holder
            } else {
                card = convertView as LinearLayout
                holder = card.tag as VideoCardUi.Holder
            }
            val video = videos[position]
            VideoCardUi.bind(card, holder, video)
            if (pendingAnimations.remove(video.bvid)) {
                card.alpha = 0f
                card.translationY = dp(10).toFloat()
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((position % 2) * 28L)
                    .setDuration(220L)
                    .setInterpolator(MOTION_EASE)
                    .start()
            }
            return card
        }
    }

    private fun scrollIdKey(slot: Int): String = "home_scroll_bvid_$slot"

    private fun scrollPositionKey(slot: Int): String = "$STATE_SCROLL_POSITION_PREFIX$slot"

    private fun scrollOffsetKey(slot: Int): String = "$STATE_SCROLL_OFFSET_PREFIX$slot"

    private companion object {
        const val TAB_RECOMMEND = 0
        const val TAB_POPULAR = 1
        const val CARD_SPACING_DP = 5
        const val CARD_SHADOW_INSET_DP = 2
        const val SEARCH_BOX_WIDTH_DP = 224
        const val HOME_SEARCH_WIDTH = SEARCH_BOX_WIDTH_DP
        const val HEADER_HEIGHT_DP = 40
        const val SEARCH_BOX_HEIGHT_DP = 40
        /** 品牌徽标边长：与搜索框（40dp）视觉重量匹配，比原 26dp 略大。 */
        const val BRAND_MARK_DP = 32
        const val SEARCH_BOX_CORNER_DP = 20
        const val SEARCH_BOX_HORIZONTAL_PADDING_DP = 14
        const val SEARCH_BOX_TEXT_SIZE_SP = 14f
        const val SEARCH_BOX_TRANSITION_NAME = "yui_search_box"
        const val TAB_SWITCH_DURATION_MS = 190L
        const val TAB_INDICATOR_WIDTH = 28
        const val TAB_PANEL_SHIFT = 18
        const val STATE_SELECTED_TAB = "home_selected_tab"
        const val STATE_SCROLL_POSITION_PREFIX = "home_scroll_position_"
        const val STATE_SCROLL_OFFSET_PREFIX = "home_scroll_offset_"
        const val LOAD_AHEAD_ITEMS = 4
        const val PULL_REFRESH_DISTANCE = 56
        const val PULL_REFRESH_HOLD = 38
        const val PULL_MAX_OFFSET = 56
        const val PULL_DRAG_RATIO = 0.42f
        const val NOTICE_DURATION_MS = 1_600L
        const val REFRESH_BUDGET_MS = 4_500L
        val MOTION_EASE = MotionTokens.easeOut
    }
}

internal fun homeTabIndicatorOffset(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress.coerceIn(0f, 1f)

/**
 * Global UI theme. Switchable at runtime; all COLOR_* constants follow it.
 */
object Theme {
    const val PREFS = "yuibili_settings"
    const val KEY_DARK = "dark_mode"

    @Volatile
    var isDark: Boolean = false
        private set

    /**
     * One-shot circular theme transition: the outgoing screenshot is drawn as a
     * full-screen overlay and then revealed/collapsed around [cx]/[cy].
     * Cleared after the animation finishes (or if no Activity consumes it).
     */
    @Volatile
    var pendingTransitionBitmap: android.graphics.Bitmap? = null
        private set
    @Volatile
    var pendingTransitionCx: Float = 0f
        private set
    @Volatile
    var pendingTransitionCy: Float = 0f
        private set
    @Volatile
    var pendingTransitionExpand: Boolean = true
        private set

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isDark = prefs.getBoolean(KEY_DARK, false)
        LayoutMode.init(context)
    }

    fun setDark(context: Context, dark: Boolean) {
        isDark = dark
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, dark).apply()
    }

    fun prepareCircularTransition(
        snapshot: android.graphics.Bitmap,
        cx: Float,
        cy: Float,
        expand: Boolean
    ) {
        pendingTransitionBitmap?.recycle()
        pendingTransitionBitmap = snapshot
        pendingTransitionCx = cx
        pendingTransitionCy = cy
        pendingTransitionExpand = expand
    }

    internal data class TabletFade(val bitmap: android.graphics.Bitmap, val fromDark: Boolean)
    private var pendingTabletFade: TabletFade? = null

    internal fun prepareTabletFade(bitmap: android.graphics.Bitmap, fromDark: Boolean) {
        pendingTabletFade?.bitmap?.recycle()
        pendingTabletFade = TabletFade(bitmap, fromDark)
    }

    internal fun consumeTabletFade(): TabletFade? = pendingTabletFade.also { pendingTabletFade = null }
    internal fun hasTabletFade(): Boolean = pendingTabletFade != null

    fun peekCircularTransition(): Boolean =
        pendingTransitionBitmap != null

    fun consumeCircularTransition(): Triple<android.graphics.Bitmap, android.util.Pair<Float, Float>, Boolean>? {
        val bmp = pendingTransitionBitmap ?: return null
        pendingTransitionBitmap = null
        return Triple(
            bmp,
            android.util.Pair(pendingTransitionCx, pendingTransitionCy),
            pendingTransitionExpand
        )
    }

    /**
     * Status / navigation bar chrome.
     * - lightContent=true: black status strip for pure black player chrome.
     * - otherwise: themed canvas color, with light/dark icons matching the theme.
     * Fullscreen player should call its own immersive helpers instead.
     */
    fun applySystemBars(activity: android.app.Activity, lightContent: Boolean = false) {
        val window = activity.window
        window.statusBarColor = if (lightContent) android.graphics.Color.BLACK else COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        @Suppress("DEPRECATION")
        var flags = window.decorView.systemUiVisibility and
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
            0x00000010.inv()
        if (!isDark) {
            // Dark icons on light canvas. 0x10 = LIGHT_NAVIGATION_BAR (API 26+).
            if (!lightContent) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            flags = flags or 0x00000010
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }
}

internal val COLOR_BACKGROUND: Int get() = Ds.colors.bg
internal val COLOR_CARD: Int get() = Ds.colors.surface
/** Elevated surfaces (search boxes, floating docks, dialogs). */
internal val COLOR_SURFACE: Int get() = Ds.colors.surfaceRaised
internal val COLOR_INK: Int get() = Ds.colors.text
internal val COLOR_MUTED: Int get() = Ds.colors.muted
internal val COLOR_ROSE: Int get() = Ds.colors.accent
internal val COLOR_ROSE_SOFT: Int get() = Ds.colors.accentSoft
internal val COLOR_RED_SOFT: Int get() = Ds.colors.dangerSoft
internal val COLOR_BORDER: Int get() = Ds.colors.lineSoft
internal val COLOR_CARD_BORDER: Int get() = Ds.colors.lineSoft
internal val COLOR_COVER: Int get() = Ds.colors.cover
internal val COLOR_NOTICE: Int get() = Ds.colors.surfaceRaised
internal val COLOR_SWITCH_TRACK_OFF: Int get() = Ds.colors.lineSoft
internal val COLOR_NAV_INACTIVE: Int get() = Ds.colors.muted
internal val COLOR_NAV_FILL: Int get() = Ds.colors.bg
internal val COLOR_NAV_STROKE: Int get() = Ds.colors.line
/** Soft chip / avatar / skeleton fill that stays visible on both themes. */
internal val COLOR_SOFT_FILL: Int get() = if (Ds.colors.isDark) 0x1FFFFFFF else 0x12000000
internal val COLOR_SOFT_FILL_STRONG: Int get() = if (Ds.colors.isDark) 0x33FFFFFF else 0x22000000
internal val COLOR_FOLLOWED_CHIP: Int get() = if (Ds.colors.isDark) 0x24FFFFFF else 0x14000000
internal val COLOR_ON_ROSE: Int get() = Ds.colors.onAccent

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
internal fun View.dp(value: Int): Int = context.dp(value)

internal fun rounded(fill: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setShape(GradientDrawable.RECTANGLE)
        setColor(fill)
        setCornerRadius(radius.toFloat())
        if (stroke != null && strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

internal fun formatDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val remainder = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%d:%02d".format(minutes, remainder)
}

internal fun formatViews(value: Long): String = when {
    value >= 100_000_000L -> compactCount(value / 100_000_000.0) + "亿"
    value >= 10_000L -> compactCount(value / 10_000.0) + "万"
    value > 0L -> value.toString()
    else -> "--"
}

/** 万/亿级紧凑计数：整万显示 "1"，非整万保留一位小数，如 "1.2"。 */
private fun compactCount(raw: Double): String {
    val rounded = Math.round(raw * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString()
    else String.format(Locale.CHINA, "%.1f", rounded)
}

private val publishedDateFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }
}

internal fun formatPublishedDate(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "日期未知"
    return requireNotNull(publishedDateFormat.get()).format(Date(epochSeconds * 1_000L))
}
