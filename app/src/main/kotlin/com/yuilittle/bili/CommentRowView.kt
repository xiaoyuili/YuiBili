package com.yuilittle.bili

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal class CommentRowView(
    private val activity: Activity,
    private val sheet: Boolean,
    private val rootTag: Boolean,
    private val openReplies: ((VideoComment) -> Unit)?,
    private val feedback: (String) -> Unit
) : LinearLayout(activity) {
    private var comment: VideoComment? = null
    private val reply = sheet && !rootTag
    private val avatar = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Ds.circle(COLOR_COVER)
        Ds.clipRounded(this, 999)
    }
    private val avatarFrame = FrameLayout(activity).apply {
        background = Ds.pill(activity, COLOR_COVER, Ds.colors.lineSoft, Ds.hairline(activity))
        val pad = Ds.hairline(activity)
        setPadding(pad, pad, pad, pad)
        Ds.bindPressFeedback(this, 0.93f)
        setOnClickListener { openAuthor() }
    }
    private val name = TextView(activity).apply {
        textSize = if (reply) 13f else 13.5f
        typeface = Ds.body(bold = true)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setOnClickListener { openAuthor() }
    }
    private val up = CommentSectionUi.buildCommentUpBadge(activity)
    private val rootBadge = CommentSectionUi.buildRoleTag(activity, "楼主")
    private val pinned = CommentSectionUi.buildRoleTag(activity, "置顶")
    private val meta = TextView(activity).apply {
        textSize = 11.5f
        fontFeatureSettings = "tnum"
        includeFontPadding = false
        setTextColor(Ds.colors.subtle)
        setPadding(0, activity.dp(3), 0, 0)
    }
    private val message = CommentTextView(activity, if (reply) 14f else 14.8f, feedback)
    private val pictures = LinearLayout(activity).apply { orientation = VERTICAL }
    private val likes = CommentSectionUi.CommentLikeButton(activity, emptyComment(), "", 1, feedback)
    private val replies = CommentSectionUi.ReplyCountBadge(activity, 0) { comment?.let { openReplies?.invoke(it) } }
    private val floor = LinearLayout(activity).apply {
        orientation = VERTICAL
        background = Ds.card(activity, Ds.colors.surfaceRaised, Ds.colors.lineSoft, Ds.Radius.control, Ds.hairline(activity))
        setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
        Ds.bindPressFeedback(this, 0.985f)
        setOnClickListener { comment?.let { openReplies?.invoke(it) } }
    }
    private var pictureModels: List<CommentPicture>? = null
    private var previewModels: List<VideoComment>? = null
    private var previewCount = -1L
    private var previewOwner = -1L

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        setPadding(if (sheet) activity.dp(if (rootTag) 16 else 24) else activity.dp(16),
            activity.dp(if (sheet) 13 else 15), activity.dp(16), activity.dp(if (sheet) 13 else 15))
        avatarFrame.addView(avatar, FrameLayout.LayoutParams(-1, -1))
        addView(avatarFrame, LayoutParams(activity.dp(if (reply) 30 else 36), activity.dp(if (reply) 30 else 36)).apply { topMargin = activity.dp(2) })
        val body = LinearLayout(activity).apply { orientation = VERTICAL; setPadding(activity.dp(12), 0, 0, 0) }
        val nameLine = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nameHolder = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = View.MeasureSpec.getSize(widthMeasureSpec)
                name.maxWidth = (available - activity.dp(88)).coerceAtLeast(1)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameHolder.addView(name, LayoutParams(-2, -2))
        for (badge in listOf(up, rootBadge)) nameHolder.addView(badge, LayoutParams(-2, -2).apply { leftMargin = activity.dp(6) })
        nameLine.addView(nameHolder, LayoutParams(0, -2, 1f))
        nameLine.addView(pinned, LayoutParams(-2, -2).apply { leftMargin = activity.dp(8) })
        body.addView(nameLine, LayoutParams(-1, -2))
        body.addView(meta, LayoutParams(-1, -2))
        body.addView(message, LayoutParams(-1, -2))
        body.addView(pictures, LayoutParams(-1, -2))
        val actions = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(8), 0, 0)
            addView(likes, LayoutParams(-2, activity.dp(26)))
            addView(replies, LayoutParams(-2, activity.dp(26)).apply { leftMargin = activity.dp(14) })
        }
        body.addView(actions, LayoutParams(-1, -2))
        body.addView(floor, LayoutParams(-1, -2).apply { topMargin = activity.dp(9) })
        addView(body, LayoutParams(0, -2, 1f))
    }

    fun bind(item: VideoComment, owner: Long, oid: String, type: Int, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
        val different = comment?.id != item.id || comment?.content != item.content ||
            comment?.emotes != item.emotes || comment?.jumpLinks != item.jumpLinks || comment?.mentions != item.mentions
        comment = item
        name.text = item.user
        val byUp = isCommentFromUp(item, owner)
        name.setTextColor(if (byUp) COLOR_ROSE else if (reply) COLOR_MUTED else COLOR_INK)
        name.isClickable = item.userMid > 0L
        avatarFrame.isClickable = item.userMid > 0L
        up.visibility = if (byUp) VISIBLE else GONE
        rootBadge.visibility = if (rootTag) VISIBLE else GONE
        pinned.visibility = if (item.isPinned && !reply) VISIBLE else GONE
        meta.text = buildString {
            append(CommentSectionUi.formatCommentTime(item.publishedAt))
            if (item.location.isNotBlank()) append(" · ${item.location}")
        }
        if (item.avatar.isBlank()) CoverLoader.clear(avatar) else CoverLoader.load(avatar, item.avatar + "@72w_72h_1c.webp", cropThumb = false, thumbEdge = 160)
        if (different) message.bind(item, expanded, onExpanded) else message.setExpanded(expanded, onExpanded)
        if (different) { likes.animate().cancel(); likes.scaleX = 1f; likes.scaleY = 1f }
        if (pictureModels != item.pictures) {
            clearImages(pictures)
            pictures.removeAllViews()
            pictureModels = item.pictures
            if (item.pictures.isNotEmpty()) pictures.addView(CommentSectionUi.buildPicturesRow(activity, item.pictures, feedback), LayoutParams(-1, -2))
        }
        pictures.visibility = if (item.pictures.isEmpty()) GONE else VISIBLE
        likes.bind(item, oid, type)
        val hasReplies = !sheet && openReplies != null && (item.replyCount > 0 || item.replies.isNotEmpty())
        replies.visibility = if (hasReplies) VISIBLE else GONE
        replies.bindCount(maxOf(item.replyCount, item.replies.size.toLong()))
        floor.visibility = if (hasReplies) VISIBLE else GONE
        val floorParams = floor.layoutParams as LayoutParams
        floorParams.width = if (item.replies.isEmpty()) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
        if (!hasReplies && floor.childCount > 0) {
            floor.removeAllViews()
            previewModels = null
        }
        if (hasReplies && (previewModels != item.replies || previewCount != item.replyCount || previewOwner != owner)) {
            floor.removeAllViews()
            previewModels = item.replies
            previewCount = item.replyCount
            previewOwner = owner
            item.replies.take(2).forEachIndexed { index, sub ->
                floor.addView(CommentSectionUi.buildSubCommentItem(activity, sub, owner, resources.displayMetrics.density, feedback), LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = activity.dp(5)
                })
            }
            val more = LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, if (item.replies.isEmpty()) 0 else activity.dp(7), 0, 0)
                addView(TextView(activity).apply {
                    text = if (item.replyCount > item.replies.size) "查看全部 ${item.replyCount} 条回复" else "查看全部回复"
                    textSize = 12f
                    typeface = Ds.body(bold = true)
                    includeFontPadding = false
                    setTextColor(COLOR_ROSE)
                }, LayoutParams(-2, -2))
                addView(Ds.chevronRightIcon(activity, COLOR_ROSE, 1.6f), LayoutParams(activity.dp(12), activity.dp(12)).apply { leftMargin = activity.dp(2) })
            }
            floor.addView(more, LayoutParams(-2, -2))
        }
    }

    fun release() {
        CoverLoader.clear(avatar)
        clearImages(pictures)
        pictures.removeAllViews()
        floor.removeAllViews()
        message.release()
        likes.unbind()
        pictureModels = null
        previewModels = null
        comment = null
    }

    private fun openAuthor() { comment?.takeIf { it.userMid > 0 }?.let { SpaceActivity.open(activity, it.userMid, it.user, it.avatar) } }
    private fun clearImages(view: View) {
        if (view is ImageView) CoverLoader.clear(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) clearImages(view.getChildAt(i))
    }
    private fun emptyComment() = VideoComment(0, "", "", "", 0, 0)
}

internal class CommentTextView(private val activity: Activity, textSp: Float, private val feedback: (String) -> Unit) : LinearLayout(activity) {
    private val body = TextView(activity).apply {
        textSize = textSp
        setTextColor(COLOR_INK)
        setLineSpacing(4.5f * resources.displayMetrics.density, 1f)
        setTextIsSelectable(true)
        isCursorVisible = false
        highlightColor = Ds.colors.accentSoft
        setSpannableFactory(object : Spannable.Factory() {
            override fun newSpannable(source: CharSequence?): Spannable = source as? Spannable ?: super.newSpannable(source)
        })
    }
    private val fade = View(activity).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(COLOR_BACKGROUND and 0x00FFFFFF, COLOR_BACKGROUND))
        isClickable = false
    }
    private val toggle = TextView(activity).apply {
        textSize = 12.5f
        typeface = Ds.body(bold = true)
        setTextColor(COLOR_ROSE)
        setPadding(activity.dp(12), activity.dp(6), activity.dp(2), activity.dp(4))
        Ds.bindPressFeedback(this, 0.94f)
    }
    private val toggleRow = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.END
        setPadding(0, activity.dp(4), 0, 0)
        addView(toggle, LayoutParams(-2, -2))
    }
    private var expanded = false
    private var collapsible = false
    private var onExpanded: (Boolean) -> Unit = {}
    private var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var boundWidth = 0

    init {
        orientation = VERTICAL
        setPadding(0, activity.dp(6), 0, 0)
        val host = FrameLayout(activity)
        host.addView(body, FrameLayout.LayoutParams(-1, -2))
        host.addView(fade, FrameLayout.LayoutParams(-1, activity.dp(22), Gravity.BOTTOM))
        addView(host, LayoutParams(-1, -2))
        addView(toggleRow, LayoutParams(-1, -2))
        toggle.setOnClickListener { expanded = !expanded; onExpanded(expanded); applyState() }
        CommentSectionUi.installEmoteTapHandler(activity, body, feedback) {
            if (collapsible && !expanded) { expanded = true; onExpanded(true); applyState() }
        }
    }

    fun bind(comment: VideoComment, expanded: Boolean, changed: (Boolean) -> Unit) {
        release()
        this.expanded = expanded
        onExpanded = changed
        visibility = if (comment.content.isEmpty()) GONE else VISIBLE
        body.text = CommentSectionUi.commentSpannable(activity, comment.content, comment.emotes, jumpLinks = comment.jumpLinks, mentions = comment.mentions)
        CommentSectionUi.resolveBiliTitles(activity, body, comment.content, comment.emotes, comment.jumpLinks, comment.mentions) { measureFullText() }
        CommentSectionUi.attachEmoteSpans(body)
        measureFullText()
    }

    fun setExpanded(value: Boolean, changed: (Boolean) -> Unit) {
        onExpanded = changed
        if (expanded != value) { expanded = value; applyState() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && boundWidth != w) { boundWidth = w; measureFullText() }
    }

    private fun measureFullText() {
        preDraw?.let { if (body.viewTreeObserver.isAlive) body.viewTreeObserver.removeOnPreDrawListener(it) }
        body.maxLines = Int.MAX_VALUE
        body.ellipsize = null
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (body.viewTreeObserver.isAlive) body.viewTreeObserver.removeOnPreDrawListener(this)
                preDraw = null
                collapsible = (body.layout?.lineCount ?: 0) > 4
                applyState()
                return !collapsible || expanded
            }
        }
        preDraw = listener
        body.viewTreeObserver.addOnPreDrawListener(listener)
        body.requestLayout()
    }

    private fun applyState() {
        body.maxLines = if (collapsible && !expanded) 4 else Int.MAX_VALUE
        body.ellipsize = if (collapsible && !expanded) TextUtils.TruncateAt.END else null
        fade.visibility = if (collapsible && !expanded) VISIBLE else GONE
        toggleRow.visibility = if (collapsible) VISIBLE else GONE
        toggle.text = if (expanded) "收起" else "展开"
    }

    fun release() {
        preDraw?.let { if (body.viewTreeObserver.isAlive) body.viewTreeObserver.removeOnPreDrawListener(it) }
        preDraw = null
        body.setTag(R.id.comment_title_bind_tag, Any())
        body.clearFocus()
        body.text = ""
        collapsible = false
        onExpanded = {}
    }
}
