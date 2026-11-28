package com.yuilittle.bili

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 「我的 → 离线缓存 / 下载管理」页面：
 * - 保存位置仅影响新任务；旧系统在用户选择公共库时申请写权限；
 * - 顶部真实空间占用统计卡片（通过 `DownloadManager.measureStorageBreakdown` 计算真实目录与设备存储）；
 * - 下方仅「视频」「音频」双标签，支持点击与左右滑动无缝切换（复用 `RootPager`）；
 * - 支持全选/取消全选批量删除（二次确认）以及长按条目的选项弹窗（重命名 / 删除，二次确认）；
 * - 单视频/单音频展示封面内嵌时长、标题、下载日期、真实占用；合集展示堆叠封面+居中集数角标、标题、日期、总占用。
 */
class DownloadPageView(
    context: Context,
    private val initialTab: Int = TAB_VIDEO,
    private val onClose: (() -> Unit)? = null
) : FrameLayout(context) {

    companion object {
        const val TAB_VIDEO = 0
        const val TAB_AUDIO = 1
    }

    private sealed class DisplayEntry {
        abstract val entryKey: String
        abstract val taskIds: List<Long>
        abstract val latestTime: Long

        data class Single(val task: DownloadManager.Task) : DisplayEntry() {
            override val entryKey: String = "single:${task.id}"
            override val taskIds: List<Long> = listOf(task.id)
            override val latestTime: Long = task.finishedAt.takeIf { it > 0L } ?: task.createdAt
        }

        data class Season(
            val mediaType: Int,
            val groupId: String,
            val tasks: List<DownloadManager.Task>
        ) : DisplayEntry() {
            override val entryKey: String = "season:$mediaType:$groupId"
            override val taskIds: List<Long> = tasks.map { it.id }
            override val latestTime: Long = tasks.maxOfOrNull {
                it.finishedAt.takeIf { f -> f > 0L } ?: it.createdAt
            } ?: 0L
        }
    }

    private var currentTab = if (initialTab == TAB_AUDIO) TAB_AUDIO else TAB_VIDEO
    private var selectionMode = false
    private val selectedEntryKeys = HashSet<String>()

    private var cachedBreakdown: DownloadManager.StorageBreakdown? = null
    private var measuringStorage = false
    private var lastStorageMeasureAtMs = 0L
    private var pageActive = true
    private var listenerRegistered = false
    private var lastStructureSignature = ""
    private var pendingRefreshRunnable: Runnable? = null
    private var lastRefreshAtMs = 0L

    private lateinit var manageBtn: FrameLayout
    private lateinit var manageIcon: ToolbarIcon
    private lateinit var selectAllBtn: TextView
    private lateinit var storagePrimaryText: TextView
    private lateinit var storageDetailText: TextView
    private lateinit var storageBar: StorageSegmentBar
    private lateinit var destinationButton: FrameLayout

    private lateinit var tabVideoLabel: TextView
    private lateinit var tabAudioLabel: TextView
    private lateinit var tabIndicator: View
    private lateinit var tabRow: FrameLayout
    private lateinit var pager: RootPager

    private lateinit var videoListContainer: LinearLayout
    private lateinit var audioListContainer: LinearLayout
    private lateinit var videoScroll: ScrollView
    private lateinit var audioScroll: ScrollView

    private lateinit var batchBar: LinearLayout
    private lateinit var batchCountText: TextView
    private lateinit var batchDeleteBtn: TextView

    private var videoEntries: List<DisplayEntry> = emptyList()
    private var audioEntries: List<DisplayEntry> = emptyList()

    private val listener: (List<DownloadManager.Task>) -> Unit = { allTasks ->
        post {
            if (!pageActive) return@post
            scheduleCoalescedRefresh(allTasks)
        }
    }

    init {
        if (!DownloadManager.init(context)) {
            Toast.makeText(context, "暂时无法读取下载记录，原文件已保留，请稍后重新打开", Toast.LENGTH_LONG).show()
        }
        setBackgroundColor(Ds.colors.bg)
        buildLayout()
        refreshAll(DownloadManager.snapshot(), remeasureStorage = true, forceRebuild = true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncListenerRegistration()
        if (pageActive) {
            refreshAll(DownloadManager.snapshot(), remeasureStorage = true, forceRebuild = true)
        }
    }

    override fun onDetachedFromWindow() {
        pendingRefreshRunnable?.let(::removeCallbacks)
        pendingRefreshRunnable = null
        if (listenerRegistered) {
            DownloadManager.removeListener(listener)
            listenerRegistered = false
        }
        super.onDetachedFromWindow()
    }

    /** 供平板侧边栏隐藏/切换时显式暂停监听，避免隐藏下载页每 250ms 重建视图与扫目录。 */
    fun setActive(active: Boolean) {
        if (pageActive == active) return
        pageActive = active
        syncListenerRegistration()
        if (active && isAttachedToWindow) {
            refreshAll(DownloadManager.snapshot(), remeasureStorage = true, forceRebuild = true)
        } else {
            pendingRefreshRunnable?.let(::removeCallbacks)
            pendingRefreshRunnable = null
        }
    }

    private fun syncListenerRegistration() {
        val shouldListen = pageActive && isAttachedToWindow
        if (shouldListen && !listenerRegistered) {
            DownloadManager.addListener(listener)
            listenerRegistered = true
        } else if (!shouldListen && listenerRegistered) {
            DownloadManager.removeListener(listener)
            listenerRegistered = false
        }
    }

    fun refreshNow() {
        if (!pageActive) return
        refreshAll(DownloadManager.snapshot(), remeasureStorage = true, forceRebuild = true)
    }

    private fun scheduleCoalescedRefresh(allTasks: List<DownloadManager.Task>) {
        val sig = buildStructureSignature(allTasks)
        val structureChanged = sig != lastStructureSignature
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = now - lastRefreshAtMs
        // 结构变化（新增/完成/暂停/删除）立即刷新并重算存储；纯进度回调合并至 500ms 一次且不重扫存储目录
        if (structureChanged) {
            pendingRefreshRunnable?.let(::removeCallbacks)
            pendingRefreshRunnable = null
            refreshAll(allTasks, remeasureStorage = true, forceRebuild = true)
            return
        }
        val minInterval = 500L
        if (elapsed >= minInterval) {
            pendingRefreshRunnable?.let(::removeCallbacks)
            pendingRefreshRunnable = null
            refreshAll(allTasks, remeasureStorage = false, forceRebuild = true)
        } else if (pendingRefreshRunnable == null) {
            val r = Runnable {
                pendingRefreshRunnable = null
                if (pageActive && isAttachedToWindow) {
                    refreshAll(DownloadManager.snapshot(), remeasureStorage = false, forceRebuild = true)
                }
            }
            pendingRefreshRunnable = r
            postDelayed(r, minInterval - elapsed)
        }
    }

    private fun buildStructureSignature(allTasks: List<DownloadManager.Task>): String =
        allTasks.joinToString("|") { "${it.id}:${it.type}:${it.state}:${it.groupId}:${it.title}:${it.groupTitle}" }

    /** 侧滑/系统返回时，若处于批量管理状态则先退出批量管理并返回 true。 */
    fun handleBack(): Boolean {
        if (!selectionMode) return false
        toggleSelectionMode(false)
        return true
    }

    private fun buildLayout() {
        val c = Ds.colors
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ── 顶栏 ──
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.Space.sm), dp(Ds.Space.sm), dp(Ds.Space.lg), dp(Ds.Space.sm))
        }
        if (onClose != null) {
            topBar.addView(Ds.backIcon(context, c.text) { onClose.invoke() },
                LinearLayout.LayoutParams(dp(40), dp(40)))
        } else {
            topBar.addView(View(context), LinearLayout.LayoutParams(dp(8), 0))
        }
        topBar.addView(TextView(context).apply {
            text = "离线缓存"
            textSize = 20f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        destinationButton = FrameLayout(context).apply {
            isFocusable = true
            addView(ToolbarIcon(context, storage = true), LayoutParams(dp(20), dp(20), Gravity.CENTER))
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { DownloadUiShared.showStorageChoice(context, ::updateDestinationLabel) }
        }
        updateDestinationLabel()
        topBar.addView(destinationButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        selectAllBtn = TextView(context).apply {
            text = "全选"
            textSize = 12.5f
            typeface = Ds.body(bold = true)
            setTextColor(c.accent)
            background = Ds.pill(context, c.accentSoft)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { toggleSelectAllCurrentTab() }
        }
        topBar.addView(selectAllBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8)
        })

        manageIcon = ToolbarIcon(context, storage = false)
        manageBtn = FrameLayout(context).apply {
            isFocusable = true
            contentDescription = "批量管理"
            addView(manageIcon, LayoutParams(dp(20), dp(20), Gravity.CENTER))
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { toggleSelectionMode(!selectionMode) }
        }
        topBar.addView(manageBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 真实空间占用头卡 ──
        val storageCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(context, c.surface, c.lineSoft, Ds.Radius.card)
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val storageHead = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        storagePrimaryText = TextView(context).apply {
            text = "正在统计存储占用..."
            textSize = 13.5f
            typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            setTextColor(c.text)
        }
        storageHead.addView(storagePrimaryText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        storageCard.addView(storageHead)

        storageBar = StorageSegmentBar(context)
        storageCard.addView(storageBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply {
            topMargin = dp(9)
        })

        storageDetailText = TextView(context).apply {
            text = "本应用离线缓存 · 设备可用空间"
            textSize = 11.5f
            fontFeatureSettings = "tnum"
            setTextColor(c.muted)
            maxLines = 2
        }
        storageCard.addView(storageDetailText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        root.addView(storageCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(4); bottomMargin = dp(6)
        })

        // ── 仅「视频 / 音频」双标签 ──
        tabRow = FrameLayout(context).apply {
            setPadding(dp(14), 0, dp(14), 0)
        }
        val labelsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabVideoLabel = TextView(context).apply {
            text = "视频"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, dp(11))
            isClickable = true
            setOnClickListener { switchTab(TAB_VIDEO, animate = true) }
        }
        tabAudioLabel = TextView(context).apply {
            text = "音频"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, dp(11))
            isClickable = true
            setOnClickListener { switchTab(TAB_AUDIO, animate = true) }
        }
        labelsRow.addView(tabVideoLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        labelsRow.addView(tabAudioLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabRow.addView(labelsRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        tabIndicator = View(context).apply {
            background = Ds.pill(context, c.accent)
        }
        tabRow.addView(tabIndicator, FrameLayout.LayoutParams(dp(28), dp(3), Gravity.BOTTOM or Gravity.START))
        root.addView(tabRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(View(context).apply { setBackgroundColor(c.lineSoft) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(context)))

        // ── 双标签横滑列表容器 ──
        videoListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(88))
        }
        videoScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(videoListContainer)
        }

        audioListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(88))
        }
        audioScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(audioListContainer)
        }

        pager = RootPager(context).apply {
            addPage(videoScroll)
            addPage(audioScroll)
            setOnPageSelectedListener { page ->
                if (currentTab != page) {
                    currentTab = page
                    updateTabChrome()
                    updateSelectionChrome()
                }
            }
            setOnPageScrollListener { offset ->
                positionIndicator(offset)
            }
        }
        pager.showPage(currentTab, animate = false)
        root.addView(pager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val rootParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(root, rootParams)

        // ── 底部批量删除操作条 ──
        batchBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(c.surface)
            setPadding(dp(16), dp(10), dp(16), dp(14))
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }
        batchCountText = TextView(context).apply {
            text = "已选 0 项"
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(c.text)
        }
        batchBar.addView(batchCountText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        batchDeleteBtn = TextView(context).apply {
            text = "删除所选"
            textSize = 13.5f
            typeface = Ds.body(bold = true)
            setTextColor(Color.WHITE)
            background = Ds.pill(context, c.danger)
            setPadding(dp(20), dp(9), dp(20), dp(9))
            isClickable = true
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener { confirmDeleteSelected() }
        }
        batchBar.addView(batchDeleteBtn)
        addView(batchBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        tabRow.post { positionIndicator(currentTab.toFloat()) }
        updateTabChrome()
    }

    private fun switchTab(tab: Int, animate: Boolean) {
        currentTab = tab
        pager.showPage(tab, animate)
        updateTabChrome()
        updateSelectionChrome()
    }

    private fun updateTabChrome() {
        val c = Ds.colors
        val videoActive = currentTab == TAB_VIDEO
        tabVideoLabel.setTextColor(if (videoActive) c.text else c.muted)
        tabVideoLabel.typeface = Ds.display(bold = videoActive)
        tabAudioLabel.setTextColor(if (!videoActive) c.text else c.muted)
        tabAudioLabel.typeface = Ds.display(bold = !videoActive)
        positionIndicator(currentTab.toFloat())
    }

    private fun positionIndicator(fraction: Float) {
        val innerW = tabRow.width - tabRow.paddingLeft - tabRow.paddingRight
        if (innerW <= 0) return
        val tabW = innerW / 2f
        val indW = dp(28).toFloat()
        val x = tabRow.paddingLeft + tabW * fraction + (tabW - indW) / 2f
        tabIndicator.translationX = x - tabRow.paddingLeft
    }

    // ── 数据刷新与分组 ─────────────────────────────────────────

    private fun refreshAll(
        allTasks: List<DownloadManager.Task>,
        remeasureStorage: Boolean,
        forceRebuild: Boolean = false
    ) {
        lastRefreshAtMs = android.os.SystemClock.elapsedRealtime()
        if (remeasureStorage) updateDestinationLabel()
        val sig = buildStructureSignature(allTasks)
        val structureChanged = sig != lastStructureSignature
        lastStructureSignature = sig
        videoEntries = groupTasksForType(allTasks, DownloadManager.TYPE_VIDEO)
        audioEntries = groupTasksForType(allTasks, DownloadManager.TYPE_AUDIO)

        tabVideoLabel.text = if (videoEntries.isEmpty()) "视频" else "视频 (${allTasks.count { it.type == DownloadManager.TYPE_VIDEO }})"
        tabAudioLabel.text = if (audioEntries.isEmpty()) "音频" else "音频 (${allTasks.count { it.type == DownloadManager.TYPE_AUDIO }})"

        // 移除失效的选择 key
        val validKeys = (videoEntries + audioEntries).mapTo(HashSet()) { it.entryKey }
        selectedEntryKeys.retainAll(validKeys)

        if (forceRebuild || structureChanged) {
            // 只在结构变更或当前可见标签处于活跃态时渲染，避免无谓重建两个不可见列表
            if (structureChanged || currentTab == TAB_VIDEO || videoListContainer.childCount == 0) {
                renderList(videoListContainer, videoEntries, DownloadManager.TYPE_VIDEO)
            }
            if (structureChanged || currentTab == TAB_AUDIO || audioListContainer.childCount == 0) {
                renderList(audioListContainer, audioEntries, DownloadManager.TYPE_AUDIO)
            }
        }
        updateSelectionChrome()

        val now = android.os.SystemClock.elapsedRealtime()
        val storageDue = remeasureStorage && (cachedBreakdown == null || structureChanged || now - lastStorageMeasureAtMs >= 4_000L)
        if (storageDue && !measuringStorage) {
            measuringStorage = true
            lastStorageMeasureAtMs = now
            Thread {
                val stats = DownloadManager.measureStorageBreakdown(context)
                post {
                    measuringStorage = false
                    cachedBreakdown = stats
                    if (isAttachedToWindow) {
                        renderStorageCard(stats)
                    }
                }
            }.start()
        } else {
            cachedBreakdown?.let { renderStorageCard(it) }
        }
    }

    private fun groupTasksForType(all: List<DownloadManager.Task>, mediaType: Int): List<DisplayEntry> {
        val typed = all.filter { it.type == mediaType }
        val seasons = linkedMapOf<String, MutableList<DownloadManager.Task>>()
        val singles = ArrayList<DisplayEntry>()
        for (task in typed) {
            if (task.groupId.isNotBlank()) {
                seasons.getOrPut(task.groupId) { ArrayList() }.add(task)
            } else {
                singles.add(DisplayEntry.Single(task))
            }
        }
        val combined = ArrayList<DisplayEntry>(singles.size + seasons.size)
        combined.addAll(singles)
        for ((gid, list) in seasons) {
            combined.add(DisplayEntry.Season(mediaType, gid, list))
        }
        return combined.sortedByDescending { it.latestTime }
    }

    private fun updateDestinationLabel() {
        val library = DownloadManager.defaultDestination(context) == DownloadDestination.LIBRARY
        val label = "新下载保存位置：${if (library) "系统媒体库" else "应用内"}"
        destinationButton.contentDescription = label
        if (android.os.Build.VERSION.SDK_INT >= 26) destinationButton.tooltipText = label
    }

    private fun renderStorageCard(stats: DownloadManager.StorageBreakdown) {
        val appUsed = stats.appTotalBytes
        val cacheUsed = stats.downloadsBytes
        val avail = stats.deviceFreeBytes
        val total = stats.deviceTotalBytes.coerceAtLeast(1L)

        storagePrimaryText.text = "应用占用 ${DownloadUiShared.formatBytes(appUsed)} · 私有下载 ${DownloadUiShared.formatBytes(cacheUsed)}"
        storageDetailText.text = "本机已用 ${DownloadUiShared.formatBytes(stats.deviceUsedBytes)} · 剩余可用 ${DownloadUiShared.formatBytes(avail)} / 共 ${DownloadUiShared.formatBytes(total)}"
        storageBar.setData(
            deviceTotal = total,
            appBytes = appUsed,
            otherUsedBytes = (stats.deviceUsedBytes - appUsed).coerceAtLeast(0L)
        )
    }

    private fun renderList(container: LinearLayout, entries: List<DisplayEntry>, mediaType: Int) {
        container.removeAllViews()

        if (entries.isEmpty()) {
            container.addView(buildEmptyState(mediaType))
            return
        }

        val rowHeight = dp(88)
        val useTwoCols = LayoutMode.isWideScreen(context)

        fun buildEntryCard(entry: DisplayEntry): View {
            val selected = entry.entryKey in selectedEntryKeys
            return when (entry) {
                is DisplayEntry.Single -> DownloadUiShared.buildSingleTaskCardContent(
                    context = context,
                    task = entry.task,
                    selectionMode = selectionMode,
                    isSelected = selected,
                    onToggleSelect = { toggleEntrySelected(entry.entryKey) },
                    onActionClick = { handleTaskAction(entry.task) },
                    onBodyClick = { openTask(entry.task) },
                    onLongClick = { showEntryOptions(entry) }
                )
                is DisplayEntry.Season -> DownloadUiShared.buildSeasonGroupCardContent(
                    context = context,
                    groupTasks = entry.tasks,
                    selectionMode = selectionMode,
                    isSelected = selected,
                    onToggleSelect = { toggleEntrySelected(entry.entryKey) },
                    onBodyClick = {
                        context.startActivity(Intent(context, DownloadSeasonActivity::class.java).apply {
                            putExtra(DownloadSeasonActivity.EXTRA_GROUP_ID, entry.groupId)
                            putExtra(DownloadSeasonActivity.EXTRA_MEDIA_TYPE, entry.mediaType)
                        })
                    },
                    onLongClick = { showEntryOptions(entry) }
                )
            }
        }

        if (!useTwoCols) {
            for (entry in entries) {
                container.addView(buildEntryCard(entry), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowHeight
                ).apply { bottomMargin = dp(10) })
            }
            return
        }

        // 平板宽屏双列网格：充分铺满工作台宽度
        var i = 0
        while (i < entries.size) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val left = buildEntryCard(entries[i])
            row.addView(left, LinearLayout.LayoutParams(0, rowHeight, 1f).apply { rightMargin = dp(12) })
            if (i + 1 < entries.size) {
                val right = buildEntryCard(entries[i + 1])
                row.addView(right, LinearLayout.LayoutParams(0, rowHeight, 1f))
            } else {
                row.addView(View(context), LinearLayout.LayoutParams(0, rowHeight, 1f))
            }
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
            i += 2
        }
    }

    private fun buildEmptyState(mediaType: Int): View {
        val c = Ds.colors
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(64), dp(24), dp(40))
            addView(TextView(context).apply {
                text = if (mediaType == DownloadManager.TYPE_AUDIO) "还没有缓存音频" else "还没有缓存视频"
                textSize = 15.5f
                typeface = Ds.display(bold = true)
                setTextColor(c.text)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "在视频详情页点「下载」即可将单视频、纯音频或整个合集缓存到本地"
                textSize = 12.5f
                setTextColor(c.muted)
                gravity = Gravity.CENTER
                setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
    }

    // ── 选择、重命名与删除（全选/取消全选 + 批量删除 + 长按选项，均二次确认） ──

    private fun toggleSelectionMode(enable: Boolean) {
        selectionMode = enable
        if (!enable) selectedEntryKeys.clear()
        refreshAll(DownloadManager.snapshot(), remeasureStorage = false)
    }

    private fun showEntryOptions(entry: DisplayEntry) {
        val currentTitle = when (entry) {
            is DisplayEntry.Single -> entry.task.title
            is DisplayEntry.Season -> entry.tasks.firstOrNull()?.groupTitle?.ifBlank { entry.tasks.first().title }.orEmpty()
        }
        DownloadUiShared.showEntryLongPressMenu(
            context = context,
            title = currentTitle,
            onRename = {
                DownloadUiShared.showRenameDialog(
                    context = context,
                    dialogTitle = if (entry is DisplayEntry.Season) "重命名合集" else "重命名",
                    currentName = currentTitle
                ) { nextName ->
                    when (entry) {
                        is DisplayEntry.Single -> DownloadManager.rename(entry.task.id, nextName)
                        is DisplayEntry.Season -> DownloadManager.renameGroup(entry.groupId, nextName)
                    }
                    Toast.makeText(context, "已重命名", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { confirmDeleteEntry(entry) }
        )
    }

    private fun currentTabEntries(): List<DisplayEntry> =
        if (currentTab == TAB_AUDIO) audioEntries else videoEntries

    private fun toggleEntrySelected(key: String) {
        if (key in selectedEntryKeys) selectedEntryKeys.remove(key)
        else selectedEntryKeys.add(key)
        renderList(
            if (currentTab == TAB_AUDIO) audioListContainer else videoListContainer,
            currentTabEntries(),
            if (currentTab == TAB_AUDIO) DownloadManager.TYPE_AUDIO else DownloadManager.TYPE_VIDEO
        )
        updateSelectionChrome()
    }

    private fun toggleSelectAllCurrentTab() {
        val entries = currentTabEntries()
        if (entries.isEmpty()) return
        val allSelected = entries.all { it.entryKey in selectedEntryKeys }
        if (allSelected) {
            entries.forEach { selectedEntryKeys.remove(it.entryKey) }
        } else {
            entries.forEach { selectedEntryKeys.add(it.entryKey) }
        }
        renderList(
            if (currentTab == TAB_AUDIO) audioListContainer else videoListContainer,
            entries,
            if (currentTab == TAB_AUDIO) DownloadManager.TYPE_AUDIO else DownloadManager.TYPE_VIDEO
        )
        updateSelectionChrome()
    }

    private fun updateSelectionChrome() {
        val c = Ds.colors
        val entries = currentTabEntries()
        val count = selectedEntryKeys.size
        manageBtn.isSelected = selectionMode
        manageIcon.isSelected = selectionMode
        manageIcon.invalidate()
        manageBtn.contentDescription = if (selectionMode) "完成批量管理" else "批量管理"
        if (android.os.Build.VERSION.SDK_INT >= 26) manageBtn.tooltipText = manageBtn.contentDescription
        if (selectionMode) {
            selectAllBtn.visibility = if (entries.isNotEmpty()) View.VISIBLE else View.GONE
            val allSelected = entries.isNotEmpty() && entries.all { it.entryKey in selectedEntryKeys }
            selectAllBtn.text = if (allSelected) "取消全选" else "全选"
            batchBar.visibility = View.VISIBLE
            batchCountText.text = "已选 $count 项"
            batchDeleteBtn.alpha = if (count > 0) 1f else 0.45f
            batchDeleteBtn.isEnabled = count > 0
        } else {
            selectAllBtn.visibility = View.GONE
            batchBar.visibility = View.GONE
        }
    }

    private fun confirmDeleteEntry(entry: DisplayEntry) {
        val title: String
        val msg: String
        when (entry) {
            is DisplayEntry.Single -> {
                title = "确认删除缓存"
                msg = "确定要删除「${entry.task.title}」的本地缓存文件吗？删除后无法恢复。"
            }
            is DisplayEntry.Season -> {
                val gTitle = entry.tasks.firstOrNull()?.groupTitle?.ifBlank { "该合集" } ?: "该合集"
                title = "确认删除合集"
                msg = "确定要删除合集「$gTitle」下的全部 ${entry.tasks.size} 个本地缓存文件吗？删除后无法恢复。"
            }
        }
        DownloadUiShared.showConfirmDeleteDialog(context, title, msg, entry.taskIds) {
            DownloadManager.deleteMany(entry.taskIds)
            Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSelected() {
        if (selectedEntryKeys.isEmpty()) return
        val allEntries = videoEntries + audioEntries
        val chosen = allEntries.filter { it.entryKey in selectedEntryKeys }
        val ids = chosen.flatMap { it.taskIds }.distinct()
        if (ids.isEmpty()) return
        DownloadUiShared.showConfirmDeleteDialog(
            context,
            "确认批量删除",
            "确定要删除所选 ${chosen.size} 项（共 ${ids.size} 个下载任务）吗？删除后无法恢复。",
            ids
        ) {
            DownloadManager.deleteMany(ids)
            selectedEntryKeys.clear()
            selectionMode = false
            Toast.makeText(context, "已删除 ${ids.size} 项缓存", Toast.LENGTH_SHORT).show()
        }
    }

    // ── 任务交互 ───────────────────────────────────────────────

    private fun handleTaskAction(task: DownloadManager.Task) = DownloadUiShared.taskAction(context, task)

    private fun openTask(task: DownloadManager.Task) {
        when (task.state) {
            DownloadManager.STATE_COMPLETED -> {
                if (!task.playable()) {
                    Toast.makeText(context, "本地文件已丢失，可重新下载", Toast.LENGTH_SHORT).show()
                    return
                }
                if (task.type == DownloadManager.TYPE_AUDIO) {
                    AudioPlayerActivity.open(context, task.id)
                } else {
                    context.startActivity(Intent(context, VideoDetailActivity::class.java).apply {
                        putExtra(VideoDetailActivity.EXTRA_LOCAL_TASK_ID, task.id)
                    })
                }
            }
            DownloadManager.STATE_RUNNING, DownloadManager.STATE_QUEUED -> {
                Toast.makeText(context, "正在下载中，完成后即可离线播放", Toast.LENGTH_SHORT).show()
            }
            DownloadManager.STATE_PAUSED, DownloadManager.STATE_FAILED -> handleTaskAction(task)
        }
    }

    private class ToolbarIcon(context: Context, private val storage: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = android.graphics.Path()
        override fun onDraw(canvas: Canvas) {
            paint.color = if (isSelected) Ds.colors.accent else Ds.colors.text
            val w = width.toFloat()
            val h = height.toFloat()
            path.rewind()
            if (storage) {
                path.moveTo(w * .12f, h * .78f)
                path.lineTo(w * .12f, h * .22f)
                path.lineTo(w * .39f, h * .22f)
                path.lineTo(w * .49f, h * .34f)
                path.lineTo(w * .88f, h * .34f)
                path.lineTo(w * .88f, h * .78f)
                path.close()
            } else if (isSelected) {
                path.moveTo(w * .16f, h * .51f)
                path.lineTo(w * .40f, h * .74f)
                path.lineTo(w * .85f, h * .25f)
            } else {
                for (y in listOf(.25f, .5f, .75f)) {
                    path.moveTo(w * .12f, h * (y - .01f))
                    path.lineTo(w * .18f, h * (y + .05f))
                    path.lineTo(w * .29f, h * (y - .07f))
                    path.moveTo(w * .44f, h * y)
                    path.lineTo(w * .88f, h * y)
                }
            }
            canvas.drawPath(path, paint)
        }
    }

    /** 三段式真实磁盘占用条：本软件占用（玫瑰粉） / 其他已用（浅灰） / 设备剩余。 */
    private class StorageSegmentBar(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ds.colors.lineSoft }
        private val otherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ds.colors.subtle }
        private val appPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ds.colors.accent }
        private var totalBytes = 1L
        private var appBytes = 0L
        private var otherBytes = 0L

        fun setData(deviceTotal: Long, appBytes: Long, otherUsedBytes: Long) {
            this.totalBytes = deviceTotal.coerceAtLeast(1L)
            this.appBytes = appBytes.coerceAtLeast(0L)
            this.otherBytes = otherUsedBytes.coerceAtLeast(0L)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, trackPaint)

            val usedFrac = ((appBytes + otherBytes).toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            val appFrac = (appBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            if (usedFrac > 0.004f) {
                canvas.drawRoundRect(RectF(0f, 0f, w * usedFrac, h), r, r, otherPaint)
            }
            if (appBytes > 0L) {
                val appW = (w * appFrac).coerceAtLeast(h * 1.5f).coerceAtMost(w)
                canvas.drawRoundRect(RectF(0f, 0f, appW, h), r, r, appPaint)
            }
        }
    }
}
