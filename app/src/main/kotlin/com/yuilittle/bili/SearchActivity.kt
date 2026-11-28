package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.Transition
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Search is a two-stage surface within one activity: entry/typing -> results.
 * This keeps result back-navigation inside search rather than losing the query
 * and jumping to home. The home search field is the shared element for entry
 * and return, so the surface expands and collapses from the original position.
 */
class SearchActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var stateCard: LinearLayout
    private lateinit var stateTitle: TextView
    private lateinit var stateAction: TextView
    private lateinit var results: ListView
    private lateinit var resultsHost: FrameLayout
    private lateinit var skeletonGrid: FrameLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var resultAdapter: SearchVideoAdapter
    private lateinit var pgcHeader: LinearLayout
    private lateinit var pgcRow: LinearLayout
    private var pgcRequestGeneration = 0
    /** 首屏聚合：UGC 与 PGC 全部落定（或 PGC 超过宽限窗）后才一次性提交。 */
    private var firstScreen: FirstScreen? = null
    private var firstScreenTimeout: Runnable? = null
    /** 同关键词的番剧区结果复用，切换排序/时长时不再重拉、不再闪。 */
    private var lastPgcKeyword = ""
    private var lastPgcItems: List<PgcMedia> = emptyList()

    private lateinit var suggestions: LinearLayout
    private lateinit var historySection: LinearLayout
    private lateinit var historyChips: HistoryChipLayout
    private lateinit var clearHistoryAction: TextView
    private lateinit var searchAction: TextView
    private lateinit var loadingSpinner: BrandSpinnerView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSuggestion: Runnable? = null
    private var searchInProgress = false
    private var loadingNextPage = false
    private var searchEndReached = false
    private var currentSearchKeyword = ""
    private var searchPage = 0
    private var showingResultPage = false
    private var searchGeneration = 0
    private lateinit var pagingSpinner: BrandSpinnerView
    private lateinit var topButton: ScrollToTopButton
    /** 搜索结果回顶驱动器（近场 8：位置 0 是 PGC header，之后每行 2 卡）。 */
    private var topScroller: AbsListSmoothTopScroller? = null
    private var suppressTextWatcher = false
    private var entryFocusDispatched = false
    /** 带关键词直接进入（评论区热词）：已直接出结果，不应再自动弹键盘。 */
    private var presetQueryMode = false

    // ── 重写后的搜索筛选状态（排序标签 + 筛选项面板） ──────────────
    private var sortMode = SortMode.DEFAULT
    private var filterDuration = 0
    private lateinit var filterBar: LinearLayout
    private lateinit var sortTabIndicator: View
    private val sortTabViews = ArrayList<TextView>()
    private lateinit var durationChip: LinearLayout
    private lateinit var durationChipIcon: View
    private lateinit var durationChipLabel: TextView
    private lateinit var durationChipChevron: View
    private lateinit var filterResetBtn: TextView
    private lateinit var filterScrim: View
    private lateinit var filterSheet: LinearLayout
    private var filterSheetVisible = false

    private enum class SortMode(val label: String, val orderParam: String) {
        DEFAULT("综合", ""),
        PLAY_DESC("最多播放", "click"),
        LATEST("最新发布", "pubdate"),
    }

    /** 搜索对象分类：视频沿用原有排序/时长筛选，直播与用户各有独立列表与分页。 */
    private enum class SearchScope(val label: String) {
        VIDEO("视频"),
        LIVE("直播"),
        USER("用户"),
    }

    private var searchScope = SearchScope.VIDEO
    private lateinit var scopeBar: LinearLayout
    private lateinit var scopeIndicator: View
    private val scopeTabViews = ArrayList<TextView>()
    private lateinit var altResults: ListView
    private lateinit var altAdapter: ScopeSearchAdapter
    private var altPage = 0
    private var altLoadingNext = false
    private var altEndReached = false
    private var altGeneration = 0

    private val filterDurationOptions = listOf("全部时长", "5 分钟+", "10 分钟+", "30 分钟+", "1 小时+")
    private var lastSuggestionKeyword = ""
    private var cachedSuggestions: List<String> = emptyList()

    private val isTabletLayout by lazy { LayoutMode.isTablet }
    private val searchSidePadDp: Int get() = if (isTabletLayout) 18 else VideoCardUi.CARD_SPACING_DP
    private val searchAvailableWidthPx: Int
        get() = (resources.displayMetrics.widthPixels - dp(searchSidePadDp * 2) + dp(VideoCardUi.CARD_SPACING_DP * 2))
            .coerceAtLeast(dp(320))
    private val searchColumns: Int
        get() = LayoutMode.gridColumns(this, searchAvailableWidthPx)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureTransitions()
        configureBars()
        PlayerSettingsStore.applyHomeOrientation(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(dp(searchSidePadDp), dp(Ds.Space.sm), dp(searchSidePadDp), 0)
            clipChildren = false
            clipToPadding = false
            // 不要让 root 抢焦点：否则从首页点搜索栏进来，输入框不会自动进入编辑态。
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        root.addView(
            createToolbar(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(SEARCH_BOX_HEIGHT_DP)).apply {
                leftMargin = dp(Ds.Space.xs)
                rightMargin = dp(Ds.Space.xs)
            }
        )
        scopeBar = createScopeStrip().apply { visibility = View.GONE }
        root.addView(
            scopeBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply {
                topMargin = dp(2)
            }
        )
        filterBar = createFilterStrip().apply { visibility = View.GONE }
        root.addView(
            filterBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply {
                topMargin = dp(2)
            }
        )

        contentHost = FrameLayout(this).apply {
            alpha = 0f
            translationY = dp(8).toFloat()
            clipChildren = false
        }
        val contentColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historySection = createHistorySection()
        contentColumn.addView(
            historySection,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
                leftMargin = dp(Ds.Space.sm)
                rightMargin = dp(Ds.Space.sm)
            }
        )

        resultsHost = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        resultAdapter = SearchVideoAdapter()
        results = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setPadding(dp(VideoCardUi.CARD_SHADOW_INSET_DP), dp(3), dp(VideoCardUi.CARD_SHADOW_INSET_DP), dp(32))
            overScrollMode = View.OVER_SCROLL_NEVER
            setVerticalScrollBarEnabled(false)
            setHorizontalScrollBarEnabled(false)
            setSelector(android.R.color.transparent)
            clipToPadding = false
            clipChildren = false
            // PGC 顶部区：外层空槽位包住可隐藏内容，GONE 时零高度不留白
            val headerSlot = FrameLayout(this@SearchActivity)
            pgcHeader = buildPgcHeader()
            headerSlot.addView(pgcHeader, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            addHeaderView(headerSlot, null, false)
            adapter = resultAdapter
            setRecyclerListener { row ->
                (row.tag as? List<*>)?.forEach { card ->
                    (card as? View)?.tag?.let { tag ->
                        (tag as? VideoCardUi.Holder)?.let { CoverLoader.clear(it.cover) }
                    }
                }
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

                override fun onScroll(view: AbsListView?, firstVisible: Int, visibleCount: Int, totalCount: Int) {
                    updateTopButton(firstVisible)
                    if (showingResultPage && topScroller?.isRunning != true && totalCount > 0 &&
                        firstVisible + visibleCount >= totalCount - SEARCH_LOAD_AHEAD_ROWS
                    ) loadNextSearchPage()
                }
            })
        }
        resultsHost.addView(
            results,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // 直播 / 用户结果列表：与视频网格互斥显示，共用 resultsHost 的定位与回顶按钮。
        altAdapter = ScopeSearchAdapter()
        altResults = ListView(this).apply {
            divider = null
            dividerHeight = 0
            visibility = View.GONE
            setPadding(dp(4), 0, dp(4), dp(32))
            overScrollMode = View.OVER_SCROLL_NEVER
            setVerticalScrollBarEnabled(false)
            setSelector(android.R.color.transparent)
            clipToPadding = false
            adapter = altAdapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView?, firstVisible: Int, visibleCount: Int, totalCount: Int) {
                    if (searchScope == SearchScope.VIDEO) return
                    updateTopButton(firstVisible)
                    if (totalCount > 0 && firstVisible + visibleCount >= totalCount - 2) {
                        loadNextScopePage()
                    }
                }
            })
        }
        resultsHost.addView(
            altResults,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        topScroller = AbsListSmoothTopScroller(results, nearPosition = 8)

        skeletonGrid = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = false
            addView(VideoCardUi.createSkeletonLayout(this@SearchActivity, this, searchColumns, searchAvailableWidthPx))
        }
        resultsHost.addView(
            skeletonGrid,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        stateCard = createStateCard()
        resultsHost.addView(
            stateCard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(42)
                leftMargin = dp(Ds.Space.lg)
                rightMargin = dp(Ds.Space.lg)
            }
        )

        pagingSpinner = BrandSpinnerView(this, COLOR_ROSE, Color.WHITE).apply {
            visibility = View.GONE
            contentDescription = "正在加载更多搜索结果"
        }
        resultsHost.addView(
            pagingSpinner,
            FrameLayout.LayoutParams(dp(30), dp(30), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(18)
            }
        )
        topButton = ScrollToTopButton(this) {
            if (searchScope == SearchScope.VIDEO) topScroller?.scrollToTop()
            else altResults.smoothScrollToPosition(0)
        }
        resultsHost.addView(
            topButton,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(16)
                bottomMargin = dp(24)
            }
        )
        contentColumn.addView(resultsHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        contentHost.addView(
            contentColumn,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // 筛选面板与轻遮罩（浮在结果网格上方，不改变底部网格布局高度和滚动位置）
        filterScrim = View(this).apply {
            setBackgroundColor(Ds.colors.scrim)
            alpha = 0f
            visibility = View.GONE
            isClickable = true
            setOnClickListener { hideFilterSheet() }
        }
        contentHost.addView(
            filterScrim,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        filterSheet = createFilterSheet().apply {
            visibility = View.GONE
            elevation = dp(12).toFloat()
        }
        contentHost.addView(
            filterSheet,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                leftMargin = dp(Ds.Space.xs)
                rightMargin = dp(Ds.Space.xs)
                topMargin = dp(4)
            }
        )

        // Suggestions are an independent floating layer: they never consume
        // history layout height or move the history bubbles.
        suggestions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@SearchActivity, COLOR_SURFACE, Ds.colors.lineSoft, Ds.Radius.card)
            visibility = View.GONE
            elevation = dp(8).toFloat()
            setPadding(0, dp(Ds.Space.xs), 0, dp(Ds.Space.xs))
        }
        val suggestionLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(44)
            rightMargin = dp(56)
            topMargin = dp(6)
        }
        contentHost.addView(suggestions, suggestionLp)

        root.addView(contentHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showSearchHistory()
        // 外部带关键词进入（如评论区热词）：直接填入并搜索
        val presetKeyword = intent?.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        presetQueryMode = presetKeyword.isNotBlank()
        if (presetKeyword.isNotBlank()) {
            suppressTextWatcher = true
            input.setText(presetKeyword)
            input.setSelection(input.text.length)
            suppressTextWatcher = false
            performSearch()
        }
        contentHost.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(55L)
            .setDuration(SEARCH_SURFACE_FADE_MS)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
        // 带关键词进入时已直接出结果，不弹键盘
        if (!presetQueryMode) {
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            root.post {
                if (!entryFocusDispatched) requestInputFocus(showKeyboard = true)
            }
            root.postDelayed({
                if (!entryFocusDispatched) requestInputFocus(showKeyboard = true)
            }, 180L)
        } else {
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            entryFocusDispatched = true
            // 容器先不把焦点交给输入框，否则布局后会自动弹键盘
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            input.clearFocus()
            root.post {
                // 布局稳定后恢复，用户主动点击仍可正常输入
                root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                input.clearFocus()
            }
        }
    }

    override fun onDestroy() {
        topScroller?.cancel()
        pendingSuggestion?.let { mainHandler.removeCallbacks(it) }
        pgcRequestGeneration += 1
        cancelFirstScreenGate()
        if (::pgcRow.isInitialized) clearPgcHeader()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (filterSheetVisible) {
            hideFilterSheet()
            return
        }
        navigateBack()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN &&
            ::suggestions.isInitialized && suggestions.visibility == View.VISIBLE
        ) {
            val inputBounds = Rect().also { input.getGlobalVisibleRect(it) }
            val suggestionBounds = Rect().also { suggestions.getGlobalVisibleRect(it) }
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            if (!inputBounds.contains(x, y) && !suggestionBounds.contains(x, y)) {
                hideSuggestions()
                input.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(input.windowToken, 0)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun createToolbar(): View {
        val bar = FrameLayout(this)
        val field = FrameLayout(this).apply {
            background = Ds.pill(this@SearchActivity, COLOR_SURFACE, Ds.colors.lineSoft)
            transitionName = SEARCH_BOX_TRANSITION_NAME
        }
        input = EditText(this).apply {
            hint = "搜索公开视频"
            textSize = SEARCH_BOX_TEXT_SIZE_SP
            typeface = Ds.body()
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            setPadding(0, 0, dp(14), 0)
            background = null
            isFocusableInTouchMode = true
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    performSearch()
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(value: Editable?) {
                    if (suppressTextWatcher) return
                    onKeywordChanged(value?.toString().orEmpty())
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                hint = if (hasFocus) "" else "搜索公开视频"
                if (hasFocus) restoreSuggestionsForCurrentText() else hideSuggestions()
            }
            setOnClickListener {
                requestInputFocus(showKeyboard = true)
                restoreSuggestionsForCurrentText()
            }
        }
        field.addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = dp(14 + 16 + Ds.Space.sm)
                rightMargin = dp(30)
            }
        )
        field.addView(
            Ds.searchIcon(this, COLOR_MUTED, 1.8f),
            FrameLayout.LayoutParams(dp(16), dp(16), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(14)
            }
        )
        val clearInput = FrameLayout(this).apply {
            visibility = if (input.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            isClickable = true
            isFocusable = true
            contentDescription = "清空搜索词"
            Ds.bindPressFeedback(this, 0.90f)
            addView(
                Ds.closeIcon(this@SearchActivity, COLOR_MUTED, 1.7f),
                FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)
            )
            setOnClickListener {
                input.setText("")
                requestInputFocus(showKeyboard = true)
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearInput.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })
        field.addView(
            clearInput,
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp(4)
            }
        )
        field.setOnClickListener {
            requestInputFocus(showKeyboard = true)
            restoreSuggestionsForCurrentText()
        }
        bar.addView(
            field,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(SEARCH_BOX_HEIGHT_DP),
                Gravity.CENTER
            ).apply {
                leftMargin = dp(44)
                rightMargin = dp(52)
            }
        )
        val back = Ds.backIcon(this, COLOR_INK) { navigateBack() }
        bar.addView(back, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL))

        val actionHost = FrameLayout(this)
        searchAction = TextView(this).apply {
            text = "搜索"
            textSize = 14f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ROSE)
            isClickable = true
            isFocusable = true
            contentDescription = "开始搜索"
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { performSearch() }
        }
        loadingSpinner = BrandSpinnerView(this, COLOR_ROSE, Color.WHITE).apply {
            visibility = View.GONE
            contentDescription = "搜索加载中"
        }
        actionHost.addView(
            searchAction,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        actionHost.addView(loadingSpinner, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        bar.addView(actionHost, FrameLayout.LayoutParams(dp(48), dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
        return bar
    }

    private fun createStateCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        background = Ds.card(this@SearchActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
        setPadding(dp(Ds.Space.xl), dp(Ds.Space.lg), dp(Ds.Space.xl), dp(Ds.Space.lg))

        stateTitle = TextView(this@SearchActivity).apply {
            textSize = 14f
            typeface = Ds.body()
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        addView(
            stateTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        stateAction = TextView(this@SearchActivity).apply {
            textSize = 13f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ROSE)
            background = Ds.pill(this@SearchActivity, Ds.colors.accentSoft)
            setPadding(dp(Ds.Space.lg), dp(Ds.Space.sm), dp(Ds.Space.lg), dp(Ds.Space.sm))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            Ds.bindPressFeedback(this, 0.96f)
        }
        addView(
            stateAction,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = this@SearchActivity.dp(Ds.Space.md)
            }
        )
    }

    private fun showState(message: String, actionText: String? = null, onAction: (() -> Unit)? = null) {
        stateTitle.text = message
        if (actionText != null && onAction != null) {
            stateAction.text = actionText
            stateAction.visibility = View.VISIBLE
            stateAction.setOnClickListener { onAction() }
        } else {
            stateAction.visibility = View.GONE
            stateAction.setOnClickListener(null)
        }
        stateCard.visibility = View.VISIBLE
    }

    private fun hideState() {
        stateCard.visibility = View.GONE
        stateAction.setOnClickListener(null)
    }

    // ── 搜索类型栏（视频 / 直播 / 用户）：位于排序栏之上 ──

    private fun createScopeStrip(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.Space.xs), 0, dp(Ds.Space.xs), 0)
        }
        val host = FrameLayout(this)
        scopeIndicator = PillIndicatorView(this).apply { fillColor = Ds.colors.accentSoft }
        host.addView(
            scopeIndicator,
            FrameLayout.LayoutParams(dp(SCOPE_TAB_WIDTH_DP), dp(28), Gravity.START or Gravity.CENTER_VERTICAL)
        )
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scopeTabViews.clear()
        SearchScope.values().forEach { scope ->
            val tab = TextView(this).apply {
                text = scope.label
                textSize = 13.5f
                typeface = Ds.body(scope == searchScope)
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                setTextColor(if (scope == searchScope) COLOR_ROSE else COLOR_MUTED)
                isClickable = true
                isFocusable = true
                contentDescription = "搜索类型：${scope.label}"
                setOnClickListener { selectSearchScope(scope) }
            }
            scopeTabViews += tab
            strip.addView(tab, LinearLayout.LayoutParams(dp(SCOPE_TAB_WIDTH_DP), dp(30)))
        }
        host.addView(
            strip,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        bar.addView(host, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        refreshScopeStripUi(animate = false)
        return bar
    }

    private fun refreshScopeStripUi(animate: Boolean) {
        if (!::scopeIndicator.isInitialized) return
        SearchScope.values().forEachIndexed { index, scope ->
            val tab = scopeTabViews.getOrNull(index) ?: return@forEachIndexed
            val selected = scope == searchScope
            tab.setTextColor(if (selected) COLOR_ROSE else COLOR_MUTED)
            tab.typeface = Ds.body(selected)
        }
        val targetX = searchScope.ordinal * dp(SCOPE_TAB_WIDTH_DP).toFloat()
        if (animate && !MotionTokens.isReduced(this)) {
            DsSpring.animate(
                scopeIndicator,
                androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X,
                targetX,
                DsSpring.SETTLE_STIFFNESS,
                DsSpring.SETTLE_DAMPING
            )
        } else {
            (scopeIndicator.getTag(R.id.ds_spring_tx) as? androidx.dynamicanimation.animation.SpringAnimation)?.cancel()
            scopeIndicator.translationX = targetX
        }
    }

    private fun selectSearchScope(next: SearchScope) {
        if (searchScope == next) return
        searchScope = next
        refreshScopeStripUi(animate = true)
        applyScopeChrome()
        if (showingResultPage && currentSearchKeyword.isNotBlank()) {
            performSearch()
        }
    }

    /** 按当前分类切换结果区：视频网格与直播/用户列表互斥，排序筛选只属于视频。 */
    private fun applyScopeChrome() {
        if (!::altResults.isInitialized) return
        val video = searchScope == SearchScope.VIDEO
        results.visibility = if (video) View.VISIBLE else View.GONE
        altResults.visibility = if (!video && showingResultPage) View.VISIBLE else View.GONE
        if (!video) {
            filterBar.animate().cancel()
            filterBar.visibility = View.GONE
            hideFilterSheet()
        }
        // 视频模式的排序栏显隐交给 performSearch 的淡入动画，这里不强行改可见性。
    }

    private fun showScopeBar() {
        if (scopeBar.visibility == View.VISIBLE) return
        scopeBar.visibility = View.VISIBLE
        scopeBar.alpha = 0f
        scopeBar.animate().alpha(1f).setDuration(160L).start()
    }

    private fun hideScopeBar() {
        if (!::scopeBar.isInitialized) return
        scopeBar.animate().cancel()
        scopeBar.visibility = View.GONE
        scopeBar.alpha = 1f
    }

    // ── 重写后的筛选条（与首页推荐/热门同款胶囊指示器 + 右侧紧凑时长筛选胶囊） ──

    private fun createFilterStrip(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.Space.xs), 0, dp(Ds.Space.xs), 0)
        }

        // 左侧：与首页「推荐 | 热门」一致的滑动胶囊排序标签组
        val sortHost = FrameLayout(this)
        sortTabIndicator = PillIndicatorView(this).apply {
            fillColor = Ds.colors.accentSoft
        }
        sortHost.addView(
            sortTabIndicator,
            FrameLayout.LayoutParams(dp(72), dp(28), Gravity.CENTER_VERTICAL or Gravity.START)
        )
        val sortStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sortTabViews.clear()
        SortMode.values().forEachIndexed { index, mode ->
            val tab = TextView(this).apply {
                text = mode.label
                textSize = 13.5f
                typeface = Ds.body(mode == sortMode)
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                isFocusable = true
                contentDescription = "排序：${mode.label}"
                setOnClickListener { selectSortMode(mode) }
            }
            sortTabViews += tab
            sortStrip.addView(
                tab,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                    if (index > 0) leftMargin = dp(Ds.Space.xs)
                }
            )
        }
        sortHost.addView(
            sortStrip,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        sortHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            moveSortIndicator(animate = false)
        }

        val sortScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                sortHost,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }
        bar.addView(sortScroll, LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, 1f))

        // 右侧：时长筛选胶囊 + 激活态快捷重置
        durationChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(9), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "筛选时长"
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener { toggleFilterSheet() }
        }
        durationChipIcon = Ds.filterIcon(this, COLOR_MUTED, 1.6f)
        durationChipLabel = TextView(this).apply {
            text = "筛选"
            textSize = 12.5f
            typeface = Ds.body()
            maxLines = 1
            includeFontPadding = false
            setPadding(dp(5), 0, dp(3), 0)
        }
        durationChipChevron = Ds.toggleChevron(this, COLOR_MUTED, 1.6f, expanded = false)
        durationChip.addView(durationChipIcon, LinearLayout.LayoutParams(dp(13), dp(13)))
        durationChip.addView(
            durationChipLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        durationChip.addView(durationChipChevron, LinearLayout.LayoutParams(dp(12), dp(12)))
        bar.addView(
            durationChip,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)).apply {
                leftMargin = dp(Ds.Space.xs)
            }
        )

        filterResetBtn = TextView(this).apply {
            text = "重置"
            textSize = 12f
            typeface = Ds.body()
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(8), 0, dp(4), 0)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            contentDescription = "重置搜索筛选"
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { resetFilters() }
        }
        bar.addView(filterResetBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)))

        refreshFilterStripUi(animateIndicator = false)
        return bar
    }

    private fun createFilterSheet(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ds.card(this@SearchActivity, COLOR_SURFACE, Ds.colors.lineSoft, Ds.Radius.card)
        setPadding(dp(Ds.Space.lg), dp(Ds.Space.md), dp(Ds.Space.lg), dp(Ds.Space.lg))
        isClickable = true
        populateFilterSheet(this)
    }

    private fun populateFilterSheet(sheet: LinearLayout) {
        sheet.removeAllViews()
        // 标题行
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "视频时长"
                textSize = 13f
                typeface = Ds.body(true)
                setTextColor(COLOR_INK)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (filterDuration != 0 || sortMode != SortMode.DEFAULT) {
            header.addView(
                TextView(this).apply {
                    text = "恢复默认"
                    textSize = 12f
                    typeface = Ds.body(true)
                    setTextColor(COLOR_ROSE)
                    setPadding(dp(8), dp(4), dp(2), dp(4))
                    isClickable = true
                    isFocusable = true
                    Ds.bindPressFeedback(this, 0.94f)
                    setOnClickListener { resetFilters() }
                }
            )
        }
        sheet.addView(
            header,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // 时长流式胶囊选项
        val chipFlow = HistoryChipLayout(this).apply {
            setPadding(0, dp(Ds.Space.md), 0, 0)
        }
        filterDurationOptions.forEachIndexed { index, label ->
            val selected = index == filterDuration
            val chip = TextView(this).apply {
                text = label
                textSize = 12.5f
                typeface = Ds.body(selected)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(if (selected) COLOR_ROSE else COLOR_INK)
                background = if (selected) {
                    Ds.pill(this@SearchActivity, Ds.colors.accentSoft, COLOR_ROSE)
                } else {
                    Ds.pill(this@SearchActivity, COLOR_CARD, Ds.colors.lineSoft)
                }
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isClickable = true
                isFocusable = true
                contentDescription = "时长筛选：$label"
                Ds.bindPressFeedback(this, 0.95f)
                setOnClickListener { selectDurationFilter(index) }
            }
            chipFlow.addView(
                chip,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        sheet.addView(
            chipFlow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun selectSortMode(next: SortMode) {
        if (sortMode == next) {
            if (filterSheetVisible) hideFilterSheet()
            return
        }
        sortMode = next
        refreshFilterStripUi(animateIndicator = true)
        if (filterSheetVisible) hideFilterSheet()
        if (showingResultPage && currentSearchKeyword.isNotBlank()) {
            performSearch()
        }
    }

    private fun selectDurationFilter(index: Int) {
        val changed = filterDuration != index
        filterDuration = index
        refreshFilterStripUi(animateIndicator = false)
        populateFilterSheet(filterSheet)
        mainHandler.postDelayed({ hideFilterSheet() }, 90L)
        if (changed && showingResultPage && currentSearchKeyword.isNotBlank()) {
            mainHandler.postDelayed({ performSearch() }, 130L)
        }
    }

    private fun resetFilters() {
        val changed = sortMode != SortMode.DEFAULT || filterDuration != 0
        sortMode = SortMode.DEFAULT
        filterDuration = 0
        refreshFilterStripUi(animateIndicator = true)
        populateFilterSheet(filterSheet)
        hideFilterSheet()
        if (changed && showingResultPage && currentSearchKeyword.isNotBlank()) {
            performSearch()
        }
    }

    private fun refreshFilterStripUi(animateIndicator: Boolean) {
        if (!::sortTabIndicator.isInitialized) return
        SortMode.values().forEachIndexed { index, mode ->
            val tab = sortTabViews.getOrNull(index) ?: return@forEachIndexed
            val selected = mode == sortMode
            tab.setTextColor(if (selected) COLOR_ROSE else COLOR_MUTED)
            tab.typeface = Ds.body(selected)
            tab.animate().cancel()
            tab.animate()
                .alpha(if (selected) 1f else 0.80f)
                .setDuration(170L)
                .setInterpolator(MotionTokens.easeOut)
                .start()
        }
        moveSortIndicator(animate = animateIndicator)

        val durationActive = filterDuration != 0
        val chipHighlight = durationActive || filterSheetVisible
        val chipTint = if (chipHighlight) COLOR_ROSE else COLOR_MUTED
        durationChip.background = if (chipHighlight) {
            Ds.pill(this, Ds.colors.accentSoft)
        } else {
            Ds.pill(this, COLOR_SURFACE, Ds.colors.lineSoft)
        }
        durationChipLabel.text = if (durationActive) filterDurationOptions[filterDuration] else "时长"
        durationChipLabel.setTextColor(if (chipHighlight) COLOR_ROSE else COLOR_INK)
        durationChipLabel.typeface = Ds.body(chipHighlight)
        durationChipIcon.tag = chipTint
        durationChipIcon.invalidate()
        durationChipChevron.tag = chipTint
        durationChipChevron.invalidate()
        durationChipChevron.animate().cancel()
        durationChipChevron.animate()
            .rotation(if (filterSheetVisible) 180f else 0f)
            .setDuration(180L)
            .setInterpolator(MotionTokens.easeOut)
            .start()

        val anyFilterActive = durationActive || sortMode != SortMode.DEFAULT
        filterResetBtn.visibility = if (anyFilterActive) View.VISIBLE else View.GONE
    }

    private fun moveSortIndicator(animate: Boolean) {
        val target = sortTabViews.getOrNull(sortMode.ordinal) ?: return
        target.post {
            val w = target.width.coerceAtLeast(dp(44))
            val lp = sortTabIndicator.layoutParams
            if (lp.width != w) {
                lp.width = w
                sortTabIndicator.layoutParams = lp
            }
            val targetX = target.left.toFloat().coerceAtLeast(0f)
            if (animate && !MotionTokens.isReduced(this)) {
                DsSpring.animate(
                    sortTabIndicator,
                    androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X,
                    targetX,
                    DsSpring.SETTLE_STIFFNESS,
                    DsSpring.SETTLE_DAMPING
                )
            } else {
                (sortTabIndicator.getTag(R.id.ds_spring_tx) as? androidx.dynamicanimation.animation.SpringAnimation)?.cancel()
                sortTabIndicator.translationX = targetX
            }
        }
    }

    private fun toggleFilterSheet() {
        if (filterSheetVisible) hideFilterSheet() else showFilterSheet()
    }

    private fun showFilterSheet() {
        if (filterSheetVisible) return
        filterSheetVisible = true
        populateFilterSheet(filterSheet)
        refreshFilterStripUi(animateIndicator = false)

        filterScrim.animate().cancel()
        filterScrim.visibility = View.VISIBLE
        filterScrim.alpha = 0f
        filterScrim.animate().alpha(1f).setDuration(160L).setInterpolator(MotionTokens.easeOut).start()

        filterSheet.animate().cancel()
        filterSheet.visibility = View.VISIBLE
        filterSheet.alpha = 0f
        filterSheet.translationY = -dp(8).toFloat()
        filterSheet.scaleY = 0.94f
        filterSheet.pivotY = 0f
        filterSheet.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleY(1f)
            .setDuration(200L)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    private fun hideFilterSheet() {
        if (!filterSheetVisible) return
        filterSheetVisible = false
        refreshFilterStripUi(animateIndicator = false)

        filterScrim.animate().cancel()
        filterScrim.animate()
            .alpha(0f)
            .setDuration(140L)
            .setInterpolator(MotionTokens.easeOut)
            .withEndAction {
                if (!filterSheetVisible) filterScrim.visibility = View.GONE
            }
            .start()

        filterSheet.animate().cancel()
        filterSheet.animate()
            .alpha(0f)
            .translationY(-dp(6).toFloat())
            .scaleY(0.95f)
            .setDuration(140L)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                if (!filterSheetVisible) {
                    filterSheet.visibility = View.GONE
                    filterSheet.alpha = 1f
                    filterSheet.translationY = 0f
                    filterSheet.scaleY = 1f
                }
            }
            .start()
    }

    private fun createHistorySection(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        val header = LinearLayout(this@SearchActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this@SearchActivity).apply {
                text = "搜索历史"
                textSize = 15f
                typeface = Ds.display(bold = true)
                setTextColor(COLOR_INK)
            },
            LinearLayout.LayoutParams(0, dp(34), 1f)
        )
        clearHistoryAction = TextView(this@SearchActivity).apply {
            text = "清空"
            textSize = 13f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ROSE)
            isClickable = true
            isFocusable = true
            contentDescription = "清空全部搜索历史"
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { confirmClearHistory() }
        }
        header.addView(clearHistoryAction, LinearLayout.LayoutParams(dp(52), dp(34)))
        addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
        historyChips = HistoryChipLayout(this@SearchActivity).apply {
            setPadding(0, dp(6), 0, dp(4))
        }
        addView(historyChips, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun showSearchHistory() {
        if (showingResultPage) {
            historySection.visibility = View.GONE
            return
        }
        val values = SearchHistoryStore.load(this)
        historyChips.removeAllViews()
        if (values.isEmpty()) {
            historySection.visibility = View.GONE
            if (input.text.toString().trim().isBlank()) {
                showState("输入关键词，搜索公开视频")
            } else {
                hideState()
            }
            return
        }
        values.forEach {
            historyChips.addView(
                historyChip(it),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        historySection.visibility = View.VISIBLE
        hideState()
    }

    private fun historyChip(keyword: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ds.pill(this@SearchActivity, COLOR_SURFACE, Ds.colors.lineSoft)
        setPadding(dp(12), 0, dp(4), 0)
        contentDescription = "历史搜索：$keyword"
        Ds.bindPressFeedback(this, 0.96f)
        val label = TextView(this@SearchActivity).apply {
            text = keyword
            textSize = 13f
            typeface = Ds.body()
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)))
        val remove = FrameLayout(this@SearchActivity).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "删除历史：$keyword"
            Ds.bindPressFeedback(this, 0.90f)
            addView(
                Ds.closeIcon(this@SearchActivity, Ds.colors.subtle, 1.55f),
                FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER)
            )
            setOnClickListener {
                SearchHistoryStore.remove(this@SearchActivity, keyword)
                showSearchHistory()
            }
        }
        addView(remove, LinearLayout.LayoutParams(dp(26), dp(30)).apply { leftMargin = dp(2) })
        setOnClickListener {
            suppressTextWatcher = true
            input.setText(keyword)
            input.setSelection(input.text.length)
            suppressTextWatcher = false
            pendingSuggestion?.let { mainHandler.removeCallbacks(it) }
            BiliApi.cancelSuggestions()
            performSearch()
        }
    }

    private fun confirmClearHistory() {
        android.app.AlertDialog.Builder(this)
            .setTitle("清空搜索历史？")
            .setMessage("这只会删除本机保存的搜索词，无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                SearchHistoryStore.clear(this)
                showSearchHistory()
            }
            .show()
    }

    private fun onKeywordChanged(raw: String) {
        pendingSuggestion?.let { mainHandler.removeCallbacks(it) }
        cancelFirstScreenGate()
        if (searchInProgress) {
            searchGeneration += 1
            searchInProgress = false
            hideLoading()
            VideoCardUi.toggleSkeleton(skeletonGrid, false)
        }
        if (showingResultPage) {
            showingResultPage = false
            ++altGeneration
            hideFilterSheet()
            filterBar.animate().cancel()
            filterBar.visibility = View.GONE
            hideScopeBar()
            resultAdapter.replace(emptyList(), animateNew = false)
            altAdapter.submit(emptyList())
            altResults.visibility = View.GONE
            results.visibility = View.VISIBLE
            VideoCardUi.toggleSkeleton(skeletonGrid, false)
            pagingSpinner.visibility = View.GONE
            if (::pgcRow.isInitialized) clearPgcHeader()
            showState(if (searchScope == SearchScope.VIDEO) "输入关键词，搜索公开视频" else "输入关键词开始搜索")
        }
        val keyword = raw.trim()
        if (keyword.isBlank()) {
            hideSuggestions()
            showSearchHistory()
            return
        }
        showSearchHistory()
        requestSuggestions(keyword)
    }

    private fun requestSuggestions(keyword: String, immediate: Boolean = false) {
        if (keyword.isBlank() || !input.hasFocus()) {
            hideSuggestions()
            return
        }
        pendingSuggestion?.let { mainHandler.removeCallbacks(it) }
        if (keyword == lastSuggestionKeyword && cachedSuggestions.isNotEmpty()) {
            showSuggestions(cachedSuggestions)
            return
        }
        val request = Runnable {
            BiliApi.suggest(keyword) callback@{ values, _ ->
                if (showingResultPage || searchInProgress || !input.hasFocus() || input.text.toString().trim() != keyword) return@callback
                lastSuggestionKeyword = keyword
                cachedSuggestions = values.orEmpty()
                showSuggestions(cachedSuggestions)
            }
        }
        pendingSuggestion = request
        mainHandler.postDelayed(request, if (immediate) 0L else SUGGEST_DEBOUNCE_MS)
    }

    private fun restoreSuggestionsForCurrentText() {
        if (showingResultPage || searchInProgress) return
        val keyword = input.text.toString().trim()
        if (keyword.isBlank()) {
            hideSuggestions()
        } else {
            requestSuggestions(keyword, immediate = true)
        }
    }

    private fun showSuggestions(values: List<String>) {
        suggestions.removeAllViews()
        if (values.isEmpty()) {
            suggestions.visibility = View.GONE
            return
        }
        values.take(MAX_SUGGESTIONS).forEachIndexed { index, value ->
            suggestions.addView(
                suggestionRow(value),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
            )
            if (index < minOf(values.size, MAX_SUGGESTIONS) - 1) {
                suggestions.addView(
                    View(this).apply { setBackgroundColor(COLOR_BORDER) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)).apply {
                        leftMargin = dp(15)
                        rightMargin = dp(15)
                    }
                )
            }
        }
        suggestions.bringToFront()
        if (suggestions.visibility != View.VISIBLE) {
            suggestions.alpha = 0f
            suggestions.translationY = -dp(6).toFloat()
            suggestions.visibility = View.VISIBLE
            suggestions.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(SUGGESTION_EXPAND_DURATION_MS)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()
        }
    }

    private fun suggestionRow(keyword: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        contentDescription = "搜索建议：$keyword"
        setPadding(dp(14), 0, dp(14), 0)
        Ds.bindPressFeedback(this, 0.98f)
        addView(
            Ds.searchIcon(this@SearchActivity, COLOR_MUTED, 1.6f),
            LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(10) }
        )
        addView(
            TextView(this@SearchActivity).apply {
                text = keyword
                textSize = 14f
                typeface = Ds.body()
                setTextColor(COLOR_INK)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
        setOnClickListener {
            suppressTextWatcher = true
            input.setText(keyword)
            input.setSelection(input.text.length)
            suppressTextWatcher = false
            BiliApi.cancelSuggestions()
            performSearch()
        }
    }

    private fun hideSuggestions(animated: Boolean = true) {
        suggestions.animate().cancel()
        if (!animated || suggestions.visibility != View.VISIBLE) {
            suggestions.removeAllViews()
            suggestions.alpha = 1f
            suggestions.translationY = 0f
            suggestions.visibility = View.GONE
            return
        }
        suggestions.animate()
            .alpha(0f)
            .translationY(-dp(4).toFloat())
            .setDuration(SUGGESTION_COLLAPSE_DURATION_MS)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                if (suggestions.alpha == 0f) {
                    suggestions.removeAllViews()
                    suggestions.alpha = 1f
                    suggestions.translationY = 0f
                    suggestions.visibility = View.GONE
                }
            }
            .start()
    }

    private fun performSearch() {
        pulseSearchAction()
        val keyword = input.text.toString().trim()
        if (keyword.isBlank()) {
            showState(
                if (searchScope == SearchScope.VIDEO) "先输入想找的视频关键词" else "先输入关键词"
            )
            hideSuggestions()
            return
        }
        SearchHistoryStore.record(this, keyword)
        ++searchGeneration
        ++altGeneration
        currentSearchKeyword = keyword
        searchPage = 0
        altPage = 0
        searchEndReached = false
        altEndReached = false
        altLoadingNext = false
        showingResultPage = true
        topScroller?.cancel()
        topButton.setShown(false, animate = false)
        historySection.visibility = View.GONE
        searchInProgress = true
        loadingNextPage = false
        pendingSuggestion?.let { mainHandler.removeCallbacks(it) }
        hideSuggestions()
        hideFilterSheet()
        hideKeyboard()
        resultAdapter.replace(emptyList(), animateNew = false)
        altAdapter.submit(emptyList())
        hideState()
        if (::pgcRow.isInitialized) clearPgcHeader()
        cancelFirstScreenGate()
        VideoCardUi.toggleSkeleton(skeletonGrid, false)
        showScopeBar()
        applyScopeChrome()

        if (searchScope != SearchScope.VIDEO) {
            pagingSpinner.visibility = View.GONE
            performScopeSearch(initial = true)
            return
        }

        VideoCardUi.toggleSkeleton(skeletonGrid, true)
        if (filterBar.visibility != View.VISIBLE) {
            filterBar.visibility = View.VISIBLE
            filterBar.alpha = 0f
            filterBar.translationY = -dp(5).toFloat()
            filterBar.animate().alpha(1f).translationY(0f).setDuration(180L).start()
            refreshFilterStripUi(animateIndicator = false)
        }
        mainHandler.postDelayed({
            if (searchInProgress) showLoading()
        }, SEARCH_BUTTON_PULSE_MS)
        // 首屏聚合：UGC 与 PGC 并发，两者都落定（或 PGC 超宽限窗）后才一次性提交，
        // 避免 PGC 后到把 header 从 GONE 撑开、把已经渲染的首屏列表整体下推。
        val gate = FirstScreen(keyword, searchGeneration)
        firstScreen = gate
        if (keyword == lastPgcKeyword) {
            // 同关键词（切换排序/时长）复用已拿到的番剧区，不再重拉也不再闪
            gate.pgcItems = lastPgcItems
            gate.pgcSettled = true
        } else {
            requestPgcHeader(keyword, gate)
        }
        requestSearchPage(page = 1, initial = true)
    }

    /** 顶部番剧/影视横滑区：标题 + 海报卡片，默认隐藏，有结果才显示。 */
    private fun buildPgcHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(VideoCardUi.CARD_SHADOW_INSET_DP), dp(4), dp(VideoCardUi.CARD_SHADOW_INSET_DP), dp(10))
            visibility = View.GONE
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(5), dp(8))
        }
        titleRow.addView(TextView(this).apply {
            text = "番剧 / 影视"
            textSize = 14f
            typeface = Ds.display(bold = true)
            setTextColor(Ds.colors.text)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply {
            id = PGC_COUNT_TITLE_ID
            textSize = 11.5f
            typeface = Ds.body()
            setTextColor(Ds.colors.muted)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(titleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
        }
        pgcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(VideoCardUi.CARD_SHADOW_INSET_DP), 0, dp(VideoCardUi.CARD_SHADOW_INSET_DP), dp(4))
        }
        scroller.addView(pgcRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        header.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return header
    }

    private fun clearPgcHeader() {
        for (i in 0 until pgcRow.childCount) {
            (pgcRow.getChildAt(i).tag as? PgcCardHolder)?.let { CoverLoader.clear(it.cover) }
        }
        pgcRow.removeAllViews()
        pgcHeader.visibility = View.GONE
    }

    /** 搜索首屏拉取番剧+电影，结果写入首屏聚合器，不直接操作 UI。 */
    private fun requestPgcHeader(keyword: String, gate: FirstScreen) {
        val gen = ++pgcRequestGeneration
        var bangumi: List<PgcMedia>? = null
        var movie: List<PgcMedia>? = null
        var bangumiDone = false
        var movieDone = false
        fun mergeIfDone() {
            if (!bangumiDone || !movieDone) return
            android.util.Log.d("YuiPgc", "merge kw=$keyword bangumi=${bangumi?.size} movie=${movie?.size} gen=$gen/$pgcRequestGeneration gate=${gate.generation}/${searchGeneration}")
            if (isFinishing || isDestroyed || gen != pgcRequestGeneration || gate !== firstScreen) return
            val merged = ArrayList<PgcMedia>()
            val seen = HashSet<Long>()
            val a = bangumi.orEmpty()
            val b = movie.orEmpty()
            for (i in 0 until maxOf(a.size, b.size)) {
                a.getOrNull(i)?.let { if (seen.add(it.seasonId)) merged.add(it) }
                b.getOrNull(i)?.let { if (seen.add(it.seasonId)) merged.add(it) }
            }
            gate.pgcItems = merged.take(MAX_PGC_CARDS)
            gate.pgcSettled = true
            if (keyword == currentSearchKeyword) {
                lastPgcKeyword = keyword
                lastPgcItems = gate.pgcItems
            }
            commitFirstScreenIfReady()
        }
        BiliApi.searchPgc(keyword, "media_bangumi") { list, err ->
            android.util.Log.d("YuiPgc", "bangumi cb size=${list?.size} err=$err")
            bangumi = if (err == null) list.orEmpty() else null
            bangumiDone = true; mergeIfDone()
        }
        BiliApi.searchPgc(keyword, "media_ft") { list, err ->
            android.util.Log.d("YuiPgc", "movie cb size=${list?.size} err=$err")
            movie = if (err == null) list.orEmpty() else null
            movieDone = true; mergeIfDone()
        }
    }

    /**
     * 首屏唯一提交入口：UGC 已落定、且 PGC 已落定或超过宽限窗。
     * 先同步写入 PGC 区与视频列表，再由骨架屏淡出遮住高度变化，不会下推。
     */
    private fun commitFirstScreenIfReady() {
        val gate = firstScreen ?: return
        if (gate.committed || gate.generation != searchGeneration || !showingResultPage) return
        if (!gate.ugcSettled) return
        if (!gate.pgcSettled) {
            // UGC 先回、PGC 还在路上：给 PGC 一个宽限窗，超时则放弃后插，避免用户干等
            if (firstScreenTimeout == null) {
                val pending = Runnable {
                    firstScreenTimeout = null
                    val current = firstScreen ?: return@Runnable
                    if (current !== gate || current.committed) return@Runnable
                    current.pgcSettled = true
                    current.pgcItems = emptyList()
                    commitFirstScreenIfReady()
                }
                firstScreenTimeout = pending
                mainHandler.postDelayed(pending, PGC_FIRST_SCREEN_GRACE_MS)
            }
            return
        }
        cancelFirstScreenTimeout()
        gate.committed = true
        commitFirstScreen(gate)
    }

    private fun commitFirstScreen(gate: FirstScreen) {
        searchInProgress = false
        loadingNextPage = false
        hideLoading()
        val videos = gate.videos
        val pgc = gate.pgcItems
        if (videos == null && pgc.isEmpty()) {
            VideoCardUi.toggleSkeleton(skeletonGrid, false)
            showState(
                message = "搜索暂不可用：${gate.error}",
                actionText = "在官方搜索页继续"
            ) {
                startActivity(
                    Intent(this@SearchActivity, PlayerActivity::class.java).putExtra(
                        PlayerActivity.EXTRA_URL,
                        "https://search.bilibili.com/all?keyword=${java.net.URLEncoder.encode(gate.keyword, "UTF-8")}"
                    )
                )
            }
            return
        }
        if (videos.isNullOrEmpty() && pgc.isEmpty()) {
            VideoCardUi.toggleSkeleton(skeletonGrid, false)
            searchEndReached = true
            if (filterDuration != 0 || sortMode != SortMode.DEFAULT) {
                showState("没有找到符合当前筛选的公开视频", actionText = "重置筛选") { resetFilters() }
            } else {
                showState("没有找到相关公开视频")
            }
            return
        }
        hideState()
        // 同一帧内先落定 PGC 区高度、再提交视频行，骨架屏淡出期间画面静止
        renderPgcHeader(pgc)
        if (videos.isNullOrEmpty()) {
            searchEndReached = true
            resultAdapter.replace(emptyList(), animateNew = false)
        } else {
            resultAdapter.replace(videos, animateNew = true)
        }
        VideoCardUi.toggleSkeleton(skeletonGrid, false)
    }

    private fun cancelFirstScreenTimeout() {
        firstScreenTimeout?.let { mainHandler.removeCallbacks(it) }
        firstScreenTimeout = null
    }

    /** 作废本轮首屏聚合（新搜索/退出结果页/销毁时调用）。 */
    private fun cancelFirstScreenGate() {
        cancelFirstScreenTimeout()
        firstScreen = null
    }

    private class FirstScreen(val keyword: String, val generation: Int) {
        var videos: List<VideoItem>? = null
        var error: String? = null
        var ugcSettled = false
        var pgcItems: List<PgcMedia> = emptyList()
        var pgcSettled = false
        var committed = false
    }

    private fun renderPgcHeader(items: List<PgcMedia>) {
        clearPgcHeader()
        if (items.isEmpty()) return
        pgcHeader.findViewById<TextView>(PGC_COUNT_TITLE_ID)?.text = "共 ${items.size} 部"
        for (media in items) {
            pgcRow.addView(createPgcCard(media), LinearLayout.LayoutParams(dp(106), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
        pgcHeader.visibility = View.VISIBLE
    }

    private fun createPgcCard(media: PgcMedia): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@SearchActivity, Ds.colors.surface,
                if (Theme.isDark) Ds.colors.lineSoft else Ds.colors.line, Ds.Radius.hairline)
            Ds.clipRounded(this, Ds.Radius.hairline)
            elevation = dp(2).toFloat()
            setPadding(Ds.hairline(this@SearchActivity), Ds.hairline(this@SearchActivity), Ds.hairline(this@SearchActivity), Ds.hairline(this@SearchActivity))
            isClickable = true
            isFocusable = true
            contentDescription = "${media.typeName}：${media.title}，评分 ${media.score}"
            Ds.bindPressFeedback(this, 0.965f)
            setOnClickListener {
                if (media.seasonId > 0L) PgcDetailActivity.open(this@SearchActivity, media.seasonId)
            }
        }
        val coverFrame = FrameLayout(this).apply { setBackgroundColor(Ds.colors.cover) }
        val cover = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
        coverFrame.addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(141)))
        // 类型角标
        if (media.typeName.isNotBlank()) {
            val badge = TextView(this).apply {
                text = media.typeName
                textSize = 9.5f
                typeface = Ds.body(bold = true)
                setTextColor(0xF5FFFFFF.toInt())
                includeFontPadding = false
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = Ds.pill(this@SearchActivity, 0xB318191C.toInt(), 0xB318191C.toInt())
            }
            coverFrame.addView(badge, FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(5); topMargin = dp(5)
            })
        }
        // 评分角标
        if (media.score.isNotBlank()) {
            coverFrame.addView(TextView(this).apply {
                text = "${media.score}分"
                textSize = 11f
                typeface = Ds.display(bold = true)
                setTextColor(0xFFFFCA58.toInt())
                includeFontPadding = false
                fontFeatureSettings = "tnum"
                setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.BLACK)
            }, FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(6); bottomMargin = dp(4)
            })
        }
        card.addView(coverFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(141)))
        val title = TextView(this).apply {
            text = media.title
            textSize = 13f
            typeface = Ds.body(bold = true)
            setTextColor(Ds.colors.text)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(7), dp(6), dp(7), 0)
        }
        card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        val metaText = when {
            media.epCount > 0 -> "全${media.epCount}话"
            media.areas.isNotBlank() -> media.areas
            else -> media.typeName
        }
        card.addView(TextView(this).apply {
            text = metaText
            textSize = 10.5f
            typeface = Ds.body()
            setTextColor(Ds.colors.muted)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(7), 0, dp(7), dp(5))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.tag = PgcCardHolder(cover)
        // PGC 海报多为竖版，禁用 1c 裁剪，按原比例完整显示
        CoverLoader.load(cover, media.cover, cropThumb = false, thumbEdge = 384)
        return card
    }

    private class PgcCardHolder(val cover: android.widget.ImageView)

    // ─────────────────────────────────────────────────
    //  直播 / 用户搜索
    // ─────────────────────────────────────────────────

    private fun performScopeSearch(initial: Boolean) {
        val keyword = currentSearchKeyword
        if (keyword.isBlank() || searchScope == SearchScope.VIDEO) return
        if (!initial && (altLoadingNext || altEndReached)) return
        val generation = altGeneration
        val page = if (initial) 1 else altPage + 1
        val scope = searchScope

        if (initial) {
            showState(if (scope == SearchScope.LIVE) "正在搜索直播…" else "正在搜索用户…")
        } else {
            altLoadingNext = true
            pagingSpinner.visibility = View.VISIBLE
            pagingSpinner.startSpinning()
        }

        val deliver: (List<Any>?, String?) -> Unit = { items, error ->
            handleScopeResult(scope, keyword, generation, page, initial, items, error)
        }
        when (scope) {
            SearchScope.LIVE -> BiliApi.searchLive(keyword, page) { list, error ->
                deliver(list?.map { it as Any }, error)
            }
            SearchScope.USER -> BiliApi.searchUsers(keyword, page) { list, error ->
                deliver(list?.map { it as Any }, error)
            }
            SearchScope.VIDEO -> Unit
        }
    }

    private fun handleScopeResult(
        scope: SearchScope,
        keyword: String,
        generation: Int,
        page: Int,
        initial: Boolean,
        items: List<Any>?,
        error: String?
    ) {
        if (generation != altGeneration || !showingResultPage ||
            currentSearchKeyword != keyword || searchScope != scope
        ) return
        if (!initial) {
            altLoadingNext = false
            pagingSpinner.visibility = View.GONE
        } else {
            searchInProgress = false
            hideLoading()
        }

        if (items == null) {
            if (initial) {
                if (altAdapter.count == 0) {
                    showState(
                        message = "搜索暂不可用：${error ?: "网络异常"}",
                        actionText = "在官方搜索页继续"
                    ) { openOfficialSearch(keyword) }
                }
            }
            return
        }

        if (initial) {
            hideState()
            altPage = page
            altAdapter.submit(items)
            altResults.setSelection(0)
            topButton.setShown(false, animate = false)
            if (items.isEmpty()) {
                showState(
                    if (scope == SearchScope.LIVE) "没有找到正在直播的房间" else "没有找到相关用户"
                )
            }
            if (items.isEmpty()) altEndReached = true
        } else {
            if (items.isEmpty()) {
                altEndReached = true
                return
            }
            altPage = page
            altAdapter.append(items)
        }
    }

    private fun loadNextScopePage() {
        if (searchScope == SearchScope.VIDEO) return
        if (!showingResultPage || altLoadingNext || altEndReached || currentSearchKeyword.isBlank()) return
        performScopeSearch(initial = false)
    }

    private fun openOfficialSearch(keyword: String) {
        startActivity(
            Intent(this@SearchActivity, PlayerActivity::class.java).putExtra(
                PlayerActivity.EXTRA_URL,
                "https://search.bilibili.com/all?keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}"
            )
        )
    }

    /** 直播 / 用户结果列表适配器：两种条目共用一个 ListView。 */
    private inner class ScopeSearchAdapter : BaseAdapter() {
        private val items = ArrayList<Any>()

        fun submit(list: List<Any>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun append(list: List<Any>) {
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val item = items[position]) {
                is LiveSearchItem -> liveSearchRow(item)
                is UserSearchItem -> userSearchRow(item)
                else -> View(this@SearchActivity)
            }
        }
    }

    private fun searchRowShell(content: View): View = FrameLayout(this).apply {
        setPadding(dp(2), dp(3), dp(2), dp(3))
        addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun liveSearchRow(item: LiveSearchItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(this@SearchActivity, COLOR_SURFACE, Ds.colors.lineSoft, Ds.Radius.card)
            Ds.clipRounded(this, Ds.Radius.card)
            setPadding(dp(8), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = "直播：${item.title}，${item.owner}"
            Ds.bindPressFeedback(this, 0.97f)
            setOnClickListener {
                if (!item.live) {
                    android.widget.Toast.makeText(
                        this@SearchActivity, "该直播间暂未开播", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                LiveRoomActivity.open(
                    this@SearchActivity,
                    LiveRoom(item.roomId, item.uid, item.title, item.owner, item.cover, item.face, item.area, item.online)
                )
            }
        }

        val coverFrame = FrameLayout(this).apply {
            setBackgroundColor(Ds.colors.cover)
            Ds.clipRounded(this, 8)
        }
        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        coverFrame.addView(cover, FrameLayout.LayoutParams(dp(132), dp(74)))
        if (item.live) {
            coverFrame.addView(TextView(this).apply {
                text = "直播中"
                textSize = 9f
                typeface = Ds.body(true)
                setTextColor(Color.WHITE)
                includeFontPadding = false
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = Ds.pill(this@SearchActivity, 0xE6F04A5E.toInt())
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = dp(5)
                topMargin = dp(5)
            })
        }
        card.addView(coverFrame, LinearLayout.LayoutParams(dp(132), dp(74)))
        if (item.cover.isNotBlank()) CoverLoader.load(cover, item.cover, thumbEdge = 320)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        info.addView(TextView(this).apply {
            text = item.title
            textSize = 13.5f
            typeface = Ds.body(true)
            setTextColor(Ds.colors.text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val meta = buildString {
            append(item.owner)
            if (item.online > 0) append(" · ").append(formatScopeCount(item.online)).append("人气")
            if (item.area.isNotBlank()) append(" · ").append(item.area)
        }
        info.addView(TextView(this).apply {
            text = meta
            textSize = 11f
            typeface = Ds.body()
            setTextColor(Ds.colors.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(5), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return searchRowShell(card)
    }

    private fun userSearchRow(item: UserSearchItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(this@SearchActivity, COLOR_SURFACE, Ds.colors.lineSoft, Ds.Radius.card)
            Ds.clipRounded(this, Ds.Radius.card)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = "用户：${item.name}"
            Ds.bindPressFeedback(this, 0.97f)
            setOnClickListener {
                SpaceActivity.open(this@SearchActivity, item.mid, item.name, item.face)
            }
        }
        val avatarFrame = FrameLayout(this).apply {
            setBackgroundColor(Ds.colors.cover)
            Ds.clipRounded(this, 24)
        }
        val avatar = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        avatarFrame.addView(avatar, FrameLayout.LayoutParams(dp(48), dp(48)))
        if (item.face.isNotBlank()) CoverLoader.load(avatar, item.face, thumbEdge = 160)
        card.addView(avatarFrame, LinearLayout.LayoutParams(dp(48), dp(48)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(this).apply {
            text = item.name
            textSize = 13.5f
            typeface = Ds.body(true)
            setTextColor(Ds.colors.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (item.live) {
            nameRow.addView(TextView(this).apply {
                text = "直播中"
                textSize = 9f
                typeface = Ds.body(true)
                setTextColor(0xFFF04A5E.toInt())
                background = Ds.pill(this@SearchActivity, 0x1FF04A5E)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        info.addView(nameRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val meta = buildString {
            append(formatScopeCount(item.fans)).append("粉丝")
            if (item.videos > 0) append(" · ").append(item.videos).append(" 视频")
            if (item.verify.isNotBlank()) append(" · ").append(item.verify)
        }
        info.addView(TextView(this).apply {
            text = meta
            textSize = 11f
            typeface = Ds.body()
            setTextColor(Ds.colors.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (item.sign.isNotBlank()) {
            info.addView(TextView(this).apply {
                text = item.sign
                textSize = 11f
                typeface = Ds.body()
                setTextColor(Ds.colors.subtle)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(2), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        card.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return searchRowShell(card)
    }

    private fun formatScopeCount(value: Long): String = when {
        value >= 100_000_000L -> String.format("%.1f亿", value / 100_000_000.0)
        value >= 10_000L -> String.format("%.1f万", value / 10_000.0)
        else -> value.toString()
    }

    private fun loadNextSearchPage() {
        if (!showingResultPage || searchInProgress || loadingNextPage || searchEndReached || currentSearchKeyword.isBlank()) return
        requestSearchPage(page = searchPage + 1, initial = false)
    }

    private fun requestSearchPage(page: Int, initial: Boolean) {
        val keyword = currentSearchKeyword
        val requestGeneration = searchGeneration
        if (!initial) {
            loadingNextPage = true
            pagingSpinner.visibility = View.VISIBLE
            pagingSpinner.startSpinning()
        }
        BiliApi.search(keyword, page, sortMode.orderParam, searchDurationParam()) callback@{ videos, error ->
            if (requestGeneration != searchGeneration || !showingResultPage || currentSearchKeyword != keyword) return@callback
            if (!initial) {
                loadingNextPage = false
                pagingSpinner.visibility = View.GONE
            }
            if (initial) {
                // 首屏不直接渲染：交给聚合器与 PGC 一起提交，避免先后插入导致下推
                val gate = firstScreen
                if (gate == null || gate.generation != requestGeneration) return@callback
                if (videos == null) {
                    gate.videos = null
                    gate.error = error
                    gate.ugcSettled = true
                    commitFirstScreenIfReady()
                    return@callback
                }
                if (videos.isEmpty()) {
                    gate.videos = emptyList()
                    gate.ugcSettled = true
                    commitFirstScreenIfReady()
                    return@callback
                }
                val additions = applyClientFilters(videos.filter { it.bvid.isNotBlank() })
                searchPage = page
                if (additions.isEmpty() && filterDuration != 0 && page < SEARCH_FILTER_SCAN_PAGES) {
                    // 精确时长筛选在客户端做，多扫几页避免稀疏筛选直接空白；
                    // 骨架屏与 PGC 聚合保持挂起，扫到有效结果再一次性提交。
                    requestSearchPage(page + 1, initial = true)
                    return@callback
                }
                gate.videos = additions
                gate.ugcSettled = true
                commitFirstScreenIfReady()
                return@callback
            }
            if (videos == null) return@callback
            if (videos.isEmpty()) {
                searchEndReached = true
                return@callback
            }

            val known = resultAdapter.bvids()
            val additions = applyClientFilters(videos.filter { it.bvid.isNotBlank() && it.bvid !in known })
            searchPage = page
            if (additions.isNotEmpty()) {
                val firstVisible = results.firstVisiblePosition
                val firstTop = results.getChildAt(0)?.top ?: results.paddingTop
                val removed = resultAdapter.appendWindowed(additions)
                if (removed > 0) {
                    val removedRows = removed / 2
                    val target = if (firstVisible > 0) (firstVisible - removedRows).coerceAtLeast(1) else 0
                    results.setSelectionFromTop(target, firstTop)
                }
            }
        }
    }

    // Bilibili's duration parameter uses disjoint ranges and cannot represent
    // 5+/10+/30+ accurately. Keep the server query broad and apply exact minimums locally.
    private fun searchDurationParam(): Int = 0

    private fun applyClientFilters(items: List<VideoItem>): List<VideoItem> {
        val minimumSeconds = when (filterDuration) {
            1 -> 5 * 60
            2 -> 10 * 60
            3 -> 30 * 60
            4 -> 60 * 60
            else -> 0
        }
        val filtered = if (minimumSeconds > 0) items.filter { it.duration >= minimumSeconds } else items
        return when (sortMode) {
            SortMode.LATEST -> filtered.sortedByDescending { it.publishedAt }
            SortMode.PLAY_DESC -> filtered.sortedByDescending { it.views }
            SortMode.DEFAULT -> filtered
        }
    }

    private fun pulseSearchAction() {
        if (!::searchAction.isInitialized) return
        searchAction.animate().cancel()
        searchAction.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(70L)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .withEndAction {
                searchAction.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150L)
                    .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                    .start()
            }
            .start()
    }

    private fun showLoading() {
        searchAction.visibility = View.INVISIBLE
        loadingSpinner.visibility = View.VISIBLE
        loadingSpinner.startSpinning()
    }

    private fun hideLoading() {
        if (!::loadingSpinner.isInitialized) return
        loadingSpinner.visibility = View.GONE
        searchAction.visibility = View.VISIBLE
    }

    private fun navigateBack() {
        if (showingResultPage) {
            resetToSearchEntry()
        } else {
            collapseToHome()
        }
    }

    /** 迟滞显隐：结果页且非加载中时才可能显示，避免骨架屏/历史页残留。 */
    private fun updateTopButton(firstVisible: Int) {
        if (!::topButton.isInitialized) return
        val allow = showingResultPage && !searchInProgress
        val visible = allow && nextScrollTopVisible(
            currentlyVisible = topButton.visibility == View.VISIBLE,
            firstVisiblePosition = firstVisible,
            showAt = 4,
            hideAt = 1
        )
        topButton.setShown(visible)
    }

    private fun resetToSearchEntry() {
        searchGeneration += 1
        altGeneration += 1
        pgcRequestGeneration += 1
        cancelFirstScreenGate()
        clearPgcHeader()
        searchInProgress = false
        loadingNextPage = false
        searchEndReached = false
        altLoadingNext = false
        altEndReached = false
        altPage = 0
        currentSearchKeyword = ""
        searchPage = 0
        pagingSpinner.visibility = View.GONE
        showingResultPage = false
        topScroller?.cancel()
        topButton.setShown(false, animate = false)
        hideFilterSheet()
        filterBar.animate().cancel()
        filterBar.visibility = View.GONE
        hideScopeBar()
        hideLoading()
        VideoCardUi.toggleSkeleton(skeletonGrid, false)
        hideSuggestions()
        resultAdapter.replace(emptyList(), animateNew = false)
        altAdapter.submit(emptyList())
        results.visibility = View.VISIBLE
        altResults.visibility = View.GONE
        showState("修改关键词后再次搜索")
        input.requestFocus()
        input.setSelection(input.text.length)
        showSearchHistory()
        requestSuggestions(input.text.toString().trim())
    }

    private fun collapseToHome() {
        hideKeyboard()
        finishAfterTransition()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    fun requestInputFocus(showKeyboard: Boolean) {
        if (!::input.isInitialized || isFinishing) return
        entryFocusDispatched = true
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.requestFocus()
        try { input.setSelection(input.text.length) } catch (_: Exception) {}
        if (showKeyboard) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            // SHOW_FORCED 在从首页点进搜索时更稳，避免共享元素过渡吞掉键盘。
            @Suppress("DEPRECATION")
            imm?.showSoftInput(input, InputMethodManager.SHOW_FORCED)
            input.post {
                if (!isFinishing) {
                    input.requestFocus()
                    imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            input.postDelayed({
                if (!isFinishing) {
                    if (!input.hasFocus()) input.requestFocus()
                    imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 220L)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !showingResultPage && !presetQueryMode) {
            // 窗口真正拿到焦点后再补一次，覆盖过渡动画后的焦点丢失。
            requestInputFocus(showKeyboard = true)
        }
    }

    private fun configureTransitions() {
        val enter = ChangeBounds().apply {
            duration = SEARCH_TRANSITION_DURATION_MS
            interpolator = PathInterpolator(0.16f, 0.84f, 0.24f, 1f)
            addListener(object : Transition.TransitionListener {
                override fun onTransitionStart(transition: Transition?) = Unit
                override fun onTransitionCancel(transition: Transition?) = Unit
                override fun onTransitionPause(transition: Transition?) = Unit
                override fun onTransitionResume(transition: Transition?) = Unit
                override fun onTransitionEnd(transition: Transition?) {
                    removeListener(this)
                    if (!entryFocusDispatched && !presetQueryMode) requestInputFocus(showKeyboard = true)
                }
            })
        }
        val exit = ChangeBounds().apply {
            duration = SEARCH_TRANSITION_DURATION_MS
            interpolator = PathInterpolator(0.16f, 0.84f, 0.24f, 1f)
        }
        window.sharedElementEnterTransition = enter
        window.sharedElementReturnTransition = exit
        // Surrounding controls fade in a little later so they do not visually
        // compete with the now-stationary shared search field.
        window.enterTransition = Fade().apply {
            startDelay = 60L
            duration = SEARCH_SURFACE_FADE_MS
        }
        window.returnTransition = Fade().apply { duration = SEARCH_SURFACE_FADE_MS }
    }

    /** Lightweight wrapping layout for local-history bubbles & filter duration chips. */
    private inner class HistoryChipLayout(context: Context) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            var cursorX = 0
            var cursorY = 0
            var rowHeight = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == View.GONE) continue
                child.measure(
                    MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                if (cursorX > 0 && cursorX + child.measuredWidth > availableWidth) {
                    cursorX = 0
                    cursorY += rowHeight + dp(HISTORY_CHIP_GAP)
                    rowHeight = 0
                }
                cursorX += child.measuredWidth + dp(HISTORY_CHIP_GAP)
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
            val contentHeight = if (childCount == 0) 0 else cursorY + rowHeight
            setMeasuredDimension(
                resolveSize(availableWidth + paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec)
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val availableWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
            var cursorX = paddingLeft
            var cursorY = paddingTop
            var rowHeight = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == View.GONE) continue
                if (cursorX > paddingLeft && cursorX + child.measuredWidth > paddingLeft + availableWidth) {
                    cursorX = paddingLeft
                    cursorY += rowHeight + dp(HISTORY_CHIP_GAP)
                    rowHeight = 0
                }
                child.layout(cursorX, cursorY, cursorX + child.measuredWidth, cursorY + child.measuredHeight)
                cursorX += child.measuredWidth + dp(HISTORY_CHIP_GAP)
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
        }
    }

    /** 搜索结果复用与首页完全同源的 VideoCardUi 卡片（手机 2 列，平板自适应 3~4 列）。 */
    private inner class SearchVideoAdapter : BaseAdapter() {
        private val videos = ArrayList<VideoItem>()
        private val ids = HashSet<String>()
        private val pendingAnimations = HashSet<String>()
        private val reduced = MotionTokens.isReduced(this@SearchActivity)
        private val cols = searchColumns
        private val cardWidth = VideoCardUi.cardWidth(this@SearchActivity, cols, searchAvailableWidthPx)
        private val coverHeight = VideoCardUi.coverHeight(cardWidth, compactTablet = isTabletLayout)
        private val cardHeight = VideoCardUi.cardHeight(coverHeight, this@SearchActivity, compactTablet = isTabletLayout)

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
            results.post { results.setSelection(0) }
        }

        fun bvids(): Set<String> = videos.mapTo(LinkedHashSet()) { it.bvid }

        fun appendWindowed(items: List<VideoItem>): Int {
            if (items.isEmpty()) return 0
            val removeCount = FeedPagingPolicy.trimFromStart(videos.size, items.size)
            if (removeCount > 0) {
                val dropped = videos.subList(0, removeCount)
                for (item in dropped) {
                    ids.remove(item.bvid)
                    pendingAnimations.remove(item.bvid)
                }
                dropped.clear()
            }
            videos.addAll(items)
            items.mapTo(ids) { it.bvid }
            if (!reduced) items.mapTo(pendingAnimations) { it.bvid }
            notifyDataSetChanged()
            return removeCount
        }

        override fun getCount(): Int = (videos.size + cols - 1) / cols
        override fun getItem(position: Int): VideoItem? = videos.getOrNull(position * cols)
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val holders: List<VideoCardUi.Holder?>
            if (convertView == null) {
                row = LinearLayout(this@SearchActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    setPadding(0, 0, 0, dp(VideoCardUi.CARD_SPACING_DP))
                }
                val list = ArrayList<VideoCardUi.Holder>(cols)
                for (c in 0 until cols) {
                    val card = createBoundCard()
                    row.addView(card, LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                        if (c < cols - 1) rightMargin = dp(VideoCardUi.CARD_SPACING_DP)
                    })
                    list.add(card.tag as VideoCardUi.Holder)
                }
                holders = list
                row.tag = holders
            } else {
                row = convertView as LinearLayout
                holders = row.tag as List<VideoCardUi.Holder?>
            }
            val base = position * cols
            for (c in 0 until cols) {
                bindCard(holders.getOrNull(c), row.getChildAt(c), base + c, position)
            }
            return row
        }

        private fun createBoundCard(): LinearLayout =
            VideoCardUi.createCard(this@SearchActivity, cardWidth, coverHeight, cardHeight).apply {
                setOnClickListener {
                    (tag as? VideoCardUi.Holder)?.boundVideo?.let { video ->
                        VideoDetailActivity.open(this@SearchActivity, video.bvid, video.aid, video.cid)
                    }
                }
            }

        private fun bindCard(holder: VideoCardUi.Holder?, card: View?, index: Int, rowPosition: Int) {
            if (holder == null || card == null) return
            val video = videos.getOrNull(index)
            if (video == null) {
                card.visibility = View.INVISIBLE
                card.isClickable = false
                holder.boundVideo = null
                CoverLoader.clear(holder.cover)
                return
            }
            card.visibility = View.VISIBLE
            card.isClickable = true
            VideoCardUi.bind(
                card = card as LinearLayout,
                holder = holder,
                video = video,
                showPublishDate = true,
                contentDescriptionPrefix = SEARCH_CARD_ROW
            )
            if (pendingAnimations.remove(video.bvid)) {
                card.alpha = 0f
                card.translationY = dp(10).toFloat()
                card.animate().alpha(1f).translationY(0f)
                    .setStartDelay((rowPosition % 2) * 28L)
                    .setDuration(220L)
                    .setInterpolator(MotionTokens.easeOut).start()
            }
        }
    }

    private fun configureBars() {
        Theme.init(this)
        Theme.applySystemBars(this)
    }

    companion object {
        const val EXTRA_QUERY = "search_query"
        const val SUGGEST_DEBOUNCE_MS = 260L
        const val MAX_SUGGESTIONS = 8
        const val SEARCH_LOAD_AHEAD_ROWS = 2
        const val MAX_PGC_CARDS = 8
        const val PGC_COUNT_TITLE_ID = 0x50474301
        const val SEARCH_FILTER_SCAN_PAGES = 4
        /** PGC 首屏宽限窗：UGC 先回时最多等这么久，超时则本轮不插番剧区，避免后插下推。 */
        const val PGC_FIRST_SCREEN_GRACE_MS = 260L
        const val SEARCH_CARD_ROW = "search_card_row"
        const val SEARCH_BOX_HEIGHT_DP = 40
        /** 搜索类型栏（视频/直播/用户）每个标签的固定宽度，指示器按此平移。 */
        const val SCOPE_TAB_WIDTH_DP = 58
        const val SEARCH_BOX_TEXT_SIZE_SP = 14f
        const val SEARCH_BOX_TRANSITION_NAME = "yui_search_box"
        const val SEARCH_TRANSITION_DURATION_MS = 220L
        const val SEARCH_SURFACE_FADE_MS = 160L
        const val SEARCH_BUTTON_PULSE_MS = 90L
        const val SUGGESTION_EXPAND_DURATION_MS = 170L
        const val SUGGESTION_COLLAPSE_DURATION_MS = 120L
        const val HISTORY_CHIP_GAP = 8

        /** 打开搜索页并直接搜索指定关键词（评论区热词等）。 */
        fun openWithQuery(context: android.content.Context, query: String) {
            if (query.isBlank()) return
            context.startActivity(
                Intent(context, SearchActivity::class.java).putExtra(EXTRA_QUERY, query.trim())
            )
        }
    }
}
