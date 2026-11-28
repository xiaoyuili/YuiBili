package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentGifPolicyTest {
    @Test
    fun animatedCdnRequestUsesTheOriginalPathAndKeepsTheQuery() {
        val original = "https://i0.hdslb.com/bfs/@user/a.GiF?token=x@y&size=320"
        for (separator in listOf("@", "%40")) {
            val url = "//i0.hdslb.com/bfs/@user/a.GiF${separator}320w_320h.WEBP?token=x@y&size=320"
            val list = requireNotNull(resolveCoverKey(url, original = false, thumbEdge = 320))
            assertTrue(list.gif)
            assertEquals(original, list.source)
            assertEquals(original, originalCoverUrl(url))
            assertEquals(list.flightKey, resolveCoverKey(original, original = true))
        }
    }

    @Test
    fun queryTextAndNonCdnAtSignsAreNotGifTransforms() {
        val external = "https://cdn.example.com/a.GIF@160w.webp?hint=a.gif"
        val still = requireNotNull(resolveCoverKey(external, original = true))
        assertFalse(still.gif)
        assertEquals(external, still.source)
        assertFalse(requireNotNull(resolveCoverKey("https://not-hdslb.com/a.gif@320w.webp", original = false)).gif)
        assertFalse(requireNotNull(resolveCoverKey("https://i0.hdslb.com/a.jpg?file=a.gif", original = true)).gif)
        val literal = "https://cdn.example.com/user@host/a.GIF?token=@"
        assertTrue(requireNotNull(resolveCoverKey(literal, original = false)).gif)
        assertEquals(literal, originalCoverUrl(literal))
    }

    @Test
    fun postersAndThumbnailsDoNotSatisfyAnAnimatedRequestOnReopen() {
        val raw = "http://i0.hdslb.com/bfs/a.GIF?key=1"
        val card = requireNotNull(resolveCoverKey(raw, original = false, thumbEdge = 320))
        val preview = requireNotNull(resolveCoverKey(raw, original = true))
        val neighbor = requireNotNull(resolveCoverKey(raw, original = false, thumbEdge = 480, allowAnimatedGif = false))
        val poster = requireNotNull(card.posterKey)
        val cachedPosters = setOf(poster, neighbor)
        assertFalse(poster.gif)
        assertFalse(neighbor.gif)
        assertEquals("https://i0.hdslb.com/bfs/a.GIF@480w_480h.webp?key=1", neighbor.source)
        assertEquals(card.source, poster.source)
        assertFalse(card in cachedPosters)
        assertFalse(preview in cachedPosters)
        assertNotEquals(card, preview) // 独立的显示尺寸和 Drawable，共享下载而非共享播放实例
        assertEquals(card.flightKey, preview.flightKey)
        assertEquals(preview, resolveCoverKey(originalCoverUrl(neighbor.source), original = true))
        assertNull(preview.posterKey)
    }

    @Test
    fun fallbackIsASingleStaticRequestAndNeverRedownloadsTheSameExternalGif() {
        val raw = "https://i0.hdslb.com/bfs/a.GIF@160w.webp?key=1"
        val gif = requireNotNull(resolveCoverKey(raw, original = false, thumbEdge = 320))
        val fallback = requireNotNull(gif.staticFallback(raw, crop = false))
        assertFalse(fallback.gif)
        assertFalse(fallback.original)
        assertEquals(raw, fallback.source)
        assertNull(fallback.staticFallback(raw, crop = false))
        val external = "https://cdn.example.com/a.GIF"
        assertNull(requireNotNull(resolveCoverKey(external, original = true)).staticFallback(external, crop = false))
    }

    @Test
    fun firstOpenAndNewAssetUseAPosterButSameSlotRerenderKeepsItsDrawable() {
        val url = "https://i0.hdslb.com/a.gif"
        assertTrue(shouldInstallPreviewPoster(null, url, hasDrawable = false))
        assertFalse(shouldInstallPreviewPoster(url, url, hasDrawable = true))
        assertTrue(shouldInstallPreviewPoster(url, url, hasDrawable = false))
        assertTrue(shouldInstallPreviewPoster(url, "$url?new=1", hasDrawable = true))
    }

    @Test
    fun playbackRequiresVisibilityAndCanResumeAfterFocusOrAttachmentReturns() {
        fun eligible(attached: Boolean = true, shown: Boolean = true, focused: Boolean = true, visible: Boolean = true) =
            gifEligible(attached, shown, focused) { visible }
        assertTrue(eligible())
        assertFalse(eligible(visible = false))
        assertFalse(eligible(shown = false))
        assertFalse(eligible(focused = false))
        assertFalse(eligible(attached = false))
        assertTrue(eligible())
    }

    @Test
    fun decodeBudgetsLimitOutputAndDeclineUnsafeNativeAllocations() {
        assertEquals(320 to 180, gifDecodeSize(1920, 1080, 320, canResize = true))
        assertEquals(720 to 1280, gifDecodeSize(1080, 1920, 2048, canResize = true))
        assertEquals(1 to 320, gifDecodeSize(1, 4000, 320, canResize = true))
        assertEquals(320 to 240, gifDecodeSize(320, 240, 320, canResize = false))
        assertNull(gifDecodeSize(1920, 1080, 320, canResize = false)) // Movie 无法缩小源画布
        assertEquals(1920 to 1080, gifDecodeSize(1920, 1080, 2048, canResize = false))
        assertNull(gifDecodeSize(1, 65000, 320, canResize = false))
        assertNull(gifDecodeSize(Int.MAX_VALUE, Int.MAX_VALUE, 320, canResize = true))
        assertNull(gifDecodeSize(0, 240, 320, canResize = true))
    }
}
