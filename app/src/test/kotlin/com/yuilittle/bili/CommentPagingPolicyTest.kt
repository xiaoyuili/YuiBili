package com.yuilittle.bili

import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.security.MessageDigest

class CommentPagingPolicyTest {
    @Test fun paginationOffsetUsesJsonEscaping() {
        assertEquals("{\"offset\":\"\"}", encodeCommentOffsetJson(null))
        assertEquals("{\"offset\":\"a\\\"b\\\\c\\n\\u0001\"}", encodeCommentOffsetJson("a\"b\\c\n\u0001"))
    }

    @Test fun missingEndMarkerIsBlockedEvenIfANextOffsetExists() {
        assertEquals(CommentCursorDecision(null, false, "分页响应不完整"), decideCommentCursor(CommentPageCursor("b", null)))
    }

    @Test fun jsonEncodingProtectsPunctuationThatTheWbiSignerFilters() {
        assertEquals("{\"offset\":\"\\u0021\\u0027\\u0028\\u0029\\u002a\"}", quoteCursorForWbi(encodeCommentOffsetJson("!'()*")))
    }

    @Test fun endHasNoNextPageButAValidCursorCanAdvance() {
        assertEquals(CommentCursorDecision(null, false), decideCommentCursor(CommentPageCursor(null, true)))
        assertEquals(CommentCursorDecision("b", true), decideCommentCursor(CommentPageCursor("b", false)))
    }

    @Test fun aMissingCursorIsBlockedButAnOpaqueCursorCanBeReused() {
        assertEquals(CommentCursorDecision(null, false, "未返回下一页位置"), decideCommentCursor(CommentPageCursor(null, false)))
        assertEquals(CommentCursorDecision("session-token", true), decideCommentCursor(CommentPageCursor("session-token", false)))
    }

    @Test fun reusedCursorWithNewCommentsContinuesUntilAnExplicitEnd() {
        val cursor = CommentPageCursor("session-token", false)
        var state = CommentPageState()
        repeat(4) {
            val decision = decideCommentCursor(cursor)
            state = state.accept(decision.nextOffset, decision.hasMore, 20, decision.failureReason)
            assertEquals("session-token", state.offset)
            assertTrue(state.hasMore)
            assertFalse(state.failed)
            assertFalse(state.manualNext)
        }
        val end = decideCommentCursor(CommentPageCursor(null, true))
        state = state.accept(end.nextOffset, end.hasMore, 3, end.failureReason)
        assertFalse(state.hasMore)
        assertFalse(state.manualNext)
    }

    @Test fun aPageWithNoNewCommentIdsPausesAutomaticPagingEvenIfTheCursorChanges() {
        for (next in listOf("same-token", "new-token")) {
            val state = CommentPageState("same-token").accept(next, true, 0)
            assertTrue(state.hasMore)
            assertTrue(state.manualNext)
            assertFalse(state.failed)
            assertEquals(next, state.offset)
            val restored = state.copy()
            assertTrue(restored.manualNext)
            val continued = restored.retry()
            assertFalse(continued.manualNext)
            assertEquals(next, continued.offset)
            assertFalse(continued.accept("after-manual", true, 2).manualNext)
        }
    }

    @Test fun blockedPageRetriesTheSameOffsetUntilASuccessfulResponse() {
        val current = CommentPageState("a")
        val decision = decideCommentCursor(CommentPageCursor(null, false))
        val blocked = current.accept(decision.nextOffset, decision.hasMore, 3, decision.failureReason)
        assertTrue(blocked.failed)
        assertFalse(blocked.hasMore)
        assertEquals("a", blocked.offset)
        val retry = blocked.retry()
        assertTrue(retry.hasMore)
        assertFalse(retry.failed)
        assertEquals("a", retry.offset)
        val next = retry.accept("b", true, 20)
        assertEquals("b", next.offset)
        assertNull(next.failureReason)
    }

    @Test fun networkFailureAndSavedStateKeepTheOriginalReasonAndCurrentPage() {
        val failed = CommentPageState("page-4").fail("连接超时")
        val restored = failed.copy()
        assertEquals("连接超时", restored.failureReason)
        assertTrue(restored.failed)
        assertEquals("page-4", restored.offset)
        val retry = restored.retry()
        assertNull(retry.failureReason)
        assertEquals("page-4", retry.offset)
    }

    @Test fun retryCannotReopenASuccessfullyEndedList() {
        val ended = CommentPageState("last").accept(null, false, 0)
        assertFalse(ended.retry().hasMore)
        assertFalse(ended.failed)
        assertFalse(ended.manualNext)
    }

    @Test fun aFailedFirstPageCanRetryWithoutInventingACursor() {
        val retry = CommentPageState().fail("HTTP 503").retry()
        assertTrue(retry.hasMore)
        assertNull(retry.offset)
    }

    @Test fun fullWbiQueryPreservesTheEncodedOffsetAndSignsExactlyWhatIsSent() {
        val offset = "a\"b\\c\n\u0001!'()*%+&=中文"
        val mixin = "0123456789abcdef0123456789abcdef"
        val encoded = quoteCursorForWbi(encodeCommentOffsetJson(offset))
        val query = BiliWbiSign.signWithMixin(mapOf("pagination_str" to encoded), mixin, 12345)
        val values = query.split('&').associate { part ->
            URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }
        assertEquals(encoded, values["pagination_str"])
        assertEquals("{\"offset\":\"a\\\"b\\\\c\\n\\u0001\\u0021\\u0027\\u0028\\u0029\\u002a%+&=中文\"}", values["pagination_str"])
        val signed = query.substringBeforeLast("&w_rid=")
        val expected = MessageDigest.getInstance("MD5").digest((signed + mixin).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(expected, values["w_rid"])
    }
}
