package com.yuilittle.bili

import kotlin.math.abs
import kotlin.math.roundToLong

internal object PlayerSeekPolicy {
    const val DOUBLE_TAP_MS = 10_000L
    private const val FULL_SWIPE_MS = 60_000L

    /** 起手阈值不计入距离；满屏位移最多一分钟，与片长无关。 */
    fun swipePosition(startMs: Long, duration: Long, dx: Float, width: Int, deadZone: Float): Long {
        if (duration <= 0 || width <= 0) return startMs.coerceAtLeast(0L)
        val usable = (width - deadZone).coerceAtLeast(1f)
        val fraction = ((abs(dx) - deadZone).coerceAtLeast(0f) / usable).coerceAtMost(1f)
        val delta = (minOf(FULL_SWIPE_MS, duration) * fraction.toDouble()).roundToLong()
        return (startMs + if (dx < 0f) -delta else delta).coerceIn(0L, duration)
    }

    fun doubleTapDelta(fullscreen: Boolean, x: Float, width: Int): Long = when {
        !fullscreen || width <= 0 -> 0L
        x < width * 0.2f -> -DOUBLE_TAP_MS
        x > width * 0.8f -> DOUBLE_TAP_MS
        else -> 0L
    }

    fun fraction(ms: Long, duration: Long): Float =
        if (duration > 0L) (ms.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() else 0f
}
