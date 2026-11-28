package com.yuilittle.bili

internal data class CommentSheetBounds(val topPx: Int, val heightPx: Int)

internal fun commentSheetBounds(anchorTopPx: Int, windowHeightPx: Int, minimumHeightPx: Int): CommentSheetBounds {
    val windowHeight = windowHeightPx.coerceAtLeast(1)
    val top = anchorTopPx.coerceIn(0, (windowHeight - minimumHeightPx).coerceAtLeast(0))
    return CommentSheetBounds(top, (windowHeight - top).coerceAtLeast(1))
}

internal fun commentPictureCardWidth(availableWidthPx: Int, cards: Int, gapPx: Int): Int {
    val available = availableWidthPx.coerceAtLeast(1)
    val count = cards.coerceAtLeast(1)
    val gap = gapPx.coerceAtMost((available - count).coerceAtLeast(0) / (count - 1).coerceAtLeast(1))
    return ((available - gap * (count - 1)) / count).coerceAtLeast(1)
}

internal fun commentSinglePictureCardWidth(
    availableWidthPx: Int,
    pictureWidth: Int,
    pictureHeight: Int,
    targetHeightPx: Int,
    minimumWidthPx: Int,
    maximumWidthPx: Int
): Int {
    val available = availableWidthPx.coerceAtLeast(1)
    val intrinsic = if (pictureWidth > 0 && pictureHeight > 0) {
        (targetHeightPx.toDouble() * pictureWidth / pictureHeight).toInt()
    } else targetHeightPx
    val lower = minOf(minimumWidthPx.coerceAtLeast(1), available)
    val upper = minOf(maximumWidthPx.coerceAtLeast(1), available).coerceAtLeast(lower)
    return intrinsic.coerceIn(lower, upper)
}
