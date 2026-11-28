package com.yuilittle.bili

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.yuilittle.bili.DownloadManager.LocalPlayMode

internal fun playerTapDetector(context: Context, single: () -> Unit, armDouble: () -> Unit, double: (Float) -> Unit): GestureDetector {
    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    return GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { single(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { armDouble(); return true }
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = e.x; downY = e.y; moved = false
            } else if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) moved = true
            if (e.actionMasked == MotionEvent.ACTION_UP && !moved &&
                e.eventTime - e.downTime < android.view.ViewConfiguration.getLongPressTimeout()) double(e.x)
            return true
        }
    }).apply { setIsLongpressEnabled(false) }
}

internal fun cancelTapDetector(detector: GestureDetector, event: MotionEvent) {
    val cancel = MotionEvent.obtain(event)
    cancel.action = MotionEvent.ACTION_CANCEL
    detector.onTouchEvent(cancel)
    cancel.recycle()
}

internal fun showPlayerSpeedMenu(anchor: View, host: View, speeds: FloatArray, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit): PopupWindow? {
    val ctx = anchor.context
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
    }
    val scroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        background = Ds.plate(0xE6181A20.toInt(), 0x26FFFFFF, Ds.hairline(ctx), ctx.dp(12).toFloat())
        addView(row)
    }
    val popup = PopupWindow(scroll, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        elevation = ctx.dp(4).toFloat()
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    }
    speeds.forEachIndexed { i, speed ->
        val choice = TextView(ctx).apply {
            text = "${speed}×"
            textSize = 13f
            typeface = Ds.body(true)
            includeFontPadding = false
            gravity = Gravity.CENTER
            isSelected = i == selected
            isFocusable = true
            contentDescription = "$speed 倍速"
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setTextColor(if (isSelected) Ds.colors.accent else Color.WHITE)
            if (isSelected) background = rounded(0x28F27D9E, dp(8))
            setOnClickListener { popup.dismiss(); onPick(i) }
            Ds.bindPressFeedback(this, 0.96f)
        }
        row.addView(choice, LinearLayout.LayoutParams(
            maxOf(ctx.dp(48), (choice.paint.measureText(choice.text.toString()) + ctx.dp(16)).toInt()),
            maxOf(ctx.dp(48), (choice.paint.fontSpacing + ctx.dp(12)).toInt())))
    }
    val bounds = android.graphics.Rect()
    if (!host.getGlobalVisibleRect(bounds)) { onDismiss(); return null }
    val rootLocation = IntArray(2)
    host.rootView.getLocationOnScreen(rootLocation)
    bounds.offset(rootLocation[0], rootLocation[1])
    val windowBounds = android.graphics.Rect()
    host.getWindowVisibleDisplayFrame(windowBounds)
    if (!bounds.intersect(windowBounds) || bounds.width() <= ctx.dp(16)) { onDismiss(); return null }
    val margin = ctx.dp(8)
    row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    popup.width = minOf(row.measuredWidth, (bounds.width() - margin * 2).coerceAtLeast(1))
    popup.height = minOf(row.measuredHeight, bounds.height().coerceAtLeast(1))
    val location = IntArray(2)
    anchor.getLocationOnScreen(location)
    val left = (location[0] + anchor.width - popup.width)
        .coerceIn(bounds.left + margin, maxOf(bounds.left + margin, bounds.right - margin - popup.width))
    val top = (location[1] - popup.height - margin)
        .coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - popup.height))
    val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (l != ol || t != ot || r != or || b != ob) popup.dismiss()
    }
    host.addOnLayoutChangeListener(layoutListener)
    popup.setOnDismissListener {
        host.removeOnLayoutChangeListener(layoutListener)
        onDismiss()
    }
    popup.showAsDropDown(anchor, left - location[0], top - location[1] - anchor.height, Gravity.LEFT)
    scroll.post { scroll.smoothScrollTo(row.getChildAt(selected).left - popup.width / 2 + row.getChildAt(selected).width / 2, 0) }
    return popup
}

internal fun savedOnlinePlayMode(prefs: SharedPreferences): LocalPlayMode =
    LocalPlayMode.entries.firstOrNull { it.name == prefs.getString("online_queue_mode", null) } ?: LocalPlayMode.SEASON_SEQUENCE

internal fun seasonModeChip(context: Context, mode: () -> LocalPlayMode, onPick: (LocalPlayMode) -> Unit) =
    TextView(context).apply {
        text = mode().label()
        textSize = 11.5f
        typeface = Ds.body(bold = true)
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextColor(COLOR_ROSE)
        background = Ds.pill(context, COLOR_ROSE_SOFT)
        setPadding(dp(9), dp(5), dp(9), dp(5))
        contentDescription = "合集播放模式：$text"
        setOnClickListener {
            val modes = LocalPlayMode.entries
            showAnchoredMenu(this, modes.map { it.label() }, modes.indexOf(mode())) { i ->
                onPick(modes[i])
            }
        }
    }

/** 横条有可能在全屏隐藏状态下重建，等实际视口完成布局再居中并绑定可见卡片。 */
internal fun HorizontalScrollView.bindEpisodeViewport(index: Int, step: Int, cardWidth: Int, total: Int, bind: (Int, Int) -> Unit) {
    addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right > left && right - left != oldRight - oldLeft) post {
            if (width > 0 && isAttachedToWindow) {
                val offset = (index.coerceAtLeast(0) * step - (width - cardWidth) / 2).coerceIn(0, (total - width).coerceAtLeast(0))
                scrollTo(offset, 0)
                bind(scrollX, width)
            }
        }
    }
}
