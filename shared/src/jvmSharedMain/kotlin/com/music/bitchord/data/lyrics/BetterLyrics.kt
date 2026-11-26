package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Word-timed lyrics from BetterLyrics — the backend behind the YouTube Music
 * browser extension of the same name.
 *
 * BetterLyrics serves cached Apple Music TTML and QQ Portato karaoke entries
 * without authentication, while uncached upstream queries require an `X-API-Key`
 * header (returning HTTP 401 when omitted).
 *
 * Why lookups previously failed:
 *  - `lyrics-api.boidu.dev` rejects non-browser `User-Agent` strings (including
 *    BitChord's default `LYRICS_AGENT`) with a Cloudflare HTTP 403 challenge.
 *  - Over-constrained queries with album names (`al`), music-video durations
 *    (`d`), or `&`/`feat.` artist delimiters miss the public cache and hit 401.
 *
 * We send browser-compatible request headers, walk descending specificity
 * tiers with URL deduplication, support an optional user-configured `X-API-Key`,
 * and automatically fail over between `api.betterlyrics.org` and
 * `lyrics-api.boidu.dev` on transport, 403, 429, or 5xx errors.
 */
object BetterLyrics {

    internal const val PRIMARY_BASE = "https://api.betterlyrics.org/getLyrics"
    internal const val LEGACY_BASE = "https://lyrics-api.boidu.dev/getLyrics"
    internal const val PRIMARY_PORTATO = "https://api.betterlyrics.org/qq/getLyrics"
    internal const val LEGACY_PORTATO = "https://lyrics-api.boidu.dev/qq/getLyrics"

    internal const val BETTER_LYRICS_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val ARTIST_SPLIT =
        Regex("""\s*(?:,|&|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|\bx\b|×|·)\s*""", RegexOption.IGNORE_CASE)
    private val COLLAB_NORMALIZE =
        Regex("""\s*(?:&|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|\bx\b|×|·)\s*""", RegexOption.IGNORE_CASE)
    private val TRAILING_EDITION =
        Regex("""\s*[-–—]\s*(?:\d{4}\s+)?(?:remaster(?:ed)?|single|album|radio|deluxe|explicit|clean).*$""", RegexOption.IGNORE_CASE)

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
    ): List<LyricLine>? = fetchFromEndpoints(
        endpoints = listOf(PRIMARY_BASE, LEGACY_BASE),
        title = title,
        artist = artist,
        durationMs = durationMs,
        album = album,
    )

    /** QQ Music's karaoke timings through BetterLyrics' Portato endpoint. */
    suspend fun portato(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = fetchFromEndpoints(
        endpoints = listOf(PRIMARY_PORTATO, LEGACY_PORTATO),
        title = title,
        artist = artist,
        durationMs = durationMs,
        album = album,
    )

    /**
     * Builds descending specificity query URLs for a single [endpoint]:
     * 1. Full metadata (`s`, `a`, `d`, `al`)
     * 2. Without album (`s`, `a`, `d`)
     * 3. Title + artist only (`s`, `a`)
     * 4. Comma-normalized multi-artist string (`ROSÉ & Bruno Mars` -> `ROSÉ, Bruno Mars`)
     * 5. Cleaned core title + primary artist (when different from earlier tiers)
     */
    internal fun buildCandidateUrls(
        endpoint: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<String> {
        val cleanTitle = title.forLyricsSearch().trim()
        val cleanArtist = artist.artistForLyricsSearch().trim()
        if (cleanTitle.isEmpty()) return emptyList()

        val seconds = (durationMs / 1000).takeIf { it > 0 }
        val cleanAlbum = album?.trim()?.takeIf { it.isNotEmpty() }

        val urls = LinkedHashSet<String>(5)
        fun addTier(s: String, a: String, d: Long?, al: String?) {
            if (urls.size >= MAX_CANDIDATE_TIERS) return
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

        val commaArtist = normalizeCollabArtists(cleanArtist)
        if (commaArtist != cleanArtist) {
            addTier(cleanTitle, commaArtist, seconds, null)
        }

        val primaryArtist = primaryArtistOf(cleanArtist)
        val coreTitle = TRAILING_EDITION.replace(cleanTitle, "").trim().ifBlank { cleanTitle }
        if (primaryArtist != cleanArtist || coreTitle != cleanTitle) {
            addTier(coreTitle, primaryArtist, null, null)
        }
        return urls.toList()
    }

    internal fun normalizeCollabArtists(artist: String): String =
        COLLAB_NORMALIZE.replace(artist.trim(), ", ")
            .replace(MULTI_COMMA_SPACE, ", ")
            .trim(',', ' ')

    internal fun primaryArtistOf(artist: String): String =
        ARTIST_SPLIT.split(artist.trim()).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    internal suspend fun fetchFromEndpoints(
        endpoints: List<String>,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        keyOverride: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val key = keyOverride ?: apiKey
        val headers = buildMap {
            put("User-Agent", BETTER_LYRICS_USER_AGENT)
            put("Origin", "https://music.youtube.com")
            put("Referer", "https://music.youtube.com/")
            if (key.isNotBlank()) put("X-API-Key", key)
        }

        for (endpoint in endpoints) {
            val candidates = buildCandidateUrls(endpoint, title, artist, durationMs, album)
            var shouldTryNextMirror = false

            for (url in candidates) {
                val response = lyricsGetResponse(
                    url = url,
                    extraHeaders = headers,
                    authenticated = key.isNotBlank(),
                )
                if (response == null || response.code == 403 || response.code == 429 || response.code >= 500) {
                    // Fail over to the mirror host if this host blocked, rate-limited,
                    // errored, or failed at the transport layer.
                    shouldTryNextMirror = true
                    break
                }
                if (response.code == 401) {
                    // Cache miss without an API key (or invalid key): try the next relaxed query tier.
                    continue
                }
                if (response.isSuccessful && !response.body.isNullOrBlank()) {
                    val parsed = ProviderLyrics.parse(response.body)
                    if (!parsed.isNullOrEmpty()) return@withContext parsed
                }
            }

            if (!shouldTryNextMirror) break
        }
        null
    }

    private const val MAX_CANDIDATE_TIERS = 5
    private val MULTI_COMMA_SPACE = Regex("""\s*,\s*(?:,\s*)+""")
}

private val BEARER_PREFIX = Regex("""^bearer\s+""", RegexOption.IGNORE_CASE)

/** Trims whitespace and any leading `Bearer ` prefix from a BetterLyrics API key. */
fun normalizeBetterLyricsApiKey(value: String): String =
    value.trim().replace(BEARER_PREFIX, "").trim()
