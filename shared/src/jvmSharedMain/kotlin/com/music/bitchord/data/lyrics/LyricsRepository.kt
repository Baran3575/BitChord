package com.music.bitchord.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Where the player gets its lyrics.
 *
 * Sources tried in [order] — the user's own priority list in Settings,
 * defaulting to [LyricsSource.offered]; a [hidden][LyricsSource.hidden] one is
 * never asked, whatever the saved settings say:
 *
 *  - [LrcRed] — Apple Music TTML filed by ISRC, and the catalogue
 *    [BiniLyrics] itself answers from.
 *  - [BetterLyrics], [PaxSenix] and [BiniLyrics] — Apple Music TTML,
 *    per-syllable, from three independent hosts so one having a bad day
 *    doesn't cost the timing.
 *  - [LyricsPlus] — the YouLy+ backend; finest timing of the lot, flakiest hosting.
 *  - [SimpMusicLyrics] — keyed on the video id, so it can't fetch the wrong edit.
 *  - [Unison] — contributed rather than licensed; thin, and occasionally the
 *    only place a track exists.
 *  - [LrcLib], [Musixmatch], [KuGou] — line-synced only, but between them
 *    almost always up, and [KuGou] carries a lot that the others don't.
 *
 * Every enabled source is asked *at the same time*, but their answers are
 * taken in [order]: the loop awaits them one at a time in that sequence, so a
 * lower-priority source finishing first never preempts one still pending
 * ahead of it. Asked one after another instead, a miss on each source would
 * cost its own round trip before the next was even tried, and a track with no
 * lyrics anywhere would spend the best part of a minute finding that out with
 * eight of them. Run together, a miss costs whatever the slowest one needed
 * to still be waited on took.
 *
 * A word-timed answer wins outright. Failing that, a line-timed one is taken
 * from the highest-priority source that had it — better a whole line lighting
 * up in sync than the right animation on lyrics that don't exist.
 *
 * ### Matching on the recording without blocking race start
 *
 * An ISRC names one recording and avoids single-vs-album edit mismatches.
 * Instead of delaying the whole provider race on a preliminary identify call,
 * all enabled sources launch immediately with any known or cached ISRC, while
 * [BiniLyrics], [LrcRed], [PaxSenix], and [Musixmatch] share and cache
 * discovered ISRCs non-blockingly for in-flight fallback stages and future
 * lookups of the same [videoId]. Disabled or hidden sources are never contacted.
 */
object LyricsRepository {

    /** Lyrics, and which source they turned out to come from. */
    data class Result(val source: LyricsSource, val lines: List<LyricLine>)

    internal data class SourceCall(
        val source: LyricsSource,
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
        val currentIsrc: () -> String?,
        val onIsrc: (String) -> Unit,
        val getDocument: suspend (String) -> String?,
    )

    /**
     * [sources] is the user's pick from Settings; anything not in it is not
     * contacted at all. An empty set means no lyrics, which is the same answer
     * as switching the feature off. [order] is tried first-to-last; a source
     * missing from it (an upgrade that added one after the order was last
     * saved) falls in after everything named, in [LyricsSource]'s own order.
     *
     * [prioritizeSyllableSync] decides what happens once *something* has come
     * back: on by default, a merely line-synced answer is kept only as a
     * fallback while the search keeps going through the rest of [order] for a
     * word-synced one, taking the top-priority source that has one. Off, the
     * highest-priority source's own answer is taken as-is.
     */
    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = true,
        isrc: String? = null,
        /** Called from the provider job itself, including for lazily-started providers. */
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        /**
         * Reports a completed provider attempt. A null result is a genuine miss;
         * a cancelled race loser is deliberately not reported as one.
         */
        onSourceResult: ((LyricsSource, Result?) -> Unit)? = null,
        /** Lets callers turn a cancelled race loser back into "not fetched". */
        onSourceCancelled: ((LyricsSource) -> Unit)? = null,
    ): Result? = lookup(
        videoId = videoId,
        title = title,
        artist = artist,
        durationMs = durationMs,
        album = album,
        sources = sources,
        order = order,
        prioritizeSyllableSync = prioritizeSyllableSync,
        isrc = isrc,
        onSourceStarted = onSourceStarted,
        onSourceResult = onSourceResult,
        onSourceCancelled = onSourceCancelled,
        fetchSource = ::fetch,
    )

    internal suspend fun lookup(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = true,
        isrc: String? = null,
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        onSourceResult: ((LyricsSource, Result?) -> Unit)? = null,
        onSourceCancelled: ((LyricsSource) -> Unit)? = null,
        fetchSource: suspend (SourceCall) -> List<LyricLine>?,
    ): Result? = coroutineScope {
        val sequence = (order + LyricsSource.offered)
            .distinct()
            .filter { it in sources && !it.hidden }

        // Every source but [SimpMusicLyrics] is asked for a name, and
        // YouTube's is not the name anyone catalogued. Cleaned once, here,
        // rather than by whichever source thought to do it for itself.
        val searchTitle = title.forLyricsSearch()
        val searchArtist = artist.artistForLyricsSearch()

        val initialIsrc = LrcRed.normalizeIsrc(isrc) ?: isrc?.trim()?.takeIf { it.isNotEmpty() }
        if (initialIsrc != null) remember(videoId, initialIsrc)
        val sharedIsrc = AtomicReference<String?>(initialIsrc ?: isrcs[videoId])
        val onIsrc: (String) -> Unit = { discovered ->
            val normalized = LrcRed.normalizeIsrc(discovered) ?: discovered.trim().takeIf { it.isNotEmpty() }
            if (normalized != null) {
                sharedIsrc.compareAndSet(null, normalized)
                remember(videoId, normalized)
            }
        }
        val currentIsrc: () -> String? = { sharedIsrc.get() ?: isrcs[videoId] }
        val documents = SharedDocuments(this)

        // Genius is a plain text web scraper. To preserve bandwidth and avoid rate-limiting,
        // it starts lazily and is only contacted if all higher-priority synced sources miss.
        val racing: List<Pair<LyricsSource, Deferred<Result?>>> = sequence.map { source ->
            val startMode = if (source == LyricsSource.GENIUS) {
                kotlinx.coroutines.CoroutineStart.LAZY
            } else {
                kotlinx.coroutines.CoroutineStart.DEFAULT
            }
            source to async(Dispatchers.IO, start = startMode) {
                onSourceStarted?.invoke(source)
                try {
                    val found = fetchSource(
                        SourceCall(
                            source = source,
                            videoId = videoId,
                            title = searchTitle,
                            artist = searchArtist,
                            durationMs = durationMs,
                            album = album,
                            currentIsrc = currentIsrc,
                            onIsrc = onIsrc,
                            getDocument = documents::get,
                        ),
                    )?.let { result(source, it) }
                    onSourceResult?.invoke(source, found)
                    found
                } catch (cancelled: CancellationException) {
                    onSourceCancelled?.invoke(source)
                    throw cancelled
                } catch (_: Exception) {
                    onSourceResult?.invoke(source, null)
                    null
                }
            }
        }

        try {
            pick(racing.map { (source, job) -> source to suspend { job.await() } }, prioritizeSyllableSync)
        } finally {
            // Whoever lost the race is no longer worth waiting on, and
            // coroutineScope will not return while they are still running.
            racing.forEach { it.second.cancel() }
            documents.cancel()
        }
    }

    /**
     * The first word-synced result in [racing] order; failing that, the first
     * line-synced one; failing that, the first plain one. Without
     * [prioritizeSyllableSync], the first timed result wins outright.
     *
     * Plain and line-synced are kept apart: a plain result that happened to
     * arrive first must not shadow a line-synced one further down the order.
     */
    internal suspend fun pick(
        racing: List<Pair<LyricsSource, suspend () -> Result?>>,
        prioritizeSyllableSync: Boolean,
    ): Result? {
        var lineSynced: Result? = null
        var plain: Result? = null
        for ((source, await) in racing) {
            // If we already found a line-synced or better result, skip Genius completely
            if ((lineSynced ?: plain) != null && source == LyricsSource.GENIUS) continue

            val found = runCatching { await() }.getOrNull() ?: continue
            if (found.lines.any { it.isWordSynced }) return found
            if (found.lines.any { it.timeMs > 0 }) {
                if (!prioritizeSyllableSync) return found
                if (lineSynced == null) lineSynced = found
            } else if (plain == null) {
                plain = found
            }
        }
        return lineSynced ?: plain
    }

    private suspend fun fetch(call: SourceCall): List<LyricLine>? {
        val found = when (call.source) {
            LyricsSource.LRC_RED -> LrcRed.lyrics(
                title = call.title,
                artist = call.artist,
                durationMs = call.durationMs,
                isrc = call.currentIsrc(),
                get = call.getDocument,
                isrcProvider = call.currentIsrc,
                onIsrc = call.onIsrc,
            )
            LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(call.title, call.artist, call.durationMs, call.album)
            LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portato(call.title, call.artist, call.durationMs, call.album)
            LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(
                title = call.title,
                artist = call.artist,
                durationMs = call.durationMs,
                album = call.album,
                isrc = call.currentIsrc(),
                isrcProvider = call.currentIsrc,
            )
            LyricsSource.BINI_LYRICS -> BiniLyrics.lyrics(
                title = call.title,
                artist = call.artist,
                durationMs = call.durationMs,
                album = call.album,
                isrc = call.currentIsrc(),
                get = call.getDocument,
                isrcProvider = call.currentIsrc,
                onIsrc = call.onIsrc,
            )?.lines
            LyricsSource.UNISON -> Unison.lyrics(call.title, call.artist, call.durationMs, call.album)
            LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(call.videoId, call.durationMs)
            LyricsSource.YOUTUBE_TRANSCRIPT -> YouTubeTranscriptLyrics.lyrics(call.videoId)
            LyricsSource.YOUTUBE_MUSIC -> YouTubeMusicLyrics.lyrics(call.videoId)
            LyricsSource.LRCLIB -> LrcLib.lyrics(call.title, call.artist, call.durationMs)
            LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(
                title = call.title,
                artist = call.artist,
                durationMs = call.durationMs,
                onIsrc = call.onIsrc,
            )
            LyricsSource.PAXSENIX -> PaxSenix.lyrics(
                title = call.title,
                artist = call.artist,
                durationMs = call.durationMs,
                album = call.album,
                onIsrc = call.onIsrc,
            )
            LyricsSource.PAXSENIX_SPOTIFY -> PaxSenix.spotifyLyrics(call.title, call.artist, call.durationMs)
            LyricsSource.PAXSENIX_MUSIXMATCH -> PaxSenix.musixmatchLyrics(call.title, call.artist, call.durationMs)
            LyricsSource.KUGOU -> KuGou.lyrics(call.title, call.artist, call.durationMs, call.album)
            LyricsSource.MEGALOBIZ -> Megalobiz.lyrics(call.title, call.artist)
            LyricsSource.GENIUS -> Genius.lyrics(call.title, call.artist)
        }
        // An instrumental's "pure music, please enjoy" is no lyrics, whoever relays it.
        return found?.takeUnless(KuGou::isInstrumentalNotice)
    }

    /**
     * Whichever source won, its lines get the same last pass: the answering
     * vocal split off the lead so it can be drawn under it. Done here rather
     * than in each parser because most of them write it as a bracket and only
     * [TtmlLyrics] knows it structurally — [withBackgroundVocals] leaves that
     * one's own split alone.
     */
    private fun result(source: LyricsSource, lines: List<LyricLine>) =
        Result(source, lines.withBackgroundVocals())

    /** How many recordings to keep in hand; see [isrcs]. */
    private const val REMEMBERED = 100

    /**
     * The recording behind a video id, once something has worked it out.
     *
     * Bounded, least-recently-used, and in memory only. This is a shortcut,
     * not a store: losing it costs one fuzzy match, which is what every lookup
     * did before any of this, and keeping it on disk would mean keeping a
     * wrong answer on disk too.
     */
    private val isrcs: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(REMEMBERED, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > REMEMBERED
        },
    )

    internal fun rememberedIsrc(videoId: String): String? = isrcs[videoId]

    internal fun clearRememberedIsrcsForTests() {
        isrcs.clear()
    }

    private fun remember(videoId: String, isrc: String?) {
        if (isrc.isNullOrBlank() || videoId.isEmpty()) return
        isrcs.put(videoId, isrc)
    }

    /**
     * Documents fetched during one lookup, by URL, so two sources after the
     * same file make one request between them. That is the ordinary case, not
     * an edge: [BiniLyrics]' search answers with an [LrcRed] URL, and [LrcRed],
     * handed the ISRC that search found, asks for exactly that URL too.
     *
     * Each download runs in the lookup's own scope rather than the asking
     * source's, so the source that started it losing the race doesn't take
     * the other one's copy down with it; [cancel] ends whatever is left.
     */
    private class SharedDocuments(private val scope: CoroutineScope) {
        private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()

        suspend fun get(url: String): String? =
            inFlight.computeIfAbsent(url) { scope.async(Dispatchers.IO) { lyricsGet(url) } }.await()

        fun cancel() = inFlight.values.forEach { it.cancel() }
    }
}
