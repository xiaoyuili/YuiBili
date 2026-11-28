package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 独立动态详情页：
 * - 承载图文动态、纯文字动态、以及「配文 + 引用视频」动态的完整详情视图；
 * - 顶部保留简洁返回栏与主题色适配，展示完整无截断正文（含内联 B 站表情与点击弹窗预览保存）、标题（如有）、图片流与引用视频子卡（点击视频子卡直达 VideoDetailActivity）；
 * - 下方挂载独立的动态评论区（按热度 / 按最新切换、游标分页加载、楼中楼评论详情抽屉、评论点赞、点击评论者头像/昵称跳空间）。
 */
class SpaceDynamicDetailActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var detailRequest: BiliRequest? = null

    private lateinit var dynamicItem: SpaceDynamic
    private lateinit var snackHost: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var detailHost: LinearLayout
    private lateinit var comments: CommentListView

    private var currentCommentOid: String = ""
    private var currentCommentType: Int = 17

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        BiliSessionStore.init(this)
        Theme.applySystemBars(this)
        LayoutMode.applyScreenOrientation(this)

        val parsed = readDynamicFromIntent(intent)
        if (parsed == null) {
            finish()
            return
        }
        dynamicItem = parsed
        currentCommentOid = dynamicItem.commentOid.orEmpty().ifBlank {
            dynamicItem.id.takeIf { it.all(Char::isDigit) }.orEmpty()
        }
        currentCommentType = dynamicItem.commentType ?: 17

        val root = FrameLayout(this).apply { setBackgroundColor(COLOR_BACKGROUND) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        topBar = buildTopBar()
        column.addView(
            topBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        )
        column.addView(
            View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this))
        )

        if (LayoutMode.isWideScreen(this)) {
            val split = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(COLOR_BACKGROUND)
            }
            val leftScroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                setBackgroundColor(COLOR_CARD)
            }
            detailHost = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(COLOR_CARD)
            }
            leftScroll.addView(
                detailHost,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            )
            split.addView(leftScroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f))
            split.addView(
                View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
                LinearLayout.LayoutParams(Ds.hairline(this), LinearLayout.LayoutParams.MATCH_PARENT)
            )

            comments = buildCommentSection()
            split.addView(comments, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            column.addView(split, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            detailHost = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(COLOR_CARD)
            }
            val leading = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(detailHost, LinearLayout.LayoutParams(-1, -2))
                addView(View(this@SpaceDynamicDetailActivity).apply { setBackgroundColor(COLOR_SOFT_FILL) }, LinearLayout.LayoutParams(-1, dp(8)))
            }
            comments = buildCommentSection(leading)
            column.addView(comments, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(column, FrameLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        snackHost = FrameLayout(this).apply { isClickable = false }
        root.addView(
            snackHost,
            FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                bottomMargin = dp(20)
                leftMargin = dp(16)
                rightMargin = dp(16)
            }
        )

        setContentView(root)

        renderDynamicBody(dynamicItem)
        refreshDynamicDetailIfNeeded()
        comments.restoreState(savedInstanceState?.getBundle("comments"))
        comments.restoreRetainedState(lastNonConfigurationInstance as? CommentListView.RetainedState)
        comments.bindTarget(currentCommentOid, currentCommentType, dynamicItem.authorMid ?: 0L)
    }

    private fun buildTopBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(COLOR_CARD)
        setPadding(dp(Ds.Space.sm), dp(6), dp(Ds.Space.md), dp(6))
        addView(
            Ds.backIcon(this@SpaceDynamicDetailActivity, COLOR_INK) { finishWithAnim() },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        addView(TextView(this@SpaceDynamicDetailActivity).apply {
            text = "动态详情"
            textSize = 16.5f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun renderDynamicBody(item: SpaceDynamic) {
        detailHost.removeAllViews()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        // 作者信息行（支持点击跳转空间）
        val authorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val mid = item.authorMid ?: 0L
            if (mid > 0L) {
                isClickable = true
                Ds.bindPressFeedback(this, 0.98f)
                setOnClickListener {
                    SpaceActivity.open(
                        this@SpaceDynamicDetailActivity,
                        mid,
                        item.authorName.orEmpty(),
                        item.authorFace.orEmpty()
                    )
                }
            }
        }
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ds.circle(COLOR_SOFT_FILL)
            Ds.clipRounded(this, 999)
        }
        item.authorFace?.takeIf { it.isNotBlank() }?.let { face ->
            CoverLoader.load(avatar, face, cropThumb = false, thumbEdge = 128, animate = false)
        }
        authorRow.addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)))

        val authorMeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(8), 0)
        }
        authorMeta.addView(TextView(this).apply {
            text = item.authorName?.ifBlank { null } ?: "哔哩哔哩用户"
            textSize = 15f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        })
        val timeText = SpaceDynamicCardUi.formatDynamicTime(item.pubTs, item.pubTime)
        if (timeText.isNotEmpty()) {
            authorMeta.addView(TextView(this).apply {
                text = timeText
                textSize = 12f
                typeface = Ds.body()
                setTextColor(Ds.colors.subtle)
                maxLines = 1
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) })
        }
        authorRow.addView(authorMeta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (item.isPinned) {
            authorRow.addView(TextView(this).apply {
                text = "置顶"
                textSize = 10.5f
                typeface = Ds.body(bold = true)
                setTextColor(COLOR_ROSE)
                background = rounded(COLOR_ROSE_SOFT, dp(6))
                setPadding(dp(7), dp(2), dp(7), dp(2))
                includeFontPadding = false
            })
        }
        body.addView(authorRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 图文标题（若有）
        if (!item.title.isNullOrBlank()) {
            body.addView(TextView(this).apply {
                text = item.title
                textSize = 18f
                typeface = Ds.display(bold = true)
                setTextColor(COLOR_INK)
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })
        }

        // 完整动态正文（无行数限制，内联表情可点预览保存）
        if (item.text.isNotBlank()) {
            val fullSpannable = buildDetailBodySpannable(item.text, item.richNodes)
            val bodyTv = TextView(this).apply {
                textSize = 15.5f
                typeface = Ds.body()
                setTextColor(COLOR_INK)
                setLineSpacing(4.5f * resources.displayMetrics.density, 1f)
                setTextIsSelectable(true)
                setSpannableFactory(object : android.text.Spannable.Factory() {
                    override fun newSpannable(source: CharSequence?): android.text.Spannable {
                        return source as? android.text.Spannable ?: super.newSpannable(source)
                    }
                })
                setText(fullSpannable, TextView.BufferType.SPANNABLE)
            }
            CommentSectionUi.attachEmoteSpans(bodyTv)
            CommentSectionUi.installEmoteTapHandler(this, bodyTv, { msg -> showFeedback(msg) })
            body.addView(bodyTv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }

        // 图片流（单图按比例大图展示，多图网格展示，点击打开 ImagePreviewDialog 滑动+缩放+长图+保存）
        if (item.images.isNotEmpty()) {
            renderImagesSection(body, item.images)
        }

        // 引用视频子卡（点击直达 VideoDetailActivity）
        item.video?.let { video ->
            renderVideoReferenceCard(body, video)
        }

        // 底部轻统计信息（仅评论与点赞计数，无转发、无分享）
        val statParts = ArrayList<String>(2)
        item.stats.comment?.takeIf { it > 0L }?.let { statParts += "${BiliSpaceApi.formatNullableCount(it)} 评论" }
        item.stats.like?.takeIf { it > 0L }?.let { statParts += "${BiliSpaceApi.formatNullableCount(it)} 点赞" }
        if (statParts.isNotEmpty()) {
            body.addView(TextView(this).apply {
                text = statParts.joinToString("  ·  ")
                textSize = 12f
                typeface = Ds.body()
                fontFeatureSettings = "tnum"
                setTextColor(Ds.colors.subtle)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })
        }

        detailHost.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun buildDetailBodySpannable(rawText: String, richNodes: List<RichNode>): SpannableStringBuilder {
        val sb = SpannableStringBuilder(rawText)
        val emoteMap = HashMap<String, String>()
        val topicOrAt = ArrayList<String>()
        richNodes.forEach { node ->
            if (!node.emojiUrl.isNullOrBlank() && node.text.isNotBlank()) {
                emoteMap[node.text] = node.emojiUrl
            } else if (
                (node.type == "RICH_TEXT_NODE_TYPE_TOPIC" || node.type == "RICH_TEXT_NODE_TYPE_AT") &&
                node.text.isNotBlank()
            ) {
                topicOrAt += node.text
            }
        }
        topicOrAt.distinct().forEach { token ->
            var idx = rawText.indexOf(token)
            while (idx >= 0) {
                sb.setSpan(
                    ForegroundColorSpan(COLOR_ROSE),
                    idx,
                    (idx + token.length).coerceAtMost(sb.length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = rawText.indexOf(token, idx + token.length)
            }
        }
        CommentSectionUi.applyEmotesToSpannable(this, sb, rawText, emoteMap, sizeDp = 19)
        return sb
    }

    private fun renderImagesSection(parent: LinearLayout, images: List<SpaceImage>) {
        val urls = images.map { it.url }.filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        val contentWidth = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(200))

        if (images.size <= 3) {
            // 详情页少图采用更舒展的纵向流式大图呈现（单张或多张大图），方便阅读细节
            images.forEachIndexed { idx, img ->
                val iw = img.width ?: 0
                val ih = img.height ?: 0
                val ratio = if (iw > 0 && ih > 0) ih.toFloat() / iw.toFloat() else 0.72f
                val isLong = ratio >= 2.0f
                val targetW = if (isLong) (contentWidth * 0.78f).toInt() else contentWidth
                val targetH = when {
                    isLong -> dp(360)
                    else -> (targetW * ratio.coerceIn(0.45f, 1.45f)).toInt().coerceAtLeast(dp(140))
                }
                val frame = FrameLayout(this).apply {
                    background = rounded(COLOR_COVER, dp(12))
                    Ds.clipRounded(this, Ds.Radius.control)
                    isClickable = true
                    Ds.bindPressFeedback(this, 0.985f)
                    setOnClickListener {
                        ImagePreviewDialog.show(this@SpaceDynamicDetailActivity, urls, idx) { msg ->
                            showFeedback(msg)
                        }
                    }
                }
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(COLOR_COVER)
                }
                CoverLoader.load(
                    iv,
                    SpaceDynamicCardUi.buildDynamicImageThumbUrl(img, singleMode = true),
                    cropThumb = false,
                    animate = false
                )
                frame.addView(iv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    targetH
                ))
                if (isLong) {
                    frame.addView(TextView(this).apply {
                        text = "长图"
                        textSize = 10f
                        typeface = Ds.body(bold = true)
                        setTextColor(Color.WHITE)
                        background = rounded(0x99000000.toInt(), dp(6))
                        setPadding(dp(7), dp(3), dp(7), dp(3))
                        includeFontPadding = false
                    }, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.END
                    ).apply { bottomMargin = dp(8); rightMargin = dp(8) })
                }
                parent.addView(frame, LinearLayout.LayoutParams(
                    targetW,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(if (idx == 0) 14 else 8) })
            }
            return
        }

        // 4+ 张图片时在详情页使用完整 3 列（或 4 张时 2 列）网格全部展示（不截断 9 图）
        val cols = if (images.size == 4) 2 else 3
        val gap = dp(6)
        val cellSize = ((contentWidth - gap * (cols - 1)) / cols).coerceAtLeast(dp(72))
        val cellHeight = if (cols == 2) (cellSize * 0.85f).toInt() else cellSize
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = images.chunked(cols)
        rows.forEachIndexed { rIdx, rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (cIdx in 0 until cols) {
                if (cIdx >= rowItems.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, cellHeight, 1f).apply {
                        if (cIdx < cols - 1) rightMargin = gap
                    })
                    continue
                }
                val itemIndex = rIdx * cols + cIdx
                val img = rowItems[cIdx]
                val cell = FrameLayout(this).apply {
                    setBackgroundColor(COLOR_COVER)
                    Ds.clipRounded(this, Ds.Radius.chip)
                    isClickable = true
                    Ds.bindPressFeedback(this, 0.96f)
                    setOnClickListener {
                        ImagePreviewDialog.show(this@SpaceDynamicDetailActivity, urls, itemIndex) { msg ->
                            showFeedback(msg)
                        }
                    }
                }
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(COLOR_COVER)
                }
                CoverLoader.load(
                    iv,
                    SpaceDynamicCardUi.buildDynamicImageThumbUrl(img, singleMode = false),
                    cropThumb = true,
                    animate = false
                )
                cell.addView(iv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
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
        ).apply { topMargin = dp(14) })
    }

    private fun renderVideoReferenceCard(parent: LinearLayout, video: SpaceVideo) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(
                this@SpaceDynamicDetailActivity,
                COLOR_SURFACE,
                Ds.colors.lineSoft,
                Ds.Radius.control
            )
            setPadding(dp(10), dp(10), dp(12), dp(10))
            isClickable = true
            Ds.bindPressFeedback(this, 0.985f)
            setOnClickListener {
                val bvid = video.bvid.orEmpty()
                val aid = video.aid ?: 0L
                if (bvid.isNotBlank() || aid > 0L) {
                    VideoDetailActivity.open(this@SpaceDynamicDetailActivity, bvid, aid)
                }
            }
        }
        val coverW = dp(136)
        val coverH = dp(84)
        val coverFrame = FrameLayout(this).apply {
            setBackgroundColor(COLOR_COVER)
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_COVER)
        }
        if (!video.cover.isNullOrBlank()) {
            CoverLoader.load(
                cover,
                SpaceDynamicCardUi.buildSpaceVideoCoverThumbUrl(video.cover),
                cropThumb = false,
                animate = false
            )
        }
        coverFrame.addView(cover, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        coverFrame.addView(
            View(this).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00000000, 0x66000000)
                )
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(22), Gravity.BOTTOM)
        )
        if (!video.duration.isNullOrBlank()) {
            coverFrame.addView(TextView(this).apply {
                text = video.duration
                textSize = 9.5f
                typeface = Ds.body(bold = true)
                fontFeatureSettings = "tnum"
                setTextColor(Color.WHITE)
                background = rounded(0x99000000.toInt(), dp(4))
                setPadding(dp(4), dp(1), dp(4), dp(1))
                includeFontPadding = false
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { rightMargin = dp(4); bottomMargin = dp(4) })
        }
        box.addView(coverFrame, LinearLayout.LayoutParams(coverW, coverH))

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(2), 0, dp(2))
        }
        infoCol.addView(TextView(this).apply {
            text = video.title
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        infoCol.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val metaParts = ArrayList<String>(2)
        video.play?.let { metaParts += "${BiliSpaceApi.formatNullableCount(it)}播放" }
        video.danmaku?.let { metaParts += "${BiliSpaceApi.formatNullableCount(it)}弹幕" }
        infoCol.addView(TextView(this).apply {
            text = if (metaParts.isNotEmpty()) metaParts.joinToString(" · ") else "投稿视频"
            textSize = 11.5f
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
        ).apply { topMargin = dp(14) })
    }

    private fun refreshDynamicDetailIfNeeded() {
        val dynId = dynamicItem.id.trim()
        if (dynId.isEmpty() || !dynId.all(Char::isDigit)) return
        detailRequest?.cancel()
        detailRequest = BiliSpaceApi.fetchDynamicDetail(dynId, forceRefresh = false) { fresh, _ ->
            if (isFinishing || isDestroyed || fresh == null) return@fetchDynamicDetail
            val prevOid = currentCommentOid
            val prevType = currentCommentType
            dynamicItem = fresh
            val nextOid = fresh.commentOid.orEmpty().ifBlank { prevOid }
            val nextType = fresh.commentType ?: prevType
            renderDynamicBody(fresh)
            if (nextOid.isNotBlank() && (nextOid != prevOid || nextType != prevType)) {
                currentCommentOid = nextOid
                currentCommentType = nextType
                comments.bindTarget(nextOid, nextType, fresh.authorMid ?: 0L, start = true)
            }
        }
    }

    private fun buildCommentSection(leadingHeader: View? = null): CommentListView =
        CommentListView(this, { showFeedback(it) }, {
            val location = IntArray(2)
            topBar.getLocationOnScreen(location)
            location[1] + topBar.height
        }, leadingHeader)

    private fun showFeedback(message: String) {
        if (isFinishing || isDestroyed || message.isBlank()) return
        snackHost.removeAllViews()
        val bar = TextView(this).apply {
            text = message
            textSize = 13f
            typeface = Ds.body(bold = true)
            setTextColor(0xFFFFFFFF.toInt())
            background = Ds.pill(this@SpaceDynamicDetailActivity, if (Theme.isDark) 0xE62A2D36.toInt() else 0xE61E2026.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            gravity = Gravity.CENTER
        }
        snackHost.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL
            )
        )
        bar.alpha = 0f
        bar.translationY = dp(10).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed && bar.parent === snackHost) {
                bar.animate().alpha(0f).setDuration(180L).withEndAction {
                    snackHost.removeView(bar)
                }.start()
            }
        }, 2000L)
    }

    private fun finishWithAnim() {
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithAnim()
    }

    override fun onResume() {
        super.onResume()
        if (::comments.isInitialized) comments.setPageActive(true)
    }

    override fun onPause() {
        if (::comments.isInitialized) comments.setPageActive(false)
        super.onPause()
    }

    override fun onRetainNonConfigurationInstance(): Any? =
        if (::comments.isInitialized) comments.retainState() else null

    override fun onSaveInstanceState(outState: Bundle) {
        if (::comments.isInitialized) outState.putBundle("comments", comments.saveState())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::comments.isInitialized) comments.dispose()
        detailRequest?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_DYNAMIC_JSON = "dynamic_json"

        fun open(context: Context, dynamic: SpaceDynamic) {
            val intent = Intent(context, SpaceDynamicDetailActivity::class.java)
                .putExtra(EXTRA_DYNAMIC_JSON, serializeDynamic(dynamic))
            context.startActivity(intent)
        }

        internal fun serializeDynamic(item: SpaceDynamic): String {
            val root = JSONObject()
            root.put("id", item.id)
            root.put("kind", item.kind.name)
            item.authorMid?.let { root.put("authorMid", it) }
            item.authorName?.let { root.put("authorName", it) }
            item.authorFace?.let { root.put("authorFace", it) }
            item.pubTs?.let { root.put("pubTs", it) }
            item.pubTime?.let { root.put("pubTime", it) }
            root.put("isPinned", item.isPinned)
            root.put("text", item.text)
            item.title?.let { root.put("title", it) }
            item.jumpUrl?.let { root.put("jumpUrl", it) }
            item.commentOid?.let { root.put("commentOid", it) }
            item.commentType?.let { root.put("commentType", it) }
            root.put("supportsDetail", item.supportsDetail)
            root.put("visible", item.visible)

            val richArr = JSONArray()
            item.richNodes.forEach { n ->
                richArr.put(JSONObject().apply {
                    put("text", n.text)
                    put("type", n.type)
                    n.jumpUrl?.let { put("jumpUrl", it) }
                    n.emojiUrl?.let { put("emojiUrl", it) }
                })
            }
            root.put("richNodes", richArr)

            val imgArr = JSONArray()
            item.images.forEach { img ->
                imgArr.put(JSONObject().apply {
                    put("url", img.url)
                    img.width?.let { put("width", it) }
                    img.height?.let { put("height", it) }
                })
            }
            root.put("images", imgArr)

            item.video?.let { v ->
                root.put("video", JSONObject().apply {
                    v.aid?.let { put("aid", it) }
                    v.bvid?.let { put("bvid", it) }
                    put("title", v.title)
                    v.cover?.let { put("cover", it) }
                    v.duration?.let { put("duration", it) }
                    v.publishedAt?.let { put("publishedAt", it) }
                    v.play?.let { put("play", it) }
                    v.danmaku?.let { put("danmaku", it) }
                    v.comments?.let { put("comments", it) }
                    v.jumpUrl?.let { put("jumpUrl", it) }
                })
            }

            root.put("stats", JSONObject().apply {
                item.stats.comment?.let { put("comment", it) }
                item.stats.like?.let { put("like", it) }
            })
            return root.toString()
        }

        internal fun deserializeDynamic(jsonStr: String?): SpaceDynamic? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val root = JSONObject(jsonStr)
                val kindName = root.optString("kind")
                val kind = DynamicKind.entries.firstOrNull { it.name == kindName } ?: DynamicKind.UNKNOWN
                val richNodes = ArrayList<RichNode>()
                root.optJSONArray("richNodes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        richNodes += RichNode(
                            text = o.optString("text"),
                            type = o.optString("type"),
                            jumpUrl = o.optString("jumpUrl").ifBlank { null },
                            emojiUrl = o.optString("emojiUrl").ifBlank { null }
                        )
                    }
                }
                val images = ArrayList<SpaceImage>()
                root.optJSONArray("images")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val url = o.optString("url")
                        if (url.isNotBlank()) {
                            images += SpaceImage(
                                url = url,
                                width = if (o.has("width")) o.optInt("width") else null,
                                height = if (o.has("height")) o.optInt("height") else null
                            )
                        }
                    }
                }
                val videoObj = root.optJSONObject("video")
                val video = videoObj?.let { v ->
                    SpaceVideo(
                        aid = if (v.has("aid")) v.optLong("aid") else null,
                        bvid = v.optString("bvid").ifBlank { null },
                        title = v.optString("title"),
                        cover = v.optString("cover").ifBlank { null },
                        duration = v.optString("duration").ifBlank { null },
                        publishedAt = if (v.has("publishedAt")) v.optLong("publishedAt") else null,
                        play = if (v.has("play")) v.optLong("play") else null,
                        danmaku = if (v.has("danmaku")) v.optLong("danmaku") else null,
                        comments = if (v.has("comments")) v.optLong("comments") else null,
                        jumpUrl = v.optString("jumpUrl").ifBlank { null }
                    )
                }
                val statsObj = root.optJSONObject("stats")
                val stats = DynamicStats(
                    comment = statsObj?.takeIf { it.has("comment") }?.optLong("comment"),
                    like = statsObj?.takeIf { it.has("like") }?.optLong("like")
                )
                SpaceDynamic(
                    id = root.optString("id"),
                    kind = kind,
                    authorMid = if (root.has("authorMid")) root.optLong("authorMid") else null,
                    authorName = root.optString("authorName").ifBlank { null },
                    authorFace = root.optString("authorFace").ifBlank { null },
                    pubTs = if (root.has("pubTs")) root.optLong("pubTs") else null,
                    pubTime = root.optString("pubTime").ifBlank { null },
                    isPinned = root.optBoolean("isPinned", false),
                    text = root.optString("text"),
                    richNodes = richNodes,
                    images = images,
                    video = video,
                    stats = stats,
                    visible = root.optBoolean("visible", true),
                    jumpUrl = root.optString("jumpUrl").ifBlank { null },
                    title = root.optString("title").ifBlank { null },
                    commentOid = root.optString("commentOid").ifBlank { null },
                    commentType = if (root.has("commentType")) root.optInt("commentType") else null,
                    supportsDetail = root.optBoolean("supportsDetail", false)
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun readDynamicFromIntent(intent: Intent?): SpaceDynamic? {
            if (intent == null) return null
            return deserializeDynamic(intent.getStringExtra(EXTRA_DYNAMIC_JSON))
        }
    }
}
