package com.yuilittle.bili

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class PlaybackRecoveryTest {
    private val content = "0123456789abcdefghijklmnop".toByteArray()
    private data class Open(val index: Int, val position: Long, val length: Long)

    private fun source(position: Long, length: Long, quota: Int = Int.MAX_VALUE,
                       failure: IOException? = null, closed: () -> Unit = {}): FallbackMediaReader.Source =
        object : FallbackMediaReader.Source {
            private var cursor = position.toInt()
            private var delivered = 0
            private val end = if (length < 0) content.size else minOf(content.size.toLong(), position + length).toInt()
            override val length = (end - cursor).toLong()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (delivered >= quota) {
                    if (failure != null) throw failure
                    return -1
                }
                if (cursor >= end) return -1
                val count = minOf(length, end - cursor, quota - delivered)
                content.copyInto(buffer, offset, cursor, cursor + count)
                cursor += count
                delivered += count
                return count
            }
            override fun close() = closed()
        }

    private fun consume(reader: FallbackMediaReader): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(5)
        while (true) {
            val count = reader.read(buffer, 0, buffer.size)
            if (count < 0) return out.toByteArray()
            if (count > 0) out.write(buffer, 0, count)
        }
    }

    @Test fun consecutiveReadFailuresReachThirdCandidateAtExactlyTheDeliveredOffset() {
        val opens = ArrayList<Open>()
        val closes = IntArray(3)
        val reader = FallbackMediaReader(3) { index, position, length ->
            opens += Open(index, position, length)
            source(position, length, if (index == 0) 3 else if (index == 1) 0 else Int.MAX_VALUE,
                if (index < 2) IOException("read failed") else null) { closes[index]++ }
        }
        assertEquals(12L, reader.open(4, 12))
        assertArrayEquals(content.copyOfRange(4, 16), consume(reader))
        assertEquals(listOf(Open(0, 4, 12), Open(1, 7, 9), Open(2, 7, 9)), opens)
        reader.close()
        reader.close()
        assertArrayEquals(intArrayOf(1, 1, 1), closes)
    }

    @Test fun opensAndReadsHaveOneFiniteCandidateBudget() {
        val calls = ArrayList<Int>()
        val reader = FallbackMediaReader(4) { index, position, length ->
            calls += index
            if (index == 0 || index == 2) throw IOException("open failed")
            source(position, length, 0, IOException("read failed"))
        }
        reader.open(0, 10)
        assertThrows(IOException::class.java) { consume(reader) }
        assertEquals(listOf(0, 1, 2, 3), calls)
        reader.close()
    }

    @Test fun seeksPreferLastCandidateThatActuallyDeliveredBytes() {
        val calls = ArrayList<Int>()
        val reader = FallbackMediaReader(3) { index, position, length ->
            calls += index
            source(position, length, if (index < 2) 0 else Int.MAX_VALUE,
                if (index < 2) IOException() else null)
        }
        reader.open(0, 5)
        assertArrayEquals(content.copyOfRange(0, 5), consume(reader))
        reader.close()
        reader.open(10, 4)
        assertArrayEquals(content.copyOfRange(10, 14), consume(reader))
        assertEquals(listOf(0, 1, 2, 2), calls)
        reader.close()
    }

    @Test fun prematureKnownEofResumesButNormalEofDoesNotOpenBackups() {
        val calls = ArrayList<Open>()
        val reader = FallbackMediaReader(2) { index, position, length ->
            calls += Open(index, position, length)
            source(position, length, if (index == 0) 2 else Int.MAX_VALUE)
        }
        reader.open(1, 7)
        assertArrayEquals(content.copyOfRange(1, 8), consume(reader))
        assertEquals(listOf(Open(0, 1, 7), Open(1, 3, 5)), calls)
        assertEquals(-1, reader.read(ByteArray(4), 0, 4))
        assertEquals(2, calls.size)
        reader.close()
    }

    @Test fun openLengthCanBoundAnUnknownLengthRequestAfterFallback() {
        val calls = ArrayList<Open>()
        val reader = FallbackMediaReader(2) { index, position, length ->
            calls += Open(index, position, length)
            source(position, length, if (index == 0) 4 else Int.MAX_VALUE, if (index == 0) IOException() else null)
        }
        assertEquals((content.size - 3).toLong(), reader.open(3, -1))
        assertArrayEquals(content.copyOfRange(3, content.size), consume(reader))
        assertEquals(Open(1, 7, (content.size - 7).toLong()), calls.last())
        reader.close()
    }

    @Test fun cancellationDuringReadDoesNotContactTheNextCandidate() {
        var attempts = 0
        val reader = FallbackMediaReader(3) { _, position, length ->
            attempts++
            source(position, length, 0, IOException("wrapped", InterruptedIOException("cancelled")))
        }
        reader.open(0, 8)
        assertThrows(IOException::class.java) { consume(reader) }
        assertEquals(1, attempts)
        reader.close()
    }

    @Test fun socketTimeoutIsRetryableUnlikeCancellation() {
        var attempts = 0
        val reader = FallbackMediaReader(2) { index, position, length ->
            attempts++
            source(position, length, if (index == 0) 0 else Int.MAX_VALUE,
                if (index == 0) IOException("wrapped", SocketTimeoutException()) else null)
        }
        reader.open(0, 8)
        assertArrayEquals(content.copyOfRange(0, 8), consume(reader))
        assertEquals(2, attempts)
        reader.close()
    }

    @Test fun allOpenFailuresAndRepeatedCloseLeaveNoOpenedSource() {
        var attempts = 0
        val reader = FallbackMediaReader(2) { _, _, _ -> attempts++; throw IOException("open") }
        assertThrows(IOException::class.java) { reader.open(0, -1) }
        assertEquals(2, attempts)
        reader.close()
        reader.close()
        assertThrows(InterruptedIOException::class.java) { reader.read(ByteArray(1), 0, 1) }
    }

    @Test fun threadInterruptionStopsBeforeOpeningAnyCandidate() {
        var attempts = 0
        val reader = FallbackMediaReader(2) { _, p, l -> attempts++; source(p, l) }
        Thread.currentThread().interrupt()
        try { assertThrows(InterruptedIOException::class.java) { reader.open(0, 2) } }
        finally { Thread.interrupted(); reader.close() }
        assertEquals(0, attempts)
    }

    @Test fun exhaustedPartialReadThrowsInsteadOfSilentlyEndingTheMovie() {
        val reader = FallbackMediaReader(1) { _, p, l -> source(p, l, 2) }
        reader.open(0, 4)
        assertThrows(EOFException::class.java) { consume(reader) }
        reader.close()
    }

    @Test fun stationaryBufferTimesOutAfterTwentySeconds() {
        val monitor = PlaybackStallMonitor(20_000)
        monitor.observe(1_000, 90_000)
        monitor.observe(20_000, 90_000)
        assertEquals(1_000L, monitor.remainingMs(20_000))
        assertEquals(0L, monitor.remainingMs(21_000))
    }

    @Test fun realPlayableBufferProgressDefersTheWatchdogWithinTotalLimit() {
        val monitor = PlaybackStallMonitor(20_000)
        monitor.observe(0, 90_000)
        monitor.observe(18_000, 91_000)
        assertEquals(18_000L, monitor.remainingMs(20_000))
        monitor.observe(36_000, 92_000)
        monitor.observe(54_000, 93_000)
        assertEquals(6_000L, monitor.remainingMs(54_000))
        assertEquals(0L, monitor.remainingMs(60_000))
    }

    @Test fun resetOnPauseSeekOrNewMediaDoesNotInheritThePreviousTimeout() {
        val monitor = PlaybackStallMonitor(20_000)
        monitor.observe(0, 100_000)
        monitor.reset()
        assertFalse(monitor.started)
        monitor.observe(80_000, 50_000)
        assertEquals(20_000L, monitor.remainingMs(80_000))
        monitor.observe(90_000, 49_000)
        assertEquals(10_000L, monitor.remainingMs(90_000))
    }
}
