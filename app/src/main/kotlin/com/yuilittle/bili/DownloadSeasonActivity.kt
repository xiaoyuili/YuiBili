package com.yuilittle.bili

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 离线合集详情列表页：
 * - 展示合集信息（封面、合集名、分集数量与总真实磁盘占用）；
 * - 支持排序（默认/从旧到新/从新到旧）；
 * - 支持全选/取消全选批量删除（二次确认）以及长按条目的选项弹窗（重命名 / 删除，二次确认）；
 * - 侧滑/系统返回在批量管理状态下先退出批量管理；
 * - 单条样式与主页单视频/单音频一致：封面内嵌时长、标题、下载日期与大小；点击直接进入离线播放。
 */
class DownloadSeasonActivity : Activity() {

    private var groupId: String = ""
    private var mediaType: Int = DownloadManager.TYPE_VIDEO
    private var sortOrder = DownloadManager.SeasonSortOrder.DEFAULT

    private var selectionMode = false
    private val selectedTaskIds = HashSet<Long>()

    private lateinit var titleText: TextView
    private lateinit var selectAllBtn: TextView
    private lateinit var manageBtn: TextView
    private lateinit var headerCover: ImageView
    private lateinit var headerTitle: TextView
    private lateinit var headerMeta: TextView
    private lateinit var sortChipText: TextView
    private lateinit var sortChip: LinearLayout
    private var sortIconView: SortOrderIconView? = null
    private lateinit var listContainer: LinearLayout
    private lateinit var batchBar: LinearLayout
    private lateinit var batchCountText: TextView
    private lateinit var batchDeleteBtn: TextView

    private var currentTasks: List<DownloadManager.Task> = emptyList()

    private val listener: (List<DownloadManager.Task>) -> Unit = { all ->
        runOnUiThread { refresh(all) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Ds.applySystemBars(this)
        PlayerSettingsStore.applyHomeOrientation(this)
        window.setBackgroundDrawable(ColorDrawable(Ds.colors.bg))
        DownloadManager.init(this)

        groupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        mediaType = intent.getIntExtra(EXTRA_MEDIA_TYPE, DownloadManager.TYPE_VIDEO)
        if (groupId.isBlank()) {
            Toast.makeText(this, "合集信息缺失", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(buildUi())
        refresh(DownloadManager.snapshot())
    }

    override fun onStart() {
        super.onStart()
        DownloadManager.addListener(listener)
        refresh(DownloadManager.snapshot())
    }

    override fun onStop() {
        DownloadManager.removeListener(listener)
        super.onStop()
    }

    private fun buildUi(): View {
        val c = Ds.colors
        val root = FrameLayout(this).apply { setBackgroundColor(c.bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── 顶栏 ──
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.Space.sm), dp(Ds.Space.sm), dp(Ds.Space.lg), dp(Ds.Space.sm))
        }
        topBar.addView(Ds.backIcon(this, c.text) { handleBack() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        titleText = TextView(this).apply {
            text = if (mediaType == DownloadManager.TYPE_AUDIO) "音频合集" else "视频合集"
            textSize = 18f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topBar.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
        })

        selectAllBtn = TextView(this).apply {
            text = "全选"
            textSize = 12.5f
            typeface = Ds.body(bold = true)
            setTextColor(c.accent)
            background = Ds.pill(this@DownloadSeasonActivity, c.accentSoft)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { toggleSelectAll() }
        }
        topBar.addView(selectAllBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8)
        })

        manageBtn = TextView(this).apply {
            text = "批量管理"
            textSize = 12.5f
            typeface = Ds.body(bold = true)
            setTextColor(c.muted)
            background = Ds.pill(this@DownloadSeasonActivity, c.surfaceRaised, c.lineSoft)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { toggleSelectionMode(!selectionMode) }
        }
        topBar.addView(manageBtn)
        col.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(View(this).apply { setBackgroundColor(c.lineSoft) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)))

        // ── 合集头卡 + 排序条 ──
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(this@DownloadSeasonActivity, c.surface, c.lineSoft, Ds.Radius.card)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val coverHost = FrameLayout(this).apply {
            setBackgroundColor(c.cover)
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        headerCover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        coverHost.addView(headerCover, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        headerCard.addView(coverHost, LinearLayout.LayoutParams(dp(92), dp(58)))

        val headerInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerTitle = TextView(this).apply {
            textSize = 15f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        headerMeta = TextView(this).apply {
            textSize = 12f
            fontFeatureSettings = "tnum"
            setTextColor(c.muted)
        }
        headerInfo.addView(headerTitle)
        headerInfo.addView(headerMeta, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        headerCard.addView(headerInfo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12)
        })
        col.addView(headerCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(12)
        })

        // 排序工具条
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        toolbar.addView(TextView(this).apply {
            text = "已缓存分集"
            textSize = 13f
            typeface = Ds.body(bold = true)
            setTextColor(c.muted)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val sortBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = sortChipBackground(sortOrder)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { showSortSheet() }
        }
        sortChip = sortBtn
        val sortIcon = Ds.sortOrderIcon(this, sortOrder, sortTint(sortOrder)) as SortOrderIconView
        sortIconView = sortIcon
        sortBtn.addView(sortIcon,
            LinearLayout.LayoutParams(dp(13), dp(13)).apply { rightMargin = dp(4) })
        sortChipText = TextView(this).apply {
            text = sortOrder.seasonLabel()
            textSize = 12f
            typeface = Ds.body(bold = true)
            setTextColor(sortTint(sortOrder))
        }
        sortBtn.addView(sortChipText)
        toolbar.addView(sortBtn)
        col.addView(toolbar)

        // 列表滚动区
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(88))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listContainer)
        }
        col.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val maxW = LayoutMode.centeredMaxWidthPx(this, 860)
        val colLp = if (LayoutMode.isTablet) {
            FrameLayout.LayoutParams(
                maxW,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL
            )
        } else {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(col, colLp)

        // 底部批量删除栏
        batchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(c.surface)
            setPadding(dp(16), dp(10), dp(16), dp(14))
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }
        batchCountText = TextView(this).apply {
            text = "已选 0 集"
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(c.text)
        }
        batchBar.addView(batchCountText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        batchDeleteBtn = TextView(this).apply {
            text = "删除所选"
            textSize = 13.5f
            typeface = Ds.body(bold = true)
            setTextColor(Color.WHITE)
            background = Ds.pill(this@DownloadSeasonActivity, c.danger)
            setPadding(dp(20), dp(9), dp(20), dp(9))
            isClickable = true
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener { confirmDeleteSelected() }
        }
        batchBar.addView(batchDeleteBtn)
        root.addView(batchBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        return root
    }

    private fun refresh(all: List<DownloadManager.Task> = DownloadManager.snapshot()) {
        val raw = all.filter { it.groupId == groupId && it.type == mediaType }
        currentTasks = DownloadManager.sortTasks(raw, sortOrder)

        val validIds = currentTasks.mapTo(HashSet()) { it.id }
        selectedTaskIds.retainAll(validIds)

        if (currentTasks.isEmpty()) {
            finish()
            return
        }

        val first = currentTasks.first()
        val gTitle = first.groupTitle.ifBlank { first.title }
        headerTitle.text = gTitle
        val doneCount = currentTasks.count { it.state == DownloadManager.STATE_COMPLETED }
        val totalBytes = currentTasks.sumOf {
            if (it.state == DownloadManager.STATE_COMPLETED) DownloadManager.taskDiskBytes(it)
            else it.bytesDone.coerceAtLeast(0L)
        }
        headerMeta.text = "共 ${currentTasks.size} 集（已下载 $doneCount 集） · ${DownloadUiShared.formatBytes(totalBytes)}"
        DownloadUiShared.loadCoverUrl(headerCover, first.groupCoverUrl.ifBlank { first.coverUrl }, first.coverPath)

        renderRows()
        updateSelectionChrome()
    }

    private fun renderRows() {
        listContainer.removeAllViews()

        val rowHeight = dp(86)

        for (task in currentTasks) {
            val isSelected = task.id in selectedTaskIds
            val contentView = DownloadUiShared.buildSingleTaskCardContent(
                context = this,
                task = task,
                selectionMode = selectionMode,
                isSelected = isSelected,
                onToggleSelect = { toggleTaskSelected(task.id) },
                onActionClick = { handleTaskAction(task) },
                onBodyClick = { openTask(task) },
                onLongClick = { showTaskOptions(task) }
            )
            listContainer.addView(contentView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowHeight).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun handleBack() {
        if (selectionMode) {
            toggleSelectionMode(false)
            return
        }
        finish()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    private fun showTaskOptions(task: DownloadManager.Task) {
        DownloadUiShared.showEntryLongPressMenu(
            context = this,
            title = task.title,
            onRename = {
                DownloadUiShared.showRenameDialog(
                    context = this,
                    dialogTitle = "重命名分集",
                    currentName = task.title
                ) { nextName ->
                    DownloadManager.rename(task.id, nextName)
                    Toast.makeText(this, "已重命名", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { confirmDeleteSingle(task) }
        )
    }

    private fun toggleSelectionMode(enable: Boolean) {
        selectionMode = enable
        if (!enable) selectedTaskIds.clear()
        renderRows()
        updateSelectionChrome()
    }

    private fun toggleTaskSelected(id: Long) {
        if (id in selectedTaskIds) selectedTaskIds.remove(id) else selectedTaskIds.add(id)
        renderRows()
        updateSelectionChrome()
    }

    private fun toggleSelectAll() {
        val allSelected = currentTasks.isNotEmpty() && currentTasks.all { it.id in selectedTaskIds }
        if (allSelected) selectedTaskIds.clear()
        else currentTasks.forEach { selectedTaskIds.add(it.id) }
        renderRows()
        updateSelectionChrome()
    }

    private fun updateSelectionChrome() {
        val c = Ds.colors
        val count = selectedTaskIds.size
        if (selectionMode) {
            manageBtn.text = "完成"
            manageBtn.setTextColor(c.onAccent)
            manageBtn.background = Ds.pill(this, c.accent)
            selectAllBtn.visibility = View.VISIBLE
            val allSelected = currentTasks.isNotEmpty() && count == currentTasks.size
            selectAllBtn.text = if (allSelected) "取消全选" else "全选"
            batchBar.visibility = View.VISIBLE
            batchCountText.text = "已选 $count 集"
            batchDeleteBtn.alpha = if (count > 0) 1f else 0.45f
            batchDeleteBtn.isEnabled = count > 0
        } else {
            manageBtn.text = "批量管理"
            manageBtn.setTextColor(c.muted)
            manageBtn.background = Ds.pill(this, c.surfaceRaised, c.lineSoft)
            selectAllBtn.visibility = View.GONE
            batchBar.visibility = View.GONE
        }
    }

    private fun confirmDeleteSingle(task: DownloadManager.Task) {
        DownloadUiShared.showConfirmDeleteDialog(
            this,
            "确认删除分集",
            "确定要删除「${task.title}」的本地缓存文件吗？删除后无法恢复。"
        ) {
            DownloadManager.delete(task.id)
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSelected() {
        if (selectedTaskIds.isEmpty()) return
        val ids = selectedTaskIds.toList()
        DownloadUiShared.showConfirmDeleteDialog(
            this,
            "确认批量删除",
            "确定要删除所选 ${ids.size} 集的本地缓存文件吗？删除后无法恢复。"
        ) {
            DownloadManager.deleteMany(ids)
            selectedTaskIds.clear()
            selectionMode = false
            Toast.makeText(this, "已删除 ${ids.size} 集", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSortSheet() {
        val options = listOf(
            DownloadManager.SeasonSortOrder.DEFAULT to "默认排序",
            DownloadManager.SeasonSortOrder.OLDEST_FIRST to "发布时间从旧到新",
            DownloadManager.SeasonSortOrder.NEWEST_FIRST to "发布时间从新到旧"
        )
        val c = Ds.colors
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.attributes?.gravity = Gravity.BOTTOM

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@DownloadSeasonActivity, c.surface, c.lineSoft, Ds.Radius.dialog)
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }
        root.addView(TextView(this).apply {
            text = "分集排序"
            textSize = 16f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        options.forEach { (order, label) ->
            val selected = sortOrder == order
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(50)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = Ds.card(
                    this@DownloadSeasonActivity,
                    if (selected) c.accentSoft else Color.TRANSPARENT,
                    null,
                    Ds.Radius.control,
                    0
                )
                isClickable = true
                setOnClickListener {
                    sortOrder = order
                    sortChipText.text = sortLabel(order)
                    sortChipText.setTextColor(sortTint(order))
                    sortIconView?.update(order, sortTint(order))
                    sortChip.background = sortChipBackground(order)
                    refresh()
                    dialog.dismiss()
                }
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
                typeface = Ds.body(bold = selected)
                setTextColor(if (selected) c.accent else c.text)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) {
                row.addView(Ds.checkIcon(this, c.accent), LinearLayout.LayoutParams(dp(16), dp(16)))
            }
            root.addView(row)
        }
        dialog.setContentView(root)
        dialog.show()
        LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 460, phoneMarginDp = 24)
    }

    private fun sortLabel(order: DownloadManager.SeasonSortOrder): String = order.seasonLabel()

    private fun sortTint(order: DownloadManager.SeasonSortOrder): Int =
        if (order == DownloadManager.SeasonSortOrder.DEFAULT) Ds.colors.muted else Ds.colors.accent

    private fun sortChipBackground(order: DownloadManager.SeasonSortOrder) =
        if (order == DownloadManager.SeasonSortOrder.DEFAULT) {
            Ds.pill(this, Ds.colors.surfaceRaised, Ds.colors.lineSoft)
        } else {
            Ds.pill(this, Ds.colors.accentSoft, Ds.colors.accent)
        }

    private fun handleTaskAction(task: DownloadManager.Task) {
        when (task.state) {
            DownloadManager.STATE_RUNNING, DownloadManager.STATE_QUEUED -> DownloadManager.pause(task.id)
            DownloadManager.STATE_PAUSED -> DownloadManager.resume(task.id)
            DownloadManager.STATE_FAILED -> DownloadManager.retry(task.id)
        }
    }

    private fun openTask(task: DownloadManager.Task) {
        when (task.state) {
            DownloadManager.STATE_COMPLETED -> {
                if (!task.playable()) {
                    Toast.makeText(this, "本地文件已丢失，可重新下载", Toast.LENGTH_SHORT).show()
                    return
                }
                if (task.type == DownloadManager.TYPE_AUDIO) {
                    AudioPlayerActivity.open(this, task.id, sortOrder.name)
                } else {
                    startActivity(Intent(this, VideoDetailActivity::class.java).apply {
                        putExtra(VideoDetailActivity.EXTRA_LOCAL_TASK_ID, task.id)
                        putExtra(VideoDetailActivity.EXTRA_LOCAL_SORT_ORDER, sortOrder.name)
                    })
                }
            }
            DownloadManager.STATE_RUNNING, DownloadManager.STATE_QUEUED -> {
                Toast.makeText(this, "正在下载中，完成后即可离线播放", Toast.LENGTH_SHORT).show()
            }
            DownloadManager.STATE_PAUSED -> {
                DownloadManager.resume(task.id)
                Toast.makeText(this, "已继续下载", Toast.LENGTH_SHORT).show()
            }
            DownloadManager.STATE_FAILED -> {
                DownloadManager.retry(task.id)
                Toast.makeText(this, "已重试下载", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_MEDIA_TYPE = "media_type"
    }
}
