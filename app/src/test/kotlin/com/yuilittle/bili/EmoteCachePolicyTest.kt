package com.yuilittle.bili

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class EmoteCachePolicyTest {
    @Test fun repeatedFailureHasALongerExpiryInsteadOfPermanentlyBlockingTheUrl() {
        val first = nextEmoteFailure(null, 1_000)
        assertEquals(EmoteFailure(1, 6_000), first)
        val second = nextEmoteFailure(first, 6_000)
        assertEquals(EmoteFailure(2, 66_000), second)
        assertTrue(second.retryAtMs <= 66_000L)
    }

    @Test fun diskBudgetTrimsOldFilesAndKeepsValidReads() {
        val path = Files.createTempDirectory("yuibili-emote-test").toFile()
        try {
            val cache = EmoteDiskCache(path, 16)
            cache.write("one.img", ByteArray(8) { 1 })
            cache.write("two.img", ByteArray(8) { 2 })
            cache.write("three.img", ByteArray(8) { 3 })
            assertTrue(path.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() } <= 16)
            assertTrue(path.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
            val current = path.listFiles().orEmpty().first { it.isFile }
            assertEquals(8, cache.read(current.name, 8)?.size)
            assertNull(cache.read(current.name, 4))
        } finally { path.deleteRecursively() }
    }

    @Test fun discardDoesNotLeaveAnOversizedFileInTheCache() {
        val path = Files.createTempDirectory("yuibili-emote-discard").toFile()
        try {
            val cache = EmoteDiskCache(path, 32)
            cache.write("bad.img", ByteArray(10))
            cache.discard("bad.img")
            assertNull(cache.read("bad.img", 32))
        } finally { path.deleteRecursively() }
    }
}
