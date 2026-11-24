package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Apple Music TTML again, from a third host — and the only one here that will
 * answer to a recording rather than to a name.
 *
 * The catalogue is the same one [BetterLyrics] serves; on a track both have,
 * the documents come back byte for byte identical. What it adds is a different
 * matcher over that catalogue, which is not nothing — it finds tracks
 * [BetterLyrics] misses, particularly outside the English-language releases —
 * and, more importantly, an index by ISRC.
 *
 * ### Why the ISRC matters
 *
 * Every other source here is asked for a title, an artist and a length, and
 * hopes. "Dracula" by Tame Impala exists as a single at 205 seconds and an
 * album cut at 206, with different words in places, and nothing in a fuzzy
 * match reliably tells those apart. An ISRC names one recording and only that
 * recording, so a lookup that has one cannot come back with the wrong edit.
 *
 * A search here also *reports* the ISRC of whatever it matched, which is where
 * [LyricsRepository] gets one to hand to the sources that can use it. That is
 * the whole arrangement: this asks by name once, and everything afterwards can
 * ask by recording.
 *
 * Two requests either way — the search returns a URL rather than the document,
 * so the TTML itself is a second fetch from the storage host.
 */
object BiniLyrics {

    private const val BASE = "https://lyrics-api.binimum.org/"

    /** Lyrics, and the ISRC of the recording they were matched to. */
    data class Match(val isrc: String?, val lines: List<LyricLine>)

    /**
     * Which recording this is, without fetching its words.
     *
     * Matches by ISRC first when one is already known, otherwise runs up to
     * [MAX_LYRICS_QUERY_STAGES] staged name queries (full metadata, without
     * album, and lead artist) and checks any ISRC shared mid-flight.
     */
    internal suspend fun identify(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        isrc: String? = null,
        isrcProvider: (() -> String?)? = null,
        searchGet: suspend (String) -> String? = { lyricsGet(it) },
    ): Hit? = withContext(Dispatchers.IO) {
        var attempts = 0
        val triedIsrcs = HashSet<String>()

        suspend fun queryIsrc(code: String): Hit? {
            attempts++
            triedIsrcs.add(code)
            val url = BASE.toHttpUrl().newBuilder()
                .addQueryParameter("isrc", code)
                .build()
            return queryHits(url.toString(), searchGet)?.firstOrNull()
        }

        val initialIsrc = (isrc ?: isrcProvider?.invoke())?.trim()?.takeIf { it.isNotEmpty() }
        if (initialIsrc != null) {
            queryIsrc(initialIsrc)?.let { return@withContext it }
        }

        // When an explicit ISRC missed, spend the remaining budget (capped at
        // MAX_LYRICS_QUERY_STAGES total queries) on album-less and lead-artist fallbacks.
        val stages = if (attempts > 0) {
            lyricsQueryStages(title, artist, null)
        } else {
            lyricsQueryStages(title, artist, album)
        }

        val cleanWantedArtist = artist.artistForLyricsSearch()
        val seconds = durationMs / 1000
        for (stage in stages) {
            if (attempts >= MAX_LYRICS_QUERY_STAGES) break
            val discovered = isrcProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() && it !in triedIsrcs }
            if (discovered != null && attempts < MAX_LYRICS_QUERY_STAGES) {
                queryIsrc(discovered)?.let { return@withContext it }
                if (attempts >= MAX_LYRICS_QUERY_STAGES) break
            }
            attempts++
            val url = BASE.toHttpUrl().newBuilder()
                .addQueryParameter("track", stage.title)
                .addQueryParameter("artist", stage.artist)
                .apply {
                    if (!stage.album.isNullOrBlank()) addQueryParameter("album", stage.album)
                    if (seconds > 0) addQueryParameter("duration", seconds.toString())
                }
                .build()
            val results = queryHits(url.toString(), searchGet) ?: continue
            best(results, stage.title, cleanWantedArtist.ifBlank { stage.artist }, durationMs)
                ?.let { return@withContext it }
        }
        null
    }

    private suspend fun queryHits(
        url: String,
        searchGet: suspend (String) -> String?,
    ): List<Hit>? {
        // A miss may be a 404 (turned into null by [lyricsGet]) or a 200 with an empty list.
        val body = searchGet(url) ?: return null
        val response = runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull()
            ?: return null
        return response.results?.takeIf { it.isNotEmpty() }
    }

    /**
     * The search by name can put a different recording or a line-synced sibling
     * first. Filter candidates using the same recording checks and
     * [LrcRed.DURATION_TOLERANCE_SECONDS] window as [LrcRed.best], then prefer
     * word-synced (`timing_type == "word"`) entries before breaking ties by
     * closest duration.
     */
    fun best(results: List<Hit>, title: String, artist: String, durationMs: Long): Hit? =
        results.filter { hit ->
            LrcRed.matchesCandidate(
                candidateIsrc = hit.isrc,
                candidateTitle = hit.trackName,
                candidateArtist = hit.artistName,
                candidateDurationSeconds = hit.duration?.toDouble(),
                wantedTitle = title,
                wantedArtist = artist,
                wantedDurationMs = durationMs,
            )
        }.minWithOrNull(
            compareBy<Hit> { if (it.timingType.equals("word", ignoreCase = true)) 0 else 1 }
                .thenBy { LrcRed.durationDistanceSeconds(it.duration?.toDouble(), durationMs) },
        )

    /**
     * The document a search already found, fetched and parsed. These are
     * [LrcRed]'s own files now, so [LyricsRepository] passes a [get] that
     * shares the one download between the two.
     */
    suspend fun lyricsFor(
        hit: Hit,
        get: suspend (String) -> String? = { lyricsGet(it) },
    ): Match? = withContext(Dispatchers.IO) {
        val document = hit.lyricsUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        val ttml = get(document) ?: return@withContext null
        val lines = TtmlLyrics.parse(ttml).takeIf { it.isNotEmpty() } ?: return@withContext null
        Match(LrcRed.normalizeIsrc(hit.isrc) ?: hit.isrc?.takeIf { it.isNotBlank() }, lines)
    }

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        isrc: String? = null,
        get: suspend (String) -> String? = { lyricsGet(it) },
        isrcProvider: (() -> String?)? = null,
        onIsrc: ((String) -> Unit)? = null,
        searchGet: suspend (String) -> String? = { lyricsGet(it) },
    ): Match? {
        val hit = identify(title, artist, durationMs, album, isrc, isrcProvider, searchGet)
            ?: return null
        (LrcRed.normalizeIsrc(hit.isrc) ?: hit.isrc?.trim()?.takeIf { it.isNotEmpty() })
            ?.let { onIsrc?.invoke(it) }
        return lyricsFor(hit, get)
    }

    @Serializable
    data class Response(
        val total: Int? = null,
        /** How it matched — `HIT-EXACT` and friends. Logged, not acted on. */
        val source: String? = null,
        val results: List<Hit>? = null,
    )

    @Serializable
    data class Hit(
        @SerialName("track_name") val trackName: String? = null,
        @SerialName("artist_name") val artistName: String? = null,
        @SerialName("album_name") val albumName: String? = null,
        val duration: Int? = null,
        val isrc: String? = null,
        /** `word` or `line`; the document itself is the authority. */
        @SerialName("timing_type") val timingType: String? = null,
        val lyricsUrl: String? = null,
    )
}
