package com.yuilittle.bili

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 消息中心：私信会话、回复与 @、收到的喜欢、新增粉丝。
 *
 * 入口在手机首页右上角与平板侧栏「我的」上方。
 * 页面只做展示，点击任意一行会用桌面端 UA + 站内登录态打开网页版对应页面（私信会话 / 评论原页），
 * 回复操作在网页里完成，不重复实现私信长连接协议。
 */
internal class MessageCenterPageView(
    context: Context,
    private val showBack: Boolean = true,
    private val onBack: (() -> Unit)? = null
) : LinearLayout(context) {

    private var destroyed = false

    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var sectionTitle: TextView
    private lateinit var backToSessions: TextView
    private val entryChips = LinkedHashMap<MessageKind, LinearLayout>()
    private val entryLabels = LinkedHashMap<MessageKind, TextView>()

    private var mode = MessageKind.SESSION
    private var loadRequest: BiliRequest? = null
    private var generation = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BACKGROUND)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }

        // ── 顶栏 ──
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(14), dp(8))
        }
        val back = Ds.backIcon(context, COLOR_INK) { onBack?.invoke() }
        back.visibility = if (showBack) View.VISIBLE else View.GONE
        bar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(TextView(context).apply {
            text = "消息"
            textSize = 19f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── 三个入口 ──
        val entryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(2), dp(14), dp(6))
        }
        entryRow.addView(buildEntry(MessageKind.REPLY, "@", "回复与@", COLOR_ROSE),
            LinearLayout.LayoutParams(0, dp(84), 1f))
        entryRow.addView(buildEntry(MessageKind.LIKE, "赞", "收到喜欢", 0xFF5AA9FF.toInt()),
            LinearLayout.LayoutParams(0, dp(84), 1f).apply { leftMargin = dp(10) })
        entryRow.addView(buildEntry(MessageKind.FAN, "粉", "新增粉丝", 0xFF35C7A0.toInt()),
            LinearLayout.LayoutParams(0, dp(84), 1f).apply { leftMargin = dp(10) })
        root.addView(entryRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── 分区标题 ──
        val sectionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(6))
        }
        sectionTitle = TextView(context).apply {
            text = "私信"
            textSize = 14.5f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            includeFontPadding = false
        }
        sectionRow.addView(sectionTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        backToSessions = TextView(context).apply {
            text = "返回私信"
            textSize = 12f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_MUTED)
            visibility = View.GONE
            isClickable = true
            setPadding(dp(8), dp(4), dp(2), dp(4))
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { switchMode(MessageKind.SESSION) }
        }
        sectionRow.addView(backToSessions)
        root.addView(sectionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── 列表 ──
        scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(24))
        }
        scroll.addView(listBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateEntryChrome()
        loadCurrent()
    }

    // ── 入口卡片 ───────────────────────────────────────────────────────────

    private fun buildEntry(kind: MessageKind, symbol: String, label: String, accent: Int): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(12), dp(6), dp(11))
            background = Ds.card(context, COLOR_SURFACE, Ds.colors.lineSoft, Ds.Radius.control)
            isClickable = true
            isFocusable = true
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener { switchMode(kind) }
        }
        box.addView(TextView(context).apply {
            text = symbol
            textSize = 16f
            typeface = Ds.display(bold = true)
            gravity = Gravity.CENTER
            setTextColor(accent)
            background = Ds.pill(context, blendOnSurface(accent))
            includeFontPadding = false
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val caption = TextView(context).apply {
            text = label
            textSize = 12f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_INK)
            includeFontPadding = false
        }
        box.addView(caption, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(7) })
        entryChips[kind] = box
        entryLabels[kind] = caption
        return box
    }

    /** 把强调色兑进卡片底色，暗色主题下也不会刺眼。 */
    private fun blendOnSurface(accent: Int): Int {
        val base = COLOR_SURFACE
        val ratio = 0.16f
        fun mix(a: Int, b: Int): Int =
            (a * ratio + b * (1 - ratio)).toInt().coerceIn(0, 255)
        return Color.argb(
            255,
            mix(Color.red(accent), Color.red(base)),
            mix(Color.green(accent), Color.green(base)),
            mix(Color.blue(accent), Color.blue(base))
        )
    }

    private fun updateEntryChrome() {
        entryChips.forEach { (kind, box) ->
            val active = kind == mode
            box.background = Ds.card(
                context,
                if (active) COLOR_ROSE_SOFT else COLOR_SURFACE,
                if (active) COLOR_ROSE else Ds.colors.lineSoft,
                Ds.Radius.control
            )
            entryLabels[kind]?.setTextColor(if (active) COLOR_ROSE else COLOR_INK)
        }
        sectionTitle.text = when (mode) {
            MessageKind.SESSION -> "私信"
            MessageKind.REPLY -> "回复与@"
            MessageKind.LIKE -> "收到的喜欢"
            MessageKind.FAN -> "新增粉丝"
            MessageKind.AT -> "@我的"
        }
        backToSessions.visibility = if (mode == MessageKind.SESSION) View.GONE else View.VISIBLE
    }

    private fun switchMode(kind: MessageKind) {
        if (mode == kind) {
            loadCurrent()
            return
        }
        mode = kind
        updateEntryChrome()
        loadCurrent()
    }

    // ── 数据 ───────────────────────────────────────────────────────────────

    private fun loadCurrent() {
        loadRequest?.cancel()
        val gen = ++generation
        showState("加载中…", clickable = false)
        loadRequest = when (mode) {
            MessageKind.SESSION -> MessageApi.fetchSessions { items, error -> handleResult(gen, items, error) }
            MessageKind.REPLY, MessageKind.AT -> run {
                loadRepliesAndAts(gen)
                null
            }
            MessageKind.LIKE -> MessageApi.fetchLikes { items, error -> handleResult(gen, items, error) }
            MessageKind.FAN -> MessageApi.fetchNewFans { items, error -> handleResult(gen, items, error) }
        }
    }

    /** 「回复与@」是两个接口，合并展示且任一为空都不影响另一类。 */
    private fun loadRepliesAndAts(gen: Int) {
        val merged = ArrayList<MessageItem>()
        var remaining = 2
        var firstError: String? = null
        fun finish() {
            if (--remaining > 0) return
            // 两个接口各自有序，合并后按时间倒序重排。
            merged.sortByDescending { it.ts }
            if (merged.isEmpty() && firstError != null) {
                handleResult(gen, null, firstError)
            } else {
                handleResult(gen, merged, null)
            }
        }
        MessageApi.fetchReplies { items, error ->
            if (gen != generation) return@fetchReplies
            if (items != null) merged += items else if (firstError == null) firstError = error
            finish()
        }
        MessageApi.fetchAts { items, error ->
            if (gen != generation) return@fetchAts
            if (items != null) merged += items else if (firstError == null) firstError = error
            finish()
        }
    }

    private fun handleResult(gen: Int, items: List<MessageItem>?, error: String?) {
        if (gen != generation || destroyed) return
        if (items == null) {
            showState(error ?: "消息加载失败", clickable = true)
            return
        }
        if (items.isEmpty()) {
            showState(
                when (mode) {
                    MessageKind.SESSION -> "还没有私信"
                    MessageKind.REPLY, MessageKind.AT -> "还没有回复和@"
                    MessageKind.LIKE -> "还没有收到喜欢"
                    MessageKind.FAN -> "还没有新增粉丝"
                },
                clickable = false
            )
            return
        }
        renderList(items)
    }

    private fun renderList(items: List<MessageItem>) {
        listBox.removeAllViews()
        items.forEachIndexed { index, item ->
            listBox.addView(buildRow(item), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            if (index != items.lastIndex) {
                listBox.addView(View(context).apply { setBackgroundColor(Ds.colors.lineSoft) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(context)).apply {
                        leftMargin = dp(56)
                    })
            }
        }
        scroll.scrollTo(0, 0)
    }

    private fun showState(text: String, clickable: Boolean) {
        listBox.removeAllViews()
        val label = TextView(context).apply {
            this.text = text
            textSize = 13f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(56), dp(24), dp(56))
            includeFontPadding = false
            if (clickable) {
                isClickable = true
                Ds.bindPressFeedback(this, 0.96f)
                setOnClickListener { loadCurrent() }
            }
        }
        listBox.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    /** 点击一行要打开的网页版地址。 */
    private fun webTarget(item: MessageItem): String = when {
        item.kind == MessageKind.SESSION && item.system -> "https://message.bilibili.com/"
        item.kind == MessageKind.SESSION && item.mid > 0L ->
            "https://message.bilibili.com/#/whisper/mid${item.mid}"
        item.uri.startsWith("http") -> {
            // 网页版评论锚点：#reply{rpid} 可直接滚到具体的回复 / @ 那条评论。
            if (item.replyId > 0L && !item.uri.contains("#reply")) {
                "${item.uri}#reply${item.replyId}"
            } else item.uri
        }
        item.kind == MessageKind.REPLY || item.kind == MessageKind.AT ->
            "https://message.bilibili.com/#/reply"
        item.kind == MessageKind.LIKE -> "https://message.bilibili.com/#/like"
        item.kind == MessageKind.FAN && item.mid > 0L ->
            "https://space.bilibili.com/${item.mid}"
        else -> ""
    }

    private fun buildRow(item: MessageItem): View {
        val target = webTarget(item)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(12))
            if (target.isNotBlank()) {
                isClickable = true
                Ds.bindPressFeedback(this, 0.98f)
                setOnClickListener {
                    InAppWebActivity.open(
                        context,
                        target,
                        desktopUa = true,
                        withBiliLogin = true
                    )
                }
            }
        }
        val avatar = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ds.circle(COLOR_SOFT_FILL)
            Ds.clipRounded(this, 999)
        }
        if (item.face.isNotBlank()) {
            CoverLoader.load(avatar, item.face, cropThumb = false, thumbEdge = 120, animate = false)
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(42), dp(42)))

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(2), 0)
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = item.name
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (item.timeText.isNotBlank()) {
            titleRow.addView(TextView(context).apply {
                text = item.timeText
                textSize = 10.5f
                typeface = Ds.body()
                fontFeatureSettings = "tnum"
                setTextColor(Ds.colors.subtle)
                includeFontPadding = false
            })
        }
        col.addView(titleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        contentRow.addView(TextView(context).apply {
            text = item.content
            textSize = 12.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (item.unread > 0) {
            contentRow.addView(View(context).apply { background = Ds.circle(COLOR_ROSE) },
                LinearLayout.LayoutParams(dp(7), dp(7)).apply { leftMargin = dp(8) })
        }
        col.addView(contentRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5) })

        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    fun setActive(active: Boolean) {
        if (active && !destroyed && listBox.childCount == 0 && loadRequest == null) loadCurrent()
    }

    fun destroy() {
        destroyed = true
        loadRequest?.cancel()
    }
}
