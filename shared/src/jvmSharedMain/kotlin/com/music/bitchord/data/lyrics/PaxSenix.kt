package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Base64
import java.util.Locale
import kotlin.math.abs

/** Public Apple lyrics plus optional authenticated Spotify and Musixmatch routes. */
object PaxSenix {
    private const val API = "https://api.paxsenix.org"
    private const val PUBLIC_PROXY = "https://lyrics.paxsenix.org"
    private const val APPLE_CATALOG = "https://amp-api.music.apple.com/v1/catalog"
    private const val APPLE_WEB_BROWSE = "https://music.apple.com/us/browse"
    private const val APPLE_WEB_NEW = "https://music.apple.com/us/new"
    private const val MINIMUM_MATCH_SCORE = 10
    private const val TOKEN_RETRY_MS = 30L * 60 * 1000

    private val tokenMutex = Mutex()
    private var cachedAppleToken: String? = null
    private var appleTokenExpiresAtMs: Long = 0L
    private var retryAppleTokenAfterMs: Long = 0L
    private val rejectedAppleTokens = HashSet<String>()

    internal fun resetAppleTokenStateForTests() {
        cachedAppleToken = null
        appleTokenExpiresAtMs = 0L
        retryAppleTokenAfterMs = 0L
        rejectedAppleTokens.clear()
    }

    internal fun resolveStorefront(locale: Locale = Locale.getDefault()): String =
        locale.country.takeIf { it.length == 2 && it.all(Char::isLetter) }?.lowercase(Locale.ROOT) ?: "us"

    internal fun resolveLanguageTag(locale: Locale = Locale.getDefault()): String =
        locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: "en-US"

    @Volatile
    private var apiKey: String = ""

    fun setApiKey(value: String) {
        apiKey = normalizePaxSenixApiKey(value)
    }

    /** The original keyless Apple Music provider used by the existing source id. */
    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        onIsrc: ((String) -> Unit)? = null,
    ): List<LyricLine>? = lyricsInternal(
        title = title,
        artist = artist,
        durationMs = durationMs,
        album = album,
        onIsrc = onIsrc,
    )

    internal suspend fun lyricsInternal(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        onIsrc: ((String) -> Unit)? = null,
        locale: Locale = Locale.getDefault(),
        get: (String) -> String? = ::lyricsGet,
        authorizedRequest: (String, String) -> LyricsHttpResponse? = ::lyricsRequestAuthorized,
        nowMs: () -> Long = System::currentTimeMillis,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val candidate = searchPublicAppleCandidate(
            title = title,
            artist = artist,
            durationMs = durationMs,
            album = album,
            locale = locale,
            get = get,
            authorizedRequest = authorizedRequest,
            nowMs = nowMs,
        ) ?: return@withContext null
        candidate.isrc?.let { onIsrc?.invoke(it) }
        val url = publicUrl("apple-music/lyrics")
            .addQueryParameter("id", candidate.id)
            .addQueryParameter("ttml", "true")
            .build()
        get(url.toString())?.let(::parseResponse)
            ?: apiBody(
                apiUrl("lyrics/apple-music")
                    .addQueryParameter("id", candidate.id)
                    .addQueryParameter("ttml", "true")
                    .build(),
            )?.let(::parseResponse)
    }

    suspend fun spotifyLyrics(
        title: String,
        artist: String,
        durationMs: Long,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val id = searchTrackId("spotify/search", title, artist, durationMs)
        id?.let {
            apiBody(apiUrl("lyrics/spotify").addQueryParameter("id", it).build())
                ?.let(::parseResponse)
        } ?: genericAuthenticatedLyrics(title, artist, durationMs)
    }

    suspend fun musixmatchLyrics(
        title: String,
        artist: String,
        durationMs: Long,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val query: HttpUrl.Builder.() -> Unit = {
            addQueryParameter("t", title)
            addQueryParameter("a", artist)
            addQueryParameter("d", (durationMs / 1000).toString())
        }
        apiBody(apiUrl("lyrics/musixmatch").apply(query).build())
            ?.let(::parseResponse)
            ?: genericAuthenticatedLyrics(title, artist, durationMs)
    }

    internal suspend fun searchPublicAppleCandidate(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        locale: Locale = Locale.getDefault(),
        get: (String) -> String? = ::lyricsGet,
        authorizedRequest: (String, String) -> LyricsHttpResponse? = ::lyricsRequestAuthorized,
        nowMs: () -> Long = System::currentTimeMillis,
    ): Candidate? {
        val storefront = resolveStorefront(locale)
        val language = resolveLanguageTag(locale)
        val storefronts = if (storefront == "us") listOf("us") else listOf(storefront, "us")
        val term = "$title $artist".trim()
        for (sf in storefronts) {
            val url = "$APPLE_CATALOG/$sf/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", term)
                .addQueryParameter("types", "songs")
                .addQueryParameter("limit", "10")
                .addQueryParameter("l", if (sf == storefront) language else "en-US")
                .build()
            val body = appleCatalogGet(url.toString(), get, authorizedRequest, nowMs) ?: continue
            val root = runCatching { lyricsJson.parseToJsonElement(body) }.getOrNull() ?: continue
            bestCandidate(root, title, artist, durationMs, album)?.let { return it }
        }
        return null
    }

    private suspend fun appleCatalogGet(
        url: String,
        get: (String) -> String?,
        authorizedRequest: (String, String) -> LyricsHttpResponse?,
        nowMs: () -> Long,
    ): String? {
        val token = appleToken(get, nowMs) ?: return null
        val first = authorizedRequest(url, token)
        when {
            first == null -> return null
            first.isSuccessful -> return first.body
            first.code == 401 -> {
                invalidateAppleToken(token)
                val refreshed = appleToken(get, nowMs) ?: return null
                val second = authorizedRequest(url, refreshed)
                if (second?.code == 401) {
                    invalidateAppleToken(refreshed)
                    return null
                }
                return second?.takeIf { it.isSuccessful }?.body
            }
            else -> return null
        }
    }

    private suspend fun invalidateAppleToken(bearer: String) {
        tokenMutex.withLock {
            rejectedAppleTokens += bearer
            if (cachedAppleToken == bearer) {
                cachedAppleToken = null
                appleTokenExpiresAtMs = 0L
            }
            retryAppleTokenAfterMs = 0L
        }
    }

    internal suspend fun appleToken(
        get: (String) -> String? = ::lyricsGet,
        nowMs: () -> Long = System::currentTimeMillis,
    ): String? = tokenMutex.withLock {
        val now = nowMs()
        cachedAppleToken?.let { token ->
            if (token !in rejectedAppleTokens && now < appleTokenExpiresAtMs - 60_000L) {
                return@withLock token
            }
        }
        if (now < retryAppleTokenAfterMs) return@withLock null

        val scraped = scrapeAppleToken(get, now)
        if (scraped != null) {
            cachedAppleToken = scraped.first
            appleTokenExpiresAtMs = scraped.second
            return@withLock scraped.first
        }

        retryAppleTokenAfterMs = now + TOKEN_RETRY_MS
        null
    }

    private fun scrapeAppleToken(get: (String) -> String?, now: Long): Pair<String, Long>? {
        val pages = sequenceOf(APPLE_WEB_BROWSE, APPLE_WEB_NEW)
        for (pageUrl in pages) {
            val html = get(pageUrl) ?: continue
            val scripts = APPLE_INDEX_SCRIPT.findAll(html)
                .map(MatchResult::value)
                .distinct()
                .toList()
            for (scriptPath in scripts) {
                val script = get("https://music.apple.com$scriptPath") ?: continue
                val candidates = APPLE_TOKEN.findAll(script)
                    .map(MatchResult::value)
                    .distinct()
                    .filter { it !in rejectedAppleTokens }
                    .mapNotNull { jwt -> tokenExpiryMs(jwt)?.let { jwt to it } }
                    .filter { it.second > now }
                    .toList()
                if (candidates.isEmpty()) continue
                return candidates.firstOrNull { isWebPlayerToken(it.first) } ?: candidates.first()
            }
        }
        return null
    }

    internal fun isWebPlayerToken(jwt: String): Boolean = runCatching {
        val parts = jwt.split('.')
        if (parts.size < 2) return false
        val header = decodeJwtPart(parts[0])
        val payload = decodeJwtPart(parts[1])
        header.contains("WebPlayKid") || payload.contains("AMPWebPlay")
    }.getOrDefault(false)

    internal fun tokenExpiryMs(jwt: String): Long? = runCatching {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        val payload = decodeJwtPart(parts[1])
        val seconds = JWT_EXPIRY.find(payload)?.groupValues?.get(1)
        seconds?.toLong()?.times(1000L)
    }.getOrDefault(null)

    private fun decodeJwtPart(part: String): String {
        val padded = when (part.length % 4) {
            2 -> "$part=="
            3 -> "$part="
            else -> part
        }
        return String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    }

    private fun searchTrackId(
        path: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): String? {
        val query: HttpUrl.Builder.() -> Unit = {
            addQueryParameter("q", "$title $artist")
        }
        val root = apiBody(apiUrl(path).apply(query).build())
            ?.let { runCatching { lyricsJson.parseToJsonElement(it) }.getOrNull() }
            ?: return null
        return bestCandidate(root, title, artist, durationMs)?.id
    }

    private fun genericAuthenticatedLyrics(
        title: String,
        artist: String,
        durationMs: Long,
    ): List<LyricLine>? {
        val url = apiUrl("lyrics/lrcget")
            .addQueryParameter("q", "$title $artist")
            .build()
        return apiBody(url)?.let { parseLrcGet(it, title, artist, durationMs) }
    }

    /**
     * The general endpoint returns search candidates, not lyric lines. Parsing
     * its array as one document concatenates every candidate and makes each
     * song's timestamps restart at zero, which appears as repeated lines that
     * cannot stay in sync. Select one recording before parsing its lyrics.
     */
    fun parseLrcGet(
        raw: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): List<LyricLine>? {
        val root = runCatching { lyricsJson.parseToJsonElement(raw) }.getOrNull()
            ?: return null
        val payload = (root as? JsonObject)?.get("lyrics")
        val documents = (payload as? JsonArray).orEmpty()
        if (documents.isEmpty()) return parseResponse(raw)

        return documents.mapNotNull { document ->
            val lines = parseResponse(document.toString()) ?: return@mapNotNull null
            val metadataScore = (document as? JsonObject)
                ?.lyricCandidateScore(title, artist, durationMs) ?: 0
            ParsedDocument(lines, metadataScore, durationDistance(lines, durationMs))
        }.maxWithOrNull(
            compareBy<ParsedDocument> { it.metadataScore }
                .thenBy { -it.durationDistanceMs }
                .thenBy { document -> document.lines.count { it.timeMs > 0L } }
                .thenBy { document -> document.lines.count { it.isWordSynced } },
        )?.lines
    }

    private fun durationDistance(lines: List<LyricLine>, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val lastTimestamp = lines.maxOfOrNull { line ->
            maxOf(
                line.timeMs,
                line.sungUntilMs ?: 0L,
                line.words.maxOfOrNull(LyricWord::endMs) ?: 0L,
            )
        } ?: return Long.MAX_VALUE
        return abs(lastTimestamp - durationMs)
    }

    private fun JsonObject.lyricCandidateScore(
        title: String,
        artist: String,
        durationMs: Long,
    ): Int {
        val details = this["attributes"] as? JsonObject ?: this
        val candidate = Candidate(
            id = firstString(ID_KEYS) ?: "",
            title = details.firstString(TITLE_KEYS).orEmpty(),
            artist = details.firstString(ARTIST_KEYS) ?: details.artistNames().orEmpty(),
            durationMs = details.firstLong(DURATION_KEYS).toDurationMs(),
        )
        return candidate.score(title, artist, durationMs)
    }

    private fun bestCandidate(
        root: JsonElement,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): Candidate? {
        val candidates = buildList { root.collectCandidates(this) }
        return candidates.map { it to it.score(title, artist, durationMs, album) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= MINIMUM_MATCH_SCORE }
            ?.first
    }

    private fun parseResponse(raw: String): List<LyricLine>? =
        parseTimedApple(raw) ?: ProviderLyrics.parse(raw)

    /** Preserve word timestamps when PaxSenix returns its structured Apple payload. */
    fun parseTimedApple(raw: String): List<LyricLine>? {
        val root = runCatching { lyricsJson.parseToJsonElement(raw) }.getOrNull() ?: return null
        val content = root.findTimedContent() ?: return null
        val rows = content.mapNotNull { it as? JsonObject }
        val lines = rows.mapIndexedNotNull { index, row ->
            val start = row.long("timestamp") ?: return@mapIndexedNotNull null
            val wordRows = row["text"] as? JsonArray ?: return@mapIndexedNotNull null
            val texts = wordRows.mapNotNull { (it as? JsonObject)?.string("text") }
            if (texts.isEmpty()) return@mapIndexedNotNull null
            val nextLine = rows.getOrNull(index + 1)?.long("timestamp")
            // Each entry is a syllable. `part` marks one that runs straight into
            // the next — "e" then "nough" — so the space goes only where it is
            // false. Joined with a space throughout, that read "long e nough".
            val runs = wordRows.mapIndexedNotNull { wordIndex, element ->
                val word = element as? JsonObject ?: return@mapIndexedNotNull null
                val text = word.string("text")?.trim()?.takeIf(String::isNotEmpty)
                    ?: return@mapIndexedNotNull null
                val wordStart = word.long("timestamp") ?: return@mapIndexedNotNull null
                val wordEnd = word.long("endtime")
                    ?: (wordRows.getOrNull(wordIndex + 1) as? JsonObject)?.long("timestamp")
                    ?: nextLine ?: wordStart + 800
                val joinsNext = (word["part"] as? JsonPrimitive)?.booleanOrNull == true
                TimedRun(wordStart, wordEnd, if (joinsNext) text else "$text ")
            }
            val timed = wordsFromRuns(runs, spacingIsExplicit = true)
            // A syllable with no stamp leaves nothing to time the line by, as
            // before; the text is then the entries as they came.
            val untimed = runs.size != texts.count { it.isNotBlank() }
            LyricLine(
                timeMs = minOf(start, timed.firstOrNull()?.startMs ?: start),
                text = if (untimed) {
                    texts.joinToString(" ") { it.trim() }
                } else {
                    timed.joinToString(" ") { it.text }
                },
                words = if (untimed) emptyList() else timed,
                sungUntilMs = nextLine,
            )
        }
        return lines.withInstrumentalGaps().takeIf { it.any { line -> line.text.isNotBlank() } }
    }

    private fun JsonElement.findTimedContent(): JsonArray? = when (this) {
        is JsonObject -> {
            (this["content"] as? JsonArray)?.takeIf { array ->
                array.any { (it as? JsonObject)?.get("timestamp") != null }
            } ?: values.firstNotNullOfOrNull { it.findTimedContent() }
        }
        is JsonArray -> firstNotNullOfOrNull { it.findTimedContent() }
        else -> null
    }

    private fun JsonElement.collectCandidates(into: MutableList<Candidate>) {
        when (this) {
            is JsonArray -> forEach { it.collectCandidates(into) }
            is JsonObject -> {
                toCandidate()?.let(into::add)
                values.forEach { it.collectCandidates(into) }
            }
            else -> Unit
        }
    }

    private fun JsonObject.toCandidate(): Candidate? {
        val details = this["attributes"] as? JsonObject ?: this
        val id = firstString(ID_KEYS) ?: details.firstString(ID_KEYS) ?: return null
        val title = details.firstString(TITLE_KEYS) ?: return null
        val artist = details.firstString(ARTIST_KEYS) ?: details.artistNames().orEmpty()
        val album = details.firstString(ALBUM_KEYS) ?: firstString(ALBUM_KEYS)
        val isrc = LrcRed.normalizeIsrc(details.firstString(ISRC_KEYS) ?: firstString(ISRC_KEYS))
        return Candidate(id, title, artist, details.firstLong(DURATION_KEYS).toDurationMs(), isrc, album)
    }

    private fun JsonObject.artistNames(): String? = when (val artists = this["artists"] ?: this["artist"]) {
        is JsonPrimitive -> artists.contentOrNull
        is JsonObject -> artists.firstString(listOf("name", "artistName", "title"))
        is JsonArray -> artists.mapNotNull {
            when (it) {
                is JsonPrimitive -> it.contentOrNull
                is JsonObject -> it.firstString(listOf("name", "artistName", "title"))
                else -> null
            }
        }.joinToString(", ").takeIf(String::isNotEmpty)
        else -> null
    }

    private fun JsonObject.firstString(keys: List<String>): String? =
        keys.firstNotNullOfOrNull { string(it)?.trim()?.takeIf(String::isNotEmpty) }

    private fun JsonObject.firstLong(keys: List<String>): Long? =
        keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.longOrNull }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun Long?.toDurationMs(): Long = when {
        this == null || this <= 0 -> 0
        this < 10_000 -> this * 1000
        else -> this
    }

    private fun Candidate.score(
        wantedTitle: String,
        wantedArtist: String,
        wantedDuration: Long,
        wantedAlbum: String? = null,
    ): Int {
        var score = textScore(title, wantedTitle, 20, 10) + textScore(artist, wantedArtist, 15, 5)
        if (!album.isNullOrBlank() && !wantedAlbum.isNullOrBlank()) {
            score += textScore(album, wantedAlbum, 4, 2)
        }
        if (wantedDuration > 0 && durationMs > 0) score += when {
            abs(durationMs - wantedDuration) < 3_000 -> 10
            abs(durationMs - wantedDuration) < 10_000 -> 5
            else -> 0
        }
        return score
    }

    private fun textScore(candidate: String, wanted: String, exact: Int, partial: Int): Int = when {
        candidate.isBlank() || wanted.isBlank() -> 0
        candidate.equals(wanted, ignoreCase = true) -> exact
        candidate.contains(wanted, ignoreCase = true) || wanted.contains(candidate, ignoreCase = true) -> partial
        else -> 0
    }

    private fun apiUrl(path: String) = "$API/$path".toHttpUrl().newBuilder()

    private fun publicUrl(path: String) = "$PUBLIC_PROXY/$path".toHttpUrl().newBuilder()

    private fun apiBody(url: HttpUrl): String? = apiKey.takeIf(String::isNotBlank)?.let { key ->
        lyricsGetBearer(url.toString(), key)
    }

    internal data class Candidate(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val isrc: String? = null,
        val album: String? = null,
    )
    private data class ParsedDocument(
        val lines: List<LyricLine>,
        val metadataScore: Int,
        val durationDistanceMs: Long,
    )

    private val ID_KEYS = listOf("id", "trackId", "track_id", "realId")
    private val TITLE_KEYS = listOf("name", "title", "trackName", "track_name")
    private val ARTIST_KEYS = listOf("artistName", "artist_name")
    private val ALBUM_KEYS = listOf("albumName", "album_name", "album")
    private val DURATION_KEYS = listOf("durationInMillis", "durationMs", "duration_ms", "duration")
    private val ISRC_KEYS = listOf("isrc")

    private val APPLE_INDEX_SCRIPT = Regex("""/assets/index(?:-legacy)?[~-][A-Za-z0-9_-]+\.js""")
    private val APPLE_TOKEN = Regex("""ey[A-Za-z0-9_-]+\.ey[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
    private val JWT_EXPIRY = Regex(""""exp"\s*:\s*(\d+)""")
}

fun normalizePaxSenixApiKey(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
        trimmed.substringAfter(' ').trim()
    } else {
        trimmed
    }
}
