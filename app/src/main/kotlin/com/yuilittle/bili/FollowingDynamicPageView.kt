package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * 「关注」动态流页面：手机端作为底栏第二项、平板端作为侧栏独立页。
 *
 * 复用空间动态的解析与卡片组件（[BiliSpaceApi.parseDynamicItem] / [SpaceDynamicCardUi.createCard]），
 * 数据源为 `web-dynamic/v1/feed/all`，需要登录态。点击视频进入播放页，
 * 点击图文卡片进入已有的动态详情页。
 */
internal class FollowingDynamicPageView(context: Context) : LinearLayout(context) {

    private val items = ArrayList<SpaceDynamic>()
    private val adapter = DynamicAdapter()
    private val listView = ListView(context)
    private val stateText = TextView(context)
    private val stateAction = TextView(context)
    private val refreshBtn: TextView

    private var loadRequest: BiliRequest? = null
    private var nextOffset: String? = null
    private var loading = false
    private var endReached = false
    private var started = false
    private var active = false

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BACKGROUND)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(12), dp(6))
        }
        header.addView(TextView(context).apply {
            text = "关注动态"
            textSize = 19f
            typeface = Ds.display(true)
            setTextColor(COLOR_INK)
            includeFontPadding = false
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        refreshBtn = TextView(context).apply {
            text = "刷新"
            textSize = 12f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ROSE)
            background = Ds.pill(context, COLOR_ROSE_SOFT)
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(6), dp(14), dp(6))
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { if (!loading) load(reset = true) }
        }
        header.addView(refreshBtn, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))

        val stage = FrameLayout(context)
        listView.apply {
            divider = ColorDrawable(COLOR_BACKGROUND)
            dividerHeight = dp(8)
            setPadding(dp(8), 0, dp(8), dp(24))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            adapter = this@FollowingDynamicPageView.adapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                    if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 3) {
                        loadMore()
                    }
                }
            })
        }
        stage.addView(listView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        stateText.apply {
            textSize = 13f
            typeface = Ds.body()
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(32), dp(12), dp(32), dp(12))
            visibility = View.GONE
        }
        stage.addView(stateText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        stateAction.apply {
            textSize = 12f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ON_ROSE)
            background = Ds.pill(context, COLOR_ROSE)
            isClickable = true
            isFocusable = true
            setPadding(dp(20), dp(8), dp(20), dp(8))
            visibility = View.GONE
            setOnClickListener {
                if (!BiliSessionStore.isLoggedIn()) {
                    openAccountPage()
                } else {
                    load(reset = true)
                }
            }
        }
        stage.addView(stateAction, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply { topMargin = dp(56) })

        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        updatePlaceholder()
    }

    fun setActive(value: Boolean) {
        active = value
        if (value && !started) load(reset = true)
    }

    fun activate() = setActive(true)

    fun prepareVisible() {
        if (!started) load(reset = true)
    }

    fun pauseRequests() = Unit

    /** 用户主动刷新（底部导航重复点击、顶部刷新按钮共用）。 */
    fun refresh() {
        load(reset = true)
    }

    fun isAtTop(): Boolean = listView.firstVisiblePosition <= 0

    fun firstVisibleRowPosition(): Int = listView.firstVisiblePosition

    fun scrollToTop(): Boolean {
        if (items.isEmpty() || listView.firstVisiblePosition <= 0) return false
        listView.smoothScrollToPosition(0)
        return true
    }

    fun cancelScrollToTop() = Unit

    private fun load(reset: Boolean) {
        if (loading) return
        if (!reset && (endReached || items.isEmpty())) return
        if (!BiliSessionStore.isLoggedIn()) {
            items.clear()
            adapter.notifyDataSetChanged()
            updatePlaceholder()
            return
        }
        if (reset && items.isNotEmpty()) {
            // 刷新时保留旧内容，避免列表闪空。
            nextOffset = null
            endReached = false
        }
        loading = true
        if (!reset || items.isEmpty()) showState("正在加载关注动态…")
        loadRequest = BiliSpaceApi.fetchFollowingDynamics(if (reset) "" else nextOffset.orEmpty()) { page, error ->
            loading = false
            if (!isAttachedToWindow) return@fetchFollowingDynamics
            if (page == null) {
                if (items.isEmpty()) {
                    showState(error ?: "关注动态加载失败", action = "重试")
                } else {
                    showState(error ?: "加载失败", action = "重试", transient = true)
                }
                return@fetchFollowingDynamics
            }
            started = true
            if (reset) items.clear()
            val existing = items.mapTo(HashSet()) { it.id }
            page.items.filter { it.id.isNotBlank() && existing.add(it.id) }.forEach(items::add)
            nextOffset = page.nextOffset
            endReached = !page.hasMore || page.items.isEmpty()
            adapter.notifyDataSetChanged()
            if (items.isEmpty()) {
                showState("关注的 UP 主还没有发布动态")
            } else {
                hideState()
            }
        }
    }

    private fun loadMore() {
        if (loading || endReached || !started) return
        load(reset = false)
    }

    private fun updatePlaceholder() {
        if (!BiliSessionStore.isLoggedIn()) {
            showState("登录后可查看关注的 UP 主动态", action = "去登录")
        } else if (items.isEmpty() && !loading) {
            showState("正在加载关注动态…")
        }
    }

    private fun showState(message: String, action: String? = null, transient: Boolean = false) {
        stateText.text = message
        stateText.visibility = View.VISIBLE
        stateAction.apply {
            text = action.orEmpty()
            visibility = if (action.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        if (transient) {
            postDelayed({
                if (stateText.text == message) hideState()
            }, 2200L)
        }
    }

    private fun hideState() {
        stateText.visibility = View.GONE
        stateAction.visibility = View.GONE
    }

    private fun openAccountPage() {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SWITCH_TO_ACCOUNT, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    private inner class DynamicAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val activity = context as? Activity
                ?: return LinearLayout(context)
            val dynamic = items[position]
            val card = SpaceDynamicCardUi.createCard(
                activity = activity,
                item = dynamic,
                cardMode = true,
                onOpenDetail = { item -> SpaceDynamicDetailActivity.open(activity, item) },
                onOpenVideo = { bvid, aid ->
                    if (!bvid.isNullOrBlank()) {
                        VideoDetailActivity.open(activity, bvid, aid ?: 0L)
                    }
                }
            )
            card.layoutParams = AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
            return card
        }
    }

    override fun onDetachedFromWindow() {
        loadRequest?.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
