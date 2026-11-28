package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.Request
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 空间主页「动态」与空间搜索页「动态」共用的动态卡片渲染器：
 * - 支持置顶玫瑰粉徽标、纯文字/富文本表情、单图（按真实比例防裁切）/2图/4图/九宫格图（点击大图预览）、
 *   清爽视频子卡、单层转发嵌套（含原动态作者跳空间 & 失效提示）与未知类型只读降级。
 * - 支持关键词高亮（用于空间内搜索结果）与列表回收时精准清理图片任务。
 * - 严格按用户要求克制展示：时间右侧绝无三点菜单、无分享按钮、底部统计行只读。
 */
internal object SpaceDynamicCardUi {

    private const val MAX_TEXT_LINES = 5

    /** ListView setRecyclerListener 回调入口：递归取消卡片内所有 ImageView 的在途加载，避免滑快时串图和浪费流量。 */
    fun clearImages(view: View?) {
        if (view == null) return
        if (view is ImageView) {
            CoverLoader.clear(view)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearImages(view.getChildAt(i))
            }
        }
    }

    /** 根据动态视频封面构建 16:9 缩略图地址（适配卡片大图展示），仅在 /bfs/ CDN 上追加后缀且保留 query。 */
    internal fun buildDynamicVideoCoverThumbUrl(rawUrl: String): String {
        val raw = rawUrl.trim()
        if (raw.isEmpty()) return ""
        val qIdx = raw.indexOf('?')
        val basePart = if (qIdx >= 0) raw.substring(0, qIdx) else raw
        val queryPart = if (qIdx >= 0) raw.substring(qIdx) else ""
        val cleanPath = basePart.substringBefore('@')
        val isBiliCdn = cleanPath.contains("hdslb.com") ||
            cleanPath.contains("biliimg.com") ||
            cleanPath.contains("hdslb.net")
        if (!isBiliCdn || !cleanPath.contains("/bfs/")) return raw
        if (cleanPath.endsWith(".gif", ignoreCase = true)) return raw
        return "$cleanPath@720w_405h_1c.webp$queryPart"
    }

    /** 构造空间视频卡片 16:10 横排封面缩略图地址，仅在 /bfs/ CDN 上追加后缀且保留 query。 */
    internal fun buildSpaceVideoCoverThumbUrl(rawUrl: String): String {
        val raw = rawUrl.trim()
        if (raw.isEmpty()) return ""
        val qIdx = raw.indexOf('?')
        val basePart = if (qIdx >= 0) raw.substring(0, qIdx) else raw
        val queryPart = if (qIdx >= 0) raw.substring(qIdx) else ""
        val cleanPath = basePart.substringBefore('@')
        val isBiliCdn = cleanPath.contains("hdslb.com") ||
            cleanPath.contains("biliimg.com") ||
            cleanPath.contains("hdslb.net")
        if (!isBiliCdn || !cleanPath.contains("/bfs/")) return raw
        if (cleanPath.endsWith(".gif", ignoreCase = true)) return raw
        return "$cleanPath@480w_300h_1c.webp$queryPart"
    }

    /** 根据图片真实宽高构造 B 站 CDN 缩略图地址，长竖图保留完整宽度比例避免强制裁成横图，同时保留原始 query。 */
    internal fun buildDynamicImageThumbUrl(img: SpaceImage, singleMode: Boolean): String {
        val raw = img.url.trim()
        if (raw.isEmpty()) return ""
        val qIdx = raw.indexOf('?')
        val basePart = if (qIdx >= 0) raw.substring(0, qIdx) else raw
        val queryPart = if (qIdx >= 0) raw.substring(qIdx) else ""
        val cleanPath = basePart.substringBefore('@')
        val isBiliCdn = cleanPath.contains("hdslb.com") ||
            cleanPath.contains("biliimg.com") ||
            cleanPath.contains("hdslb.net")
        if (!isBiliCdn || !cleanPath.contains("/bfs/")) return raw
        if (cleanPath.endsWith(".gif", ignoreCase = true)) return raw
        if (!singleMode) return "$cleanPath@420w_420h_1c.webp$queryPart"
        val w = img.width ?: 0
        val h = img.height ?: 0
        val aspect = if (w > 0 && h > 0) h.toFloat() / w.toFloat() else 0.75f
        val suffix = when {
            aspect >= 1.5f -> "@720w.webp"
            aspect <= 0.6f -> "@800w.webp"
            else -> "@720w_720h.webp"
        }
        return "$cleanPath$suffix$queryPart"
    }

    fun createCard(
        activity: Activity,
        item: SpaceDynamic,
        highlightKeyword: String = "",
        cardWidthPx: Int = 0,
        cardMode: Boolean = false,
        onOpenDetail: ((SpaceDynamic) -> Unit)? = null,
        onOpenVideo: (bvid: String?, aid: Long?) -> Unit,
        onFeedback: ((String) -> Unit)? = null
    ): LinearLayout {
        val c = Ds.colors
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (cardMode) {
                background = Ds.card(activity, COLOR_CARD, c.lineSoft, Ds.Radius.card, Ds.hairline(activity))
                Ds.clipRounded(this, Ds.Radius.card)
            } else {
                setBackgroundColor(COLOR_CARD)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val effectiveContentW = if (cardWidthPx > 0) {
            (cardWidthPx - activity.dp(32)).coerceAtLeast(activity.dp(180))
        } else {
            (activity.resources.displayMetrics.widthPixels - activity.dp(32)).coerceAtLeast(activity.dp(180))
        }

        val isPureVideo = item.kind == DynamicKind.VIDEO && item.text.isBlank() &&
            item.title.isNullOrBlank() && item.images.isEmpty()
        val supportsDetail = item.kind != DynamicKind.LIVE && (
            item.supportsDetail ||
                (item.kind != DynamicKind.UNKNOWN && !isPureVideo && item.visible)
            )
        val openDetailAction: () -> Unit = {
            if (supportsDetail && onOpenDetail != null) {
                onOpenDetail(item)
            } else if (item.video != null) {
                onOpenVideo(item.video.bvid, item.video.aid)
            }
        }

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(13))
            if (supportsDetail || item.kind == DynamicKind.VIDEO) {
                isClickable = true
                setOnClickListener { openDetailAction() }
            }
        }

        // 1. 头部：头像 + 昵称/时间 + 置顶徽标（绝无三点菜单或分享入口）
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ds.circle(COLOR_SOFT_FILL)
            Ds.clipRounded(this, 999)
        }
        val faceUrl = item.authorFace?.takeIf { it.isNotBlank() }
        if (faceUrl != null) {
            CoverLoader.load(avatar, faceUrl, cropThumb = false, thumbEdge = 128, animate = false)
        }
        headerRow.addView(avatar, LinearLayout.LayoutParams(activity.dp(34), activity.dp(34)))

        val authorMeta = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(10), 0, activity.dp(8), 0)
        }
        authorMeta.addView(TextView(activity).apply {
            text = item.authorName?.ifBlank { null } ?: "哔哩哔哩用户"
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        })
        val timeText = formatDynamicTime(item.pubTs, item.pubTime)
        val kindTag = when (item.kind) {
            DynamicKind.VIDEO -> if (isPureVideo) "投稿了视频" else "发布了动态"
            // 开播动态正文已经是「xxx 开启了直播」，这里不再重复
            else -> ""
        }
        val subtitleLine = listOf(timeText, kindTag).filter { it.isNotBlank() }.joinToString(" · ")
        if (subtitleLine.isNotEmpty()) {
            authorMeta.addView(TextView(activity).apply {
                text = subtitleLine
                textSize = 11.5f
                typeface = Ds.body()
                setTextColor(Ds.colors.subtle)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = activity.dp(3) })
        }
        headerRow.addView(authorMeta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (item.isPinned) {
            headerRow.addView(TextView(activity).apply {
                text = "置顶"
                textSize = 10.5f
                typeface = Ds.body(bold = true)
                setTextColor(COLOR_ROSE)
                background = rounded(COLOR_ROSE_SOFT, activity.dp(6))
                setPadding(activity.dp(7), activity.dp(2), activity.dp(7), activity.dp(2))
                includeFontPadding = false
            })
        }
        body.addView(headerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 1.5 动态标题（部分图文专栏 OPUS 动态有标题）
        if (!item.title.isNullOrBlank()) {
            body.addView(TextView(activity).apply {
                text = applyKeywordHighlight(item.title, highlightKeyword)
                textSize = 15.5f
                typeface = Ds.display(bold = true)
                setTextColor(COLOR_INK)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(activity.dp(2f), 1f)
                includeFontPadding = false
                if (supportsDetail) {
                    isClickable = true
                    setOnClickListener { openDetailAction() }
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = activity.dp(10) })
        }

        // 2. 动态正文（支持富文本小表情、关键词高亮、长按复制、超 5 行展示“全文”并直接跳动态详情页）
        if (item.text.isNotBlank()) {
            addBodyTextWithDetailJump(
                activity = activity,
                parent = body,
                rawText = item.text,
                richNodes = item.richNodes,
                highlightKeyword = highlightKeyword,
                maxCollapsedLines = MAX_TEXT_LINES,
                textSizeSp = 14.5f,
                textColor = COLOR_INK,
                topMarginDp = 10,
                supportsDetail = supportsDetail,
                onOpenDetail = openDetailAction,
                onFeedback = onFeedback
            )
        }

        // 3. 主体区
        when (item.kind) {
            DynamicKind.TEXT -> Unit
            DynamicKind.IMAGE -> {
                if (item.images.isNotEmpty()) {
                    addImageGrid(activity, body, item.images, topMarginDp = 10, contentWidthPx = effectiveContentW, onFeedback = { msg ->
                        onFeedback?.invoke(msg)
                    })
                }
            }
            DynamicKind.VIDEO -> {
                item.video?.let { video ->
                    if (isPureVideo) {
                        addLargeVideoCard(
                            activity = activity,
                            parent = body,
                            video = video,
                            highlightKeyword = highlightKeyword,
                            topMarginDp = 10,
                            contentWidthPx = effectiveContentW,
                            onOpenVideo = onOpenVideo
                        )
                    } else {
                        // 图三：动态配文 + 引用视频子卡（点子卡直达视频播放页，点文字/评论/空白进独立动态详情页）
                        addVideoSubCard(
                            activity = activity,
                            parent = body,
                            video = video,
                            highlightKeyword = highlightKeyword,
                            topMarginDp = 10,
                            onOpenVideo = onOpenVideo
                        )
                    }
                }
            }
            DynamicKind.UNKNOWN -> {
                addUnknownNoticeBlock(activity, body, item, onOpenVideo)
            }
            DynamicKind.LIVE -> {
                val live = item.live
                if (live != null) {
                    addLiveCard(
                        activity = activity,
                        parent = body,
                        item = item,
                        live = live,
                        topMarginDp = 10,
                        contentWidthPx = effectiveContentW
                    )
                } else {
                    addUnknownNoticeBlock(activity, body, item, onOpenVideo)
                }
            }
        }

        // 4. 底部轻量统计行（彻底去除转发，仅保留评论与赞；支持的动态点底部评论区直接进入独立动态详情页）
        addStatsRow(activity, body, item.stats, supportsDetail, openDetailAction)

        card.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        // 卡片模式（平板多列卡片流）自带圆角描边和外间距，不额外追加灰底色分割条
        if (!cardMode) {
            card.addView(View(activity).apply {
                setBackgroundColor(COLOR_BACKGROUND)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dp(6)
            ))
        }

        return card
    }

    private fun addBodyTextWithDetailJump(
        activity: Activity,
        parent: LinearLayout,
        rawText: String,
        richNodes: List<RichNode>,
        highlightKeyword: String,
        maxCollapsedLines: Int,
        textSizeSp: Float,
        textColor: Int,
        topMarginDp: Int,
        supportsDetail: Boolean,
        onOpenDetail: () -> Unit,
        onFeedback: ((String) -> Unit)? = null
    ) {
        val bodyTv = TextView(activity).apply {
            textSize = textSizeSp
            typeface = Ds.body()
            setTextColor(textColor)
            setLineSpacing(activity.dp(3.5f), 1f)
            maxLines = maxCollapsedLines
            ellipsize = TextUtils.TruncateAt.END
            setText(
                buildDynamicSpannable(rawText, richNodes, highlightKeyword, activity.dp(17)),
                TextView.BufferType.SPANNABLE
            )
            attachSpaceEmoteSpans(this)
            if (supportsDetail) {
                isClickable = true
                setOnClickListener { onOpenDetail() }
            }
            isLongClickable = true
            setOnLongClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("动态内容", rawText))
                onFeedback?.invoke("已复制动态文案")
                true
            }
        }
        parent.addView(bodyTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(topMarginDp) })

        if (supportsDetail) {
            val fullTextBtn = TextView(activity).apply {
                text = "全文"
                textSize = 13.5f
                typeface = Ds.body(bold = true)
                setTextColor(COLOR_ROSE)
                setPadding(0, activity.dp(4), activity.dp(12), activity.dp(2))
                visibility = View.GONE
                isClickable = true
                Ds.bindPressFeedback(this, 0.94f)
                setOnClickListener { onOpenDetail() }
            }
            parent.addView(fullTextBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            bodyTv.post {
                val layout = bodyTv.layout ?: return@post
                val lineCount = layout.lineCount
                val truncated = lineCount >= maxCollapsedLines &&
                    (lineCount > maxCollapsedLines || layout.getEllipsisCount(lineCount - 1) > 0)
                if (truncated) fullTextBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun addImageGrid(
        activity: Activity,
        parent: LinearLayout,
        images: List<SpaceImage>,
        topMarginDp: Int,
        contentWidthPx: Int = 0,
        onFeedback: (String) -> Unit
    ) {
        val urls = images.map { it.url }.filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        val shown = images.take(9)
        val extraCount = (images.size - 9).coerceAtLeast(0)
        val gap = activity.dp(6)
        val contentWidth = if (contentWidthPx > 0) {
            contentWidthPx
        } else {
            (activity.resources.displayMetrics.widthPixels - activity.dp(32)).coerceAtLeast(activity.dp(180))
        }

        if (shown.size == 1) {
            val img = shown[0]
            val iw = img.width ?: 0
            val ih = img.height ?: 0
            val ratio = if (iw > 0 && ih > 0) ih.toFloat() / iw.toFloat() else 0.72f
            val isLong = ratio >= 2.0f
            var targetW: Int
            var targetH: Int
            when {
                isLong -> {
                    targetW = (contentWidth * 0.54f).toInt().coerceAtLeast(activity.dp(150))
                    targetH = activity.dp(248)
                }
                ratio > 1.15f -> {
                    targetW = (contentWidth * 0.64f).toInt().coerceAtLeast(activity.dp(160))
                    targetH = (targetW * ratio.coerceAtMost(1.38f)).toInt().coerceAtMost(activity.dp(290))
                }
                else -> {
                    targetW = contentWidth
                    targetH = (targetW * ratio.coerceIn(0.46f, 0.92f)).toInt().coerceAtLeast(activity.dp(135))
                }
            }
            // 平板横屏：单图最多占内容区 1/3，避免一张图横贯整屏。
            if (LayoutMode.isWideScreen(activity)) {
                val maxW = (contentWidth / 3f).toInt().coerceAtLeast(activity.dp(150))
                if (targetW > maxW) {
                    val scale = maxW.toFloat() / targetW
                    targetW = maxW
                    targetH = (targetH * scale).toInt().coerceAtLeast(activity.dp(110))
                }
            }

            val container = FrameLayout(activity).apply {
                background = rounded(COLOR_COVER, activity.dp(12))
                Ds.clipRounded(this, Ds.Radius.control)
                isClickable = true
                Ds.bindPressFeedback(this, 0.985f)
            }
            val iv = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(COLOR_COVER)
            }
            CoverLoader.load(iv, buildDynamicImageThumbUrl(img, singleMode = true), cropThumb = false, animate = false)
            container.addView(iv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                targetH
            ))
            if (isLong) {
                container.addView(TextView(activity).apply {
                    text = "长图"
                    textSize = 10f
                    typeface = Ds.body(bold = true)
                    setTextColor(0xFFFFFFFF.toInt())
                    background = rounded(0x99000000.toInt(), activity.dp(6))
                    setPadding(activity.dp(7), activity.dp(3), activity.dp(7), activity.dp(3))
                    includeFontPadding = false
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).apply { bottomMargin = activity.dp(8); rightMargin = activity.dp(8) })
            }
            container.setOnClickListener {
                ImagePreviewDialog.show(activity, urls, 0, onFeedback = onFeedback)
            }
            parent.addView(container, LinearLayout.LayoutParams(
                targetW,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = activity.dp(topMarginDp) })
            return
        }

        val cols = if (shown.size == 2 || shown.size == 4) 2 else 3
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val rows = shown.chunked(cols)
        val cellSize = ((contentWidth - gap * (cols - 1)) / cols).coerceAtLeast(activity.dp(72))
        val cellHeight = if (cols == 2) (cellSize * 0.85f).toInt() else cellSize

        rows.forEachIndexed { rIdx, rowItems ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (cIdx in 0 until cols) {
                if (cIdx >= rowItems.size) {
                    row.addView(View(activity), LinearLayout.LayoutParams(0, cellHeight, 1f).apply {
                        if (cIdx < cols - 1) rightMargin = gap
                    })
                    continue
                }
                val itemIndex = rIdx * cols + cIdx
                val img = rowItems[cIdx]
                val cell = FrameLayout(activity).apply {
                    setBackgroundColor(COLOR_COVER)
                    Ds.clipRounded(this, Ds.Radius.chip)
                    isClickable = true
                    Ds.bindPressFeedback(this, 0.96f)
                }
                val iv = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(COLOR_COVER)
                }
                CoverLoader.load(iv, buildDynamicImageThumbUrl(img, singleMode = false), cropThumb = true, animate = false)
                cell.addView(iv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                if (extraCount > 0 && itemIndex == shown.lastIndex) {
                    cell.addView(TextView(activity).apply {
                        text = "+$extraCount"
                        textSize = 18f
                        typeface = Ds.display(bold = true)
                        setTextColor(0xFFFFFFFF.toInt())
                        gravity = Gravity.CENTER
                        setBackgroundColor(0x73000000)
                    }, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                }
                cell.setOnClickListener {
                    ImagePreviewDialog.show(activity, urls, itemIndex, onFeedback = onFeedback)
                }
                row.addView(cell, LinearLayout.LayoutParams(0, cellHeight, 1f).apply {
                    if (cIdx < cols - 1) rightMargin = gap
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (rIdx > 0) topMargin = gap
            })
        }
        parent.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(topMarginDp) })
    }

    /**
     * 图一风格：纯视频投稿动态大卡片（16:9 圆角封面、底部渐隐暗角、左下角「播放 / 弹幕 / 时长」、封面下方视频标题）。
     */
    private fun addLargeVideoCard(
        activity: Activity,
        parent: LinearLayout,
        video: SpaceVideo,
        highlightKeyword: String,
        topMarginDp: Int,
        contentWidthPx: Int = 0,
        onOpenVideo: (bvid: String?, aid: Long?) -> Unit
    ) {
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            Ds.bindPressFeedback(this, 0.985f)
            setOnClickListener { onOpenVideo(video.bvid, video.aid) }
        }
        val contentWidth = if (contentWidthPx > 0) {
            contentWidthPx
        } else {
            (activity.resources.displayMetrics.widthPixels - activity.dp(32)).coerceAtLeast(activity.dp(200))
        }
        // 平板横屏用正常大小的 16:9 缩略图（内容区 1/3 宽），不再把封面拉满整行。
        val coverW = if (LayoutMode.isWideScreen(activity)) {
            (contentWidth / 3f).toInt().coerceAtLeast(activity.dp(180))
        } else contentWidth
        val coverH = (coverW * 9f / 16f).toInt().coerceIn(activity.dp(116), activity.dp(260))

        val coverFrame = FrameLayout(activity).apply {
            setBackgroundColor(COLOR_COVER)
            Ds.clipRounded(this, Ds.Radius.control)
        }
        val cover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_COVER)
        }
        if (!video.cover.isNullOrBlank()) {
            CoverLoader.load(
                cover,
                buildDynamicVideoCoverThumbUrl(video.cover),
                cropThumb = false,
                animate = false
            )
        }
        coverFrame.addView(cover, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        coverFrame.addView(
            View(activity).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00000000, 0x8A000000.toInt())
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                activity.dp(46),
                Gravity.BOTTOM
            )
        )

        val badgeParts = ArrayList<String>(3)
        video.duration?.takeIf { it.isNotBlank() }?.let { badgeParts += it }
        video.play?.let { badgeParts += "${BiliSpaceApi.formatNullableCount(it)}观看" }
        video.danmaku?.let { badgeParts += "${BiliSpaceApi.formatNullableCount(it)}弹幕" }
        if (badgeParts.isNotEmpty()) {
            val badgeRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            video.duration?.takeIf { it.isNotBlank() }?.let { dur ->
                badgeRow.addView(TextView(activity).apply {
                    text = dur
                    textSize = 10.5f
                    typeface = Ds.body(bold = true)
                    fontFeatureSettings = "tnum"
                    setTextColor(0xFFFFFFFF.toInt())
                    background = rounded(0x88000000.toInt(), activity.dp(4))
                    setPadding(activity.dp(6), activity.dp(2), activity.dp(6), activity.dp(2))
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = activity.dp(8) })
            }
            val meta = listOfNotNull(
                video.play?.let { "${BiliSpaceApi.formatNullableCount(it)}观看" },
                video.danmaku?.let { "${BiliSpaceApi.formatNullableCount(it)}弹幕" }
            ).joinToString("   ")
            if (meta.isNotEmpty()) {
                badgeRow.addView(TextView(activity).apply {
                    text = meta
                    textSize = 11f
                    typeface = Ds.body(bold = true)
                    fontFeatureSettings = "tnum"
                    setTextColor(0xF0FFFFFF.toInt())
                    includeFontPadding = false
                })
            }
            coverFrame.addView(badgeRow, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = activity.dp(10)
                bottomMargin = activity.dp(9)
            })
        }

        wrap.addView(coverFrame, LinearLayout.LayoutParams(coverW, coverH))

        wrap.addView(TextView(activity).apply {
            text = applyKeywordHighlight(video.title, highlightKeyword)
            textSize = 14.5f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(activity.dp(2.5f), 1f)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(9) })

        parent.addView(wrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(topMarginDp) })
    }

    /**
     * 图三风格：带配文动态中嵌的视频子卡，保留高区分度圆角小卡片，点击直达 VideoDetailActivity。
     */
    private fun addVideoSubCard(
        activity: Activity,
        parent: LinearLayout,
        video: SpaceVideo,
        highlightKeyword: String,
        topMarginDp: Int,
        onOpenVideo: (bvid: String?, aid: Long?) -> Unit
    ) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(
                activity,
                COLOR_SURFACE,
                Ds.colors.lineSoft,
                Ds.Radius.control
            )
            setPadding(activity.dp(9), activity.dp(9), activity.dp(11), activity.dp(9))
            isClickable = true
            Ds.bindPressFeedback(this, 0.985f)
            setOnClickListener { onOpenVideo(video.bvid, video.aid) }
        }
        val coverW = activity.dp(130)
        val coverH = activity.dp(82)
        val coverFrame = FrameLayout(activity).apply {
            setBackgroundColor(COLOR_COVER)
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        val cover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_COVER)
        }
        if (!video.cover.isNullOrBlank()) {
            CoverLoader.load(
                cover,
                buildSpaceVideoCoverThumbUrl(video.cover),
                cropThumb = false,
                animate = false
            )
        }
        coverFrame.addView(cover, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        coverFrame.addView(
            View(activity).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00000000, 0x66000000)
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                activity.dp(22),
                Gravity.BOTTOM
            )
        )
        if (!video.duration.isNullOrBlank()) {
            coverFrame.addView(TextView(activity).apply {
                text = video.duration
                textSize = 9.5f
                typeface = Ds.body(bold = true)
                fontFeatureSettings = "tnum"
                setTextColor(0xFFFFFFFF.toInt())
                background = rounded(0x99000000.toInt(), activity.dp(4))
                setPadding(activity.dp(4), activity.dp(1), activity.dp(4), activity.dp(1))
                includeFontPadding = false
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { rightMargin = activity.dp(4); bottomMargin = activity.dp(4) })
        }
        box.addView(coverFrame, LinearLayout.LayoutParams(coverW, coverH))

        val infoCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(11), activity.dp(1), 0, activity.dp(1))
        }
        infoCol.addView(TextView(activity).apply {
            text = applyKeywordHighlight(video.title, highlightKeyword)
            textSize = 13.5f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(activity.dp(2).toFloat(), 1f)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        infoCol.addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))

        val metaParts = ArrayList<String>(2)
        video.play?.let { metaParts += "${BiliSpaceApi.formatNullableCount(it)}播放" }
        video.danmaku?.let { metaParts += "${BiliSpaceApi.formatNullableCount(it)}弹幕" }
        infoCol.addView(TextView(activity).apply {
            text = if (metaParts.isNotEmpty()) metaParts.joinToString(" · ") else "投稿视频"
            textSize = 11f
            typeface = Ds.body()
            fontFeatureSettings = "tnum"
            setTextColor(Ds.colors.subtle)
            maxLines = 1
            includeFontPadding = false
        })
        box.addView(infoCol, LinearLayout.LayoutParams(0, coverH, 1f))

        parent.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(topMarginDp) })
    }

    /**
     * 开播动态卡：封面 +「直播中」角标 + 直播间标题 + 一键进入直播间。
     * 这类动态原先落到「该动态类型暂不支持展示」，现在改为可直接进入直播间。
     */
    private fun addLiveCard(
        activity: Activity,
        parent: LinearLayout,
        item: SpaceDynamic,
        live: DynamicLive,
        topMarginDp: Int,
        contentWidthPx: Int
    ) {
        val openLive: () -> Unit = {
            LiveRoomActivity.open(
                activity,
                live.roomId,
                live.title,
                item.authorName.orEmpty(),
                live.cover
            )
        }
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(activity, COLOR_SURFACE, Ds.colors.lineSoft, Ds.Radius.control)
            Ds.clipRounded(this, Ds.Radius.control)
            isClickable = true
            Ds.bindPressFeedback(this, 0.985f)
            setOnClickListener { openLive() }
        }

        val coverFrame = FrameLayout(activity).apply { setBackgroundColor(COLOR_COVER) }
        val cover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_COVER)
        }
        if (live.cover.isNotBlank()) {
            CoverLoader.load(cover, live.cover, cropThumb = false, animate = false)
        }
        coverFrame.addView(cover, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // 左上角「直播中」角标，与直播页保持同一套视觉
        coverFrame.addView(TextView(activity).apply {
            text = "直播中"
            textSize = 10f
            typeface = Ds.body(bold = true)
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(COLOR_ROSE, activity.dp(6))
            setPadding(activity.dp(7), activity.dp(2), activity.dp(7), activity.dp(2))
            includeFontPadding = false
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = activity.dp(9)
            topMargin = activity.dp(9)
        })
        val coverH = (contentWidthPx * 9 / 16).coerceIn(activity.dp(150), activity.dp(260))
        wrap.addView(coverFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            coverH
        ))

        val infoRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(11), activity.dp(10), activity.dp(11), activity.dp(10))
        }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(TextView(activity).apply {
            text = live.title.ifBlank { "${item.authorName.orEmpty()} 的直播间" }
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(activity.dp(2).toFloat(), 1f)
            includeFontPadding = false
        })
        val metaParts = ArrayList<String>(2)
        if (live.areaName.isNotBlank()) metaParts += live.areaName
        if (live.onlineText.isNotBlank()) metaParts += live.onlineText
        textCol.addView(TextView(activity).apply {
            text = if (metaParts.isNotEmpty()) metaParts.joinToString(" · ") else "正在直播"
            textSize = 11f
            typeface = Ds.body()
            fontFeatureSettings = "tnum"
            setTextColor(Ds.colors.subtle)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(4) })
        infoRow.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        infoRow.addView(TextView(activity).apply {
            text = "进入直播间"
            textSize = 12f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = Ds.pill(activity, COLOR_ROSE)
            setPadding(activity.dp(14), activity.dp(7), activity.dp(14), activity.dp(7))
            includeFontPadding = false
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { openLive() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = activity.dp(10) })

        wrap.addView(infoRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        parent.addView(wrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(topMarginDp) })
    }

    private fun addUnknownNoticeBlock(
        activity: Activity,
        parent: LinearLayout,
        item: SpaceDynamic,
        onOpenVideo: (bvid: String?, aid: Long?) -> Unit
    ) {
        val jumpBvid = extractBvidFromJump(item.jumpUrl)
        val hasTitle = item.text.isNotBlank()
        val label = when {
            !item.visible -> item.text.ifBlank { "该动态已失效或暂不可查看" }
            jumpBvid != null -> "查看关联视频（$jumpBvid）"
            hasTitle -> "该动态包含其他富媒体内容，暂仅展示摘要"
            else -> "该动态类型暂不支持展示"
        }
        val notice = TextView(activity).apply {
            text = label
            textSize = 12.5f
            typeface = Ds.body()
            setTextColor(if (jumpBvid != null) COLOR_ROSE else COLOR_MUTED)
            background = Ds.card(
                activity,
                COLOR_SURFACE,
                Ds.colors.lineSoft,
                Ds.Radius.control
            )
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
            if (jumpBvid != null) {
                isClickable = true
                Ds.bindPressFeedback(this, 0.98f)
                setOnClickListener { onOpenVideo(jumpBvid, null) }
            }
        }
        parent.addView(notice, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(8) })
    }

    private fun addStatsRow(
        activity: Activity,
        parent: LinearLayout,
        stats: DynamicStats,
        supportsDetail: Boolean,
        onOpenDetail: () -> Unit
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(12), 0, 0)
            if (supportsDetail) {
                isClickable = true
                setOnClickListener { onOpenDetail() }
            }
        }
        val items = listOf(
            StatGlyph.COMMENT to (stats.comment?.takeIf { it > 0L }?.let { BiliSpaceApi.formatNullableCount(it) } ?: "评论"),
            StatGlyph.LIKE to (stats.like?.takeIf { it > 0L }?.let { BiliSpaceApi.formatNullableCount(it) } ?: "赞")
        )
        items.forEachIndexed { idx, (glyph, label) ->
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (supportsDetail) {
                    isClickable = true
                    Ds.bindPressFeedback(this, 0.95f)
                    setOnClickListener { onOpenDetail() }
                }
            }
            cell.addView(StatIconView(activity, glyph), LinearLayout.LayoutParams(
                activity.dp(13), activity.dp(13)
            ).apply { rightMargin = activity.dp(4) })
            cell.addView(TextView(activity).apply {
                text = label
                textSize = 11.5f
                typeface = Ds.body()
                fontFeatureSettings = "tnum"
                setTextColor(Ds.colors.subtle)
                maxLines = 1
                includeFontPadding = false
            })
            row.addView(cell, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (idx > 0) leftMargin = activity.dp(24)
            })
        }
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun extractBvidFromJump(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return Regex("BV[0-9A-Za-z]{10}").find(url)?.value
    }

    internal fun formatDynamicTime(pubTs: Long?, rawPubTime: String?): String {
        if (pubTs == null || pubTs <= 0L) return rawPubTime?.trim().orEmpty()
        val nowMs = System.currentTimeMillis()
        val pubMs = pubTs * 1000L
        val diffMs = nowMs - pubMs
        if (diffMs in 0L until 60_000L) return "刚刚"
        if (diffMs in 60_000L until 3_600_000L) return "${diffMs / 60_000L}分钟前"

        val nowCal = Calendar.getInstance()
        val pubCal = Calendar.getInstance().apply { timeInMillis = pubMs }
        val sameDay = nowCal.get(Calendar.YEAR) == pubCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == pubCal.get(Calendar.DAY_OF_YEAR)
        if (sameDay) {
            return "今天 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(pubMs))
        }
        val sameYear = nowCal.get(Calendar.YEAR) == pubCal.get(Calendar.YEAR)
        return if (sameYear) {
            SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(pubMs))
        } else {
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(pubMs))
        }
    }

    internal fun applyKeywordHighlight(text: String, keyword: String): CharSequence {
        val needle = keyword.trim()
        if (needle.isEmpty()) return text
        val sb = SpannableStringBuilder(text)
        highlightRanges(sb, text, needle)
        return sb
    }

    private fun highlightRanges(sb: SpannableStringBuilder, rawText: String, keyword: String) {
        val needle = keyword.trim()
        if (needle.isEmpty()) return
        val lowerText = rawText.lowercase(Locale.ROOT)
        val lowerNeedle = needle.lowercase(Locale.ROOT)
        var start = lowerText.indexOf(lowerNeedle)
        while (start >= 0) {
            val end = (start + lowerNeedle.length).coerceAtMost(sb.length)
            if (end > start) {
                sb.setSpan(BackgroundColorSpan(COLOR_ROSE_SOFT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(COLOR_ROSE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start = lowerText.indexOf(lowerNeedle, end)
        }
    }

    /** 将动态正文 + 富文本节点拼接为带高亮与内嵌表情的 SpannableString，供卡片与详情页共用。 */
    internal fun buildDynamicSpannable(
        rawText: String,
        richNodes: List<RichNode>,
        highlightKeyword: String,
        emoteSizePx: Int
    ): SpannableStringBuilder {
        val sb = SpannableStringBuilder(rawText)
        val emojiMap = HashMap<String, String>()
        val topicOrAtList = ArrayList<String>()
        richNodes.forEach { node ->
            if (!node.emojiUrl.isNullOrBlank() && node.text.isNotBlank()) {
                emojiMap[node.text] = node.emojiUrl
            } else if (
                (node.type == "RICH_TEXT_NODE_TYPE_TOPIC" || node.type == "RICH_TEXT_NODE_TYPE_AT") &&
                node.text.isNotBlank()
            ) {
                topicOrAtList += node.text
            }
        }
        topicOrAtList.distinct().forEach { token ->
            var idx = rawText.indexOf(token)
            while (idx >= 0) {
                sb.setSpan(
                    ForegroundColorSpan(COLOR_ROSE),
                    idx,
                    idx + token.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = rawText.indexOf(token, idx + token.length)
            }
        }
        highlightRanges(sb, rawText, highlightKeyword)
        emojiMap.forEach { (code, url) ->
            var idx = rawText.indexOf(code)
            while (idx >= 0) {
                sb.setSpan(
                    SpaceEmoteSpan(url, emoteSizePx),
                    idx,
                    idx + code.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = rawText.indexOf(code, idx + code.length)
            }
        }
        return sb
    }

    internal fun attachSpaceEmoteSpans(tv: TextView) {
        val spanned = tv.text as? Spanned ?: return
        val spans = spanned.getSpans(0, spanned.length, SpaceEmoteSpan::class.java)
        if (spans.isEmpty()) return
        val trigger = {
            spans.forEach { it.attach(tv) }
        }
        if (tv.isAttachedToWindow) {
            trigger()
        } else {
            tv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    trigger()
                }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    private fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

    private class SpaceEmoteSpan(
        private val url: String,
        private val sizePx: Int
    ) : ReplacementSpan() {
        private var host: WeakReference<TextView>? = null
        private val bounds = RectF()
        private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun attach(view: TextView) {
            host = WeakReference(view)
            SpaceEmotes.bitmap(url, view)
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                val lineH = fm.descent - fm.ascent
                if (sizePx > lineH) {
                    val extra = sizePx - lineH
                    fm.ascent -= extra / 2
                    fm.descent += extra - extra / 2
                }
            }
            return sizePx
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val bitmap = SpaceEmotes.bitmap(url, host?.get()) ?: return
            val yTop = (top + bottom - sizePx) / 2f
            bounds.set(x, yTop, x + sizePx, yTop + sizePx)
            canvas.drawBitmap(bitmap, null, bounds, filterPaint)
        }
    }

    private object SpaceEmotes {
        private const val DECODE_EDGE = 128
        private val main = Handler(Looper.getMainLooper())
        private val pool = ThreadPoolExecutor(
            2, 4, 20L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(64)
        ).apply { allowCoreThreadTimeOut(true) }
        private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
        private val retryAfter = LruCache<String, Long>(64)
        private val pending = HashMap<String, WeakHashMap<TextView, Unit>>()
        @Volatile private var cacheDir: File? = null

        fun bitmap(url: String, view: TextView?): Bitmap? {
            if (url.isBlank()) return null
            cache.get(url)?.let { return it }
            if (view == null || !view.isAttachedToWindow) return null
            if (cacheDir == null) {
                cacheDir = File(view.context.applicationContext.cacheDir, "emote_cache").apply {
                    if (!isDirectory) mkdirs()
                }
            }
            val now = SystemClock.uptimeMillis()
            if ((retryAfter.get(url) ?: 0L) > now) return null
            pending[url]?.let { targets ->
                targets[view] = Unit
                return null
            }
            pending[url] = WeakHashMap<TextView, Unit>().apply { put(view, Unit) }
            try {
                pool.execute {
                    val decoded = fetch(url)
                    main.post {
                        val targets = pending.remove(url) ?: return@post
                        if (decoded == null) {
                            retryAfter.put(url, SystemClock.uptimeMillis() + 5_000L)
                        } else {
                            cache.put(url, decoded)
                            targets.keys.forEach { tv ->
                                if (tv.isAttachedToWindow) {
                                    tv.invalidate()
                                    tv.postInvalidateOnAnimation()
                                }
                            }
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                pending.remove(url)
            }
            return null
        }

        private fun fetch(url: String): Bitmap? {
            return try {
                val candidates = buildEmoteCandidates(url)
                for (candidate in candidates) {
                    val diskFile = cacheDir?.let { File(it, "se_${candidate.hashCode().toUInt().toString(16)}") }
                    val bytes = if (diskFile != null && diskFile.isFile && diskFile.length() > 0L) {
                        diskFile.readBytes()
                    } else {
                        val req = Request.Builder()
                            .url(candidate)
                            .header("User-Agent", NETWORK_USER_AGENT)
                            .header("Referer", "https://www.bilibili.com/")
                            .build()
                        val downloaded = BiliHttp.client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) null else resp.body?.bytes()
                        } ?: continue
                        if (diskFile != null && downloaded.size <= 512 * 1024) {
                            runCatching { diskFile.writeBytes(downloaded) }
                        }
                        downloaded
                    }
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (opts.outWidth <= 0 || opts.outHeight <= 0) continue
                    var sample = 1
                    while (opts.outWidth / sample > DECODE_EDGE * 2 || opts.outHeight / sample > DECODE_EDGE * 2) {
                        sample *= 2
                    }
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                    if (bmp != null) return bmp
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    private enum class StatGlyph { COMMENT, LIKE }

    private class StatIconView(
        context: Context,
        private val glyph: StatGlyph
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ds.colors.subtle
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = Ds.colors.subtle
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            when (glyph) {
                StatGlyph.COMMENT -> {
                    rect.set(w * 0.14f, h * 0.16f, w * 0.86f, h * 0.72f)
                    canvas.drawRoundRect(rect, 2.5f * density, 2.5f * density, paint)
                    path.rewind()
                    path.moveTo(w * 0.34f, h * 0.72f)
                    path.lineTo(w * 0.28f, h * 0.88f)
                    path.lineTo(w * 0.52f, h * 0.72f)
                    canvas.drawPath(path, paint)
                }
                StatGlyph.LIKE -> {
                    path.rewind()
                    path.moveTo(w * 0.22f, h * 0.46f)
                    path.lineTo(w * 0.22f, h * 0.84f)
                    path.moveTo(w * 0.36f, h * 0.84f)
                    path.lineTo(w * 0.72f, h * 0.84f)
                    path.cubicTo(w * 0.82f, h * 0.84f, w * 0.86f, h * 0.50f, w * 0.82f, h * 0.44f)
                    path.lineTo(w * 0.58f, h * 0.44f)
                    path.lineTo(w * 0.60f, h * 0.24f)
                    path.cubicTo(w * 0.60f, h * 0.16f, w * 0.50f, h * 0.16f, w * 0.46f, h * 0.26f)
                    path.lineTo(w * 0.36f, h * 0.46f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
    }
}
