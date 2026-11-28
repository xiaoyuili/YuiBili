package com.yuilittle.bili

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 首页「直播」面板：人气直播间网格。
 *
 * 与推荐/热门共用首页 tab 栏；手机 2 列、平板 4 列，滚动到底自动续页。
 * 卡片点击直接进入 [LiveRoomActivity]。
 */
internal class LiveFeedPanel(context: Context) : FrameLayout(context) {

    private val rooms = ArrayList<LiveRoom>()
    private val adapter = LiveAdapter()
    private val grid = GridView(context)
    private val stateText = TextView(context).apply {
        textSize = 13f
        typeface = Ds.body()
        gravity = Gravity.CENTER
        setTextColor(COLOR_MUTED)
        visibility = View.GONE
    }

    private var loadRequest: BiliRequest? = null
    private var page = 0
    private var loading = false
    private var endReached = false
    private var loadedOnce = false
    private var itemWidth = 0
    private var coverHeight = 0

    // 首张卡片在少数布局下不会生成可点击子 View，用坐标兜底补发打开动作。
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var lastOpenAt = 0L
    private var lastOpenPosition = -1

    private val columns: Int
        get() = if (LayoutMode.isTablet) 4 else 2

    init {
        setBackgroundColor(COLOR_BACKGROUND)
        grid.apply {
            numColumns = columns
            stretchMode = GridView.NO_STRETCH
            verticalSpacing = dp(10)
            horizontalSpacing = dp(8)
            setPadding(dp(6), dp(4), dp(6), dp(16))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            adapter = this@LiveFeedPanel.adapter
            // 兜底：即使卡片内部某个子 View 抢走触摸，GridView 也能把点击派发到直播间。
            setOnItemClickListener { _, _, position, _ ->
                openRoom(position)
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                    if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 4) {
                        loadNextPage()
                    }
                }
            })
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(stateText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(24)
            rightMargin = dp(24)
        })
        setOnClickListener { if (rooms.isEmpty() && !loading) reload() }
    }

    /**
     * 首张卡片在部分布局下不会生成可点击子 View（封面未加载时尤其明显），
     * 点封面/标题都没反应。这里只在「按下到抬起没有移动 + 落在第 0 项」时补发一次打开，
     * 并用 [lastOpenAt] 与正常点击链路去重；不消费事件，GridView 滚动与其它卡片点击不受影响。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downAt = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val isTap = abs(ev.x - downX) <= dp(10) &&
                    abs(ev.y - downY) <= dp(10) &&
                    now - downAt < 700L
                if (isTap && rooms.isNotEmpty()) {
                    val gx = ev.x - grid.left
                    val gy = ev.y - grid.top
                    if (gx >= 0 && gy >= 0 && gx <= grid.width && gy <= grid.height &&
                        grid.pointToPosition(gx.toInt(), gy.toInt()) == 0
                    ) {
                        openRoom(0)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 统一入口：无论来自子 View 点击还是坐标兜底，同一项短时间内只打开一次直播间。 */
    private fun openRoom(position: Int) {
        val room = rooms.getOrNull(position) ?: return
        val now = SystemClock.uptimeMillis()
        if (position == lastOpenPosition && now - lastOpenAt < 900L) return
        lastOpenAt = now
        lastOpenPosition = position
        LiveRoomActivity.open(context, room)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val available = w - dp(12) - dp(8) * (columns - 1)
        val nextItemWidth = (available / columns).coerceAtLeast(dp(120))
        if (nextItemWidth != itemWidth) {
            itemWidth = nextItemWidth
            coverHeight = itemWidth * 9 / 16
            grid.columnWidth = itemWidth
            adapter.notifyDataSetChanged()
        }
    }

    fun activate() {
        if (!loadedOnce) reload()
    }

    fun prepareVisible() {
        if (!loadedOnce) reload()
    }

    fun pauseRequests() = Unit

    fun isAtTop(): Boolean = grid.firstVisiblePosition <= 0

    fun firstVisibleRowPosition(): Int = grid.firstVisiblePosition

    fun scrollToTop(): Boolean {
        if (rooms.isEmpty()) return false
        if (grid.firstVisiblePosition <= 0) return false
        grid.smoothScrollToPosition(0)
        return true
    }

    fun cancelScrollToTop() = Unit

    private fun reload() {
        loadRequest?.cancel()
        page = 0
        endReached = false
        loadedOnce = true
        rooms.clear()
        adapter.notifyDataSetChanged()
        requestPage(1)
    }

    private fun loadNextPage() {
        if (loading || endReached || !loadedOnce) return
        requestPage(page + 1)
    }

    private fun requestPage(target: Int) {
        if (loading) return
        loading = true
        if (rooms.isEmpty()) {
            stateText.text = "正在加载直播…"
            stateText.visibility = View.VISIBLE
        }
        loadRequest = LiveApi.fetchRooms(target) { list, error ->
            loading = false
            if (list == null) {
                stateText.text = error ?: "直播列表加载失败"
                stateText.visibility = View.VISIBLE
                return@fetchRooms
            }
            page = target
            if (target == 1) rooms.clear()
            // 房间可能跨页重复（人气榜变动），按 roomId 去重。
            val existing = rooms.mapTo(HashSet()) { it.roomId }
            list.filter { it.roomId > 0L && existing.add(it.roomId) }.forEach(rooms::add)
            if (list.isEmpty()) endReached = true
            adapter.notifyDataSetChanged()
            when {
                rooms.isEmpty() -> {
                    stateText.text = "当前没有正在直播的房间"
                    stateText.visibility = View.VISIBLE
                }
                endReached -> {
                    stateText.text = ""
                    stateText.visibility = View.GONE
                }
                else -> stateText.visibility = View.GONE
            }
        }
    }

    private inner class LiveAdapter : BaseAdapter() {
        override fun getCount(): Int = rooms.size
        override fun getItem(position: Int): Any = rooms[position]
        override fun getItemId(position: Int): Long = rooms[position].roomId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val card = convertView as? LinearLayout ?: buildCard().also { it.tag = Holder(it) }
            val holder = card.tag as Holder
            val room = rooms[position]
            holder.title.text = room.title
            holder.owner.text = if (room.areaName.isNotBlank()) "${room.owner} · ${room.areaName}" else room.owner
            holder.online.text = if (room.online > 0L) formatOnline(room.online) else ""
            holder.online.visibility = if (room.online > 0L) View.VISIBLE else View.GONE
            if (room.cover.isNotBlank()) {
                CoverLoader.load(holder.cover, room.cover, animate = false, thumbEdge = itemWidth.coerceAtLeast(240))
            } else {
                holder.cover.setImageDrawable(null)
            }
            // 封面加载与否都不影响点击：整卡透明热区 + 各子层都挂同一个入口。
            card.setOnClickListener { openRoom(position) }
            val wrap = card.getChildAt(0) as FrameLayout
            wrap.isClickable = true
            wrap.setOnClickListener { openRoom(position) }
            holder.cover.isClickable = true
            holder.cover.setOnClickListener { openRoom(position) }
            holder.hit.isClickable = true
            holder.hit.setOnClickListener { openRoom(position) }
            wrap.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, coverHeight)
            card.layoutParams = AbsListView.LayoutParams(itemWidth, coverHeight + dp(70))
            return card
        }
    }

    private class Holder(val root: LinearLayout) {
        val cover: ImageView = root.getChildAt(0).let { (it as FrameLayout).getChildAt(0) as ImageView }
        val online: TextView = root.getChildAt(0).let {
            ((it as FrameLayout).getChildAt(2) as LinearLayout).getChildAt(0) as TextView
        }
        val title: TextView = root.getChildAt(1) as TextView
        val owner: TextView = root.getChildAt(2) as TextView
        val hit: View = root.getChildAt(0).let { (it as FrameLayout).getChildAt(3) }
    }

    private fun buildCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
        }
        val coverWrap = FrameLayout(context).apply {
            background = Ds.card(context, COLOR_SURFACE, Ds.colors.lineSoft, Ds.Radius.card)
            Ds.clipRounded(this, Ds.Radius.card)
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_SURFACE)
        }
        coverWrap.addView(cover, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val liveBadge = TextView(context).apply {
            text = "直播中"
            textSize = 9f
            typeface = Ds.body(true)
            setTextColor(COLOR_ON_ROSE)
            background = Ds.pill(context, COLOR_ROSE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        coverWrap.addView(liveBadge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = dp(6)
            topMargin = dp(6)
        })

        // 底部人气渐变条
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(6), 0, dp(6), dp(4))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0x99000000.toInt(), 0x00000000)
            )
        }
        val online = TextView(context).apply {
            textSize = 10f
            typeface = Ds.body(true)
            setTextColor(android.graphics.Color.WHITE)
            includeFontPadding = false
        }
        bottomRow.addView(online, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        coverWrap.addView(bottomRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(22),
            Gravity.BOTTOM
        ))

        // 透明热区盖在封面上层：缩略图尚未加载/加载失败时，点击仍由它接住。
        val hit = View(context).apply {
            isClickable = true
            isFocusable = true
        }
        coverWrap.addView(hit, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        card.addView(coverWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0))

        val title = TextView(context).apply {
            textSize = 13f
            typeface = Ds.body(true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(7), 0, 0)
        }
        card.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val owner = TextView(context).apply {
            textSize = 11f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(owner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return card
    }

    private fun formatOnline(value: Long): String = when {
        value >= 100_000_000L -> String.format("%.1f亿", value / 100_000_000.0)
        value >= 10_000L -> String.format("%.1f万", value / 10_000.0)
        else -> value.toString()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDetachedFromWindow() {
        loadRequest?.cancel()
        super.onDetachedFromWindow()
    }
}
