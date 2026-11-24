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
        get: suspend (String) -> String? = { lyricsGet(it) },
    ): List<LyricLine>? = fetch(BASE, title, artist, durationMs, album, get)

    /** QQ Music's karaoke timings through BetterLyrics' Portato endpoint. */
    suspend fun portato(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        get: suspend (String) -> String? = { lyricsGet(it) },
    ): List<LyricLine>? = fetch(PORTATO, title, artist, durationMs, album, get)

    private suspend fun fetch(
        endpoint: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        get: suspend (String) -> String?,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val seconds = durationMs / 1000
        for (stage in lyricsQueryStages(title, artist, album)) {
            val url = endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("s", stage.title)
                .addQueryParameter("a", stage.artist)
                .apply {
                    if (seconds > 0) addQueryParameter("d", seconds.toString())
                    if (!stage.album.isNullOrBlank()) addQueryParameter("al", stage.album)
                }
                .build()

            val body = get(url.toString()) ?: continue
            val lines = ProviderLyrics.parse(body)?.takeIf { it.isNotEmpty() }
            if (lines != null) return@withContext lines
        }
        null
    }
}
