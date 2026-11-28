package com.yuilittle.bili

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BiliRequestTest {
    @Test fun cancellingQueuedWorkRemovesOnlyThatRunnable() {
        val executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), ThreadPoolExecutor.AbortPolicy()
        )
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val request = BiliRequest()
        val otherRequest = BiliRequest()
        val queued = Runnable { error("cancelled work must not run") }
        val otherQueued = Runnable { error("other request must stay queued") }
        try {
            executor.execute {
                running.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            assertTrue(request.enqueue(executor, queued))
            assertTrue(otherRequest.enqueue(executor, otherQueued))

            request.cancel()

            assertEquals(1, executor.queue.size)
            assertTrue(executor.queue.contains(otherQueued))
            assertFalse(executor.queue.contains(queued))
            assertFalse(request.isActive())
            assertTrue(otherRequest.isActive())
        } finally {
            executor.shutdownNow()
            release.countDown()
        }
    }

    @Test fun cancellingARequestCancelsItsAttachedCallAfterTheTaskStarts() {
        val executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), ThreadPoolExecutor.AbortPolicy()
        )
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val request = BiliRequest()
        val call = FakeCall()
        lateinit var work: Runnable
        work = Runnable {
            if (request.startTask(work)) {
                assertTrue(request.attach(call))
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(request.enqueue(executor, work))
            assertTrue(started.await(2, TimeUnit.SECONDS))

            request.cancel()

            assertEquals(1, call.cancelCount.get())
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun cancelIsIdempotentForAnAttachedCall() {
        val request = BiliRequest()
        val call = FakeCall()
        assertTrue(request.attach(call))

        request.cancel()
        request.cancel()

        assertEquals(1, call.cancelCount.get())
        assertFalse(request.isActive())
    }

    @Test fun attachingAfterCancellationIsRejectedAndDoesNotCancelTheNewCall() {
        val request = BiliRequest()
        val call = FakeCall()

        request.cancel()

        assertFalse(request.attach(call))
        assertEquals(0, call.cancelCount.get())
    }

    @Test fun cancellingOneRequestDoesNotCancelAnotherAttachedCall() {
        val first = BiliRequest()
        val second = BiliRequest()
        val firstCall = FakeCall()
        val secondCall = FakeCall()
        assertTrue(first.attach(firstCall))
        assertTrue(second.attach(secondCall))

        first.cancel()

        assertEquals(1, firstCall.cancelCount.get())
        assertEquals(0, secondCall.cancelCount.get())
        assertTrue(second.isActive())
        assertFalse(first.isActive())
    }

    private class FakeCall : Call {
        val cancelCount = AtomicInteger(0)
        @Volatile private var canceled = false

        override fun request(): Request = Request.Builder().url("https://example.com/").build()
        override fun execute(): Response = throw IOException("fake call is not executed")
        override fun enqueue(responseCallback: Callback) = Unit
        override fun cancel() {
            canceled = true
            cancelCount.incrementAndGet()
        }
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = canceled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall()
    }
}
