package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Word-timed lyrics from BetterLyrics — the backend behind the YouTube Music
 * browser extension of the same name.
 *
 * One key-less call keyed on title, artist and duration, answering with Apple
 * Music's own TTML. That combination is why it leads the chain: no track-id
 * lookup, no token to scrape, no login, and the timing is per-syllable.
 *
 * Note this is the extension's original host. The project's newer Cloudflare
 * API puts the same endpoint behind a Turnstile challenge, which a native
 * client has no way to answer.
 */
object BetterLyrics {

    private const val BASE = "https://lyrics-api.boidu.dev/getLyrics"
    private const val PORTATO = "https://lyrics-api.boidu.dev/qq/getLyrics"

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = document(title, artist, durationMs, album)
        ?.takeIf { ProviderLyrics.matchesStudioDuration(it, durationMs) }
        ?.lines

    /** QQ Music's karaoke timings through BetterLyrics' Portato endpoint. */
    suspend fun portato(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = portatoDocument(title, artist, durationMs, album)
        ?.takeIf { ProviderLyrics.matchesStudioDuration(it, durationMs) }
        ?.lines

    internal suspend fun document(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): ProviderLyrics.Document? = fetchDocument(BASE, title, artist, durationMs, album)

    internal suspend fun portatoDocument(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): ProviderLyrics.Document? = fetchDocument(PORTATO, title, artist, durationMs, album)

    private suspend fun fetchDocument(
        endpoint: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): ProviderLyrics.Document? = withContext(Dispatchers.IO) {
        if (durationMs <= 0L) return@withContext null
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("s", title)
            .addQueryParameter("a", artist)
            .apply {
                val seconds = durationMs / 1000
                if (seconds > 0) addQueryParameter("d", seconds.toString())
                if (!album.isNullOrBlank()) addQueryParameter("al", album)
            }
            .build()

        val body = lyricsGet(url.toString()) ?: return@withContext null
        ProviderLyrics.parseDocument(body)
    }

    internal fun matchingLines(raw: String, durationMs: Long): List<LyricLine>? {
        if (durationMs <= 0L) return null
        val doc = ProviderLyrics.parseDocument(raw) ?: return null
        return doc.lines.takeIf { ProviderLyrics.matchesStudioDuration(doc, durationMs) }
    }
}
