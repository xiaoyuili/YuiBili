package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutModeTest {

    @Test
    fun horizonBodiesExitRightAndEnterFromLeft() {
        val outgoingStart = horizonOrbit(0f, incoming = false)
        val outgoingEnd = horizonOrbit(1f, incoming = false)
        val incomingStart = horizonOrbit(0f, incoming = true)
        val incomingEnd = horizonOrbit(1f, incoming = true)
        assertEquals(0f, outgoingStart.first, 0.0001f)
        assertEquals(-1f, outgoingStart.second, 0.0001f)
        assertEquals(1f, outgoingEnd.second, 0.0001f)
        assertEquals(1f, incomingStart.second, 0.0001f)
        assertEquals(0f, incomingEnd.first, 0.0001f)
        assertEquals(-1f, incomingEnd.second, 0.0001f)
        for (step in 1..99) {
            val progress = step / 100f
            assertTrue(horizonOrbit(progress, incoming = false).first > 0f)
            assertTrue(horizonOrbit(progress, incoming = true).first < 0f)
            assertTrue(horizonOrbit(progress, incoming = false).second >= horizonOrbit(progress - .01f, incoming = false).second)
            assertTrue(horizonOrbit(progress, incoming = true).second <= horizonOrbit(progress - .01f, incoming = true).second)
        }
    }

    @Test
    fun horizonAndTabMotionClampAtTheirEndpoints() {
        assertEquals(horizonOrbit(0f, false), horizonOrbit(-1f, false))
        assertEquals(horizonOrbit(1f, true), horizonOrbit(2f, true))
        assertEquals(40f, homeTabIndicatorOffset(40f, 122f, -1f), 0f)
        assertEquals(122f, homeTabIndicatorOffset(40f, 122f, 2f), 0f)
    }

    @Test
    fun tabIndicatorFollowsBothDirectionsAndCancellingDrag() {
        assertEquals(40f, homeTabIndicatorOffset(40f, 122f, 0f), 0f)
        assertEquals(81f, homeTabIndicatorOffset(40f, 122f, .5f), 0f)
        assertEquals(122f, homeTabIndicatorOffset(40f, 122f, 1f), 0f)
        assertEquals(60.5f, homeTabIndicatorOffset(40f, 122f, .25f), 0f)
        assertEquals(40f, homeTabIndicatorOffset(40f, 122f, 0f), 0f)
        assertEquals(81f, homeTabIndicatorOffset(122f, 40f, .5f), 0f)
    }

    @Test
    fun tabletThemeBlendUsesTheSameProgressInBothDirections() {
        val light = 0xFFFAFAFC.toInt()
        val dark = 0xFF101114.toInt()
        assertEquals(light, themeBlendColor(light, dark, 0f))
        assertEquals(dark, themeBlendColor(light, dark, 1f))
        assertEquals(light, themeBlendColor(light, dark, -1f))
        assertEquals(dark, themeBlendColor(light, dark, 2f))
        assertEquals(0xFF858588.toInt(), themeBlendColor(light, dark, .5f))
        assertEquals(themeBlendColor(light, dark, .5f), themeBlendColor(dark, light, .5f))
        assertEquals(0f, horizonOrbit(.5f, false).second, .0001f)
        assertEquals(0f, horizonOrbit(.5f, true).second, .0001f)
    }

    @Test
    fun tabletSearchDestinationsKeepTheirSourcePages() {
        assertEquals(TabletSearchScope.PUBLIC, tabletSearchScope(TabletRailNav.DEST_SEARCH))
        assertEquals(TabletSearchScope.HISTORY, tabletSearchScope(TabletRailNav.DEST_HISTORY_SEARCH))
        assertEquals(TabletSearchScope.FAVORITES, tabletSearchScope(TabletRailNav.DEST_FAVORITES_SEARCH))
        assertEquals(TabletRailNav.DEST_HOME, TabletSearchScope.PUBLIC.parentDestination)
        assertEquals(TabletRailNav.DEST_HISTORY, TabletSearchScope.HISTORY.parentDestination)
        assertEquals(TabletRailNav.DEST_FAVORITES, TabletSearchScope.FAVORITES.parentDestination)
        assertNull(tabletSearchScope(TabletRailNav.DEST_PROFILE))
    }

    @Test
    fun historySearchKeepsPartsAndAidOnlyRowsDistinctWhileFavoritesKeepArchives() {
        val first = VideoItem("BVone", "视频", "UP", "", 0L, 90, "", aid = 10, cid = 101)
        val second = first.copy(cid = 102)
        assertNotEquals(TabletSearchScope.HISTORY.rowKey(first), TabletSearchScope.HISTORY.rowKey(second))
        assertEquals(TabletSearchScope.FAVORITES.rowKey(first), TabletSearchScope.FAVORITES.rowKey(second))
        assertNotEquals(TabletSearchScope.HISTORY.rowKey(first.copy(bvid = "")),
            TabletSearchScope.HISTORY.rowKey(first.copy(bvid = "", aid = 11)))
    }

    @Test
    fun modeFromKey_defaultsToPhone() {
        assertEquals(LayoutMode.Mode.PHONE, LayoutMode.Mode.fromKey(null))
        assertEquals(LayoutMode.Mode.PHONE, LayoutMode.Mode.fromKey(""))
        assertEquals(LayoutMode.Mode.PHONE, LayoutMode.Mode.fromKey("unknown"))
        assertEquals(LayoutMode.Mode.PHONE, LayoutMode.Mode.fromKey("phone"))
        assertEquals(LayoutMode.Mode.TABLET, LayoutMode.Mode.fromKey("tablet"))
    }
}
