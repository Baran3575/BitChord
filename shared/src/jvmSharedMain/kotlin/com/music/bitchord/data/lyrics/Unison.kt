package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A community-submitted lyrics database, and the only source here whose
 * contents are contributed rather than licensed.
 *
 * That is its whole character. Every other source is a window onto somebody's
 * catalogue — Apple's, Musixmatch's, KuGou's — and carries whatever that
 * catalogue has. This one carries what people have uploaded, which means it
 * occasionally has a track none of the others do, and means its coverage is
 * thin and uneven everywhere else. It sits low in the default order for
 * exactly that reason.
 *
 * Each entry is voted on, and the response says so: a `score`, a `voteCount`
 * and a `confidence`. None of it is acted on here. A fresh submission is
 * `confidence: "low"` with no votes at all, so refusing those would refuse
 * nearly the whole database, and the alternative to a lightly-voted entry is
 * usually no lyrics rather than better ones.
 *
 * Three shapes come back, and it says which: Apple-style TTML, ordinary
 * line-stamped LRC, or plain text with no timing at all.
 */
object Unison {

    private const val BASE = "https://unison.boidu.dev/lyrics"

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        videoId: String? = null,
        isVideo: Boolean = false,
    ): List<LyricLine>? = entry(title, artist, durationMs, album)
        ?.let { matchingLinesOf(it, durationMs, videoId, isVideo) }

    internal suspend fun entry(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): Entry? = withContext(Dispatchers.IO) {
        if (durationMs <= 0L) return@withContext null
        val url = BASE.toHttpUrl().newBuilder()
            .addQueryParameter("song", title)
            .addQueryParameter("artist", artist)
            .apply {
                if (!album.isNullOrBlank()) addQueryParameter("album", album)
                val seconds = durationMs / 1000
                if (seconds > 0) addQueryParameter("duration", seconds.toString())
            }
            .build()

        val body = lyricsGet(url.toString()) ?: return@withContext null
        val response = runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull()
            ?: return@withContext null
        if (response.success != true) return@withContext null
        response.data
    }

    internal fun matchingLinesOf(
        data: Entry,
        durationMs: Long,
        videoId: String? = null,
        isVideo: Boolean = false,
    ): List<LyricLine>? {
        if (durationMs <= 0L) return null
        val lines = linesOf(data) ?: return null
        val explicitMs = explicitDurationMs(data)
        if (explicitMs != null) {
            return lines.takeIf { abs(explicitMs - durationMs) <= STUDIO_DURATION_TOLERANCE_MS }
        }
        val vocalLines = lines.filterNot { it.isGap }
        val isTimed = vocalLines.any { it.timeMs > 0L || it.endMs > 0L }
        if (!isTimed) {
            return if (isVideo && (videoId.isNullOrBlank() || data.videoId != videoId)) null else lines
        }
        if (!isVideo && !videoId.isNullOrBlank() && data.videoId == videoId) {
            val spanMs = vocalLines.maxOfOrNull { maxOf(it.timeMs, it.endMs) } ?: 0L
            return lines.takeIf { spanMs in 1L..(durationMs + STUDIO_DURATION_TOLERANCE_MS) }
        }
        return lines.takeIf { ProviderLyrics.matchesStudioDuration(lines, null, durationMs) }
    }

    internal fun explicitDurationMs(data: Entry): Long? {
        data.duration?.takeIf { it > 0.0 }?.let { raw ->
            return if (raw < 1_000.0) (raw * 1_000.0).roundToLong() else raw.roundToLong()
        }
        val text = data.lyrics?.takeIf { it.isNotBlank() } ?: return null
        return if (data.format.equals(TTML, ignoreCase = true)) {
            TtmlLyrics.parseDocument(text).durationMs
        } else {
            ProviderLyrics.lrcHeaderDurationMs(text)
        }
    }

    /** The three shapes, told apart by what the entry says it is. */
    fun linesOf(data: Entry): List<LyricLine>? {
        val text = data.lyrics?.takeIf { it.isNotBlank() } ?: return null
        val lines = when {
            data.format.equals(TTML, ignoreCase = true) -> TtmlLyrics.parse(text)
            data.syncType.equals(PLAIN, ignoreCase = true) -> plain(text)
            // Word stamps first: the format field says only "lrc", and an
            // enhanced file is still one. [EnhancedLrc] comes back empty when
            // there are no word stamps in it, which is the signal to fall
            // through rather than a failure.
            else -> EnhancedLrc.parse(text).takeIf { it.isNotEmpty() } ?: LrcLib.parseLrc(text)
        }
        return lines.takeIf { it.isNotEmpty() }
    }

    /**
     * Lyrics with no timing: one line each, all stamped zero, which is how
     * every unsynced source here states the same thing — the panel reads it as
     * words to be scrolled by hand rather than followed.
     */
    private fun plain(text: String): List<LyricLine> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { LyricLine(timeMs = 0, text = it) }
        .toList()

    private const val TTML = "ttml"
    private const val PLAIN = "plain"

    @Serializable
    data class Response(
        val success: Boolean? = null,
        val data: Entry? = null,
    )

    @Serializable
    data class Entry(
        val videoId: String? = null,
        val song: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val duration: Double? = null,
        val lyrics: String? = null,
        /** `ttml` or `lrc`. */
        val format: String? = null,
        /** `wordsync`, `linesync` or `plain`. */
        val syncType: String? = null,
        val confidence: String? = null,
        val voteCount: Int? = null,
    )
}
