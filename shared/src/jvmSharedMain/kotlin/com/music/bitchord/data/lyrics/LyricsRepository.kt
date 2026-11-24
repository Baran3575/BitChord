package com.music.bitchord.data.lyrics

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.VersionTimeMap
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

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
 * ### Matching on the recording
 *
 * Everything above matches a *name*, and a name is ambiguous in a way that
 * matters: a single and its album cut share a title, an artist and very nearly
 * a length, and routinely differ in the words. An ISRC names one recording and
 * settles it. Two sources accept one — [BiniLyrics] and [LyricsPlus] — and
 * [BiniLyrics] is also the one that hands them out, reporting the ISRC of
 * whatever its own search matched.
 *
 * So the recording is settled *before* anybody is asked for words: one small
 * search against [BiniLyrics], whose answer is a name every other source can
 * use. It costs a round trip at the head of the lookup, which is why it is
 * capped at [IDENTIFY_TIMEOUT_MS] and why its result is kept against the studio
 * video id — a track asked about twice pays for this once. A downloaded or local
 * file can skip it entirely: its own tags name the recording, which is what the
 * [isrc] parameter is for.
 *
 * Only run when the user has [LyricsSource.BINI_LYRICS] enabled. It is a
 * request to a third party like any other, and a source somebody has turned off
 * is a source this app does not contact — not even for something it would only
 * use to help the sources they left on.
 */
object LyricsRepository {

    /** Lyrics, and which source they turned out to come from. */
    data class Result(val source: LyricsSource, val lines: List<LyricLine>)

    /** How studio timestamps are projected onto a playing music video's timeline. */
    internal sealed interface StudioTimeline {
        data class Segmented(val timeMap: VersionTimeMap) : StudioTimeline
        data class Offset(val offsetMs: Long) : StudioTimeline
    }

    internal data class Overrides(
        val counterpartResolver: (suspend (Song) -> Song?)? = null,
        val timeMapLookup: ((fromId: String, toId: String) -> VersionTimeMap?)? = null,
        val providerFetcher: (suspend (
            source: LyricsSource,
            videoId: String,
            title: String,
            artist: String,
            durationMs: Long,
            album: String?,
            isrc: String?,
            isVideo: Boolean,
        ) -> List<LyricLine>?)? = null,
        val identifyOverride: (suspend (
            title: String,
            artist: String,
            durationMs: Long,
            album: String?,
        ) -> BiniLyrics.Hit?)? = null,
    )

    /**
     * [sources] is the user's pick from Settings; anything not in it is not
     * contacted at all. An empty set means no lyrics, which is the same answer
     * as switching the feature off. [order] is tried first-to-last; a source
     * missing from it (an upgrade that added one after the order was last
     * saved) falls in after everything named, in [LyricsSource]'s own order.
     *
     * [prioritizeSyllableSync] decides what happens once *something* has come
     * back: off, the highest-priority source's own answer is taken as-is,
     * word-synced or not — priority is priority, and second-guessing it with
     * more network calls after it has already answered is not what "first"
     * was supposed to mean. On, a merely line-synced answer is kept only as a
     * fallback, and the search keeps going through the rest of [order] for a
     * word-synced one, taking the top-priority source that has one.
     */
    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = false,
        isrc: String? = null,
        isVideo: Boolean = false,
        alignmentResolver: (suspend (studioSong: Song, videoSong: Song) -> Long?)? = null,
        /** Called from the provider job itself, including for lazily-started providers. */
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        /**
         * Reports a completed provider attempt. A null result is a genuine miss;
         * a cancelled race loser is deliberately not reported as one.
         */
        onSourceResult: ((LyricsSource, Result?) -> Unit)? = null,
        /** Lets callers turn a cancelled race loser back into "not fetched". */
        onSourceCancelled: ((LyricsSource) -> Unit)? = null,
    ): Result? = lyricsInternal(
        videoId = videoId,
        title = title,
        artist = artist,
        durationMs = durationMs,
        album = album,
        sources = sources,
        order = order,
        prioritizeSyllableSync = prioritizeSyllableSync,
        isrc = isrc,
        isVideo = isVideo,
        alignmentResolver = alignmentResolver,
        onSourceStarted = onSourceStarted,
        onSourceResult = onSourceResult,
        onSourceCancelled = onSourceCancelled,
        overrides = null,
    )

    internal suspend fun lyricsInternal(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = false,
        isrc: String? = null,
        isVideo: Boolean = false,
        alignmentResolver: (suspend (studioSong: Song, videoSong: Song) -> Long?)? = null,
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        onSourceResult: ((LyricsSource, Result?) -> Unit)? = null,
        onSourceCancelled: ((LyricsSource) -> Unit)? = null,
        overrides: Overrides? = null,
    ): Result? = coroutineScope {
        val requestedSequence = (order + LyricsSource.offered)
            .distinct()
            .filter { it in sources && !it.hidden }

        // Without a known positive duration, studio catalogues cannot validate
        // which edit is playing: skip studio sources and touch nothing in [isrcs].
        val canQueryStudio = durationMs > 0L
        val sequence = if (canQueryStudio) {
            requestedSequence
        } else {
            requestedSequence.filterNot { it.isStudio }
        }
        if (sequence.isEmpty()) return@coroutineScope null

        val hasStudioSource = sequence.any { it.isStudio }
        val resolution = if (canQueryStudio && isVideo && hasStudioSource) {
            resolveVideoCounterpart(
                videoId = videoId,
                title = title,
                artist = artist,
                durationMs = durationMs,
                album = album,
                alignmentResolver = alignmentResolver,
                overrides = overrides,
            )
        } else {
            StudioResolution(
                studioSong = null,
                timeline = null,
                studioDurationMs = durationMs,
                isrcKey = if (!isVideo && canQueryStudio) videoId else null,
            )
        }

        val effectiveSequence = if (resolution.studioDurationMs > 0L) {
            sequence
        } else {
            sequence.filterNot { it.isStudio }
        }
        if (effectiveSequence.isEmpty()) return@coroutineScope null

        // Every source but [SimpMusicLyrics] is asked for a name, and
        // YouTube's is not the name anyone catalogued. Cleaned once, here,
        // rather than by whichever source thought to do it for itself.
        val rawStudioTitle = resolution.studioSong?.takeIf { resolution.timeline != null }?.title
            ?.takeIf { it.isNotBlank() } ?: title
        val rawStudioArtist = resolution.studioSong?.takeIf { resolution.timeline != null }?.artist
            ?.takeIf { it.isNotBlank() } ?: artist
        val studioAlbum = resolution.studioSong?.takeIf { resolution.timeline != null }?.albumName
            ?.takeIf { it.isNotBlank() } ?: album
        val searchTitle = rawStudioTitle.forLyricsSearch()
        val searchArtist = rawStudioArtist.artistForLyricsSearch()

        // Settled before anyone is asked for words, so every source that can
        // name the recording does. Keyed strictly to the studio video id so a
        // studio ISRC never overwrites a music video's id.
        val studioDurationMs = resolution.studioDurationMs
        val isrcKey = resolution.isrcKey
        val known = if (canQueryStudio && studioDurationMs > 0L) {
            isrc?.takeIf { it.isNotBlank() } ?: isrcKey?.let { isrcs[it] }
        } else {
            null
        }
        val hit = if (canQueryStudio && studioDurationMs > 0L && known == null) {
            identify(
                isrcKey = isrcKey,
                title = searchTitle,
                artist = searchArtist,
                durationMs = studioDurationMs,
                album = studioAlbum,
                sequence = effectiveSequence,
                overrides = overrides,
            )
        } else {
            null
        }
        val recording = known ?: hit?.isrc?.takeIf { it.isNotBlank() }
        val documents = SharedDocuments(this)
        val unalignedVideo = isVideo && resolution.timeline == null

        // Genius is a plain text web scraper. To preserve bandwidth and avoid rate-limiting,
        // it starts lazily and is only contacted if all higher-priority synced sources miss.
        val racing: List<Pair<LyricsSource, Deferred<Result?>>> = effectiveSequence.map { source ->
            val startMode = if (source == LyricsSource.GENIUS) {
                kotlinx.coroutines.CoroutineStart.LAZY
            } else {
                kotlinx.coroutines.CoroutineStart.DEFAULT
            }
            source to async(Dispatchers.IO, start = startMode) {
                onSourceStarted?.invoke(source)
                try {
                    val providerVideoId = if (source.isStudio) {
                        resolution.studioSong?.videoId ?: videoId
                    } else {
                        videoId
                    }
                    val providerDurationMs = if (source.isStudio) studioDurationMs else durationMs
                    val found = fetch(
                        source = source,
                        videoId = providerVideoId,
                        isrcKey = isrcKey,
                        title = searchTitle,
                        artist = searchArtist,
                        durationMs = providerDurationMs,
                        album = if (source.isStudio) studioAlbum else album,
                        isrc = recording,
                        isVideo = if (source.isStudio) unalignedVideo else isVideo,
                        hit = hit,
                        get = documents::get,
                        overrides = overrides,
                    )?.let { result(source, it, resolution.timeline) }
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
            pick(
                racing = racing.map { (source, job) -> source to suspend { job.await() } },
                prioritizeSyllableSync = prioritizeSyllableSync,
                preferVideoFallback = unalignedVideo,
            )
        } finally {
            // Whoever lost the race is no longer worth waiting on, and
            // coroutineScope will not return while they are still running.
            racing.forEach { it.second.cancel() }
            documents.cancel()
        }
    }

    private data class StudioResolution(
        val studioSong: Song?,
        val timeline: StudioTimeline?,
        val studioDurationMs: Long,
        val isrcKey: String?,
    )

    private suspend fun resolveVideoCounterpart(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        alignmentResolver: (suspend (studioSong: Song, videoSong: Song) -> Long?)?,
        overrides: Overrides?,
    ): StudioResolution {
        val videoSong = Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = null,
            albumName = album,
            durationText = formatSecondsText(durationMs),
            isVideo = true,
        )
        val counterpart = runCatching {
            val resolver = overrides?.counterpartResolver
            if (resolver != null) resolver(videoSong) else YtMusicRepository.resolveAudio(videoSong)
        }.getOrNull()?.takeIf { it.videoId.isNotBlank() && it.videoId != videoId && !it.isVideo }
            ?: return StudioResolution(
                studioSong = null,
                timeline = null,
                studioDurationMs = durationMs,
                isrcKey = null,
            )

        val studioId = counterpart.videoId
        val timeMap = (overrides?.timeMapLookup ?: YtMusicRepository::versionTimeMap)
            .invoke(studioId, videoId)
        if (timeMap != null) {
            val studioDuration = counterpart.durationMillis().takeIf { it > 0L }
                ?: timeMap.fromDurationMs().takeIf { it > 0L }
                ?: 0L
            return StudioResolution(
                studioSong = counterpart,
                timeline = StudioTimeline.Segmented(timeMap),
                studioDurationMs = studioDuration,
                isrcKey = studioId,
            )
        }

        val offsetMs = runCatching { alignmentResolver?.invoke(counterpart, videoSong) }.getOrNull()
        if (offsetMs != null) {
            val studioDuration = counterpart.durationMillis().takeIf { it > 0L }
                ?: (durationMs - offsetMs).takeIf { it > 0L }
                ?: 0L
            return StudioResolution(
                studioSong = counterpart,
                timeline = StudioTimeline.Offset(offsetMs),
                studioDurationMs = studioDuration,
                isrcKey = studioId,
            )
        }

        val counterpartDuration = counterpart.durationMillis()
        val sameLengthAsStudio = counterpartDuration > 0L &&
            abs(counterpartDuration - durationMs) <= STUDIO_DURATION_TOLERANCE_MS
        return StudioResolution(
            studioSong = counterpart,
            timeline = null,
            studioDurationMs = durationMs,
            isrcKey = studioId.takeIf { sameLengthAsStudio },
        )
    }

    private fun formatSecondsText(durationMs: Long): String? {
        if (durationMs <= 0L) return null
        val totalSeconds = durationMs / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "$minutes:${seconds.toString().padStart(2, '0')}"
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
        preferVideoFallback: Boolean = false,
    ): Result? {
        var lineSynced: Result? = null
        var plain: Result? = null
        for ((source, await) in racing) {
            // If we already found a line-synced or better result, skip Genius completely;
            // on unaligned videos, let video-keyed YouTube sources run before Genius.
            if (source == LyricsSource.GENIUS && (lineSynced ?: plain) != null) continue
            if (preferVideoFallback && source == LyricsSource.GENIUS) continue

            val found = runCatching { await() }.getOrNull() ?: continue
            if (found.lines.any { it.isWordSynced }) return found
            if (found.lines.any { it.timeMs > 0 }) {
                if (!prioritizeSyllableSync) return found
                if (lineSynced == null) lineSynced = found
            } else if (plain == null || (preferVideoFallback && source.isVideoKeyed && !plain.source.isVideoKeyed)) {
                plain = found
            }
        }
        if (lineSynced != null || plain != null) return lineSynced ?: plain
        if (preferVideoFallback) {
            for ((source, await) in racing) {
                if (source != LyricsSource.GENIUS) continue
                val found = runCatching { await() }.getOrNull() ?: continue
                return found
            }
        }
        return null
    }

    private suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        isrcKey: String?,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,
        isVideo: Boolean,
        /** What [identify] already found, where it ran; saves a second search. */
        hit: BiniLyrics.Hit?,
        /** Fetches a document once per lookup, however many sources want it. */
        get: suspend (String) -> String?,
        overrides: Overrides? = null,
    ): List<LyricLine>? {
        if (source.isStudio && durationMs <= 0L) return null
        val customFetcher = overrides?.providerFetcher
        val found = if (customFetcher != null) {
            customFetcher(source, videoId, title, artist, durationMs, album, isrc, isVideo)
        } else {
            when (source) {
                LyricsSource.LRC_RED -> LrcRed.lyrics(title, artist, durationMs, isrc, get)
                LyricsSource.BETTER_LYRICS -> BetterLyrics.document(title, artist, durationMs, album)
                    ?.takeIf { ProviderLyrics.matchesStudioDuration(it, durationMs) }
                    ?.lines
                LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portatoDocument(title, artist, durationMs, album)
                    ?.takeIf { ProviderLyrics.matchesStudioDuration(it, durationMs) }
                    ?.lines
                LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album, isrc)
                LyricsSource.BINI_LYRICS ->
                    (hit?.let { BiniLyrics.lyricsFor(it, durationMs, get) }
                        ?: BiniLyrics.lyrics(title, artist, durationMs, album, isrc, get))
                        ?.also { remember(isrcKey, it.isrc) }
                        ?.lines
                LyricsSource.UNISON -> Unison.entry(title, artist, durationMs, album)
                    ?.let { Unison.matchingLinesOf(it, durationMs, videoId, isVideo) }
                LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(videoId, durationMs)
                LyricsSource.YOUTUBE_TRANSCRIPT -> YouTubeTranscriptLyrics.lyrics(videoId)
                LyricsSource.YOUTUBE_MUSIC -> YouTubeMusicLyrics.lyrics(videoId)
                LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs)
                LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(title, artist, durationMs)
                LyricsSource.PAXSENIX -> PaxSenix.lyrics(title, artist, durationMs, album)
                LyricsSource.PAXSENIX_SPOTIFY -> PaxSenix.spotifyLyrics(title, artist, durationMs)
                LyricsSource.PAXSENIX_MUSIXMATCH -> PaxSenix.musixmatchLyrics(title, artist, durationMs)
                LyricsSource.KUGOU -> KuGou.lyrics(title, artist, durationMs, album)
                LyricsSource.MEGALOBIZ -> Megalobiz.lyrics(title, artist)
                LyricsSource.GENIUS -> Genius.lyrics(title, artist)
            }
        }
        // An instrumental's "pure music, please enjoy" is no lyrics, whoever relays it.
        return found?.takeUnless(KuGou::isInstrumentalNotice)
    }

    /**
     * Whichever source won, its lines get the same last pass: the answering
     * vocal split off the lead so it can be drawn under it, followed by
     * remapping onto the playing video's timeline (and rebuilding gaps) when a
     * studio source was paired against a music video.
     */
    private fun result(
        source: LyricsSource,
        lines: List<LyricLine>,
        studioTimeline: StudioTimeline?,
    ): Result {
        val withBacking = lines.withBackgroundVocals()
        val mapped = if (source.isStudio && studioTimeline != null) {
            when (studioTimeline) {
                is StudioTimeline.Segmented -> withBacking.remapped(studioTimeline.timeMap)
                is StudioTimeline.Offset -> withBacking.shifted(studioTimeline.offsetMs)
            }
        } else {
            withBacking
        }
        return Result(source, mapped)
    }

    /**
     * Longest the lookup will wait to find out which recording this is.
     *
     * Short on purpose. Knowing the recording makes every match better, but not
     * knowing it only puts things back where they were a release ago, and a
     * lyrics panel sitting empty because one host is having a slow morning is a
     * worse failure than a fuzzy match.
     */
    private const val IDENTIFY_TIMEOUT_MS = 2_500L

    /**
     * The one request made before the race: which recording is this?
     *
     * Skipped entirely when the source that answers it is switched off, and
     * given up on rather than waited out — see [IDENTIFY_TIMEOUT_MS]. What it
     * finds is remembered against the studio id, so a track pays for this once
     * rather than once per lookup, and the hit is handed back so [BiniLyrics]
     * need not search twice.
     */
    private suspend fun identify(
        isrcKey: String?,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sequence: List<LyricsSource>,
        overrides: Overrides? = null,
    ): BiniLyrics.Hit? {
        if (durationMs <= 0L) return null
        if (LyricsSource.BINI_LYRICS !in sequence) return null
        val hit = withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
            runCatching {
                val custom = overrides?.identifyOverride
                if (custom != null) {
                    custom(title, artist, durationMs, album)
                } else {
                    BiniLyrics.identify(title, artist, durationMs, album)
                }
            }.getOrNull()
        }
        if (hit == null) return null
        remember(isrcKey, hit.isrc)
        return hit
    }

    /** How many recordings to keep in hand; see [isrcs]. */
    private const val REMEMBERED = 100

    /**
     * The recording behind a studio video id, once something has worked it out.
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

    private fun remember(studioVideoId: String?, isrc: String?) {
        if (isrc.isNullOrBlank() || studioVideoId.isNullOrEmpty()) return
        isrcs.put(studioVideoId, isrc)
    }

    internal fun cachedIsrcForTest(videoId: String): String? = isrcs[videoId]

    internal fun rememberIsrcForTest(videoId: String, isrc: String) {
        remember(videoId, isrc)
    }

    internal fun clearIsrcsForTest() {
        isrcs.clear()
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
