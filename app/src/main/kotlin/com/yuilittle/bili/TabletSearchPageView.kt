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
internal enum class TabletSearchScope(val hint: String, val returnLabel: String) {
    PUBLIC("搜索公开视频、番剧、UP主…", "返回首页"),
    HISTORY("搜索历史记录标题", "返回历史记录"),
    FAVORITES("搜索全部收藏夹内视频", "返回我的收藏");

    val parentDestination: Int get() = when (this) {
        PUBLIC -> TabletRailNav.DEST_HOME
        HISTORY -> TabletRailNav.DEST_HISTORY
        FAVORITES -> TabletRailNav.DEST_FAVORITES
    }
    fun rowKey(video: VideoItem): String = if (this == HISTORY) video.historyMedia.rowKey else video.bvid
}

internal fun tabletSearchScope(destination: Int): TabletSearchScope? = when (destination) {
    TabletRailNav.DEST_SEARCH -> TabletSearchScope.PUBLIC
    TabletRailNav.DEST_HISTORY_SEARCH -> TabletSearchScope.HISTORY
    TabletRailNav.DEST_FAVORITES_SEARCH -> TabletSearchScope.FAVORITES
    else -> null
}

internal class TabletSearchPageView(
    private val activity: Activity,
    private val scope: TabletSearchScope = TabletSearchScope.PUBLIC,
    restored: Snapshot? = null,
    private val onReturn: () -> Unit
) : FrameLayout(activity) {

    data class Snapshot(
        val session: String, val input: String, val keyword: String, val showingResults: Boolean,
        val videos: List<VideoItem>, val pgc: List<PgcMedia>, val page: Int, val ended: Boolean,
        val sort: Int, val duration: Int, val reload: Boolean, val resumePage: Boolean,
        val failed: Boolean, val pendingQuery: Boolean, val pendingPgc: Boolean, val firstVisible: Int, val offset: Int
    )

    private enum class SortMode(val label: String, val orderParam: String) {
        DEFAULT("综合", ""),
        PLAY_DESC("最多播放", "click"),
        LATEST("最新发布", "pubdate")
    }

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
    private lateinit var retryRow: TextView
    private lateinit var scopeHint: TextView
    private val pgcItems = ArrayList<PgcMedia>()
    private var pgcPending = false
    private var session = BiliSessionStore.cookie()
    private var resumeFirst = false
    private var resumeMore = false
    private var pageError = false
    private var restoring = false
    private var pendingScopedQuery = false

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
    private var active = false

    private val availableWidthPx: Int
        get() = (resources.displayMetrics.widthPixels - dp(TabletRailNav.RAIL_WIDTH_DP + 36)).coerceAtLeast(dp(480))
    private val columns: Int
        get() = LayoutMode.gridColumns(activity, availableWidthPx)

    init {
        setBackgroundColor(COLOR_BACKGROUND)
        buildUi()
        refreshHistory()
        restored?.takeIf { it.session == session }?.let { state ->
            restoring = true
            input.setText(state.input)
            restoring = false
            currentKeyword = state.keyword
            showingResults = state.showingResults
            searchPage = state.page
            endReached = state.ended
            sortMode = SortMode.entries.getOrElse(state.sort) { SortMode.DEFAULT }
            filterDuration = state.duration.coerceIn(durationLabels.indices)
            updateFilterUi()
            adapter.replace(state.videos)
            bindPgcCards(state.pgc)
            pgcPending = state.pendingPgc
            filterRow.visibility = if (showingResults && scope == TabletSearchScope.PUBLIC) View.VISIBLE else View.GONE
            resultsHost.visibility = if (showingResults) View.VISIBLE else View.GONE
            idleScroll.visibility = if (showingResults) View.GONE else View.VISIBLE
            resumeFirst = state.reload
            resumeMore = state.resumePage
            pageError = state.failed
            pendingScopedQuery = state.pendingQuery
            if (pageError && searchPage > 0) {
                retryRow.text = "点此继续加载搜索结果"
                retryRow.visibility = View.VISIBLE
            }
            if (!resumeFirst && showingResults && state.videos.isEmpty() && state.pgc.isEmpty() && endReached) {
                stateView.text = if (scope != TabletSearchScope.PUBLIC && !BiliSessionStore.isLoggedIn(session)) {
                    if (scope == TabletSearchScope.HISTORY) "请先登录后再搜索历史" else "请先登录后再搜索收藏"
                } else "没有找到相关结果"
                stateView.visibility = View.VISIBLE
            }
            resultsList.post { resultsList.setSelectionFromTop(state.firstVisible, state.offset) }
        }
    }

    fun snapshot(): Snapshot = Snapshot(session, input.text.toString(), currentKeyword, showingResults,
        adapter.snapshot(), pgcItems.toList(), searchPage, endReached, sortMode.ordinal, filterDuration,
        resumeFirst || searching || pageError && searchPage == 0, resumeMore || loadingMore, pageError, pendingScopedQuery, pgcPending, resultsList.firstVisiblePosition,
        (resultsList.getChildAt(0)?.top ?: resultsList.paddingTop) - resultsList.paddingTop)

    fun setActive(value: Boolean) {
        val sessionChanged = session != BiliSessionStore.cookie()
        if (active == value && !sessionChanged) return
        if (sessionChanged) {
            session = BiliSessionStore.cookie()
            restoring = true
            input.setText("")
            restoring = false
            adapter.replace(emptyList())
            bindPgcCards(emptyList())
            resetToIdle()
        }
        active = value
        if (value) {
            refreshHistory()
            if (resumeFirst && currentKeyword.isNotBlank()) {
                resumeFirst = false
                runSearch(currentKeyword, keepPgc = false)
            } else if (resumeMore) {
                resumeMore = false
                loadNextPage()
            } else if (pendingScopedQuery) scheduleScopedQuery(input.text.toString())
            else if (!showingResults && !input.text.isNullOrBlank()) scheduleSuggest(input.text.toString().trim())
            if (scope == TabletSearchScope.HISTORY && showingResults && !searching) {
                adapter.updateHistoryProgress()
            }
            if (scope == TabletSearchScope.PUBLIC && pgcPending && !searching && showingResults) requestPgcResults(currentKeyword)
        } else {
            resumeFirst = resumeFirst || searching
            resumeMore = resumeMore || loadingMore
            searchGen++
            searching = false
            loadingMore = false
            pendingSuggest?.let(mainHandler::removeCallbacks)
            pendingSuggest = null
            pagingSpinner.visibility = View.GONE
            VideoCardUi.toggleSkeleton(skeletonOverlay, false)
            hideKeyboard()
        }
    }

    private fun accepts(gen: Int, expectedSession: String, keyword: String): Boolean =
        active && !activity.isFinishing && !activity.isDestroyed && gen == searchGen &&
            expectedSession == session && session == BiliSessionStore.cookie() && keyword == currentKeyword

    fun handleBack(): Boolean {
        if (showingResults) {
            resetToIdle()
            return true
        }
        return false
    }

    fun destroy() {
        active = false
        searchGen++
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
        searchBarRow.addView(Ds.backIcon(activity, c.text) {
            hideKeyboard()
            onReturn()
        }.apply { contentDescription = scope.returnLabel }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
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
            hint = scope.hint
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
                    if (restoring) return
                    pendingSuggest?.let(mainHandler::removeCallbacks)
                    pendingSuggest = null
                    if (scope != TabletSearchScope.PUBLIC && q.isNotBlank()) {
                        resetToIdle()
                        scheduleScopedQuery(q)
                        return
                    }
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

        // ── 2. 筛选条（平板直接一排平铺：排序胶囊 | 时长筛选胶囊，无需手机端底部弹层） ──
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
        scopeHint = TextView(activity).apply {
            text = if (scope == TabletSearchScope.HISTORY) "输入关键词搜索观看历史" else "输入关键词搜索全部收藏"
            textSize = 13f
            setTextColor(c.muted)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(24))
            visibility = if (scope == TabletSearchScope.PUBLIC) View.GONE else View.VISIBLE
        }
        idleCol.addView(scopeHint, LinearLayout.LayoutParams(-1, -2))
        idleScroll.addView(idleCol)
        stage.addView(idleScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        resultsHost = FrameLayout(activity).apply { visibility = View.GONE }
        adapter = GridAdapter()
        retryRow = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(c.muted)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            visibility = View.GONE
            setOnClickListener { pageError = false; visibility = View.GONE; loadNextPage() }
        }
        resultsList = ListView(activity).apply {
            addFooterView(retryRow, null, true)
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

        topButton = ScrollToTopButton(activity) { topScroller?.scrollToTop() }
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
        pgcItems.clear()
        if (scope == TabletSearchScope.PUBLIC) pgcItems.addAll(list)
        pgcRow.removeAllViews()
        if (list.isEmpty() || scope != TabletSearchScope.PUBLIC) {
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
        if (scope != TabletSearchScope.PUBLIC) { historyContainer.visibility = View.GONE; return }
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
        if (!active || scope != TabletSearchScope.PUBLIC) return
        pendingSuggest?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            BiliApi.suggest(keyword) { items, _ ->
                val list = items.orEmpty()
                if (!active || showingResults || input.text?.toString()?.trim() != keyword) return@suggest
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

    private fun scheduleScopedQuery(raw: String) {
        pendingSuggest?.let(mainHandler::removeCallbacks)
        pendingSuggest = null
        pendingScopedQuery = raw.isNotBlank()
        if (!active || !pendingScopedQuery) return
        val task = Runnable {
            pendingSuggest = null
            pendingScopedQuery = false
            if (active && input.text.toString() == raw) startScopedSearch(raw.trim())
        }
        pendingSuggest = task
        mainHandler.postDelayed(task, 320L)
    }

    private fun submitSearch(raw: String) {
        if (session != BiliSessionStore.cookie()) { setActive(true); return }
        val kw = raw.trim()
        if (!active || kw.isEmpty()) return
        pendingSuggest?.let(mainHandler::removeCallbacks)
        pendingSuggest = null
        pendingScopedQuery = false
        hideKeyboard()
        if (scope == TabletSearchScope.PUBLIC) {
            SearchHistoryStore.record(activity, kw)
            refreshHistory()
        }
        runSearch(kw, keepPgc = false)
    }

    private fun runSearch(keyword: String, keepPgc: Boolean) {
        if (!active) return
        if (scope != TabletSearchScope.PUBLIC) { startScopedSearch(keyword); return }
        currentKeyword = keyword
        showingResults = true
        searching = true
        loadingMore = false
        endReached = false
        searchPage = 0
        val gen = ++searchGen
        val expectedSession = session
        pageError = false
        retryRow.visibility = View.GONE
        suggestionBox.visibility = View.GONE
        idleScroll.visibility = View.GONE
        filterRow.visibility = View.VISIBLE
        resultsHost.visibility = View.VISIBLE
        stateView.visibility = View.GONE
        topButton.setShown(false, animate = false)
        topScroller?.cancel()
        adapter.replace(emptyList())
        VideoCardUi.toggleSkeleton(skeletonOverlay, true)
        if (!keepPgc || pgcPending) requestPgcResults(keyword)

        val minDurationSec = minDurationSeconds(filterDuration)
        BiliApi.search(keyword, page = 1, order = sortMode.orderParam, duration = 0) { list, error ->
            if (!accepts(gen, expectedSession, keyword) || !showingResults) return@search
            searching = false
            VideoCardUi.toggleSkeleton(skeletonOverlay, false)
            if (error != null) {
                pageError = true
                stateView.text = "搜索失败：$error\n\n点这里重试"
                stateView.visibility = View.VISIBLE
                stateView.setOnClickListener { runSearch(keyword, keepPgc = false) }
                return@search
            }
            val safeList = list.orEmpty()
            val filtered = filterByMinDuration(safeList, minDurationSec)
            searchPage = 1
            endReached = safeList.size < 15
            adapter.replace(filtered)
            if (filtered.isEmpty() && pgcHeader.visibility != View.VISIBLE) {
                stateView.text = "没有找到与「$keyword」相关的内容"
                stateView.visibility = View.VISIBLE
                stateView.setOnClickListener(null)
            }
        }
    }

    private fun requestPgcResults(keyword: String) {
        if (scope != TabletSearchScope.PUBLIC || !active || keyword.isBlank()) return
        val gen = searchGen
        val expectedSession = session
        bindPgcCards(emptyList())
        pgcPending = true
        val collected = ArrayList<PgcMedia>()
        var remaining = 2
        fun finish(items: List<PgcMedia>?) {
            if (!accepts(gen, expectedSession, keyword) || !showingResults) return
            items?.forEach { media -> if (collected.none { it.seasonId == media.seasonId }) collected.add(media) }
            remaining--
            if (remaining == 0) {
                pgcPending = false
                bindPgcCards(collected)
                if (collected.isNotEmpty()) stateView.visibility = View.GONE
            }
        }
        BiliApi.searchPgc(keyword, "media_bangumi") { list, _ -> finish(list) }
        BiliApi.searchPgc(keyword, "media_ft") { list, _ -> finish(list) }
    }

    private fun startScopedSearch(keyword: String) {
        if (!active || keyword.isBlank()) return
        if (session != BiliSessionStore.cookie()) { setActive(true); return }
        if (keyword == currentKeyword && searching) return
        searchGen++
        currentKeyword = keyword
        showingResults = true
        searchPage = 0
        endReached = false
        pageError = false
        resumeFirst = false
        resumeMore = false
        searching = true
        loadingMore = false
        retryRow.visibility = View.GONE
        stateView.visibility = View.GONE
        stateView.setOnClickListener(null)
        suggestionBox.visibility = View.GONE
        idleScroll.visibility = View.GONE
        filterRow.visibility = View.GONE
        resultsHost.visibility = View.VISIBLE
        pgcHeader.visibility = View.GONE
        topButton.setShown(false, animate = false)
        topScroller?.cancel()
        adapter.replace(emptyList())
        if (!BiliSessionStore.isLoggedIn(session)) {
            searching = false
            endReached = true
            stateView.text = if (scope == TabletSearchScope.HISTORY) "请先登录后再搜索历史" else "请先登录后再搜索收藏"
            stateView.visibility = View.VISIBLE
            VideoCardUi.toggleSkeleton(skeletonOverlay, false)
            return
        }
        VideoCardUi.toggleSkeleton(skeletonOverlay, true)
        fetchScopedPage(1)
    }

    private fun fetchScopedPage(page: Int) {
        if (!active || scope == TabletSearchScope.PUBLIC) return
        if (session != BiliSessionStore.cookie()) { setActive(true); return }
        val gen = searchGen
        val expectedSession = session
        val keyword = currentKeyword
        val first = page == 1
        if (first) searching = true else {
            loadingMore = true
            pagingSpinner.visibility = View.VISIBLE
            pagingSpinner.startSpinning()
        }
        val deliver: (List<VideoItem>, String?, Boolean) -> Unit = callback@{ videos, error, ended ->
            if (!accepts(gen, expectedSession, keyword) || !showingResults) return@callback
            searching = false
            loadingMore = false
            pagingSpinner.visibility = View.GONE
            VideoCardUi.toggleSkeleton(skeletonOverlay, false)
            if (error != null) {
                pageError = true
                if (first) {
                    stateView.text = "搜索失败：$error\n\n点这里重试"
                    stateView.visibility = View.VISIBLE
                    stateView.setOnClickListener { startScopedSearch(keyword) }
                } else {
                    retryRow.text = "加载失败，点此重试"
                    retryRow.visibility = View.VISIBLE
                }
                return@callback
            }
            val rows = if (scope == TabletSearchScope.HISTORY) HistoryStore.withLocalProgress(activity, videos) else videos
            searchPage = page
            endReached = ended
            pageError = false
            retryRow.visibility = View.GONE
            stateView.setOnClickListener(null)
            val existing = if (first) emptySet() else adapter.snapshot().map { scope.rowKey(it) }.toSet()
            val hasNewRows = rows.any { scope.rowKey(it) !in existing }
            pageError = !ended && !hasNewRows
            if (first) adapter.replace(rows) else adapter.append(rows)
            if (pageError) {
                retryRow.text = "继续加载搜索结果"
                retryRow.visibility = View.VISIBLE
            }
            stateView.text = "没有找到与「$keyword」相关的${if (scope == TabletSearchScope.HISTORY) "历史" else "收藏"}"
            stateView.visibility = if (adapter.itemCount() == 0 && ended) View.VISIBLE else View.GONE
        }
        when (scope) {
            TabletSearchScope.HISTORY -> BiliApi.searchHistory(keyword, page, deliver)
            TabletSearchScope.FAVORITES -> BiliApi.searchAllFavoriteVideos(keyword) { videos, error -> deliver(videos, error, true) }
            TabletSearchScope.PUBLIC -> Unit
        }
    }

    private fun loadNextPage() {
        if (!active || searching || loadingMore || endReached || pageError || currentKeyword.isBlank()) return
        if (session != BiliSessionStore.cookie()) { setActive(true); return }
        if (scope != TabletSearchScope.PUBLIC) {
            if (scope == TabletSearchScope.HISTORY) fetchScopedPage(searchPage + 1)
            return
        }
        loadingMore = true
        val gen = searchGen
        val expectedSession = session
        val keyword = currentKeyword
        val nextPage = searchPage + 1
        pagingSpinner.visibility = View.VISIBLE
        pagingSpinner.startSpinning()
        val minDurationSec = minDurationSeconds(filterDuration)
        BiliApi.search(currentKeyword, page = nextPage, order = sortMode.orderParam, duration = 0) { list, error ->
            if (!accepts(gen, expectedSession, keyword)) return@search
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
        pendingSuggest?.let(mainHandler::removeCallbacks)
        pendingSuggest = null
        pendingScopedQuery = false
        pgcPending = false
        searchGen++
        currentKeyword = ""
        resumeFirst = false
        resumeMore = false
        pageError = false
        retryRow.visibility = View.GONE
        stateView.setOnClickListener(null)
        pagingSpinner.visibility = View.GONE
        VideoCardUi.toggleSkeleton(skeletonOverlay, false)
        showingResults = false
        searching = false
        loadingMore = false
        topButton.setShown(false, animate = false)
        topScroller?.cancel()
        filterRow.visibility = View.GONE
        resultsHost.visibility = View.GONE
        idleScroll.visibility = View.VISIBLE
        suggestionBox.visibility = View.GONE
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

        fun snapshot(): List<VideoItem> = videos.toList()
        fun itemCount(): Int = videos.size

        fun updateHistoryProgress() {
            val updated = HistoryStore.withLocalProgress(activity, videos)
            videos.clear()
            seen.clear()
            updated.forEach { if (seen.add(scope.rowKey(it))) videos.add(it) }
            notifyDataSetChanged()
        }

        fun replace(items: List<VideoItem>) {
            videos.clear()
            seen.clear()
            items.forEach { if (seen.add(scope.rowKey(it))) videos.add(it) }
            notifyDataSetChanged()
            resultsList.post { resultsList.setSelection(0) }
        }

        fun append(items: List<VideoItem>) {
            var added = false
            items.forEach { if (seen.add(scope.rowKey(it))) { videos.add(it); added = true } }
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
                                if (scope == TabletSearchScope.HISTORY) VideoDetailActivity.openHistory(activity, v)
                                else VideoDetailActivity.open(activity, v.bvid, v.aid, v.cid)
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
                    VideoCardUi.bind(card, holder, if (scope == TabletSearchScope.HISTORY) video.copy(rcmdReason = "") else video, showPublishDate = true)
                    holder.boundVideo = video
                    if (scope == TabletSearchScope.HISTORY) {
                        holder.duration.text = formatHistoryCoverBadge(video.watchProgressSec, video.duration)
                        holder.viewsText.text = ""
                        holder.viewsText.visibility = View.GONE
                        (holder.viewsText.parent as? ViewGroup)?.getChildAt(0)?.visibility = View.GONE
                    }
                }
            }
            return row
        }
    }
}
