package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryResumeTest {
    private val p1 = HistoryMediaId("BVtest", 51L, 101L)
    private val p2 = p1.copy(cid = 102L)
    private val empty = HistoryMediaId("", 0L, 0L)
    private val uid = 7L

    private fun row(media: HistoryMediaId = p2, progress: Long = 40L, viewedAt: Long = 1_000L, duration: Int = 90) =
        VideoItem(media.bvid, "下集", "UP", "cover", 0L, duration, "", publishedAt = viewedAt,
            aid = media.aid, cid = media.cid, watchProgressSec = progress)

    private fun local(media: HistoryMediaId = p2, progress: Long = 15L, at: Long = 1_001L) =
        HistoryStore.Entry(row(media, progress, at), "", at, uid)

    @Test fun cursorAndFlatSearchUseTheWatchedPartNotTheFirstPart() {
        assertEquals(p2, archiveHistoryMedia("archive", 0L, p2, p1))
        assertEquals(p2, archiveHistoryMedia("", 0L, empty, p2))
        assertEquals(p2, archiveHistoryMedia("archive", 0L, p2.copy(aid = 0L, cid = 0L), p2))
        assertEquals(p2.copy(bvid = ""), archiveHistoryMedia("archive", 0L, p2.copy(bvid = ""), empty))
        assertNotEquals(p1.rowKey, p2.rowKey)
        assertNotEquals(p2.copy(bvid = "").rowKey, p2.copy(bvid = "", aid = 52L).rowKey)
    }

    @Test fun otherHistoryBusinessesAndUnidentifiedRowsAreNotArchiveLaunches() {
        assertNull(archiveHistoryMedia("pgc", 392L, p2, empty))
        assertNull(archiveHistoryMedia("", 392L, empty, p2))
        assertNull(archiveHistoryMedia("live", 0L, p2, empty))
        assertNull(archiveHistoryMedia("archive", 0L, empty.copy(bvid = "null"), empty))
    }

    @Test fun completionInvalidSecondsAndMissingPartNeverSeekToTheEnd() {
        for (sec in listOf(-9L, -1L, 0L, 90L, 91L, Long.MAX_VALUE)) {
            assertEquals(0L, historyResumeMs(p2.cid, sec, 90))
        }
        assertEquals(0L, historyResumeMs(0L, 30L, 90))
        assertEquals(89_000L, historyResumeMs(p2.cid, 89L, 90))
        // 没有列表时长时，候选位置必须再经实际流校验。
        val candidate = historyResumeMs(p2.cid, 25L, 0)
        assertEquals(25_000L, checkedHistoryStart(p2, p2, candidate, 30_000L, true))
        for (duration in listOf(0L, 20_000L, 25_000L)) {
            assertEquals(0L, checkedHistoryStart(p2, p2, candidate, duration, true))
        }
    }

    @Test fun correctedCidOrUnmappableSegmentsDoNotReceiveThePreviousPartOffset() {
        assertEquals(0L, checkedHistoryStart(p2, p1, 25_000L, 40_000L, true))
        assertEquals(0L, checkedHistoryStart(p2, p2.copy(aid = 52L), 25_000L, 40_000L, true))
        assertEquals(0L, checkedHistoryStart(p2, p2, 25_000L, 40_000L, false))
        assertEquals(25_000L, checkedHistoryStart(p2.copy(bvid = ""), p2, 25_000L, 40_000L, true))
    }

    @Test fun laterCheckpointCanBeEarlierInTheVideoButNeverBelongToAnotherPartOrViewer() {
        val cloud = row()
        assertEquals(15L, HistoryStore.withLocalProgress(cloud, local(), uid).watchProgressSec)
        assertEquals(-1L, HistoryStore.withLocalProgress(cloud, local(progress = -1L), uid).watchProgressSec)
        for (other in listOf(local(p1), local(p2.copy(bvid = "BVother")), local().copy(viewerUid = 8L),
            local(at = 999L), local(at = 1_000L), local(progress = 0L), local(progress = 95L))) {
            assertEquals(cloud, HistoryStore.withLocalProgress(cloud, other, uid))
        }
        val finished = row(progress = -1L, viewedAt = 1_002L)
        assertEquals(finished, HistoryStore.withLocalProgress(finished, local(), uid))
        val undated = row(viewedAt = 0L)
        assertEquals(undated, HistoryStore.withLocalProgress(undated, local(), uid))
    }

    @Test fun metadataRefreshDoesNotEraseOrRedateTheLastRealCheckpoint() {
        val before = local(at = 900L)
        val details = row().copy(title = "第二 P")
        val opened = HistoryStore.recordedEntry(details, null, before, now = 1_001L, uid = uid)
        assertEquals("第二 P", opened.video.title)
        assertEquals(1_001L, opened.video.publishedAt)
        assertEquals(900L, opened.progressAtSec)
        assertEquals(15L, opened.video.watchProgressSec)
        assertEquals(row(), HistoryStore.withLocalProgress(row(), opened, uid))
        val differentPart = HistoryStore.recordedEntry(row(p1), null, before, now = 1_002L, uid = uid)
        assertEquals(0L, differentPart.video.watchProgressSec)
        assertEquals(0L, differentPart.progressAtSec)
        val guest = HistoryStore.recordedEntry(details, 6L, null, now = 1_003L, uid = 0L)
        assertEquals(p2, guest.video.historyMedia)
        assertEquals(6_000L, historyResumeMs(guest.video.cid, guest.video.watchProgressSec, guest.video.duration))
    }

    @Test fun eachViewerSeesOnlyOwnRecordsAndLegacyRowsBelongToGuest() {
        val guest = local(p2.copy(aid = 0L, cid = 0L), progress = 0L).copy(viewerUid = 0L)
        val mine = local()
        val other = local(progress = 31L).copy(viewerUid = 66L)
        val unknown = local().copy(viewerUid = -1L)
        val all = listOf(mine, other, guest, unknown)
        assertEquals(listOf(guest), HistoryStore.visibleEntries(all, 0L))
        assertEquals(listOf(mine), HistoryStore.visibleEntries(all, uid))
        assertEquals(listOf(other), HistoryStore.visibleEntries(all, 66L))
        assertEquals(emptyList<HistoryStore.Entry>(), HistoryStore.visibleEntries(all, -1L))
        assertEquals(all, HistoryStore.upsertEntry(all, unknown))
        assertEquals(all, HistoryStore.withoutEntries(all, -1L))
    }

    @Test fun recordingTheSamePartOrHittingTheLimitNeverEvictsAnotherViewer() {
        val guest = local(p2.copy(aid = 0L, cid = 0L), progress = 0L).copy(viewerUid = 0L)
        val other = local(progress = 62L).copy(viewerUid = 66L)
        val own = (1L..100L).map { local(p2.copy(cid = it)) }
        val inserted = local(p2, progress = 8L)
        val updated = HistoryStore.upsertEntry(listOf(other, guest) + own, inserted)
        assertEquals(listOf(inserted) + own.take(99), HistoryStore.visibleEntries(updated, uid))
        val changed = inserted.copy(video = inserted.video.copy(watchProgressSec = 23L))
        val replaced = HistoryStore.upsertEntry(updated, changed)
        assertEquals(listOf(changed) + own.take(99), HistoryStore.visibleEntries(replaced, uid))
        assertEquals(listOf(other), HistoryStore.visibleEntries(replaced, 66L))
        assertEquals(listOf(guest), HistoryStore.visibleEntries(replaced, 0L))
    }

    @Test fun deletingAPartAnArchiveOrAllHistoryAffectsOnlyTheCurrentViewer() {
        val first = local(p1)
        val second = local(p2)
        val otherArchive = local(HistoryMediaId("BVelse", 81L, 401L))
        val other = second.copy(viewerUid = 66L)
        val guest = first.copy(viewerUid = 0L)
        val all = listOf(first, other, second, guest, otherArchive)
        assertEquals(listOf(first, other, guest, otherArchive), HistoryStore.withoutEntries(all, uid, p2))
        assertEquals(listOf(other, guest, otherArchive), HistoryStore.withoutEntries(all, uid, p2, wholeArchive = true))
        assertEquals(listOf(other, guest), HistoryStore.withoutEntries(all, uid))
        assertEquals(listOf(first, other, second, otherArchive), HistoryStore.withoutEntries(all, 0L))
    }

    @Test fun aSnapshotIncludingZeroSupersedesTheOriginalHistoryOrQueueIntent() {
        assertEquals(HistoryPlaybackStart(0L), historyPlaybackStart(0L, false, null, false, 41_000L))
        assertEquals(HistoryPlaybackStart(8_250L), historyPlaybackStart(8_250L, false, 2_000L, false, 41_000L))
        assertEquals(HistoryPlaybackStart(41_000L, true), historyPlaybackStart(41_000L, true, null, false, 2_000L))
        assertEquals(HistoryPlaybackStart(41_000L), historyPlaybackStart(41_000L, false, null, false, 2_000L))
    }

    @Test fun onlyAFreshHistoryLaunchUsesHistorySeconds() {
        assertEquals(HistoryPlaybackStart(0L), historyPlaybackStart(null, false, null, false, 0L))
        assertEquals(HistoryPlaybackStart(0L), historyPlaybackStart(null, false, null, true, 41_000L))
        assertEquals(HistoryPlaybackStart(0L), historyPlaybackStart(null, false, 0L, false, 41_000L))
        assertEquals(HistoryPlaybackStart(5_500L), historyPlaybackStart(null, false, 5_500L, false, 41_000L))
        assertEquals(HistoryPlaybackStart(41_000L, true), historyPlaybackStart(null, false, null, false, 41_000L))
    }

    @Test fun localHistoryAppearsBeforeCloudReportsItAndRemainsOnAnEmptyCloudPage() {
        val recent = local(p1, at = 1_010L)
        assertEquals(listOf(recent.video), HistoryStore.mergeRows(emptyList(), listOf(recent), uid).map { it.video })
        assertEquals(listOf(recent.video, row()), HistoryStore.mergeRows(listOf(row()), listOf(recent), uid).map { it.video })
    }

    @Test fun mergedHistoryReordersRecentViewsWithoutConfusingMetadataTimeAndProgressTime() {
        val cloud = row().copy(rcmdReason = "archive_51", ownerMid = 88L)
        val newerCloud = row(p1, viewedAt = 1_005L)
        val reopened = local(at = 999L).copy(video = local().video.copy(publishedAt = 1_010L))
        val result = HistoryStore.mergeRows(listOf(newerCloud, cloud), listOf(reopened), uid)
        assertEquals(cloud, result.first().video)
        assertEquals(1_010L, result.first().viewedAtSec)
        assertEquals(newerCloud, result.last().video)
        val rewind = HistoryStore.mergeRows(listOf(cloud), listOf(local(progress = 8L)), uid).single().video
        assertEquals(8L, rewind.watchProgressSec)
        assertEquals("archive_51", rewind.rcmdReason)
        assertEquals(88L, rewind.ownerMid)
    }

    @Test fun mergedHistoryMatchesAidOnlyRowsButKeepsPartsAndUnknownPartsSeparate() {
        val result = HistoryStore.mergeRows(listOf(row(p2.copy(bvid = ""))), listOf(local(p1), local(p2)), uid)
        assertEquals(2, result.size)
        assertEquals(15L, result.first { it.video.cid == p2.cid }.video.watchProgressSec)
        assertEquals(3, HistoryStore.mergeRows(listOf(row(p2.copy(cid = 0L))), listOf(local(p1), local(p2)), uid).size)
        assertEquals(2, HistoryStore.mergeRows(listOf(row(p2.copy(bvid = "BVother"))), listOf(local()), uid).size)
    }

    @Test fun mergedHistoryNeverLeaksAnotherAccountOrTruncatesCloudPagination() {
        val other = local().copy(viewerUid = 88L)
        val guest = local(p1).copy(viewerUid = 0L)
        val all = listOf(local(), other, guest)
        assertEquals(listOf(local().video), HistoryStore.mergeRows(emptyList(), all, uid).map { it.video })
        assertEquals(listOf(guest.video), HistoryStore.mergeRows(emptyList(), all, 0L).map { it.video })
        assertEquals(emptyList<HistoryStore.ViewedRow>(), HistoryStore.mergeRows(emptyList(), all, -1L))
        val cloud = (1L..140L).map { row(p2.copy(cid = it), viewedAt = 2_000L - it) }
        assertEquals(140, HistoryStore.mergeRows(cloud + cloud, emptyList(), uid).size)
    }

    @Test fun mergedHistoryKeepsCloudProgressForSameSecondAndUndatedCloudRows() {
        for (cloud in listOf(row(viewedAt = 1_001L), row(viewedAt = 0L))) {
            assertEquals(cloud.watchProgressSec, HistoryStore.mergeRows(listOf(cloud), listOf(local()), uid).single().video.watchProgressSec)
        }
        assertEquals(-1L, HistoryStore.mergeRows(listOf(row()), listOf(local(progress = -1L)), uid).single().video.watchProgressSec)
    }

    @Test fun cloudAliasesAcrossPagesMergeIntoOnePartWithNewestProgress() {
        val older = row(p2.copy(bvid = ""), viewedAt = 1_000L).copy(rcmdReason = "archive_51")
        val newer = row(progress = 20L, viewedAt = 1_005L)
        for (cloud in listOf(listOf(older, newer), listOf(newer, older))) {
            val merged = HistoryStore.mergeRows(cloud, listOf(local()), uid).single()
            assertEquals(p2, merged.video.historyMedia)
            assertEquals(20L, merged.video.watchProgressSec)
            assertEquals(1_005L, merged.viewedAtSec)
            assertEquals("archive_51", merged.video.rcmdReason)
        }
    }

    @Test fun completeIdentityCanJoinTwoPartialCloudAliasesRegardlessOfOrder() {
        val bvOnly = row(p2.copy(aid = 0L), progress = 8L, viewedAt = 1_010L)
        val aidOnly = row(p2.copy(bvid = ""), viewedAt = 1_005L)
        val complete = row(viewedAt = 1_000L)
        for (cloud in listOf(listOf(bvOnly, aidOnly, complete), listOf(complete, aidOnly, bvOnly), listOf(aidOnly, complete, bvOnly))) {
            val result = HistoryStore.mergeRows(cloud, emptyList(), uid).single()
            assertEquals(p2, result.video.historyMedia)
            assertEquals(8L, result.video.watchProgressSec)
        }
        assertEquals(1, HistoryStore.mergeRows(listOf(bvOnly, aidOnly), listOf(local()), uid).size)
        assertEquals(2, HistoryStore.mergeRows(listOf(bvOnly, aidOnly), emptyList(), uid).size)
        assertEquals(3, HistoryStore.mergeRows(listOf(bvOnly, complete, complete.copy(aid = 52L)), emptyList(), uid).size)
    }

    @Test fun playbackReportsUseWholeSecondsAndPreserveARealCompletion() {
        assertEquals(HistorySample(89L, 90), historySample(89_250L, 89_600L, false))
        assertEquals(HistorySample(-1L, 90), historySample(89_600L, 89_600L, false))
        assertEquals(HistorySample(-1L, 90), historySample(1_000L, 89_600L, true))
        assertEquals(HistorySample(0L, 0), historySample(-1L, 0L, false))
    }
}
