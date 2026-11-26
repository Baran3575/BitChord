package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BetterLyricsTest {

    @Test
    fun `buildCandidateUrls emits descending specificity tiers without duplicates`() {
        val urls = BetterLyrics.buildCandidateUrls(
            endpoint = BetterLyrics.PRIMARY_BASE,
            title = "Blinding Lights (Official Video) - 2020 Remaster",
            artist = "The Weeknd & Daft Punk",
            durationMs = 200_000L,
            album = "After Hours",
        )

        assertEquals(
            listOf(
                "https://api.betterlyrics.org/getLyrics?s=Blinding%20Lights%20-%202020%20Remaster&a=The%20Weeknd%20%26%20Daft%20Punk&d=200&al=After%20Hours",
                "https://api.betterlyrics.org/getLyrics?s=Blinding%20Lights%20-%202020%20Remaster&a=The%20Weeknd%20%26%20Daft%20Punk&d=200",
                "https://api.betterlyrics.org/getLyrics?s=Blinding%20Lights%20-%202020%20Remaster&a=The%20Weeknd%20%26%20Daft%20Punk",
                "https://api.betterlyrics.org/getLyrics?s=Blinding%20Lights%20-%202020%20Remaster&a=The%20Weeknd%2C%20Daft%20Punk&d=200",
                "https://api.betterlyrics.org/getLyrics?s=Blinding%20Lights&a=The%20Weeknd",
            ),
            urls,
        )
    }

    @Test
    fun `buildCandidateUrls omits zero duration and blank album cleanly`() {
        val urls = BetterLyrics.buildCandidateUrls(
            endpoint = BetterLyrics.PRIMARY_BASE,
            title = "Blinding Lights",
            artist = "The Weeknd",
            durationMs = 0L,
            album = "   ",
        )

        assertEquals(
            listOf("https://api.betterlyrics.org/getLyrics?s=Blinding%20Lights&a=The%20Weeknd"),
            urls,
        )
    }

    @Test
    fun `normalizeCollabArtists and primaryArtistOf handle featured credits`() {
        assertEquals("ROSÉ, Bruno Mars", BetterLyrics.normalizeCollabArtists("ROSÉ & Bruno Mars"))
        assertEquals("Duman, Teoman", BetterLyrics.normalizeCollabArtists("Duman feat. Teoman"))
        assertEquals("The Weeknd", BetterLyrics.primaryArtistOf("The Weeknd, Daft Punk"))
        assertEquals("Sezen Aksu", BetterLyrics.primaryArtistOf("Sezen Aksu & Tarkan"))
    }

    @Test
    fun `normalizeBetterLyricsApiKey trims whitespace and Bearer prefix`() {
        assertEquals("bl_test_123", normalizeBetterLyricsApiKey("  Bearer bl_test_123  "))
        assertEquals("bl_test_123", normalizeBetterLyricsApiKey("bearer  bl_test_123"))
        assertEquals("bl_test_123", normalizeBetterLyricsApiKey("bl_test_123"))
        assertEquals("", normalizeBetterLyricsApiKey("   "))
    }

    @Test
    fun `ProviderLyrics parses escaped BetterLyrics TTML payload`() {
        val raw = """{"ttml":"\u003ctt xmlns=\"http://www.w3.org/ns/ttml\" itunes:timing=\"Word\"\u003e\u003cbody\u003e\u003cdiv\u003e\u003cp begin=\"1.000\" end=\"3.000\"\u003e\u003cspan begin=\"1.000\" end=\"1.800\"\u003eHello\u003c/span\u003e \u003cspan begin=\"1.800\" end=\"3.000\"\u003eworld\u003c/span\u003e\u003c/p\u003e\u003c/div\u003e\u003c/body\u003e\u003c/tt\u003e"}"""
        val lines = ProviderLyrics.parse(raw)
        assertNotNull(lines)
        assertEquals(1, lines.size)
        assertEquals("Hello world", lines[0].text)
        assertTrue(lines[0].isWordSynced)
        assertFalse(lines[0].words.isEmpty())
    }

    @Test
    fun `fetchFromEndpoints sends browser headers, relaxes on 401, and fails over on 403 or 429`() = runBlocking {
        val primary = MockWebServer()
        val mirror = MockWebServer()
        primary.start()
        mirror.start()
        try {
            // Primary returns 403 (e.g. Cloudflare WAF) on first attempt
            primary.enqueue(MockResponse().setResponseCode(403).setBody("Blocked"))
            // Mirror returns 401 on album+duration tier, then 200 on duration-only tier
            mirror.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"API key required"}"""))
            mirror.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"ttml":"<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"1.0\" end=\"2.0\"><span begin=\"1.0\" end=\"1.5\">Hello</span> <span begin=\"1.5\" end=\"2.0\">there</span></p></div></body></tt>"}""",
                ),
            )

            val result = BetterLyrics.fetchFromEndpoints(
                endpoints = listOf(primary.url("/getLyrics").toString(), mirror.url("/getLyrics").toString()),
                title = "Hello",
                artist = "Artist",
                durationMs = 180_000L,
                album = "Album",
                keyOverride = "my_secret_key",
            )

            assertNotNull(result)
            assertEquals("Hello there", result.single { !it.isGap }.text)

            val primaryReq = primary.takeRequest()
            assertEquals(BetterLyrics.BETTER_LYRICS_USER_AGENT, primaryReq.getHeader("User-Agent"))
            assertEquals("my_secret_key", primaryReq.getHeader("X-API-Key"))
            assertEquals("https://music.youtube.com", primaryReq.getHeader("Origin"))

            val mirrorReq1 = mirror.takeRequest()
            val mirrorReq2 = mirror.takeRequest()
            assertTrue(mirrorReq1.path!!.contains("al=Album"))
            assertFalse(mirrorReq2.path!!.contains("al="))
        } finally {
            primary.shutdown()
            mirror.shutdown()
        }
    }
}
