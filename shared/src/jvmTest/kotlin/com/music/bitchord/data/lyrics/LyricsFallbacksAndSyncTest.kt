package com.music.bitchord.data.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsFallbacksAndSyncTest {

    private val wordTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
          <p begin="1.0" end="2.0"><span begin="1.0" end="1.4">let</span> <span begin="1.4" end="2.0">down</span></p>
        </div></body></tt>
    """.trimIndent()

    @BeforeTest
    fun resetState() {
        LyricsRepository.clearRememberedIsrcsForTests()
        LyricsPlus.resetForTests()
        PaxSenix.resetAppleTokenStateForTests()
    }

    // ---- Requirement 1: Staged query fallbacks & version word preservation ----

    @Test
    fun `staged queries try full metadata then drop album then use lead artist within 3 stages`() {
        val stages = lyricsQueryStages(
            title = "Levitating (Remix) [Official Video]",
            artist = "Dua Lipa, DaBaby - Topic",
            album = "Download",
        )

        assertEquals(MAX_LYRICS_QUERY_STAGES, stages.size)
        assertEquals(LyricsQueryStage("Levitating (Remix)", "Dua Lipa, DaBaby", "Download"), stages[0])
        assertEquals(LyricsQueryStage("Levitating (Remix)", "Dua Lipa, DaBaby", null), stages[1])
        assertEquals(LyricsQueryStage("Levitating (Remix)", "Dua Lipa", null), stages[2])
    }

    @Test
    fun `staged queries preserve Live and Remix markers and deduplicate solo-artist stages`() {
        val liveStages = lyricsQueryStages("Song 2 (Live)", "Blur", null)
        assertEquals(listOf(LyricsQueryStage("Song 2 (Live)", "Blur", null)), liveStages)

        val albumStages = lyricsQueryStages("Dracula (JENNIE Remix)", "Tame Impala", "Download")
        assertEquals(
            listOf(
                LyricsQueryStage("Dracula (JENNIE Remix)", "Tame Impala", "Download"),
                LyricsQueryStage("Dracula (JENNIE Remix)", "Tame Impala", null),
            ),
            albumStages,
        )

        assertEquals("Bad Bunny", "Música, Bad Bunny".leadArtistForLyricsSearch())
    }

    @Test
    fun `BetterLyrics retries without album and then with lead artist and stops at 3 attempts`() = runBlocking {
        val seenUrls = mutableListOf<String>()
        val lines = BetterLyrics.lyrics(
            title = "Stay (Live)",
            artist = "The Kid LAROI & Justin Bieber",
            durationMs = 141_000L,
            album = "Download",
            get = { url ->
                seenUrls += url
                val parsed = url.toHttpUrl()
                if (parsed.queryParameter("al") == null && parsed.queryParameter("a") == "The Kid LAROI") {
                    """{"ttml":${lyricsJson.encodeToString(kotlinx.serialization.serializer(), wordTtml)}}"""
                } else {
                    null
                }
            },
        )

        assertNotNull(lines)
        assertTrue(lines.any { it.isWordSynced })
        assertEquals(3, seenUrls.size)
        assertEquals("Download", seenUrls[0].toHttpUrl().queryParameter("al"))
        assertNull(seenUrls[1].toHttpUrl().queryParameter("al"))
        assertEquals("The Kid LAROI & Justin Bieber", seenUrls[1].toHttpUrl().queryParameter("a"))
        assertEquals("The Kid LAROI", seenUrls[2].toHttpUrl().queryParameter("a"))
        assertEquals("Stay (Live)", seenUrls[2].toHttpUrl().queryParameter("s"))

        val missCount = AtomicInteger()
        assertNull(
            BetterLyrics.lyrics(
                title = "Missing",
                artist = "One, Two & Three",
                durationMs = 180_000L,
                album = "Wrong Album",
                get = {
                    missCount.incrementAndGet()
                    null
                },
            ),
        )
        assertEquals(3, missCount.get())
    }

    @Test
    fun `Unison retries without album and with lead artist capped at 3 attempts`() = runBlocking {
        val queries = mutableListOf<String>()
        val found = Unison.lyrics(
            title = "Nightcall (Remix)",
            artist = "Kavinsky, Lovefoxxx",
            durationMs = 258_000L,
            album = "Download",
            get = { url ->
                queries += url
                val parsed = url.toHttpUrl()
                if (parsed.queryParameter("album") == null && parsed.queryParameter("artist") == "Kavinsky") {
                    """{"success":true,"data":{"format":"lrc","syncType":"linesync","lyrics":"[00:05.00]There's something inside you"}}"""
                } else {
                    """{"success":false}"""
                }
            },
        )

        assertNotNull(found)
        assertEquals(3, queries.size)
        assertEquals("Download", queries[0].toHttpUrl().queryParameter("album"))
        assertNull(queries[1].toHttpUrl().queryParameter("album"))
        assertEquals("Kavinsky", queries[2].toHttpUrl().queryParameter("artist"))
        assertEquals("Nightcall (Remix)", queries[2].toHttpUrl().queryParameter("song"))
    }

    @Test
    fun `BiniLyrics retries staged queries on album miss and caps total queries including ISRC at 3`() = runBlocking {
        val calls = mutableListOf<String>()
        val reportedIsrcs = mutableListOf<String>()
        val match = BiniLyrics.lyrics(
            title = "golden hour",
            artist = "JVKE, Fujii Kaze",
            durationMs = 209_000L,
            album = "Download",
            get = { wordTtml },
            onIsrc = { reportedIsrcs += it },
            searchGet = { url ->
                calls += url
                val parsed = url.toHttpUrl()
                if (parsed.queryParameter("album") == null && parsed.queryParameter("artist") == "JVKE") {
                    """{"total":1,"results":[{"track_name":"golden hour","artist_name":"JVKE","duration":209,"isrc":"GBKPL2204171","timing_type":"word","lyricsUrl":"https://lrc.red/s/GBKPL2204171.ttml"}]}"""
                } else {
                    """{"total":0,"results":[]}"""
                }
            },
        )

        assertNotNull(match)
        assertEquals(listOf("GBKPL2204171"), reportedIsrcs)
        assertEquals(3, calls.size)

        val cappedCalls = mutableListOf<String>()
        assertNull(
            BiniLyrics.identify(
                title = "Missing",
                artist = "Artist A & Artist B",
                durationMs = 200_000L,
                album = "Album",
                isrc = "USUM71703861",
                searchGet = { url ->
                    cappedCalls += url
                    null
                },
            ),
        )
        assertEquals(3, cappedCalls.size)
        assertEquals("USUM71703861", cappedCalls[0].toHttpUrl().queryParameter("isrc"))
        assertNull(cappedCalls[1].toHttpUrl().queryParameter("album"))
        assertEquals("Artist A", cappedCalls[2].toHttpUrl().queryParameter("artist"))
    }

    // ---- Requirement 5: BiniLyrics word preference & default syllable sync ----

    @Test
    fun `BiniLyrics best prefers word timing within 3s tolerance on Let Down fixture`() {
        val fixture = sequenceOf(
            File("web/fixtures/lyrics/binilyrics.json"),
            File("../web/fixtures/lyrics/binilyrics.json"),
        ).firstOrNull(File::exists) ?: error("binilyrics.json fixture not found")

        val results = lyricsJson.decodeFromString<BiniLyrics.Response>(fixture.readText()).results!!
        val chosen = BiniLyrics.best(results, "Let Down", "Radiohead", 299_000L)

        assertNotNull(chosen)
        assertEquals("GBAYE9701374", chosen.isrc)
        assertEquals("word", chosen.timingType)

        // Outside LrcRed's 3.0s tolerance, neither candidate matches.
        assertNull(BiniLyrics.best(results, "Let Down", "Radiohead", 294_000L))
    }

    // ---- Requirement 2: Non-blocking ISRC sharing, caching & provider toggles ----

    @Test
    fun `LyricsRepository starts providers concurrently without blocking on BiniLyrics and skips disabled or hidden sources`() =
        runBlocking {
            val started = Collections.synchronizedList(mutableListOf<LyricsSource>())
            val fastProviderStarted = CompletableDeferred<Unit>()
            val releaseBini = CompletableDeferred<Unit>()

            val lookupJob = async {
                LyricsRepository.lookup(
                    videoId = "vid-nonblocking",
                    title = "Let Down",
                    artist = "Radiohead",
                    durationMs = 299_000L,
                    sources = setOf(
                        LyricsSource.BINI_LYRICS,
                        LyricsSource.BETTER_LYRICS,
                        LyricsSource.SIMP_MUSIC, // hidden: must never start
                    ),
                    order = listOf(
                        LyricsSource.SIMP_MUSIC,
                        LyricsSource.LRCLIB, // disabled (not in sources): must never start
                        LyricsSource.BINI_LYRICS,
                        LyricsSource.BETTER_LYRICS,
                    ),
                    onSourceStarted = { source ->
                        started += source
                        if (source == LyricsSource.BETTER_LYRICS) {
                            fastProviderStarted.complete(Unit)
                        }
                    },
                    fetchSource = { call ->
                        when (call.source) {
                            LyricsSource.BINI_LYRICS -> {
                                // Still waiting while BETTER_LYRICS already started concurrently!
                                fastProviderStarted.await()
                                releaseBini.await()
                                null
                            }
                            LyricsSource.BETTER_LYRICS -> listOf(
                                LyricLine(
                                    timeMs = 1_000L,
                                    text = "let down",
                                    words = listOf(
                                        LyricWord(1_000L, 1_400L, "let"),
                                        LyricWord(1_400L, 2_000L, "down"),
                                    ),
                                ),
                            )
                            else -> error("Unexpected source contacted: ${call.source}")
                        }
                    },
                )
            }

            withTimeout(2_000L) {
                fastProviderStarted.await()
            }
            releaseBini.complete(Unit)
            val result = lookupJob.await()

            assertEquals(LyricsSource.BETTER_LYRICS, result?.source)
            assertFalse(LyricsSource.SIMP_MUSIC in started)
            assertFalse(LyricsSource.LRCLIB in started)
        }

    @Test
    fun `LyricsRepository caches ISRCs from BiniLyrics LrcRed PaxSenix and Musixmatch and prioritizes syllable sync by default`() =
        runBlocking {
            val providersAndIsrcs = listOf(
                LyricsSource.BINI_LYRICS to "GBAYE9701374",
                LyricsSource.LRC_RED to "USUG11904206",
                LyricsSource.PAXSENIX to "USUM71703861",
                LyricsSource.MUSIXMATCH to "QZXDB2300005",
            )

            for ((provider, isrc) in providersAndIsrcs) {
                val videoId = "video-${provider.name}"
                val first = LyricsRepository.lookup(
                    videoId = videoId,
                    title = "Song",
                    artist = "Artist",
                    durationMs = 200_000L,
                    sources = setOf(provider, LyricsSource.BETTER_LYRICS),
                    order = listOf(provider, LyricsSource.BETTER_LYRICS),
                    // Rely on default prioritizeSyllableSync = true!
                    fetchSource = { call ->
                        when (call.source) {
                            provider -> {
                                call.onIsrc(isrc)
                                // Line-synced only: held as fallback because prioritizeSyllableSync defaults to true
                                listOf(LyricLine(timeMs = 1_000L, text = "line sync"))
                            }
                            LyricsSource.BETTER_LYRICS -> listOf(
                                LyricLine(
                                    timeMs = 1_000L,
                                    text = "word sync",
                                    words = listOf(
                                        LyricWord(1_000L, 1_500L, "word"),
                                        LyricWord(1_500L, 2_000L, "sync"),
                                    ),
                                ),
                            )
                            else -> null
                        }
                    },
                )

                assertEquals(LyricsSource.BETTER_LYRICS, first?.source)
                assertEquals(isrc, LyricsRepository.rememberedIsrc(videoId))

                // Second lookup for the same videoId immediately receives the cached ISRC.
                var observedIsrc: String? = null
                LyricsRepository.lookup(
                    videoId = videoId,
                    title = "Song",
                    artist = "Artist",
                    durationMs = 200_000L,
                    sources = setOf(LyricsSource.LRC_RED),
                    order = listOf(LyricsSource.LRC_RED),
                    fetchSource = { call ->
                        observedIsrc = call.currentIsrc()
                        null
                    },
                )
                assertEquals(isrc, observedIsrc)
            }
        }

    @Test
    fun `Musixmatch reports normalized track_isrc via onIsrc`() = runBlocking {
        val seenIsrcs = mutableListOf<String>()
        val lines = Musixmatch.lyricsInternal(
            title = "Song",
            artist = "Artist",
            durationMs = 200_000L,
            onIsrc = { seenIsrcs += it },
            requestSigned = { buildUrl ->
                val url = buildUrl("tok").toString()
                when {
                    "track.search" in url -> """
                        {"message":{"header":{"status_code":200},"body":{"track_list":[
                          {"track":{"track_id":42,"track_name":"Song","artist_name":"Artist","track_length":200,"track_isrc":"gb-aye-97-01374","has_subtitles":1,"has_richsync":0}}
                        ]}}}
                    """.trimIndent()
                    "track.subtitle.get" in url -> """
                        {"message":{"header":{"status_code":200},"body":{"subtitle":{"subtitle_body":"[{\"text\":\"hello world\",\"time\":{\"total\":1.5}}]"}}}}
                    """.trimIndent()
                    else -> null
                }
            },
        )

        assertNotNull(lines)
        assertEquals(listOf("GBAYE9701374"), seenIsrcs)
    }

    // ---- Requirement 6: PaxSenix storefront, JWT validation & 401 refresh; LyricsPlus dead-host pruning ----

    private fun fakeJwt(payloadJson: String, headerJson: String = """{"alg":"ES256","kid":"WebPlayKid1"}"""): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val h = enc.encodeToString(headerJson.toByteArray(Charsets.UTF_8))
        val p = enc.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "$h.$p.signature_bytes_1234567890"
    }

    @Test
    fun `PaxSenix uses device storefront validates web-player JWT refreshes on 401 and reports ISRC`() = runBlocking {
        val nowMs = 1_700_000_000_000L
        val nowSec = nowMs / 1000L
        val expiredJwt = fakeJwt("""{"iss":"AMPWebPlay","exp":${nowSec - 100},"root_https_origin":["apple.com"]}""")
        val staleWebJwt = fakeJwt("""{"iss":"AMPWebPlay","exp":${nowSec + 7200},"root_https_origin":["apple.com"]}""")
        val freshWebJwt = fakeJwt("""{"iss":"AMPWebPlay","exp":${nowSec + 14400},"root_https_origin":["music.apple.com"]}""")

        var bundleLoads = 0
        val catalogCalls = mutableListOf<Pair<String, String>>()
        val reportedIsrcs = mutableListOf<String>()

        val lines = PaxSenix.lyricsInternal(
            title = "Idol",
            artist = "YOASOBI",
            durationMs = 213_000L,
            locale = Locale.forLanguageTag("ja-JP"),
            onIsrc = { reportedIsrcs += it },
            nowMs = { nowMs },
            get = { url ->
                when {
                    url == "https://music.apple.com/us/browse" -> """<script src="/assets/index-legacy-abc123.js"></script>"""
                    url == "https://music.apple.com/assets/index-legacy-abc123.js" -> {
                        bundleLoads++
                        if (bundleLoads == 1) {
                            "const a=\"$expiredJwt\"; const b=\"$staleWebJwt\";"
                        } else {
                            "const c=\"$freshWebJwt\";"
                        }
                    }
                    url.startsWith("https://lyrics.paxsenix.org/apple-music/lyrics") -> wordTtml
                    else -> null
                }
            },
            authorizedRequest = { url, bearer ->
                catalogCalls += url to bearer
                if (bearer == staleWebJwt) {
                    LyricsHttpResponse(code = 401, contentType = "application/json", body = """{"message":"Unauthorized"}""")
                } else {
                    LyricsHttpResponse(
                        code = 200,
                        contentType = "application/json",
                        body = """
                            {"results":{"songs":{"data":[{
                              "id":"1681584481",
                              "attributes":{
                                "name":"Idol",
                                "artistName":"YOASOBI",
                                "durationInMillis":213000,
                                "isrc":"JPXX02300123"
                              }
                            }]}}}
                        """.trimIndent(),
                    )
                }
            },
        )

        assertNotNull(lines)
        assertTrue(lines.any { it.isWordSynced })
        assertEquals(listOf("JPXX02300123"), reportedIsrcs)
        // First attempt used staleWebJwt (skipping expiredJwt), got 401, refreshed to freshWebJwt, and queried /jp/search with l=ja-JP!
        assertEquals(2, catalogCalls.size)
        assertEquals(staleWebJwt, catalogCalls[0].second)
        assertEquals(freshWebJwt, catalogCalls[1].second)
        assertTrue(catalogCalls[0].first.startsWith("https://amp-api.music.apple.com/v1/catalog/jp/search"))
        assertEquals("ja-JP", catalogCalls[0].first.toHttpUrl().queryParameter("l"))
    }

    @Test
    fun `LyricsPlus prunes dead mirrors cools down failing hosts and retries JSON 404 fallback stages`() = runBlocking {
        assertEquals(
            listOf("https://lyricsplus.prjktla.my.id", "https://lyricsplus.binimum.org"),
            LyricsPlus.MIRRORS,
        )

        val deadHost = "https://dead.lyricsplus.example"
        val liveHost = "https://live.lyricsplus.example"
        val deadRequests = AtomicInteger()
        val liveRequests = mutableListOf<String>()
        var clockMs = 1_000L

        val v2WordPayload = """
            {"type":"Word","lyrics":[{"time":1000,"duration":1000,"text":"let down",
              "syllabus":[{"time":1000,"duration":400,"text":"let "},{"time":1400,"duration":600,"text":"down"}]}]}
        """.trimIndent()

        val first = LyricsPlus.lyricsInternal(
            title = "Let Down",
            artist = "Radiohead, Thom Yorke",
            durationMs = 299_000L,
            album = "Download",
            mirrors = listOf(deadHost, liveHost),
            nowMs = { clockMs },
            request = { url ->
                when {
                    url.startsWith(deadHost) -> {
                        deadRequests.incrementAndGet()
                        // Plain-text / Vercel 402 deployment failure -> marks host dead immediately on stage 1!
                        LyricsHttpResponse(code = 402, contentType = "text/plain", body = "DEPLOYMENT_DISABLED")
                    }
                    url.startsWith(liveHost) -> {
                        liveRequests += url
                        val parsed = url.toHttpUrl()
                        if (parsed.queryParameter("album") != null) {
                            LyricsHttpResponse(code = 404, contentType = "application/json", body = """{"error":"not found"}""")
                        } else {
                            LyricsHttpResponse(code = 200, contentType = "application/json", body = v2WordPayload)
                        }
                    }
                    else -> null
                }
            },
        )

        assertNotNull(first)
        assertEquals(1, deadRequests.get(), "Dead mirror should be abandoned on first stage failure")
        assertEquals(2, liveRequests.size, "Live mirror should retry without album after JSON 404")

        // Next lookup within cooldown window skips deadHost completely!
        clockMs += 60_000L
        LyricsPlus.lyricsInternal(
            title = "Karma Police",
            artist = "Radiohead",
            durationMs = 264_000L,
            mirrors = listOf(deadHost, liveHost),
            nowMs = { clockMs },
            request = { url ->
                if (url.startsWith(deadHost)) deadRequests.incrementAndGet()
                LyricsHttpResponse(code = 200, contentType = "application/json", body = v2WordPayload)
            },
        )
        assertEquals(1, deadRequests.get(), "Cooled-down dead mirror must not be contacted again during cooldown")
    }
}
