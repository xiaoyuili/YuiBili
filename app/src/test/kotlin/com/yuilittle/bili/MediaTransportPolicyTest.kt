package com.yuilittle.bili

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MediaTransportPolicyTest {
    @Test
    fun enforcesHttpsForMediaUrls() {
        assertEquals("https://upos-sz-mirrorcos.bilivideo.com/a.m4s",
            MediaTransportPolicy.validateMediaUrl("https://upos-sz-mirrorcos.bilivideo.com/a.m4s").toString())
        try {
            MediaTransportPolicy.validateMediaUrl("http://upos-sz-mirrorcos.bilivideo.com/a.m4s")
            throw AssertionError("HTTP media url must be rejected")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun attachesAnonymousCookieOnlyToTrustedCdnHosts() {
        val trusted = MediaTransportPolicy.buildCdnHeaders(
            "https://upos-sz-mirrorcos.bilivideo.com/test.m4s".toHttpUrl(),
            anonymousCookie = "buvid3=abc; buvid4=def",
            rangeHeader = "bytes=0-1023"
        )
        assertEquals("buvid3=abc; buvid4=def", trusted["Cookie"])
        assertEquals("bytes=0-1023", trusted["Range"])
        assertEquals("https://www.bilibili.com/", trusted["Referer"])

        val untrusted = MediaTransportPolicy.buildCdnHeaders(
            "https://evil.example.net/test.m4s".toHttpUrl(),
            anonymousCookie = "buvid3=abc; buvid4=def"
        )
        assertNull(untrusted["Cookie"])
    }

    @Test
    fun aPartialOrUnknownLength206MustNotBePublishedAsTheWholeFile() {
        fun complete(raw: String, bytes: Long) = MediaTransportPolicy.parseContentRange(raw)!!.completesOpenEndedRequest(1024L, bytes)
        assertTrue(complete("bytes 1024-2047/2048", 1024L))
        assertFalse(complete("bytes 1024-2047/*", 1024L))
        assertFalse(complete("bytes 1024-2047/4096", 1024L))
        assertFalse(complete("bytes 1024-2047/2048", 999L))
    }

    @Test
    fun parsesContentRangeAndByteRangesStrictly() {
        val satisfiable = MediaTransportPolicy.parseContentRange("bytes 1024-2047/4096")
        assertNotNull(satisfiable)
        assertTrue(satisfiable!!.isSatisfiableRange)
        assertFalse(satisfiable.isUnsatisfiable)
        assertEquals(1024L, satisfiable.start)
        assertEquals(2047L, satisfiable.end)
        assertEquals(4096L, satisfiable.total)

        val unsatisfiable = MediaTransportPolicy.parseContentRange("bytes */4096")
        assertNotNull(unsatisfiable)
        assertTrue(unsatisfiable!!.isUnsatisfiable)
        assertEquals(4096L, unsatisfiable.total)

        assertNull(MediaTransportPolicy.parseContentRange("bytes 200-100/4096"))
        assertNull(MediaTransportPolicy.parseContentRange("bytes 0-4096/4096"))
        assertNull(MediaTransportPolicy.parseContentRange("invalid"))

        assertEquals(0L..891L, MediaTransportPolicy.parseByteRangeSpec("0-891"))
        assertNull(MediaTransportPolicy.parseByteRangeSpec("100-50"))
        assertNull(MediaTransportPolicy.parseByteRangeSpec("0-1\" bad=\"x"))
    }
}
