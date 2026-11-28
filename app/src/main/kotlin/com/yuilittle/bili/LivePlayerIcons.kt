package com.yuilittle.bili

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * 直播播放器控制栏图标（纯 Canvas 矢量绘制，风格与视频播放页一致）。
 */
internal class LivePlayPauseIcon(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpf(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private var playing = true

    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        invalidate()
    }

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        path.reset()
        if (playing) {
            val barW = dpf(3.6f)
            val gap = dpf(2.6f)
            val h = dpf(10.5f)
            val left = cx - gap / 2f - barW
            path.addRect(left, cy - h / 2f, left + barW, cy + h / 2f, Path.Direction.CW)
            path.addRect(cx + gap / 2f, cy - h / 2f, cx + gap / 2f + barW, cy + h / 2f, Path.Direction.CW)
            canvas.drawPath(path, fillPaint)
        } else {
            val size = dpf(7.4f)
            path.moveTo(cx - size * 0.5f, cy - size)
            path.lineTo(cx + size * 0.72f, cy)
            path.lineTo(cx - size * 0.5f, cy + size)
            path.close()
            canvas.drawPath(path, fillPaint)
        }
    }
}

/** 刷新图标：断口圆环 + 箭头，用于重连直播流。 */
internal class LiveRefreshIcon(context: Context) : View(context) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.9f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val arcRect = RectF()
    private val arrow = Path()

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = dpf(7.6f)
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arcRect, -55f, 280f, false, strokePaint)
        // 箭头：缺口处的切向实心三角形
        val angle = Math.toRadians(-55.0)
        val px = cx + r * kotlin.math.cos(angle).toFloat()
        val py = cy + r * kotlin.math.sin(angle).toFloat()
        val tx = -kotlin.math.sin(angle).toFloat()
        val ty = kotlin.math.cos(angle).toFloat()
        val nx = kotlin.math.cos(angle).toFloat()
        val ny = kotlin.math.sin(angle).toFloat()
        val size = dpf(4.4f)
        arrow.reset()
        arrow.moveTo(px + tx * size * 0.9f, py + ty * size * 0.9f)
        arrow.lineTo(
            px - tx * size * 0.55f + nx * size * 0.85f,
            py - ty * size * 0.55f + ny * size * 0.85f
        )
        arrow.lineTo(
            px - tx * size * 0.55f - nx * size * 0.85f,
            py - ty * size * 0.55f - ny * size * 0.85f
        )
        arrow.close()
        canvas.drawPath(arrow, fillPaint)
    }
}

/** 全屏图标：四角外扩 / 内收，跟随当前状态切换。 */
internal class LiveFullscreenIcon(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpf(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var fullscreen = false

    fun setFullscreen(value: Boolean) {
        if (fullscreen == value) return
        fullscreen = value
        invalidate()
    }

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val s = dpf(8.2f)
        val gap = dpf(2.2f)
        if (fullscreen) {
            // 退出全屏：箭头向内
            canvas.drawLine(cx + s - gap, cy - s + gap, cx + s - gap, cy - gap, paint)
            canvas.drawLine(cx + s - gap, cy - gap, cx + gap, cy - gap, paint)
            canvas.drawLine(cx - s + gap, cy + s - gap, cx - s + gap, cy + gap, paint)
            canvas.drawLine(cx - s + gap, cy + gap, cx - gap, cy + gap, paint)
        } else {
            // 进入全屏：箭头向外
            canvas.drawLine(cx - s, cy - s + gap, cx - gap, cy - s + gap, paint)
            canvas.drawLine(cx - s, cy - s + gap, cx - s, cy - gap, paint)
            canvas.drawLine(cx + s, cy + s - gap, cx + gap, cy + s - gap, paint)
            canvas.drawLine(cx + s, cy + s - gap, cx + s, cy + gap, paint)
        }
    }
}
