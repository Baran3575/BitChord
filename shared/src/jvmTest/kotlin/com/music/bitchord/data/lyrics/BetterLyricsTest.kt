package com.music.bitchord.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BetterLyricsTest {

    @Test
    fun `buildCandidateUrls walks descending specificity tiers without duplicates`() {
        val urls = BetterLyrics.buildCandidateUrls(
            endpoint = BetterLyrics.PRIMARY_BASE,
            title = "Starboy - Remastered",
            artist = "The Weeknd, Daft Punk",
            durationMs = 230_000L,
            album = "Starboy",
        )

        assertEquals(4, urls.size)
        assertTrue(urls[0].contains("d=230") && urls[0].contains("al=Starboy"))
        assertTrue(urls[1].contains("d=230") && !urls[1].contains("al="))
        assertTrue(!urls[2].contains("d=") && !urls[2].contains("al=") && urls[2].contains("Daft"))
        assertTrue(urls[3].contains("s=Starboy") && !urls[3].contains("Remastered") && !urls[3].contains("Daft"))
    }

    @Test
    fun `buildCandidateUrls deduplicates when optional metadata is absent`() {
        val urls = BetterLyrics.buildCandidateUrls(
            endpoint = BetterLyrics.PRIMARY_BASE,
            title = "Blinding Lights",
            artist = "The Weeknd",
            durationMs = 0L,
            album = null,
        )

        assertEquals(1, urls.size)
        assertEquals(
            "https://api.betterlyrics.org/getLyrics?s=Blinding%20Lights&a=The%20Weeknd",
            urls.single(),
        )
    }

    @Test
    fun `primaryArtistOf strips collaborations and featured credits`() {
        assertEquals("The Weeknd", BetterLyrics.primaryArtistOf("The Weeknd, Daft Punk"))
        assertEquals("Sezen Aksu", BetterLyrics.primaryArtistOf("Sezen Aksu & Tarkan"))
        assertEquals("Duman", BetterLyrics.primaryArtistOf("Duman feat. Teoman"))
        assertEquals("Tarkan", BetterLyrics.primaryArtistOf("Tarkan"))
    }

    @Test
    fun `normalizeBetterLyricsApiKey trims whitespace and Bearer prefix`() {
        assertEquals("bl_test_123", normalizeBetterLyricsApiKey("  Bearer bl_test_123  "))
        assertEquals("bl_test_123", normalizeBetterLyricsApiKey("bl_test_123"))
        assertEquals("", normalizeBetterLyricsApiKey("   "))
    }

    @Test
    fun `ProviderLyrics parses escaped BetterLyrics TTML payload`() {
        val raw = """{"ttml":"\u003ctt xmlns=\"http://www.w3.org/ns/ttml\" itunes:timing=\"Word\"\u003e\u003cbody\u003e\u003cdiv\u003e\u003cp begin=\"1.000\" end=\"3.000\"\u003e\u003cspan begin=\"1.000\" end=\"1.800\"\u003eHello\u003c/span\u003e \u003cspan begin=\"1.800\" end=\"3.000\"\u003eworld\u003c/span\u003e\u003c/p\u003e\u003c/div\u003e\u003c/body\u003e\u003c/tt\u003e"}"""
        val lines = ProviderLyrics.parse(raw)
        assertNotNull(lines)
        assertEquals(1, lines!!.size)
        assertEquals("Hello world", lines[0].text)
        assertTrue(lines[0].isWordSynced)
        assertFalse(lines[0].words.isEmpty())
    }
}
