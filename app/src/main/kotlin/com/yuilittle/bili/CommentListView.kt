package com.yuilittle.bili

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

internal class CommentListView(
    private val activity: Activity,
    private val feedback: (String) -> Unit,
    private val replyAnchor: () -> Int,
    leadingHeader: View? = null,
    private val sheetRoot: VideoComment? = null,
    private val showSort: Boolean = true
) : ListView(activity) {
    data class RetainedState(
        val oid: String,
        val type: Int,
        val epoch: Long,
        val sort: Int,
        val items: List<VideoComment>,
        val pageState: CommentPageState,
        val replyPage: Int,
        val loaded: Boolean,
        val interrupted: Boolean,
        val viewport: Bundle
    )

    private var retained: RetainedState? = null
    private val rows = ArrayList<VideoComment>()
    private val seen = HashSet<Long>()
    private val expanded = HashSet<Long>()
    private var oid = ""
    private var type = 1
    private var owner = 0L
    private var sort = 0
    private var pageState = CommentPageState()
    private val failed: Boolean get() = pageState.failed
    private val hasMore: Boolean get() = pageState.hasMore
    private val manualNext: Boolean get() = pageState.manualNext
    private var replyPage = 1
    private var session = BiliSessionStore.snapshot()
    private var request: BiliRequest? = null
    private var generation = 0
    private var loaded = false
    private var loading = false
    private var replyWarmPages = if (sheetRoot == null) 0 else 2
    private var enabledForPage = false
    private var interrupted = false
    private var replyDialog: CommentSectionUi.ReplySheetDialog? = null
    private var prefetch: CoverLoader.PrefetchHandle? = null
    private var pendingRestore: Bundle? = null
    private val header = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val sortHeader: CommentSectionUi.CommentHeaderBar = CommentSectionUi.CommentHeaderBar(activity) { changeSort() }
    private fun changeSort() {
        pendingRestore = null
        sort = if (sort == 0) 1 else 0
        sortHeader.applySort(sort)
        reset()
        ensureStarted()
    }
    private val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val status = TextView(activity).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(COLOR_MUTED)
        setPadding(activity.dp(16), activity.dp(20), activity.dp(16), activity.dp(24))
        setOnClickListener {
            if (loading) return@setOnClickListener
            pageState = pageState.retry()
            load()
        }
    }
    private val login = TextView(activity).apply {
        text = "仅展示热门评论，登录后可查看全部回复 →"
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(COLOR_ROSE)
        setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(16))
        visibility = GONE
        setOnClickListener {
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SWITCH_TO_ACCOUNT, true)
            })
        }
    }
    private val rowAdapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id
        override fun hasStableIds() = true
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = false
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val row = recycled as? CommentRowView ?: CommentRowView(activity, sheetRoot != null, false,
                if (sheetRoot == null) ::openReplies else null, feedback)
            val item = rows[position]
            row.bind(item, owner, oid, type, item.id in expanded) { value ->
                if (value) expanded += item.id else expanded -= item.id
            }
            return row
        }
    }

    init {
        divider = ColorDrawable(Ds.colors.lineSoft)
        dividerHeight = Ds.hairline(activity)
        selector = ColorDrawable(Color.TRANSPARENT)
        cacheColorHint = Color.TRANSPARENT
        isVerticalScrollBarEnabled = false
        itemsCanFocus = true
        clipToPadding = false
        setBackgroundColor(COLOR_BACKGROUND)
        if (leadingHeader != null) header.addView(leadingHeader, LinearLayout.LayoutParams(-1, -2))
        if (sheetRoot != null) {
            header.addView(CommentRowView(activity, true, true, null, feedback).apply {
                bind(sheetRoot, owner, oid, type, false) {}
            }, LinearLayout.LayoutParams(-1, -2))
            header.addView(TextView(activity).apply {
                text = if (sheetRoot.replyCount > 0) "全部回复  ${sheetRoot.replyCount}" else "全部回复"
                textSize = 12.5f
                typeface = Ds.display(bold = true)
                setTextColor(COLOR_INK)
                setBackgroundColor(Ds.colors.surfaceRaised)
                setPadding(activity.dp(16), activity.dp(9), activity.dp(16), activity.dp(9))
            }, LinearLayout.LayoutParams(-1, -2))
        } else if (showSort) header.addView(sortHeader, LinearLayout.LayoutParams(-1, -2))
        footer.addView(status, LinearLayout.LayoutParams(-1, -2))
        footer.addView(login, LinearLayout.LayoutParams(-1, -2))
        addHeaderView(header, null, false)
        addFooterView(footer, null, false)
        adapter = rowAdapter
        setRecyclerListener { (it as? CommentRowView)?.release() }
        setOnScrollListener(object : AbsListView.OnScrollListener {
            private var userScrolling = false
            override fun onScrollStateChanged(view: AbsListView, state: Int) { userScrolling = state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE }
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (userScrolling && visible > 0 && first + visible >= total - 2 && rows.isNotEmpty() && !failed && !manualNext) load()
            }
        })
        renderStatus()
    }

    fun bindTarget(nextOid: String, nextType: Int, nextOwner: Long, start: Boolean = false) {
        if (oid != nextOid || type != nextType || !BiliSessionStore.isCurrent(session)) {
            oid = nextOid
            type = nextType
            owner = nextOwner
            reset()
        } else if (owner != nextOwner) {
            owner = nextOwner
            rowAdapter.notifyDataSetChanged()
        }
        retained?.let { saved ->
            retained = null
            if (saved.oid == oid && saved.type == type && saved.epoch == session.epoch) {
                rows.clear()
                rows += saved.items
                seen.clear()
                seen += saved.items.map(VideoComment::id)
                sort = saved.sort
                pageState = saved.pageState
                replyPage = saved.replyPage
                loaded = saved.loaded
                interrupted = saved.interrupted
                pendingRestore = saved.viewport
                rowAdapter.notifyDataSetChanged()
                renderStatus()
            }
        }
        pendingRestore?.takeIf { it.getString("oid") == oid && it.getInt("type") == type }?.let {
            sort = it.getInt("sort").coerceIn(0, 1)
            sortHeader.applySort(sort)
        }
        if (sheetRoot != null) (header.getChildAt(0) as? CommentRowView)?.bind(sheetRoot, owner, oid, type, sheetRoot.id in expanded) {
            if (it) expanded += sheetRoot.id else expanded -= sheetRoot.id
        }
        if (loaded) post { restoreAnchor() }
        if (start) ensureStarted()
    }

    fun ensureStarted() {
        if (!enabledForPage) return
        if (!BiliSessionStore.isCurrent(session)) reset()
        if (!loaded && !loading && !failed) load()
    }

    fun setPageActive(active: Boolean) {
        enabledForPage = active
        if (!active) {
            interrupted = interrupted || loading
            cancelRequest()
            prefetch?.cancel()
            replyDialog?.dismiss()
            renderStatus()
        } else {
            if (!BiliSessionStore.isCurrent(session)) reset()
            if (interrupted) { interrupted = false; load() }
            else ensureStarted()
        }
    }

    fun reset() {
        cancelRequest()
        session = BiliSessionStore.snapshot()
        prefetch?.cancel()
        prefetch = null
        replyDialog?.dismiss()
        replyDialog = null
        rows.clear()
        seen.clear()
        expanded.clear()
        pageState = CommentPageState()
        replyPage = 1
        loaded = false
        interrupted = false
        replyWarmPages = if (sheetRoot == null) 0 else 2
        rowAdapter.notifyDataSetChanged()
        setSelection(0)
        renderStatus()
    }

    private fun sortHeaderUpdate() { sortHeader.applySort(sort) }

    private fun load() {
        if (!enabledForPage || loading || !hasMore || oid.isBlank() || type <= 0) return
        if (!BiliSessionStore.isCurrent(session)) { reset(); load(); return }
        pageState = pageState.retry()
        loading = true
        val gen = ++generation
        val auth = session
        val requestedOffset = pageState.offset
        renderStatus()
        if (sheetRoot != null) {
            request = BiliApi.fetchReplyPage(oid, type, sheetRoot.id, replyPage, auth) { items, next, error ->
                if (gen != generation || !BiliSessionStore.isCurrent(auth)) return@fetchReplyPage
                request = null
                loading = false
                if (error != null) {
                    pageState = pageState.fail(error)
                    logPage("reply-error", requestedOffset)
                    renderStatus()
                    return@fetchReplyPage
                }
                val fresh = append(items)
                loaded = true
                pageState = pageState.accept(null, next > 0, fresh)
                if (next > 0) replyPage = next
                logPage("reply-ok", requestedOffset, items.size, fresh)
                rowAdapter.notifyDataSetChanged()
                renderStatus()
                restoreAnchor()
                if (replyWarmPages > 0) replyWarmPages--
                if (hasMore && fresh > 0 && replyWarmPages > 0) post {
                    if (gen == generation && enabledForPage) load()
                }
            }
        } else {
            request = BiliApi.fetchCommentPage(oid, type, sort, requestedOffset, auth) { page, error ->
                if (gen != generation || !BiliSessionStore.isCurrent(auth)) return@fetchCommentPage
                request = null
                loading = false
                if (error != null || page == null) {
                    pageState = pageState.fail(error ?: "未返回评论数据")
                    logPage("comment-error", requestedOffset)
                    renderStatus()
                    return@fetchCommentPage
                }
                val fresh = append(page.items)
                if (!loaded && fresh > 0) prefetch = CommentSectionUi.prefetchCommentTopMedia(activity, page.items)
                loaded = true
                pageState = pageState.accept(page.nextOffset, page.hasMore, fresh, page.failureReason)
                logPage(if (failed) "comment-cursor-error" else "comment-ok", requestedOffset, page.items.size, fresh, page.nextOffset == requestedOffset && requestedOffset != null)
                rowAdapter.notifyDataSetChanged()
                renderStatus()
                restoreAnchor()
            }
        }
    }

    private fun append(items: List<VideoComment>): Int {
        var count = 0
        items.forEach { item -> if (item.id > 0 && seen.add(item.id)) { rows += item; count++ } }
        return count
    }

    private fun logPage(result: String, requestedOffset: String?, count: Int = 0, fresh: Int = 0, repeatedCursor: Boolean = false) {
        if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val reason = pageState.failureReason.orEmpty()
        val apiCode = reason.substringAfterLast("（错误码 ", "").substringBefore('）').toIntOrNull()
        val httpCode = reason.substringAfterLast("HTTP ", "").substringBefore('）').toIntOrNull()
        Log.i("YuiBiliComment", "result=$result target=${oid.hashCode()} type=$type sort=$sort " +
            "cursorChars=${requestedOffset?.length ?: 0} repeatedCursor=$repeatedCursor rows=$count fresh=$fresh total=${rows.size} " +
            "replyPage=$replyPage root=${sheetRoot?.id?.hashCode() ?: 0} hasMore=$hasMore manualNext=$manualNext " +
            "failure=${reason.hashCode()} apiCode=$apiCode httpCode=$httpCode")
    }

    private fun renderStatus() {
        status.visibility = if (loading || failed || rows.isEmpty() || hasMore) VISIBLE else GONE
        status.text = when {
            loading -> if (rows.isEmpty()) "正在加载评论…" else "正在加载更多…"
            failed -> "${if (rows.isEmpty()) "评论加载失败" else "加载更多失败"}：${pageState.failureReason}\n\n点这里重试当前页"
            !loaded -> "正在加载评论…"
            rows.isEmpty() && !hasMore -> "暂无评论"
            manualNext -> "继续加载评论"
            hasMore -> "上滑加载更多"
            else -> ""
        }
        status.isClickable = !loading && (failed || hasMore)
        login.visibility = if (sheetRoot == null && loaded && !hasMore && !failed && rows.isNotEmpty() && !BiliSessionStore.isLoggedIn(session.cookie)) VISIBLE else GONE
    }

    private fun cancelRequest() {
        generation++
        request?.cancel()
        request = null
        loading = false
    }

    private fun openReplies(comment: VideoComment) {
        if (!enabledForPage || !BiliSessionStore.isCurrent(session) || replyDialog?.isShowing == true) return
        replyDialog = CommentSectionUi.ReplySheetDialog(activity, comment, oid, type, owner, replyAnchor, feedback).also { it.show() }
    }

    fun saveState(): Bundle = Bundle().apply {
        putString("oid", oid)
        putInt("type", type)
        putInt("sort", sort)
        putLongArray("expanded", expanded.toLongArray())
        val position = firstVisiblePosition
        putLong("anchor_id", rows.getOrNull(position - headerViewsCount)?.id ?: 0L)
        putInt("anchor", position)
        putInt("top", getChildAt(0)?.top?.minus(paddingTop) ?: 0)
    }

    fun restoreState(state: Bundle?) { pendingRestore = state }

    fun retainState(): RetainedState = RetainedState(
        oid, type, session.epoch, sort, rows.toList(), pageState, replyPage,
        loaded, interrupted || loading, saveState()
    )

    fun restoreRetainedState(state: RetainedState?) { retained = state }

    private fun restoreAnchor() {
        val state = pendingRestore ?: return
        if (state.getString("oid") != oid || state.getInt("type") != type) { pendingRestore = null; return }
        if (state.getInt("sort") != sort) { sort = state.getInt("sort"); sortHeaderUpdate(); reset(); ensureStarted(); return }
        expanded += state.getLongArray("expanded")?.toList().orEmpty()
        rowAdapter.notifyDataSetChanged()
        val anchorId = state.getLong("anchor_id")
        val rowIndex = rows.indexOfFirst { it.id == anchorId }
        val position = if (rowIndex >= 0) rowIndex + headerViewsCount else state.getInt("anchor").coerceAtMost(rows.size)
        setSelectionFromTop(position, state.getInt("top"))
        pendingRestore = null
    }

    fun dispose() {
        enabledForPage = false
        cancelRequest()
        prefetch?.cancel()
        prefetch = null
        replyDialog?.dismiss()
        replyDialog = null
        for (index in 0 until childCount) releaseRows(getChildAt(index))
        releaseRows(header)
    }

    private fun releaseRows(view: View) {
        if (view is CommentRowView) { view.release(); return }
        if (view is ViewGroup) for (index in 0 until view.childCount) releaseRows(view.getChildAt(index))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (enabledForPage) post {
            if (interrupted) { interrupted = false; load() } else ensureStarted()
        }
    }

    override fun onDetachedFromWindow() {
        interrupted = interrupted || loading
        cancelRequest()
        prefetch?.cancel()
        replyDialog?.dismiss()
        renderStatus()
        super.onDetachedFromWindow()
    }
}
