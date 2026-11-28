package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadStorageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun absentOrUnknownLocationDoesNotMigrateLegacyDownloads() {
        assertEquals(DownloadDestination.PRIVATE, DownloadDestination.fromKey(null))
        assertEquals(DownloadDestination.PRIVATE, DownloadDestination.fromKey(""))
        assertEquals(DownloadDestination.PRIVATE, DownloadDestination.fromKey("unknown"))
        DownloadDestination.entries.forEach { assertEquals(it, DownloadDestination.fromKey(it.key)) }
    }

    @Test fun stagedSegmentsKeepNumericOrderAndRequireTheWholeSet() {
        val dir = temp.newFolder()
        val lengths = (1L..12L).toList()
        val stage = DownloadStage(DownloadLayout.SEGMENTS, lengths)
        val inputs = stage.inputs(dir, 21)
        assertEquals((0..11).map { "21_seg$it.mp4" }, inputs.map(File::getName))
        assertEquals(12, stage.outputCount)
        inputs.forEachIndexed { i, file -> file.writeBytes(ByteArray(lengths[i].toInt())) }
        assertTrue(stage.isIntact(dir, 21))
        inputs[5].writeBytes(ByteArray(1))
        assertFalse(stage.isIntact(dir, 21))
        inputs[5].delete()
        assertFalse(stage.isIntact(dir, 21))
    }

    @Test fun dashNeverSilentlyDropsAnExpectedAudioTrack() {
        val dir = temp.newFolder()
        File(dir, "7_video.m4s").writeBytes(ByteArray(8))
        val videoOnly = DownloadStage(DownloadLayout.DASH, listOf(8L))
        val withAudio = DownloadStage(DownloadLayout.DASH, listOf(8L, 4L))
        assertEquals(1, withAudio.outputCount)
        assertTrue(videoOnly.isIntact(dir, 7))
        assertFalse(withAudio.isIntact(dir, 7))
        File(dir, "7_audio.m4s").writeBytes(ByteArray(4))
        assertTrue(withAudio.isIntact(dir, 7))
        assertThrows(IllegalArgumentException::class.java) { DownloadStage(DownloadLayout.DASH, listOf(8L, 0L)) }
        assertThrows(IllegalArgumentException::class.java) { DownloadStage(DownloadLayout.SEGMENTS, emptyList()) }
    }

    @Test fun sameTitleCannotAuthorizeDeletionOrAdoptionOfAnotherTasksOutputs() {
        val names = LibraryBatch.names("../../合集:电影", 42, "deadbeef1234", 3, audio = false)
        assertEquals(3, names.distinct().size)
        for (name in names) {
            assertTrue(LibraryBatch.validName(name, 42, false))
            assertFalse(LibraryBatch.validName(name, 43, false))
            assertFalse(LibraryBatch.validName(name, 42, true))
            assertFalse(LibraryBatch.validName("../$name", 42, false))
        }
        assertFalse(LibraryBatch.validName("电影.mp4", 42, false))
    }

    @Test fun recoveredPublicBatchMustMatchEveryPersistedLengthBeforeCleanup() {
        val sizes = listOf(400L, 800L)
        assertTrue(LibraryBatch.canAdopt(sizes, listOf(400L, 800L)))
        assertFalse(LibraryBatch.canAdopt(sizes, listOf(400L, 799L)))
        assertFalse(LibraryBatch.canAdopt(sizes, listOf(400L, null)))
        assertFalse(LibraryBatch.canAdopt(sizes, listOf(400L)))
        assertFalse(LibraryBatch.canAdopt(emptyList(), emptyList()))
    }

    @Test fun postCommitCleanupOnlyRemovesThisTasksPrivateMedia() {
        val dir = temp.newFolder()
        val media = listOf("7.mpd", "7_audio.m4s", "7_video.m4s", "7_merged.mp4", "7_seg0.mp4", "7_seg10.mp4")
        val cover = "7_cover.webp"
        val keep = listOf("70_audio.m4s", "7_my-video.mp4", "7_seg0.mp4.bak", "tasks.json")
        (media + keep + cover).forEach { File(dir, it).writeText("x") }
        assertTrue(DownloadPrivateFiles.clean(dir, 7, includeCover = false))
        assertEquals((keep + cover).toSet(), dir.list()!!.toSet())
        assertTrue(DownloadPrivateFiles.clean(dir, 7, includeCover = true))
        assertEquals(keep.toSet(), dir.list()!!.toSet())
    }
}
