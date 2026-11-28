package com.yuilittle.bili

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 个人中心子页面容器：
 * - MODE_HISTORY：观看历史（登录态远端游标分页 + 观看进度 + 时间分组 + 长按删除/清空 + 失败可重试切本地）
 * - MODE_FAVORITES：我的收藏（限流预览并发队列 + 收藏夹列表 + 夹内分页列表 + 长按移出收藏）
 * - MODE_SPONSOR：赞助开发（简洁微信扫码卡片 + 保存/长按保存）
 * - MODE_ABOUT：关于软件（紧凑卡片化信息 + 版本复制 + 免责声明弹窗）
 * - MODE_SETTINGS：播放设置（屏幕方向锁定 + 默认播放画质）
 */
class ProfileSubActivity : Activity() {

    private var mode = MODE_HISTORY
    private lateinit var rootColumn: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var listBox: LinearLayout
    private lateinit var playAllBtn: View
    /** 收藏夹详情已加载的视频，按收藏夹原始顺序累积（分页去重）。 */
    private val favVideos = ArrayList<VideoItem>()
    /** 收藏夹自定义封面缓存：folderId -> cover URL（created/list-all 不返回 cover）。 */
    private val folderCoverCache = HashMap<Long, String>()
    /** 已发起过封面元数据查询的收藏夹，避免重复请求挤爆队列。 */
    private val coverMetaRequested = HashSet<Long>()
    private lateinit var titleView: TextView
    private var searchBtnRef: View? = null
    private var addBtnRef: View? = null
    private var moreBtnRef: View? = null
    /** 批量管理模式：列表左侧显示勾选框，底部出现全选/删除条。 */
    private var batchMode = false
    private val selectedBvids = LinkedHashSet<String>()
    private var batchBar: View? = null
    private var batchSelectAllBtn: TextView? = null
    private var batchDeleteBtn: TextView? = null
    private val favRowChecks = HashMap<String, BatchCheckView>()
    private var clearBtnRef: TextView? = null

    private val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private var pageSessionGen = 0

    // ── 历史记录状态 ──────────────────────────────────────────────
    private var historyCursorMax = 0L
    private var historyCursorViewAt = 0L
    private var historyCursorBusiness = "archive"
    private var historyEnded = false
    private var historyLoading = false
    private var historyEmptyAutoAdvance = 0
    private var usingLocalHistory = false
    private var historyFooterRow: View? = null
    private val historySeen = HashSet<String>()
    private var historyLastGroup = ""
    private var historyRowAnimIndex = 0
    private val todayStartSec: Long by lazy {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000L
    }

    // ── 我的收藏状态 ──────────────────────────────────────────────
    private var inFavoriteFolder = false
    private var currentFolder: FavoriteFolder? = null
    private var favListScrollY = 0
    private var favPage = 1
    private var favEnded = false
    private var favLoading = false
    private var favFooterRow: View? = null
    private val favSeen = HashSet<String>()
    private var favRowAnimIndex = 0
    private val previewCache = HashMap<Long, List<VideoItem>>()
    private val previewQueue = ArrayDeque<Pair<FavoriteFolder, (List<VideoItem>) -> Unit>>()
    private var previewInFlight = 0
    private val maxPreviewConcurrent = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_HISTORY
        Theme.applySystemBars(this)
        PlayerSettingsStore.applyHomeOrientation(this)
        window.setBackgroundDrawable(ColorDrawable(COLOR_BACKGROUND))

        val root = FrameLayout(this).apply { setBackgroundColor(COLOR_BACKGROUND) }
        rootHost = root
        rootColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── 统一顶栏 ──────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.Space.sm), dp(Ds.Space.sm), dp(Ds.Space.md), dp(Ds.Space.sm))
        }
        header.addView(
            Ds.backIcon(this, COLOR_INK) { handleBack() },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        titleView = TextView(this).apply {
            text = titleFor(mode)
            textSize = 18f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(8), 0)
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (mode == MODE_HISTORY) {
            val clearBtn = TextView(this).apply {
                text = "清空"
                textSize = 12.5f
                typeface = Ds.body(true)
                setTextColor(COLOR_MUTED)
                background = Ds.pill(this@ProfileSubActivity, COLOR_SURFACE, Ds.colors.lineSoft)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                visibility = View.GONE
                Ds.bindPressFeedback(this, 0.94f)
                setOnClickListener { confirmClearHistory() }
            }
            clearBtnRef = clearBtn
            header.addView(clearBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(4) })
        }

        if (mode == MODE_HISTORY || mode == MODE_FAVORITES) {
            val searchBtn = FrameLayout(this).apply {
                isClickable = true
                isFocusable = true
                contentDescription = "搜索"
                Ds.bindPressFeedback(this, 0.90f)
                setOnClickListener {
                    if (mode == MODE_HISTORY) openHistorySearch()
                    else openFavoriteSearch()
                }
                addView(
                    Ds.searchIcon(this@ProfileSubActivity, COLOR_INK, 1.8f),
                    FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER)
                )
            }
            searchBtnRef = searchBtn
            header.addView(searchBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

            // 加号在搜索右边；只在收藏夹列表页显示，进入某个收藏夹后隐藏
            if (mode == MODE_FAVORITES) {
                val addBtn = FrameLayout(this).apply {
                    isClickable = true
                    isFocusable = true
                    contentDescription = "创建收藏夹"
                    Ds.bindPressFeedback(this, 0.90f)
                    setOnClickListener { openCreateFolder() }
                    addView(
                        Ds.plusIcon(this@ProfileSubActivity, COLOR_INK, 1.9f),
                        FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER)
                    )
                }
                addBtnRef = addBtn
                header.addView(addBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
            }
        }

        // 收藏夹详情页的「更多」按钮：默认隐藏，进入某个收藏夹后显示
        moreBtnRef = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "更多操作"
            visibility = View.GONE
            Ds.bindPressFeedback(this, 0.90f)
            setOnClickListener { showFolderMoreMenu(it) }
            addView(
                MoreDotsIcon(this@ProfileSubActivity, COLOR_INK),
                FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER)
            )
        }
        header.addView(moreBtnRef, LinearLayout.LayoutParams(dp(40), dp(40)))

        rootColumn.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        rootColumn.addView(
            View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this))
        )

        scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val child = scroll.getChildAt(0) ?: return@setOnScrollChangeListener
            if (child.bottom > scroll.height + scroll.scrollY + dp(220)) return@setOnScrollChangeListener
            when (mode) {
                MODE_HISTORY -> {
                    if (!usingLocalHistory && !historyEnded && !historyLoading) {
                        loadHistoryPage()
                    }
                }
                MODE_FAVORITES -> {
                    if (inFavoriteFolder && !favEnded && !favLoading) {
                        loadFavoriteFolderPage()
                    }
                }
            }
        }

        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = dp(if (LayoutMode.isWideScreen(this@ProfileSubActivity)) 24 else Ds.Space.lg)
            setPadding(hPad, dp(Ds.Space.md), hPad, dp(28))
        }
        scroll.addView(
            listBox,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        rootColumn.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            rootColumn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        playAllBtn = buildPlayAllButton()
        root.addView(
            playAllBtn,
            FrameLayout.LayoutParams(dp(96), dp(44), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(Ds.Space.lg)
                bottomMargin = dp(28)
            }
        )
        setContentView(root)

        when (mode) {
            MODE_HISTORY -> renderHistory()
            MODE_FAVORITES -> loadFavorites()
            MODE_DOWNLOADS -> renderDownloads()
            MODE_SPONSOR -> renderSponsor()
            MODE_SETTINGS -> renderSettings()
            else -> renderAbout()
        }
    }

    override fun onDestroy() {
        pageSessionGen++
        previewQueue.clear()
        super.onDestroy()
    }

    private fun titleFor(mode: String): String = when (mode) {
        MODE_FAVORITES -> "我的收藏"
        MODE_DOWNLOADS -> "下载管理"
        MODE_SPONSOR -> "赞助开发"
        MODE_ABOUT -> "关于软件"
        MODE_SETTINGS -> "播放设置"
        else -> "历史记录"
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. 历史记录（远端游标分页 + 时间分组 + 进度条 + 长按删除 + 本地兜底）
    // ═══════════════════════════════════════════════════════════════

    private fun renderHistory() {
        val gen = ++pageSessionGen
        listBox.removeAllViews()
        historySeen.clear()
        historyLastGroup = ""
        historyRowAnimIndex = 0
        historyFooterRow = null
        clearBtnRef?.visibility = View.GONE
        scroll.scrollTo(0, 0)

        if (!BiliSessionStore.isLoggedIn()) {
            renderLocalHistory(bannerText = null)
            return
        }

        usingLocalHistory = false
        historyCursorMax = 0L
        historyCursorViewAt = 0L
        historyCursorBusiness = "archive"
        historyEnded = false
        historyLoading = false
        historyEmptyAutoAdvance = 0
        listBox.addView(loadingHint("正在同步观看历史…"))
        loadHistoryPage(expectedGen = gen)
    }

    private fun loadHistoryPage(expectedGen: Int = pageSessionGen) {
        if (usingLocalHistory || historyLoading || historyEnded || expectedGen != pageSessionGen) return
        historyLoading = true
        val firstPage = historyCursorMax == 0L && historyCursorViewAt == 0L && historySeen.isEmpty()
        if (!firstPage) {
            setHistoryFooter(loadMoreHint("正在加载…"))
        }

        BiliApi.fetchHistory(
            historyCursorMax,
            historyCursorViewAt,
            historyCursorBusiness
        ) { videos, error, nextMax, nextViewAt, nextBusiness, ended ->
            if (isFinishing || isDestroyed || expectedGen != pageSessionGen) return@fetchHistory
            historyLoading = false
            removeHistoryFooter()
            if (firstPage) listBox.removeAllViews()

            if (error != null) {
                if (firstPage) {
                    val localItems = HistoryStore.load(this)
                    if (localItems.isNotEmpty()) {
                        renderLocalHistory(bannerText = "远端同步失败，当前展示本机记录 · 点此重试")
                    } else {
                        historyEnded = true
                        listBox.addView(
                            emptyActionCard(
                                title = "加载失败",
                                subtitle = error,
                                actionLabel = "重新加载"
                            ) { renderHistory() }
                        )
                    }
                } else {
                    val retryRow = loadMoreHint("加载失败，点击重试").apply {
                        isClickable = true
                        setOnClickListener { loadHistoryPage(expectedGen) }
                    }
                    setHistoryFooter(retryRow)
                }
                return@fetchHistory
            }

            if (firstPage && videos.isEmpty() && ended) {
                historyEnded = true
                listBox.addView(
                    emptyActionCard(
                        title = "暂无观看记录",
                        subtitle = "看过的视频会同步到这里"
                    )
                )
                return@fetchHistory
            }

            var added = 0
            videos.forEach { item ->
                if (!historySeen.add(item.bvid)) return@forEach
                val group = historyTimeGroup(item.publishedAt)
                if (group != historyLastGroup) {
                    historyLastGroup = group
                    listBox.addView(groupHeader(group, topSpace = if (added == 0 && historySeen.size == 1) 4 else 14))
                }
                val timeText = formatHistoryTime(item.publishedAt)
                val metaText = if (timeText.isBlank()) item.owner else "${item.owner} · $timeText"

                lateinit var rowRef: View
                rowRef = buildVideoCardRow(
                    bvid = item.bvid,
                    title = item.title,
                    meta = metaText,
                    cover = item.cover,
                    durationSec = item.duration,
                    progressSec = item.watchProgressSec,
                    aid = item.aid,
                    cid = item.cid,
                    resumeMs = historyResumeMs(item.watchProgressSec, item.duration),
                    onLongClick = {
                        showActionSheet(
                            title = item.title,
                            actions = listOf(
                                "立即播放" to {
                                    VideoDetailActivity.open(
                                        this@ProfileSubActivity,
                                        item.bvid,
                                        item.aid,
                                        item.cid,
                                        historyResumeMs(item.watchProgressSec, item.duration)
                                    )
                                },
                                "删除此条记录" to {
                                    deleteRemoteHistoryItem(item, rowRef)
                                }
                            )
                        )
                    }
                )
                listBox.addView(rowRef, cardRowLayoutParams())
                animateRowIn(rowRef, historyRowAnimIndex++)
                added++
            }

            if (historySeen.isNotEmpty()) {
                clearBtnRef?.visibility = View.VISIBLE
            }

            historyCursorMax = nextMax
            historyCursorViewAt = nextViewAt
            historyCursorBusiness = nextBusiness.ifBlank { historyCursorBusiness }
            historyEnded = ended

            if (added > 0) {
                historyEmptyAutoAdvance = 0
            }

            if (historyEnded) {
                if (historySeen.isNotEmpty()) {
                    setHistoryFooter(loadMoreHint("已显示全部记录"))
                } else {
                    listBox.addView(
                        emptyActionCard(
                            title = "暂无观看记录",
                            subtitle = "看过的视频会同步到这里"
                        )
                    )
                }
            } else if (added == 0) {
                if (historyEmptyAutoAdvance < 3) {
                    historyEmptyAutoAdvance++
                    // 仅连续 3 页内全被过滤/去重时自动补拉，防止异常游标无限请求
                    listBox.post { loadHistoryPage(expectedGen) }
                } else if (historySeen.isEmpty()) {
                    historyEnded = true
                    listBox.addView(
                        emptyActionCard(
                            title = "暂无观看记录",
                            subtitle = "没有可展示的视频记录"
                        )
                    )
                } else {
                    val moreRow = loadMoreHint("继续加载更多").apply {
                        isClickable = true
                        setOnClickListener {
                            historyEmptyAutoAdvance = 0
                            loadHistoryPage(expectedGen)
                        }
                    }
                    setHistoryFooter(moreRow)
                }
            }
        }
    }

    private fun renderLocalHistory(bannerText: String?) {
        usingLocalHistory = true
        historyEnded = true
        historyLoading = false
        listBox.removeAllViews()
        historySeen.clear()
        historyRowAnimIndex = 0
        historyLastGroup = ""

        if (!bannerText.isNullOrBlank()) {
            listBox.addView(
                infoBanner(bannerText, clickable = true) { renderHistory() },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
        }

        val history = HistoryStore.load(this)
        if (history.isEmpty()) {
            clearBtnRef?.visibility = View.GONE
            listBox.addView(
                emptyActionCard(
                    title = "暂无观看记录",
                    subtitle = if (BiliSessionStore.isLoggedIn()) "看过的视频会出现在这里" else "登录后可同步 B 站云端历史"
                )
            )
            return
        }

        clearBtnRef?.visibility = View.VISIBLE
        history.forEachIndexed { index, item ->
            val meta = if (item.date.isBlank()) item.owner else "${item.owner} · ${item.date}"
            lateinit var rowRef: View
            rowRef = buildVideoCardRow(
                bvid = item.bvid,
                title = item.title,
                meta = meta,
                cover = item.cover,
                durationSec = 0,
                progressSec = null,
                onLongClick = {
                    showActionSheet(
                        title = item.title,
                        actions = listOf(
                            "立即播放" to { openVideo(item.bvid) },
                            "删除此条记录" to {
                                HistoryStore.remove(this, item.bvid)
                                listBox.removeView(rowRef)
                                historySeen.remove(item.bvid)
                                if (HistoryStore.load(this).isEmpty()) {
                                    renderLocalHistory(bannerText = null)
                                }
                                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                            }
                        )
                    )
                }
            )
            listBox.addView(rowRef, cardRowLayoutParams())
            animateRowIn(rowRef, index)
        }
    }

    private fun deleteRemoteHistoryItem(item: VideoItem, rowView: View) {
        val kid = item.rcmdReason
        if (kid.isBlank()) {
            Toast.makeText(this, "该记录暂不支持从云端删除", Toast.LENGTH_SHORT).show()
            return
        }
        BiliApi.deleteHistoryItem(kid) { ok, err ->
            if (isFinishing || isDestroyed) return@deleteHistoryItem
            if (!ok) {
                Toast.makeText(this, err ?: "删除失败", Toast.LENGTH_SHORT).show()
                return@deleteHistoryItem
            }
            HistoryStore.remove(this, item.bvid)
            listBox.removeView(rowView)
            historySeen.remove(item.bvid)
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            if (historySeen.isEmpty()) renderHistory()
        }
    }

    private fun confirmClearHistory() {
        val remote = !usingLocalHistory && BiliSessionStore.isLoggedIn()
        showConfirmDialog(
            title = "清空历史记录",
            message = if (remote) "将清空 B 站账号下的云端观看历史与本机记录，不可恢复。" else "将清空本机的观看记录，确定继续？",
            confirmText = "清空",
            onConfirm = {
                if (remote) {
                    BiliApi.clearRemoteHistory { ok, err ->
                        if (isFinishing || isDestroyed) return@clearRemoteHistory
                        if (!ok) {
                            Toast.makeText(this, err ?: "清空失败", Toast.LENGTH_SHORT).show()
                            return@clearRemoteHistory
                        }
                        HistoryStore.clear(this)
                        Toast.makeText(this, "已清空观看历史", Toast.LENGTH_SHORT).show()
                        renderHistory()
                    }
                } else {
                    HistoryStore.clear(this)
                    Toast.makeText(this, "已清空本机记录", Toast.LENGTH_SHORT).show()
                    renderLocalHistory(bannerText = null)
                }
            }
        )
    }

    private fun setHistoryFooter(view: View) {
        removeHistoryFooter()
        historyFooterRow = view
        listBox.addView(view)
    }

    private fun removeHistoryFooter() {
        historyFooterRow?.let { runCatching { listBox.removeView(it) } }
        historyFooterRow = null
    }

    private fun historyTimeGroup(epochSec: Long): String {
        if (epochSec <= 0L) return "最近"
        return when {
            epochSec >= todayStartSec -> "今天"
            epochSec >= todayStartSec - 86_400L -> "昨天"
            else -> "更早"
        }
    }

    private fun formatHistoryTime(epochSec: Long): String {
        if (epochSec <= 0L) return ""
        val pattern = if (epochSec >= todayStartSec - 86_400L) "HH:mm" else "MM-dd HH:mm"
        return SimpleDateFormat(pattern, Locale.CHINA).format(Date(epochSec * 1000L))
    }

    /** 竖向三点图标。 */
    private class MoreDotsIcon(context: Context, private val tint: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = tint
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val r = 1.7f * density
            val gap = 5.6f * density
            canvas.drawCircle(cx, cy - gap, r, paint)
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawCircle(cx, cy + gap, r, paint)
        }
    }

    /** 收藏夹「更多」菜单：从按钮位置动画展开。 */
    private fun showFolderMoreMenu(anchor: View) {
        val folder = currentFolder ?: return
        if (menuPopup?.isShowing == true) {
            menuPopup?.dismiss()
            return
        }
        val c = Ds.colors
        val popup = android.widget.PopupWindow().apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10).toFloat()
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@ProfileSubActivity, c.surfaceRaised, c.lineSoft, Ds.Radius.card)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun addItem(label: String, danger: Boolean = false, onClick: () -> Unit) {
            panel.addView(TextView(this).apply {
                text = label
                textSize = 14f
                typeface = Ds.body()
                setTextColor(if (danger) c.accent else c.text)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                isClickable = true
                Ds.bindPressFeedback(this, 0.96f)
                setOnClickListener {
                    popup.dismiss()
                    onClick()
                }
            }, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addItem("编辑收藏夹") { openEditFolder(folder) }
        addItem("批量管理") { enterBatchMode() }
        addItem("清除失效视频") { confirmCleanInvalid(folder) }
        addItem("删除收藏夹", danger = true) { confirmDeleteFolder(folder) }

        popup.contentView = panel
        popup.width = dp(162)
        popup.height = LinearLayout.LayoutParams.WRAP_CONTENT
        // 从按钮左下方向下展开
        popup.showAsDropDown(anchor, -dp(122), dp(4))
        menuPopup = popup
        // 入场动画：淡入 + 轻微下移
        panel.alpha = 0f
        panel.translationY = -dp(6).toFloat()
        panel.animate().alpha(1f).translationY(0f).setDuration(150L).start()
    }

    private var menuPopup: android.widget.PopupWindow? = null
    /** Activity 根视图，批量操作条挂载于此。 */
    private var rootHost: FrameLayout? = null

    /** 编辑收藏夹：复用创建页的编辑模式。 */
    private fun openEditFolder(folder: FavoriteFolder) {
        startActivityForResult(
            Intent(this, CreateFavoriteFolderActivity::class.java).apply {
                putExtra(CreateFavoriteFolderActivity.EXTRA_EDIT_ID, folder.id)
                putExtra(CreateFavoriteFolderActivity.EXTRA_EDIT_TITLE, folder.name)
                putExtra(CreateFavoriteFolderActivity.EXTRA_EDIT_PUBLIC, folder.isPublic)
            },
            REQUEST_CREATE_FOLDER
        )
    }

    /** 清除失效视频：二次确认后调用清理接口。 */
    private fun confirmCleanInvalid(folder: FavoriteFolder) {
        showConfirmDialog(
            title = "清除失效视频？",
            message = "将移除「${folder.name}」中已失效的收藏内容，此操作无法撤销。",
            confirmText = "清除"
        ) {
            BiliApi.cleanFavoriteResources(folder.id) { ok, error ->
                if (isFinishing || isDestroyed) return@cleanFavoriteResources
                if (ok) {
                    Toast.makeText(this, "已清除失效视频", Toast.LENGTH_SHORT).show()
                    // 重新拉一次元数据，让左上角统计实时更新
                    BiliApi.refreshFavoriteFolder(folder.id) { latest ->
                        if (isFinishing || isDestroyed) return@refreshFavoriteFolder
                        openFavoriteFolder(latest ?: folder)
                    }
                } else {
                    Toast.makeText(this, error ?: "清除失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 删除收藏夹：二次确认。 */
    private fun confirmDeleteFolder(folder: FavoriteFolder) {
        showConfirmDialog(
            title = "删除收藏夹？",
            message = "将删除「${folder.name}」及其中的 ${folder.count} 个内容，此操作无法撤销。",
            confirmText = "删除"
        ) {
            BiliApi.deleteFavoriteFolders(folder.id.toString()) { ok, error ->
                if (isFinishing || isDestroyed) return@deleteFavoriteFolders
                if (ok) {
                    Toast.makeText(this, "收藏夹已删除", Toast.LENGTH_SHORT).show()
                    loadFavorites(restoreScrollY = 0)
                } else {
                    Toast.makeText(this, error ?: "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 打开创建收藏夹页；成功后刷新列表。 */
    private fun openCreateFolder() {
        startActivityForResult(
            Intent(this, CreateFavoriteFolderActivity::class.java),
            REQUEST_CREATE_FOLDER
        )
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CREATE_FOLDER && resultCode == RESULT_OK && mode == MODE_FAVORITES) {
            loadFavorites(restoreScrollY = scroll.scrollY)
        }
    }

    private fun openHistorySearch() {
        startActivity(Intent(this, HistorySearchActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left_soft)
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. 我的收藏（限流并发预览 + 夹内滚动分页 + 长按移出收藏）
    // ═══════════════════════════════════════════════════════════════

    private fun loadFavorites(restoreScrollY: Int = 0) {
        val gen = ++pageSessionGen
        previewQueue.clear()
        inFavoriteFolder = false
        currentFolder = null
        folderCoverCache.clear()
        coverMetaRequested.clear()
        previewCache.clear()
        favVideos.clear()
        updatePlayAllVisibility()
        titleView.text = titleFor(MODE_FAVORITES)
        searchBtnRef?.visibility = View.VISIBLE
        addBtnRef?.visibility = View.VISIBLE
        moreBtnRef?.visibility = View.GONE
        exitBatchMode()
        scroll.scrollTo(0, 0)
        listBox.removeAllViews()

        if (!BiliSessionStore.isLoggedIn()) {
            listBox.addView(
                emptyActionCard(
                    title = "未登录",
                    subtitle = "扫码登录后可查看全部收藏夹",
                    actionLabel = "去登录"
                ) {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(EXTRA_SWITCH_TO_ACCOUNT, true)
                    )
                    finish()
                }
            )
            return
        }

        listBox.addView(loadingHint("正在加载收藏夹…"))
        BiliApi.fetchFavoriteFolders { folders, error ->
            if (isFinishing || isDestroyed || gen != pageSessionGen) {
                return@fetchFavoriteFolders
            }
            listBox.removeAllViews()
            if (error != null) {
                listBox.addView(
                    emptyActionCard(
                        title = "加载失败",
                        subtitle = error,
                        actionLabel = "重试"
                    ) { loadFavorites(restoreScrollY) }
                )
                return@fetchFavoriteFolders
            }
            if (folders.isEmpty()) {
                listBox.addView(emptyActionCard("还没有收藏夹", "在视频详情页点收藏即可创建并管理"))
                return@fetchFavoriteFolders
            }

            if (LayoutMode.isWideScreen(this)) {
                var idx = 0
                while (idx < folders.size) {
                    val gridRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    val left = buildFavoriteFolderCard(folders[idx], gen).apply {
                        background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
                    gridRow.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(14)
                    })
                    if (idx + 1 < folders.size) {
                        val right = buildFavoriteFolderCard(folders[idx + 1], gen).apply {
                            background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
                            setPadding(dp(14), dp(12), dp(14), dp(12))
                        }
                        gridRow.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    } else {
                        gridRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
                    }
                    listBox.addView(gridRow, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(12) })
                    animateRowIn(gridRow, idx / 2)
                    idx += 2
                }
            } else {
                folders.forEachIndexed { index, folder ->
                    val card = buildFavoriteFolderCard(folder, gen)
                    listBox.addView(
                        card,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { if (index > 0) topMargin = dp(4) }
                    )
                    animateRowIn(card, index)
                }
            }
            if (restoreScrollY > 0) {
                scroll.post { scroll.scrollTo(0, restoreScrollY) }
            }
        }
    }

    private fun enqueueFolderPreview(folder: FavoriteFolder, expectedGen: Int, onReady: (List<VideoItem>) -> Unit) {
        previewCache[folder.id]?.let {
            onReady(it)
            return
        }
        previewQueue.addLast(folder to { list ->
            if (expectedGen == pageSessionGen) onReady(list)
        })
        pumpPreviewQueue(expectedGen)
    }

    private fun pumpPreviewQueue(expectedGen: Int) {
        while (previewInFlight < maxPreviewConcurrent && previewQueue.isNotEmpty()) {
            if (isFinishing || isDestroyed || expectedGen != pageSessionGen) {
                previewQueue.clear()
                return
            }
            val (folder, callback) = previewQueue.removeFirst()
            val cached = previewCache[folder.id]
            if (cached != null) {
                callback(cached)
            } else {
                previewInFlight++
                BiliApi.fetchFavoriteVideos(folder.id, page = 1, pageSize = 1) { videos, error, _ ->
                    previewInFlight = (previewInFlight - 1).coerceAtLeast(0)
                    if (isFinishing || isDestroyed || expectedGen != pageSessionGen) return@fetchFavoriteVideos
                    if (error == null) {
                        val take = videos.take(1)
                        previewCache[folder.id] = take
                        callback(take)
                    } else {
                        callback(emptyList())
                    }
                    pumpPreviewQueue(expectedGen)
                }
            }
        }
    }

    private fun buildFavoriteFolderCard(folder: FavoriteFolder, expectedGen: Int): View {
        val c = Ds.colors
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true
            Ds.bindPressFeedback(this, 0.98f)
            setOnClickListener { openFavoriteFolder(folder) }
            setOnLongClickListener {
                showFolderActions(folder)
                true
            }
        }

        // 左侧叠层封面（单容器自绘，内部只挂一个 ImageView）
        val cover = FavoriteStackCoverView(this)
        row.addView(cover, LinearLayout.LayoutParams(dp(116), dp(80)).apply {
            rightMargin = dp(12)
        })

        // 中间文字
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textColumn.addView(TextView(this).apply {
            text = folder.name
            textSize = 14.5f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            // emoji（如 ❤️）字形会超出汉字字框，关闭 fontPadding 后顶部会被裁掉；
            // 这里保留 fontPadding 并给足行距，保证 emoji 完整显示。
            includeFontPadding = true
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(1), 0, dp(1))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val owner = folder.owner.takeIf { it.isNotBlank() }
        if (owner != null) {
            textColumn.addView(TextView(this).apply {
                text = "创建者：$owner"
                textSize = 11.5f
                typeface = Ds.body()
                setTextColor(c.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
        }
        textColumn.addView(TextView(this).apply {
            text = if (folder.isPublic) "收藏夹 · 公开" else "收藏夹 · 私密"
            textSize = 11.5f
            typeface = Ds.body()
            setTextColor(c.muted)
            maxLines = 1
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (owner != null) dp(3) else dp(6)
        })
        row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 封面来源优先级：folder.cover > 缓存 > 首个视频封面
        val knownCover = folder.cover.takeIf { it.isNotBlank() } ?: folderCoverCache[folder.id]
        if (!knownCover.isNullOrBlank()) {
            cover.bind(folder.count, knownCover)
            return row
        }
        cover.bind(folder.count, null)
        // 自定义封面查询独立发起，不依赖首视频预览回调（它受并发限制且可能被代际丢弃）
        if (!coverMetaRequested.contains(folder.id)) {
            coverMetaRequested.add(folder.id)
            BiliApi.fetchFavoriteFolderInfo(folder.id) { info, error ->
                if (isFinishing || isDestroyed || expectedGen != pageSessionGen) {
                    android.util.Log.w("YuiFavCover", "gen 丢弃 ${folder.id} $expectedGen != $pageSessionGen")
                    return@fetchFavoriteFolderInfo
                }
                val custom = info?.cover.orEmpty()
                if (custom.isNotBlank()) {
                    folderCoverCache[folder.id] = custom
                    cover.bind(folder.count, custom, onFailed = {
                        // 自定义封面加载失败，清掉缓存并回退到首视频封面
                        folderCoverCache.remove(folder.id)
                        if (folder.count > 0L) {
                            enqueueFolderPreview(folder, expectedGen) { take ->
                                if (isFinishing || isDestroyed || expectedGen != pageSessionGen) return@enqueueFolderPreview
                                cover.bind(folder.count, take.firstOrNull()?.cover)
                            }
                        }
                    })
                } else {
                    // 元数据没拿到封面（无自定义 / 查询失败）时，回退到首视频封面
                    if (error != null) {
                        android.util.Log.w("YuiFavCover", "folder ${folder.id} 封面查询失败：$error")
                    }
                    if (folder.count > 0L && folderCoverCache[folder.id].isNullOrBlank()) {
                        enqueueFolderPreview(folder, expectedGen) { take ->
                            if (isFinishing || isDestroyed || expectedGen != pageSessionGen) return@enqueueFolderPreview
                            if (folderCoverCache[folder.id].isNullOrBlank()) {
                                cover.bind(folder.count, take.firstOrNull()?.cover)
                            }
                        }
                    }
                }
            }
        } else {
            // 已查过元数据但没有自定义封面，用首视频封面回退
            if (folder.count > 0L) {
                enqueueFolderPreview(folder, expectedGen) { take ->
                    if (isFinishing || isDestroyed || expectedGen != pageSessionGen) return@enqueueFolderPreview
                    if (folderCoverCache[folder.id].isNullOrBlank()) {
                        cover.bind(folder.count, take.firstOrNull()?.cover)
                    }
                }
            }
        }
        return row
    }

    /** 收藏夹的三点菜单。 */
    private fun showFolderActions(folder: FavoriteFolder) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "打开收藏夹" to { openFavoriteFolder(folder) }
        if (folder.count > 0L) {
            actions += "播放全部" to { playFolderDirectly(folder) }
        }
        showActionSheet(title = folder.name, actions = actions)
    }

    /** 不进入详情页直接播放整个收藏夹：拉全分页后交给播放页。 */
    private fun playFolderDirectly(folder: FavoriteFolder) {
        if (folder.count <= 0L) return
        val collected = ArrayList<VideoItem>()
        val seen = HashSet<String>()
        var page = 1
        fun loadNext() {
            BiliApi.fetchFavoriteVideos(folder.id, page = page, pageSize = 20) { videos, error, ended ->
                if (isFinishing || isDestroyed) return@fetchFavoriteVideos
                if (error != null) {
                    if (collected.isNotEmpty()) {
                        launchFavoritePlayback(folder, collected)
                    } else {
                        android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@fetchFavoriteVideos
                }
                videos.forEach { if (seen.add(it.bvid)) collected.add(it) }
                if (ended || videos.isEmpty()) {
                    if (collected.isEmpty()) {
                        android.widget.Toast.makeText(this, "收藏夹为空", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        launchFavoritePlayback(folder, collected)
                    }
                } else {
                    page++
                    loadNext()
                }
            }
        }
        loadNext()
    }

    /**
     * 叠层收藏夹封面：单容器自绘背后两层错位卡片 + 前景底板 + 空态小电视，
     * 右下角自适应宽度数量角标。内部只挂 1 个 ImageView，降低滚动绘制开销。
     */
    private class FavoriteStackCoverView(context: Context) : FrameLayout(context) {
        private val density = resources.displayMetrics.density
        private val coverW = (116f * density).toInt()
        private val coverH = (72f * density).toInt()
        private val topOffset = (7f * density).toInt()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val tvPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.7f * density
        }
        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Ds.body(bold = true)
            textSize = 11f * resources.displayMetrics.scaledDensity
            color = Color.WHITE
        }
        private val badgeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        private val rect = RectF()
        private val path = Path()

        private val coverImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            Ds.clipRounded(this, Ds.Radius.chip)
            visibility = View.GONE
        }

        private var countText = "0"
        private var showEmptyTv = true

        init {
            setWillNotDraw(false)
            addView(coverImage, LayoutParams(coverW, coverH).apply { topMargin = topOffset })
        }

        fun bind(count: Long, coverUrl: String?, onFailed: (() -> Unit)? = null) {
            countText = count.toString()
            if (coverUrl.isNullOrBlank()) {
                showEmptyTv = true
                CoverLoader.clear(coverImage)
                coverImage.visibility = View.GONE
            } else {
                showEmptyTv = false
                coverImage.visibility = View.VISIBLE
                // 由 CoverLoader 自行决定缩略图后缀，避免双 @ 或拼进 query
                CoverLoader.load(
                    coverImage, coverUrl,
                    onFailure = { failedUrl ->
                        android.util.Log.w("YuiFavCover", "封面加载失败：$failedUrl")
                        showEmptyTv = true
                        coverImage.visibility = View.GONE
                        invalidate()
                        onFailed?.invoke()
                    }
                )
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val c = Ds.colors
            val w = coverW.toFloat()
            val topY = topOffset.toFloat()
            val fullBottom = topY + coverH

            // 最底层
            val inset2 = 12f * density
            rect.set(inset2, 0f, w - inset2, fullBottom - 8f * density)
            fillPaint.color = if (c.isDark) 0xFF1B1E24.toInt() else 0xFFECEEF3.toInt()
            canvas.drawRoundRect(rect, 8f * density, 8f * density, fillPaint)
            if (c.isDark) {
                strokePaint.strokeWidth = 0.5f * density
                strokePaint.color = 0xFF262A33.toInt()
                canvas.drawRoundRect(rect, 8f * density, 8f * density, strokePaint)
            }

            // 中间层
            val inset1 = 6f * density
            rect.set(inset1, 4f * density, w - inset1, fullBottom - 4f * density)
            fillPaint.color = if (c.isDark) 0xFF252932.toInt() else 0xFFDFE2EA.toInt()
            canvas.drawRoundRect(rect, 9f * density, 9f * density, fillPaint)
            strokePaint.strokeWidth = 0.75f * density
            strokePaint.color = if (c.isDark) 0xFF323744.toInt() else 0xFFD4D8E2.toInt()
            canvas.drawRoundRect(rect, 9f * density, 9f * density, strokePaint)

            // 前景底板（16:10）
            rect.set(0f, topY, w, fullBottom)
            fillPaint.color = c.cover
            val frontR = 10f * density
            canvas.drawRoundRect(rect, frontR, frontR, fillPaint)
            strokePaint.strokeWidth = if (c.isDark) 0.75f * density else 0.5f * density
            strokePaint.color = if (c.isDark) 0xFF343946.toInt() else c.lineSoft
            canvas.drawRoundRect(rect, frontR, frontR, strokePaint)

            if (showEmptyTv) drawEmptyTv(canvas, w, topY, c)
        }

        private fun drawEmptyTv(canvas: Canvas, w: Float, topY: Float, c: Ds.Palette) {
            val tvW = 36f * density
            val tvH = 25f * density
            val cx = w / 2f
            val cy = topY + coverH / 2f - 1f * density
            val left = cx - tvW / 2f
            val top = cy - tvH / 2f
            val color = if (c.isDark) 0xFF555B68.toInt() else 0xFFB2B7C2.toInt()
            tvPaint.color = color
            eyePaint.color = color
            rect.set(left, top, left + tvW, top + tvH)
            canvas.drawRoundRect(rect, 6f * density, 6f * density, tvPaint)
            canvas.drawLine(cx - 7.2f * density, top, cx - 11.5f * density, top - 5f * density, tvPaint)
            canvas.drawLine(cx + 7.2f * density, top, cx + 11.5f * density, top - 5f * density, tvPaint)
            val eyeY = top + 11.5f * density
            canvas.drawCircle(cx - 6.5f * density, eyeY, 1.6f * density, eyePaint)
            canvas.drawCircle(cx + 6.5f * density, eyeY, 1.6f * density, eyePaint)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            // 角标：自适应宽度
            val bh = 20f * density
            val padH = 6f * density
            val iconW = 8.5f * density
            val gap = 3.5f * density
            val textW = badgeTextPaint.measureText(countText)
            val bw = (padH * 2f + iconW + gap + textW).coerceAtLeast(36f * density)

            val right = coverW - 6f * density
            val bottom = topOffset + coverH - 6f * density
            rect.set(right - bw, bottom - bh, right, bottom)
            fillPaint.color = 0xB8000000.toInt()
            canvas.drawRoundRect(rect, bh / 2f, bh / 2f, fillPaint)
            strokePaint.strokeWidth = 0.5f * density
            strokePaint.color = 0x24FFFFFF
            canvas.drawRoundRect(rect, bh / 2f, bh / 2f, strokePaint)

            val ix = rect.left + padH
            val iy = rect.centerY() - iconW / 2f
            val boxS = 5.4f * density
            val off = 2.6f * density
            val frontTop = iy + off
            canvas.drawRoundRect(
                ix, frontTop, ix + boxS, frontTop + boxS,
                1.1f * density, 1.1f * density, badgeIconPaint
            )
            path.rewind()
            path.moveTo(ix + off, iy + off * 0.45f)
            path.lineTo(ix + off, iy)
            path.lineTo(ix + off + boxS, iy)
            path.lineTo(ix + off + boxS, iy + boxS)
            path.lineTo(ix + boxS + off * 0.55f, iy + boxS)
            canvas.drawPath(path, badgeIconPaint)

            val baseline = rect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(countText, ix + iconW + gap, baseline, badgeTextPaint)
        }
    }

    private fun openFavoriteFolder(folder: FavoriteFolder) {
        val gen = ++pageSessionGen
        previewQueue.clear()
        favListScrollY = scroll.scrollY
        inFavoriteFolder = true
        currentFolder = folder
        // 进入详情时补一次元数据，确保封面与计数最新
        BiliApi.fetchFavoriteFolderInfo(folder.id) { info, _ ->
            if (isFinishing || isDestroyed || info == null) return@fetchFavoriteFolderInfo
            if (info.cover.isNotBlank()) folderCoverCache[info.id] = info.cover
        }
        titleView.text = folder.name
        searchBtnRef?.visibility = View.GONE
        addBtnRef?.visibility = View.GONE
        moreBtnRef?.visibility = View.VISIBLE
        scroll.scrollTo(0, 0)
        favVideos.clear()
        updatePlayAllVisibility()

        favPage = 1
        favEnded = false
        favLoading = false
        favFooterRow = null
        favSeen.clear()
        favRowAnimIndex = 0
        favVideos.clear()
        updatePlayAllVisibility()
        listBox.removeAllViews()
        listBox.addView(loadingHint("正在加载收藏内容…"))
        loadFavoriteFolderPage(expectedGen = gen)
    }

    private fun loadFavoriteFolderPage(expectedGen: Int = pageSessionGen) {
        val folder = currentFolder ?: return
        if (!inFavoriteFolder || favLoading || favEnded || expectedGen != pageSessionGen) return
        favLoading = true
        val firstPage = favPage == 1 && favSeen.isEmpty()
        if (!firstPage) {
            setFavFooter(loadMoreHint("正在加载…"))
        }

        BiliApi.fetchFavoriteVideos(folder.id, page = favPage, pageSize = 20) { videos, error, ended ->
            if (isFinishing || isDestroyed || expectedGen != pageSessionGen) return@fetchFavoriteVideos
            favLoading = false
            removeFavFooter()
            if (firstPage) listBox.removeAllViews()

            if (error != null) {
                if (firstPage) {
                    listBox.addView(
                        emptyActionCard(
                            title = "加载失败",
                            subtitle = error,
                            actionLabel = "重试"
                        ) { openFavoriteFolder(folder) }
                    )
                } else {
                    val retry = loadMoreHint("加载失败，点击重试").apply {
                        isClickable = true
                        setOnClickListener { loadFavoriteFolderPage(expectedGen) }
                    }
                    setFavFooter(retry)
                }
                return@fetchFavoriteVideos
            }

            if (firstPage) {
                listBox.addView(
                    TextView(this).apply {
                        text = "${if (folder.count > 0L) folder.count else videos.size.toLong()} 个视频"
                        textSize = 12f
                        typeface = Ds.body(true)
                        setTextColor(COLOR_MUTED)
                        setPadding(dp(2), 0, 0, dp(8))
                    }
                )
            }

            if (firstPage && videos.isEmpty()) {
                favEnded = true
                listBox.addView(emptyActionCard("收藏夹为空", "还没有收藏任何视频"))
                return@fetchFavoriteVideos
            }

            var added = 0
            val newlyWrapped = ArrayList<View>()
            videos.forEach { video ->
                if (!favSeen.add(video.bvid)) return@forEach
                favVideos.add(video)
                val dateText = formatShortDate(video.publishedAt)
                val meta = if (dateText.isBlank()) video.owner else "${video.owner} · $dateText"
                lateinit var rowRef: View
                rowRef = buildVideoCardRow(
                    bvid = video.bvid,
                    title = video.title,
                    meta = meta,
                    cover = video.cover,
                    durationSec = video.duration,
                    progressSec = null,
                    onLongClick = {
                        showActionSheet(
                            title = video.title,
                            actions = listOf(
                                "立即播放" to { openVideo(video.bvid) },
                                "从「${folder.name}」移出" to {
                                    removeFromFavoriteFolder(folder, video, rowRef)
                                }
                            )
                        )
                    }
                )
                newlyWrapped.add(wrapFavoriteRow(video, rowRef, folder))
                added++
            }
            if (LayoutMode.isWideScreen(this)) {
                var i = 0
                while (i < newlyWrapped.size) {
                    val gridRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    gridRow.addView(newlyWrapped[i], LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(12)
                    })
                    if (i + 1 < newlyWrapped.size) {
                        gridRow.addView(newlyWrapped[i + 1], LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    } else {
                        gridRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
                    }
                    listBox.addView(gridRow, cardRowLayoutParams())
                    animateRowIn(gridRow, favRowAnimIndex++)
                    i += 2
                }
            } else {
                newlyWrapped.forEach { wrapped ->
                    listBox.addView(wrapped, cardRowLayoutParams())
                    animateRowIn(wrapped, favRowAnimIndex++)
                }
            }

            favEnded = ended || videos.isEmpty()
            if (!favEnded) favPage++
            updatePlayAllVisibility()
            if (favEnded && favSeen.isNotEmpty()) {
                setFavFooter(loadMoreHint("已显示全部收藏"))
            } else if (!favEnded && added == 0) {
                val moreRow = loadMoreHint("继续加载更多").apply {
                    isClickable = true
                    setOnClickListener { loadFavoriteFolderPage(expectedGen) }
                }
                setFavFooter(moreRow)
            }
        }
    }

    /**
     * 收藏夹视频行包装：批量管理时左侧显示勾选框，点击整行切换选中；
     * 普通模式直接返回原行。
     */
    private fun wrapFavoriteRow(video: VideoItem, row: View, folder: FavoriteFolder): View {
        if (!batchMode) return row
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val check = BatchCheckView(this).apply {
            isChecked = selectedBvids.contains(video.bvid)
        }
        favRowChecks[video.bvid] = check
        host.addView(check, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            rightMargin = dp(4)
        })
        host.addView(row, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // 批量模式下整行点击 = 切换选中，不进入播放
        row.setOnClickListener {
            check.isChecked = !check.isChecked
            if (check.isChecked) selectedBvids.add(video.bvid) else selectedBvids.remove(video.bvid)
            updateBatchBar()
        }
        row.setOnLongClickListener(null)
        row.isLongClickable = false
        return host
    }

    /** 进入批量管理模式：重建当前收藏夹列表。 */
    private fun enterBatchMode() {
        if (batchMode) return
        val folder = currentFolder ?: return
        batchMode = true
        selectedBvids.clear()
        favRowChecks.clear()
        // 批量管理要能全选整个收藏夹，先把未加载的分页拉完
        if (!favEnded && !favLoading) {
            listBox.removeAllViews()
            listBox.addView(loadingHint("正在加载全部收藏内容…"))
            fetchAllFavoriteVideos(folder) { all ->
                if (isFinishing || isDestroyed) return@fetchAllFavoriteVideos
                if (!batchMode) return@fetchAllFavoriteVideos
                favVideos.clear()
                favVideos.addAll(all)
                favSeen.clear()
                all.forEach { favSeen.add(it.bvid) }
                rebuildFavoriteFolderList()
                showBatchBar()
            }
            return
        }
        rebuildFavoriteFolderList()
        showBatchBar()
    }

    /** 退出批量管理。 */
    private fun exitBatchMode() {
        if (!batchMode) return
        batchMode = false
        selectedBvids.clear()
        favRowChecks.clear()
        hideBatchBar()
        rebuildFavoriteFolderList()
    }

    /** 用已加载的 favVideos 重建列表（不重新请求）。 */
    private fun rebuildFavoriteFolderList() {
        val folder = currentFolder ?: return
        listBox.removeAllViews()
        if (favVideos.isEmpty()) {
            listBox.addView(emptyActionCard("收藏夹为空", "还没有收藏任何视频"))
            return
        }
        listBox.addView(TextView(this).apply {
            // 优先用收藏夹真实总数，避免只显示已加载条数
            val total = if (folder.count > 0L) folder.count else favVideos.size.toLong()
            text = "$total 个视频"
            textSize = 12f
            typeface = Ds.body(true)
            setTextColor(COLOR_MUTED)
            setPadding(dp(2), 0, 0, dp(8))
        })
        favRowChecks.clear()
        favVideos.forEach { video ->
            val dateText = formatShortDate(video.publishedAt)
            val meta = if (dateText.isBlank()) video.owner else "${video.owner} · $dateText"
            lateinit var rowRef: View
            rowRef = buildVideoCardRow(
                bvid = video.bvid,
                title = video.title,
                meta = meta,
                cover = video.cover,
                durationSec = video.duration,
                progressSec = null,
                onLongClick = {
                    showActionSheet(
                        title = video.title,
                        actions = listOf(
                            "立即播放" to { openVideo(video.bvid) },
                            "从「${folder.name}」移出" to {
                                removeFromFavoriteFolder(folder, video, rowRef)
                            }
                        )
                    )
                }
            )
            listBox.addView(wrapFavoriteRow(video, rowRef, folder), cardRowLayoutParams())
        }
        updatePlayAllVisibility()
    }

    /** 批量管理用的圆角勾选框，与设计系统统一（替代系统 CheckBox）。 */
    private class BatchCheckView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.9f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        private val rect = RectF()
        private val path = Path()

        var isChecked: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                animate().cancel()
                animate().scaleX(1.12f).scaleY(1.12f).setDuration(90L).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                }.start()
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = minOf(width, height).toFloat() * 0.62f
            val left = (width - size) / 2f
            val top = (height - size) / 2f
            val radius = size * 0.28f
            rect.set(left, top, left + size, top + size)

            if (isChecked) {
                fill.color = Ds.colors.accent
                canvas.drawRoundRect(rect, radius, radius, fill)
                // 对勾
                path.rewind()
                path.moveTo(left + size * 0.24f, top + size * 0.52f)
                path.lineTo(left + size * 0.43f, top + size * 0.71f)
                path.lineTo(left + size * 0.76f, top + size * 0.32f)
                canvas.drawPath(path, tick)
            } else {
                stroke.color = Ds.colors.lineSoft
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }
        }
    }

    /** 底部批量操作条：悬浮圆角卡片，左全选、右删除。 */
    private fun showBatchBar() {
        hideBatchBar()
        val c = Ds.colors
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(
                this@ProfileSubActivity, c.surfaceRaised, c.lineSoft, Ds.Radius.card
            )
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(12).toFloat()
        }

        val selectAll = TextView(this).apply {
            text = "全选"
            textSize = 13.5f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            setTextColor(c.text)
            includeFontPadding = false
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener {
                val allSelected = favVideos.isNotEmpty() && selectedBvids.size == favVideos.size
                selectedBvids.clear()
                if (!allSelected) favVideos.forEach { selectedBvids.add(it.bvid) }
                favRowChecks.forEach { (bvid, cb) -> cb.isChecked = selectedBvids.contains(bvid) }
                updateBatchBar()
            }
        }
        batchSelectAllBtn = selectAll
        bar.addView(selectAll, LinearLayout.LayoutParams(dp(96), dp(42)))

        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val deleteBtn = TextView(this).apply {
            text = "删除"
            textSize = 13.5f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ds.pill(this@ProfileSubActivity, c.danger)
            includeFontPadding = false
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { confirmBatchDelete() }
        }
        batchDeleteBtn = deleteBtn
        bar.addView(deleteBtn, LinearLayout.LayoutParams(dp(104), dp(42)))

        val host = rootHost ?: return
        host.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply {
            leftMargin = dp(Ds.Space.md)
            rightMargin = dp(Ds.Space.md)
            bottomMargin = dp(Ds.Space.md)
        })
        batchBar = bar
        bar.translationY = dp(72).toFloat()
        bar.alpha = 0f
        bar.animate().translationY(0f).alpha(1f).setDuration(200L).start()
        updateBatchBar()
    }

    private fun hideBatchBar() {
        batchBar?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        batchBar = null
        batchSelectAllBtn = null
        batchDeleteBtn = null
    }

    private fun updateBatchBar() {
        val selectAll = batchSelectAllBtn ?: return
        val deleteBtn = batchDeleteBtn ?: return
        val allSelected = favVideos.isNotEmpty() && selectedBvids.size == favVideos.size
        val nextSelectText = if (allSelected) "取消全选" else "全选"
        if (selectAll.text.toString() != nextSelectText) {
            selectAll.animate().cancel()
            selectAll.animate().alpha(0.35f).setDuration(70L).withEndAction {
                selectAll.text = nextSelectText
                selectAll.animate().alpha(1f).setDuration(110L).start()
            }.start()
        }
        val count = selectedBvids.size
        deleteBtn.text = if (count > 0) "删除($count)" else "删除"
        deleteBtn.isEnabled = count > 0
        deleteBtn.animate().cancel()
        deleteBtn.animate().alpha(if (count > 0) 1f else 0.45f).setDuration(120L).start()
    }

    /** 批量删除：二次确认。 */
    private fun confirmBatchDelete() {
        val folder = currentFolder ?: return
        if (selectedBvids.isEmpty()) return
        val targets = favVideos.filter { selectedBvids.contains(it.bvid) && it.aid > 0L }
        if (targets.isEmpty()) {
            Toast.makeText(this, "所选内容无法删除", Toast.LENGTH_SHORT).show()
            return
        }
        showConfirmDialog(
            title = "删除 ${targets.size} 个内容？",
            message = "将从「${folder.name}」中移出所选内容，此操作无法撤销。",
            confirmText = "删除"
        ) {
            val resources = targets.joinToString(",") { "${it.aid}:2" }
            BiliApi.batchDeleteFavoriteResources(folder.id, resources) { ok, error ->
                if (isFinishing || isDestroyed) return@batchDeleteFavoriteResources
                if (ok) {
                    Toast.makeText(this, "已删除 ${targets.size} 个内容", Toast.LENGTH_SHORT).show()
                    exitBatchMode()
                    BiliApi.refreshFavoriteFolder(folder.id) { latest ->
                        if (isFinishing || isDestroyed) return@refreshFavoriteFolder
                        openFavoriteFolder(latest ?: folder)
                    }
                } else {
                    Toast.makeText(this, error ?: "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun removeFromFavoriteFolder(folder: FavoriteFolder, video: VideoItem, rowView: View) {
        if (video.aid <= 0L) {
            Toast.makeText(this, "无法移出该条目", Toast.LENGTH_SHORT).show()
            return
        }
        BiliApi.favoriteVideo(video.aid, addIds = "", delIds = folder.id.toString()) { ok, err ->
            if (isFinishing || isDestroyed) return@favoriteVideo
            if (!ok) {
                Toast.makeText(this, err ?: "移出失败", Toast.LENGTH_SHORT).show()
                return@favoriteVideo
            }
            previewCache.remove(folder.id)
            listBox.removeView(rowView)
            favSeen.remove(video.bvid)
            currentFolder = folder.copy(count = (folder.count - 1L).coerceAtLeast(0L))
            Toast.makeText(this, "已移出收藏夹", Toast.LENGTH_SHORT).show()
            if (favSeen.isEmpty() && favEnded) {
                openFavoriteFolder(currentFolder!!)
            }
        }
    }

    private fun setFavFooter(view: View) {
        removeFavFooter()
        favFooterRow = view
        listBox.addView(view)
    }

    private fun removeFavFooter() {
        favFooterRow?.let { runCatching { listBox.removeView(it) } }
        favFooterRow = null
    }

    private fun handleBack() {
        // 批量管理优先退出，不退到收藏夹列表
        if (batchMode) {
            exitBatchMode()
            return
        }
        if (mode == MODE_FAVORITES && inFavoriteFolder) {
            loadFavorites(restoreScrollY = favListScrollY)
            return
        }
        closeWithAnim()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (batchMode) {
            exitBatchMode()
            return
        }
        if (mode == MODE_FAVORITES && inFavoriteFolder) {
            loadFavorites(restoreScrollY = favListScrollY)
            return
        }
        closeWithAnim()
    }

    private fun closeWithAnim() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left_soft, R.anim.slide_out_right)
    }

    private fun openFavoriteSearch() {
        startActivity(Intent(this, FavoriteSearchActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left_soft)
    }

    // ── Downloads (fallback) ──────────────────────────────────────

    private fun renderDownloads() {
        listBox.removeAllViews()
        listBox.addView(emptyActionCard("暂无下载任务", "请前往下载管理查看"))
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. 赞助开发（简洁现代扫码卡片，无啰嗦文案）
    // ═══════════════════════════════════════════════════════════════

    private fun renderSponsor() {
        listBox.removeAllViews()
        val qrFrame = FrameLayout(this).apply {
            background = Ds.card(this@ProfileSubActivity, Color.WHITE, Ds.colors.lineSoft, Ds.Radius.control)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val qrImage = ImageView(this).apply {
            setImageResource(R.drawable.weixin_sponsor_qr)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "微信收款码"
            isClickable = true
            isLongClickable = true
            setOnLongClickListener {
                downloadSponsorQr()
                true
            }
        }
        qrFrame.addView(qrImage, FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER))

        val saveBtn = TextView(this).apply {
            text = "保存收款码"
            textSize = 14.5f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ON_ROSE)
            background = Ds.pill(this@ProfileSubActivity, COLOR_ROSE)
            setPadding(dp(28), dp(12), dp(28), dp(12))
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener { downloadSponsorQr() }
        }

        if (LayoutMode.isWideScreen(this)) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
                setPadding(dp(36), dp(32), dp(36), dp(32))
            }
            val leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            leftCol.addView(TextView(this).apply {
                text = "请开发者喝杯咖啡"
                textSize = 22f
                typeface = Ds.display(bold = true)
                setTextColor(COLOR_INK)
            })
            leftCol.addView(TextView(this).apply {
                text = "YuiBili 由个人独立维护，无广告、无商业化。\n您的支持将用于持续优化体验与功能迭代。"
                textSize = 13.5f
                typeface = Ds.body()
                setTextColor(COLOR_MUTED)
                setLineSpacing(dp(5).toFloat(), 1f)
                setPadding(0, dp(10), 0, dp(22))
            })
            leftCol.addView(TextView(this).apply {
                text = "原作者 xiaoyuili · 二改作者 苏青安（suqingan）"
                textSize = 12f
                typeface = Ds.body()
                setTextColor(Ds.colors.subtle)
                setPadding(0, 0, 0, dp(16))
            })
            leftCol.addView(saveBtn, LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT))
            leftCol.addView(TextView(this).apply {
                text = "长按右侧赞赏码或点击按钮即可保存"
                textSize = 12f
                setTextColor(Ds.colors.subtle)
                setPadding(0, dp(12), 0, 0)
            })
            qrImage.layoutParams = FrameLayout.LayoutParams(dp(216), dp(216), Gravity.CENTER)
            card.addView(leftCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(32)
            })
            card.addView(qrFrame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            listBox.addView(LayoutMode.wrapCenteredForTablet(this, card, maxDp = 760), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) })
            animateRowIn(card, 0)
            return
        }

        // 手机端：左侧说明与署名，右侧赞赏码
        qrImage.layoutParams = FrameLayout.LayoutParams(dp(132), dp(132), Gravity.CENTER)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(18), dp(20), dp(18), dp(20))
        }
        val leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        leftCol.addView(TextView(this).apply {
            text = "请开发者喝杯咖啡"
            textSize = 16f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
        })
        leftCol.addView(TextView(this).apply {
            text = "YuiBili 由个人独立维护，无广告、无商业化。\n您的支持将用于持续优化体验与功能迭代。"
            textSize = 12f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(10))
        })
        leftCol.addView(TextView(this).apply {
            text = "原作者 xiaoyuili\n二改作者 苏青安（suqingan）"
            textSize = 11.5f
            typeface = Ds.body()
            setTextColor(Ds.colors.subtle)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        })
        leftCol.addView(saveBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(leftCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(10)
        })

        val qrCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        qrCol.addView(qrFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        qrCol.addView(TextView(this).apply {
            text = "苏青安的微信赞赏码"
            textSize = 10.5f
            typeface = Ds.body()
            setTextColor(Ds.colors.subtle)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(qrCol, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        listBox.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        animateRowIn(card, 0)
    }

    private fun downloadSponsorQr() {
        val bitmap = try {
            BitmapFactory.decodeResource(resources, R.drawable.weixin_sponsor_qr)
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) {
            Toast.makeText(this, "收款码加载失败", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "YuiBili微信收款码_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YuiBili")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    Toast.makeText(this, "已保存到相册（Pictures/YuiBili）", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    contentResolver.delete(uri, null, null)
                    Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    0x51A2
                )
                Toast.makeText(this, "请允许存储权限后再次点击保存", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "YuiBili"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                sendBroadcast(
                    Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(file))
                )
                Toast.makeText(this, "已保存到相册（Pictures/YuiBili）", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. 关于软件（克制卡片化信息，点击复制版本，弹窗查看免责说明）
    // ═══════════════════════════════════════════════════════════════

    private fun renderAbout() {
        listBox.removeAllViews()
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName.orEmpty()
        @Suppress("DEPRECATION")
        val versionCode = packageInfo.versionCode
        val fullVersion = "v$versionName ($versionCode)"

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }
        hero.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo_round)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "YuiBili"
            Ds.clipRounded(this, Ds.Radius.card)
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        hero.addView(TextView(this).apply {
            text = "YuiBili"
            textSize = 22f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        hero.addView(TextView(this).apply {
            text = "轻量、克制的第三方哔哩哔哩客户端"
            textSize = 13f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        hero.addView(TextView(this).apply {
            text = fullVersion
            textSize = 12f
            typeface = Ds.body(true)
            setTextColor(COLOR_ROSE)
            background = Ds.pill(this@ProfileSubActivity, COLOR_ROSE_SOFT)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            includeFontPadding = false
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("YuiBili Version", "YuiBili $fullVersion"))
                Toast.makeText(this@ProfileSubActivity, "已复制版本信息", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        listBox.addView(hero, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        animateRowIn(hero, 0)

        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(0, dp(4), 0, dp(4))
        }
        fun infoRow(label: String, value: String, clickable: Boolean = false, onClick: (() -> Unit)? = null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                if (clickable && onClick != null) {
                    isClickable = true
                    isFocusable = true
                    Ds.bindPressFeedback(this, 0.985f)
                    setOnClickListener { onClick() }
                }
                addView(TextView(this@ProfileSubActivity).apply {
                    text = label
                    textSize = 14.5f
                    typeface = Ds.body(true)
                    setTextColor(COLOR_INK)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@ProfileSubActivity).apply {
                    text = value
                    textSize = 13f
                    typeface = Ds.body()
                    setTextColor(COLOR_MUTED)
                })
                if (clickable) {
                    addView(
                        Ds.chevronRightIcon(this@ProfileSubActivity, Ds.colors.subtle, 1.7f),
                        LinearLayout.LayoutParams(dp(14), dp(14)).apply { leftMargin = dp(6) }
                    )
                }
            }
            infoCard.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ))
        }
        fun infoDivider() {
            infoCard.addView(
                View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)).apply {
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                }
            )
        }

        infoRow("版本", fullVersion, clickable = true) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("YuiBili Version", "YuiBili $fullVersion"))
            Toast.makeText(this, "已复制版本信息", Toast.LENGTH_SHORT).show()
        }
        infoDivider()
        infoRow("检查更新", "点击检查", clickable = true) { checkUpdateManually() }
        infoDivider()
        infoRow("作者", "xiaoyuili（二改作者 suqingan）")
        infoDivider()
        infoRow("隐私与存储", "凭证仅存本机")
        infoDivider()
        infoRow("免责声明", "查看", clickable = true) { showDisclaimerDialog() }

        // 更新检查开关：独立卡片，默认开启；关闭后启动时不再自动请求
        val updateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(0, dp(4), 0, dp(4))
        }
        run {
            val autoEnabled = UpdateChecker.isAutoCheckEnabled(this)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                isClickable = true
                isFocusable = true
                Ds.bindPressFeedback(this, 0.985f)
                addView(LinearLayout(this@ProfileSubActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@ProfileSubActivity).apply {
                        text = "自动检查更新"
                        textSize = 14.5f
                        typeface = Ds.body(true)
                        setTextColor(COLOR_INK)
                    })
                    addView(TextView(this@ProfileSubActivity).apply {
                        text = "启动时每天最多检查一次新版本"
                        textSize = 11.5f
                        typeface = Ds.body()
                        setTextColor(COLOR_MUTED)
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val toggle = PillSwitch(this, autoEnabled)
            row.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(28)))
            row.setOnClickListener {
                toggle.toggle()
                UpdateChecker.setAutoCheckEnabled(this, toggle.isOn)
                Toast.makeText(
                    this,
                    if (toggle.isOn) "已开启自动检查更新" else "已关闭自动检查更新",
                    Toast.LENGTH_SHORT
                ).show()
            }
            updateCard.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
            ))
        }

        if (LayoutMode.isWideScreen(this)) {
            listBox.removeView(hero)
            val split = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            split.addView(hero, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.95f).apply {
                rightMargin = dp(20)
            })
            split.addView(infoCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.05f))
            listBox.addView(LayoutMode.wrapCenteredForTablet(this, split, maxDp = 820), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) })
            listBox.addView(LayoutMode.wrapCenteredForTablet(this, updateCard, maxDp = 820), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })
            animateRowIn(split, 0)
        } else {
            listBox.addView(infoCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            listBox.addView(updateCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            animateRowIn(infoCard, 1)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. 播放设置（屏幕方向 / 默认画质，立即写入本机偏好）
    // ═══════════════════════════════════════════════════════════════

    private fun renderSettings() {
        listBox.removeAllViews()

        fun sectionHeader(text: String, topSpace: Int): TextView = TextView(this).apply {
            this.text = text
            textSize = 11.5f
            typeface = Ds.body(true)
            letterSpacing = 0.08f
            setTextColor(Ds.colors.subtle)
            setPadding(dp(2), dp(topSpace), 0, dp(8))
        }

        fun hint(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            setPadding(dp(4), dp(8), dp(4), 0)
        }

        fun newCard(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(0, dp(4), 0, dp(4))
        }

        fun cardDivider(card: LinearLayout) {
            card.addView(
                View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)).apply {
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                }
            )
        }

        fun choiceRow(
            card: LinearLayout,
            label: String,
            value: String,
            selected: Boolean,
            description: String,
            onClick: () -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                isClickable = true
                isFocusable = true
                Ds.bindPressFeedback(this, 0.985f)
                setOnClickListener { onClick() }
                addView(LinearLayout(this@ProfileSubActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@ProfileSubActivity).apply {
                        text = label
                        textSize = 14.5f
                        typeface = Ds.body(true)
                        setTextColor(COLOR_INK)
                    })
                    addView(TextView(this@ProfileSubActivity).apply {
                        text = description
                        textSize = 11.5f
                        typeface = Ds.body()
                        setTextColor(COLOR_MUTED)
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@ProfileSubActivity).apply {
                    text = value
                    textSize = 13f
                    typeface = Ds.body()
                    setTextColor(if (selected) COLOR_ROSE else COLOR_MUTED)
                })
                addView(
                    Ds.chevronRightIcon(this@ProfileSubActivity, Ds.colors.subtle, 1.7f),
                    LinearLayout.LayoutParams(dp(14), dp(14)).apply { leftMargin = dp(6) }
                )
            }
            card.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
            ))
        }

        // 开关栏：与「我的」页夜间模式使用同一款胶囊开关
        fun switchRow(
            card: LinearLayout,
            label: String,
            checked: Boolean,
            description: String,
            onToggle: (Boolean) -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                isClickable = true
                isFocusable = true
                Ds.bindPressFeedback(this, 0.985f)
                addView(LinearLayout(this@ProfileSubActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@ProfileSubActivity).apply {
                        text = label
                        textSize = 14.5f
                        typeface = Ds.body(true)
                        setTextColor(COLOR_INK)
                    })
                    addView(TextView(this@ProfileSubActivity).apply {
                        text = description
                        textSize = 11.5f
                        typeface = Ds.body()
                        setTextColor(COLOR_MUTED)
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val toggle = PillSwitch(this, checked)
            row.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(28)))
            row.setOnClickListener {
                toggle.toggle()
                onToggle(toggle.isOn)
            }
            card.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
            ))
        }

        // 二级菜单：点击栏位后弹出单选列表
        fun <T> showChoiceSheet(
            title: String,
            entries: List<Triple<T, String, String>>,
            selected: T,
            onPicked: (T) -> Unit
        ) {
            val dialog = Dialog(this)
            // 二级菜单不要用系统默认的白色圆角背景，边缘透明、直角贴合。
            dialog.window?.setBackgroundDrawable(ColorDrawable(0x00000000))
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ds.card(
                    this@ProfileSubActivity, COLOR_CARD, null, 0
                )
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            entries.forEach { (key, label, summary) ->
                val isSelected = key == selected
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0)
                    if (isSelected) {
                        background = Ds.card(
                            this@ProfileSubActivity, COLOR_ROSE_SOFT, null, 0, 0
                        )
                    }
                    isClickable = true
                    isFocusable = true
                    Ds.bindPressFeedback(this, 0.97f)
                    addView(LinearLayout(this@ProfileSubActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@ProfileSubActivity).apply {
                            text = label
                            textSize = 14.5f
                            typeface = Ds.body(isSelected)
                            setTextColor(if (isSelected) COLOR_ROSE else COLOR_INK)
                        })
                        addView(TextView(this@ProfileSubActivity).apply {
                            text = summary
                            textSize = 11.5f
                            typeface = Ds.body()
                            setTextColor(COLOR_MUTED)
                            setPadding(0, dp(2), 0, 0)
                        })
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    if (isSelected) {
                        addView(
                            Ds.checkIcon(this@ProfileSubActivity, COLOR_ROSE, 1.7f),
                            LinearLayout.LayoutParams(dp(15), dp(15))
                        )
                    }
                    setOnClickListener {
                        onPicked(key)
                        dialog.dismiss()
                    }
                }
                panel.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
                ))
            }
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(ScrollView(this@ProfileSubActivity).apply {
                addView(panel, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
            })
            LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 420)
            dialog.show()
        }

        // 一级栏位：点击进入二级菜单
        val playbackOrientation = PlayerSettingsStore.orientation(this)
        val homeOrientation = PlayerSettingsStore.homeOrientation(this)
        val currentQuality = PlayerSettingsStore.defaultQuality(this)
        val autoFullscreen = PlayerSettingsStore.landscapeAutoFullscreen(this)
        val smartFullscreen = PlayerSettingsStore.smartFullscreenByVideoRatio(this)

        val orientationCard = newCard()
        choiceRow(
            orientationCard,
            "播放页屏幕方向",
            playbackOrientation.label,
            true,
            "进入播放页后的横竖屏锁定"
        ) {
            showChoiceSheet(
                "播放页屏幕方向",
                PlayerSettingsStore.OrientationMode.entries.map { Triple(it, it.label, it.summary) },
                playbackOrientation
            ) { mode ->
                PlayerSettingsStore.setOrientation(this, mode)
                Toast.makeText(this, "播放页：${mode.label}", Toast.LENGTH_SHORT).show()
                renderSettings()
            }
        }
        cardDivider(orientationCard)
        choiceRow(
            orientationCard,
            "默认播放画质",
            PlayerSettingsStore.QUALITY_OPTIONS.firstOrNull { it.qn == currentQuality }?.label ?: "跟随上次选择",
            true,
            "新打开视频时使用的画质"
        ) {
            showChoiceSheet(
                "默认播放画质",
                PlayerSettingsStore.QUALITY_OPTIONS.map { Triple(it.qn, it.label, it.summary) },
                currentQuality
            ) { qn ->
                PlayerSettingsStore.setDefaultQuality(this, qn)
                val label = PlayerSettingsStore.QUALITY_OPTIONS.firstOrNull { it.qn == qn }?.label ?: "画质"
                Toast.makeText(this, "默认画质：$label", Toast.LENGTH_SHORT).show()
                renderSettings()
            }
        }
        cardDivider(orientationCard)
        switchRow(
            orientationCard,
            "按视频比例智能全屏",
            smartFullscreen,
            "竖屏视频全屏不旋转，横屏视频自动横屏全屏"
        ) { next ->
            PlayerSettingsStore.setSmartFullscreenByVideoRatio(this, next)
            Toast.makeText(this, if (next) "已开启智能全屏" else "已关闭智能全屏", Toast.LENGTH_SHORT).show()
        }
        listBox.addView(sectionHeader("播放", 12))
        listBox.addView(orientationCard)
        listBox.addView(hint("播放页方向设置会覆盖系统方向；智能全屏仅在「跟随系统」且竖屏时按视频比例生效。"))

        val globalCard = newCard()
        choiceRow(
            globalCard,
            "主页（全局）屏幕方向",
            homeOrientation.label,
            true,
            "主页与列表页的横竖屏锁定"
        ) {
            showChoiceSheet(
                "主页（全局）屏幕方向",
                PlayerSettingsStore.OrientationMode.entries.map { Triple(it, it.label, it.summary) },
                homeOrientation
            ) { mode ->
                PlayerSettingsStore.setHomeOrientation(this, mode)
                Toast.makeText(this, "主页：${mode.label}", Toast.LENGTH_SHORT).show()
                renderSettings()
            }
        }
        cardDivider(globalCard)
        switchRow(
            globalCard,
            "横屏进入播放页自动全屏",
            autoFullscreen,
            "横屏打开视频时直接进入全屏播放"
        ) { next ->
            PlayerSettingsStore.setLandscapeAutoFullscreen(this, next)
            Toast.makeText(this, if (next) "已开启横屏自动全屏" else "已关闭横屏自动全屏", Toast.LENGTH_SHORT).show()
        }
        listBox.addView(sectionHeader("主页与全局", 18))
        listBox.addView(globalCard)
        listBox.addView(hint("主页方向设置退出设置后生效；自动全屏仅对播放页横屏进入时生效。"))

        animateRowIn(orientationCard, 0)
        animateRowIn(globalCard, 1)
    }

    /** 用系统浏览器打开外部链接。 */
    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    /** 手动检查更新：成功且有新版就弹窗，否则提示已是最新。 */
    private fun checkUpdateManually() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
        UpdateChecker.check(this) { release, error ->
            if (isFinishing || isDestroyed) return@check
            val current = UpdateChecker.currentVersionName(this)
            if (error != null || release == null) {
                Toast.makeText(this, "检查失败：${error ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                return@check
            }
            if (UpdateChecker.isNewer(release, current)) {
                showUpdateDialog(release, current, allowDismiss = false)
            } else {
                Toast.makeText(this, "已是最新版本 v$current", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 新版本弹窗：展示版本号与更新说明，可直接跳转下载页。
     * allowDismiss 为 true 时（启动自动检查）提供「忽略此版本」，避免反复打扰。
     */
    private fun showUpdateDialog(
        release: UpdateChecker.Release,
        currentVersion: String,
        allowDismiss: Boolean
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0x00000000))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.dialog)
            setPadding(dp(22), dp(22), dp(22), dp(18))
        }
        panel.addView(TextView(this).apply {
            text = "发现新版本 ${release.tag}"
            textSize = 17f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
        })
        panel.addView(TextView(this).apply {
            text = "当前版本 v$currentVersion"
            textSize = 12.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(4), 0, 0)
        })

        val notes = release.body
            .replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")
            .replace(Regex("""```[^`]*```"""), "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        if (notes.isNotBlank()) {
            panel.addView(ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(24))
                addView(TextView(this@ProfileSubActivity).apply {
                    text = notes.take(1600)
                    textSize = 13f
                    typeface = Ds.body()
                    setTextColor(COLOR_MUTED)
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setPadding(0, 0, 0, dp(10))
                })
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { topMargin = dp(14) })
        }

        val primary = TextView(this).apply {
            text = "前往网盘下载"
            textSize = 14f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ON_ROSE)
            background = Ds.pill(this@ProfileSubActivity, COLOR_ROSE)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener {
                openExternalUrl(UpdateChecker.PAN_URL)
                dialog.dismiss()
            }
        }
        panel.addView(primary, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })

        // 网盘密码：点击复制，避免用户还要去记
        panel.addView(TextView(this).apply {
            text = "网盘密码：${UpdateChecker.PAN_PASSWORD}（点击复制）"
            textSize = 13f
            typeface = Ds.body()
            gravity = Gravity.CENTER
            setTextColor(COLOR_ROSE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("YuiBili 网盘密码", UpdateChecker.PAN_PASSWORD))
                Toast.makeText(this@ProfileSubActivity, "网盘密码已复制", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        panel.addView(TextView(this).apply {
            text = "GitHub Releases"
            textSize = 13f
            typeface = Ds.body()
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener {
                openExternalUrl(release.pageUrl)
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val secondary = TextView(this).apply {
            text = if (allowDismiss) "忽略此版本" else "稍后再说"
            textSize = 13.5f
            typeface = Ds.body()
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener {
                if (allowDismiss) UpdateChecker.dismiss(this@ProfileSubActivity, release.tag)
                dialog.dismiss()
            }
        }
        panel.addView(secondary, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        dialog.setContentView(panel)
        dialog.show()
        LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 460)
    }

    private fun showDisclaimerDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0x00000000))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.dialog)
            setPadding(dp(22), dp(22), dp(22), dp(18))
        }
        panel.addView(TextView(this).apply {
            text = "免责声明"
            textSize = 17f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
        })
        panel.addView(TextView(this).apply {
            text = "本软件仅供学习与技术交流使用，与哔哩哔哩官方无任何关联或授权。\n\n所有视频内容版权归原作者及哔哩哔哩所有，请遵守相关法律法规。"
            textSize = 13.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(12), 0, 0)
        })
        panel.addView(TextView(this).apply {
            text = "我知道了"
            textSize = 14f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ON_ROSE)
            background = Ds.pill(this@ProfileSubActivity, COLOR_ROSE)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })
        dialog.setContentView(panel)
        dialog.show()
        LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 420)
    }

    // ═══════════════════════════════════════════════════════════════
    // 共享组件与交互辅助
    // ═══════════════════════════════════════════════════════════════

    /** 收藏夹底部「播放全部」：默认隐藏，仅在收藏夹详情且有内容时显示。 */
    private fun buildPlayAllButton(): View = TextView(this).apply {
        text = "播放全部"
        textSize = 13.5f
        typeface = Ds.body(bold = true)
        setTextColor(android.graphics.Color.WHITE)
        gravity = Gravity.CENTER
        background = Ds.pill(this@ProfileSubActivity, Ds.colors.accent)
        visibility = View.GONE
        isClickable = true
        Ds.bindPressFeedback(this, 0.94f)
        setOnClickListener { playFavoriteFolder() }
    }

    private fun updatePlayAllVisibility() {
        if (!::playAllBtn.isInitialized) return
        val show = mode == MODE_FAVORITES && inFavoriteFolder && favVideos.isNotEmpty()
        playAllBtn.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 从收藏夹第一个视频开始播，整份列表交给播放页作为队列。 */
    private fun playFavoriteFolder() {
        val folder = currentFolder ?: return
        if (favVideos.isEmpty()) return
        // 先把剩余分页拉完，保证队列与收藏夹真实内容一致
        if (!favEnded && !favLoading) {
            setPlayAllLoading(true)
            fetchAllFavoriteVideos(folder) { all ->
                setPlayAllLoading(false)
                if (isFinishing || isDestroyed) return@fetchAllFavoriteVideos
                launchFavoritePlayback(folder, all)
            }
            return
        }
        launchFavoritePlayback(folder, favVideos)
    }

    /** 串行拉完收藏夹剩余分页；失败时回退到已加载部分。 */
    private fun fetchAllFavoriteVideos(
        folder: FavoriteFolder,
        onDone: (List<VideoItem>) -> Unit
    ) {
        val collected = ArrayList(favVideos)
        val seen = HashSet(favSeen)
        var page = favPage
        fun loadNext() {
            BiliApi.fetchFavoriteVideos(folder.id, page = page, pageSize = 20) { videos, error, ended ->
                if (isFinishing || isDestroyed) return@fetchFavoriteVideos
                if (error != null) {
                    // 拿不全就用已加载的部分，不让用户卡在加载中
                    onDone(collected)
                    return@fetchFavoriteVideos
                }
                videos.forEach { video -> if (seen.add(video.bvid)) collected.add(video) }
                val done = ended || videos.isEmpty()
                if (done) {
                    onDone(collected)
                } else {
                    page++
                    loadNext()
                }
            }
        }
        loadNext()
    }

    private fun setPlayAllLoading(loading: Boolean) {
        if (!::playAllBtn.isInitialized) return
        playAllBtn.isEnabled = !loading
        playAllBtn.alpha = if (loading) 0.5f else 1f
        if (playAllBtn is TextView) {
            (playAllBtn as TextView).text = if (loading) "正在准备…" else "播放全部"
        }
    }

    private fun launchFavoritePlayback(folder: FavoriteFolder, videos: List<VideoItem>) {
        if (videos.isEmpty()) return
        val entries = videos.map { video ->
            UgcEpisode(
                bvid = video.bvid,
                aid = video.aid,
                cid = video.cid,
                title = video.title,
                duration = video.duration,
                cover = video.cover,
                publishedAt = video.publishedAt,
                owner = video.owner
            )
        }
        startActivity(
            FavoritePlaybackLauncher.intent(
                this,
                FavoritePlayback(
                    folderId = folder.id,
                    folderName = folder.name,
                    entries = entries,
                    startIndex = 0,
                    startPositionMs = 0L
                )
            )
        )
    }

    private fun openVideo(bvid: String) {
        if (bvid.isBlank()) return
        startActivity(
            Intent(this, VideoDetailActivity::class.java)
                .putExtra(VideoDetailActivity.EXTRA_BVID, bvid)
        )
    }

    private fun cardRowLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }

    private fun groupHeader(title: String, topSpace: Int): TextView = TextView(this).apply {
        text = title
        textSize = 11.5f
        typeface = Ds.body(true)
        letterSpacing = 0.08f
        setTextColor(Ds.colors.subtle)
        setPadding(dp(2), dp(topSpace), 0, dp(8))
    }

    private fun infoBanner(text: String, clickable: Boolean, onClick: (() -> Unit)? = null): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = Ds.body(true)
            setTextColor(COLOR_ROSE)
            background = Ds.card(this@ProfileSubActivity, COLOR_ROSE_SOFT, null, Ds.Radius.control, 0)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            if (clickable && onClick != null) {
                isClickable = true
                isFocusable = true
                Ds.bindPressFeedback(this, 0.98f)
                setOnClickListener { onClick() }
            }
        }

    private fun buildVideoCardRow(
        bvid: String,
        title: String,
        meta: String,
        cover: String,
        durationSec: Int,
        progressSec: Long?,
        onLongClick: () -> Unit,
        aid: Long = 0L,
        cid: Long = 0L,
        resumeMs: Long = 0L
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(10), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            isLongClickable = true
            setOnClickListener {
                VideoDetailActivity.open(this@ProfileSubActivity, bvid, aid, cid, resumeMs)
            }
            setOnLongClickListener {
                onLongClick()
                true
            }
        }

        val thumbBox = FrameLayout(this).apply {
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_COVER)
        }
        if (cover.isNotBlank()) CoverLoader.load(thumb, cover + "@320w_180h.webp")
        thumbBox.addView(
            thumb,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val overlayLabel = formatHistoryCoverBadge(progressSec, durationSec)
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

        val ratio = computeHistoryProgressRatio(progressSec, durationSec)
        if (ratio > 0f) {
            // 封面底边播放进度条：底槽 + 主题玫瑰粉已播放段，对齐参考图视觉
            val track = FrameLayout(this).apply {
                setBackgroundColor(0x55000000)
            }
            val fill = View(this).apply {
                setBackgroundColor(COLOR_ROSE)
            }
            val coverWidthPx = dp(118)
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
        card.addView(thumbBox, LinearLayout.LayoutParams(dp(118), dp(68)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        body.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Ds.body(true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        body.addView(TextView(this).apply {
            text = meta
            textSize = 11.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> DsSpring.pressLift(view, true, 0.98f)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> DsSpring.pressLift(view, false, 0.98f)
            }
            false
        }
        return card
    }

    private fun showActionSheet(title: String, actions: List<Pair<String, () -> Unit>>) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0x00000000))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.dialog)
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }
        panel.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), dp(12))
        })
        actions.forEachIndexed { idx, (label, callback) ->
            if (idx > 0) {
                panel.addView(
                    View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this))
                )
            }
            panel.addView(TextView(this).apply {
                text = label
                textSize = 15f
                typeface = Ds.body(true)
                setTextColor(if (label.contains("删除") || label.contains("移出")) Ds.colors.danger else COLOR_INK)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(14), dp(6), dp(14))
                Ds.bindPressFeedback(this, 0.97f)
                setOnClickListener {
                    dialog.dismiss()
                    callback()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        dialog.setContentView(panel)
        dialog.show()
        LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 420)
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String,
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0x00000000))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.dialog)
            setPadding(dp(22), dp(22), dp(22), dp(18))
        }
        panel.addView(TextView(this).apply {
            text = title
            textSize = 17f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
        })
        panel.addView(TextView(this).apply {
            text = message
            textSize = 13.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(20), 0, 0)
        }
        buttons.addView(TextView(this).apply {
            text = "取消"
            textSize = 14f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            background = Ds.pill(this@ProfileSubActivity, COLOR_SURFACE, Ds.colors.lineSoft)
            setPadding(dp(20), dp(9), dp(20), dp(9))
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener { dialog.dismiss() }
        })
        buttons.addView(TextView(this).apply {
            text = confirmText
            textSize = 14f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ON_ROSE)
            background = Ds.pill(this@ProfileSubActivity, Ds.colors.danger)
            setPadding(dp(20), dp(9), dp(20), dp(9))
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(10) })
        panel.addView(buttons)
        dialog.setContentView(panel)
        dialog.show()
        LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 420)
    }

    private fun animateRowIn(row: View, index: Int) {
        if (MotionTokens.isReduced(this)) return
        row.alpha = 0f
        row.translationY = dp(12).toFloat()
        row.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index.coerceAtMost(10) * 24L))
            .setDuration(240L)
            .setInterpolator(easeOut)
            .start()
    }

    private fun emptyActionCard(
        title: String,
        subtitle: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = Ds.card(this@ProfileSubActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.card)
        setPadding(dp(24), dp(32), dp(24), dp(28))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(28) }
        addView(TextView(this@ProfileSubActivity).apply {
            text = title
            textSize = 16f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            gravity = Gravity.CENTER
        })
        addView(TextView(this@ProfileSubActivity).apply {
            text = subtitle
            textSize = 13f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            addView(TextView(this@ProfileSubActivity).apply {
                text = actionLabel
                textSize = 13.5f
                typeface = Ds.body(true)
                setTextColor(COLOR_ON_ROSE)
                background = Ds.pill(this@ProfileSubActivity, COLOR_ROSE)
                setPadding(dp(22), dp(9), dp(22), dp(9))
                Ds.bindPressFeedback(this, 0.95f)
                setOnClickListener { onAction() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) })
        }
    }

    private fun loadingHint(label: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(72), 0, 0)
        addView(BrandSpinnerView(this@ProfileSubActivity, COLOR_ROSE, Color.WHITE).apply {
            contentDescription = label
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        addView(TextView(this@ProfileSubActivity).apply {
            text = label
            textSize = 12.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun loadMoreHint(text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(14), 0, dp(14))
        if (text.contains("加载") && !text.contains("失败")) {
            addView(
                BrandSpinnerView(this@ProfileSubActivity, COLOR_ROSE, Color.WHITE),
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) }
            )
        }
        addView(TextView(this@ProfileSubActivity).apply {
            this.text = text
            textSize = 12f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
        })
    }

    private fun formatShortDate(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(epochSeconds * 1000L))
    }

    companion object {
        private const val HORIZONTAL_OR_ROW = LinearLayout.HORIZONTAL
        const val EXTRA_MODE = "profile_sub_mode"
        const val MODE_HISTORY = "history"
        const val MODE_FAVORITES = "favorites"
        const val MODE_DOWNLOADS = "downloads"
        const val MODE_SPONSOR = "sponsor"
        const val MODE_ABOUT = "about"
        const val MODE_SETTINGS = "settings"
        private const val REQUEST_CREATE_FOLDER = 4101
    }
}

/** 本机最近打开的视频历史（仅作未登录/离线兜底，最多 100 条）。 */
object HistoryStore {
    private const val PREFS = "yuibili_history"
    private const val KEY = "items"
    private const val MAX = 100

    data class Entry(val bvid: String, val title: String, val owner: String, val cover: String, val date: String)

    fun add(context: Context, bvid: String, title: String, owner: String, cover: String) {
        if (bvid.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = try { JSONArray(prefs.getString(KEY, "[]").orEmpty()) } catch (_: Exception) { JSONArray() }
        val filtered = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optString("bvid") != bvid) filtered.put(obj)
        }
        val head = JSONArray().put(JSONObject().apply {
            put("bvid", bvid)
            put("title", title)
            put("owner", owner)
            put("cover", cover)
            put("date", SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date()))
        })
        for (i in 0 until Math.min(filtered.length(), MAX - 1)) head.put(filtered.get(i))
        prefs.edit().putString(KEY, head.toString()).apply()
    }

    fun remove(context: Context, bvid: String) {
        if (bvid.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = try { JSONArray(prefs.getString(KEY, "[]").orEmpty()) } catch (_: Exception) { JSONArray() }
        val filtered = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optString("bvid") != bvid) filtered.put(obj)
        }
        prefs.edit().putString(KEY, filtered.toString()).apply()
    }

    fun load(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = try { JSONArray(prefs.getString(KEY, "[]").orEmpty()) } catch (_: Exception) { JSONArray() }
        val result = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            result += Entry(
                bvid = obj.optString("bvid"),
                title = obj.optString("title").ifBlank { "（无标题）" },
                owner = obj.optString("owner").ifBlank { "未知UP主" },
                cover = obj.optString("cover"),
                date = obj.optString("date")
            )
        }
        return result
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

/** 格式化历史封面右下角「已播放时长 / 总时长」（如 `00:07 / 07:31`），无进度时退化为总时长。 */
internal fun formatHistoryCoverBadge(progressSec: Long?, durationSec: Int): String {
    if (durationSec <= 0) {
        return if (progressSec != null && progressSec > 0L) formatWatchTime(progressSec.toInt()) else ""
    }
    val total = formatWatchTime(durationSec)
    if (progressSec == null) return total
    val playedSec = when {
        progressSec == -1L -> durationSec
        progressSec > 0L -> progressSec.toInt().coerceAtMost(durationSec)
        else -> 0
    }
    return "${formatWatchTime(playedSec)} / $total"
}

/** 计算封面底部进度条比例（0f 表示未看/不展示进度条，1f 表示已看完）。 */
internal fun computeHistoryProgressRatio(progressSec: Long?, durationSec: Int): Float {
    if (progressSec == null || durationSec <= 0) return 0f
    return when {
        progressSec == -1L -> 1f
        progressSec > 0L -> (progressSec.toFloat() / durationSec.toFloat()).coerceIn(0.02f, 1f)
        else -> 0f
    }
}

private fun formatWatchTime(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 与「我的」页夜间模式一致的胶囊开关（自绘，避免系统 Switch 样式割裂）。 */
private class PillSwitch(context: Context, initial: Boolean) : View(context) {
    var isOn: Boolean = initial
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density: Float = resources.displayMetrics.density
    private val dpf: (Float) -> Float = { it * density }
    private var progress: Float = if (initial) 1f else 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
    }

    fun toggle() {
        val target = !isOn
        isOn = target
        animator?.cancel()
        if (MotionTokens.isReduced(context)) {
            progress = if (target) 1f else 0f
            invalidate()
            return
        }
        val start = progress
        animator = ValueAnimator.ofFloat(start, if (target) 1f else 0f).apply {
            duration = 220L
            interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val c = Ds.colors
        val r = h / 2f
        paint.style = Paint.Style.FILL
        paint.color = mixColor(c.line, c.accent, progress)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)

        val pad = dpf(3f)
        val knobSize = h - pad * 2f
        val knobX = pad + (w - knobSize - pad * 2f) * progress
        paint.color = Color.WHITE
        canvas.drawCircle(knobX + knobSize / 2f, h / 2f, knobSize / 2f, paint)
    }

    private fun mixColor(from: Int, to: Int, t: Float): Int {
        val fr = (from shr 16) and 0xFF
        val fg = (from shr 8) and 0xFF
        val fb = from and 0xFF
        val tr = (to shr 16) and 0xFF
        val tg = (to shr 8) and 0xFF
        val tb = to and 0xFF
        return Color.rgb(
            (fr + (tr - fr) * t).toInt(),
            (fg + (tg - fg) * t).toInt(),
            (fb + (tb - fb) * t).toInt()
        )
    }
}
