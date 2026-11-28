package com.yuilittle.bili

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object DownloadUiShared {

    fun showStorageChoice(context: Context, onChanged: () -> Unit) {
        val current = DownloadManager.defaultDestination(context)
        val c = Ds.colors
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setCanceledOnTouchOutside(true)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(context, c.surface, c.lineSoft, Ds.Radius.dialog)
            setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(16))
        }
        root.addView(TextView(context).apply {
            text = "新下载保存位置"
            textSize = 17f
            typeface = Ds.display(true)
            setTextColor(c.text)
        })
        root.addView(TextView(context).apply {
            text = "仅影响新任务，已有下载保持原位置"
            textSize = 12.5f
            setTextColor(c.muted)
            setLineSpacing(context.dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8); bottomMargin = context.dp(14) })
        for (destination in listOf(DownloadDestination.PRIVATE, DownloadDestination.LIBRARY)) {
            val selected = current == destination
            val title = if (destination == DownloadDestination.PRIVATE) "应用内" else "系统媒体库"
            val subtitle = if (destination == DownloadDestination.PRIVATE) "在离线缓存中播放和管理" else "视频保存到相册，音频保存到音乐库"
            val option = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(64)
                setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
                background = Ds.card(context, if (selected) c.accentSoft else c.surfaceRaised,
                    if (selected) c.accent else c.lineSoft, Ds.Radius.control)
                isSelected = selected
                isFocusable = true
                contentDescription = "$title，$subtitle"
                setOnClickListener {
                    dialog.dismiss()
                    val select = {
                        DownloadManager.setDefaultDestination(context, destination)
                        onChanged()
                    }
                    if (destination == DownloadDestination.LIBRARY) DownloadStoragePermission.withAccess(context, select) else select()
                }
                Ds.bindPressFeedback(this, 0.97f)
            }
            val text = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(TextView(context).apply {
                    this.text = title
                    textSize = 14.5f
                    typeface = Ds.body(true)
                    setTextColor(if (selected) c.accent else c.text)
                })
                addView(TextView(context).apply {
                    this.text = subtitle
                    textSize = 12f
                    setTextColor(c.muted)
                    setLineSpacing(context.dp(2).toFloat(), 1f)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
            }
            option.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            option.addView(Ds.checkIcon(context, c.accent).apply {
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
            }, LinearLayout.LayoutParams(context.dp(18), context.dp(18)).apply { leftMargin = context.dp(12) })
            root.addView(option, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = context.dp(8)
            })
        }
        root.addView(TextView(context).apply {
            text = "取消"
            textSize = 13.5f
            typeface = Ds.body(true)
            setTextColor(c.muted)
            gravity = Gravity.CENTER
            minimumHeight = context.dp(48)
            setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
            Ds.bindPressFeedback(this, 0.96f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
        val scroll = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        dialog.setContentView(scroll)
        dialog.show()
        val visible = Rect()
        dialog.window?.decorView?.getWindowVisibleDisplayFrame(visible)
        val width = minOf(LayoutMode.dialogWidthPx(context, maxTabletDp = 420),
            (visible.width() - context.dp(24)).coerceAtLeast(1))
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val availableHeight = (visible.height() - context.dp(32)).coerceAtLeast(1)
        dialog.window?.setLayout(width, minOf(root.measuredHeight, availableHeight))
    }

    fun taskAction(context: Context, task: DownloadManager.Task) {
        when (task.state) {
            DownloadManager.STATE_RUNNING, DownloadManager.STATE_QUEUED -> DownloadManager.pause(task.id)
            DownloadManager.STATE_PAUSED, DownloadManager.STATE_FAILED -> {
                val resume = {
                    if (task.state == DownloadManager.STATE_PAUSED) DownloadManager.resume(task.id) else DownloadManager.retry(task.id)
                }
                if (task.destination == DownloadDestination.LIBRARY) DownloadStoragePermission.withAccess(context, resume) else resume()
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        if (safe < 1024L) return "${safe}B"
        val kb = safe / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1fMB", mb)
        return String.format(Locale.US, "%.2fGB", mb / 1024.0)
    }

    fun formatDurationMs(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun formatDateTimeMs(epochMs: Long): String {
        if (epochMs <= 0L) return "刚刚"
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(epochMs))
    }

    fun loadTaskCover(iv: ImageView, task: DownloadManager.Task) {
        val local = task.coverPath
        if (local.isNotBlank() && File(local).exists()) {
            val remote = task.coverUrl
            CoverLoader.loadLocal(iv, local, iv.dp(120)) { target ->
                if (remote.isNotBlank()) CoverLoader.load(target, remote + "@400w_250h.webp")
            }
        } else if (task.coverUrl.isNotBlank()) {
            CoverLoader.load(iv, task.coverUrl + "@400w_250h.webp")
        } else {
            CoverLoader.clear(iv)
        }
    }

    fun loadCoverUrl(iv: ImageView, url: String, localPath: String = "") {
        if (localPath.isNotBlank() && File(localPath).exists()) {
            CoverLoader.loadLocal(iv, localPath, iv.dp(120)) { target ->
                if (url.isNotBlank()) CoverLoader.load(target, url + "@400w_250h.webp")
            }
        } else if (url.isNotBlank()) {
            CoverLoader.load(iv, url + "@400w_250h.webp")
        } else {
            CoverLoader.clear(iv)
        }
    }

    fun buildSingleTaskCardContent(
        context: Context,
        task: DownloadManager.Task,
        selectionMode: Boolean,
        isSelected: Boolean,
        onToggleSelect: () -> Unit,
        onActionClick: () -> Unit,
        onBodyClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ): LinearLayout {
        val c = Ds.colors
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (selectionMode && isSelected) {
                Ds.card(context, c.accentSoft, c.accent, Ds.Radius.card)
            } else {
                Ds.card(context, c.surface, c.lineSoft, Ds.Radius.card)
            }
            setPadding(context.dp(10), context.dp(10), context.dp(12), context.dp(10))
            isClickable = true
            isLongClickable = onLongClick != null
            Ds.bindPressFeedback(this, 0.985f)
            setOnClickListener {
                if (selectionMode) onToggleSelect() else onBodyClick()
            }
            if (onLongClick != null) {
                setOnLongClickListener {
                    if (!selectionMode) {
                        onLongClick()
                        true
                    } else false
                }
            }
        }

        if (selectionMode) {
            row.addView(CheckBox(context).apply {
                isChecked = isSelected
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(-android.R.attr.state_checked),
                        intArrayOf(android.R.attr.state_checked)
                    ),
                    intArrayOf(c.muted, c.accent)
                )
            }, LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply {
                rightMargin = context.dp(6)
            })
        }

        // 左侧封面（时长角标内嵌于封面右下角）
        val thumbHost = FrameLayout(context).apply {
            setBackgroundColor(c.cover)
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        val thumb = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumbHost.addView(thumb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        loadTaskCover(thumb, task)
        if (task.durationMs > 0L) {
            thumbHost.addView(TextView(context).apply {
                text = formatDurationMs(task.durationMs)
                textSize = 9.5f
                typeface = Ds.body(bold = true)
                fontFeatureSettings = "tnum"
                setTextColor(Color.WHITE)
                background = Ds.card(context, 0xAD000000.toInt(), null, 4, 0)
                setPadding(context.dp(5), context.dp(1), context.dp(5), context.dp(1))
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = context.dp(4); bottomMargin = context.dp(4)
            })
        }
        row.addView(thumbHost, LinearLayout.LayoutParams(context.dp(106), context.dp(64)))

        // 右侧：标题 + 下载日期 & 大小（或实时下载进度）
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        infoCol.addView(TextView(context).apply {
            text = task.title.ifBlank { if (task.type == DownloadManager.TYPE_AUDIO) "未命名音频" else "未命名视频" }
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(c.text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(context.dp(2).toFloat(), 1f)
        })

        val sizeText = formatBytes(
            if (task.state == DownloadManager.STATE_COMPLETED) DownloadManager.taskDiskBytes(task)
            else task.bytesTotal.takeIf { it > 0L } ?: task.bytesDone
        )
        val dateText = formatDateTimeMs(
            task.finishedAt.takeIf { it > 0L } ?: task.createdAt
        )
        val statusPair = buildTaskStatusMeta(context, task, dateText, sizeText)

        if (task.state != DownloadManager.STATE_COMPLETED) {
            val bar = MiniProgressBar(context).apply {
                setProgress(DownloadManager.computeDownloadProgress(task.bytesDone, task.bytesTotal))
            }
            infoCol.addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, context.dp(3)).apply {
                topMargin = context.dp(6)
            })
        }

        val metaView = TextView(context).apply {
            text = statusPair.first
            textSize = 11.5f
            fontFeatureSettings = "tnum"
            setTextColor(statusPair.second)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        infoCol.addView(metaView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (task.state == DownloadManager.STATE_COMPLETED) context.dp(6) else context.dp(4)
        })

        row.addView(infoCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = context.dp(11)
        })

        if (!selectionMode && task.state != DownloadManager.STATE_COMPLETED) {
            val actionIcon = buildStateActionView(context, task, onActionClick)
            row.addView(actionIcon, LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply {
                leftMargin = context.dp(6)
            })
        }
        return row
    }

    fun buildTaskStatusMeta(
        context: Context,
        task: DownloadManager.Task,
        dateText: String,
        sizeText: String
    ): Pair<String, Int> {
        val c = Ds.colors
        return when (task.state) {
            DownloadManager.STATE_RUNNING -> {
                if (task.destination == DownloadDestination.LIBRARY && task.stage != null) {
                    return (if (task.type == DownloadManager.TYPE_AUDIO) "正在保存到音乐库" else "正在保存到系统相册") to c.accent
                }
                val pct = if (task.bytesTotal > 0L) {
                    "${(DownloadManager.computeDownloadProgress(task.bytesDone, task.bytesTotal) * 100).toInt()}% · "
                } else ""
                val spd = formatBytes(task.speedBytes)
                "下载中 $pct${formatBytes(task.bytesDone)} · $spd/s" to c.accent
            }
            DownloadManager.STATE_QUEUED -> "等待下载 · $dateText" to c.muted
            DownloadManager.STATE_PAUSED -> "已暂停 · ${formatBytes(task.bytesDone)}" to c.muted
            DownloadManager.STATE_FAILED -> {
                val err = task.error.takeIf { it.isNotBlank() }?.let { " · ${it.take(12)}" } ?: ""
                "下载失败$err" to c.danger
            }
            else -> {
                val location = if (task.destination == DownloadDestination.LIBRARY) {
                    if (task.type == DownloadManager.TYPE_AUDIO) "音乐库" else "相册"
                } else "应用内"
                "$location · $sizeText · $dateText" to c.muted
            }
        }
    }

    /** 合集卡片：前方采用图片堆叠效果并在图片居中位置显示已下载（或总）数量，右侧展示标题、日期与总占用。 */
    fun buildSeasonGroupCardContent(
        context: Context,
        groupTasks: List<DownloadManager.Task>,
        selectionMode: Boolean,
        isSelected: Boolean,
        onToggleSelect: () -> Unit,
        onBodyClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ): LinearLayout {
        val c = Ds.colors
        val first = groupTasks.first()
        val title = first.groupTitle.ifBlank { first.title }
        val completedCount = groupTasks.count { it.state == DownloadManager.STATE_COMPLETED }
        val totalSize = groupTasks.sumOf {
            if (it.state == DownloadManager.STATE_COMPLETED) DownloadManager.taskDiskBytes(it)
            else it.bytesDone.coerceAtLeast(0L)
        }
        val latestTime = groupTasks.maxOfOrNull { it.finishedAt.takeIf { f -> f > 0L } ?: it.createdAt } ?: 0L

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (selectionMode && isSelected) {
                Ds.card(context, c.accentSoft, c.accent, Ds.Radius.card)
            } else {
                Ds.card(context, c.surface, c.lineSoft, Ds.Radius.card)
            }
            setPadding(context.dp(10), context.dp(10), context.dp(12), context.dp(10))
            isClickable = true
            isLongClickable = onLongClick != null
            Ds.bindPressFeedback(this, 0.985f)
            setOnClickListener {
                if (selectionMode) onToggleSelect() else onBodyClick()
            }
            if (onLongClick != null) {
                setOnLongClickListener {
                    if (!selectionMode) {
                        onLongClick()
                        true
                    } else false
                }
            }
        }

        if (selectionMode) {
            row.addView(CheckBox(context).apply {
                isChecked = isSelected
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(-android.R.attr.state_checked),
                        intArrayOf(android.R.attr.state_checked)
                    ),
                    intArrayOf(c.muted, c.accent)
                )
            }, LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply {
                rightMargin = context.dp(6)
            })
        }

        // 左侧图片堆叠效果 + 居中数量角标
        val stackHost = FrameLayout(context)
        val backLayer2 = FrameLayout(context).apply {
            background = Ds.card(context, c.surfaceRaised, c.line, Ds.Radius.chip)
        }
        val backLayer1 = FrameLayout(context).apply {
            background = Ds.card(context, c.cover, c.line, Ds.Radius.chip)
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        val secondThumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.65f
        }
        val secondTask = groupTasks.getOrNull(1)
        if (secondTask != null) {
            loadCoverUrl(secondThumb, secondTask.coverUrl.ifBlank { first.groupCoverUrl }, secondTask.coverPath)
        }
        backLayer1.addView(secondThumb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val frontLayer = FrameLayout(context).apply {
            background = Ds.card(context, c.cover, c.surface, Ds.Radius.chip)
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        val mainImg = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        loadCoverUrl(mainImg, first.groupCoverUrl.ifBlank { first.coverUrl }, first.coverPath)
        frontLayer.addView(mainImg, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 居中半透明暗色遮罩保证居中数字清晰可辨
        frontLayer.addView(View(context).apply { setBackgroundColor(0x5A000000) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frontLayer.addView(TextView(context).apply {
            val shownCount = if (completedCount > 0) completedCount else groupTasks.size
            text = "$shownCount"
            textSize = 17f
            typeface = Ds.display(bold = true)
            fontFeatureSettings = "tnum"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(context.dp(3).toFloat(), 0f, 1f, 0x99000000.toInt())
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        stackHost.addView(backLayer2, FrameLayout.LayoutParams(context.dp(92), context.dp(56), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = context.dp(12)
        })
        stackHost.addView(backLayer1, FrameLayout.LayoutParams(context.dp(96), context.dp(58), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = context.dp(6); bottomMargin = context.dp(3)
        })
        stackHost.addView(frontLayer, FrameLayout.LayoutParams(context.dp(96), context.dp(58), Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = context.dp(6)
        })
        row.addView(stackHost, LinearLayout.LayoutParams(context.dp(106), context.dp(64)))

        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        infoCol.addView(TextView(context).apply {
            text = buildSeasonTitle(context, title, 14f * context.resources.displayMetrics.scaledDensity)
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(c.text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(context.dp(1).toFloat(), 1f)
        })
        val isAllDone = completedCount == groupTasks.size
        val subText = if (isAllDone) {
            "${formatDateTimeMs(latestTime)} · ${formatBytes(totalSize)}"
        } else {
            "下载中 $completedCount/${groupTasks.size} · ${formatBytes(totalSize)}"
        }
        infoCol.addView(TextView(context).apply {
            text = subText
            textSize = 11.5f
            fontFeatureSettings = "tnum"
            setTextColor(if (isAllDone) c.muted else c.accent)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(6)
        })
        row.addView(infoCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = context.dp(11); rightMargin = context.dp(4)
        })
        row.addView(Ds.chevronRightIcon(context, c.subtle), LinearLayout.LayoutParams(context.dp(14), context.dp(14)))
        return row
    }

    fun showConfirmDeleteDialog(
        context: Context,
        title: String,
        message: String,
        ids: List<Long>,
        onConfirm: () -> Unit
    ) {
        val records = ids.mapNotNull(DownloadManager::task)
        val hasLibrary = records.any { it.destination == DownloadDestination.LIBRARY }
        val hasLegacyCopies = records.any { it.galleryExports.isNotEmpty() }
        val c = Ds.colors
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(context, c.surface, c.lineSoft, Ds.Radius.dialog)
            setPadding(context.dp(20), dp(context, 18), context.dp(20), context.dp(16))
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(trashIcon(context, c.danger), LinearLayout.LayoutParams(context.dp(18), context.dp(18)))
        titleRow.addView(TextView(context).apply {
            text = title
            textSize = 17f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = context.dp(8)
        })
        root.addView(titleRow)

        root.addView(TextView(context).apply {
            text = buildString {
                append(message)
                if (hasLibrary) append("\n这些任务保存到系统相册或音乐库、仍可确认归属的成品也会删除。")
                if (hasLegacyCopies) append("\n旧版已导出的相册副本会保留。")
            }
            textSize = 13.5f
            setTextColor(c.muted)
            setLineSpacing(context.dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(12)
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(TextView(context).apply {
            text = "取消"
            textSize = 13.5f
            setTextColor(c.muted)
            gravity = Gravity.CENTER
            setPadding(context.dp(18), context.dp(9), context.dp(18), context.dp(9))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(context).apply {
            text = "确认删除"
            textSize = 13.5f
            typeface = Ds.body(bold = true)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ds.pill(context, c.danger)
            setPadding(context.dp(20), context.dp(9), context.dp(20), context.dp(9))
            isClickable = true
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener {
                dialog.dismiss()
                if (hasLibrary) DownloadStoragePermission.withAccess(context, onConfirm) else onConfirm()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = context.dp(6)
        })
        root.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(18)
        })

        dialog.setContentView(root)
        LayoutMode.applyDialogWidth(dialog, context, maxTabletDp = 420)
        dialog.show()
    }

    /** 长按选项底部弹窗（重命名 / 删除）。 */
    fun showEntryLongPressMenu(
        context: Context,
        title: String,
        onRename: () -> Unit,
        onDelete: () -> Unit
    ) {
        val c = Ds.colors
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.attributes?.gravity = Gravity.BOTTOM

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(context, c.surface, c.lineSoft, Ds.Radius.dialog)
            setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 14))
        }
        root.addView(TextView(context).apply {
            text = title
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(c.muted)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 10)
        })

        fun actionRow(label: String, textColor: Int, bgFill: Int, action: () -> Unit): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(context, 48)
                background = Ds.card(context, bgFill, null, Ds.Radius.control, 0)
                setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
                isClickable = true
                Ds.bindPressFeedback(this, 0.97f)
                addView(TextView(context).apply {
                    text = label
                    textSize = 14.5f
                    typeface = Ds.body(bold = true)
                    setTextColor(textColor)
                })
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
        }

        root.addView(actionRow("重命名", c.text, c.surfaceRaised, onRename),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 8)
            })
        root.addView(actionRow("删除下载文件", c.danger, c.dangerSoft, onDelete),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(root)
        dialog.show()
        LayoutMode.applyDialogWidth(dialog, context, maxTabletDp = 460, phoneMarginDp = 24)
    }

    /** 重命名输入弹窗（仅改展示标题，不改磁盘文件）。 */
    fun showRenameDialog(
        context: Context,
        dialogTitle: String,
        currentName: String,
        onConfirm: (String) -> Unit
    ) {
        val c = Ds.colors
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(context, c.surface, c.lineSoft, Ds.Radius.dialog)
            setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 16))
        }
        root.addView(TextView(context).apply {
            text = dialogTitle
            textSize = 16.5f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
        })
        val input = EditText(context).apply {
            setText(currentName)
            setSelection(currentName.length)
            textSize = 14.5f
            setTextColor(c.text)
            setHintTextColor(c.subtle)
            hint = "输入新名称"
            background = Ds.card(context, c.surfaceRaised, c.lineSoft, Ds.Radius.control)
            setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11))
            maxLines = 2
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 14)
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(TextView(context).apply {
            text = "取消"
            textSize = 13.5f
            setTextColor(c.muted)
            gravity = Gravity.CENTER
            setPadding(dp(context, 18), dp(context, 9), dp(context, 18), dp(context, 9))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(context).apply {
            text = "保存"
            textSize = 13.5f
            typeface = Ds.body(bold = true)
            setTextColor(c.onAccent)
            gravity = Gravity.CENTER
            background = Ds.pill(context, c.accent)
            setPadding(dp(context, 20), dp(context, 9), dp(context, 20), dp(context, 9))
            isClickable = true
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener {
                val next = input.text.toString().trim()
                if (next.isEmpty()) {
                    Toast.makeText(context, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onConfirm(next)
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(context, 6)
        })
        root.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 16)
        })

        dialog.setContentView(root)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        LayoutMode.applyDialogWidth(dialog, context, maxTabletDp = 420)
        dialog.show()
    }

    private fun buildStateActionView(context: Context, task: DownloadManager.Task, onActionClick: () -> Unit): View {
        val c = Ds.colors
        val label = when (task.state) {
            DownloadManager.STATE_RUNNING, DownloadManager.STATE_QUEUED -> "暂停"
            DownloadManager.STATE_PAUSED -> "继续"
            else -> "重试"
        }
        return TextView(context).apply {
            text = label
            textSize = 10.5f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            setTextColor(c.accent)
            background = Ds.pill(context, c.accentSoft)
            isClickable = true
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { onActionClick() }
        }
    }

    fun trashIcon(context: Context, color: Int): View = object : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.9f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            path.rewind()
            path.moveTo(w * 0.20f, h * 0.28f)
            path.lineTo(w * 0.80f, h * 0.28f)
            path.moveTo(w * 0.36f, h * 0.18f)
            path.lineTo(w * 0.64f, h * 0.18f)
            path.moveTo(w * 0.28f, h * 0.28f)
            path.lineTo(w * 0.33f, h * 0.84f)
            path.lineTo(w * 0.67f, h * 0.84f)
            path.lineTo(w * 0.72f, h * 0.28f)
            path.moveTo(w * 0.43f, h * 0.42f)
            path.lineTo(w * 0.44f, h * 0.70f)
            path.moveTo(w * 0.57f, h * 0.42f)
            path.lineTo(w * 0.56f, h * 0.70f)
            canvas.drawPath(path, paint)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 合集标题前的「合集」小徽标，随标题首行居中。 */
    private class SeasonBadgeSpan(
        private val fillColor: Int,
        private val textColor: Int,
        private val density: Float,
        private val textSizePx: Float
    ) : ReplacementSpan() {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            typeface = Ds.body(bold = true)
            textSize = textSizePx
            textAlign = Paint.Align.CENTER
        }
        private val padX = 5f * density
        private val padY = 1.5f * density
        private val rightGap = 5f * density
        private val radius = 5f * density
        private val rect = RectF()
        private val badgeWidth: Int = kotlin.math.ceil(textPaint.measureText("合集") + padX * 2f).toInt()

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: FontMetricsInt?): Int =
            badgeWidth + kotlin.math.ceil(rightGap).toInt()

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            val textFm = textPaint.fontMetrics
            val boxH = (textFm.descent - textFm.ascent) + padY * 2f
            val centerY = (top + bottom) / 2f
            val boxTop = centerY - boxH / 2f
            rect.set(x, boxTop, x + badgeWidth, boxTop + boxH)
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            val baseline = centerY - (textFm.ascent + textFm.descent) / 2f
            canvas.drawText("合集", rect.centerX(), baseline, textPaint)
        }
    }

    private fun buildSeasonTitle(context: Context, title: String, textSizePx: Float): CharSequence {
        val c = Ds.colors
        val badge = "\u00A0"
        val span = SpannableStringBuilder()
        span.append(badge)
        span.setSpan(
            SeasonBadgeSpan(c.accentSoft, c.accent, context.resources.displayMetrics.density, textSizePx),
            0, badge.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        span.append(title)
        return span
    }

    private class MiniProgressBar(context: Context) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ds.colors.lineSoft }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ds.colors.accent }
        private var progress = 0f

        fun setProgress(value: Float) {
            progress = value.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            val r = h / 2f
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), r, r, track)
            if (progress > 0.005f) {
                canvas.drawRoundRect(RectF(0f, 0f, width.toFloat() * progress, h), r, r, fill)
            }
        }
    }
}
