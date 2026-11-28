package com.yuilittle.bili

import com.yuilittle.bili.DownloadManager.LocalPlayMode.*
import org.junit.Assert.*
import org.junit.Test

class PlayerFeedbackTest {
    private val episodes = listOf(
        UgcEpisode("one", 1, 101, "P1", 6), UgcEpisode("one", 1, 102, "P2", 6),
        UgcEpisode("two", 2, 201, "下一稿", 6))

    @Test fun phoneFullscreenTemporarilyUsesLandscapeWithoutChangingPagePreference() {
        for (direction in LayoutMode.ScreenDirection.entries) {
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                LayoutMode.playbackOrientation(false, true, direction))
            assertEquals(direction.orientation, LayoutMode.playbackOrientation(false, false, direction))
            assertEquals(direction.orientation, LayoutMode.playbackOrientation(true, true, direction))
            assertEquals(direction.orientation, LayoutMode.playbackOrientation(true, false, direction))
        }
    }

    @Test fun queueMatchesExactPartAndCidlessFavorites() {
        assertEquals(1, episodes.playingIndex("one", 1, 102))
        assertEquals(-1, episodes.playingIndex("one", 1, 999))
        assertEquals(0, listOf(episodes.first().copy(cid = 0)).playingIndex("one", 1, 101))
    }
    @Test fun nextAndRepeatAreDifferentCommandsIncludingAtTheEnd() {
        assertEquals(1, nextEpisodeIndex(3, 0, SEASON_SEQUENCE, automatic = true))
        assertNull(nextEpisodeIndex(3, 2, SEASON_SEQUENCE, automatic = true))
        assertEquals(2, nextEpisodeIndex(3, 2, SINGLE_LOOP, automatic = true))
        assertEquals(1, nextEpisodeIndex(3, 0, SINGLE_LOOP, automatic = false))
        assertEquals(0, nextEpisodeIndex(3, 2, LIST_LOOP, automatic = true))
        assertNull(nextEpisodeIndex(0, -1, SINGLE_LOOP, automatic = true))
    }
    @Test fun oneEpisodeDetailCannotReplaceTheActiveCollection() {
        val initial = UgcSeason(3, "全集", "", episodes)
        assertSame(initial, seasonForEpisode(initial, null, "two", 2, 201))
        assertSame(initial, seasonForEpisode(initial, initial.copy(episodes = episodes.takeLast(1)), "two", 2, 201))
        assertNull(seasonForEpisode(initial, null, "other", 3, 301))
    }
    @Test fun seekGesturesPreserveProgressAndAreBoundedForLongVideos() {
        for (duration in listOf(1_800_000L, 7_200_000L)) {
            assertEquals(90_000L, PlayerSeekPolicy.swipePosition(90_000, duration, 15f, 1020, 20f))
            assertEquals(96_000L, PlayerSeekPolicy.swipePosition(90_000, duration, 120f, 1020, 20f))
            assertEquals(30_000L, PlayerSeekPolicy.swipePosition(90_000, duration, -1120f, 1020, 20f))
        }
        assertEquals(1f / 3, PlayerSeekPolicy.fraction(600_000, 1_800_000), 0.00001f)
        assertEquals(0f, PlayerSeekPolicy.fraction(600_000, -1), 0f)
        assertEquals(5_000L, PlayerSeekPolicy.swipePosition(4_900, 5_000, 1120f, 1020, 20f))
        assertEquals(-10_000L, PlayerSeekPolicy.doubleTapDelta(true, 59f, 300))
        assertEquals(0L, PlayerSeekPolicy.doubleTapDelta(true, 99f, 300))
        assertEquals(0L, PlayerSeekPolicy.doubleTapDelta(true, 150f, 300))
        assertEquals(10_000L, PlayerSeekPolicy.doubleTapDelta(true, 299f, 300))
        assertEquals(0L, PlayerSeekPolicy.doubleTapDelta(false, 299f, 300))
    }
    @Test fun doubleTapKeepsTheMiddleSixtyPercentForPauseIncludingBoundaries() {
        for (width in listOf(300, 1080, 1920)) {
            val left = width * .2f
            val right = width * .8f
            assertEquals(-10_000L, PlayerSeekPolicy.doubleTapDelta(true, left - 1, width))
            for (x in listOf(left, width / 3f, width / 2f, width * 2f / 3f, right)) {
                assertEquals(0L, PlayerSeekPolicy.doubleTapDelta(true, x, width))
            }
            assertEquals(10_000L, PlayerSeekPolicy.doubleTapDelta(true, right + 1, width))
            assertEquals(0L, PlayerSeekPolicy.doubleTapDelta(false, 0f, width))
        }
        assertEquals(0L, PlayerSeekPolicy.doubleTapDelta(true, 100f, 0))
    }

    @Test fun highestPreferenceIsSeparateFromFixed8kAndTheLastManualQuality() {
        assertEquals(PreferredVideoQuality.HIGHEST, PreferredVideoQuality.resolve(PreferredVideoQuality.HIGHEST, 64))
        assertEquals(127, PreferredVideoQuality.resolve(127, 64))
        assertEquals(64, PreferredVideoQuality.resolve(PreferredVideoQuality.REMEMBER, PreferredVideoQuality.HIGHEST))
        assertEquals(64, PreferredVideoQuality.resolve(0, 0))
        assertEquals(80, PreferredVideoQuality.resolve(0, 80))
        assertEquals(120, PreferredVideoQuality.resolve(120, 80))
        assertEquals("当前视频最高画质", PreferredVideoQuality.label(PreferredVideoQuality.HIGHEST))
    }

    @Test fun highestNeverSendsItsModeIdToTheApiOrOverridesGuestAndRecoveryLimits() {
        val highest = PreferredVideoQuality.HIGHEST
        assertEquals(127, PreferredVideoQuality.requestQuality(highest, loggedIn = true))
        assertEquals(32, PreferredVideoQuality.requestQuality(highest, loggedIn = false))
        assertEquals(64, PreferredVideoQuality.requestQuality(64, loggedIn = true))
        assertEquals(highest, PreferredVideoQuality.selectionQuality(highest, 127))
        for (limit in listOf(16, 32, 64, 80, 120)) {
            assertEquals(limit, PreferredVideoQuality.selectionQuality(highest, limit))
        }
        assertEquals(127, PreferredVideoQuality.selectionQuality(127, 127))
    }

    @Test fun highestAutomaticResumeRetainsRecoveryRequestUntilTheSessionChanges() {
        val highest = PreferredVideoQuality.HIGHEST
        assertEquals(64, PreferredVideoQuality.resumeRequestQuality(highest, 64, true))
        assertEquals(127, PreferredVideoQuality.resumeRequestQuality(highest, 64, true, sessionChanged = true))
        assertEquals(32, PreferredVideoQuality.resumeRequestQuality(highest, 64, false))
        assertEquals(127, PreferredVideoQuality.resumeRequestQuality(highest, 0, true))
        assertEquals(120, PreferredVideoQuality.resumeRequestQuality(120, 64, true))
    }

    @Test fun highestUsesReturnedTracksNotAdvertisedQualitiesAndFollowsEachVideo() {
        val highest = PreferredVideoQuality.HIGHEST
        fun stream(vararg qualities: Int) = BiliPlayUrl.DashStream(
            qualities.map { BiliPlayUrl.DashVideoTrack(it, "v", emptyList(), it * 1000, "video/mp4", "avc1", 16, 9, "30", null, null) },
            emptyList(), durationMs = 3000, actualQuality = 0,
            acceptedQualities = listOf(127, 126, 120, 80, 64), acceptedDescriptions = emptyList(), isDash = true)
        assertEquals(80, BiliPlayUrl.selectDashStreamTrack(stream(32, 80, 64), highest).actualQuality)
        assertEquals(120, BiliPlayUrl.selectDashStreamTrack(stream(120, 64, 80), highest).actualQuality)
        assertEquals(80, BiliPlayUrl.selectDashStreamTrack(stream(120, 64, 80), highest, setOf(120 to "avc1")).actualQuality)
        assertEquals(64, BiliPlayUrl.selectDashStreamTrack(stream(120, 64, 80),
            PreferredVideoQuality.selectionQuality(highest, 64)).actualQuality)
        val progressive = stream().copy(isDash = false, actualQuality = 32)
        assertSame(progressive, BiliPlayUrl.selectDashStreamTrack(progressive, highest))
    }

    @Test fun missing4kDoesNotChangeStoredPreferenceForNextEpisode() {
        val last = 120
        fun play(vararg qualities: Int): Int {
            val tracks = qualities.map { BiliPlayUrl.DashVideoTrack(it, "v", emptyList(), 1, "video/mp4", "avc1", 16, 9, "30", null, null) }
            val s = BiliPlayUrl.DashStream(tracks, emptyList(), durationMs = 3000, actualQuality = 0, acceptedQualities = qualities.toList(), acceptedDescriptions = emptyList(), isDash = true)
            return BiliPlayUrl.selectDashStreamTrack(s, PreferredVideoQuality.resolve(0, last)).actualQuality
        }
        assertEquals(80, play(80, 64, 32))
        assertEquals(120, play(64, 120, 80))
        assertEquals(64, PreferredVideoQuality.resolve(64, last))
    }
}
