package com.music.bitchord.data.lyrics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

/** Strict ±3.5s tolerance shared by every studio lyric source. */
internal const val STUDIO_DURATION_TOLERANCE_MS = 3_500L

/** Turns the wire formats used by the smaller providers into BitChord lyrics. */
object ProviderLyrics {

    data class Document(
        val lines: List<LyricLine>,
        val durationMs: Long? = null,
    )

    fun parse(raw: String): List<LyricLine>? = parseDocument(raw)?.lines

    fun parseDocument(raw: String): Document? {
        val cleaned = stripCodeFence(raw) ?: return null
        val json = runCatching { lyricsJson.parseToJsonElement(cleaned) }.getOrNull()
        val wrapperDurationMs = json?.let(::extractDurationMs)
        val unwrapped = if (json != null) {
            extract(json)?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            cleaned
        } ?: return null
        val content = unescapeTtml(unwrapped)
        val (lines, contentDurationMs) = when {
            content.contains(Regex("""<tt(?:\s|>)""", RegexOption.IGNORE_CASE)) ||
                content.contains("http://www.w3.org/ns/ttml", ignoreCase = true) -> {
                val doc = TtmlLyrics.parseDocument(content)
                doc.lines to doc.durationMs
            }
            KaraokeLrc.looksLike(content) -> KaraokeLrc.parse(content) to lrcHeaderDurationMs(content)
            content.trimStart().startsWith("<") -> emptyList<LyricLine>() to null
            else -> {
                val parsed = EnhancedLrc.parse(content)
                    .ifEmpty { LrcLib.parseLrc(content) }
                    .ifEmpty { plain(content) }
                parsed to lrcHeaderDurationMs(content)
            }
        }
        val usable = lines.takeIf { found -> found.any { it.text.isNotBlank() } } ?: return null
        return Document(lines = usable, durationMs = contentDurationMs ?: wrapperDurationMs)
    }

    internal fun matchesStudioDuration(
        document: Document,
        wantedDurationMs: Long,
    ): Boolean = matchesStudioDuration(document.lines, document.durationMs, wantedDurationMs)

    internal fun matchesStudioDuration(
        lines: List<LyricLine>,
        explicitDurationMs: Long?,
        wantedDurationMs: Long,
    ): Boolean {
        if (wantedDurationMs <= 0L || lines.isEmpty()) return false
        if (explicitDurationMs != null && explicitDurationMs > 0L) {
            return abs(explicitDurationMs - wantedDurationMs) <= STUDIO_DURATION_TOLERANCE_MS
        }
        val spanMs = lines.filterNot { it.isGap }.maxOfOrNull { maxOf(it.timeMs, it.endMs) } ?: 0L
        return spanMs > 0L && abs(spanMs - wantedDurationMs) <= STUDIO_DURATION_TOLERANCE_MS
    }

    internal fun parseFlexibleDurationMs(rawValue: String?): Long? {
        val raw = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (':' in raw || raw.endsWith("ms", ignoreCase = true) || raw.endsWith("s", ignoreCase = true)) {
            return TtmlLyrics.time(raw.lowercase())?.takeIf { it > 0L }
        }
        val numeric = raw.toDoubleOrNull() ?: return null
        if (numeric <= 0.0) return null
        return if ('.' in raw || numeric < 1_000.0) {
            (numeric * 1_000.0).roundToLong()
        } else {
            numeric.roundToLong()
        }
    }

    internal fun lrcHeaderDurationMs(content: String): Long? {
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            TOTAL_TAG.matchEntire(trimmed)?.groupValues?.get(1)?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { return it }
            LENGTH_TAG.matchEntire(trimmed)?.groupValues?.get(1)?.let(::parseFlexibleDurationMs)
                ?.takeIf { it > 0L }
                ?.let { return it }
        }
        return null
    }

    private fun plain(content: String): List<LyricLine> {
        if (content.contains(Regex("""(?i)\b(?:lyrics? (?:not found|unavailable)|error)\b"""))) return emptyList()
        return content.lineSequence().map(String::trim).filter(String::isNotEmpty)
            .filterNot { it.matches(Regex("""\[[A-Za-z]+:.*]""")) }
            .map { LyricLine(0L, it) }.toList()
    }

    private fun stripCodeFence(raw: String): String? {
        var value = raw.replace("\uFEFF", "").trim()
        if (value.startsWith("```")) {
            value = value.lineSequence().drop(1).toList()
                .let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }
                .joinToString("\n").trim()
        }
        return value.takeIf { it.isNotBlank() }
    }

    /** Providers sometimes wrap the same lyric string in one or two JSON envelopes. */
    internal fun unwrap(raw: String): String? {
        val value = stripCodeFence(raw) ?: return null
        val json = runCatching { lyricsJson.parseToJsonElement(value) }.getOrNull() ?: return value
        return extract(json)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractDurationMs(element: JsonElement): Long? = when (element) {
        JsonNull, is JsonArray -> null
        is JsonPrimitive -> if (element.isString) {
            val nested = runCatching { lyricsJson.parseToJsonElement(element.content.trim()) }.getOrNull()
            if (nested != null && nested !is JsonPrimitive) extractDurationMs(nested) else null
        } else {
            null
        }
        is JsonObject -> {
            MS_DURATION_KEYS.asSequence().mapNotNull { key ->
                (element[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 }
                    ?.roundToLong()
            }.firstOrNull()
                ?: FLEXIBLE_DURATION_KEYS.asSequence().mapNotNull { key ->
                    (element[key] as? JsonPrimitive)?.contentOrNull?.let(::parseFlexibleDurationMs)
                }.firstOrNull()
                ?: (element["metadata"] as? JsonObject)?.let(::extractDurationMs)
                ?: (element["data"] as? JsonObject)?.let(::extractDurationMs)
                ?: (element["result"] as? JsonObject)?.let(::extractDurationMs)
                ?: (element["response"] as? JsonObject)?.let(::extractDurationMs)
        }
    }

    private fun extract(element: JsonElement): String? = when (element) {
        JsonNull -> null
        is JsonPrimitive -> if (element.isString) {
            val text = element.content.trim()
            val nested = runCatching { lyricsJson.parseToJsonElement(text) }.getOrNull()
            if (nested != null && nested !is JsonPrimitive) extract(nested) else text
        } else null
        is JsonArray -> element.mapNotNull(::extract).joinToString("\n").takeIf { it.isNotBlank() }
        is JsonObject -> {
            if (element["isError"]?.toString() == "true" || element["ok"]?.toString() == "false" ||
                element["error"]?.let { it !is JsonNull && it.toString() !in setOf("false", "\"\"") } == true
            ) return null
            CONTENT_KEYS.asSequence().mapNotNull { element[it]?.let(::extract) }.firstOrNull()
                ?: (element["metadata"] as? JsonObject)?.let(::extract)
                ?: element["words"]?.let(::extract)
        }
    }

    private fun unescapeTtml(value: String): String = if (value.contains("&lt;tt", ignoreCase = true)) {
        value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
    } else value

    private val CONTENT_KEYS = listOf(
        "ttml", "ttmlContent", "lyrics", "lrc", "content", "text",
        "plainLyrics", "syncedLyrics", "line", "lines", "lyric",
        "data", "result", "response",
    )
    private val MS_DURATION_KEYS = listOf("durationInMillis", "durationMs", "duration_ms", "trackDurationMs")
    private val FLEXIBLE_DURATION_KEYS = listOf("totalDuration", "duration", "trackDuration", "trackLength", "track_length", "length")
    private val TOTAL_TAG = Regex("""^\[total:\s*(\d+)\s*]$""", RegexOption.IGNORE_CASE)
    private val LENGTH_TAG = Regex("""^\[length:\s*([^\]]+)\s*]$""", RegexOption.IGNORE_CASE)
}

/** QQ/QRC and NetEase YRC: millisecond line and word ranges. */
object KaraokeLrc {
    private val LINE = Regex("""^\[(\d{1,8}),(\d{1,8})](.*)$""")
    private val PREFIX_WORD = Regex("""\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)([^()]*)""")
    private val SUFFIX_WORD = Regex("""([^()]*)\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)""")
    private val WORD_TIME = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")
    private val CONTENT = Regex("""LyricContent\s*=\s*\"([^\"]*)\"""", RegexOption.IGNORE_CASE)

    fun looksLike(raw: String): Boolean = lyricContent(raw).lineSequence().any { line ->
        LINE.matchEntire(line.trim())?.groupValues?.get(3)?.let {
            PREFIX_WORD.containsMatchIn(it) || SUFFIX_WORD.containsMatchIn(it)
        } == true
    }

    fun parse(raw: String): List<LyricLine> {
        val rows = lyricContent(raw).lineSequence().mapNotNull { source ->
            val match = LINE.matchEntire(source.trim()) ?: return@mapNotNull null
            val lineStart = match.groupValues[1].toLong()
            val lineDuration = match.groupValues[2].toLong()
            val body = match.groupValues[3]
            // QRC and YRC stamp each syllable, and say which ones make a word
            // by leaving the space out between them — see [wordsFromRuns].
            val prefixed = wordsFromRuns(
                PREFIX_WORD.findAll(body).map { word ->
                    timedRun(word.groupValues[3], word.groupValues[1], word.groupValues[2])
                }.toList(),
            )
            val suffixed = wordsFromRuns(
                SUFFIX_WORD.findAll(body).map { word ->
                    timedRun(word.groupValues[1], word.groupValues[2], word.groupValues[3])
                }.toList(),
            )
            val words = if (prefixed.sumOf { it.text.length } >= suffixed.sumOf { it.text.length }) prefixed else suffixed
            if (words.isEmpty()) return@mapNotNull null
            LyricLine(
                timeMs = minOf(lineStart, words.first().startMs),
                text = EnhancedLrc.decodeEntities(body.replace(WORD_TIME, "")).trim(),
                words = words,
                sungUntilMs = (lineStart + lineDuration).takeIf { lineDuration > 0 },
            )
        }.filter { it.text.isNotEmpty() }.sortedBy { it.timeMs }.toList()
        return rows.withInstrumentalGaps()
    }

    private fun timedRun(text: String, start: String, duration: String): TimedRun {
        val startMs = start.toLong()
        return TimedRun(startMs, startMs + duration.toLong(), EnhancedLrc.decodeEntities(text))
    }

    private fun lyricContent(raw: String): String = CONTENT.find(raw)?.groupValues?.get(1)
        ?.replace("&quot;", "\"")?.replace("&apos;", "'")?.replace("&lt;", "<")
        ?.replace("&gt;", ">")?.replace("&amp;", "&") ?: raw
}
