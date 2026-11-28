package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 平板端内嵌「搜索」主舞台视图（挂载在 TabletRailNav 右侧，不跳出主壳层）：
 * - 顶部：居中搜索输入胶囊 + 搜索按钮；搜索后显示排序（综合/最多播放/最新发布）与时长筛选胶囊条；
 * - 未搜索态：历史搜索流式标签卡 + 实时搜索建议；
 * - 结果态：上方横向番剧/影视卡带 + 下方 4 列视频卡片网格 + 自动翻页 + 右下角悬浮回顶按钮。
 */
internal class TabletSearchPageView(
    private val activity: Activity
) : FrameLayout(activity) {

    private enum class SortMode(val label: String, val orderParam: String) {
        DEFAULT("综合", ""),
        PLAY_DESC("最多播放", "click"),
        LATEST("最新发布", "pubdate")
    }

    /** 搜索对象分类：视频沿用排序/时长，直播与用户走独立列表。 */
    private enum class SearchScope(val label: String) {
        VIDEO("视频"),
        LIVE("直播"),
        USER("用户")
    }

    private var searchScope = SearchScope.VIDEO
    private lateinit var scopeRow: LinearLayout
    private lateinit var scopeViews: List<TextView>
    private lateinit var altList: ListView
    private lateinit var altAdapter: ScopeAdapter
    private var altPage = 0
    private var altLoading = false
    private var altEnd = false

    private val durationLabels = listOf("全部时长", "5分钟+", "10分钟+", "30分钟+", "1小时+")

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var input: EditText
    private lateinit var clearBtn: View
    private lateinit var filterRow: LinearLayout
    private lateinit var sortViews: List<TextView>
    private lateinit var durationViews: List<TextView>
    private lateinit var idleScroll: ScrollView
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyFlow: ChipFlowLayout
    private lateinit var suggestionBox: LinearLayout
    private lateinit var resultsHost: FrameLayout
    private lateinit var resultsList: ListView
    private lateinit var pgcHeader: LinearLayout
    private lateinit var pgcRow: LinearLayout
    private lateinit var skeletonOverlay: FrameLayout
    private lateinit var stateView: TextView
    private lateinit var pagingSpinner: BrandSpinnerView
    private lateinit var topButton: ScrollToTopButton
    private var topScroller: AbsListSmoothTopScroller? = null
    private lateinit var adapter: GridAdapter

    private var sortMode = SortMode.DEFAULT
    private var filterDuration = 0
    private var currentKeyword = ""
    private var showingResults = false
    private var searchGen = 0
    private var searchPage = 0
    private var searching = false
    private var loadingMore = false
    private var endReached = false
    private var pendingSuggest: Runnable? = null

    private val availableWidthPx: Int
        get() = (resources.displayMetrics.widthPixels - dp(TabletRailNav.RAIL_WIDTH_DP + 36)).coerceAtLeast(dp(480))
    private val columns: Int
        get() = LayoutMode.gridColumns(activity, availableWidthPx)

    init {
        setBackgroundColor(COLOR_BACKGROUND)
        buildUi()
        refreshHistory()
    }

    fun onPageActivated() {
        refreshHistory()
    }

    fun handleBack(): Boolean {
        if (showingResults) {
            resetToIdle()
            return true
        }
        return false
    }

    fun destroy() {
        pendingSuggest?.let(mainHandler::removeCallbacks)
        topScroller?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun buildUi() {
        val c = Ds.colors
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), 0)
        }

        // ── 1. 顶部搜索框行 ──
        val searchBarRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val field = FrameLayout(activity).apply {
            background = Ds.pill(activity, c.surface, c.lineSoft, Ds.hairline(activity))
        }
        field.addView(
            Ds.searchIcon(activity, c.muted, 1.8f),
            FrameLayout.LayoutParams(dp(16), dp(16), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(16)
            }
        )
        input = EditText(activity).apply {
            hint = "搜索公开视频、番剧、UP主…"
            textSize = 14.5f
            typeface = Ds.body()
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setTextColor(c.text)
            setHintTextColor(c.muted)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, event ->
                val go = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (go) {
                    submitSearch(text?.toString().orEmpty())
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString().orEmpty()
                    clearBtn.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                    if (q.isBlank()) {
                        suggestionBox.visibility = View.GONE
                        if (showingResults) resetToIdle()
                    } else if (!showingResults) {
                        scheduleSuggest(q.trim())
                    }
                }
            })
        }
        field.addView(input, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            leftMargin = dp(40)
            rightMargin = dp(40)
        })
        clearBtn = FrameLayout(activity).apply {
            visibility = View.GONE
            isClickable = true
            Ds.bindPressFeedback(this, 0.90f)
            addView(Ds.closeIcon(activity, c.muted, 1.7f), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            setOnClickListener {
                input.setText("")
                resetToIdle()
                input.requestFocus()
            }
        }
        field.addView(clearBtn, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(4)
        })
        searchBarRow.addView(field, LinearLayout.LayoutParams(0, dp(42), 1f))

        val searchBtn = TextView(activity).apply {
            text = "搜索"
            textSize = 14f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(c.onAccent)
            background = Ds.pill(activity, c.accent)
            setPadding(dp(22), 0, dp(22), 0)
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { submitSearch(input.text?.toString().orEmpty()) }
        }
        searchBarRow.addView(searchBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)
        ).apply { leftMargin = dp(10) })
        col.addView(searchBarRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── 2. 搜索类型栏（视频 / 直播 / 用户），位于排序栏之上 ──
        scopeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, dp(2))
        }
        val scopeChips = ArrayList<TextView>()
        SearchScope.entries.forEachIndexed { idx, scope ->
            val tv = TextView(activity).apply {
                text = scope.label
                textSize = 12.5f
                typeface = Ds.body(true)
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(6), dp(18), dp(6))
                isClickable = true
                Ds.bindPressFeedback(this, 0.94f)
                setOnClickListener { selectScope(scope) }
            }
            scopeChips += tv
            scopeRow.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (idx > 0) leftMargin = dp(6) })
        }
        scopeViews = scopeChips
        col.addView(scopeRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        updateScopeUi()

        // ── 3. 筛选条（平板直接一排平铺：排序胶囊 | 时长筛选胶囊，无需手机端底部弹层） ──
        filterRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, dp(8))
        }
        val sortChips = ArrayList<TextView>()
        SortMode.entries.forEachIndexed { idx, mode ->
            val tv = TextView(activity).apply {
                text = mode.label
                textSize = 12.5f
                typeface = Ds.body(true)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(5), dp(12), dp(5))
                isClickable = true
                Ds.bindPressFeedback(this, 0.94f)
                setOnClickListener {
                    if (sortMode != mode) {
                        sortMode = mode
                        updateFilterUi()
                        if (currentKeyword.isNotBlank()) runSearch(currentKeyword, keepPgc = true)
                    }
                }
            }
            sortChips.add(tv)
            filterRow.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (idx > 0) leftMargin = dp(6) })
        }
        sortViews = sortChips

        filterRow.addView(View(activity).apply { setBackgroundColor(c.lineSoft) }, LinearLayout.LayoutParams(
            Ds.hairline(activity), dp(16)
        ).apply { leftMargin = dp(12); rightMargin = dp(12) })

        val durScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val durRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val durChips = ArrayList<TextView>()
        durationLabels.forEachIndexed { idx, label ->
            val tv = TextView(activity).apply {
                text = label
                textSize = 12f
                typeface = Ds.body(true)
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(5), dp(11), dp(5))
                isClickable = true
                Ds.bindPressFeedback(this, 0.94f)
                setOnClickListener {
                    if (filterDuration != idx) {
                        filterDuration = idx
                        updateFilterUi()
                        if (currentKeyword.isNotBlank()) runSearch(currentKeyword, keepPgc = true)
                    }
                }
            }
            durChips.add(tv)
            durRow.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (idx > 0) leftMargin = dp(6) })
        }
        durationViews = durChips
        durScroll.addView(durRow)
        filterRow.addView(durScroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        updateFilterUi()
        col.addView(filterRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── 3. 主内容堆叠区：空闲（历史/建议） vs 结果网格 ──
        val stage = FrameLayout(activity)

        idleScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val idleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(32))
        }
        suggestionBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = Ds.card(activity, c.surface, c.lineSoft, Ds.Radius.card)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        idleCol.addView(suggestionBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        historyContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(activity, c.surface, c.lineSoft, Ds.Radius.card)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val histHead = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        histHead.addView(TextView(activity).apply {
            text = "搜索历史"
            textSize = 14.5f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        histHead.addView(TextView(activity).apply {
            text = "清空"
            textSize = 12.5f
            typeface = Ds.body(true)
            setTextColor(c.muted)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener {
                SearchHistoryStore.clear(activity)
                refreshHistory()
            }
        })
        historyContainer.addView(histHead, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        historyFlow = ChipFlowLayout(activity)
        historyContainer.addView(historyFlow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        idleCol.addView(historyContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        idleScroll.addView(idleCol)
        stage.addView(idleScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        resultsHost = FrameLayout(activity).apply { visibility = View.GONE }
        adapter = GridAdapter()
        resultsList = ListView(activity).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(4), 0, dp(28))
            clipToPadding = false
            val headerSlot = FrameLayout(activity)
            pgcHeader = buildPgcHeader()
            headerSlot.addView(pgcHeader, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            addHeaderView(headerSlot, null, false)
            this.adapter = this@TabletSearchPageView.adapter
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
                    if (::topButton.isInitialized) {
                        val show = showingResults && !searching &&
                            nextScrollTopVisible(topButton.visibility == View.VISIBLE, firstVisible, showAt = 3, hideAt = 1)
                        topButton.setShown(show)
                    }
                    if (showingResults && !searching && !loadingMore && !endReached &&
                        topScroller?.isRunning != true && totalCount > 0 &&
                        firstVisible + visibleCount >= totalCount - 2
                    ) {
                        loadNextPage()
                    }
                }
            })
        }
        resultsHost.addView(resultsList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        topScroller = AbsListSmoothTopScroller(resultsList, nearPosition = 6)

        // 直播 / 用户结果列表：与视频网格互斥，共用同一结果舞台
        altAdapter = ScopeAdapter()
        altList = ListView(activity).apply {
            divider = null
            dividerHeight = 0
            visibility = View.GONE
            setSelector(android.R.color.transparent)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(4), 0, dp(28))
            clipToPadding = false
            adapter = altAdapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView?, firstVisible: Int, visibleCount: Int, totalCount: Int) {
                    if (searchScope == SearchScope.VIDEO) return
                    if (::topButton.isInitialized) {
                        val show = showingResults && !searching &&
                            nextScrollTopVisible(topButton.visibility == View.VISIBLE, firstVisible, showAt = 3, hideAt = 1)
                        topButton.setShown(show)
                    }
                    if (showingResults && !searching && !altLoading && !altEnd &&
                        totalCount > 0 && firstVisible + visibleCount >= totalCount - 2
                    ) {
                        loadNextScopePage()
                    }
                }
            })
        }
        resultsHost.addView(altList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        skeletonOverlay = FrameLayout(activity).apply {
            visibility = View.GONE
            isClickable = false
            addView(VideoCardUi.createSkeletonLayout(activity, this, columns, availableWidthPx))
        }
        resultsHost.addView(skeletonOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        stateView = TextView(activity).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(c.muted)
            visibility = View.GONE
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        resultsHost.addView(stateView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        pagingSpinner = BrandSpinnerView(activity, COLOR_ROSE, Color.WHITE).apply { visibility = View.GONE }
        resultsHost.addView(pagingSpinner, LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(18)
        })

        topButton = ScrollToTopButton(activity) {
            if (searchScope == SearchScope.VIDEO) topScroller?.scrollToTop()
            else altList.smoothScrollToPosition(0)
        }
        resultsHost.addView(topButton, LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(12)
            bottomMargin = dp(20)
        })

        stage.addView(resultsHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        col.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun buildPgcHeader(): LinearLayout {
        val c = Ds.colors
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(10))
        }
        wrap.addView(TextView(activity).apply {
            text = "番剧与影视"
            textSize = 13.5f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
            setPadding(dp(2), 0, 0, dp(8))
        })
        val hScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        pgcRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        hScroll.addView(pgcRow)
        wrap.addView(hScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return wrap
    }

    private fun bindPgcCards(list: List<PgcMedia>) {
        pgcRow.removeAllViews()
        if (list.isEmpty()) {
            pgcHeader.visibility = View.GONE
            return
        }
        val c = Ds.colors
        list.take(8).forEachIndexed { idx, media ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ds.card(activity, c.surface, c.lineSoft, Ds.Radius.control)
                setPadding(dp(10), dp(10), dp(12), dp(10))
                isClickable = true
                Ds.bindPressFeedback(this, 0.97f)
                setOnClickListener {
                    PgcDetailActivity.open(activity, media.seasonId)
                }
            }
            val poster = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(c.cover)
                Ds.clipRounded(this, Ds.Radius.hairline)
            }
            if (media.cover.isNotBlank()) CoverLoader.load(poster, media.cover, cropThumb = false, thumbEdge = 320)
            card.addView(poster, LinearLayout.LayoutParams(dp(58), dp(78)))
            val info = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            info.addView(TextView(activity).apply {
                text = media.title
                textSize = 13.5f
                typeface = Ds.display(bold = true)
                setTextColor(c.text)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            val meta = listOfNotNull(
                media.typeName.takeIf { it.isNotBlank() },
                media.score.takeIf { it.isNotBlank() }?.let { "${it}分" },
                media.epCount.takeIf { it > 0 }?.let { "共${it}集" }
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                info.addView(TextView(activity).apply {
                    text = meta
                    textSize = 11.5f
                    setTextColor(c.muted)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                })
            }
            card.addView(info, LinearLayout.LayoutParams(dp(156), LinearLayout.LayoutParams.WRAP_CONTENT))
            pgcRow.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (idx > 0) leftMargin = dp(10) })
        }
        pgcHeader.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────────────
    //  搜索类型（视频 / 直播 / 用户）
    // ─────────────────────────────────────────────────

    private fun updateScopeUi() {
        val c = Ds.colors
        SearchScope.entries.forEachIndexed { idx, scope ->
            val tv = scopeViews.getOrNull(idx) ?: return@forEachIndexed
            val active = scope == searchScope
            tv.setTextColor(if (active) c.accent else c.muted)
            tv.background = Ds.pill(
                activity,
                fill = if (active) c.accentSoft else Color.TRANSPARENT,
                border = if (active) (c.accent and 0x44FFFFFF) else Color.TRANSPARENT,
                borderPx = Ds.hairline(activity)
            )
        }
    }

    private fun selectScope(next: SearchScope) {
        if (searchScope == next) return
        searchScope = next
        updateScopeUi()
        applyScopeChrome()
        if (showingResults && currentKeyword.isNotBlank()) runSearch(currentKeyword, keepPgc = false)
    }

    private fun applyScopeChrome() {
        if (!::altList.isInitialized) return
        val video = searchScope == SearchScope.VIDEO
        resultsList.visibility = if (video) View.VISIBLE else View.GONE
        altList.visibility = if (!video && showingResults) View.VISIBLE else View.GONE
        filterRow.visibility = if (video && showingResults) View.VISIBLE else View.GONE
        if (!video) VideoCardUi.toggleSkeleton(skeletonOverlay, false)
    }

    private fun runScopeSearch(initial: Boolean) {
        if (searchScope == SearchScope.VIDEO || currentKeyword.isBlank()) return
        if (!initial && (altLoading || altEnd)) return
        val keyword = currentKeyword
        val gen = searchGen
        val page = if (initial) 1 else altPage + 1
        val scope = searchScope
        if (initial) {
            searching = true
            stateView.text = if (scope == SearchScope.LIVE) "正在搜索直播…" else "正在搜索用户…"
            stateView.visibility = View.VISIBLE
            stateView.setOnClickListener(null)
        } else {
            altLoading = true
            pagingSpinner.visibility = View.VISIBLE
            pagingSpinner.startSpinning()
        }
        val deliver: (List<Any>?, String?) -> Unit = { items, error ->
            onScopeResult(scope, gen, page, initial, items, error)
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

    private fun onScopeResult(
        scope: SearchScope,
        gen: Int,
        page: Int,
        initial: Boolean,
        items: List<Any>?,
        error: String?
    ) {
        if (gen != searchGen || !showingResults || searchScope != scope) return
        if (!initial) {
            altLoading = false
            pagingSpinner.visibility = View.GONE
        } else {
            searching = false
        }
        if (items == null) {
            if (initial && altAdapter.count == 0) {
                stateView.text = "搜索失败：${error ?: "网络异常"}\n\n点这里重试"
                stateView.visibility = View.VISIBLE
                stateView.setOnClickListener { runScopeSearch(initial = true) }
            }
            return
        }
        if (initial) {
            altPage = page
            altAdapter.submit(items)
            altList.setSelection(0)
            stateView.visibility = View.GONE
            if (items.isEmpty()) {
                altEnd = true
                stateView.text = if (scope == SearchScope.LIVE) {
                    "没有找到正在直播的房间"
                } else {
                    "没有找到相关用户"
                }
                stateView.visibility = View.VISIBLE
            }
        } else {
            if (items.isEmpty()) {
                altEnd = true
                return
            }
            altPage = page
            altAdapter.append(items)
            if (items.size < 15) altEnd = true
        }
    }

    private fun loadNextScopePage() {
        if (searchScope == SearchScope.VIDEO) return
        if (!showingResults || searching || altLoading || altEnd || currentKeyword.isBlank()) return
        runScopeSearch(initial = false)
    }

    private inner class ScopeAdapter : BaseAdapter() {
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
                is LiveSearchItem -> liveRow(item)
                is UserSearchItem -> userRow(item)
                else -> View(activity)
            }
        }
    }

    private fun rowShell(content: View): View = FrameLayout(activity).apply {
        setPadding(0, dp(3), 0, dp(3))
        addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun liveRow(item: LiveSearchItem): View {
        val c = Ds.colors
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(activity, c.surface, c.lineSoft, Ds.Radius.card)
            Ds.clipRounded(this, Ds.Radius.card)
            setPadding(dp(10), dp(10), dp(14), dp(10))
            isClickable = true
            Ds.bindPressFeedback(this, 0.97f)
            setOnClickListener {
                if (!item.live) return@setOnClickListener
                LiveRoomActivity.open(
                    activity,
                    LiveRoom(item.roomId, item.uid, item.title, item.owner, item.cover, item.face, item.area, item.online)
                )
            }
        }
        val coverFrame = FrameLayout(activity).apply {
            setBackgroundColor(c.cover)
            Ds.clipRounded(this, 8)
        }
        val cover = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        coverFrame.addView(cover, FrameLayout.LayoutParams(dp(152), dp(86)))
        if (item.cover.isNotBlank()) CoverLoader.load(cover, item.cover, thumbEdge = 384)
        if (item.live) {
            coverFrame.addView(TextView(activity).apply {
                text = "直播中"
                textSize = 9f
                typeface = Ds.body(true)
                setTextColor(Color.WHITE)
                includeFontPadding = false
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = Ds.pill(activity, 0xE6F04A5E.toInt())
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = dp(6)
                topMargin = dp(6)
            })
        }
        card.addView(coverFrame, LinearLayout.LayoutParams(dp(152), dp(86)))

        val info = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        info.addView(TextView(activity).apply {
            text = item.title
            textSize = 14f
            typeface = Ds.body(true)
            setTextColor(c.text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val meta = buildString {
            append(item.owner)
            if (item.online > 0) append(" · ").append(countLabel(item.online)).append("人气")
            if (item.area.isNotBlank()) append(" · ").append(item.area)
        }
        info.addView(TextView(activity).apply {
            text = meta
            textSize = 11.5f
            setTextColor(c.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return rowShell(card)
    }

    private fun userRow(item: UserSearchItem): View {
        val c = Ds.colors
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(activity, c.surface, c.lineSoft, Ds.Radius.card)
            Ds.clipRounded(this, Ds.Radius.card)
            setPadding(dp(12), dp(12), dp(14), dp(12))
            isClickable = true
            Ds.bindPressFeedback(this, 0.97f)
            setOnClickListener {
                SpaceActivity.open(activity, item.mid, item.name, item.face)
            }
        }
        val avatarFrame = FrameLayout(activity).apply {
            setBackgroundColor(c.cover)
            Ds.clipRounded(this, 26)
        }
        val avatar = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        avatarFrame.addView(avatar, FrameLayout.LayoutParams(dp(52), dp(52)))
        if (item.face.isNotBlank()) CoverLoader.load(avatar, item.face, thumbEdge = 160)
        card.addView(avatarFrame, LinearLayout.LayoutParams(dp(52), dp(52)))

        val info = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        val nameRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(activity).apply {
            text = item.name
            textSize = 14f
            typeface = Ds.body(true)
            setTextColor(c.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (item.live) {
            nameRow.addView(TextView(activity).apply {
                text = "直播中"
                textSize = 9f
                typeface = Ds.body(true)
                setTextColor(0xFFF04A5E.toInt())
                background = Ds.pill(activity, 0x1FF04A5E)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                includeFontPadding = false
            })
        }
        info.addView(nameRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val meta = buildString {
            append(countLabel(item.fans)).append("粉丝")
            if (item.videos > 0) append(" · ").append(item.videos).append(" 视频")
            if (item.verify.isNotBlank()) append(" · ").append(item.verify)
        }
        info.addView(TextView(activity).apply {
            text = meta
            textSize = 11.5f
            setTextColor(c.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (item.sign.isNotBlank()) {
            info.addView(TextView(activity).apply {
                text = item.sign
                textSize = 11.5f
                setTextColor(c.subtle)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        card.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return rowShell(card)
    }

    private fun countLabel(value: Long): String = when {
        value >= 100_000_000L -> String.format("%.1f亿", value / 100_000_000.0)
        value >= 10_000L -> String.format("%.1f万", value / 10_000.0)
        else -> value.toString()
    }

    private fun updateFilterUi() {
        val c = Ds.colors
        SortMode.entries.forEachIndexed { idx, mode ->
            val active = mode == sortMode
            val tv = sortViews[idx]
            tv.setTextColor(if (active) c.accent else c.muted)
            tv.background = Ds.pill(
                activity,
                fill = if (active) c.accentSoft else c.surface,
                border = if (active) (c.accent and 0x44FFFFFF) else c.lineSoft,
                borderPx = Ds.hairline(activity)
            )
        }
        durationLabels.indices.forEach { idx ->
            val active = idx == filterDuration
            val tv = durationViews[idx]
            tv.setTextColor(if (active) c.accent else c.muted)
            tv.background = Ds.pill(
                activity,
                fill = if (active) c.accentSoft else c.surface,
                border = if (active) (c.accent and 0x44FFFFFF) else c.lineSoft,
                borderPx = Ds.hairline(activity)
            )
        }
    }

    private fun refreshHistory() {
        val list = SearchHistoryStore.load(activity)
        historyFlow.removeAllViews()
        if (list.isEmpty()) {
            historyContainer.visibility = View.GONE
            return
        }
        historyContainer.visibility = View.VISIBLE
        val c = Ds.colors
        list.forEach { term: String ->
            val chip = TextView(activity).apply {
                text = term
                textSize = 13f
                typeface = Ds.body()
                setTextColor(c.text)
                background = Ds.pill(activity, c.surfaceRaised, c.lineSoft, Ds.hairline(activity))
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isClickable = true
                Ds.bindPressFeedback(this, 0.95f)
                setOnClickListener {
                    input.setText(term)
                    input.setSelection(term.length)
                    submitSearch(term)
                }
            }
            historyFlow.addView(chip, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun scheduleSuggest(keyword: String) {
        pendingSuggest?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            BiliApi.suggest(keyword) { items, _ ->
                val list = items.orEmpty()
                if (showingResults || input.text?.toString()?.trim() != keyword) return@suggest
                suggestionBox.removeAllViews()
                if (list.isEmpty()) {
                    suggestionBox.visibility = View.GONE
                    return@suggest
                }
                val c = Ds.colors
                list.take(8).forEachIndexed { idx, term ->
                    if (idx > 0) {
                        suggestionBox.addView(
                            View(activity).apply { setBackgroundColor(c.lineSoft) },
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(activity))
                        )
                    }
                    suggestionBox.addView(TextView(activity).apply {
                        text = term
                        textSize = 14f
                        setTextColor(c.text)
                        setPadding(dp(12), dp(11), dp(12), dp(11))
                        isClickable = true
                        Ds.bindPressFeedback(this, 0.98f)
                        setOnClickListener {
                            input.setText(term)
                            input.setSelection(term.length)
                            submitSearch(term)
                        }
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                }
                suggestionBox.visibility = View.VISIBLE
            }
        }
        pendingSuggest = task
        mainHandler.postDelayed(task, 240L)
    }

    private fun submitSearch(raw: String) {
        val kw = raw.trim()
        if (kw.isEmpty()) return
        hideKeyboard()
        SearchHistoryStore.record(activity, kw)
        refreshHistory()
        runSearch(kw, keepPgc = false)
    }

    private fun runSearch(keyword: String, keepPgc: Boolean) {
        currentKeyword = keyword
        showingResults = true
        searching = true
        loadingMore = false
        endReached = false
        altLoading = false
        altEnd = false
        altPage = 0
        searchPage = 1
        val gen = ++searchGen
        suggestionBox.visibility = View.GONE
        idleScroll.visibility = View.GONE
        scopeRow.visibility = View.VISIBLE
        resultsHost.visibility = View.VISIBLE
        stateView.visibility = View.GONE
        stateView.setOnClickListener(null)
        topButton.setShown(false, animate = false)
        topScroller?.cancel()
        if (!keepPgc) pgcHeader.visibility = View.GONE
        adapter.replace(emptyList())
        altAdapter.submit(emptyList())
        applyScopeChrome()

        if (searchScope != SearchScope.VIDEO) {
            VideoCardUi.toggleSkeleton(skeletonOverlay, false)
            runScopeSearch(initial = true)
            return
        }

        filterRow.visibility = View.VISIBLE
        VideoCardUi.toggleSkeleton(skeletonOverlay, true)

        if (!keepPgc) {
            val collectedPgc = ArrayList<PgcMedia>()
            var pendingPgc = 2
            fun finishPgcBranch(items: List<PgcMedia>?) {
                if (gen != searchGen || !showingResults) return
                items?.forEach { m -> if (collectedPgc.none { it.seasonId == m.seasonId }) collectedPgc.add(m) }
                pendingPgc--
                if (pendingPgc <= 0) bindPgcCards(collectedPgc)
            }
            BiliApi.searchPgc(keyword, "media_bangumi") { items, _ -> finishPgcBranch(items) }
            BiliApi.searchPgc(keyword, "media_ft") { items, _ -> finishPgcBranch(items) }
        }

        val minDurationSec = minDurationSeconds(filterDuration)
        BiliApi.search(keyword, page = 1, order = sortMode.orderParam, duration = 0) { list, error ->
            if (gen != searchGen || !showingResults) return@search
            searching = false
            VideoCardUi.toggleSkeleton(skeletonOverlay, false)
            if (error != null) {
                stateView.text = "搜索失败：$error\n\n点这里重试"
                stateView.visibility = View.VISIBLE
                stateView.setOnClickListener { runSearch(keyword, keepPgc = false) }
                return@search
            }
            val safeList = list.orEmpty()
            val filtered = filterByMinDuration(safeList, minDurationSec)
            adapter.replace(filtered)
            endReached = safeList.size < 15
            if (filtered.isEmpty() && pgcHeader.visibility != View.VISIBLE) {
                stateView.text = "没有找到与「$keyword」相关的内容"
                stateView.visibility = View.VISIBLE
                stateView.setOnClickListener(null)
            }
        }
    }

    private fun loadNextPage() {
        if (searchScope != SearchScope.VIDEO) {
            loadNextScopePage()
            return
        }
        if (loadingMore || endReached || currentKeyword.isBlank()) return
        loadingMore = true
        val gen = searchGen
        val nextPage = searchPage + 1
        pagingSpinner.visibility = View.VISIBLE
        pagingSpinner.startSpinning()
        val minDurationSec = minDurationSeconds(filterDuration)
        BiliApi.search(currentKeyword, page = nextPage, order = sortMode.orderParam, duration = 0) { list, error ->
            if (gen != searchGen) return@search
            loadingMore = false
            pagingSpinner.visibility = View.GONE
            val safeList = list.orEmpty()
            if (error != null || safeList.isEmpty()) {
                if (error == null) endReached = true
                return@search
            }
            searchPage = nextPage
            val filtered = filterByMinDuration(safeList, minDurationSec)
            if (filtered.isNotEmpty()) adapter.append(filtered)
            if (safeList.size < 15) endReached = true
        }
    }

    private fun resetToIdle() {
        searchGen++
        showingResults = false
        searching = false
        loadingMore = false
        altLoading = false
        altEnd = false
        altPage = 0
        topButton.setShown(false, animate = false)
        topScroller?.cancel()
        filterRow.visibility = View.GONE
        scopeRow.visibility = View.GONE
        resultsHost.visibility = View.GONE
        idleScroll.visibility = View.VISIBLE
        suggestionBox.visibility = View.GONE
        resultsList.visibility = View.VISIBLE
        altList.visibility = View.GONE
        altAdapter.submit(emptyList())
        refreshHistory()
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun minDurationSeconds(index: Int): Int = when (index) {
        1 -> 5 * 60
        2 -> 10 * 60
        3 -> 30 * 60
        4 -> 60 * 60
        else -> 0
    }

    private fun filterByMinDuration(list: List<VideoItem>, minSec: Int): List<VideoItem> =
        if (minSec <= 0) list else list.filter { it.duration >= minSec }

    private inner class GridAdapter : BaseAdapter() {
        private val videos = ArrayList<VideoItem>()
        private val seen = HashSet<String>()
        private val cols = columns
        private val cardW = VideoCardUi.cardWidth(activity, cols, availableWidthPx)
        private val coverH = VideoCardUi.coverHeight(cardW)
        private val cardH = VideoCardUi.cardHeight(coverH, activity)

        fun replace(items: List<VideoItem>) {
            videos.clear()
            seen.clear()
            items.forEach { if (seen.add(it.bvid)) videos.add(it) }
            notifyDataSetChanged()
            resultsList.post { resultsList.setSelection(0) }
        }

        fun append(items: List<VideoItem>) {
            var added = false
            items.forEach { if (seen.add(it.bvid)) { videos.add(it); added = true } }
            if (added) notifyDataSetChanged()
        }

        override fun getCount(): Int = (videos.size + cols - 1) / cols
        override fun getItem(position: Int): VideoItem? = videos.getOrNull(position * cols)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val holders: List<VideoCardUi.Holder?>
            if (convertView == null) {
                row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(VideoCardUi.CARD_SPACING_DP))
                }
                val list = ArrayList<VideoCardUi.Holder>(cols)
                for (c in 0 until cols) {
                    val card = VideoCardUi.createCard(activity, cardW, coverH, cardH).apply {
                        setOnClickListener {
                            (tag as? VideoCardUi.Holder)?.boundVideo?.let { v ->
                                VideoDetailActivity.open(activity, v.bvid, v.aid, v.cid)
                            }
                        }
                    }
                    row.addView(card, LinearLayout.LayoutParams(cardW, cardH).apply {
                        if (c < cols - 1) rightMargin = dp(VideoCardUi.CARD_SPACING_DP)
                    })
                    list.add(card.tag as VideoCardUi.Holder)
                }
                holders = list
                row.tag = holders
            } else {
                row = convertView as LinearLayout
                @Suppress("UNCHECKED_CAST")
                holders = row.tag as List<VideoCardUi.Holder?>
            }
            val base = position * cols
            for (c in 0 until cols) {
                val card = row.getChildAt(c) as LinearLayout
                val holder = holders.getOrNull(c) ?: continue
                val video = videos.getOrNull(base + c)
                if (video == null) {
                    card.visibility = View.INVISIBLE
                    card.isClickable = false
                    holder.boundVideo = null
                    CoverLoader.clear(holder.cover)
                } else {
                    card.visibility = View.VISIBLE
                    card.isClickable = true
                    VideoCardUi.bind(card, holder, video, showPublishDate = true)
                }
            }
            return row
        }
    }
}
