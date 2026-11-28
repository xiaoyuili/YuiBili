package com.yuilittle.bili

/** 左边缘在 [-字宽, 窗宽] 之间移动；同一个线性变换适用于正向和逆向。 */
internal data class DanmakuTravel(val left: Float, val velocity: Float) {
    fun resized(oldWidth: Int, oldText: Float, width: Int, text: Float): DanmakuTravel {
        if (oldWidth <= 0 || width <= 0) return this
        val scale = (width.toDouble() + text) / (oldWidth.toDouble() + oldText)
        return DanmakuTravel(((left.toDouble() + oldText) * scale - text).toFloat(), (velocity * scale).toFloat())
    }
}

// 已共用一条轨道的两条弹幕，缩窄后仍应留在原轨道内，不能互相盖住。
internal fun resizedTextSize(laneSizePerPixel: Float, desired: Float, width: Int, sharedLane: Boolean): Float =
    if (sharedLane && width > 0) minOf(desired, laneSizePerPixel * width) else desired

internal fun viewportTextFit(availablePx: Float, lineHeight: Float, occupiedLanes: Int): Float =
    if (occupiedLanes <= 1) 1f else (availablePx / (occupiedLanes * lineHeight)).coerceAtMost(1f)
