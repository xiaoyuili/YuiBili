package com.yuilittle.bili

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 平板端内嵌「我的收藏」与「历史记录」双工作台视图（挂载在 TabletRailNav 右侧，全幅双栏/多列充分铺满屏幕）：
 * - [TabletFavoritesPageView]：左侧固定 270dp 收藏夹目录栏 + 右侧选中收藏夹的 3~4 列视频网格 + 「播放全部」+ 顶部新建/搜索按钮；
 * - [TabletHistoryPageView]：顶部搜索/清空栏 + 3~4 列观看历史视频网格（带观看进度胶囊与长按删除）+ 右下角悬浮回顶按钮。
 */
internal class TabletFavoritesPageView(
    private val activity: Activity,
    restored: Snapshot? = null,
    private val onSearch: () -> Unit
) : FrameLayout(activity) {

    data class Snapshot(val session: String, val folders: List<FavoriteFolder>, val folderId: Long,
        val videos: List<VideoItem>, val page: Int, val ended: Boolean, val loaded: Boolean,
        val pendingPage: Boolean, val pageFailed: Boolean, val firstVisible: Int, val offset: Int, val foldersY: Int)

    private val folderListContainer = LinearLayout(activity)
    private lateinit var folderScroll: ScrollView
    private var session = BiliSessionStore.cookie()
    private lateinit var rightTitle: TextView
    private lateinit var rightMeta: TextView
    private lateinit var playAllBtn: TextView
    private lateinit var videoListView: ListView
    private lateinit var rightStateView: TextView
    private lateinit var topButton: ScrollToTopButton
    private var topScroller: AbsListSmoothTopScroller? = null
    private lateinit var videoAdapter: FavVideoGridAdapter

    private val folders = ArrayList<FavoriteFolder>()
    private var selectedFolder: FavoriteFolder? = null
    private val folderRows = HashMap<Long, LinearLayout>()
    private val folderVideos = ArrayList<VideoItem>()
    private val seenBvids = HashSet<String>()
    private var favPage = 1
    private var favEnded = false
    private var favLoading = false
    private var favPageError = false
    private var resumeFavPage = false
    private var loadedFoldersOnce = false
    private var sessionGen = 0

    private val rightAvailableWidthPx: Int
        get() = (resources.displayMetrics.widthPixels - dp(TabletRailNav.RAIL_WIDTH_DP + 272 + 32)).coerceAtLeast(dp(360))
    private val videoColumns: Int
        get() = if (rightAvailableWidthPx / resources.displayMetrics.density >= 560f) 3 else 2

    init {
        setBackgroundColor(COLOR_BACKGROUND)
        buildUi()
        restored?.takeIf { it.session == session && it.loaded }?.let { state ->
            favLoading = true
            folders.addAll(state.folders)
            loadedFoldersOnce = state.loaded
            renderFolders()
            selectedFolder = folders.firstOrNull { it.id == state.folderId }
            selectedFolder?.let(::bindFolderSelection)
            folderVideos.addAll(state.videos)
            seenBvids.addAll(state.videos.map { it.bvid })
            favPage = state.page
            favEnded = state.ended
            favPageError = state.pageFailed
            resumeFavPage = state.pendingPage
            videoAdapter.notifyDataSetChanged()
            rightStateView.text = when {
                favPageError -> "加载失败，点这里重试"
                resumeFavPage -> "正在加载收藏内容…"
                folders.isEmpty() -> "暂无收藏夹"
                else -> "这个收藏夹还没有视频"
            }
            rightStateView.setOnClickListener(if (favPageError) View.OnClickListener { favPageError = false; loadMoreFolderVideos() } else null)
            rightStateView.visibility = if (folderVideos.isEmpty() || favPageError) View.VISIBLE else View.GONE
            playAllBtn.visibility = if (folderVideos.isEmpty()) View.GONE else View.VISIBLE
            videoListView.post {
                videoListView.setSelectionFromTop(state.firstVisible, state.offset)
                favLoading = false
                if (resumeFavPage) { resumeFavPage = false; loadMoreFolderVideos() }
            }
            folderScroll.post { folderScroll.scrollTo(0, state.foldersY) }
        }
    }

    fun snapshot(): Snapshot = Snapshot(session, folders.toList(), selectedFolder?.id ?: 0L,
        folderVideos.toList(), favPage, favEnded, loadedFoldersOnce, resumeFavPage || favLoading, favPageError, videoListView.firstVisiblePosition,
        (videoListView.getChildAt(0)?.top ?: videoListView.paddingTop) - videoListView.paddingTop, folderScroll.scrollY)

    fun onPageActivated() {
        if (session != BiliSessionStore.cookie()) {
            session = BiliSessionStore.cookie()
            sessionGen++
            folders.clear()
            selectedFolder = null
            folderVideos.clear()
            seenBvids.clear()
            favLoading = false
            favPageError = false
            resumeFavPage = false
            loadedFoldersOnce = false
            rightStateView.setOnClickListener(null)
            videoAdapter.notifyDataSetChanged()
        }
        if (!loadedFoldersOnce || folders.isEmpty()) {
            loadFolders()
        }
    }

    fun refreshNow() {
        loadFolders()
    }

    private fun buildUi() {
        val c = Ds.colors
        val split = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(c.bg)
        }

        // ── 左栏：收藏夹目录（272dp 宽） ──
        val leftCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.surface)
        }
        val leftHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(12))
        }
        leftHeader.addView(TextView(activity).apply {
            text = "我的收藏"
            textSize = 17f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        leftHeader.addView(FrameLayout(activity).apply {
            background = Ds.pill(activity, c.surfaceRaised, c.lineSoft, Ds.hairline(activity))
            isClickable = true
            contentDescription = "搜索收藏"
            Ds.bindPressFeedback(this, 0.92f)
            addView(Ds.searchIcon(activity, c.muted, 1.7f), FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER))
            setOnClickListener { onSearch() }
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(6) })

        leftHeader.addView(FrameLayout(activity).apply {
            background = Ds.pill(activity, c.accentSoft)
            isClickable = true
            contentDescription = "新建收藏夹"
            Ds.bindPressFeedback(this, 0.92f)
            addView(Ds.plusIcon(activity, c.accent, 1.85f), FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER))
            setOnClickListener {
                activity.startActivity(Intent(activity, CreateFavoriteFolderActivity::class.java))
                loadedFoldersOnce = false
            }
        }, LinearLayout.LayoutParams(dp(32), dp(32)))

        leftCol.addView(leftHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        leftCol.addView(View(activity).apply { setBackgroundColor(c.lineSoft) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(activity)
        ))

        folderListContainer.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(24))
        }
        folderScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(folderListContainer)
        }
        leftCol.addView(folderScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        split.addView(leftCol, LinearLayout.LayoutParams(dp(272), LinearLayout.LayoutParams.MATCH_PARENT))
        split.addView(View(activity).apply { setBackgroundColor(c.lineSoft) }, LinearLayout.LayoutParams(
            Ds.hairline(activity), LinearLayout.LayoutParams.MATCH_PARENT
        ))

        // ── 右栏：当前收藏夹视频网格 ──
        val rightCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        val rightHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        val titleStack = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        rightTitle = TextView(activity).apply {
            text = "请选择收藏夹"
            textSize = 16.5f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        rightMeta = TextView(activity).apply {
            text = ""
            textSize = 12f
            setTextColor(c.muted)
        }
        titleStack.addView(rightTitle)
        titleStack.addView(rightMeta)
        rightHeader.addView(titleStack, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        playAllBtn = TextView(activity).apply {
            text = "播放全部"
            textSize = 12.5f
            typeface = Ds.body(true)
            setTextColor(c.onAccent)
            background = Ds.pill(activity, c.accent)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            visibility = View.GONE
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { playCurrentFolder() }
        }
        rightHeader.addView(playAllBtn)
        rightCol.addView(rightHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val listHost = FrameLayout(activity)
        videoAdapter = FavVideoGridAdapter()
        videoListView = ListView(activity).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(4), 0, dp(28))
            clipToPadding = false
            adapter = videoAdapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView?, firstVisible: Int, visibleCount: Int, totalCount: Int) {
                    if (::topButton.isInitialized) {
                        topButton.setShown(nextScrollTopVisible(topButton.visibility == View.VISIBLE, firstVisible, 3, 1))
                    }
                    if (!favLoading && !favEnded && selectedFolder != null && totalCount > 0 &&
                        topScroller?.isRunning != true && firstVisible + visibleCount >= totalCount - 2
                    ) {
                        loadMoreFolderVideos()
                    }
                }
            })
        }
        topScroller = AbsListSmoothTopScroller(videoListView, nearPosition = 6)
        listHost.addView(videoListView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        rightStateView = TextView(activity).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(c.muted)
            setPadding(dp(24), dp(56), dp(24), dp(24))
        }
        listHost.addView(rightStateView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        topButton = ScrollToTopButton(activity) { topScroller?.scrollToTop() }
        listHost.addView(topButton, LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(8)
            bottomMargin = dp(20)
        })

        rightCol.addView(listHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        split.addView(rightCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(split, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun loadFolders() {
        session = BiliSessionStore.cookie()
        val expectedSession = session
        folderListContainer.removeAllViews()
        folderRows.clear()
        if (!BiliSessionStore.isLoggedIn()) {
            rightTitle.text = "登录后查看收藏夹"
            rightMeta.text = ""
            rightStateView.text = "请在左下角「我的」中扫码登录"
            rightStateView.visibility = View.VISIBLE
            playAllBtn.visibility = View.GONE
            return
        }
        rightStateView.text = "正在加载收藏夹…"
        rightStateView.visibility = View.VISIBLE
        val gen = ++sessionGen
        BiliApi.fetchFavoriteFolders { list, error ->
            if (activity.isDestroyed || gen != sessionGen || expectedSession != BiliSessionStore.cookie()) return@fetchFavoriteFolders
            if (error != null) {
                rightStateView.text = "加载收藏夹失败：$error\n\n点这里重试"
                rightStateView.setOnClickListener { loadFolders() }
                return@fetchFavoriteFolders
            }
            loadedFoldersOnce = true
            folders.clear()
            folders.addAll(list)
            folderListContainer.removeAllViews()
            folderRows.clear()
            if (list.isEmpty()) {
                rightTitle.text = "暂无收藏夹"
                rightMeta.text = ""
                rightStateView.text = "点击左上角 + 创建收藏夹"
                rightStateView.visibility = View.VISIBLE
                return@fetchFavoriteFolders
            }
            renderFolders()
            val initial = selectedFolder?.let { prev -> list.firstOrNull { it.id == prev.id } } ?: list.first()
            selectFolder(initial)
        }
    }

    private fun renderFolders() {
        val c = Ds.colors
        folderListContainer.removeAllViews()
        folderRows.clear()
        folders.forEach { folder ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                isClickable = true
                Ds.bindPressFeedback(this, 0.97f)
                setOnClickListener { selectFolder(folder) }
                addView(TextView(activity).apply {
                    text = folder.name
                    textSize = 14f
                    typeface = Ds.body(true)
                    setTextColor(c.text)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    tag = "name"
                })
                addView(TextView(activity).apply {
                    text = "${folder.count} 个视频 · ${if (folder.isPublic) "公开" else "私密"}"
                    textSize = 11.5f
                    fontFeatureSettings = "tnum"
                    setTextColor(c.muted)
                    setPadding(0, dp(3), 0, 0)
                    tag = "meta"
                })
            }
            folderRows[folder.id] = row
            folderListContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
    }

    private fun bindFolderSelection(folder: FavoriteFolder) {
        val c = Ds.colors
        folderRows.forEach { (id, row) ->
            val active = id == folder.id
            row.background = if (active) Ds.card(activity, c.accentSoft, c.accent and 0x44FFFFFF, Ds.Radius.control, Ds.hairline(activity)) else null
            row.findViewWithTag<TextView>("name")?.setTextColor(if (active) c.accent else c.text)
        }
        rightTitle.text = folder.name
        rightMeta.text = "${folder.count} 个视频 · ${if (folder.isPublic) "公开" else "私密"}"
    }

    private fun selectFolder(folder: FavoriteFolder) {
        selectedFolder = folder
        bindFolderSelection(folder)
        folderVideos.clear()
        seenBvids.clear()
        favPage = 1
        favEnded = false
        favLoading = false
        favPageError = false
        resumeFavPage = false
        rightStateView.setOnClickListener(null)
        playAllBtn.visibility = View.GONE
        topButton.setShown(false, animate = false)
        videoAdapter.notifyDataSetChanged()
        rightStateView.text = "正在加载收藏内容…"
        rightStateView.visibility = View.VISIBLE
        loadMoreFolderVideos()
    }

    private fun loadMoreFolderVideos() {
        val folder = selectedFolder ?: return
        if (favLoading || favEnded || favPageError) return
        favLoading = true
        val gen = sessionGen
        val expectedSession = session
        val page = favPage
        BiliApi.fetchFavoriteVideos(folder.id, page = page, pageSize = 20) { list, error, ended ->
            if (activity.isDestroyed || gen != sessionGen || selectedFolder?.id != folder.id || expectedSession != BiliSessionStore.cookie()) return@fetchFavoriteVideos
            favLoading = false
            if (error != null) {
                favPageError = true
                rightStateView.text = "加载失败：$error\n\n点这里重试"
                rightStateView.visibility = View.VISIBLE
                rightStateView.setOnClickListener { favPageError = false; loadMoreFolderVideos() }
                return@fetchFavoriteVideos
            }
            favPageError = false
            rightStateView.setOnClickListener(null)
            list.forEach { if (seenBvids.add(it.bvid)) folderVideos.add(it) }
            favPage = page + 1
            favEnded = ended || list.isEmpty()
            videoAdapter.notifyDataSetChanged()
            if (folderVideos.isEmpty()) {
                rightStateView.text = "这个收藏夹还没有视频"
                rightStateView.visibility = View.VISIBLE
                playAllBtn.visibility = View.GONE
            } else {
                rightStateView.visibility = View.GONE
                playAllBtn.visibility = View.VISIBLE
            }
        }
    }

    private var playAllPreparing = false

    private fun playCurrentFolder() {
        val folder = selectedFolder ?: return
        if (folderVideos.isEmpty() || playAllPreparing) return
        // 若收藏夹尚未拉完全部分页（例如几十上百个视频），先串行拉完剩余分页再启动完整队列，
        // 与「我的 → 我的收藏」播放全部行为完全一致。
        if (!favEnded) {
            playAllPreparing = true
            playAllBtn.isEnabled = false
            playAllBtn.alpha = 0.55f
            playAllBtn.text = "正在准备…"
            val collected = ArrayList(folderVideos)
            val seen = HashSet(seenBvids)
            var nextPage = favPage
            val expectedGen = sessionGen
            fun finishPlay(list: List<VideoItem>) {
                playAllPreparing = false
                playAllBtn.isEnabled = true
                playAllBtn.alpha = 1f
                playAllBtn.text = "播放全部"
                if (expectedGen != sessionGen || activity.isFinishing || activity.isDestroyed) return
                launchFavoriteQueue(folder, list)
            }
            fun loadNext() {
                BiliApi.fetchFavoriteVideos(folder.id, page = nextPage, pageSize = 20) { videos, error, ended ->
                    if (expectedGen != sessionGen || activity.isFinishing || activity.isDestroyed) {
                        playAllPreparing = false
                        return@fetchFavoriteVideos
                    }
                    if (error != null) {
                        finishPlay(collected)
                        return@fetchFavoriteVideos
                    }
                    videos.forEach { v -> if (seen.add(v.bvid)) collected.add(v) }
                    if (ended || videos.isEmpty()) {
                        finishPlay(collected)
                    } else {
                        nextPage++
                        loadNext()
                    }
                }
            }
            loadNext()
            return
        }
        launchFavoriteQueue(folder, folderVideos)
    }

    private fun launchFavoriteQueue(folder: FavoriteFolder, list: List<VideoItem>) {
        if (list.isEmpty()) return
        val entries = list.map { v ->
            UgcEpisode(v.bvid, v.aid, v.cid, v.title, v.duration, v.cover, v.publishedAt, owner = v.owner)
        }
        activity.startActivity(
            FavoritePlaybackLauncher.intent(
                activity,
                FavoritePlayback(folder.id, folder.name, entries, 0)
            )
        )
    }

    private inner class FavVideoGridAdapter : BaseAdapter() {
        private val cols = videoColumns
        private val cardW = VideoCardUi.cardWidth(activity, cols, rightAvailableWidthPx)
        private val coverH = VideoCardUi.coverHeight(cardW)
        private val cardH = VideoCardUi.cardHeight(coverH, activity)

        override fun getCount(): Int = (folderVideos.size + cols - 1) / cols
        override fun getItem(position: Int): VideoItem? = folderVideos.getOrNull(position * cols)
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
                val video = folderVideos.getOrNull(base + c)
                if (video == null) {
                    card.visibility = View.INVISIBLE
                    card.isClickable = false
                    holder.boundVideo = null
                    CoverLoader.clear(holder.cover)
                } else {
                    card.visibility = View.VISIBLE
                    card.isClickable = true
                    VideoCardUi.bind(card, holder, video, showPublishDate = false)
                }
            }
            return row
        }
    }
}

/** 平板端内嵌「历史记录」4 列卡片墙，支持搜索历史、清空、自动分页与悬浮回顶。 */
internal class TabletHistoryPageView(
    private val activity: Activity,
    restored: Snapshot? = null,
    private val onSearch: () -> Unit
) : FrameLayout(activity) {

    data class Snapshot(val session: String, val cloud: List<VideoItem>, val max: Long, val viewAt: Long,
        val business: String, val ended: Boolean, val firstVisible: Int, val offset: Int,
        val pendingPage: Boolean, val failed: Boolean)

    private lateinit var listView: ListView
    private lateinit var stateView: TextView
    private lateinit var topButton: ScrollToTopButton
    private var topScroller: AbsListSmoothTopScroller? = null
    private lateinit var adapter: HistoryGridAdapter

    private val items = ArrayList<HistoryStore.ViewedRow>()
    private lateinit var retryPage: TextView
    private var cursorMax = 0L
    private var cursorViewAt = 0L
    private var cursorBusiness = "archive"
    private val cloudRows = ArrayList<VideoItem>()
    private var ended = false
    private var loading = false
    private var active = false
    private var pageError = false
    private var session = ""
    private var gen = 0
    private var restoredReady = false
    private var resumeHistoryPage = false

    private val availableWidthPx: Int
        get() = (resources.displayMetrics.widthPixels - dp(TabletRailNav.RAIL_WIDTH_DP + 36)).coerceAtLeast(dp(480))
    private val cols: Int
        get() = LayoutMode.gridColumns(activity, availableWidthPx)

    init {
        setBackgroundColor(COLOR_BACKGROUND)
        buildUi()
        restored?.takeIf { it.session == BiliSessionStore.cookie() }?.let { state ->
            session = state.session
            cloudRows.addAll(state.cloud)
            cursorMax = state.max
            cursorViewAt = state.viewAt
            cursorBusiness = state.business
            ended = state.ended
            pageError = state.failed
            resumeHistoryPage = state.pendingPage
            items.addAll(HistoryStore.mergeRows(activity, cloudRows))
            restoredReady = true
            adapter.notifyDataSetChanged()
            stateView.text = if (resumeHistoryPage) "正在加载观看历史…" else "暂无观看历史"
            stateView.visibility = if (items.isEmpty() && !pageError) View.VISIBLE else View.GONE
            if (pageError) { retryPage.text = "云端历史暂未同步，点此重试"; retryPage.visibility = View.VISIBLE }
            listView.post { listView.setSelectionFromTop(state.firstVisible, state.offset) }
        }
    }

    fun snapshot(): Snapshot = Snapshot(session, cloudRows.toList(), cursorMax, cursorViewAt, cursorBusiness,
        ended, listView.firstVisiblePosition, (listView.getChildAt(0)?.top ?: listView.paddingTop) - listView.paddingTop, resumeHistoryPage || loading, pageError)

    fun setActive(value: Boolean) {
        if (active == value && (!value || session == BiliSessionStore.cookie())) return
        active = value
        if (value) {
            if (restoredReady && session == BiliSessionStore.cookie()) {
                restoredReady = false
                if (resumeHistoryPage) { resumeHistoryPage = false; loadMore() }
            } else refresh()
        } else {
            gen++
            loading = false
            session = ""
            restoredReady = false
        }
    }

    fun refresh() {
        if (!active) return
        gen++
        session = BiliSessionStore.cookie()
        cloudRows.clear()
        retryPage.visibility = View.GONE
        cursorMax = 0L
        cursorViewAt = 0L
        cursorBusiness = "archive"
        ended = !BiliSessionStore.isLoggedIn(session)
        pageError = false
        loading = false
        items.clear()
        items.addAll(HistoryStore.mergeRows(activity, cloudRows))
        stateView.setOnClickListener(null)
        stateView.text = if (ended) "暂无本地观看记录" else "正在加载观看历史…"
        stateView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        topButton.setShown(false, animate = false)
        loading = !ended
        adapter.notifyDataSetChanged()
        listView.setSelection(0)
        loading = false
        if (!ended) loadMore()
    }

    private fun buildUi() {
        val c = Ds.colors
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), 0)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        header.addView(TextView(activity).apply {
            text = "历史记录"
            textSize = 18f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(FrameLayout(activity).apply {
            isFocusable = true
            contentDescription = "搜索观看历史"
            addView(Ds.searchIcon(activity, c.text, 1.8f), LayoutParams(dp(20), dp(20), Gravity.CENTER))
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { onSearch() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val host = FrameLayout(activity)
        retryPage = TextView(activity).apply {
            textSize = 12f
            setTextColor(c.muted)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            visibility = View.GONE
            isFocusable = true
            setOnClickListener {
                pageError = false
                visibility = View.GONE
                loadMore()
            }
        }
        adapter = HistoryGridAdapter()
        listView = ListView(activity).apply {
            addFooterView(retryPage, null, true)
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(4), 0, dp(28))
            clipToPadding = false
            adapter = this@TabletHistoryPageView.adapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView?, firstVisible: Int, visibleCount: Int, totalCount: Int) {
                    if (::topButton.isInitialized) {
                        topButton.setShown(nextScrollTopVisible(topButton.visibility == View.VISIBLE, firstVisible, 3, 1))
                    }
                    if (!loading && !ended && totalCount > 0 && topScroller?.isRunning != true &&
                        firstVisible + visibleCount >= totalCount - 2
                    ) {
                        loadMore()
                    }
                }
            })
        }
        topScroller = AbsListSmoothTopScroller(listView, nearPosition = 6)
        host.addView(listView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        stateView = TextView(activity).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(c.muted)
            setPadding(dp(24), dp(56), dp(24), dp(24))
        }
        host.addView(stateView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        topButton = ScrollToTopButton(activity) { topScroller?.scrollToTop() }
        host.addView(topButton, LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(10)
            bottomMargin = dp(20)
        })

        col.addView(host, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun loadMore() {
        if (!active || loading || ended || pageError) return
        if (session != BiliSessionStore.cookie()) { refresh(); return }
        loading = true
        val expected = gen
        val expectedSession = session
        BiliApi.fetchHistory(cursorMax, cursorViewAt, cursorBusiness) { list, error, nextMax, nextViewAt, nextBiz, isEnd ->
            if (!active || expected != gen) return@fetchHistory
            if (expectedSession != BiliSessionStore.cookie()) { refresh(); return@fetchHistory }
            loading = false
            if (error != null) {
                pageError = true
                if (items.isEmpty()) {
                    stateView.text = "加载历史失败：$error\n\n点这里重试"
                    stateView.visibility = View.VISIBLE
                    stateView.setOnClickListener {
                        pageError = false
                        stateView.text = "正在加载观看历史…"
                        stateView.setOnClickListener(null)
                        loadMore()
                    }
                } else {
                    retryPage.text = "云端历史暂未同步，点此重试"
                    retryPage.visibility = View.VISIBLE
                }
                return@fetchHistory
            }
            retryPage.visibility = View.GONE
            cloudRows.addAll(list)
            items.clear()
            items.addAll(HistoryStore.mergeRows(activity, cloudRows))
            cursorMax = nextMax
            cursorViewAt = nextViewAt
            cursorBusiness = nextBiz.ifBlank { "archive" }
            ended = isEnd || nextMax <= 0L
            adapter.notifyDataSetChanged()
            stateView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (items.isEmpty()) stateView.text = "暂无观看历史"
        }
    }

    private inner class HistoryGridAdapter : BaseAdapter() {
        private val cardW = VideoCardUi.cardWidth(activity, cols, availableWidthPx)
        private val coverH = VideoCardUi.coverHeight(cardW)
        private val cardH = VideoCardUi.cardHeight(coverH, activity)

        override fun getCount(): Int = (items.size + cols - 1) / cols
        override fun getItem(position: Int): VideoItem? = items.getOrNull(position * cols)?.video
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
                                VideoDetailActivity.openHistory(activity, v)
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
                val history = items.getOrNull(base + c)
                val video = history?.video
                if (video == null) {
                    card.visibility = View.INVISIBLE
                    card.isClickable = false
                    holder.boundVideo = null
                    CoverLoader.clear(holder.cover)
                } else {
                    card.visibility = View.VISIBLE
                    card.isClickable = true
                    // 历史接口把删除键 archive_xxx 暂存在 rcmdReason，且接口不返回播放量；
                    // 展示时清空 rcmdReason 以正常显示 UP 主昵称，封面左下展示观看进度/观看日期，右下展示「已看/总时长」。
                    val displayVideo = video.copy(rcmdReason = "", publishedAt = history.viewedAtSec)
                    VideoCardUi.bind(card, holder, displayVideo, showPublishDate = true)
                    holder.boundVideo = video
                    val progressBadge = formatHistoryCoverBadge(
                        progressSec = video.watchProgressSec.takeIf { it != 0L },
                        durationSec = video.duration
                    )
                    if (progressBadge.isNotEmpty()) holder.duration.text = progressBadge
                    // 封面右下角已有「已看/总时长」，左下角不重复显示「看到 x:xx」；仅在存在观看时间时按需隐藏播放量区
                    holder.viewsText.text = ""
                    holder.viewsText.visibility = View.GONE
                    (holder.viewsText.parent as? ViewGroup)?.getChildAt(0)?.visibility = View.GONE
                }
            }
            return row
        }
    }
}
