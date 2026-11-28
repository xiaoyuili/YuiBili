package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Test

class CommentLayoutPolicyTest {
    @Test fun replySheetAlwaysProducesAValidPositionAndHeightInShortWindows() {
        assertEquals(CommentSheetBounds(0, 200), commentSheetBounds(500, 200, 260))
        assertEquals(CommentSheetBounds(340, 260), commentSheetBounds(900, 600, 260))
        assertEquals(CommentSheetBounds(0, 1), commentSheetBounds(3, 0, 260))
    }

    @Test fun commentPicturesRespectTheActualAvailableColumnWidth() {
        assertEquals(137, commentPictureCardWidth(280, 2, 6))
        assertEquals(89, commentPictureCardWidth(280, 3, 6))
        assertEquals(1, commentPictureCardWidth(1, 3, 6))
    }

    @Test fun singlePictureWidthKeepsAspectRatioWithinTheMeasuredColumn() {
        assertEquals(180, commentSinglePictureCardWidth(180, 2, 1, 132, 72, 228))
        assertEquals(120, commentSinglePictureCardWidth(120, 16, 9, 132, 72, 228))
        assertEquals(1, commentSinglePictureCardWidth(1, 16, 9, 132, 72, 228))
    }
}
