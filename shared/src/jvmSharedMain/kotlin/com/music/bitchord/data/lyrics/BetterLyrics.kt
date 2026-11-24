package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Word-timed lyrics from BetterLyrics — the backend behind the YouTube Music
 * browser extension of the same name.
 *
 * BetterLyrics serves cached Apple Music TTML and QQ Portato karaoke entries
 * publicly without authentication, while uncached upstream fetches require an
 * `X-API-Key` header (returning HTTP 401 when omitted).
 *
 * Because YouTube metadata frequently differs in album naming, exact duration
 * (music video intros/outros), or multi-artist credit strings, a single
 * over-constrained query routinely misses the public cache and hits 401.
 * We therefore walk descending specificity tiers with identical-URL
 * deduplication, support an optional user-configured `X-API-Key`, mirror
 * across both canonical (`api.betterlyrics.org`) and legacy
 * (`lyrics-api.boidu.dev`) hosts, and—when an unauthenticated lookup hits 401
 * on every tier—fall back to BetterLyrics' own primary upstream Apple TTML
 * catalogue ([LrcRed]).
 */
object BetterLyrics {

    internal const val PRIMARY_BASE = "https://api.betterlyrics.org/getLyrics"
    internal const val LEGACY_BASE = "https://lyrics-api.boidu.dev/getLyrics"
    internal const val PRIMARY_PORTATO = "https://api.betterlyrics.org/qq/getLyrics"
    internal const val LEGACY_PORTATO = "https://lyrics-api.boidu.dev/qq/getLyrics"

    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|\bfeat\.?\b|\bft\.?\b|\bx\b|×|·)\s*""", RegexOption.IGNORE_CASE)
    private val TRAILING_EDITION = Regex("""\s*[-–—]\s*(?:remaster(?:ed)?|single|album|radio|deluxe|explicit|clean).*$""", RegexOption.IGNORE_CASE)

    @Volatile
    private var apiKey: String = ""

    fun setApiKey(value: String) {
        apiKey = normalizeBetterLyricsApiKey(value)
    }

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = fetch(
        endpoints = listOf(PRIMARY_BASE, LEGACY_BASE),
        title = title,
        artist = artist,
        durationMs = durationMs,
        album = album,
        fallbackToUpstreamTtml = true,
    )

    /** QQ Music's karaoke timings through BetterLyrics' Portato endpoint. */
    suspend fun portato(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = fetch(
        endpoints = listOf(PRIMARY_PORTATO, LEGACY_PORTATO),
        title = title,
        artist = artist,
        durationMs = durationMs,
        album = album,
        fallbackToUpstreamTtml = false,
    )

    /**
     * Builds descending specificity query URLs for a single [endpoint]:
     * 1. Full metadata (`s`, `a`, `d`, `al`)
     * 2. Without album (`s`, `a`, `d`)
     * 3. Title + artist only (`s`, `a`)
     * 4. Cleaned core title + primary artist (when different from tier 3)
     */
    internal fun buildCandidateUrls(
        endpoint: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<String> {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isEmpty()) return emptyList()

        val seconds = (durationMs / 1000).takeIf { it > 0 }
        val cleanAlbum = album?.trim()?.takeIf { it.isNotEmpty() }

        val urls = LinkedHashSet<String>(4)
        fun addTier(s: String, a: String, d: Long?, al: String?) {
            val url = endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("s", s)
                .addQueryParameter("a", a)
                .apply {
                    if (d != null && d > 0) addQueryParameter("d", d.toString())
                    if (!al.isNullOrBlank()) addQueryParameter("al", al)
                }
                .build()
                .toString()
            urls.add(url)
        }

        if (cleanAlbum != null && seconds != null) {
            addTier(cleanTitle, cleanArtist, seconds, cleanAlbum)
        }
        if (seconds != null) {
            addTier(cleanTitle, cleanArtist, seconds, null)
        }
        addTier(cleanTitle, cleanArtist, null, null)

        val primaryArtist = primaryArtistOf(cleanArtist)
        val coreTitle = TRAILING_EDITION.replace(cleanTitle, "").trim().ifBlank { cleanTitle }
        if (primaryArtist != cleanArtist || coreTitle != cleanTitle) {
            addTier(coreTitle, primaryArtist, null, null)
        }
        return urls.toList()
    }

    internal fun primaryArtistOf(artist: String): String =
        ARTIST_SPLIT.split(artist.trim()).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    private suspend fun fetch(
        endpoints: List<String>,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        fallbackToUpstreamTtml: Boolean,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val key = apiKey
        val headers = if (key.isNotBlank()) mapOf("X-API-Key" to key) else emptyMap()
        var sawUnauthorized = false

        for (endpoint in endpoints) {
            val candidates = buildCandidateUrls(endpoint, title, artist, durationMs, album)
            var transportOrServerError = false

            for (url in candidates) {
                val response = lyricsGetResponse(
                    url = url,
                    extraHeaders = headers,
                    authenticated = key.isNotBlank(),
                )
                if (response == null) {
                    transportOrServerError = true
                    break
                }
                if (response.code == 429) {
                    // Respect edge rate limiting immediately instead of hammering further tiers.
                    return@withContext null
                }
                if (response.code >= 500) {
                    transportOrServerError = true
                    break
                }
                if (response.code == 401 || response.code == 403) {
                    sawUnauthorized = true
                    continue
                }
                if (response.isSuccessful && !response.body.isNullOrBlank()) {
                    val parsed = ProviderLyrics.parse(response.body)
                    if (!parsed.isNullOrEmpty()) return@withContext parsed
                }
            }

            // Both hosts share the same cache; only try the legacy mirror when
            // the primary host itself failed at the transport/5xx layer.
            if (!transportOrServerError) break
        }

        // When keyless BetterLyrics misses the edge cache (HTTP 401), query
        // BetterLyrics' own primary upstream Apple TTML catalogue directly so
        // listeners still get syllable-synced TTML lyrics.
        if (fallbackToUpstreamTtml && sawUnauthorized && key.isBlank()) {
            return@withContext LrcRed.lyrics(title, artist, durationMs)
        }
        null
    }
}

internal fun normalizeBetterLyricsApiKey(value: String): String =
    value.trim().removePrefix("Bearer ").trim()
