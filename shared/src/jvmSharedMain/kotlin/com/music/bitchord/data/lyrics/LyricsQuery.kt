package com.music.bitchord.data.lyrics

import com.music.bitchord.data.sources.TrackMatcher

/** Maximum number of staged queries any single provider may attempt per lookup. */
const val MAX_LYRICS_QUERY_STAGES = 3

/** One query attempt in a provider's staged fallback sequence. */
data class LyricsQueryStage(
    val title: String,
    val artist: String,
    val album: String? = null,
)

/**
 * A YouTube title, as a lyrics database would have indexed it.
 *
 * Every source here but [SimpMusicLyrics] is asked for a name, and the name
 * this app has is YouTube's, which is not the name anyone catalogued. "Dracula
 * (feat. JENNIE)" is filed by Apple, LRCLIB and everyone else as "Dracula", and
 * asked for verbatim it misses all of them — which is exactly how a track ends
 * up answered by whichever source happened to clean the title on its own
 * ([PaxSenix] was the only one that did) regardless of the order the user put
 * their sources in.
 *
 * ### What is taken off, and what is deliberately left on
 *
 * Only credits and packaging: who else is on the record, and how the upload was
 * labelled. Those are not part of the recording's name anywhere.
 *
 * Anything that names a *different recording* stays — "(Remix)", "(Acoustic)",
 * "(Live)", "(Remastered 2011)", "(Sped Up)". Those look like the same kind of
 * bracket and are the opposite: stripping them turns a search for one recording
 * into a search for another, and the lyrics that come back are confidently
 * wrong rather than merely missing. A miss is recoverable; the wrong words
 * scrolling in time with the right song is not.
 */
fun String.forLyricsSearch(): String {
    var name = this
    CREDITS.forEach { pattern -> name = pattern.replace(name, " ") }
    return name.replace(WHITESPACE, " ").trim().trimEnd(',', '-', '–', '—').trim()
        // A title that was *only* packaging is no title at all; better to ask
        // with what we were given than with nothing.
        .ifBlank { trim() }
}

/**
 * Trims " - Topic" off an auto-generated channel name.
 *
 * YouTube's own artist channels for licensed music are named this way, and it
 * reaches the player as the artist on anything played from one.
 */
fun String.artistForLyricsSearch(): String =
    removeSuffix(" - Topic").trim().ifBlank { trim() }

/**
 * Derives the lead artist for fallback queries by trimming channel suffixes via
 * [artistForLyricsSearch], splitting on collaboration separators, and validating
 * against [TrackMatcher.primaryArtist] so YouTube type labels ("Música", "Video")
 * are never treated as artist names while display casing is preserved.
 */
fun String.leadArtistForLyricsSearch(): String {
    val cleaned = artistForLyricsSearch()
    if (cleaned.isBlank()) return ""
    return cleaned.split(LEAD_ARTIST_SEPARATORS)
        .asSequence()
        .map { it.artistForLyricsSearch() }
        .firstOrNull { it.isNotEmpty() && TrackMatcher.primaryArtist(it).isNotEmpty() }
        .orEmpty()
}

/**
 * Ordered query stages for providers whose strict matching can 404 when album
 * metadata disagrees (singles vs albums, untagged folder names) or when guest
 * artists are appended to the byline:
 *  1. `(cleanTitle, cleanArtist, cleanAlbum)` when an album is present
 *  2. `(cleanTitle, cleanArtist, null)` without album
 *  3. `(cleanTitle, leadArtist, null)` with only the primary artist
 *
 * Recording version words (`Remix`, `Live`, `Acoustic`, etc.) remain on
 * `cleanTitle` across every stage, and total stages are capped at
 * [MAX_LYRICS_QUERY_STAGES].
 */
fun lyricsQueryStages(
    title: String,
    artist: String,
    album: String? = null,
): List<LyricsQueryStage> {
    val cleanTitle = title.forLyricsSearch()
    val cleanArtist = artist.artistForLyricsSearch()
    val cleanAlbum = album?.trim()?.takeIf { it.isNotEmpty() }
    val leadArtist = artist.leadArtistForLyricsSearch()

    return buildList {
        if (cleanAlbum != null) {
            add(LyricsQueryStage(cleanTitle, cleanArtist, cleanAlbum))
        }
        add(LyricsQueryStage(cleanTitle, cleanArtist, null))
        if (leadArtist.isNotEmpty() && !leadArtist.equals(cleanArtist, ignoreCase = true)) {
            add(LyricsQueryStage(cleanTitle, leadArtist, null))
        }
    }.distinct().take(MAX_LYRICS_QUERY_STAGES)
}

private val WHITESPACE = Regex("""\s+""")

private val LEAD_ARTIST_SEPARATORS = Regex(
    """\s*(?:[,&/;·|、，×]|\band\b|\bx\b|\bvs\.?\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""",
    RegexOption.IGNORE_CASE,
)

private val CREDITS = listOf(
    // Bracketed credits: (feat. X), [ft. X], (with X).
    Regex("""\s*[(\[]\s*(feat|ft|featuring|with)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
    // The same, unbracketed and running to the end of the title.
    Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE),
    // How the upload was labelled, not what was recorded.
    Regex(
        """\s*[(\[]\s*(official\s*)?(music\s*)?""" +
            """(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)""" +
            """\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    ),
    Regex("""\s*[(\[]\s*official\s*[)\]]""", RegexOption.IGNORE_CASE),
)
