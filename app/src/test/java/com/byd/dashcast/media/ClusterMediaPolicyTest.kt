package com.byd.dashcast.media

import org.junit.Assert.*
import org.junit.Test

class ClusterMediaPolicyTest {
    @Test fun `encoding matches observed padding and UTF16LE without BOM`() {
        val bytes = ClusterMediaEncoding.text("Title", "Artist")
        assertEquals("  Artist - Title  ", bytes.toString(Charsets.UTF_16LE))
        assertEquals(0x20, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
    }
    @Test fun `empty artist sends title and empty text sends four spaces`() {
        assertEquals("  Title  ", ClusterMediaEncoding.text("Title", "").toString(Charsets.UTF_16LE))
        assertEquals("    ", ClusterMediaEncoding.text("", "").toString(Charsets.UTF_16LE))
    }
    @Test fun `unicode truncation preserves padding and whole surrogate pairs`() {
        for (value in listOf("é".repeat(1000), "中文".repeat(1000), "🚗".repeat(1000), "أغنية".repeat(1000))) {
            val bytes = ClusterMediaEncoding.text(value, "")
            assertTrue(bytes.size <= 254)
            assertEquals(0, bytes.size % 2)
            val result = bytes.toString(Charsets.UTF_16LE)
            assertTrue(result.startsWith("  ") && result.endsWith("  "))
            assertFalse(result.contains('\uFFFD'))
            assertArrayEquals(bytes, result.toByteArray(Charsets.UTF_16LE))
        }
    }
    @Test fun `unpaired surrogates and control characters cannot create malformed payload`() {
        val result = ClusterMediaEncoding.text("A\uD800B\uDC00\nC\u0000D", "").toString(Charsets.UTF_16LE)
        assertEquals("  A\uFFFDB\uFFFD C D  ", result)
    }
    @Test fun `playing session wins over the first paused session`() {
        val paused = candidate("p", MediaPlayback.PAUSED)
        val playing = candidate("a", MediaPlayback.PLAYING)
        assertEquals("a", MediaSourceSelector.select(listOf(paused, playing), null)?.source)
    }
    @Test fun `current playing identity stays selected when system reorders sessions`() {
        assertEquals("a", MediaSourceSelector.select(listOf(candidate("b"), candidate("a")), "a")?.source)
    }
    @Test fun `pause retains only previously selected title`() {
        assertNull(MediaSourceSelector.select(listOf(candidate("p", MediaPlayback.PAUSED)), null))
        val result = MediaSourceSelector.select(listOf(candidate("a", MediaPlayback.PAUSED)), "a")
        assertEquals("a", result?.source)
        assertFalse(result!!.playing)
    }
    @Test fun `new playing session replaces paused selected source`() {
        assertEquals("b", MediaSourceSelector.select(listOf(candidate("a", MediaPlayback.PAUSED), candidate("b")), "a")?.source)
    }
    @Test fun `missing or blank metadata and destroyed or stopped source lose ownership`() {
        assertNull(MediaSourceSelector.select(emptyList(), "a"))
        assertNull(MediaSourceSelector.select(listOf(candidate("a", MediaPlayback.OTHER)), "a"))
        assertNull(MediaSourceSelector.select(listOf(candidate("a", title = " \n\u0000")), "a"))
    }
    private fun candidate(source: String, playback: MediaPlayback = MediaPlayback.PLAYING, title: String = "Title") =
        MediaCandidate(source, title, "", playback)
}
