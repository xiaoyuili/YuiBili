package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentLikeLedgerTest {
    @Test fun twoViewsSeeOnePendingStateAndASecondBeginIsIgnored() {
        val now = 1_000L
        val ledger = CommentLikeLedger { now }
        val key = CommentLikeKey(epoch = 7L, type = 1, oid = "av1", rpid = 9L)
        val server = CommentLikeState(liked = false, count = 10L)

        assertEquals(server, ledger.read(key, server))
        val first = ledger.begin(key, server) ?: error("first operation should start")
        val pending = CommentLikeState(liked = true, count = 11L, pending = true)

        assertEquals(pending, ledger.read(key, CommentLikeState(liked = false, count = 99L)))
        assertEquals(pending, ledger.read(key, CommentLikeState(liked = true, count = 100L)))
        assertNull(ledger.begin(key, server))
        assertEquals(server, first.second)
    }

    @Test fun successfulFinishAcknowledgesTheOptimisticState() {
        val ledger = CommentLikeLedger { 2_000L }
        val key = CommentLikeKey(epoch = 1L, type = 1, oid = "av2", rpid = 10L)
        val previous = CommentLikeState(liked = false, count = 5L)
        val operation = ledger.begin(key, previous) ?: error("operation should start")

        assertEquals(
            CommentLikeState(liked = true, count = 6L, pending = true),
            ledger.current(key)
        )
        assertEquals(
            CommentLikeState(liked = true, count = 6L),
            ledger.finish(key, operation.first, operation.second, ok = true)
        )
    }

    @Test fun failedFinishRollsBackToTheStateSeenBeforeBegin() {
        val ledger = CommentLikeLedger { 3_000L }
        val key = CommentLikeKey(epoch = 1L, type = 1, oid = "av3", rpid = 11L)
        val previous = CommentLikeState(liked = true, count = 1L)
        val operation = ledger.begin(key, previous) ?: error("operation should start")

        assertEquals(
            previous,
            ledger.finish(key, operation.first, operation.second, ok = false)
        )
    }

    @Test fun serverStateReplacesAcknowledgedStateAfterTheFifteenSecondWindow() {
        var now = 4_000L
        val ledger = CommentLikeLedger { now }
        val key = CommentLikeKey(epoch = 1L, type = 1, oid = "av4", rpid = 12L)
        val previous = CommentLikeState(liked = false, count = 20L)
        val operation = ledger.begin(key, previous) ?: error("operation should start")
        val acknowledged = CommentLikeState(liked = true, count = 21L)
        ledger.finish(key, operation.first, operation.second, ok = true)

        now += 14_999L
        assertEquals(acknowledged, ledger.read(key, CommentLikeState(liked = false, count = 0L)))
        now += 1L
        assertEquals(
            CommentLikeState(liked = false, count = 30L),
            ledger.read(key, CommentLikeState(liked = false, count = 30L))
        )
    }

    @Test fun oldPageCannotUndoAnAcknowledgedLikeAfterTheTtl() {
        var now = 1_000L
        val ledger = CommentLikeLedger { now }
        val key = CommentLikeKey(1L, 1, "aid1", 42L)
        val oldPage = CommentLikeState(false, 4L)
        val observedAt = System.nanoTime()
        ledger.read(key, oldPage, observedAt)
        val op = ledger.begin(key, oldPage)!!
        ledger.finish(key, op.first, op.second, true)
        now += 20_000L
        assertEquals(CommentLikeState(true, 5L), ledger.read(key, oldPage, observedAt))
        val cancel = ledger.begin(key, oldPage)!!
        assertEquals(CommentLikeState(true, 5L), cancel.second)
        assertEquals(CommentLikeState(false, 4L, true), ledger.current(key))
    }

    @Test fun clearingAnEpochIsolatesItAndAnOldOperationCannotWriteBack() {
        var now = 5_000L
        val ledger = CommentLikeLedger { now }
        val oldKey = CommentLikeKey(epoch = 1L, type = 1, oid = "av5", rpid = 13L)
        val oldState = CommentLikeState(liked = false, count = 2L)
        val oldOperation = ledger.begin(oldKey, oldState) ?: error("old operation should start")

        ledger.clearOtherSessions(epoch = 2L)
        assertNull(ledger.current(oldKey))
        assertNull(ledger.finish(oldKey, oldOperation.first, oldOperation.second, ok = true))

        val newKey = CommentLikeKey(epoch = 2L, type = 1, oid = "av5", rpid = 13L)
        assertEquals(oldState, ledger.read(newKey, oldState))

        val raceKey = CommentLikeKey(epoch = 2L, type = 1, oid = "av6", rpid = 14L)
        val first = ledger.begin(raceKey, oldState) ?: error("first race operation should start")
        ledger.finish(raceKey, first.first, first.second, ok = false)
        now += 15_000L
        val second = ledger.begin(raceKey, oldState) ?: error("second race operation should start")

        assertNull(ledger.finish(raceKey, first.first, first.second, ok = true))
        assertEquals(CommentLikeState(liked = true, count = 3L, pending = true), ledger.current(raceKey))
        assertTrue(second.first > first.first)
    }
}
