package com.yuilittle.bili

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 首页推荐/热门与站内搜索共用的双列视频卡片与骨架屏组件。
 * 保证圆角、描边、阴影、16:10 封面渐变叠层（播放/弹幕/时长）、两行标题、UP 方标或次要元信息完全一致。
 */
internal object VideoCardUi {
    const val CARD_SPACING_DP = 5
    const val CARD_SHADOW_INSET_DP = 2
    const val CARD_FOOTER_DP = 72

    fun cardWidth(
        context: Context,
        columns: Int = 2,
        availableWidthPx: Int = context.resources.displayMetrics.widthPixels
    ): Int {
        val cols = columns.coerceAtLeast(1)
        val totalSpacing = context.dp(CARD_SPACING_DP * (cols + 1) + CARD_SHADOW_INSET_DP * 2)
        return ((availableWidthPx - totalSpacing) / cols).coerceAtLeast(context.dp(120))
    }

    fun coverHeight(cardWidth: Int, compactTablet: Boolean = false): Int =
        if (compactTablet) cardWidth * 9 / 16 else cardWidth * 10 / 16

    fun cardHeight(coverHeight: Int, context: Context, compactTablet: Boolean = false): Int =
        coverHeight + context.dp(if (compactTablet) 62 else CARD_FOOTER_DP)

    data class Holder(
        val cover: ImageView,
        val viewsText: TextView,
        val danmakuIcon: View,
        val danmakuText: TextView,
        val duration: TextView,
        val title: TextView,
        val reasonBadge: TextView,
        val upBadge: View,
        val owner: TextView,
        val extraDotMeta: TextView,
        var boundVideo: VideoItem? = null,
    )

    fun createCard(
        context: Context,
        cardWidth: Int = cardWidth(context),
        coverHeight: Int = coverHeight(cardWidth),
        cardHeight: Int = cardHeight(coverHeight, context),
    ): LinearLayout {
        val borderPx = Ds.hairline(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(
                context,
                COLOR_CARD,
                if (Theme.isDark) Ds.colors.lineSoft else Ds.colors.line,
                Ds.Radius.hairline
            )
            Ds.clipRounded(this, Ds.Radius.hairline)
            elevation = context.dp(2).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val shadowColor = if (Theme.isDark) 0x50000000 else 0x2418191C
                outlineAmbientShadowColor = shadowColor
                outlineSpotShadowColor = shadowColor
            }
            setPadding(borderPx, borderPx, borderPx, borderPx)
            layoutParams = AbsListView.LayoutParams(cardWidth, cardHeight)
        }

        val coverFrame = FrameLayout(context).apply {
            setBackgroundColor(COLOR_COVER)
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_COVER)
        }
        coverFrame.addView(
            cover,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val scrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x7A000000)
            )
        }
        coverFrame.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                context.dp(32),
                Gravity.BOTTOM
            )
        )

        val overlayRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(7), 0, context.dp(7), context.dp(5))
        }
        val playIcon = PlayCountIcon(context)
        overlayRow.addView(
            playIcon,
            LinearLayout.LayoutParams(context.dp(11), context.dp(11)).apply {
                rightMargin = context.dp(3)
            }
        )
        val viewsText = TextView(context).apply {
            textSize = 10.5f
            typeface = Ds.body(true)
            setTextColor(0xF5FFFFFF.toInt())
            maxLines = 1
            includeFontPadding = false
        }
        overlayRow.addView(
            viewsText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val danmakuIcon = DanmakuCountIcon(context)
        overlayRow.addView(
            danmakuIcon,
            LinearLayout.LayoutParams(context.dp(11), context.dp(11)).apply {
                leftMargin = context.dp(8)
                rightMargin = context.dp(3)
            }
        )
        val danmakuText = TextView(context).apply {
            textSize = 10.5f
            typeface = Ds.body(true)
            setTextColor(0xF5FFFFFF.toInt())
            maxLines = 1
            includeFontPadding = false
        }
        overlayRow.addView(danmakuText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val duration = TextView(context).apply {
            textSize = 10.5f
            typeface = Ds.body(true)
            setTextColor(0xF5FFFFFF.toInt())
            includeFontPadding = false
            setPadding(context.dp(4), 0, 0, 0)
        }
        overlayRow.addView(
            duration,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        coverFrame.addView(
            overlayRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        card.addView(coverFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, coverHeight))

        val title = TextView(context).apply {
            textSize = 13.5f
            typeface = Ds.body(true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(context.dp(2).toFloat(), 1f)
            setPadding(context.dp(7), context.dp(6), context.dp(7), 0)
        }
        card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(42)))

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(7), 0, context.dp(7), context.dp(5))
        }
        val reasonBadge = TextView(context).apply {
            textSize = 10f
            typeface = Ds.body(true)
            setTextColor(0xFFFF6633.toInt())
            background = rounded(if (Theme.isDark) 0x2BFF6633 else 0x18FF6633, context.dp(4))
            setPadding(context.dp(5), context.dp(1), context.dp(5), context.dp(1))
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        bottomRow.addView(
            reasonBadge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val upBadge = UpTagView(context)
        bottomRow.addView(
            upBadge,
            LinearLayout.LayoutParams(context.dp(18), context.dp(12)).apply {
                rightMargin = context.dp(4)
            }
        )
        val owner = TextView(context).apply {
            textSize = 11f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        bottomRow.addView(owner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val extraDotMeta = TextView(context).apply {
            textSize = 10.5f
            typeface = Ds.body()
            setTextColor(Ds.colors.subtle)
            maxLines = 1
            includeFontPadding = false
            visibility = View.GONE
            setPadding(context.dp(4), 0, 0, 0)
        }
        bottomRow.addView(
            extraDotMeta,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        card.addView(bottomRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(24)))

        card.tag = Holder(
            cover, viewsText, danmakuIcon, danmakuText, duration,
            title, reasonBadge, upBadge, owner, extraDotMeta
        )
        Ds.bindPressFeedback(card, 0.97f)
        return card
    }

    fun bind(
        card: LinearLayout,
        holder: Holder,
        video: VideoItem,
        showPublishDate: Boolean = false,
        contentDescriptionPrefix: String? = null,
    ) {
        if (holder.boundVideo !== video) {
            holder.boundVideo = video
            val viewsStr = formatViews(video.views)
            val danmakuStr = if (video.danmakuCount > 0L) formatViews(video.danmakuCount) else ""
            val ownerStr = video.owner.ifBlank { "未知UP主" }
            val reasonStr = video.rcmdReason.trim()
            val durationStr = formatDuration(video.duration)

            holder.title.text = video.title
            holder.viewsText.text = viewsStr
            if (danmakuStr.isNotEmpty()) {
                holder.danmakuIcon.visibility = View.VISIBLE
                holder.danmakuText.visibility = View.VISIBLE
                holder.danmakuText.text = danmakuStr
            } else {
                holder.danmakuIcon.visibility = View.GONE
                holder.danmakuText.visibility = View.GONE
            }
            holder.duration.text = durationStr
            if (reasonStr.isNotEmpty()) {
                holder.reasonBadge.visibility = View.VISIBLE
                holder.reasonBadge.text = reasonStr
                holder.upBadge.visibility = View.GONE
                holder.owner.text = ""
                holder.extraDotMeta.visibility = View.GONE
            } else {
                holder.reasonBadge.visibility = View.GONE
                holder.upBadge.visibility = View.VISIBLE
                holder.owner.text = ownerStr
                if (showPublishDate && video.publishedAt > 0L) {
                    holder.extraDotMeta.visibility = View.VISIBLE
                    holder.extraDotMeta.text = formatShortDate(video.publishedAt)
                } else {
                    holder.extraDotMeta.visibility = View.GONE
                }
            }
            card.contentDescription = if (contentDescriptionPrefix != null) {
                "$contentDescriptionPrefix：${video.title}，${formatPublishedDate(video.publishedAt)}，${viewsStr}播放"
            } else {
                "${video.title}，${video.owner}，${viewsStr}播放"
            }
            card.animate().cancel()
            card.alpha = 1f
            card.translationY = 0f
            card.translationZ = 0f
            card.scaleX = 1f
            card.scaleY = 1f
        }
        CoverLoader.load(
            holder.cover,
            video.cover,
            animate = !holder.cover.isAttachedToWindow || holder.cover.drawable == null
        )
    }

    // ─────────────────────────────────────────────────────────
    //  单列横条卡片（播放页「相关推荐」用）：左封面 + 右标题/UP主
    // ─────────────────────────────────────────────────────────

    data class CompactHolder(
        val cover: ImageView,
        val duration: TextView,
        val title: TextView,
        val owner: TextView,
        val views: TextView,
        var boundVideo: VideoItem? = null,
    )

    /**
     * 单列横条卡片：宽度 MATCH_PARENT，不依赖写死像素宽，
     * 因此横竖屏切换、左右栏宽度变化都能自动重排，不会错位。
     */
    fun createCompactRow(context: Context): LinearLayout {
        val borderPx = Ds.hairline(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(
                context,
                COLOR_CARD,
                if (Theme.isDark) Ds.colors.lineSoft else Ds.colors.line,
                Ds.Radius.hairline
            )
            Ds.clipRounded(this, Ds.Radius.hairline)
            setPadding(context.dp(7), context.dp(6), context.dp(7), context.dp(6))
        }

        // 左侧封面：固定 16:9 小缩略图，右下角叠时长
        val coverW = context.dp(112)
        val coverH = context.dp(63)
        val coverFrame = FrameLayout(context).apply {
            setBackgroundColor(COLOR_COVER)
            Ds.clipRounded(this, 5)
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_COVER)
        }
        coverFrame.addView(
            cover,
            FrameLayout.LayoutParams(MATCH_PARENT_VALUE, MATCH_PARENT_VALUE)
        )
        val duration = TextView(context).apply {
            textSize = 10f
            typeface = Ds.body(true)
            setTextColor(0xF5FFFFFF.toInt())
            includeFontPadding = false
            background = rounded(0xB3000000.toInt(), context.dp(3))
            setPadding(context.dp(4), context.dp(1), context.dp(4), context.dp(1))
        }
        coverFrame.addView(
            duration,
            FrameLayout.LayoutParams(WRAP_CONTENT_VALUE, WRAP_CONTENT_VALUE, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = context.dp(3)
                bottomMargin = context.dp(3)
            }
        )
        row.addView(coverFrame, LinearLayout.LayoutParams(coverW, coverH).apply {
            rightMargin = context.dp(9)
        })

        // 右侧：标题（最多两行）+ UP主 · 播放量
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(context).apply {
            textSize = 13f
            typeface = Ds.body(true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(context.dp(1).toFloat(), 1f)
        }
        info.addView(title, LinearLayout.LayoutParams(MATCH_PARENT_VALUE, WRAP_CONTENT_VALUE))
        val metaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val owner = TextView(context).apply {
            textSize = 10.5f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        metaRow.addView(owner, LinearLayout.LayoutParams(0, WRAP_CONTENT_VALUE, 1f))
        val views = TextView(context).apply {
            textSize = 10.5f
            typeface = Ds.body()
            setTextColor(Ds.colors.subtle)
            maxLines = 1
            includeFontPadding = false
            setPadding(context.dp(6), 0, 0, 0)
        }
        metaRow.addView(views, LinearLayout.LayoutParams(WRAP_CONTENT_VALUE, WRAP_CONTENT_VALUE))
        info.addView(metaRow, LinearLayout.LayoutParams(MATCH_PARENT_VALUE, WRAP_CONTENT_VALUE).apply {
            topMargin = context.dp(5)
        })
        row.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT_VALUE, 1f))

        row.tag = CompactHolder(cover, duration, title, owner, views)
        Ds.bindPressFeedback(row, 0.97f)
        return row
    }

    fun bindCompactRow(row: LinearLayout, holder: CompactHolder, video: VideoItem) {
        if (holder.boundVideo !== video) {
            holder.boundVideo = video
            holder.title.text = video.title
            holder.owner.text = video.owner.ifBlank { "未知UP主" }
            holder.views.text = formatViews(video.views)
            holder.duration.text = formatDuration(video.duration)
            row.contentDescription = "${video.title}，${video.owner}，${formatViews(video.views)}播放"
        }
        CoverLoader.load(
            holder.cover,
            video.cover,
            animate = !holder.cover.isAttachedToWindow || holder.cover.drawable == null
        )
    }

    private const val MATCH_PARENT_VALUE = -1
    private const val WRAP_CONTENT_VALUE = -2

    /** 搜索与首页通用的多列骨架屏，支持柔和呼吸动画。 */
    fun createSkeletonLayout(
        context: Context,
        hostOverlay: FrameLayout,
        columns: Int = 2,
        availableWidthPx: Int = context.resources.displayMetrics.widthPixels
    ): View {
        val cols = columns.coerceAtLeast(1)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(CARD_SHADOW_INSET_DP), context.dp(3), context.dp(CARD_SHADOW_INSET_DP), 0)
        }
        val borderPx = Ds.hairline(context)
        val cardW = cardWidth(context, cols, availableWidthPx)
        val coverH = coverHeight(cardW)
        val cardH = cardHeight(coverH, context)
        val placeholderBg = COLOR_COVER
        val placeholderSoft = Ds.colors.lineSoft
        repeat(4) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            repeat(cols) { col ->
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Ds.card(
                        context,
                        COLOR_CARD,
                        if (Theme.isDark) Ds.colors.lineSoft else Ds.colors.line,
                        Ds.Radius.hairline
                    )
                    Ds.clipRounded(this, Ds.Radius.hairline)
                    setPadding(borderPx, borderPx, borderPx, borderPx)
                }
                card.addView(View(context).apply {
                    setBackgroundColor(placeholderBg)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, coverH))
                card.addView(View(context).apply {
                    background = rounded(placeholderSoft, context.dp(4))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(12)).apply {
                    leftMargin = context.dp(8)
                    rightMargin = context.dp(18)
                    topMargin = context.dp(9)
                })
                card.addView(View(context).apply {
                    background = rounded(placeholderSoft, context.dp(4))
                }, LinearLayout.LayoutParams(cardW * 55 / 100, context.dp(12)).apply {
                    leftMargin = context.dp(8)
                    topMargin = context.dp(6)
                })
                card.addView(View(context).apply {
                    background = rounded(placeholderSoft, context.dp(4))
                }, LinearLayout.LayoutParams(cardW * 38 / 100, context.dp(10)).apply {
                    leftMargin = context.dp(8)
                    topMargin = context.dp(10)
                })
                row.addView(card, LinearLayout.LayoutParams(cardW, cardH).apply {
                    if (col < cols - 1) rightMargin = context.dp(CARD_SPACING_DP)
                })
            }
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = context.dp(CARD_SPACING_DP)
            })
        }
        if (!MotionTokens.isReduced(context)) {
            val pulse = ObjectAnimator.ofFloat(container, View.ALPHA, 1f, 0.52f).apply {
                duration = 750L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = MotionTokens.easeOut
            }
            container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (hostOverlay.visibility == View.VISIBLE) pulse.start()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    pulse.cancel()
                }
            })
            container.tag = pulse
        }
        return container
    }

    fun toggleSkeleton(skeletonOverlay: FrameLayout, show: Boolean) {
        val content = skeletonOverlay.getChildAt(0)
        val pulse = content?.tag as? ObjectAnimator
        if (show) {
            if (skeletonOverlay.visibility != View.VISIBLE) {
                skeletonOverlay.animate().cancel()
                skeletonOverlay.alpha = 1f
                skeletonOverlay.visibility = View.VISIBLE
            }
            if (pulse != null && !pulse.isStarted && skeletonOverlay.isAttachedToWindow) {
                pulse.start()
            }
        } else if (skeletonOverlay.visibility == View.VISIBLE) {
            pulse?.cancel()
            content?.alpha = 1f
            if (MotionTokens.isReduced(skeletonOverlay.context)) {
                skeletonOverlay.visibility = View.GONE
            } else {
                skeletonOverlay.animate().cancel()
                skeletonOverlay.animate()
                    .alpha(0f)
                    .setDuration(140L)
                    .setInterpolator(MotionTokens.easeOut)
                    .withEndAction {
                        skeletonOverlay.visibility = View.GONE
                        skeletonOverlay.alpha = 1f
                    }
                    .start()
            }
        }
    }

    private fun formatShortDate(epochSeconds: Long): String {
        val full = formatPublishedDate(epochSeconds)
        if (full.length < 10) return full
        val calNow = java.util.Calendar.getInstance()
        val yearNow = calNow.get(java.util.Calendar.YEAR).toString()
        return if (full.startsWith("$yearNow-")) full.substring(5) else full
    }

    /** 封面左下角轻量圆角播放量小图标。 */
    private class PlayCountIcon(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF5FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * density
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF5FFFFFF.toInt()
            style = Paint.Style.FILL
        }
        private val box = RectF()
        private val tri = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = 0.8f * density
            box.set(pad, pad * 1.4f, width - pad, height - pad * 1.4f)
            canvas.drawRoundRect(box, 2.2f * density, 2.2f * density, paint)
            val cx = width * 0.52f
            val cy = height / 2f
            val r = minOf(width, height) * 0.2f
            tri.rewind()
            tri.moveTo(cx - r * 0.6f, cy - r * 0.9f)
            tri.lineTo(cx + r * 0.9f, cy)
            tri.lineTo(cx - r * 0.6f, cy + r * 0.9f)
            tri.close()
            canvas.drawPath(tri, fill)
        }
    }

    /** 封面左下角轻量弹幕数小图标。 */
    private class DanmakuCountIcon(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF5FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = 0.8f * density
            box.set(pad, pad * 1.4f, width - pad, height - pad * 1.4f)
            canvas.drawRoundRect(box, 2.2f * density, 2.2f * density, paint)
            val y1 = height * 0.42f
            val y2 = height * 0.62f
            canvas.drawLine(width * 0.26f, y1, width * 0.74f, y1, paint)
            canvas.drawLine(width * 0.26f, y2, width * 0.60f, y2, paint)
        }
    }

    /** B 站风轻量描边「UP」小方标，统一克制视觉。 */
    private class UpTagView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * density
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Ds.body(true)
            @Suppress("DEPRECATION")
            textSize = 7.5f * resources.displayMetrics.scaledDensity
        }
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val c = Ds.colors.subtle
            borderPaint.color = c
            textPaint.color = c
            val inset = borderPaint.strokeWidth / 2f
            box.set(inset, inset, width - inset, height - inset)
            val r = 2.5f * density
            canvas.drawRoundRect(box, r, r, borderPaint)
            val fm = textPaint.fontMetrics
            val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText("UP", width / 2f, baseline, textPaint)
        }
    }
}
