package com.yuilittle.bili

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 评论区与富文本表情通用组件（供 VideoDetailActivity 与 SpaceDynamicDetailActivity 共用）：
 * - 支持 B 站官方/装扮小表情内联渲染、点击弹出带向下小箭头的气泡预览与保存；
 * - 支持主评论条目（置顶 / UP 徽标 / IP 属地 / 超 4 行折叠与渐变展开 / 多图九宫格预览 / 点赞 / 楼中楼预览条）；
 * - 支持楼中楼底部抽屉弹窗（通用 commentType + oid，兼容视频与动态评论区）。
 */
internal object CommentSectionUi {

    private const val COMMENT_COLLAPSE_LINES = 4
    private val EMOTE_CDN_DOMAINS = listOf("hdslb.com", "biliimg.com", "hdslb.net")

    /** 上下双箭头排序小图标（避免使用系统 emoji），支持选中态强调色。 */
    class SortIconView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private var tint: Int = COLOR_MUTED
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED
            strokeWidth = 1.65f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }

        fun setTint(color: Int) {
            if (tint != color) {
                tint = color
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = tint
            val cx = width / 2f
            val cy = height / 2f
            val dx = 2.6f * density
            val h = 4.6f * density
            val tip = 2.0f * density
            // 左上箭头
            val lx = cx - dx
            canvas.drawLine(lx, cy + h * 0.55f, lx, cy - h * 0.75f, paint)
            canvas.drawLine(lx - tip, cy - h * 0.75f + tip, lx, cy - h * 0.75f, paint)
            // 右下箭头
            val rx = cx + dx
            canvas.drawLine(rx, cy - h * 0.55f, rx, cy + h * 0.75f, paint)
            canvas.drawLine(rx + tip, cy + h * 0.75f - tip, rx, cy + h * 0.75f, paint)
        }
    }

    /** 评论区对齐于正文左缘的极细分割线（主列表头像 36dp + 间距 12dp = 48dp 缩进）。 */
    fun buildCommentDivider(context: Context, insetStartDp: Int = 48): View =
        View(context).apply {
            setBackgroundColor(Ds.colors.lineSoft)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Ds.hairline(context)
            ).apply {
                leftMargin = Ds.dp(context, insetStartDp).toInt()
            }
        }

    /**
     * 统一评论区头部控制栏（热门/最新标题 + 胶囊排序切换），供视频页、番剧页、动态详情页复用。
     */
    class CommentHeaderBar(
        context: Context,
        private val onToggleSort: () -> Unit
    ) : LinearLayout(context) {
        val titleView: TextView
        val sortLabel: TextView
        private val sortIcon: SortIconView
        private val sortPill: LinearLayout

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = Ds.dp(context, 16).toInt()
            val padTop = Ds.dp(context, 12).toInt()
            val padBottom = Ds.dp(context, 6).toInt()
            setPadding(padH, padTop, padH, padBottom)

            titleView = TextView(context).apply {
                text = "热门评论"
                textSize = 13.5f
                typeface = Ds.display(bold = true)
                setTextColor(COLOR_INK)
                includeFontPadding = false
            }
            addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            sortIcon = SortIconView(context)
            sortLabel = TextView(context).apply {
                text = "按热度"
                textSize = 12f
                typeface = Ds.body(bold = true)
                includeFontPadding = false
                setTextColor(COLOR_MUTED)
            }
            sortPill = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    Ds.dp(context, 10).toInt(),
                    Ds.dp(context, 5).toInt(),
                    Ds.dp(context, 10).toInt(),
                    Ds.dp(context, 5).toInt()
                )
                isClickable = true
                Ds.bindPressFeedback(this, 0.94f)
                setOnClickListener { onToggleSort() }
                addView(sortIcon, LayoutParams(Ds.dp(context, 14).toInt(), Ds.dp(context, 14).toInt()))
                addView(sortLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = Ds.dp(context, 4).toInt()
                })
            }
            addView(sortPill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            applySort(0)
        }

        fun applySort(sort: Int) {
            val isLatest = sort != 0
            val c = Ds.colors
            titleView.text = if (isLatest) "最新评论" else "热门评论"
            sortLabel.text = if (isLatest) "按时间" else "按热度"
            // 「按热度」与「按时间」是同等切换状态，保持一致的中性配色，不因切到按时间而突然高亮
            sortLabel.setTextColor(c.muted)
            sortIcon.setTint(c.muted)
            sortPill.background = Ds.pill(
                context,
                fill = c.surfaceRaised,
                border = c.lineSoft,
                borderPx = Ds.hairline(context)
            )
        }
    }

    /** 极简线性回复气泡小图标（用于评论操作栏右侧的回复数快捷入口）。 */
    class ReplyCountBadge(
        context: Context,
        count: Long,
        onClick: () -> Unit
    ) : LinearLayout(context) {
        private val countText = TextView(context).apply {
            textSize = 11.5f
            typeface = Ds.body(bold = false)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            setTextColor(Ds.colors.muted)
        }
        fun bindCount(value: Long) {
            countText.text = if (value > 0) formatViews(value) else ""
            countText.visibility = if (value > 0) View.VISIBLE else View.GONE
        }
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            val padH = Ds.dp(context, 6).toInt()
            val padV = Ds.dp(context, 3).toInt()
            setPadding(padH, padV, padH, padV)
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { onClick() }

            val icon = object : View(context) {
                private val d = resources.displayMetrics.density
                private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Ds.colors.muted
                    style = Paint.Style.STROKE
                    strokeWidth = 1.45f * d
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                private val path = Path()
                override fun onDraw(canvas: Canvas) {
                    p.color = Ds.colors.muted
                    val w = width.toFloat()
                    val h = height.toFloat()
                    val l = w * 0.14f
                    val t = h * 0.16f
                    val r = w * 0.86f
                    val b = h * 0.72f
                    val rad = 2.6f * d
                    path.rewind()
                    path.moveTo(l + rad, t)
                    path.lineTo(r - rad, t)
                    path.quadTo(r, t, r, t + rad)
                    path.lineTo(r, b - rad)
                    path.quadTo(r, b, r - rad, b)
                    path.lineTo(l + w * 0.34f, b)
                    path.lineTo(l + w * 0.14f, h * 0.86f)
                    path.lineTo(l + w * 0.14f, b)
                    path.quadTo(l, b, l, b - rad)
                    path.lineTo(l, t + rad)
                    path.quadTo(l, t, l + rad, t)
                    canvas.drawPath(path, p)
                }
            }
            addView(icon, LayoutParams(Ds.dp(context, 15).toInt(), Ds.dp(context, 15).toInt()))
            addView(countText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Ds.dp(context, 4).toInt()
            })
            bindCount(count)
        }
    }

    /**
     * 统一评论条目渲染：主评论列表 / 楼中楼楼主 / 楼中楼回复共用同一套视觉骨架。
     */
    fun buildUnifiedCommentItem(
        activity: Activity,
        comment: VideoComment,
        ownerMid: Long,
        targetOid: String,
        commentType: Int,
        isSheet: Boolean,
        showRootTag: Boolean,
        onOpenReplySheet: ((VideoComment) -> Unit)?,
        onFeedback: (String) -> Unit
    ): View {
        val isReplyInSheet = isSheet && !showRootTag
        val avatarSize = if (isReplyInSheet) 30 else 36
        val density = activity.resources.displayMetrics.density
        val c = Ds.colors
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, activity.dp(if (isSheet) 13 else 15), 0, activity.dp(if (isSheet) 13 else 15))
        }
        val commentSpaceTap = View.OnClickListener {
            if (comment.userMid > 0L) {
                SpaceActivity.open(activity, comment.userMid, comment.user, comment.avatar)
            }
        }
        val avatarFrame = FrameLayout(activity).apply {
            // 极淡描边环，让浅色/白色头像在暖白或碳墨背景中都有清晰轮廓
            background = Ds.pill(activity, COLOR_COVER, c.lineSoft, Ds.hairline(activity))
            val ringPad = Ds.hairline(activity)
            setPadding(ringPad, ringPad, ringPad, ringPad)
            if (comment.userMid > 0L) {
                isClickable = true
                Ds.bindPressFeedback(this, 0.93f)
                setOnClickListener(commentSpaceTap)
            }
        }
        val avatar = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ds.circle(COLOR_COVER)
            Ds.clipRounded(this, 999)
        }
        if (comment.avatar.isNotBlank()) {
            CoverLoader.load(
                avatar,
                comment.avatar + "@72w_72h_1c.webp",
                cropThumb = false,
                thumbEdge = 160,
                priority = CoverLoader.PRIORITY_NORMAL
            )
        }
        avatarFrame.addView(avatar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        row.addView(avatarFrame, LinearLayout.LayoutParams(activity.dp(avatarSize), activity.dp(avatarSize)).apply {
            topMargin = activity.dp(2)
        })

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), 0, 0, 0)
        }

        val isCommentByUp = isCommentFromUp(comment, ownerMid)
        val nameLine = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameHolder = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameHolder.addView(TextView(activity).apply {
            text = comment.user
            textSize = if (isReplyInSheet) 13f else 13.5f
            typeface = Ds.body(bold = true)
            setTextColor(when {
                isCommentByUp -> COLOR_ROSE
                isReplyInSheet -> COLOR_MUTED
                else -> COLOR_INK
            })
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            // 留出 UP/楼主 徽标宽度，防止超长昵称把紧随其后的徽标挤出可视区域
            maxWidth = activity.resources.displayMetrics.widthPixels - activity.dp(170)
            if (comment.userMid > 0L) {
                isClickable = true
                setOnClickListener(commentSpaceTap)
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        if (isCommentByUp) {
            nameHolder.addView(buildCommentUpBadge(activity), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = activity.dp(6)
            })
        }
        if (showRootTag) {
            nameHolder.addView(buildRoleTag(activity, "楼主"), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = activity.dp(6)
            })
        }
        nameLine.addView(nameHolder, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (comment.isPinned && !isReplyInSheet) {
            nameLine.addView(buildRoleTag(activity, "置顶"), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = activity.dp(8)
            })
        }
        body.addView(nameLine, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val metaText = buildString {
            append(formatCommentTime(comment.publishedAt))
            if (comment.location.isNotBlank()) {
                append(" · ")
                append(comment.location)
            }
        }
        body.addView(TextView(activity).apply {
            text = metaText
            textSize = 11.5f
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            setTextColor(c.subtle)
            setPadding(0, activity.dp(3), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        body.addView(
            buildExpandableCommentText(
                activity, comment.content, comment.emotes, comment.jumpLinks,
                textSizeSp = if (isReplyInSheet) 14f else 14.8f,
                onFeedback = onFeedback,
                mentions = comment.mentions
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        if (comment.pictures.isNotEmpty()) {
            body.addView(buildPicturesRow(activity, comment.pictures, onFeedback), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(8), 0, 0)
        }
        actionsRow.addView(
            CommentLikeButton(activity, comment, targetOid, commentType, onFeedback),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, activity.dp(26))
        )
        if (!isSheet && onOpenReplySheet != null && (comment.replyCount > 0L || comment.replies.isNotEmpty())) {
            actionsRow.addView(
                ReplyCountBadge(activity, maxOf(comment.replyCount, comment.replies.size.toLong())) {
                    onOpenReplySheet(comment)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, activity.dp(26)).apply {
                    leftMargin = activity.dp(14)
                }
            )
        }
        body.addView(actionsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        if (!isSheet && (comment.replies.isNotEmpty() || comment.replyCount > 0L) && onOpenReplySheet != null) {
            // 楼中楼预览卡片：微抬升底色 + 1px 轻描边 + 12dp 圆角，视觉清晰轻盈
            val floor = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = Ds.card(
                    activity,
                    fill = c.surfaceRaised,
                    border = c.lineSoft,
                    radiusDp = Ds.Radius.control,
                    borderPx = Ds.hairline(activity)
                )
                setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
                isClickable = true
                Ds.bindPressFeedback(this, 0.985f)
                setOnClickListener { onOpenReplySheet(comment) }
            }
            comment.replies.take(2).forEachIndexed { idx, sub ->
                val subView = buildSubCommentItem(activity, sub, ownerMid, density, onFeedback)
                floor.addView(subView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx > 0) topMargin = activity.dp(5)
                })
            }
            val moreLabel = when {
                comment.replyCount > comment.replies.size -> "查看全部 ${comment.replyCount} 条回复"
                comment.replies.isNotEmpty() -> "查看全部回复"
                else -> "查看 ${comment.replyCount} 条回复"
            }
            val moreRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, if (comment.replies.isEmpty()) 0 else activity.dp(7), 0, 0)
            }
            moreRow.addView(TextView(activity).apply {
                text = moreLabel
                textSize = 12f
                typeface = Ds.body(bold = true)
                fontFeatureSettings = "tnum"
                includeFontPadding = false
                setTextColor(COLOR_ROSE)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            moreRow.addView(Ds.chevronRightIcon(activity, COLOR_ROSE, strokeDp = 1.6f), LinearLayout.LayoutParams(
                activity.dp(12), activity.dp(12)
            ).apply {
                leftMargin = activity.dp(2)
            })
            floor.addView(moreRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            body.addView(floor, LinearLayout.LayoutParams(
                if (comment.replies.isEmpty()) LinearLayout.LayoutParams.WRAP_CONTENT
                else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = activity.dp(9)
            })
        }
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    fun buildRoleTag(activity: Activity, label: String): TextView = TextView(activity).apply {
        text = label
        textSize = 10f
        typeface = Ds.body(bold = true)
        includeFontPadding = false
        setTextColor(COLOR_ROSE)
        background = Ds.pill(activity, COLOR_ROSE_SOFT, COLOR_ROSE and 0x33FFFFFF, Ds.hairline(activity))
        gravity = Gravity.CENTER
        setPadding(activity.dp(7), activity.dp(2), activity.dp(7), activity.dp(2))
    }

    private fun buildExpandableCommentText(
        activity: Activity,
        content: String,
        emotes: Map<String, String>,
        jumpLinks: Map<String, String>,
        textSizeSp: Float,
        onFeedback: (String) -> Unit,
        mentions: Map<String, Long> = emptyMap()
    ): View {
        val fullText = commentSpannable(activity, content, emotes, jumpLinks = jumpLinks, mentions = mentions)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(6), 0, 0)
        }
        val textHost = FrameLayout(activity)
        val body = TextView(activity).apply {
            textSize = textSizeSp
            setTextColor(COLOR_INK)
            setLineSpacing(4.5f * activity.resources.displayMetrics.density, 1f)
            // 恢复长按选词；链接/@ 的点击由统一 OnTouchListener 命中处理
            setTextIsSelectable(true)
            isCursorVisible = false
            highlightColor = Ds.colors.accentSoft
            setSpannableFactory(object : Spannable.Factory() {
                override fun newSpannable(source: CharSequence?): Spannable {
                    return source as? Spannable ?: super.newSpannable(source)
                }
            })
            setText(fullText, TextView.BufferType.SPANNABLE)
        }
        // B 站链接异步换成标题，非 B 站保持原链接
        resolveBiliTitles(activity, body, content, emotes, jumpLinks, mentions)
        val fadeScrim = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(COLOR_BACKGROUND and 0x00FFFFFF, COLOR_BACKGROUND)
            )
            visibility = View.GONE
            isClickable = false
        }
        textHost.addView(body, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        textHost.addView(fadeScrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, activity.dp(22), Gravity.BOTTOM
        ))

        val toggleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(4), 0, 0)
            visibility = View.GONE
        }
        val togglePill = TextView(activity).apply {
            textSize = 12.5f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_ROSE)
            setPadding(activity.dp(12), activity.dp(6), activity.dp(2), activity.dp(4))
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
        }
        toggleRow.addView(togglePill, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        var collapsible = false
        var expanded = false

        fun applyState() {
            if (!collapsible) {
                body.maxLines = Integer.MAX_VALUE
                body.ellipsize = null
                fadeScrim.visibility = View.GONE
                toggleRow.visibility = View.GONE
            } else if (expanded) {
                body.maxLines = Integer.MAX_VALUE
                body.ellipsize = null
                fadeScrim.visibility = View.GONE
                toggleRow.visibility = View.VISIBLE
                togglePill.text = "收起"
            } else {
                body.maxLines = COMMENT_COLLAPSE_LINES
                body.ellipsize = TextUtils.TruncateAt.END
                fadeScrim.visibility = View.VISIBLE
                toggleRow.visibility = View.VISIBLE
                togglePill.text = "展开"
            }
            attachEmoteSpans(body)
        }

        body.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (body.viewTreeObserver.isAlive) body.viewTreeObserver.removeOnPreDrawListener(this)
                val l = body.layout ?: return true
                val exceeds = l.lineCount > COMMENT_COLLAPSE_LINES
                if (exceeds != collapsible) {
                    collapsible = exceeds
                    applyState()
                    return false
                }
                return true
            }
        })
        attachEmoteSpans(body)

        togglePill.setOnClickListener {
            if (!collapsible) return@setOnClickListener
            expanded = !expanded
            applyState()
        }
        body.setOnClickListener {
            if (collapsible && !expanded) {
                expanded = true
                applyState()
            }
        }
        installEmoteTapHandler(activity, body, onFeedback) {
            if (collapsible && !expanded) {
                expanded = true
                applyState()
            }
        }

        box.addView(textHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        box.addView(toggleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return box
    }

    /**
     * 评论区首屏受控预取（仅静图；GIF 源文件按可见性加载）：
     * - 仅预取前 4 条评论，配图最多 4 张 + 头像最多 4 张（合计最多 8 张），配图优先；
     * - 返回 PrefetchHandle，供切集/切换排序/销毁页面时随时取消。
     */
    fun prefetchCommentTopMedia(
        context: Context,
        comments: List<VideoComment>
    ): CoverLoader.PrefetchHandle {
        if (comments.isEmpty()) return CoverLoader.PrefetchHandle(emptyList())
        val specs = ArrayList<CoverLoader.PrefetchSpec>(8)
        var picBudget = 4
        var avatarBudget = 4
        val top = comments.take(4)
        // 配图优先于头像
        for (c in top) {
            if (picBudget <= 0) break
            val shownCount = minOf(3, c.pictures.size)
            for (i in 0 until shownCount) {
                if (picBudget <= 0) break
                val pic = c.pictures[i]
                if (pic.url.isNotBlank() && resolveCoverKey(pic.url, original = false)?.gif == false) {
                    specs.add(
                        CoverLoader.PrefetchSpec(
                            rawUrl = pic.url,
                            cropThumb = false,
                            thumbEdge = COMMENT_PIC_THUMB_EDGE,
                            priority = CoverLoader.PRIORITY_NORMAL
                        )
                    )
                    picBudget--
                }
            }
        }
        for (c in top) {
            if (avatarBudget <= 0) break
            if (c.avatar.isNotBlank()) {
                specs.add(
                    CoverLoader.PrefetchSpec(
                        rawUrl = c.avatar + "@72w_72h_1c.webp",
                        cropThumb = false,
                        thumbEdge = 160,
                        priority = CoverLoader.PRIORITY_LOW
                    )
                )
                avatarBudget--
            }
        }
        return CoverLoader.prefetchBatch(context, specs)
    }

    private const val COMMENT_PIC_THUMB_EDGE = 320

    fun buildPicturesRow(
        activity: Activity,
        pics: List<CommentPicture>,
        onFeedback: (String) -> Unit
    ): LinearLayout {
        val shown = minOf(2, pics.size)
        val extraCount = pics.size - shown
        val gap = activity.dp(6)
        val cardCount = shown + (if (extraCount > 0) 1 else 0)
        val cardW = activity.dp(72)
        val cardH = activity.dp(132)
        val thumbEdge = COMMENT_PIC_THUMB_EDGE
        val imageRow = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
                val width = if (pics.size == 1) {
                    val picture = pics[0]
                    commentSinglePictureCardWidth(
                        available,
                        picture.width,
                        picture.height,
                        activity.dp(132),
                        activity.dp(72),
                        activity.dp(228)
                    )
                } else commentPictureCardWidth(available, cardCount, gap)
                val height = if (pics.size == 1) activity.dp(132) else pics.take(shown).maxOf { picture ->
                    if (picture.width > 0 && picture.height > 0)
                        (width.toDouble() * picture.height / picture.width).toInt().coerceIn(activity.dp(84), activity.dp(168))
                    else activity.dp(120)
                }
                for (index in 0 until childCount) {
                    val params = getChildAt(index).layoutParams as LayoutParams
                    params.width = width
                    params.height = height
                    params.rightMargin = if (index < childCount - 1) gap else 0
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, activity.dp(9), 0, 0)
        }
        val urls = pics.map { it.url }
        val cardImages = HashMap<Int, ImageView>(cardCount)
        val posterProvider: (Int) -> Bitmap? = { idx ->
            val iv = cardImages[idx]
            val bmp = (iv?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bmp != null && !bmp.isRecycled) bmp else null
        }

        fun loadCommentPicture(iv: ImageView, rawUrl: String) {
            CoverLoader.load(
                iv,
                rawUrl,
                original = false,
                cropThumb = false,
                animate = true,
                thumbEdge = thumbEdge,
                priority = CoverLoader.PRIORITY_NORMAL + 1,
                allowAnimatedGif = true
            )
        }

        pics.take(shown).forEachIndexed { index, picture ->
            val image = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(COLOR_COVER, activity.dp(10))
                Ds.clipRounded(this, 10)
                Ds.bindPressFeedback(this, 0.97f)
                setOnClickListener {
                    ImagePreviewDialog.show(activity, urls, index, posterProvider = posterProvider, onFeedback = onFeedback)
                }
            }
            cardImages[index] = image
            loadCommentPicture(image, picture.url)
            imageRow.addView(image, LinearLayout.LayoutParams(cardW, cardH).apply {
                if (index < cardCount - 1) rightMargin = gap
            })
        }
        if (extraCount > 0) {
            val moreHost = FrameLayout(activity).apply {
                background = rounded(COLOR_COVER, activity.dp(10))
                Ds.clipRounded(this, 10)
                Ds.bindPressFeedback(this, 0.97f)
                setOnClickListener {
                    ImagePreviewDialog.show(activity, urls, shown, posterProvider = posterProvider, onFeedback = onFeedback)
                }
            }
            val moreImage = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            cardImages[shown] = moreImage
            loadCommentPicture(moreImage, pics[shown].url)
            moreHost.addView(moreImage, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            moreHost.addView(View(activity).apply {
                setBackgroundColor(0x66000000.toInt())
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            moreHost.addView(TextView(activity).apply {
                text = "+$extraCount"
                textSize = 20f
                setTextColor(Color.WHITE)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setShadowLayer(activity.dp(4).toFloat(), 0f, activity.dp(1).toFloat(), 0x99000000.toInt())
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            imageRow.addView(moreHost, LinearLayout.LayoutParams(cardW, cardH).apply {
                rightMargin = gap
            })
        }
        return imageRow
    }

    fun buildCommentUpBadge(activity: Activity): TextView = TextView(activity).apply {
        text = "UP"
        textSize = 9.5f
        typeface = Ds.body(bold = true)
        setTextColor(Color.WHITE)
        includeFontPadding = false
        background = Ds.card(activity, COLOR_ROSE, 0, radiusDp = 4, borderPx = 0)
        gravity = Gravity.CENTER
        setPadding(activity.dp(5), activity.dp(2), activity.dp(5), activity.dp(2))
    }

    fun buildSubCommentItem(
        activity: Activity,
        sub: VideoComment,
        ownerMid: Long,
        density: Float,
        onFeedback: (String) -> Unit
    ): View {
        val line = TextView(activity).apply {
            val span = SpannableStringBuilder()
            if (isCommentFromUp(sub, ownerMid)) {
                val badgeStart = span.length
                span.append("UP")
                span.setSpan(
                    InlineUpBadgeSpan(
                        fillColor = COLOR_ROSE,
                        textColor = Color.WHITE,
                        density = density,
                        textSizePx = 9f * density
                    ),
                    badgeStart, span.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val nameStart = span.length
            span.append(sub.user)
            span.setSpan(
                ForegroundColorSpan(Ds.colors.cold),
                nameStart, span.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (sub.userMid > 0L) {
                span.setSpan(
                    UserNameSpan(activity, sub.user, sub.userMid),
                    nameStart, span.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            span.append("：")
            span.append(commentSpannable(activity, sub.content, sub.emotes, emoteSizeDp = 17, mentions = sub.mentions))
            text = span
            textSize = 13f
            setTextColor(COLOR_INK)
            setLineSpacing(2.5f * density, 1f)
            setPadding(0, 0, 0, 0)
            setTextIsSelectable(false)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        attachEmoteSpans(line)
        line.highlightColor = Color.TRANSPARENT
        installEmoteTapHandler(activity, line, onFeedback) {
            (line.parent as? View)?.performClick()
        }
        return line
    }

    /** 评论点赞按钮，支持任意 commentType + targetOid（视频=1/aid，动态=11/17等），按内容自适应宽度。 */
    class CommentLikeButton(
        private val activity: Activity,
        private var comment: VideoComment,
        private var targetOid: String,
        private var commentType: Int,
        private val onFeedback: (String) -> Unit
    ) : View(activity) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f * density
            typeface = Ds.body(bold = false)
        }
        private var liked: Boolean
        private var likes: Long
        private var inFlight = false
        private val thumbPath = Path()
        private var key = CommentLikeKey(BiliSessionStore.snapshot().epoch, commentType, targetOid, comment.id)
        private val listener = CommentLikes.Listener { state ->
            liked = state.liked
            likes = state.count
            inFlight = state.pending
            isEnabled = !state.pending
            requestLayout()
            invalidate()
        }

        init {
            val initial = CommentLikes.read(key, comment)
            liked = initial.liked
            likes = initial.count
            isClickable = true
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { toggleLike() }
        }

        private fun dpf(v: Float): Float = v * density

        private fun countLabel(): String = if (likes > 0L) formatViews(likes) else ""

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val label = countLabel()
            textPaint.typeface = Ds.body(bold = liked)
            val textW = if (label.isEmpty()) 0f else textPaint.measureText(label) + dpf(4f)
            val desiredW = (dpf(22f) + textW + dpf(4f)).roundToInt().coerceAtLeast(activity.dp(34))
            val desiredH = activity.dp(26)
            setMeasuredDimension(
                resolveSize(desiredW, widthMeasureSpec),
                resolveSize(desiredH, heightMeasureSpec)
            )
        }

        override fun onDraw(canvas: Canvas) {
            val color = if (liked) COLOR_ROSE else COLOR_MUTED
            paint.color = color
            paint.strokeWidth = dpf(1.55f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val cx = dpf(9f)
            val cy = height / 2f
            paint.style = if (liked) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            val thumb = thumbPath.apply {
                rewind()
                moveTo(cx - dpf(1.1f), cy - dpf(5.3f))
                quadTo(cx + dpf(1.5f), cy - dpf(6.5f), cx + dpf(2.9f), cy - dpf(4.0f))
                lineTo(cx + dpf(2.0f), cy - dpf(1.1f))
                lineTo(cx + dpf(5.1f), cy - dpf(0.5f))
                quadTo(cx + dpf(6.5f), cy + dpf(0.2f), cx + dpf(6.0f), cy + dpf(2.3f))
                lineTo(cx + dpf(5.2f), cy + dpf(5.1f))
                quadTo(cx + dpf(4.7f), cy + dpf(6.1f), cx + dpf(3.2f), cy + dpf(5.9f))
                lineTo(cx - dpf(3.4f), cy + dpf(5.8f))
                quadTo(cx - dpf(4.8f), cy + dpf(5.6f), cx - dpf(5.0f), cy + dpf(4.2f))
                lineTo(cx - dpf(4.8f), cy + dpf(0.6f))
                quadTo(cx - dpf(4.6f), cy - dpf(0.9f), cx - dpf(3.0f), cy - dpf(1.3f))
                lineTo(cx - dpf(1.6f), cy - dpf(1.4f))
                close()
            }
            canvas.drawPath(thumb, paint)
            val text = countLabel()
            if (text.isNotEmpty()) {
                textPaint.color = color
                textPaint.typeface = Ds.body(bold = liked)
                val fm = textPaint.fontMetrics
                val baseline = cy - (fm.ascent + fm.descent) * 0.5f
                canvas.drawText(text, dpf(19.5f), baseline, textPaint)
            }
        }

        fun bind(comment: VideoComment, targetOid: String, commentType: Int) {
            CommentLikes.unsubscribe(key, listener)
            this.comment = comment
            this.targetOid = targetOid
            this.commentType = commentType
            key = CommentLikeKey(BiliSessionStore.snapshot().epoch, commentType, targetOid, comment.id)
            listener.changed(CommentLikes.read(key, comment))
            if (isAttachedToWindow) CommentLikes.subscribe(key, listener)
        }

        fun unbind() {
            CommentLikes.unsubscribe(key, listener)
            animate().cancel()
            scaleX = 1f
            scaleY = 1f
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            listener.changed(CommentLikes.read(key, comment))
            CommentLikes.subscribe(key, listener)
        }

        override fun onDetachedFromWindow() {
            unbind()
            super.onDetachedFromWindow()
        }

        private fun toggleLike() {
            if (inFlight) return
            if (targetOid.isBlank() || commentType <= 0 || comment.id <= 0L) return
            val operationKey = key
            CommentLikes.toggle(operationKey, comment) { message ->
                if (key == operationKey && isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed) onFeedback(message)
            }
            animate().scaleX(1.22f).scaleY(1.22f).setDuration(100)
                .withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(140).start() }.start()
        }
    }

    /** 楼中楼评论详情底部抽屉，支持自定义 anchorTopY（视频页放在 tab 下方，动态详情页放在顶栏下方）。 */
    class ReplySheetDialog(
        private val activity: Activity,
        private val root: VideoComment,
        private val targetOid: String,
        private val commentType: Int,
        private val ownerMid: Long,
        private val anchorTopProvider: () -> Int,
        private val onFeedback: (String) -> Unit
    ) : Dialog(activity) {
        private var closing = false
        private var panelHeightPx = 0
        private var panelTopPx = 0
        private var panelWidthPx = 0
        private val useSideDrawer = LayoutMode.isWideScreen(activity)
        private lateinit var comments: CommentListView
        private lateinit var panel: LinearLayout

        private val sheetBounds: CommentSheetBounds
            get() = commentSheetBounds(
                anchorTopProvider(),
                activity.resources.displayMetrics.heightPixels,
                activity.dp(260)
            )
        private val sheetTopY: Int get() = sheetBounds.topPx
        private val sheetHeight: Int get() = sheetBounds.heightPx

        init {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            window?.setBackgroundDrawable(ColorDrawable(0x00000000))
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }

        override fun onStart() {
            super.onStart()
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            window?.setGravity(Gravity.BOTTOM)
            window?.setBackgroundDrawable(ColorDrawable(0x00000000))
            window?.setWindowAnimations(0)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val overlay = FrameLayout(activity).apply {
                setBackgroundColor(0x2E000000)
                setOnClickListener { dismissWithAnim() }
            }
            panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedTop(COLOR_BACKGROUND, activity.dp(18))
                visibility = View.INVISIBLE
                translationY = 0f
            }
            panel.addView(View(activity).apply {
                background = Ds.pill(activity, COLOR_BORDER)
            }, LinearLayout.LayoutParams(activity.dp(34), activity.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = activity.dp(8)
            })
            panel.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(16), activity.dp(10), activity.dp(14), activity.dp(10))
                addView(TextView(activity).apply {
                    text = "评论详情"
                    textSize = 15.5f
                    typeface = Ds.display(bold = true)
                    setTextColor(COLOR_INK)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ds.closeIcon(activity, COLOR_MUTED, strokeDp = 1.8f).apply {
                    background = Ds.pill(activity, Ds.colors.surfaceRaised, Ds.colors.lineSoft, Ds.hairline(activity))
                    setPadding(activity.dp(7), activity.dp(7), activity.dp(7), activity.dp(7))
                    isClickable = true
                    contentDescription = "关闭"
                    Ds.bindPressFeedback(this, 0.90f)
                    setOnClickListener { dismissWithAnim() }
                }, LinearLayout.LayoutParams(activity.dp(30), activity.dp(30)))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            panel.addView(View(activity).apply { setBackgroundColor(Ds.colors.lineSoft) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(activity)))
            comments = CommentListView(activity, onFeedback, anchorTopProvider, sheetRoot = root, showSort = false)
            comments.bindTarget(targetOid, commentType, ownerMid)
            panel.addView(comments, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            overlay.addView(panel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, sheetHeight, Gravity.BOTTOM
            ))
            setContentView(overlay)
            panel.post {
                val location = IntArray(2)
                overlay.getLocationOnScreen(location)
                val overlayTop = location[1]
                val overlayWidth = overlay.width.coerceAtLeast(1)
                val overlayHeight = overlay.height.coerceAtLeast(1)
                if (useSideDrawer) {
                    // 平板横屏：以右侧滑出抽屉呈现，宽度约 38%（360~460dp），不遮挡左侧视频播放画面
                    panelWidthPx = minOf(overlayWidth, (overlayWidth * 0.38f).toInt().coerceIn(activity.dp(360), activity.dp(460)))
                    panelHeightPx = overlayHeight
                    panel.background = Ds.card(
                        activity,
                        fill = COLOR_BACKGROUND,
                        border = Ds.colors.lineSoft,
                        radiusDp = 0,
                        borderPx = Ds.hairline(activity)
                    )
                    panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                        width = panelWidthPx
                        height = panelHeightPx
                        gravity = Gravity.TOP or Gravity.END
                    }
                    panel.translationY = 0f
                    panel.translationX = panelWidthPx.toFloat()
                    panel.visibility = View.VISIBLE
                    panel.animate().translationX(0f).setDuration(220)
                        .setInterpolator(DecelerateInterpolator()).start()
                } else {
                    panelTopPx = (sheetTopY - overlayTop)
                        .coerceIn(0, (overlayHeight - activity.dp(260)).coerceAtLeast(0))
                    panelHeightPx = (overlayHeight - panelTopPx).coerceAtLeast(1)
                    panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                        width = FrameLayout.LayoutParams.MATCH_PARENT
                        height = panelHeightPx
                        gravity = Gravity.TOP or Gravity.START
                    }
                    panel.translationY = (panelTopPx + panelHeightPx).toFloat()
                    panel.visibility = View.VISIBLE
                    panel.animate().translationY(panelTopPx.toFloat()).setDuration(220)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
            }
            comments.setPageActive(true)
        }

        private fun dismissWithAnim() {
            if (!isShowing || closing) return
            closing = true
            comments.setPageActive(false)
            val anim = if (useSideDrawer) {
                panel.animate().translationX(panelWidthPx.toFloat())
            } else {
                panel.animate().translationY((panelTopPx + panelHeightPx).toFloat())
            }
            anim.setDuration(180)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { dismiss() }.start()
        }

        override fun onStop() {
            if (::comments.isInitialized) comments.dispose()
            super.onStop()
        }

        override fun onBackPressed() {
            dismissWithAnim()
        }
    }

    /** 在任意 TextView 上为指定的表情映射挂载内嵌图片 Span 及点击弹窗预览（兼容动态正文与评论区）。 */
    fun attachEmoteSpans(tv: TextView) {
        val spanned = tv.text as? Spanned ?: return
        spanned.getSpans(0, spanned.length, EmoteImageSpan::class.java).forEach { it.attach(tv) }
    }

    fun applyEmotesToSpannable(
        activity: Activity,
        sb: SpannableStringBuilder,
        rawText: String,
        emotes: Map<String, String>,
        sizeDp: Int = 18
    ) {
        if (emotes.isEmpty()) return
        val sizePx = activity.dp(sizeDp)
        emotes.forEach { (code, url) ->
            if (code.isBlank() || url.isBlank()) return@forEach
            var idx = rawText.indexOf(code)
            while (idx >= 0) {
                sb.setSpan(
                    EmoteImageSpan(code, url, sizePx),
                    idx,
                    (idx + code.length).coerceAtMost(sb.length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = rawText.indexOf(code, idx + code.length)
            }
        }
    }

    fun installEmoteTapHandler(
        activity: Activity,
        tv: TextView,
        onFeedback: (String) -> Unit,
        onPlainTap: (() -> Unit)? = null
    ) {
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var tappedSpan: EmoteImageSpan? = null
        var tappedSpanBounds: RectF? = null
        var tappedClickable: android.text.style.ClickableSpan? = null
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        tv.setOnTouchListener { view, event ->
            val widget = view as TextView
            val text = widget.text as? Spanned
            // 已进入系统选词态：全部交给系统处理，不干扰拖手柄
            if (widget.selectionStart != widget.selectionEnd) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = SystemClock.uptimeMillis()
                    tappedSpan = null
                    tappedSpanBounds = null
                    tappedClickable = null
                    if (text != null && widget.layout != null) {
                        val hit = hitTestEmoteSpan(activity, widget, text, event.x, event.y)
                        if (hit != null) {
                            tappedSpan = hit.first
                            tappedSpanBounds = hit.second
                        } else {
                            tappedClickable = hitTestClickableSpan(activity, widget, text, event.x, event.y)
                        }
                    }
                    // 必须返回 false，否则 TextView 收不到长按计时器，选词会失效
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
                    val elapsed = SystemClock.uptimeMillis() - downTime
                    if (moved <= touchSlop && elapsed < 350L) {
                        val span = tappedSpan
                        val bounds = tappedSpanBounds
                        if (span != null && bounds != null) {
                            showEmoteBubblePopup(activity, widget, span.code, span.url, bounds, onFeedback)
                            return@setOnTouchListener true
                        }
                        val link = tappedClickable
                        if (link != null) {
                            link.onClick(widget)
                            return@setOnTouchListener true
                        }
                        onPlainTap?.invoke()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    tappedSpan = null
                    tappedSpanBounds = null
                    tappedClickable = null
                }
            }
            false
        }
    }

    /** 命中触摸点下的 ClickableSpan（@用户 / 网址 / 子评论作者名）。 */
    private fun hitTestClickableSpan(
        activity: Activity,
        tv: TextView,
        spanned: Spanned,
        touchX: Float,
        touchY: Float
    ): android.text.style.ClickableSpan? {
        val layout = tv.layout ?: return null
        val x = touchX - tv.totalPaddingLeft + tv.scrollX
        val y = touchY - tv.totalPaddingTop + tv.scrollY
        if (x < 0f || y < 0f) return null
        val line = layout.getLineForVertical(y.toInt())
        if (y > layout.getLineBottom(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        spanned.getSpans(offset, offset, android.text.style.ClickableSpan::class.java)
            .firstOrNull()?.let { return it }
        // 点中字符左半边时 offset 会落在下一个字符，回退一格再试
        if (offset > 0) {
            spanned.getSpans(offset - 1, offset - 1, android.text.style.ClickableSpan::class.java)
                .firstOrNull()?.let { return it }
        }
        return null
    }

    fun commentSpannable(
        activity: Activity,
        content: String,
        emotes: Map<String, String>,
        emoteSizeDp: Int = 22,
        jumpLinks: Map<String, String> = emptyMap(),
        titles: Map<String, String> = emptyMap(),
        mentions: Map<String, Long> = emptyMap()
    ): SpannableString {
        val display = applyBiliTitles(content, titles)
        val spannable = SpannableString(display)
        // 1. 表情优先落地，后续链接/@ 需避让其区间
        if (emotes.isNotEmpty()) {
            val sizePx = activity.dp(emoteSizeDp)
            emotes.forEach { (code, url) ->
                if (code.isBlank() || url.isBlank()) return@forEach
                var index = display.indexOf(code)
                while (index >= 0) {
                    spannable.setSpan(
                        EmoteImageSpan(code, url, sizePx), index, index + code.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    index = display.indexOf(code, index + code.length)
                }
            }
        }
        // 2. 网址链接（淡绿可点击）
        applyLinkSpans(activity, display, spannable, jumpLinks, titles)
        // 3. @用户（淡蓝）
        applyMentionSpans(activity, display, spannable, mentions)
        return spannable
    }

    /** 把已取到标题的 B 站链接原文替换为标题文本（仅用于展示）。 */
    private fun applyBiliTitles(content: String, titles: Map<String, String>): String {
        if (titles.isEmpty()) return content
        var result = content
        titles.forEach { (url, title) ->
            if (title.isBlank()) return@forEach
            result = result.replace(url, title)
            // 无协议写法的裸域名也一并替换
            val bare = url.removePrefix("https://").removePrefix("http://")
            if (bare != url) result = result.replace(bare, title)
        }
        return result
    }

    /** 子评论作者名：保持淡蓝色，点击进入该用户主页。 */
    private class UserNameSpan(
        private val activity: Activity,
        private val name: String,
        private val mid: Long
    ) : android.text.style.ClickableSpan() {
        override fun updateDrawState(ds: TextPaint) {
            ds.color = Ds.colors.cold
            ds.isUnderlineText = false
        }

        override fun onClick(widget: View) {
            if (mid > 0L) SpaceActivity.open(activity, mid, name, "")
        }
    }

    /** 纯 URL 与 B 站富文本链接：淡绿色可点击，打开应用内网页。 */
    private fun applyLinkSpans(
        activity: Activity,
        content: String,
        spannable: SpannableString,
        jumpLinks: Map<String, String> = emptyMap(),
        titles: Map<String, String> = emptyMap()
    ) {
        if (content.isBlank()) return
        val skip = ArrayList<IntRange>()
        // 表情区间优先，链接不能覆盖
        spannable.getSpans(0, spannable.length, EmoteImageSpan::class.java).forEach {
            skip.add(spannable.getSpanStart(it)..spannable.getSpanEnd(it))
        }
        // 0. 已换成标题的 B 站链接：点击仍跳原链接
        titles.forEach { (url, title) ->
            if (title.isBlank() || !CommentLinkParser.isSafeUrl(url)) return@forEach
            var index = content.indexOf(title)
            while (index >= 0) {
                val end = index + title.length
                val overlaps = skip.any { index < it.last + 1 && end - 1 >= it.first }
                if (!overlaps) {
                    spannable.setSpan(
                        CommentWebLinkSpan(activity, url), index, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    skip.add(index until end)
                }
                index = content.indexOf(title, end)
            }
        }
        // 1. B 站富文本跳转（如「正片传送门」「收藏集」），优先于纯 URL 扫描
        jumpLinks.forEach { (label, url) ->
            if (label.isBlank() || !CommentLinkParser.isSafeUrl(url)) return@forEach
            var index = content.indexOf(label)
            while (index >= 0) {
                val end = index + label.length
                val overlaps = skip.any { index < it.last + 1 && end - 1 >= it.first }
                if (!overlaps) {
                    spannable.setSpan(
                        CommentWebLinkSpan(activity, url, searchKeywordOf(url)), index, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    skip.add(index until end)
                }
                index = content.indexOf(label, end)
            }
        }
        // 2. 纯文本 URL
        CommentLinkParser.findPlainLinks(content, skip).forEach { link ->
            spannable.setSpan(
                CommentWebLinkSpan(activity, link.url, searchKeywordOf(link.url)),
                link.start, link.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * B 站链接把裸 URL 换成内容标题（视频标题 / 剧集标题）；
     * 非 B 站链接保持原链接。标题取回后重建 span。
     */
    fun resolveBiliTitles(
        activity: Activity,
        tv: TextView,
        content: String,
        emotes: Map<String, String>,
        jumpLinks: Map<String, String>,
        mentions: Map<String, Long> = emptyMap(),
        onResolved: () -> Unit = {}
    ) {
        val bindTag = Any()
        tv.setTag(R.id.comment_title_bind_tag, bindTag)
        val links = CommentLinkParser.findPlainLinks(content).filter {
            searchKeywordOf(it.url) == null && BiliLinkTitle.isBiliUrl(it.url) && !jumpLinks.values.contains(it.url)
        }
        if (links.isEmpty()) return
        BiliLinkTitle.resolveBatchAsync(links.map { it.url }) { titles ->
            if (titles.isEmpty()) return@resolveBatchAsync
            tv.post {
                if (!tv.isAttachedToWindow || tv.getTag(R.id.comment_title_bind_tag) != bindTag) return@post
                if (tv.selectionStart >= 0 && tv.selectionStart != tv.selectionEnd) return@post
                tv.text = commentSpannable(activity, content, emotes, jumpLinks = jumpLinks, titles = titles, mentions = mentions)
                attachEmoteSpans(tv)
                onResolved()
            }
        }
    }

    /** 评论正文里的网址：淡绿色，点击进应用内网页页。 */
    private class CommentWebLinkSpan(
        private val activity: Activity,
        private val url: String,
        private val searchKeyword: String? = null
    ) : android.text.style.ClickableSpan() {
        override fun updateDrawState(ds: TextPaint) {
            ds.color = Ds.colors.success
            ds.isUnderlineText = false
        }

        override fun onClick(widget: View) {
            // 热词 / 搜索类链接走站内搜索页，不跳官方网页
            if (searchKeyword != null) {
                SearchActivity.openWithQuery(activity, searchKeyword)
            } else {
                InAppWebActivity.open(activity, url)
            }
        }
    }

    /** B 站搜索链接（热词）：提取 keyword，命中则返回关键词。 */
    private fun searchKeywordOf(url: String): String? {
        val lower = url.lowercase()
        if (!lower.contains("/search")) return null
        val raw = Regex("""[?&](?:keyword|search_word|q)=([^&]+)""").find(url)?.groupValues?.get(1)
            ?: return null
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** 识别 `@昵称` 并着色；昵称后的分隔符（空格/冒号/标点）不计入。 */
    private fun applyMentionSpans(activity: Activity, content: String, spannable: SpannableString, mentions: Map<String, Long>) {
        CommentMentionResolver.find(content, mentions).forEach { mention ->
            if (spannable.getSpans(mention.start, mention.end, android.text.style.ClickableSpan::class.java).isNotEmpty() ||
                spannable.getSpans(mention.start, mention.end, EmoteImageSpan::class.java).isNotEmpty()) return@forEach
            spannable.setSpan(MentionSpan(activity, mention.name, mention.mid), mention.start, mention.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** @用户 文本样式：淡蓝色，点击进入该用户主页（仅当能解析到 mid）。 */
    private class MentionSpan(
        private val activity: Activity,
        private val name: String,
        private val mid: Long
    ) : android.text.style.ClickableSpan() {
        override fun updateDrawState(ds: TextPaint) {
            ds.color = Ds.colors.cold
            ds.isUnderlineText = false
        }

        override fun onClick(widget: View) {
            if (mid > 0L) {
                SpaceActivity.open(activity, mid, name, "")
            }
        }
    }

    private fun hitTestEmoteSpan(
        activity: Activity,
        tv: TextView,
        spanned: Spanned,
        touchX: Float,
        touchY: Float
    ): Pair<EmoteImageSpan, RectF>? {
        val layout = tv.layout ?: return null
        val x = touchX - tv.totalPaddingLeft + tv.scrollX
        val y = touchY - tv.totalPaddingTop + tv.scrollY
        if (x < 0f || y < 0f) return null
        val line = layout.getLineForVertical(y.toInt())
        if (y > layout.getLineBottom(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        val candidates = spanned.getSpans(
            (offset - 1).coerceAtLeast(0),
            (offset + 1).coerceAtMost(spanned.length),
            EmoteImageSpan::class.java
        )
        for (span in candidates) {
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val left = layout.getPrimaryHorizontal(start)
            val right = left + span.spanWidthPx
            val pad = activity.dp(6).toFloat()
            if (x in (left - pad)..(right + pad)) {
                val top = layout.getLineTop(line).toFloat()
                val bottom = layout.getLineBottom(line).toFloat()
                val rect = RectF(
                    left + tv.totalPaddingLeft - tv.scrollX,
                    top + tv.totalPaddingTop - tv.scrollY,
                    right + tv.totalPaddingLeft - tv.scrollX,
                    bottom + tv.totalPaddingTop - tv.scrollY
                )
                return span to rect
            }
        }
        return null
    }

    private fun showEmoteBubblePopup(
        activity: Activity,
        anchor: TextView,
        code: String,
        rawUrl: String,
        anchorBoundsInView: RectF,
        onFeedback: (String) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed || rawUrl.isBlank()) return
        val cleanUrl = originalCoverUrl(rawUrl)
        val emoteSize = activity.dp(76)
        val arrowH = activity.dp(8)
        val arrowW = activity.dp(14)
        val density = activity.resources.displayMetrics.density

        val bubbleBody = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(10))
            background = Ds.card(
                activity,
                fill = COLOR_BACKGROUND,
                border = COLOR_BORDER,
                radiusDp = Ds.Radius.card,
                borderPx = Ds.hairline(activity)
            )
            elevation = 8f * density
        }

        val preview = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        CoverLoader.load(preview, cleanUrl, original = false, cropThumb = false, animate = true, thumbEdge = 280)
        bubbleBody.addView(preview, LinearLayout.LayoutParams(emoteSize, emoteSize))

        val label = code.trim('[', ']').ifBlank { "表情" }
        bubbleBody.addView(TextView(activity).apply {
            text = label
            textSize = 11.5f
            setTextColor(COLOR_MUTED)
            maxLines = 1
            maxWidth = activity.dp(120)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(6)
        })

        lateinit var popup: PopupWindow
        val saveBtn = TextView(activity).apply {
            text = "保存表情"
            textSize = 12f
            typeface = Ds.body(bold = true)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ds.pill(activity, COLOR_ROSE)
            setPadding(activity.dp(14), activity.dp(6), activity.dp(14), activity.dp(6))
            isClickable = true
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener {
                popup.dismiss()
                ImagePreviewDialog.downloadImageToGallery(activity, cleanUrl, onFeedback)
            }
        }
        bubbleBody.addView(saveBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = activity.dp(8)
        })

        val arrowView = object : View(activity) {
            var arrowCenterX = 0f
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_BACKGROUND
                style = Paint.Style.FILL
            }
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_BORDER
                style = Paint.Style.STROKE
                strokeWidth = Ds.hairline(activity).toFloat()
            }
            private val path = Path()
            override fun onDraw(canvas: Canvas) {
                val cx = if (arrowCenterX > 0f) arrowCenterX else width / 2f
                val half = arrowW / 2f
                path.reset()
                path.moveTo(cx - half, 0f)
                path.lineTo(cx, height.toFloat())
                path.lineTo(cx + half, 0f)
                path.close()
                canvas.drawPath(path, fillPaint)
                canvas.drawLine(cx - half, 0f, cx, height.toFloat(), borderPaint)
                canvas.drawLine(cx, height.toFloat(), cx + half, 0f, borderPaint)
            }
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(activity.dp(6), activity.dp(4), activity.dp(6), activity.dp(2))
            clipChildren = false
            clipToPadding = false
        }
        container.addView(bubbleBody, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        container.addView(arrowView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, arrowH
        ).apply {
            topMargin = -Ds.hairline(activity)
        })

        container.measure(
            View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
        )
        val popW = container.measuredWidth
        val popH = container.measuredHeight

        val anchorLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        val screenW = activity.resources.displayMetrics.widthPixels
        val emoteCenterX = anchorLoc[0] + anchorBoundsInView.centerX()
        val emoteTopY = anchorLoc[1] + anchorBoundsInView.top

        val marginEdge = activity.dp(8)
        val popupX = (emoteCenterX - popW / 2f)
            .coerceIn(marginEdge.toFloat(), (screenW - popW - marginEdge).toFloat().coerceAtLeast(marginEdge.toFloat()))
            .roundToInt()
        val popupY = (emoteTopY - popH - activity.dp(2)).roundToInt().coerceAtLeast(activity.dp(24))
        arrowView.arrowCenterX = (emoteCenterX - popupX - activity.dp(6))
            .coerceIn(activity.dp(18).toFloat(), (popW - activity.dp(18)).toFloat())

        popup = PopupWindow(container, popW, popH, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 8f * density
        }
        container.alpha = 0f
        container.scaleX = 0.92f
        container.scaleY = 0.92f
        container.pivotX = arrowView.arrowCenterX + activity.dp(6)
        container.pivotY = popH.toFloat()
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popupX, popupY)
        container.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150L)
            .setInterpolator(MotionTokens.easeOut)
            .start()
    }

    fun formatCommentTime(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "时间未知"
        val date = Date(epochSeconds * 1000L)
        val now = System.currentTimeMillis()
        val diff = now - date.time
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L}分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L}小时前"
            diff < 7L * 86_400_000L -> "${diff / 86_400_000L}天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(date)
        }
    }

    private fun roundedTop(fill: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadii = floatArrayOf(
            radius.toFloat(), radius.toFloat(),
            radius.toFloat(), radius.toFloat(),
            0f, 0f, 0f, 0f
        )
    }

    private class InlineUpBadgeSpan(
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
        private val padX = 4.5f * density
        private val padY = 1.5f * density
        private val rightGap = 4f * density
        private val radius = 3.5f * density
        private val rect = RectF()
        private val badgeWidth: Int = kotlin.math.ceil(textPaint.measureText("UP") + padX * 2f).toInt()

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: FontMetricsInt?): Int =
            badgeWidth + kotlin.math.ceil(rightGap).toInt()

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            val textFm = textPaint.fontMetrics
            val textH = textFm.descent - textFm.ascent
            val boxH = textH + padY * 2f
            val centerY = (top + bottom) / 2f
            val boxTop = centerY - boxH / 2f
            rect.set(x, boxTop, x + badgeWidth, boxTop + boxH)
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            val baseline = centerY - (textFm.ascent + textFm.descent) / 2f
            canvas.drawText("UP", rect.centerX(), baseline, textPaint)
        }
    }

    class EmoteImageSpan(
        val code: String,
        val url: String,
        private val sizePx: Int
    ) : ReplacementSpan() {
        val spanWidthPx: Int get() = sizePx
        private var host: WeakReference<TextView>? = null
        private val bounds = RectF()
        private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun bindHost(view: TextView) {
            host = WeakReference(view)
        }

        fun attach(view: TextView) {
            host = WeakReference(view)
            InlineEmotes.bitmap(url, view)
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: FontMetricsInt?): Int {
            if (fm != null) {
                // 用 paint 自身的 fontMetrics 作为基准，避免同行多个表情反复累加撑大行高
                val pfm = paint.fontMetricsInt
                val fontH = pfm.descent - pfm.ascent
                if (sizePx > fontH) {
                    val extra = sizePx - fontH
                    // 上方多分一些，视觉中心对齐汉字中线
                    val topExtra = (extra * 0.55f).toInt()
                    val bottomExtra = extra - topExtra
                    fm.ascent = minOf(fm.ascent, pfm.ascent - topExtra)
                    fm.descent = maxOf(fm.descent, pfm.descent + bottomExtra)
                    fm.top = minOf(fm.top, fm.ascent)
                    fm.bottom = maxOf(fm.bottom, fm.descent)
                }
            }
            return sizePx
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            val tv = host?.get()
            // draw() 热路径先查内存，未命中时只在视口内由 InlineEmotes 轻量入队
            val bitmap = InlineEmotes.bitmap(url, tv, allowRequest = true) ?: return
            // 按文字基线中线对齐，保证首行/中间行/末行一致
            val pfm = paint.fontMetrics
            val centerY = y + (pfm.ascent + pfm.descent) / 2f
            val yTop = centerY - sizePx / 2f
            bounds.set(x, yTop, x + sizePx, yTop + sizePx)
            canvas.drawBitmap(bitmap, null, bounds, filterPaint)
        }
    }

    private object InlineEmotes {
        private const val MAX_BYTES = 2L * 1024L * 1024L
        private const val DECODE_EDGE = 128
        private const val RETRY_COOLDOWN_MS = 5_000L
        private const val MAX_RETRY_PER_URL = 2
        private val main = Handler(Looper.getMainLooper())
        // 收紧表情并发为 2，避免抢占评论配图与视频带宽
        private val pool = ThreadPoolExecutor(2, 2, 20L, TimeUnit.SECONDS, LinkedBlockingQueue(64)).apply {
            allowCoreThreadTimeOut(true)
        }
        private val images = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
        private val retryAfter = LruCache<String, Long>(128)
        private val failures = LruCache<String, EmoteFailure>(128)
        private val pending = HashMap<String, WeakHashMap<TextView, Unit>>()
        private val scheduledRetries = HashMap<String, WeakHashMap<TextView, Unit>>()
        private val retryRunnables = HashMap<String, Runnable>()
        private val pendingRefreshViews = WeakHashMap<TextView, Unit>()
        private var refreshScheduled = false
        private val attachedListeners = WeakHashMap<TextView, View.OnAttachStateChangeListener>()
        @Volatile private var diskCache: EmoteDiskCache? = null

        private fun isNearViewport(view: TextView): Boolean {
            if (!view.isAttachedToWindow || !view.isShown || view.windowVisibility != View.VISIBLE) return false
            val rect = android.graphics.Rect()
            if (view.getGlobalVisibleRect(rect)) return true
            var p: android.view.ViewParent? = view.parent
            var sv: ScrollView? = null
            while (p is View) {
                if (p is ScrollView) { sv = p; break }
                p = p.parent
            }
            val scroll = sv ?: return false
            if (!scroll.getGlobalVisibleRect(rect)) return false
            val vh = scroll.height
            if (vh <= 0) return false
            val childRect = android.graphics.Rect(0, 0, view.width, view.height)
            try {
                scroll.offsetDescendantRectToMyCoords(view, childRect)
            } catch (_: IllegalArgumentException) {
                return false
            }
            val top = scroll.scrollY - (vh * 0.25f).toInt()
            val bottom = scroll.scrollY + vh + (vh * 0.75f).toInt()
            return childRect.bottom >= top && childRect.top <= bottom
        }

        fun bitmap(url: String, view: TextView?, allowRequest: Boolean = true): Bitmap? {
            if (url.isBlank()) return null
            images.get(url)?.let { return it }
            if (!allowRequest || view == null) return null
            val app = view.context.applicationContext
            if (!view.isAttachedToWindow || !isNearViewport(view)) {
                ensureAttachReload(view)
                return null
            }
            val now = SystemClock.uptimeMillis()
            val failure = failures.get(url)
            val blockedUntil = maxOf(retryAfter.get(url) ?: 0L, failure?.retryAtMs ?: 0L)
            if (blockedUntil > now) {
                if ((failure?.attempts ?: 0) < MAX_RETRY_PER_URL) scheduleRetryAfterCooldown(url, view, blockedUntil - now)
                return null
            }
            pending[url]?.let { targets ->
                targets[view] = Unit
                return null
            }
            pending[url] = WeakHashMap<TextView, Unit>().apply { put(view, Unit) }
            try {
                pool.execute {
                    val decoded = try {
                        if (diskCache == null) synchronized(this) {
                            if (diskCache == null) diskCache = EmoteDiskCache(File(app.cacheDir, "emote_cache"))
                        }
                        fetch(url)
                    } catch (_: java.io.IOException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                    main.post {
                        val targets = pending.remove(url) ?: return@post
                        if (decoded == null) {
                            val failure = nextEmoteFailure(failures.get(url), SystemClock.uptimeMillis())
                            failures.put(url, failure)
                            Log.w("YuiBiliEmote", "emote-decode-failed key=${url.hashCode()} tries=${failure.attempts}")
                            if (failure.attempts < MAX_RETRY_PER_URL) {
                                targets.keys.forEach { tv ->
                                    if (!usesUrl(tv, url)) return@forEach
                                    if (tv.isAttachedToWindow && isNearViewport(tv)) {
                                        scheduleRetryAfterCooldown(url, tv, RETRY_COOLDOWN_MS + 50L)
                                    } else {
                                        ensureAttachReload(tv)
                                    }
                                }
                            }
                        } else {
                            images.put(url, decoded)
                            retryAfter.remove(url)
                            failures.remove(url)
                            cancelScheduledRetry(url)
                            targets.keys.forEach { tv ->
                                if (!usesUrl(tv, url)) return@forEach
                                if (tv.isAttachedToWindow) {
                                    scheduleCoalescedRefresh(tv)
                                } else {
                                    ensureAttachReload(tv)
                                }
                            }
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                pending.remove(url)
                retryAfter.put(url, SystemClock.uptimeMillis() + 200L)
                scheduleRetryAfterCooldown(url, view, 250L)
            }
            return null
        }

        private fun scheduleCoalescedRefresh(tv: TextView) {
            pendingRefreshViews[tv] = Unit
            if (refreshScheduled) return
            refreshScheduled = true
            main.post {
                refreshScheduled = false
                val batch = pendingRefreshViews.keys.toList()
                pendingRefreshViews.clear()
                batch.forEach { view ->
                    if (view.isAttachedToWindow) refreshEmoteTextView(view)
                }
            }
        }

        private fun refreshEmoteTextView(tv: TextView) {
            if (!tv.isAttachedToWindow) {
                ensureAttachReload(tv)
                return
            }
            // EmoteImageSpan 已有固定宽高，直接重绘无需频繁 removeSpan/setText/requestLayout 触发表单重排
            tv.invalidate()
            tv.postInvalidateOnAnimation()
        }

        private fun cancelScheduledRetry(url: String) {
            retryRunnables.remove(url)?.let { main.removeCallbacks(it) }
            scheduledRetries.remove(url)
        }

        private fun scheduleRetryAfterCooldown(url: String, view: TextView, delayMs: Long) {
            if (!view.isAttachedToWindow) {
                ensureAttachReload(view)
                return
            }
            val waiters = scheduledRetries.getOrPut(url) { WeakHashMap() }
            waiters[view] = Unit
            if (retryRunnables.containsKey(url)) return
            val task = Runnable {
                retryRunnables.remove(url)
                val views = scheduledRetries.remove(url)?.keys?.toList().orEmpty()
                val active = views.filter { it.isAttachedToWindow && isNearViewport(it) && usesUrl(it, url) }
                if (active.isEmpty()) return@Runnable
                val cached = images.get(url)
                if (cached != null) {
                    active.forEach(::refreshEmoteTextView)
                } else {
                    val inFlight = pending[url]
                    if (inFlight != null) {
                        active.forEach { inFlight[it] = Unit }
                        return@Runnable
                    }
                    val first = active.first()
                    bitmap(url, first)
                    val targetMap = pending[url]
                    if (targetMap != null) {
                        active.drop(1).forEach { targetMap[it] = Unit }
                    } else if (images.get(url) != null) {
                        active.forEach(::refreshEmoteTextView)
                    }
                }
            }
            retryRunnables[url] = task
            main.postDelayed(task, delayMs.coerceAtLeast(100L))
        }

        private fun usesUrl(tv: TextView, url: String): Boolean {
            val text = tv.text as? Spanned ?: return false
            return text.getSpans(0, text.length, EmoteImageSpan::class.java).any { it.url == url }
        }

        private fun applyCachedOrTriggerLoad(tv: TextView) {
            val spanned = tv.text as? Spanned ?: return
            var hasCached = false
            spanned.getSpans(0, spanned.length, EmoteImageSpan::class.java).forEach { span ->
                span.bindHost(tv)
                if (bitmap(span.url, tv) != null) hasCached = true
            }
            if (hasCached) refreshEmoteTextView(tv)
        }

        private fun ensureAttachReload(view: TextView) {
            if (attachedListeners.containsKey(view)) return
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    val tv = v as? TextView ?: return
                    applyCachedOrTriggerLoad(tv)
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            }
            attachedListeners[view] = listener
            view.addOnAttachStateChangeListener(listener)
            if (view.isAttachedToWindow) {
                view.post {
                    if (view.isAttachedToWindow) applyCachedOrTriggerLoad(view)
                }
            }
        }

        private fun fetch(url: String): Bitmap? {
            for (address in buildEmoteCandidates(url)) {
                val disk = diskCache
                val name = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(address.toByteArray()).joinToString("") { "%02x".format(it) } + ".img"
                val cached = runCatching { disk?.read(name, MAX_BYTES) }.getOrNull()
                val bytes = if (cached != null) cached else {
                    val request = BiliHttp.request(address).header("Accept", "image/*").build()
                    val loaded = try {
                        BiliHttp.mediaClient.newCall(request).execute().use { response ->
                            val body = response.body ?: return@use null
                            if (!response.isSuccessful || body.contentLength() > MAX_BYTES) return@use null
                            val source = body.source()
                            if (source.request(MAX_BYTES + 1L)) return@use null
                            source.readByteArray()
                        }
                    } catch (_: java.io.IOException) {
                        null
                    }
                    if (loaded == null || loaded.isEmpty()) continue
                    runCatching { disk?.write(name, loaded) }
                    loaded
                }
                val bitmap = decodeBytes(bytes)
                if (bitmap != null) return bitmap
                runCatching { disk?.discard(name) }
            }
            return null
        }

        private fun decodeBytes(bytes: ByteArray): Bitmap? {
            if (bytes.isEmpty()) return null
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                    val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val edge = maxOf(info.size.width, info.size.height)
                        if (edge > DECODE_EDGE) decoder.setTargetSampleSize((edge + DECODE_EDGE - 1) / DECODE_EDGE)
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    if (bmp.width > 0 && bmp.height > 0) return bmp
                } catch (_: Exception) {
                }
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            options.inJustDecodeBounds = false
            val edge = maxOf(options.outWidth, options.outHeight)
            var sample = 1
            while (edge / sample > DECODE_EDGE) sample *= 2
            options.inSampleSize = sample
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }

    internal fun buildEmoteCandidates(rawUrl: String): List<String> {
        val normalized = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("http://") -> "https://${rawUrl.removePrefix("http://")}"
            else -> rawUrl
        }
        val clean = normalized.substringBefore('@')
        val parsed = clean.toHttpUrlOrNull()
        val isBiliCdn = parsed != null && EMOTE_CDN_DOMAINS.any {
            parsed.host == it || parsed.host.endsWith(".$it")
        }
        return buildList {
            if (isBiliCdn) {
                if (normalized.contains('@')) {
                    add(normalized)
                } else {
                    add("$clean@108w_108h_1c.webp")
                    add("$clean@96w_96h.webp")
                }
                add(clean)
            } else {
                add(normalized)
                if (clean != normalized) add(clean)
            }
        }.distinct()
    }
}
