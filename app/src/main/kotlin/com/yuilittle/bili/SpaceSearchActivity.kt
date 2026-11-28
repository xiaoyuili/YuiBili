package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 空间内搜索页：在该用户的动态与投稿中精准检索。
 * - 空闲态：展示当前 UP 主专属搜索历史（与全站搜索历史隔离，支持一键清空和长按删除）与简明范围提示，绝不预拉全量内容
 * - 仅在提交/点历史/切排序时发请求，防逐字符风控
 * - 「动态 | 视频」双标签支持点击与左右手势切换：
 *   - 动态：复用空间动态卡片并高亮关键词，支持点击「继续检索更早动态」沿游标向更早历史续搜
 *   - 视频：复用双列视频卡片 + 骨架屏，支持排序（最新发布 / 最多播放 / 最多收藏）与滚动自动分页
 * - 修复原下拉刷新回调漏关、ListView Header 撑出空隙、状态视图遮挡及返回层级体验
 */
class SpaceSearchActivity : Activity() {

    private var mid = 0L
    private var upName = ""
    private var initialTab = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var snackHost: FrameLayout
    private lateinit var input: EditText
    private lateinit var clearBtn: View
    private lateinit var searchBtn: TextView
    private lateinit var idlePanel: LinearLayout
    private lateinit var historyHeaderRow: LinearLayout
    private lateinit var historyFlow: ChipFlowLayout
    private lateinit var emptyHistoryCard: LinearLayout
    private lateinit var tabStrip: SpaceTabStrip
    private lateinit var resultArea: FrameLayout
    private lateinit var refreshIndicator: BrandSpinnerView

    private lateinit var pager: RootPager
    private var activeTab = 0

    private var submittedKeyword = ""
    private var dynamicGeneration = 0
    private var videoGeneration = 0

    // 动态搜索状态（手机单列 ListView / 平板双列真瀑布流）
    private var dynamicListView: SpacePullListView? = null
    private var dynamicMasonryView: SpaceMasonryDynamicView? = null
    private var dynamicAdapter: DynamicSearchAdapter? = null
    private lateinit var dynamicStateView: SpaceStateView
    private lateinit var dynamicSummaryBanner: TextView
    private lateinit var dynamicFooter: LinearLayout
    private lateinit var dynamicFooterSpinner: BrandSpinnerView
    private lateinit var dynamicFooterText: TextView
    private val dynamicItems = ArrayList<SpaceDynamic>()
    private val dynamicSeenIds = HashSet<String>()
    private var dynamicSearchedKeyword: String? = null
    private var dynamicCheckpoint: DynamicSearchCheckpoint? = null
    private var dynamicHasMoreToScan = false
    private var dynamicTotalScanned = 0
    private var dynamicLoading = false
    private var dynamicRequest: BiliRequest? = null

    // 视频搜索状态
    private lateinit var videoListView: SpacePullListView
    private lateinit var videoAdapter: VideoSearchAdapter
    private lateinit var videoStateView: SpaceStateView
    private lateinit var videoSkeletonOverlay: FrameLayout
    private lateinit var videoSummaryBanner: TextView
    private lateinit var videoFooter: LinearLayout
    private lateinit var videoFooterSpinner: BrandSpinnerView
    private lateinit var videoFooterText: TextView
    private val videoItems = ArrayList<SpaceVideo>()
    private val videoSeenIds = HashSet<String>()
    private var videoSearchedKeyword: String? = null
    private var videoSearchedOrder: SpaceVideoOrder? = null
    private var videoOrder = SpaceVideoOrder.PUBDATE
    private var videoPage = 0
    private var videoTotal: Long? = null
    private var videoHasMore = false
    private var videoLoading = false
    private var videoLastError: String? = null
    private var videoRequest: BiliRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Theme.applySystemBars(this)
        PlayerSettingsStore.applyHomeOrientation(this)

        mid = intent.getLongExtra(EXTRA_MID, 0L)
        upName = intent.getStringExtra(EXTRA_UP_NAME).orEmpty().trim()
        initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0).coerceIn(0, 1)
        if (mid <= 0L) {
            finish()
            return
        }
        activeTab = initialTab

        val root = FrameLayout(this).apply { setBackgroundColor(COLOR_BACKGROUND) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        column.addView(
            buildToolbar(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        column.addView(
            View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this))
        )

        val content = FrameLayout(this)

        // 结果区域：吸顶双标签 + 下方 Pager，不再让列表和标签叠在一起
        resultArea = FrameLayout(this).apply { visibility = View.GONE }
        val resultColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        tabStrip = SpaceTabStrip(this, listOf("动态", "视频")).apply {
            onTabSelected = { index -> pager.showPage(index, animate = true) }
            onActionClick = { anchor ->
                val entries = SpaceVideoOrder.entries
                showAnchoredMenu(anchor, entries.map { it.label }, entries.indexOf(videoOrder)) { picked ->
                    val next = entries.getOrNull(picked) ?: return@showAnchoredMenu
                    if (next == videoOrder) return@showAnchoredMenu
                    videoOrder = next
                    updateVideoSortChip()
                    if (submittedKeyword.isNotBlank()) {
                        executeVideoSearch(submittedKeyword, forceRefresh = false)
                    }
                }
            }
        }
        resultColumn.addView(
            tabStrip,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(SpaceUi.TAB_BAR_HEIGHT_DP)
            )
        )

        val pagerStage = FrameLayout(this)
        pager = RootPager(this).apply {
            setOnPageScrollListener { progress -> tabStrip.setProgress(progress) }
            setOnPageSelectedListener { index ->
                activeTab = index
                tabStrip.setSelected(index)
                updateVideoSortChip()
                maybeSearchTabOnSwitch(index)
            }
        }
        pager.addPage(buildDynamicPage())
        pager.addPage(buildVideoPage())
        pagerStage.addView(
            pager,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        refreshIndicator = BrandSpinnerView(this, COLOR_ROSE, Color.WHITE).apply {
            visibility = View.INVISIBLE
            alpha = 0f
        }
        pagerStage.addView(
            refreshIndicator,
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(10)
            }
        )

        resultColumn.addView(
            pagerStage,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        resultArea.addView(
            resultColumn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        idlePanel = buildIdlePanel()

        content.addView(
            resultArea,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        content.addView(
            idlePanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val contentLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        column.addView(content, contentLp)

        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
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

        updateVideoSortChip()
        tabStrip.post { tabStrip.setProgress(activeTab.toFloat()) }
        pager.showPage(activeTab, animate = false)
        showIdle()

        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        input.post {
            input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun buildToolbar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.Space.sm), dp(Ds.Space.sm), dp(Ds.Space.sm), dp(Ds.Space.sm))
            setBackgroundColor(COLOR_BACKGROUND)
        }
        bar.addView(
            Ds.backIcon(this, COLOR_INK) { handleBackAction() },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )

        val field = FrameLayout(this).apply {
            background = Ds.pill(this@SpaceSearchActivity, COLOR_SURFACE, Ds.colors.lineSoft)
        }
        field.addView(
            Ds.searchIcon(this, COLOR_MUTED, 1.8f),
            FrameLayout.LayoutParams(dp(16), dp(16), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(14)
            }
        )
        input = EditText(this).apply {
            hint = if (upName.isNotBlank()) "搜索 $upName 的动态或投稿" else "搜索 TA 的动态或投稿"
            textSize = 14f
            typeface = Ds.body()
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            background = null
            setPadding(0, 0, dp(8), 0)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, event ->
                val submitted = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN)
                if (submitted) {
                    submitSearch()
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val empty = s.isNullOrEmpty()
                    val blank = s.isNullOrBlank()
                    clearBtn.visibility = if (empty) View.GONE else View.VISIBLE
                    searchBtn.alpha = if (blank) 0.45f else 1f
                    if (blank && submittedKeyword.isNotEmpty()) {
                        resetToIdle()
                    }
                }
            })
        }
        field.addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = dp(14 + 16 + Ds.Space.sm)
                rightMargin = dp(34)
            }
        )

        clearBtn = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            contentDescription = "清空关键词"
            Ds.bindPressFeedback(this, 0.90f)
            addView(
                Ds.closeIcon(this@SpaceSearchActivity, COLOR_MUTED, 1.7f),
                FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)
            )
            setOnClickListener {
                input.setText("")
                input.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        field.addView(
            clearBtn,
            FrameLayout.LayoutParams(
                dp(28), dp(28), Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = dp(6) }
        )

        bar.addView(
            field,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(2)
                rightMargin = dp(4)
            }
        )

        searchBtn = TextView(this).apply {
            text = "搜索"
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_ROSE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            alpha = 0.45f
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { submitSearch() }
        }
        bar.addView(searchBtn, LinearLayout.LayoutParams(dp(48), dp(40)))
        return bar
    }

    private fun buildIdlePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
            visibility = View.GONE
        }

        // 历史记录标题行（右侧一键清空）
        historyHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        historyHeaderRow.addView(TextView(this).apply {
            text = if (upName.isNotBlank()) "在 $upName 的空间搜过" else "空间搜索记录"
            textSize = 13.5f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val clearAllBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4))
            isClickable = true
            isFocusable = true
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener {
                SearchHistoryStore.clearForSpace(this@SpaceSearchActivity, mid)
                refreshHistory()
                showFeedback("已清空该空间搜索记录")
            }
        }
        clearAllBtn.addView(
            createTrashIcon(this, Ds.colors.subtle),
            LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(3) }
        )
        clearAllBtn.addView(TextView(this).apply {
            text = "清空"
            textSize = 12f
            typeface = Ds.body()
            setTextColor(Ds.colors.subtle)
            includeFontPadding = false
        })
        historyHeaderRow.addView(clearAllBtn)
        panel.addView(
            historyHeaderRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        historyFlow = ChipFlowLayout(this).apply {
            gapX = dp(8)
            gapY = dp(8)
        }
        panel.addView(
            historyFlow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        // 空历史时的极简指引卡片
        emptyHistoryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@SpaceSearchActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        emptyHistoryCard.addView(TextView(this).apply {
            text = if (upName.isNotBlank()) "搜索 $upName 的主页内容" else "搜索该用户的主页内容"
            textSize = 14f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            includeFontPadding = false
        })
        emptyHistoryCard.addView(TextView(this).apply {
            text = "可分别在「动态」与「视频」标签查看匹配结果，支持在视频中切换排序，或在动态里继续向更早记录翻找。"
            textSize = 12.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        panel.addView(
            emptyHistoryCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        return panel
    }

    private fun refreshHistory() {
        historyFlow.removeAllViews()
        val history = SearchHistoryStore.loadForSpace(this, mid)
        val hasHistory = history.isNotEmpty()
        historyHeaderRow.visibility = if (hasHistory) View.VISIBLE else View.GONE
        historyFlow.visibility = if (hasHistory) View.VISIBLE else View.GONE
        emptyHistoryCard.visibility = if (hasHistory) View.GONE else View.VISIBLE
        history.forEach { keyword ->
            historyFlow.addView(buildHistoryChip(keyword))
        }
    }

    private fun buildHistoryChip(keyword: String): TextView = TextView(this).apply {
        text = keyword
        textSize = 13f
        typeface = Ds.body()
        setTextColor(COLOR_INK)
        background = Ds.pill(this@SpaceSearchActivity, COLOR_CARD, Ds.colors.lineSoft)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        includeFontPadding = false
        isClickable = true
        isFocusable = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        Ds.bindPressFeedback(this, 0.95f)
        setOnClickListener {
            input.setText(keyword)
            input.setSelection(keyword.length)
            submitSearch()
        }
        setOnLongClickListener {
            SearchHistoryStore.removeForSpace(this@SpaceSearchActivity, mid, keyword)
            refreshHistory()
            showFeedback("已移除「$keyword」")
            true
        }
    }

    private fun buildDynamicPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        dynamicSummaryBanner = TextView(this).apply {
            textSize = 12f
            typeface = Ds.body()
            setTextColor(Ds.colors.subtle)
            setPadding(dp(16), dp(8), dp(16), dp(4))
            includeFontPadding = false
            visibility = View.GONE
        }
        page.addView(
            dynamicSummaryBanner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val host = FrameLayout(this)

        dynamicFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(22))
            visibility = View.GONE
            isClickable = true
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener {
                if (!dynamicLoading && dynamicHasMoreToScan && submittedKeyword.isNotBlank()) {
                    continueDynamicSearch(submittedKeyword)
                }
            }
            addView(
                BrandSpinnerView(this@SpaceSearchActivity, COLOR_ROSE, Color.WHITE).apply {
                    visibility = View.GONE
                },
                LinearLayout.LayoutParams(dp(18), dp(18))
            )
            addView(TextView(this@SpaceSearchActivity).apply {
                textSize = 12.5f
                typeface = Ds.body(bold = true)
                setTextColor(COLOR_ROSE)
                setPadding(dp(8), 0, 0, 0)
                includeFontPadding = false
            })
        }
        dynamicFooterSpinner = dynamicFooter.getChildAt(0) as BrandSpinnerView
        dynamicFooterText = dynamicFooter.getChildAt(1) as TextView

        if (LayoutMode.isWideScreen(this)) {
            val masonry = SpaceMasonryDynamicView(this).apply {
                setFooter(dynamicFooter)
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
            val adapterObj = DynamicSearchAdapter()
            dynamicAdapter = adapterObj
            val list = SpacePullListView(this).apply {
                divider = null
                dividerHeight = 0
                setSelector(android.R.color.transparent)
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setPadding(0, 0, 0, dp(24))
                clipToPadding = false
                adapter = adapterObj
                setRecyclerListener { recycled -> SpaceDynamicCardUi.clearImages(recycled) }
            }
            dynamicListView = list
            list.onRefresh = { rerunActiveTab(forceRefresh = true) }
            list.onPullChanged = { d, armed -> updatePullIndicator(d, armed) }
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
            ).apply { topMargin = dp(24) }
        )

        page.addView(host, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return page
    }

    private fun buildVideoPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        videoSummaryBanner = TextView(this).apply {
            textSize = 12f
            typeface = Ds.body()
            setTextColor(Ds.colors.subtle)
            setPadding(dp(14), dp(8), dp(14), dp(2))
            includeFontPadding = false
            visibility = View.GONE
        }
        page.addView(
            videoSummaryBanner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val host = FrameLayout(this)
        videoAdapter = VideoSearchAdapter()
        val list = SpacePullListView(this).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(14), dp(8), dp(14), dp(28))
            clipToPadding = false
            adapter = videoAdapter
            setRecyclerListener { recycled -> SpaceVideoRowUi.clearImages(recycled) }
        }
        videoListView = list
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                if (!videoLoading && videoHasMore && videoItems.isNotEmpty() &&
                    submittedKeyword.isNotBlank() &&
                    firstVisibleItem + visibleItemCount >= totalItemCount - 6
                ) {
                    loadMoreVideos(submittedKeyword, videoGeneration)
                }
            }
        })
        list.onRefresh = { rerunActiveTab(forceRefresh = true) }
        list.onPullChanged = { d, armed -> updatePullIndicator(d, armed) }

        videoFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(22))
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                if (!videoLoading && submittedKeyword.isNotBlank() && (videoHasMore || videoLastError != null)) {
                    loadMoreVideos(submittedKeyword, videoGeneration)
                }
            }
            addView(
                BrandSpinnerView(this@SpaceSearchActivity, COLOR_ROSE, Color.WHITE).apply {
                    visibility = View.GONE
                },
                LinearLayout.LayoutParams(dp(18), dp(18))
            )
            addView(TextView(this@SpaceSearchActivity).apply {
                textSize = 12f
                typeface = Ds.body()
                setTextColor(COLOR_MUTED)
                setPadding(dp(8), 0, 0, 0)
                includeFontPadding = false
            })
        }
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
            addView(SpaceVideoRowUi.createSkeletonLayout(this@SpaceSearchActivity, this))
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
            ).apply { topMargin = dp(24) }
        )

        page.addView(host, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return page
    }

    private fun updateVideoSortChip() {
        tabStrip.setActionVisible(activeTab == 1)
        tabStrip.setActionLabel(videoOrder.label)
    }

    private fun updatePullIndicator(distance: Float, armed: Boolean) {
        if (distance <= 0.5f) {
            refreshIndicator.visibility = View.INVISIBLE
            refreshIndicator.alpha = 0f
            return
        }
        refreshIndicator.visibility = View.VISIBLE
        val progress = (distance / dp(52).toFloat()).coerceIn(0f, 1.15f)
        refreshIndicator.alpha = if (armed) 1f else progress.coerceAtMost(1f)
        refreshIndicator.setPullProgress(progress)
    }

    private fun submitSearch() {
        val keyword = input.text?.toString()?.trim().orEmpty()
        if (keyword.isEmpty()) {
            showFeedback("先输入要搜索的内容")
            return
        }
        hideKeyboard()
        input.clearFocus()
        SearchHistoryStore.recordForSpace(this, mid, keyword)

        submittedKeyword = keyword
        dynamicGeneration++
        videoGeneration++
        dynamicRequest?.cancel()
        videoRequest?.cancel()
        dynamicLoading = false
        videoLoading = false
        dynamicSearchedKeyword = null
        videoSearchedKeyword = null
        videoSearchedOrder = null

        tabStrip.setTabBadge(0, null)
        tabStrip.setTabBadge(1, null)

        showResults()
        if (activeTab == 0) {
            executeDynamicSearch(keyword, forceRefresh = false)
        } else {
            executeVideoSearch(keyword, forceRefresh = false)
        }
    }

    private fun refreshDynamicSearchDisplay(forceRebuild: Boolean = false) {
        dynamicAdapter?.notifyDataSetChanged()
        dynamicMasonryView?.syncItems(
            items = dynamicItems,
            highlightKeyword = submittedKeyword,
            forceRebuild = forceRebuild,
            onOpenDetail = { dynamic -> SpaceDynamicDetailActivity.open(this@SpaceSearchActivity, dynamic) },
            onOpenVideo = { bvid, aid -> openVideoByMeta(bvid, aid) },
            onFeedback = { msg -> showFeedback(msg) }
        )
    }

    private fun rerunActiveTab(forceRefresh: Boolean) {
        val keyword = submittedKeyword
        if (keyword.isBlank()) {
            dynamicListView?.setRefreshing(false)
            videoListView.setRefreshing(false)
            return
        }
        if (activeTab == 0) {
            executeDynamicSearch(keyword, forceRefresh = forceRefresh)
        } else {
            executeVideoSearch(keyword, forceRefresh = forceRefresh)
        }
    }

    private fun maybeSearchTabOnSwitch(tabIndex: Int) {
        if (submittedKeyword.isBlank()) return
        val keyword = submittedKeyword
        if (tabIndex == 0 && dynamicSearchedKeyword != keyword && !dynamicLoading) {
            executeDynamicSearch(keyword, forceRefresh = false)
        } else if (tabIndex == 1 && (videoSearchedKeyword != keyword || videoSearchedOrder != videoOrder) && !videoLoading) {
            executeVideoSearch(keyword, forceRefresh = false)
        }
    }

    private fun showIdle() {
        idlePanel.visibility = View.VISIBLE
        resultArea.visibility = View.GONE
        refreshHistory()
    }

    private fun showResults() {
        idlePanel.visibility = View.GONE
        resultArea.visibility = View.VISIBLE
    }

    private fun resetToIdle() {
        submittedKeyword = ""
        dynamicGeneration++
        videoGeneration++
        dynamicRequest?.cancel()
        videoRequest?.cancel()
        dynamicLoading = false
        videoLoading = false
        dynamicSearchedKeyword = null
        videoSearchedKeyword = null
        videoSearchedOrder = null
        dynamicListView?.setRefreshing(false)
        videoListView.setRefreshing(false)

        dynamicItems.clear()
        dynamicSeenIds.clear()
        dynamicCheckpoint = null
        dynamicHasMoreToScan = false
        dynamicTotalScanned = 0
        refreshDynamicSearchDisplay(forceRebuild = true)
        dynamicSummaryBanner.visibility = View.GONE
        dynamicFooter.visibility = View.GONE
        dynamicStateView.hide()

        videoItems.clear()
        videoSeenIds.clear()
        videoPage = 0
        videoTotal = null
        videoHasMore = false
        videoLastError = null
        videoFooter.visibility = View.GONE
        videoSummaryBanner.visibility = View.GONE
        videoAdapter.notifyDataSetChanged()
        VideoCardUi.toggleSkeleton(videoSkeletonOverlay, show = false)
        videoStateView.hide()

        tabStrip.setTabBadge(0, null)
        tabStrip.setTabBadge(1, null)

        showIdle()
    }

    // -----------------------------------------------------------------------
    // 动态搜索（支持向更早动态继续扫描）
    // -----------------------------------------------------------------------

    private fun executeDynamicSearch(keyword: String, forceRefresh: Boolean) {
        dynamicRequest?.cancel()
        val gen = ++dynamicGeneration
        dynamicSearchedKeyword = keyword
        dynamicLoading = true
        dynamicItems.clear()
        dynamicSeenIds.clear()
        dynamicCheckpoint = null
        dynamicHasMoreToScan = false
        dynamicTotalScanned = 0
        refreshDynamicSearchDisplay(forceRebuild = true)
        dynamicSummaryBanner.visibility = View.GONE
        dynamicFooter.visibility = View.GONE
        dynamicStateView.showLoading("正在检索最近动态…")

        dynamicRequest = BiliSpaceApi.searchSpaceDynamics(
            mid = mid,
            rawKeyword = keyword,
            resume = null,
            forceRefresh = forceRefresh
        ) { result, error ->
            if (isFinishing || isDestroyed || gen != dynamicGeneration) return@searchSpaceDynamics
            dynamicLoading = false
            dynamicListView?.setRefreshing(false)
            if (result == null) {
                dynamicStateView.showMessage("动态检索失败", error ?: "网络暂不可用", "重试") {
                    executeDynamicSearch(keyword, forceRefresh = true)
                }
                return@searchSpaceDynamics
            }
            applyDynamicSearchResult(keyword, result, append = false)
        }
    }

    private fun continueDynamicSearch(keyword: String) {
        val cp = dynamicCheckpoint
        if (dynamicLoading || !dynamicHasMoreToScan || cp == null) return
        dynamicRequest?.cancel()
        val gen = ++dynamicGeneration
        dynamicLoading = true
        if (dynamicItems.isEmpty()) {
            dynamicStateView.showLoading("正在检索更早动态…")
        } else {
            dynamicFooter.visibility = View.VISIBLE
            dynamicFooterSpinner.visibility = View.VISIBLE
            dynamicFooterSpinner.startSpinning()
            dynamicFooterText.setTextColor(COLOR_MUTED)
            dynamicFooterText.text = "正在继续检索更早动态…"
        }

        dynamicRequest = BiliSpaceApi.searchSpaceDynamics(
            mid = mid,
            rawKeyword = keyword,
            resume = cp,
            forceRefresh = false
        ) { result, error ->
            if (isFinishing || isDestroyed || gen != dynamicGeneration) return@searchSpaceDynamics
            dynamicLoading = false
            dynamicListView?.setRefreshing(false)
            if (result == null) {
                val msg = error ?: "检索失败"
                if (dynamicItems.isEmpty()) {
                    dynamicStateView.showMessage("动态检索失败", msg, "重试") {
                        continueDynamicSearch(keyword)
                    }
                } else {
                    dynamicFooterSpinner.visibility = View.GONE
                    dynamicFooterText.setTextColor(COLOR_ROSE)
                    dynamicFooterText.text = "$msg · 点击重试"
                }
                return@searchSpaceDynamics
            }
            applyDynamicSearchResult(keyword, result, append = true)
        }
    }

    private fun applyDynamicSearchResult(
        keyword: String,
        result: CursorPage<SpaceDynamic>,
        append: Boolean
    ) {
        dynamicTotalScanned += result.scannedItems
        dynamicCheckpoint = result.checkpoint
        dynamicHasMoreToScan = result.hasMore && result.checkpoint != null

        var added = 0
        result.items.forEach { item ->
            if (dynamicSeenIds.add(item.id)) {
                dynamicItems += item
                added++
            }
        }
        refreshDynamicSearchDisplay(forceRebuild = !append)
        tabStrip.setTabBadge(0, if (dynamicItems.isNotEmpty()) dynamicItems.size.toString() else null)

        if (dynamicItems.isEmpty()) {
            dynamicSummaryBanner.visibility = View.GONE
            dynamicFooter.visibility = View.GONE
            if (dynamicHasMoreToScan) {
                dynamicStateView.showMessage(
                    title = "前 $dynamicTotalScanned 条动态未命中「$keyword」",
                    subtitle = "可以继续向更早的历史动态检索",
                    action = "继续检索更早动态"
                ) {
                    continueDynamicSearch(keyword)
                }
            } else {
                dynamicStateView.showMessage(
                    title = "未找到相关动态",
                    subtitle = "已检索全部 $dynamicTotalScanned 条公开动态，未包含「$keyword」"
                )
            }
            return
        }

        dynamicStateView.hide()
        dynamicSummaryBanner.text = if (dynamicHasMoreToScan) {
            "找到 ${dynamicItems.size} 条动态 · 已检索最近 $dynamicTotalScanned 条"
        } else {
            "共找到 ${dynamicItems.size} 条相关动态"
        }
        dynamicSummaryBanner.visibility = View.VISIBLE

        dynamicFooter.visibility = View.VISIBLE
        dynamicFooterSpinner.visibility = View.GONE
        if (dynamicHasMoreToScan) {
            dynamicFooterText.setTextColor(COLOR_ROSE)
            dynamicFooterText.text = if (append && added == 0) {
                "这一批未发现新匹配 · 点击继续检索更早动态"
            } else {
                "继续检索更早动态"
            }
        } else {
            dynamicFooterText.setTextColor(COLOR_MUTED)
            dynamicFooterText.text = "已检索完 TA 的全部公开动态"
        }

        if (!append) {
            dynamicListView?.setSelection(0)
            dynamicMasonryView?.scrollTo(0, 0)
        }
    }

    // -----------------------------------------------------------------------
    // 视频搜索（双列卡片 + 骨架屏 + 排序切换 + 自动分页）
    // -----------------------------------------------------------------------

    private fun executeVideoSearch(keyword: String, forceRefresh: Boolean) {
        videoRequest?.cancel()
        val gen = ++videoGeneration
        videoSearchedKeyword = keyword
        videoSearchedOrder = videoOrder
        videoItems.clear()
        videoSeenIds.clear()
        videoPage = 0
        videoTotal = null
        videoHasMore = true
        videoLoading = false
        videoLastError = null
        videoSummaryBanner.visibility = View.GONE
        videoFooter.visibility = View.GONE
        videoAdapter.notifyDataSetChanged()
        videoStateView.hide()
        VideoCardUi.toggleSkeleton(videoSkeletonOverlay, show = true)
        loadMoreVideos(keyword, gen, forceRefresh = forceRefresh)
    }

    private fun loadMoreVideos(keyword: String, gen: Int, forceRefresh: Boolean = false) {
        if (videoLoading || !videoHasMore) return
        val nextPage = videoPage + 1
        videoLoading = true
        videoLastError = null
        if (videoItems.isNotEmpty()) {
            videoFooter.visibility = View.VISIBLE
            videoFooterSpinner.visibility = View.VISIBLE
            videoFooterSpinner.startSpinning()
            videoFooterText.text = "正在加载…"
        }
        videoRequest = BiliSpaceApi.fetchSpaceVideos(
            mid = mid,
            page = nextPage,
            order = videoOrder,
            keyword = keyword,
            forceRefresh = forceRefresh
        ) { page, error ->
            if (isFinishing || isDestroyed || gen != videoGeneration) return@fetchSpaceVideos
            videoLoading = false
            videoListView.setRefreshing(false)
            VideoCardUi.toggleSkeleton(videoSkeletonOverlay, show = false)
            videoFooterSpinner.visibility = View.GONE
            if (page == null) {
                videoLastError = error ?: "视频搜索失败"
                if (videoItems.isEmpty()) {
                    videoFooter.visibility = View.GONE
                    videoStateView.showMessage("视频搜索失败", videoLastError, "重试") {
                        executeVideoSearch(keyword, forceRefresh = true)
                    }
                } else {
                    videoFooter.visibility = View.VISIBLE
                    videoFooterText.text = "$videoLastError · 点击重试"
                }
                return@fetchSpaceVideos
            }
            videoPage = nextPage
            page.total?.let { t -> videoTotal = t }
            var added = 0
            page.items.forEach { sv ->
                val key = sv.bvid ?: sv.aid?.toString() ?: sv.title
                if (videoSeenIds.add(key)) {
                    videoItems += sv
                    added++
                }
            }
            videoHasMore = page.hasMore && added > 0
            videoAdapter.notifyDataSetChanged()

            val badgeCount = videoTotal?.takeIf { it > 0L } ?: videoItems.size.toLong().takeIf { it > 0L }
            tabStrip.setTabBadge(1, badgeCount?.let { BiliSpaceApi.formatNullableCount(it) })

            if (videoItems.isEmpty()) {
                videoFooter.visibility = View.GONE
                videoSummaryBanner.visibility = View.GONE
                videoStateView.showMessage(
                    title = "未找到相关投稿",
                    subtitle = "没有包含「$keyword」的视频投稿"
                )
                return@fetchSpaceVideos
            }
            videoStateView.hide()
            videoSummaryBanner.text = when {
                videoTotal != null -> "共找到 ${BiliSpaceApi.formatNullableCount(videoTotal)} 个相关投稿 · ${videoOrder.label}"
                videoHasMore -> "已找到 ${videoItems.size} 个投稿 · ${videoOrder.label}"
                else -> "共找到 ${videoItems.size} 个投稿 · ${videoOrder.label}"
            }
            videoSummaryBanner.visibility = View.VISIBLE
            if (videoHasMore) {
                videoFooter.visibility = View.GONE
            } else {
                videoFooter.visibility = View.VISIBLE
                videoFooterText.text = "已经到底了"
            }
            if (nextPage == 1) videoListView.setSelection(0)
        }
    }

    private fun handleBackAction() {
        if (submittedKeyword.isNotEmpty()) {
            // 在结果页按返回先回到搜索输入与历史页，体验对齐全站 SearchActivity
            input.setText("")
            resetToIdle()
            input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        } else {
            hideKeyboard()
            spaceFinishWithAnim()
        }
    }

    private fun openAuthorSpace(targetMid: Long, targetName: String, targetFace: String?) {
        if (targetMid <= 0L) return
        SpaceActivity.open(this, targetMid, targetName, targetFace.orEmpty())
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
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

    private fun showFeedback(message: String) {
        if (isFinishing || isDestroyed || message.isBlank()) return
        snackHost.removeAllViews()
        val bar = TextView(this).apply {
            text = message
            textSize = 13f
            typeface = Ds.body(bold = true)
            setTextColor(0xFFFFFFFF.toInt())
            background = Ds.pill(this@SpaceSearchActivity, if (Theme.isDark) 0xE62A2D36.toInt() else 0xE61E2026.toInt())
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
        handleBackAction()
    }

    override fun onDestroy() {
        dynamicRequest?.cancel()
        videoRequest?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private inner class DynamicSearchAdapter : BaseAdapter() {
        private val cols = if (LayoutMode.isWideScreen(this@SpaceSearchActivity)) 2 else 1
        private val tabletCardW: Int
            get() = ((resources.displayMetrics.widthPixels - dp(44)) / 2).coerceAtLeast(dp(260))

        override fun getCount(): Int = (dynamicItems.size + cols - 1) / cols
        override fun getItem(position: Int): SpaceDynamic = dynamicItems[position * cols]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (cols == 1) {
                SpaceDynamicCardUi.clearImages(convertView)
                return SpaceDynamicCardUi.createCard(
                    activity = this@SpaceSearchActivity,
                    item = dynamicItems[position],
                    highlightKeyword = submittedKeyword,
                    onOpenDetail = { dynamic ->
                        SpaceDynamicDetailActivity.open(this@SpaceSearchActivity, dynamic)
                    },
                    onOpenVideo = { bvid, aid -> openVideoByMeta(bvid, aid) },
                    onFeedback = { msg -> showFeedback(msg) }
                )
            }
            if (convertView is LinearLayout && convertView.tag == "tablet_s_dyn") {
                for (i in 0 until convertView.childCount) {
                    SpaceDynamicCardUi.clearImages(convertView.getChildAt(i))
                }
            }
            val row = LinearLayout(this@SpaceSearchActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                tag = "tablet_s_dyn"
                setPadding(dp(16), 0, dp(16), dp(12))
            }
            val base = position * 2
            val cardW = tabletCardW
            for (c in 0..1) {
                val item = dynamicItems.getOrNull(base + c)
                val cell: View = if (item == null) {
                    View(this@SpaceSearchActivity)
                } else {
                    SpaceDynamicCardUi.createCard(
                        activity = this@SpaceSearchActivity,
                        item = item,
                        highlightKeyword = submittedKeyword,
                        cardWidthPx = cardW,
                        cardMode = true,
                        onOpenDetail = { dynamic ->
                            SpaceDynamicDetailActivity.open(this@SpaceSearchActivity, dynamic)
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

    private inner class VideoSearchAdapter : BaseAdapter() {
        private val cols = if (LayoutMode.isWideScreen(this@SpaceSearchActivity)) 3 else 1

        override fun getCount(): Int = (videoItems.size + cols - 1) / cols
        override fun getItem(position: Int): SpaceVideo = videoItems[position * cols]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (cols == 1) {
                val wrapper = (convertView as? FrameLayout) ?: FrameLayout(this@SpaceSearchActivity).apply {
                    setPadding(0, 0, 0, dp(8))
                    addView(SpaceVideoRowUi.createRow(this@SpaceSearchActivity))
                }
                val row = wrapper.getChildAt(0) as LinearLayout
                val holder = row.tag as SpaceVideoRowUi.Holder
                val video = videoItems[position]
                SpaceVideoRowUi.bind(row, holder, video, highlightKeyword = submittedKeyword)
                row.setOnClickListener { openVideoByMeta(video.bvid, video.aid) }
                return wrapper
            }
            val rowLine = (convertView as? LinearLayout) ?: LinearLayout(this@SpaceSearchActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), 0, dp(12), dp(10))
                for (c in 0 until cols) {
                    val cell = SpaceVideoRowUi.createRow(this@SpaceSearchActivity)
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
                    SpaceVideoRowUi.bind(cell, cell.tag as SpaceVideoRowUi.Holder, video, highlightKeyword = submittedKeyword)
                    cell.setOnClickListener { openVideoByMeta(video.bvid, video.aid) }
                }
            }
            return rowLine
        }
    }

    companion object {
        private const val EXTRA_MID = "mid"
        private const val EXTRA_UP_NAME = "up_name"
        private const val EXTRA_INITIAL_TAB = "initial_tab"

        fun open(
            context: Context,
            mid: Long,
            upName: String = "",
            initialTab: Int = 0
        ) {
            if (mid <= 0L) return
            val intent = Intent(context, SpaceSearchActivity::class.java)
                .putExtra(EXTRA_MID, mid)
                .putExtra(EXTRA_UP_NAME, upName)
                .putExtra(EXTRA_INITIAL_TAB, initialTab)
            context.startActivity(intent)
            if (context is Activity) {
                @Suppress("DEPRECATION")
                context.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left_soft)
            }
        }
    }
}
