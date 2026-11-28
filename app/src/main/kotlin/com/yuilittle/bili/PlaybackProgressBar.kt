package com.yuilittle.bili

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

internal class PlaybackProgressBar(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var played = 0f
    private var buffered = 0f
    var isDragging = false

    fun setProgress(positionMs: Long, bufferedMs: Long, durationMs: Long) {
        val next = PlayerSeekPolicy.fraction(positionMs, durationMs)
        val buffer = PlayerSeekPolicy.fraction(bufferedMs, durationMs)
        if (next == played && buffer == buffered) return
        played = next
        buffered = buffer
        invalidate()
    }

    fun seekToFraction(fraction: Float) {
        played = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f || height <= 0) return
        val cy = height / 2f
        val r = 2f * density
        val pos = w * played
        paint.style = Paint.Style.FILL
        paint.color = 0x40FFFFFF
        canvas.drawRoundRect(0f, cy - r, w, cy + r, r, r, paint)
        if (buffered > played) {
            paint.color = 0xB3FFFFFF.toInt()
            canvas.drawRoundRect(pos, cy - r, w * buffered, cy + r, r, r, paint)
        }
        paint.color = Ds.colors.accent
        if (pos > 0f) canvas.drawRoundRect(0f, cy - r, pos, cy + r, r, r, paint)
        val radius = minOf(5f * density, w / 2f, cy)
        val x = pos.coerceIn(radius, w - radius)
        canvas.drawCircle(x, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = Color.WHITE
        canvas.drawCircle(x, cy, radius, paint)
    }
}
