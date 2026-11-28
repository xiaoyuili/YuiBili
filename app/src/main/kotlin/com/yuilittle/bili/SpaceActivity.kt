package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * 个人主页（用户空间）：
 * - 极简顶栏：返回 + 上滑渐显的头像/昵称（点击回到顶部）+ 空间内搜索按钮（绝无分享入口）
 * - 共享单份资料头卡（头像/昵称/LV/大会员/UID点击复制/可展开签名/粉丝·关注·获赞/关注按钮）随列表向上滚动自然折叠
 * - 吸顶「动态 | 投稿」双标签栏 + 玫瑰粉胶囊滑块实时跟随左右手势切换；投稿页右侧挂排序胶囊
 * - 支持双列视频骨架屏、下拉刷新（同时平滑刷新资料与当前标签）、关注状态与全站实时同步
 */
class SpaceActivity : Activity() {

    private var mid = 0L
    private var initialName = ""
    private var initialFace = ""
    private var currentHeader: SpaceHeader? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var headerRequest: BiliRequest? = null
    private var liveStatusRequest: BiliRequest? = null
    private var currentLiveStatus: LiveRoomStatus? = null
    private var dynamicRequest: BiliRequest? = null
    private var videoRequest: BiliRequest? = null
    private var followInFlight = false

    private lateinit var snackHost: FrameLayout
    private lateinit var topButton: ScrollToTopButton
    /** 动态列表回顶驱动器（近场 7：动态卡片高，避免回滚途经大量卡片）。 */
    private var dynamicTopScroller: AbsListSmoothTopScroller? = null
    /** 投稿列表回顶驱动器（近场 12：单列横卡）。 */
    private var videoTopScroller: AbsListSmoothTopScroller? = null
    private lateinit var topBar: LinearLayout
    private lateinit var topBarDivider: View
    private lateinit var topBarIdentity: LinearLayout
    private lateinit var topBarAvatar: ImageView
    private lateinit var topBarTitle: TextView
    private lateinit var heroCard: SpaceHeroCard
    private lateinit var tabStrip: SpaceTabStrip
    private lateinit var pager: RootPager
    private var coordinator: SpaceCoordinatorLayout? = null
    private var isTabletSplit = false
    private lateinit var refreshIndicator: BrandSpinnerView
    private var pullRefreshing = false

    private var activeTab = 0

    // 动态页（手机单列 ListView / 平板双列真瀑布流）
    private var dynamicListView: ListView? = null
    private var dynamicMasonryView: SpaceMasonryDynamicView? = null
    private var dynamicAdapter: DynamicListAdapter? = null
    private lateinit var dynamicFooter: LinearLayout
    private lateinit var dynamicFooterSpinner: BrandSpinnerView
    private lateinit var dynamicFooterText: TextView
    private lateinit var dynamicStateView: SpaceStateView
    private val dynamicItems = ArrayList<SpaceDynamic>()
    private val dynamicSeenIds = HashSet<String>()
    private var dynamicOffset = ""
    private var dynamicHasMore = true
    private var dynamicLoading = false
    private var dynamicLoadedOnce = false
    private var dynamicLastError: String? = null

    // 投稿页
    private lateinit var videoListView: ListView
    private lateinit var videoAdapter: SpaceVideoAdapter
    private lateinit var videoFooter: LinearLayout
    private lateinit var videoFooterSpinner: BrandSpinnerView
    private lateinit var videoFooterText: TextView
    private lateinit var videoStateView: SpaceStateView
    private lateinit var videoSkeletonOverlay: FrameLayout
    private val videoItems = ArrayList<SpaceVideo>()
    private val videoSeenIds = HashSet<String>()
    private var videoPage = 0
    private var videoHasMore = true
    private var videoLoading = false
    private var videoLoadedOnce = false
    private var videoTotalCount: Long? = null
    private var videoLastError: String? = null
    private var currentOrder = SpaceVideoOrder.PUBDATE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Theme.applySystemBars(this)
        PlayerSettingsStore.applyHomeOrientation(this)
        isTabletSplit = LayoutMode.isWideScreen(this)

        mid = intent.getLongExtra(EXTRA_MID, 0L)
        initialName = intent.getStringExtra(EXTRA_NAME).orEmpty().trim()
        initialFace = intent.getStringExtra(EXTRA_FACE).orEmpty().trim()
        if (mid <= 0L) {
            finish()
            return
        }
        restoreState(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(COLOR_BACKGROUND) }
        val topTint = if (Theme.isDark) 0xFF1E191E.toInt() else 0xFFFBF3F6.toInt()
        window?.statusBarColor = topTint

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            buildTopBar(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        )
        topBarDivider = View(this).apply {
            setBackgroundColor(Ds.colors.lineSoft)
            alpha = 0f
        }
        column.addView(
            topBarDivider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this))
        )

        val stage = FrameLayout(this).apply { clipChildren = true }

        heroCard = SpaceHeroCard(this).apply {
            onAvatarClick = {
                val url = currentHeader?.face?.takeIf { it.isNotBlank() } ?: initialFace
                if (url.isNotBlank()) {
                    ImagePreviewDialog.show(this@SpaceActivity, listOf(url), 0) { msg -> showFeedback(msg) }
                }
            }
            onFollowClick = { handleFollowTap() }
            onLiveClick = { openLiveRoom() }
            onFeedback = { msg -> showFeedback(msg) }
        }

        tabStrip = SpaceTabStrip(this, listOf("动态", "投稿")).apply {
            onTabSelected = { index -> pager.showPage(index, animate = true) }
            onActionClick = { anchor ->
                val entries = SpaceVideoOrder.entries
                showAnchoredMenu(anchor, entries.map { it.label }, entries.indexOf(currentOrder)) { picked ->
                    val next = entries.getOrNull(picked) ?: return@showAnchoredMenu
                    if (next == currentOrder) return@showAnchoredMenu
                    currentOrder = next
                    updateTabSortChip()
                    refreshVideos(forceRefresh = false, fromPull = false)
                }
            }
        }

        pager = RootPager(this).apply {
            setOnPageScrollListener { progress -> tabStrip.setProgress(progress) }
            setOnPageRevealListener { index -> ensureTabLoaded(index) }
            setOnPageSelectedListener { index ->
                val previous = activeTab
                activeTab = index
                tabStrip.setSelected(index)
                updateTabSortChip()
                ensureTabLoaded(index)
                if (previous != index) {
                    // 切页时停掉上一页的回顶动画，再按新页当前位置刷新按钮显隐
                    if (previous == 0) dynamicTopScroller?.cancel() else videoTopScroller?.cancel()
                    if (index == 0 && dynamicMasonryView != null) {
                        val pos = (dynamicMasonryView!!.scrollY / dp(180).coerceAtLeast(1))
                        updateTopButton(pos, showAt = 3, hideAt = 1)
                    } else {
                        val list = if (index == 0) dynamicListView else videoListView
                        val showAt = if (index == 0) 3 else 5
                        val hideAt = if (index == 0) 1 else 2
                        updateTopButton(list?.firstVisiblePosition ?: 0, showAt, hideAt)
                    }
                }
            }
        }
        pager.addPage(buildDynamicPage())
        pager.addPage(buildVideoPage())

        // 手机和平板统一接入 SpaceCoordinatorLayout：上滑平滑折叠顶部资料卡，
        // 并在顶栏返回按钮后方淡入 UP 主头像与昵称，同时保留顶部弹性下拉刷新。
        val coord = SpaceCoordinatorLayout(this, heroCard, tabStrip, pager).apply {
            activeListProvider = {
                if (activeTab == 0) dynamicMasonryView ?: dynamicListView else videoListView
            }
            onCollapseProgressChanged = { ratio -> updateTopBarIdentity(ratio) }
            onPullChanged = { distance, armed -> updatePullIndicator(distance, armed) }
            onRefreshRequested = { handlePullRefresh() }
        }
        coordinator = coord
        stage.addView(
            coord,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        refreshIndicator = BrandSpinnerView(this, COLOR_ROSE, Color.WHITE).apply {
            visibility = View.INVISIBLE
            alpha = 0f
        }
        stage.addView(
            refreshIndicator,
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(12)
            }
        )

        column.addView(
            stage,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        topButton = ScrollToTopButton(this) { scrollCurrentTabToTop() }
        root.addView(
            topButton,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(16)
                bottomMargin = dp(24)
            }
        )

        snackHost = FrameLayout(this).apply { isClickable = false }
        root.addView(
            snackHost,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                bottomMargin = dp(20)
                leftMargin = dp(16)
                rightMargin = dp(16)
            }
        )

        setContentView(root)

        updateTabSortChip()
        topBarTitle.text = currentHeader?.name ?: initialName.ifBlank { "哔哩哔哩用户" }
        if (initialFace.isNotBlank()) {
            CoverLoader.load(topBarAvatar, initialFace, cropThumb = false, thumbEdge = 96, animate = false)
        }
        tabStrip.post { tabStrip.setProgress(activeTab.toFloat()) }
        pager.showPage(activeTab, animate = false)

        if (currentHeader != null) {
            bindHeader(currentHeader!!)
        } else {
            heroCard.bindPlaceholder(mid, initialName, initialFace)
            loadHeader(forceRefresh = false)
        }
        ensureTabLoaded(activeTab)
    }

    private fun restoreState(state: Bundle?) {
        if (state == null) return
        activeTab = state.getInt(STATE_TAB, 0).coerceIn(0, 1)
        val orderName = state.getString(STATE_ORDER)
        currentOrder = SpaceVideoOrder.entries.firstOrNull { it.name == orderName } ?: SpaceVideoOrder.PUBDATE
        val name = state.getString(STATE_NAME) ?: return
        currentHeader = SpaceHeader(
            mid = mid,
            name = name,
            face = state.getString(STATE_FACE),
            sign = state.getString(STATE_SIGN),
            level = state.getInt(STATE_LEVEL, -1).takeIf { it >= 0 },
            vipLabel = state.getString(STATE_VIP),
            follower = state.getLong(STATE_FOLLOWER, -1L).takeIf { it >= 0L },
            following = state.getLong(STATE_FOLLOWING, -1L).takeIf { it >= 0L },
            likes = state.getLong(STATE_LIKES, -1L).takeIf { it >= 0L },
            followState = FollowState.UNKNOWN,
            isSelf = state.getBoolean(STATE_SELF, false)
        )
    }

    private fun buildTopBar(): LinearLayout {
        val topTone = if (Theme.isDark) 0xFF1E191E.toInt() else 0xFFFBF3F6.toInt()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.Space.sm), dp(6), dp(Ds.Space.sm), dp(6))
            setBackgroundColor(topTone)
        }
        topBar = bar
        bar.addView(
            Ds.backIcon(this, COLOR_INK) { spaceFinishWithAnim() },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )

        topBarIdentity = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0f
            translationY = dp(6).toFloat()
            setPadding(dp(4), 0, dp(8), 0)
            isClickable = true
            setOnClickListener { scrollCurrentTabToTop() }
        }
        topBarAvatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ds.circle(COLOR_SOFT_FILL)
            Ds.clipRounded(this, 999)
        }
        topBarIdentity.addView(
            topBarAvatar,
            LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(8) }
        )
        topBarTitle = TextView(this).apply {
            textSize = 15.5f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        topBarIdentity.addView(
            topBarTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        bar.addView(topBarIdentity, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        val searchBtn = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "搜索该用户的动态与投稿"
            background = Ds.circle(COLOR_CARD, Ds.colors.lineSoft, Ds.hairline(this@SpaceActivity))
            Ds.bindPressFeedback(this, 0.90f)
            addView(
                Ds.searchIcon(this@SpaceActivity, COLOR_INK, 1.85f),
                FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER)
            )
            setOnClickListener {
                SpaceSearchActivity.open(
                    context = this@SpaceActivity,
                    mid = mid,
                    upName = currentHeader?.name ?: initialName,
                    initialTab = activeTab
                )
            }
        }
        bar.addView(
            searchBtn,
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(4) }
        )
        return bar
    }

    private fun updateTopBarIdentity(collapseRatio: Float) {
        val topTint = if (Theme.isDark) 0xFF1E191E.toInt() else 0xFFFBF3F6.toInt()
        val bgProgress = (collapseRatio / 0.7f).coerceIn(0f, 1f)
        val blended = blendColor(topTint, COLOR_CARD, bgProgress)
        topBar.setBackgroundColor(blended)
        window?.statusBarColor = blended
        topBarDivider.alpha = ((collapseRatio - 0.65f) / 0.35f).coerceIn(0f, 1f)

        val visibleAlpha = ((collapseRatio - 0.55f) / 0.35f).coerceIn(0f, 1f)
        topBarIdentity.alpha = visibleAlpha
        topBarIdentity.translationY = (1f - visibleAlpha) * dp(6)
        topBarIdentity.isClickable = visibleAlpha > 0.25f
    }

    private fun blendColor(start: Int, end: Int, t: Float): Int {
        val c = t.coerceIn(0f, 1f)
        val a = (Color.alpha(start) + (Color.alpha(end) - Color.alpha(start)) * c).toInt()
        val r = (Color.red(start) + (Color.red(end) - Color.red(start)) * c).toInt()
        val g = (Color.green(start) + (Color.green(end) - Color.green(start)) * c).toInt()
        val b = (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * c).toInt()
        return Color.argb(a, r, g, b)
    }

    /** 当前 Tab 快速平滑回顶；已在顶部时只展开头卡。 */
    private fun scrollCurrentTabToTop() {
        if (activeTab == 0 && dynamicMasonryView != null) {
            val m = dynamicMasonryView!!
            if (m.scrollY > 0) {
                m.smoothScrollTo(0, 0)
                m.postDelayed({ coordinator?.expandHeader(animate = true) }, 140L)
            } else {
                coordinator?.expandHeader(animate = true)
            }
            return
        }
        val started = if (activeTab == 0) {
            dynamicTopScroller?.scrollToTop() == true
        } else {
            videoTopScroller?.scrollToTop() == true
        }
        if (!started) coordinator?.expandHeader(animate = true)
    }

    /** 迟滞显隐：避免临界位置反复抽搐。 */
    private fun updateTopButton(firstVisible: Int, showAt: Int, hideAt: Int) {
        // setOnScrollListener 会立即回调一次 onScroll，此时按钮尚未创建
        if (!::topButton.isInitialized) return
        val visible = nextScrollTopVisible(
            currentlyVisible = topButton.visibility == View.VISIBLE,
            firstVisiblePosition = firstVisible,
            showAt = showAt,
            hideAt = hideAt
        )
        topButton.setShown(visible)
    }

    private fun buildDynamicPage(): View {
        val host = FrameLayout(this)
        dynamicFooter = buildFooter { loadMoreDynamics() }
        dynamicFooterSpinner = dynamicFooter.getChildAt(0) as BrandSpinnerView
        dynamicFooterText = dynamicFooter.getChildAt(1) as TextView

        if (isTabletSplit) {
            // 平板端采用双列真瀑布流：图文高卡旁的短视频卡下方紧接下一条动态，无大片空洞
            val masonry = SpaceMasonryDynamicView(this).apply {
                setFooter(dynamicFooter)
                onScrollYChanged = { sy ->
                    if (activeTab == 0) {
                        updateTopButton(sy / dp(180).coerceAtLeast(1), showAt = 3, hideAt = 1)
                    }
                }
                onNearBottom = {
                    if (!dynamicLoading && dynamicHasMore && dynamicItems.isNotEmpty()) {
                        loadMoreDynamics()
                    }
                }
            }
            dynamicMasonryView = masonry
            host.addView(
                masonry,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            val adapterObj = DynamicListAdapter()
            dynamicAdapter = adapterObj
            val list = ListView(this).apply {
                divider = null
                dividerHeight = 0
                setSelector(android.R.color.transparent)
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setPadding(0, 0, 0, dp(28))
                clipToPadding = false
                adapter = adapterObj
                setRecyclerListener { recycled -> SpaceDynamicCardUi.clearImages(recycled) }
            }
            dynamicListView = list
            dynamicTopScroller = AbsListSmoothTopScroller(
                list, nearPosition = 7,
                onSettled = { coordinator?.expandHeader(animate = true) }
            )
            list.setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(
                    view: AbsListView?,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int
                ) {
                    if (activeTab == 0) {
                        updateTopButton(firstVisibleItem, showAt = 3, hideAt = 1)
                    }
                    if (!dynamicLoading && dynamicHasMore && dynamicItems.isNotEmpty() &&
                        dynamicTopScroller?.isRunning != true &&
                        firstVisibleItem + visibleItemCount >= totalItemCount - 3
                    ) {
                        loadMoreDynamics()
                    }
                }
            })
            list.addFooterView(dynamicFooter, null, false)
            host.addView(
                list,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        dynamicStateView = SpaceStateView(this)
        host.addView(
            dynamicStateView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(18) }
        )
        return host
    }

    private fun refreshDynamicDisplay(forceRebuild: Boolean = false) {
        dynamicAdapter?.notifyDataSetChanged()
        dynamicMasonryView?.syncItems(
            items = dynamicItems,
            forceRebuild = forceRebuild,
            onOpenDetail = { dynamic -> SpaceDynamicDetailActivity.open(this@SpaceActivity, dynamic) },
            onOpenVideo = { bvid, aid -> openVideoByMeta(bvid, aid) },
            onFeedback = { msg -> showFeedback(msg) }
        )
    }

    private fun buildVideoPage(): View {
        val host = FrameLayout(this)
        videoAdapter = SpaceVideoAdapter()
        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(14), dp(10), dp(14), dp(28))
            clipToPadding = false
            adapter = videoAdapter
            setRecyclerListener { recycled -> SpaceVideoRowUi.clearImages(recycled) }
        }
        videoListView = list
        videoTopScroller = AbsListSmoothTopScroller(
            list, nearPosition = 12,
            onSettled = { coordinator?.expandHeader(animate = true) }
        )
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                if (activeTab == 1) {
                    updateTopButton(firstVisibleItem, showAt = 5, hideAt = 2)
                }
                if (!videoLoading && videoHasMore && videoItems.isNotEmpty() &&
                    videoTopScroller?.isRunning != true &&
                    firstVisibleItem + visibleItemCount >= totalItemCount - 6
                ) {
                    loadMoreVideos()
                }
            }
        })

        videoFooter = buildFooter { loadMoreVideos() }
        videoFooterSpinner = videoFooter.getChildAt(0) as BrandSpinnerView
        videoFooterText = videoFooter.getChildAt(1) as TextView
        list.addFooterView(videoFooter, null, false)

        host.addView(
            list,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        videoSkeletonOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = false
            addView(SpaceVideoRowUi.createSkeletonLayout(this@SpaceActivity, this))
        }
        host.addView(
            videoSkeletonOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        videoStateView = SpaceStateView(this)
        host.addView(
            videoStateView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(18) }
        )
        return host
    }

    private fun buildFooter(retry: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(24))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { retry() }
            addView(
                BrandSpinnerView(this@SpaceActivity, COLOR_ROSE, Color.WHITE).apply {
                    visibility = View.GONE
                },
                LinearLayout.LayoutParams(dp(18), dp(18))
            )
            addView(TextView(this@SpaceActivity).apply {
                textSize = 12f
                typeface = Ds.body()
                setTextColor(COLOR_MUTED)
                setPadding(dp(8), 0, 0, 0)
                includeFontPadding = false
            })
        }

    private fun updatePullIndicator(distance: Float, armed: Boolean) {
        if (distance <= 0.5f) {
            if (!pullRefreshing) {
                refreshIndicator.visibility = View.INVISIBLE
                refreshIndicator.alpha = 0f
            }
            return
        }
        refreshIndicator.visibility = View.VISIBLE
        val progress = (distance / dp(56).toFloat()).coerceIn(0f, 1.15f)
        refreshIndicator.alpha = if (armed) 1f else progress.coerceAtMost(1f)
        refreshIndicator.translationY = (distance * 0.25f).coerceAtMost(dp(18).toFloat())
        if (!pullRefreshing) {
            refreshIndicator.setPullProgress(progress)
        }
    }

    private fun setPullRefreshing(refreshing: Boolean) {
        pullRefreshing = refreshing
        coordinator?.setRefreshing(refreshing)
        if (refreshing) {
            refreshIndicator.visibility = View.VISIBLE
            refreshIndicator.alpha = 1f
            refreshIndicator.translationY = dp(10).toFloat()
            refreshIndicator.startSpinning()
        } else {
            refreshIndicator.alpha = 0f
            refreshIndicator.visibility = View.INVISIBLE
        }
    }

    private fun handlePullRefresh() {
        setPullRefreshing(true)
        if (activeTab == 0) {
            refreshDynamics(forceRefresh = true, fromPull = true)
        } else {
            refreshVideos(forceRefresh = true, fromPull = true)
        }
        // 资料卡顺带刷新，但不与主列表争夺首帧回调；如果资料还没拿到也强制拉一次
        if (currentHeader == null || activeTab == 0) {
            loadHeader(forceRefresh = true)
        }
        loadLiveStatus(force = true)
    }

    private fun updateTabSortChip() {
        tabStrip.setActionVisible(activeTab == 1)
        tabStrip.setActionLabel(currentOrder.label)
    }

    private fun loadHeader(forceRefresh: Boolean) {
        headerRequest?.cancel()
        headerRequest = BiliSpaceApi.fetchSpaceHeader(
            mid = mid,
            forceRefresh = forceRefresh,
            onFastReady = { fast ->
                if (isFinishing || isDestroyed) return@fetchSpaceHeader
                bindHeader(fast)
            }
        ) { header, error ->
            if (isFinishing || isDestroyed) return@fetchSpaceHeader
            if (header != null) {
                bindHeader(header)
            } else if (error != null && currentHeader == null) {
                showFeedback(error)
                // 资料头失败时直播状态也不会有结果，避免按钮长期停在「查询中…」
                heroCard.renderLiveState(live = false)
            }
        }
    }

    private fun bindHeader(header: SpaceHeader) {
        currentHeader = header
        topBarTitle.text = header.name
        val face = header.face?.takeIf { it.isNotBlank() } ?: initialFace
        if (face.isNotBlank()) {
            CoverLoader.load(topBarAvatar, face, cropThumb = false, thumbEdge = 96, animate = false)
        }
        heroCard.bindHeader(header, initialFace)
        // 直播状态与资料头分开请求：直播接口偶发失败不应影响资料展示。
        if (currentLiveStatus == null) loadLiveStatus()
    }

    private fun loadLiveStatus(force: Boolean = false) {
        if (mid <= 0L) return
        if (!force && liveStatusRequest != null) return
        liveStatusRequest?.cancel()
        heroCard.renderLiveState(live = currentLiveStatus?.live == true, checking = currentLiveStatus == null)
        liveStatusRequest = LiveApi.fetchRoomStatus(mid) { status, _ ->
            if (isFinishing || isDestroyed) return@fetchRoomStatus
            currentLiveStatus = status
            heroCard.renderLiveState(live = status?.live == true)
        }
    }

    private fun openLiveRoom() {
        val status = currentLiveStatus
        if (status == null || !status.live || status.roomId <= 0L) {
            showFeedback("主播暂未开播")
            return
        }
        LiveRoomActivity.open(
            this,
            LiveRoom(
                roomId = status.roomId,
                uid = mid,
                title = status.title,
                owner = currentHeader?.name.orEmpty(),
                cover = status.cover,
                face = currentHeader?.face.orEmpty(),
                areaName = "",
                online = status.online
            )
        )
    }

    private fun handleFollowTap() {
        val header = currentHeader
        if (header?.isSelf == true || header?.followState == FollowState.SELF) return
        if (!BiliSessionStore.isLoggedIn()) {
            showFeedback("请先登录后再关注")
            return
        }
        if (followInFlight) return
        val targetFollow = header?.followState != FollowState.FOLLOWED
        followInFlight = true
        val prevState = header?.followState ?: FollowState.UNKNOWN
        val nextState = if (targetFollow) FollowState.FOLLOWED else FollowState.NOT_FOLLOWED

        if (header != null) {
            val delta = if (targetFollow) 1L else -1L
            val nextFollowers = header.follower?.let { (it + delta).coerceAtLeast(0L) }
            val updated = header.copy(followState = nextState, follower = nextFollowers)
            currentHeader = updated
            heroCard.bindHeader(updated, initialFace)
        }
        LikeStateBridge.rememberFollow(mid, targetFollow)

        BiliSpaceApi.modifyRelationForSpace(mid, targetFollow) { ok, err ->
            followInFlight = false
            if (isFinishing || isDestroyed) return@modifyRelationForSpace
            if (ok) {
                showFeedback(if (targetFollow) "已关注" else "已取消关注")
            } else {
                LikeStateBridge.rememberFollow(mid, prevState == FollowState.FOLLOWED)
                if (header != null) {
                    val rollback = header.copy(followState = prevState)
                    currentHeader = rollback
                    heroCard.bindHeader(rollback, initialFace)
                }
                showFeedback(err ?: "操作失败，请稍后再试")
            }
        }
    }

    private fun ensureTabLoaded(index: Int) {
        if (index == 0 && !dynamicLoadedOnce && !dynamicLoading) {
            refreshDynamics(forceRefresh = false, fromPull = false)
        } else if (index == 1 && !videoLoadedOnce && !videoLoading) {
            refreshVideos(forceRefresh = false, fromPull = false)
        }
    }

    // -----------------------------------------------------------------------
    // 动态
    // -----------------------------------------------------------------------

    private fun refreshDynamics(forceRefresh: Boolean, fromPull: Boolean) {
        dynamicRequest?.cancel()
        dynamicLoadedOnce = true
        dynamicLoading = false
        dynamicOffset = ""
        dynamicHasMore = true
        dynamicLastError = null
        if (!fromPull || dynamicItems.isEmpty()) {
            dynamicItems.clear()
            dynamicSeenIds.clear()
            refreshDynamicDisplay(forceRebuild = true)
            dynamicStateView.showLoading("正在加载动态…")
        }
        updateDynamicFooter()
        loadMoreDynamics(forceRefresh = forceRefresh, replaceExisting = fromPull && dynamicItems.isNotEmpty())
    }

    private fun loadMoreDynamics(forceRefresh: Boolean = false, replaceExisting: Boolean = false) {
        if (dynamicLoading || !dynamicHasMore) return
        dynamicLoading = true
        dynamicLastError = null
        updateDynamicFooter()
        val requestOffset = if (replaceExisting) "" else dynamicOffset
        dynamicRequest = BiliSpaceApi.fetchSpaceDynamics(
            mid = mid,
            offset = requestOffset,
            forceRefresh = forceRefresh
        ) { page, error ->
            if (isFinishing || isDestroyed) return@fetchSpaceDynamics
            dynamicLoading = false
            setPullRefreshing(false)
            if (page == null) {
                dynamicLastError = error ?: "动态加载失败"
                if (dynamicItems.isEmpty()) {
                    dynamicStateView.showMessage("动态加载失败", dynamicLastError, "重试") {
                        refreshDynamics(forceRefresh = true, fromPull = false)
                    }
                } else {
                    showFeedback(dynamicLastError!!)
                }
                updateDynamicFooter()
                return@fetchSpaceDynamics
            }
            if (replaceExisting) {
                dynamicItems.clear()
                dynamicSeenIds.clear()
            }
            val added = ArrayList<SpaceDynamic>(page.items.size)
            page.items.forEach { item ->
                if (dynamicSeenIds.add(item.id)) added += item
            }
            dynamicItems.addAll(added)
            dynamicOffset = page.nextOffset.orEmpty()
            dynamicHasMore = page.hasMore && added.isNotEmpty()
            refreshDynamicDisplay(forceRebuild = replaceExisting)
            if (dynamicItems.isEmpty()) {
                dynamicStateView.showMessage("还没有动态", "TA 暂时没有发布公开动态")
            } else {
                dynamicStateView.hide()
            }
            updateDynamicFooter()
        }
    }

    private fun updateDynamicFooter() {
        updateFooter(
            row = dynamicFooter,
            spinner = dynamicFooterSpinner,
            text = dynamicFooterText,
            loading = dynamicLoading,
            hasItems = dynamicItems.isNotEmpty(),
            hasMore = dynamicHasMore,
            error = dynamicLastError,
            endLabel = "没有更多动态了"
        )
    }

    // -----------------------------------------------------------------------
    // 投稿
    // -----------------------------------------------------------------------

    private fun refreshVideos(forceRefresh: Boolean, fromPull: Boolean) {
        videoRequest?.cancel()
        videoLoadedOnce = true
        videoLoading = false
        videoPage = 0
        videoHasMore = true
        videoLastError = null
        if (!fromPull || videoItems.isEmpty()) {
            videoItems.clear()
            videoSeenIds.clear()
            videoTotalCount = null
            videoAdapter.notifyDataSetChanged()
            videoListView.setSelection(0)
            videoStateView.hide()
            VideoCardUi.toggleSkeleton(videoSkeletonOverlay, show = true)
        }
        updateVideoFooter()
        loadMoreVideos(forceRefresh = forceRefresh, replaceExisting = fromPull && videoItems.isNotEmpty())
    }

    private fun loadMoreVideos(forceRefresh: Boolean = false, replaceExisting: Boolean = false) {
        if (videoLoading || !videoHasMore) return
        val nextPage = if (replaceExisting) 1 else videoPage + 1
        videoLoading = true
        videoLastError = null
        updateVideoFooter()
        videoRequest = BiliSpaceApi.fetchSpaceVideos(
            mid = mid,
            page = nextPage,
            order = currentOrder,
            keyword = "",
            forceRefresh = forceRefresh
        ) { page, error ->
            if (isFinishing || isDestroyed) return@fetchSpaceVideos
            videoLoading = false
            setPullRefreshing(false)
            VideoCardUi.toggleSkeleton(videoSkeletonOverlay, show = false)
            if (page == null) {
                videoLastError = error ?: "投稿加载失败"
                if (videoItems.isEmpty()) {
                    videoStateView.showMessage("投稿加载失败", videoLastError, "重试") {
                        refreshVideos(forceRefresh = true, fromPull = false)
                    }
                } else {
                    showFeedback(videoLastError!!)
                }
                updateVideoFooter()
                return@fetchSpaceVideos
            }
            if (replaceExisting) {
                videoItems.clear()
                videoSeenIds.clear()
            }
            videoPage = nextPage
            page.total?.let { total ->
                videoTotalCount = total
                tabStrip.setTabBadge(1, if (total > 0L) BiliSpaceApi.formatNullableCount(total) else null)
            }
            if (page.usedFallback && currentOrder != SpaceVideoOrder.PUBDATE && nextPage == 1) {
                showFeedback("接口受限已降级展示，当前按默认顺序排列")
            }
            var addedCount = 0
            page.items.forEach { sv ->
                val key = sv.bvid ?: sv.aid?.toString() ?: sv.title
                if (videoSeenIds.add(key)) {
                    videoItems += sv
                    addedCount++
                }
            }
            videoHasMore = page.hasMore && addedCount > 0
            videoAdapter.notifyDataSetChanged()
            if (videoItems.isEmpty()) {
                videoStateView.showMessage("还没有投稿视频", "TA 暂时没有公开视频投稿")
            } else {
                videoStateView.hide()
            }
            updateVideoFooter()
        }
    }

    private fun updateVideoFooter() {
        updateFooter(
            row = videoFooter,
            spinner = videoFooterSpinner,
            text = videoFooterText,
            loading = videoLoading,
            hasItems = videoItems.isNotEmpty(),
            hasMore = videoHasMore,
            error = videoLastError,
            endLabel = videoTotalCount?.let { "共 ${BiliSpaceApi.formatNullableCount(it)} 个投稿" }
                ?: "没有更多投稿了"
        )
    }

    private fun updateFooter(
        row: LinearLayout,
        spinner: BrandSpinnerView,
        text: TextView,
        loading: Boolean,
        hasItems: Boolean,
        hasMore: Boolean,
        error: String?,
        endLabel: String
    ) {
        if (!hasItems) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        when {
            loading -> {
                spinner.visibility = View.VISIBLE
                spinner.startSpinning()
                text.text = "正在加载…"
            }
            error != null -> {
                spinner.visibility = View.GONE
                text.text = "$error · 点击重试"
            }
            !hasMore -> {
                spinner.visibility = View.GONE
                text.text = endLabel
            }
            else -> row.visibility = View.GONE
        }
    }

    private fun openVideoByMeta(bvid: String?, aid: Long?) {
        val cleanBvid = bvid?.trim().orEmpty()
        val safeAid = aid ?: 0L
        if (cleanBvid.isBlank() && safeAid <= 0L) {
            showFeedback("无法打开该视频")
            return
        }
        VideoDetailActivity.open(this, cleanBvid, safeAid)
    }

    internal fun showFeedback(message: String) {
        if (isFinishing || isDestroyed || message.isBlank()) return
        snackHost.removeAllViews()
        val bar = TextView(this).apply {
            text = message
            textSize = 13f
            typeface = Ds.body(bold = true)
            setTextColor(0xFFFFFFFF.toInt())
            background = Ds.pill(this@SpaceActivity, if (Theme.isDark) 0xE62A2D36.toInt() else 0xE61E2026.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            gravity = Gravity.CENTER
        }
        snackHost.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL
            )
        )
        bar.alpha = 0f
        bar.translationY = dp(10).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        mainHandler.removeCallbacksAndMessages(snackHost)
        mainHandler.postAtTime({
            if (!isFinishing && !isDestroyed && bar.parent === snackHost) {
                bar.animate().alpha(0f).setDuration(180L).withEndAction {
                    snackHost.removeView(bar)
                }.start()
            }
        }, snackHost, android.os.SystemClock.uptimeMillis() + 2200L)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        spaceFinishWithAnim()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, activeTab)
        outState.putString(STATE_ORDER, currentOrder.name)
        currentHeader?.let { header ->
            outState.putString(STATE_NAME, header.name)
            outState.putString(STATE_FACE, header.face)
            outState.putString(STATE_SIGN, header.sign)
            outState.putInt(STATE_LEVEL, header.level ?: -1)
            outState.putString(STATE_VIP, header.vipLabel)
            outState.putLong(STATE_FOLLOWER, header.follower ?: -1L)
            outState.putLong(STATE_FOLLOWING, header.following ?: -1L)
            outState.putLong(STATE_LIKES, header.likes ?: -1L)
            outState.putBoolean(STATE_SELF, header.isSelf)
        }
    }

    override fun onDestroy() {
        headerRequest?.cancel()
        liveStatusRequest?.cancel()
        dynamicRequest?.cancel()
        videoRequest?.cancel()
        dynamicTopScroller?.cancel()
        videoTopScroller?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private inner class DynamicListAdapter : BaseAdapter() {
        private val cols = if (isTabletSplit) 2 else 1
        private val tabletCardW: Int
            get() = ((resources.displayMetrics.widthPixels - dp(44)) / 2).coerceAtLeast(dp(260))

        override fun getCount(): Int = (dynamicItems.size + cols - 1) / cols
        override fun getItem(position: Int): SpaceDynamic = dynamicItems[position * cols]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (cols == 1) {
                SpaceDynamicCardUi.clearImages(convertView)
                return SpaceDynamicCardUi.createCard(
                    activity = this@SpaceActivity,
                    item = dynamicItems[position],
                    onOpenDetail = { dynamic ->
                        SpaceDynamicDetailActivity.open(this@SpaceActivity, dynamic)
                    },
                    onOpenVideo = { bvid, aid -> openVideoByMeta(bvid, aid) },
                    onFeedback = { msg -> showFeedback(msg) }
                )
            }
            if (convertView is LinearLayout && convertView.tag == "tablet_dyn_row") {
                for (i in 0 until convertView.childCount) {
                    SpaceDynamicCardUi.clearImages(convertView.getChildAt(i))
                }
            }
            val row = LinearLayout(this@SpaceActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                tag = "tablet_dyn_row"
                setPadding(0, 0, 0, dp(12))
            }
            val base = position * 2
            val cardW = tabletCardW
            for (c in 0..1) {
                val item = dynamicItems.getOrNull(base + c)
                val cell: View = if (item == null) {
                    View(this@SpaceActivity)
                } else {
                    SpaceDynamicCardUi.createCard(
                        activity = this@SpaceActivity,
                        item = item,
                        cardWidthPx = cardW,
                        cardMode = true,
                        onOpenDetail = { dynamic ->
                            SpaceDynamicDetailActivity.open(this@SpaceActivity, dynamic)
                        },
                        onOpenVideo = { bvid, aid -> openVideoByMeta(bvid, aid) },
                        onFeedback = { msg -> showFeedback(msg) }
                    )
                }
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (c == 0) rightMargin = dp(12)
                })
            }
            return row
        }
    }

    private inner class SpaceVideoAdapter : BaseAdapter() {
        private val cols = if (isTabletSplit) 3 else 1

        override fun getCount(): Int = (videoItems.size + cols - 1) / cols
        override fun getItem(position: Int): SpaceVideo = videoItems[position * cols]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (cols == 1) {
                val wrapper = (convertView as? FrameLayout) ?: FrameLayout(this@SpaceActivity).apply {
                    setPadding(0, 0, 0, dp(8))
                    addView(SpaceVideoRowUi.createRow(this@SpaceActivity))
                }
                val row = wrapper.getChildAt(0) as LinearLayout
                val holder = row.tag as SpaceVideoRowUi.Holder
                val video = videoItems[position]
                SpaceVideoRowUi.bind(row, holder, video)
                row.setOnClickListener { openVideoByMeta(video.bvid, video.aid) }
                return wrapper
            }
            val rowLine = (convertView as? LinearLayout) ?: LinearLayout(this@SpaceActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
                for (c in 0 until cols) {
                    val cell = SpaceVideoRowUi.createRow(this@SpaceActivity)
                    addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (c < cols - 1) rightMargin = dp(10)
                    })
                }
            }
            val base = position * cols
            for (c in 0 until cols) {
                val cell = rowLine.getChildAt(c) as LinearLayout
                val video = videoItems.getOrNull(base + c)
                if (video == null) {
                    cell.visibility = View.INVISIBLE
                    cell.isClickable = false
                    SpaceVideoRowUi.clearImages(cell)
                } else {
                    cell.visibility = View.VISIBLE
                    cell.isClickable = true
                    SpaceVideoRowUi.bind(cell, cell.tag as SpaceVideoRowUi.Holder, video)
                    cell.setOnClickListener { openVideoByMeta(video.bvid, video.aid) }
                }
            }
            return rowLine
        }
    }

    companion object {
        private const val EXTRA_MID = "mid"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_FACE = "face"
        private const val STATE_TAB = "space_tab"
        private const val STATE_ORDER = "space_order"
        private const val STATE_NAME = "space_name"
        private const val STATE_FACE = "space_face"
        private const val STATE_SIGN = "space_sign"
        private const val STATE_LEVEL = "space_level"
        private const val STATE_VIP = "space_vip"
        private const val STATE_FOLLOWER = "space_follower"
        private const val STATE_FOLLOWING = "space_following"
        private const val STATE_LIKES = "space_likes"
        private const val STATE_SELF = "space_self"

        fun open(
            context: Context,
            mid: Long,
            initialName: String = "",
            initialFace: String = ""
        ) {
            if (mid <= 0L) return
            val intent = Intent(context, SpaceActivity::class.java)
                .putExtra(EXTRA_MID, mid)
                .putExtra(EXTRA_NAME, initialName)
                .putExtra(EXTRA_FACE, initialFace)
            context.startActivity(intent)
            if (context is Activity) {
                @Suppress("DEPRECATION")
                context.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left_soft)
            }
        }
    }
}
