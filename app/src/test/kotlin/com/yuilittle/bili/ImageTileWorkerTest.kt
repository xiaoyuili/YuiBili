package com.yuilittle.bili

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ImageTileWorkerTest {
    @Test fun tileDecodeWidthIsBoundedEvenForAnUnusuallyWidePicture() {
        assertEquals(1, imageTileSampleSize(1080))
        assertEquals(2, imageTileSampleSize(2880))
        assertEquals(8, imageTileSampleSize(10_000))
        assertTrue(Int.MAX_VALUE.toLong() / imageTileSampleSize(Int.MAX_VALUE) <= 1440L)
    }

    @Test fun pendingDeliveriesLimitDecodedTilesAndCloseDropsTheirCallbacks() {
        val executor = ManualExecutor()
        val deliveries = ArrayDeque<Runnable>()
        val decoded = ArrayList<Int>()
        val shown = ArrayList<Int>()
        var releases = 0
        val worker = ImageTileWorker<Int>(
            open = { source(decode = { decoded += it; it }, close = { releases++ }) },
            deliver = deliveries::addLast,
            onSource = { _, _ -> },
            onTile = { _, value -> if (value != null) shown += value },
            executor = executor
        )
        worker.bind("image")
        executor.runAll()
        worker.request((0..200).toList())
        executor.runAll()
        assertEquals(2, decoded.size)
        assertEquals(3, deliveries.size)
        worker.close()
        worker.close()
        executor.runAll()
        while (deliveries.isNotEmpty()) deliveries.removeFirst().run()
        assertEquals(1, releases)
        assertTrue(shown.isEmpty())
        assertTrue(executor.isShutdown)
    }

    @Test fun switchingImagesRejectsOldCallbacksEvenWhenTheTileIndexMatches() {
        val executor = ManualExecutor()
        val deliveries = ArrayDeque<Runnable>()
        val closed = ArrayList<String>()
        val shown = ArrayList<Int>()
        val worker = ImageTileWorker<Int>(
            open = { key -> source(decode = { if (key == "first") -it else it }, close = { closed += key }) },
            deliver = deliveries::addLast,
            onSource = { _, _ -> },
            onTile = { _, value -> if (value != null) shown += value },
            executor = executor
        )
        worker.bind("first")
        executor.runAll()
        worker.request(listOf(1))
        executor.runAll()
        worker.bind("second")
        worker.request(listOf(1))
        executor.runAll()
        while (deliveries.isNotEmpty()) deliveries.removeFirst().run()
        executor.runAll()
        assertEquals(listOf(1), shown)
        worker.close()
        executor.runAll()
        assertEquals(listOf("first", "second"), closed)
    }

    @Test fun aBusyDecodeFinishesBeforeItsSourceIsReleasedAndCloseNeverWaitsForIt() {
        val opened = CountDownLatch(1)
        val decoding = CountDownLatch(1)
        val allowDecode = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val worker = ImageTileWorker<Int>(
            open = { source(decode = {
                decoding.countDown()
                assertTrue(allowDecode.await(3, TimeUnit.SECONDS))
                it
            }, close = { closed.countDown() }) },
            deliver = { it.run() },
            onSource = { _, _ -> opened.countDown() },
            onTile = { _, _ -> fail("Closed image must not receive a tile") }
        )
        try {
            worker.bind("image")
            assertTrue(opened.await(3, TimeUnit.SECONDS))
            worker.request((0..32).toList())
            assertTrue(decoding.await(3, TimeUnit.SECONDS))
            worker.close()
            assertEquals(1L, closed.count)
            allowDecode.countDown()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
        } finally {
            allowDecode.countDown()
            worker.close()
        }
    }

    @Test fun requestsAfterCloseCannotReopenTheDecoder() {
        val executor = ManualExecutor()
        var opens = 0
        val worker = ImageTileWorker<Int>(
            open = { opens++; source({ it }, {}) }, deliver = { it.run() },
            onSource = { _, _ -> }, onTile = { _, _ -> }, executor = executor
        )
        worker.close()
        executor.runAll()
        worker.bind("later")
        worker.request(listOf(1))
        executor.runAll()
        assertEquals(0, opens)
    }

    private fun source(decode: (Int) -> Int, close: () -> Unit) = object : ImageTileWorker.Source<Int> {
        override val width = 100
        override val height = 10_000
        override fun decode(index: Int): Int = decode.invoke(index)
        override fun close() = close.invoke()
    }

    private class ManualExecutor : AbstractExecutorService() {
        private val work = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); work.addLast(command) }
        fun runAll() { while (work.isNotEmpty()) work.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> = work.toMutableList().also { work.clear(); stopped = true }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && work.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
