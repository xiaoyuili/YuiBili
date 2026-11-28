package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * 历史记录搜索页：按标题关键词搜索 B 站观看历史，列表展示。
 * 进入/退出使用软滑移动画；结果行入场淡入上浮。
 */
class HistorySearchActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var stateView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var searchGeneration = 0
    private var loading = false
    private var currentPage = 1
    private var currentKeyword = ""
    private var ended = false
    private var loadingRow: View? = null
    private val seen = HashSet<String>()
    private var rowAnimIndex = 0
    private val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Theme.applySystemBars(this)
        PlayerSettingsStore.applyHomeOrientation(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }

        // ── 顶栏：返回 + 搜索框（对齐全站 40dp 胶囊高度与字号） ──
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.Space.sm), dp(Ds.Space.sm), dp(Ds.Space.md), dp(Ds.Space.sm))
        }
        val back = Ds.backIcon(this, COLOR_INK) { finishWithAnim() }
        bar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))

        val field = FrameLayout(this).apply {
            background = Ds.pill(this@HistorySearchActivity, COLOR_SURFACE, Ds.colors.lineSoft)
        }
        field.addView(
            Ds.searchIcon(this, COLOR_MUTED, 1.8f),
            FrameLayout.LayoutParams(dp(16), dp(16), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(14)
            }
        )
        input = EditText(this).apply {
            hint = "搜索历史记录标题"
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
                val go = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN)
                if (go) {
                    hideKeyboard()
                    runSearch(input.text?.toString().orEmpty(), force = true)
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    scheduleSearch(s?.toString().orEmpty())
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
        val clear = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            contentDescription = "清空搜索词"
            Ds.bindPressFeedback(this, 0.90f)
            addView(
                Ds.closeIcon(this@HistorySearchActivity, COLOR_MUTED, 1.7f),
                FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)
            )
            setOnClickListener {
                input.setText("")
                input.requestFocus()
                showIdle()
            }
        }
        field.addView(
            clear,
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp(6)
            }
        )
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })
        bar.addView(
            field,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(4)
            }
        )
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)))

        // ── 内容区 ──
        val content = FrameLayout(this)
        stateView = TextView(this).apply {
            text = "输入关键词搜索历史观看"
            textSize = 13f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(80), dp(24), 0)
        }
        content.addView(stateView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            setOnScrollChangeListener { _, _, _, _, _ ->
                if (ended || loading || currentKeyword.isBlank()) return@setOnScrollChangeListener
                val child = getChildAt(0) ?: return@setOnScrollChangeListener
                if (child.bottom <= height + scrollY + dp(200)) {
                    loadMore()
                }
            }
        }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(28))
        }
        scroll.addView(
            LayoutMode.wrapCenteredForTablet(this, listBox, maxDp = 820),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        // 入场后聚焦输入框，键盘弹出
        input.post {
            input.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun scheduleSearch(raw: String) {
        pendingSearch?.let { mainHandler.removeCallbacks(it) }
        val q = raw.trim()
        if (q.isEmpty()) {
            showIdle()
            return
        }
        val task = Runnable { runSearch(q, force = false) }
        pendingSearch = task
        mainHandler.postDelayed(task, 320L)
    }

    private fun runSearch(raw: String, force: Boolean) {
        val q = raw.trim()
        if (q.isEmpty()) {
            showIdle()
            return
        }
        if (!force && q == currentKeyword && !ended && listBox.childCount > 0) return
        if (!BiliSessionStore.isLoggedIn()) {
            showState("请先登录后再搜索历史")
            return
        }
        currentKeyword = q
        currentPage = 1
        ended = false
        seen.clear()
        rowAnimIndex = 0
        loadingRow = null
        listBox.removeAllViews()
        scroll.visibility = View.VISIBLE
        stateView.visibility = View.GONE
        showState("正在搜索…")
        fetchPage(reset = true)
    }

    private fun loadMore() {
        if (loading || ended || currentKeyword.isBlank()) return
        if (loadingRow == null) {
            loadingRow = footer("正在加载…")
            listBox.addView(loadingRow)
        }
        fetchPage(reset = false)
    }

    private fun fetchPage(reset: Boolean) {
        val gen = ++searchGeneration
        loading = true
        val page = currentPage
        val keyword = currentKeyword
        BiliApi.searchHistory(keyword, page) { videos, error, pageEnded ->
            if (isFinishing || isDestroyed || gen != searchGeneration) return@searchHistory
            loading = false
            loadingRow?.let { runCatching { listBox.removeView(it) } }
            loadingRow = null
            if (error != null) {
                if (reset) showState(error)
                else {
                    val retry = footer("加载失败，点击重试").apply {
                        isClickable = true
                        setOnClickListener { loadMore() }
                    }
                    loadingRow = retry
                    listBox.addView(retry)
                }
                return@searchHistory
            }
            if (reset) {
                listBox.removeAllViews()
                stateView.visibility = View.GONE
                scroll.visibility = View.VISIBLE
            }
            var added = 0
            videos.forEach { item ->
                if (!seen.add(item.bvid)) return@forEach
                val row = buildRow(item)
                listBox.addView(row)
                animateRowIn(row, rowAnimIndex++)
                added++
            }
            ended = pageEnded || videos.isEmpty()
            if (!ended) currentPage = page + 1
            if (reset && added == 0) {
                showState("没有找到「$keyword」相关历史")
            } else if (ended && listBox.childCount > 0) {
                listBox.addView(footer("没有更多了"))
            }
        }
    }

    private fun buildRow(item: VideoItem): View {
        val date = if (item.publishedAt > 0L) {
            android.text.format.DateFormat.format(
                "MM-dd HH:mm",
                java.util.Date(item.publishedAt * 1000L)
            ).toString()
        } else ""
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(this@HistorySearchActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(10), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                VideoDetailActivity.open(
                    this@HistorySearchActivity,
                    item.bvid,
                    item.aid,
                    item.cid,
                    historyResumeMs(item.watchProgressSec, item.duration)
                )
            }
        }
        val thumbBox = FrameLayout(this).apply {
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_COVER)
        }
        if (item.cover.isNotBlank()) CoverLoader.load(thumb, item.cover + "@320w_180h.webp")
        thumbBox.addView(
            thumb,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val overlayLabel = formatHistoryCoverBadge(item.watchProgressSec, item.duration)
        if (overlayLabel.isNotBlank()) {
            thumbBox.addView(
                TextView(this).apply {
                    text = overlayLabel
                    textSize = 9.5f
                    typeface = Ds.body(true)
                    fontFeatureSettings = "tnum"
                    setTextColor(Color.WHITE)
                    background = rounded(0xA6000000.toInt(), dp(5))
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                    includeFontPadding = false
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).apply {
                    rightMargin = dp(4)
                    bottomMargin = dp(5)
                }
            )
        }
        val ratio = computeHistoryProgressRatio(item.watchProgressSec, item.duration)
        if (ratio > 0f) {
            val track = FrameLayout(this).apply { setBackgroundColor(0x55000000) }
            val fill = View(this).apply { setBackgroundColor(COLOR_ROSE) }
            val coverWidthPx = dp(116)
            val fillWidthPx = (coverWidthPx * ratio).toInt().coerceIn(dp(4), coverWidthPx)
            track.addView(fill, FrameLayout.LayoutParams(fillWidthPx, FrameLayout.LayoutParams.MATCH_PARENT))
            thumbBox.addView(
                track,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(3),
                    Gravity.BOTTOM
                )
            )
        }
        row.addView(thumbBox, LinearLayout.LayoutParams(dp(116), dp(66)))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        body.addView(TextView(this).apply {
            text = item.title
            textSize = 14f
            typeface = Ds.body(true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(TextView(this).apply {
            text = if (date.isBlank()) item.owner else "${item.owner} · $date"
            textSize = 11.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        Ds.bindPressFeedback(row, 0.98f)
        return row
    }

    private fun animateRowIn(row: View, index: Int) {
        row.alpha = 0f
        row.translationY = dp(12).toFloat()
        row.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index.coerceAtMost(10) * 24L))
            .setDuration(260L)
            .setInterpolator(easeOut)
            .start()
    }

    private fun showIdle() {
        pendingSearch?.let { mainHandler.removeCallbacks(it) }
        searchGeneration++
        currentKeyword = ""
        ended = true
        loading = false
        listBox.removeAllViews()
        scroll.visibility = View.GONE
        stateView.visibility = View.VISIBLE
        stateView.text = "输入关键词搜索历史观看"
    }

    private fun showState(text: String) {
        scroll.visibility = View.GONE
        stateView.visibility = View.VISIBLE
        stateView.text = text
    }

    private fun footer(text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(14), 0, dp(14))
        if (text.contains("加载") && !text.contains("失败")) {
            addView(BrandSpinnerView(this@HistorySearchActivity, COLOR_ROSE, Color.WHITE),
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) })
        }
        addView(TextView(this@HistorySearchActivity).apply {
            this.text = text
            textSize = 12f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
        })
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun finishWithAnim() {
        hideKeyboard()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left_soft, R.anim.slide_out_right)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithAnim()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    /** 顶栏搜索框内的小放大镜，统一 stroke 风格。 */
    private class SearchGlyph(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED
            style = Paint.Style.STROKE
            strokeWidth = 1.7f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) * 0.28f
            canvas.drawCircle(cx - r * 0.18f, cy - r * 0.18f, r, paint)
            canvas.drawLine(
                cx + r * 0.55f, cy + r * 0.55f,
                cx + r * 1.15f, cy + r * 1.15f,
                paint
            )
        }
    }
}
