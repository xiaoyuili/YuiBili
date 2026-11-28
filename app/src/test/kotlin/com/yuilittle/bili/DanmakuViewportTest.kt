package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuViewportTest {
    @Test fun playingNormalAndReverseKeepRemainingMediaTimeAndDirection() {
        for (speed in listOf(-0.2f, 0.2f)) {
            for (left in listOf(359f, 140f, 0f, -671f)) {
                val original = DanmakuTravel(left, speed)
                val wide = original.resized(360, 672f, 1280, 900f)
                val remaining = if (speed < 0) (left + 672f) / -speed else (360f - left) / speed
                val exitX = if (speed < 0) -900f else 1280f
                assertEquals(exitX, wide.left + remaining * wide.velocity, 0.001f)
                assertTrue(wide.velocity * speed > 0f)
                val later = DanmakuTravel(left + speed * 800f, speed).resized(360, 672f, 1280, 900f)
                assertEquals(later.left, wide.left + 800f * wide.velocity, 0.001f)
            }
        }
    }

    @Test fun pausedRoundTripsAndZeroSizedReparentDoNotRestartTravel() {
        val original = DanmakuTravel(42.75f, -0.13f)
        assertSame(original, original.resized(800, 145f, 0, 188f))
        var d = original
        repeat(50) {
            d = d.resized(800, 145f, 1280, 188f).resized(1280, 188f, 800, 145f)
        }
        assertEquals(original.left, d.left, 0.001f)
        assertEquals(original.velocity, d.velocity, 0.00001f)
    }

    @Test fun sizeOnlyStyleOnlyAndPastTheExitNeverReenter() {
        val d = DanmakuTravel(120f, -0.25f)
        assertEquals(d, d.resized(500, 50f, 500, 50f))
        val rest = (d.left + 50f) / -d.velocity
        for ((w, text) in listOf(500 to 90f, 1000 to 50f)) {
            val scaled = d.resized(500, 50f, w, text)
            assertEquals(-text, scaled.left + rest * scaled.velocity, 0.001f)
        }
        assertTrue(DanmakuTravel(-151f, -0.1f).resized(300, 150f, 900, 200f).left < -200f)
        assertTrue(DanmakuTravel(301f, 0.1f).resized(300, 150f, 900, 200f).left > 900f)
    }

    @Test fun crowdedLanesFitWithoutDiscardingOrOverlappingTheirItems() {
        assertEquals(1f, viewportTextFit(200f, 20f, 4), 0f)
        assertEquals(0.5f, viewportTextFit(100f, 20f, 10), 0f)
        assertEquals(1f, viewportTextFit(200f, 20f, 0), 0f)
        val reference = 20f / 800
        val size = resizedTextSize(reference, 16f, 320, sharedLane = true)
        assertEquals(8f, size, 0f)
        val a = DanmakuTravel(20f, -0.1f).resized(800, 180f, 320, 180f * size / 20f)
        val b = DanmakuTravel(225f, -0.1f).resized(800, 160f, 320, 160f * size / 20f)
        assertTrue(a.left + 180f * size / 20f < b.left)
        assertEquals(16f, resizedTextSize(reference, 16f, 320, sharedLane = false), 0f)
        assertEquals(20f, resizedTextSize(reference, 20f, 800, sharedLane = true), 0f)
    }

    @Test fun pausedSameLaneRestoresTheOriginalFontAfterExpandingAndShrinking() {
        val reference = 13f / 360
        val initial = DanmakuTravel(95f, -0.05f)
        var state = initial
        repeat(50) {
            val large = resizedTextSize(reference, 17.5f, 720, true)
            val small = resizedTextSize(reference, 13f, 360, true)
            assertEquals(17.5f, large, 0.00001f)
            assertEquals(13f, small, 0.00001f)
            state = state.resized(360, 130f, 720, large * 10).resized(720, large * 10, 360, small * 10)
        }
        assertEquals(initial.left, state.left, 0.001f)
        assertEquals(initial.velocity, state.velocity, 0.00001f)
    }
}
