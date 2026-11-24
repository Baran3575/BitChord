package com.music.bitchord.data.lyrics

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.VersionTimeMap
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioLyricsDurationAndRemapTest {

    @BeforeTest
    fun resetIsrcCache() {
        LyricsRepository.clearIsrcsForTest()
    }

    @Test
    fun `TtmlLyrics parseDocument extracts body dur in clock seconds and ms formats`() {
        val clockTtml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body dur="03:40.046">
                <div><p begin="00:10.000" end="00:14.000"><span begin="00:10.000" end="00:12.000">Hello</span> <span begin="00:12.000" end="00:14.000">world</span></p></div>
              </body>
            </tt>
        """.trimIndent()
        val parsedClock = TtmlLyrics.parseDocument(clockTtml)
        assertEquals(220_046L, parsedClock.durationMs)
        assertTrue(parsedClock.lines.any { it.isWordSynced })

        val secTtml = """<tt><body dur="220s"><div><p begin="1s" end="3s">Line</p></div></body></tt>"""
        assertEquals(220_000L, TtmlLyrics.parseDocument(secTtml).durationMs)

        val msTtml = """<tt><body dur="220500ms"><div><p begin="1000ms" end="3000ms">Line</p></div></body></tt>"""
        assertEquals(220_500L, TtmlLyrics.parseDocument(msTtml).durationMs)
    }

    @Test
    fun `LyricsPlus decodes metadata totalDuration and enforces strict 3_5s guard`() {
        val rawClock = """
            {
              "type": "Syllable",
              "metadata": { "title": "Song", "totalDuration": "3:40" },
              "lyrics": [
                { "time": 10000, "duration": 3000, "text": "Hello world",
                  "syllabus": [
                    { "time": 10000, "duration": 1500, "text": "Hello " },
                    { "time": 11500, "duration": 1500, "text": "world" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val responseClock = lyricsJson.decodeFromString<LyricsPlus.Response>(rawClock)
        assertEquals(220_000L, responseClock.metadata?.totalDurationMs())
        assertNotNull(LyricsPlus.matchingLines(responseClock, 223_500L))
        assertNull(LyricsPlus.matchingLines(responseClock, 223_501L))
        assertNull(LyricsPlus.matchingLines(responseClock, 245_000L))
        assertNull(LyricsPlus.matchingLines(responseClock, 0L))

        val rawMs = """{"metadata":{"totalDuration":220000},"lyrics":[{"time":1000,"duration":2000,"text":"Hi"}]}"""
        val responseMs = lyricsJson.decodeFromString<LyricsPlus.Response>(rawMs)
        assertEquals(220_000L, responseMs.metadata?.totalDurationMs())
        assertNull(LyricsPlus.matchingLines(responseMs, 245_000L))
    }

    @Test
    fun `LrcRed enforces 3_5s guard on search hits and cached ISRC TTML body dur`() = runBlocking {
        val hit = LrcRed.Hit(isrc = "USUM71703861", title = "Song", artist = "Artist", duration = 220.0)
        assertNotNull(LrcRed.best(listOf(hit), "Song", "Artist", 223_500L))
        assertNull(LrcRed.best(listOf(hit), "Song", "Artist", 223_600L))
        assertNull(LrcRed.best(listOf(hit), "Song", "Artist", 245_000L))
        assertNull(LrcRed.best(listOf(hit.copy(duration = null)), "Song", "Artist", 220_000L))
        assertNull(LrcRed.best(listOf(hit), "Song", "Artist", 0L))

        val studioTtml = """<tt><body dur="03:40.000"><div><p begin="00:10.000" end="00:14.000">Verse</p></div></body></tt>"""
        assertNotNull(LrcRed.document("https://lrc.red/s/USUM71703861.ttml", 223_500L) { studioTtml })
        assertNull(LrcRed.document("https://lrc.red/s/USUM71703861.ttml", 223_501L) { studioTtml })
        assertNull(LrcRed.document("https://lrc.red/s/USUM71703861.ttml", 245_000L) { studioTtml })
    }

    @Test
    fun `BiniLyrics enforces 3_5s guard on search ISRC hits and TTML document`() = runBlocking {
        val hit = BiniLyrics.Hit(
            trackName = "Song",
            artistName = "Artist",
            duration = 220,
            isrc = "USUM71703861",
            lyricsUrl = "https://lrc.red/s/USUM71703861.ttml",
        )
        assertTrue(BiniLyrics.hitMatchesDuration(hit, 223_500L))
        assertFalse(BiniLyrics.hitMatchesDuration(hit, 223_501L))
        assertNull(BiniLyrics.best(listOf(hit), "Song", "Artist", 245_000L))

        val ttml = """<tt><body dur="03:40.000"><div><p begin="00:10.000" end="00:14.000">Verse</p></div></body></tt>"""
        assertNotNull(BiniLyrics.lyricsFor(hit.copy(duration = null), 222_000L) { ttml })
        assertNull(BiniLyrics.lyricsFor(hit.copy(duration = null), 245_000L) { ttml })
    }

    @Test
    fun `BetterLyrics and Portato reject responses outside 3_5s drift`() {
        val ttmlPayload = """{"ttml":"<tt><body dur=\"03:40.000\"><div><p begin=\"00:12.000\" end=\"00:15.000\">Hello</p></div></body></tt>"}"""
        assertNotNull(BetterLyrics.matchingLines(ttmlPayload, 223_500L))
        assertNull(BetterLyrics.matchingLines(ttmlPayload, 223_501L))
        assertNull(BetterLyrics.matchingLines(ttmlPayload, 245_000L))
        assertNull(BetterLyrics.matchingLines(ttmlPayload, 0L))

        val portatoPayload = """{"lyrics":"[total:220000]\n[10000,3000]Hello(10000,1500) world(11500,1500)"}"""
        assertNotNull(BetterLyrics.matchingLines(portatoPayload, 220_000L))
        assertNull(BetterLyrics.matchingLines(portatoPayload, 245_000L))
    }

    @Test
    fun `Unison rejects studio durations on longer music videos and untimed cross-video plain entries`() {
        val ttmlEntry = Unison.Entry(
            videoId = "studio_id",
            duration = 220.0,
            format = "ttml",
            syncType = "wordsync",
            lyrics = """<tt><body dur="03:40.000"><div><p begin="00:10.000" end="00:14.000">Hello</p></div></body></tt>""",
        )
        assertNotNull(Unison.matchingLinesOf(ttmlEntry, 223_500L, videoId = "studio_id", isVideo = false))
        assertNull(Unison.matchingLinesOf(ttmlEntry, 223_501L, videoId = "video_id", isVideo = true))
        assertNull(Unison.matchingLinesOf(ttmlEntry, 245_000L, videoId = "video_id", isVideo = true))

        val plainEntry = Unison.Entry(
            videoId = "studio_id",
            format = "lrc",
            syncType = "plain",
            lyrics = "First line\nSecond line",
        )
        assertNull(Unison.matchingLinesOf(plainEntry, 245_000L, videoId = "video_id", isVideo = true))
        assertNotNull(Unison.matchingLinesOf(plainEntry, 245_000L, videoId = "studio_id", isVideo = false))
    }

    @Test
    fun `PaxSenix Apple Spotify and Musixmatch routes enforce strict 3_5s candidate and response guards`() {
        val appleSearch = """[{"id":"apple_1","trackName":"Song","artistName":"Artist","durationInMillis":220000}]"""
        assertEquals("apple_1", PaxSenix.selectBestCandidateId(appleSearch, "Song", "Artist", 223_500L))
        assertNull(PaxSenix.selectBestCandidateId(appleSearch, "Song", "Artist", 223_501L))
        assertNull(PaxSenix.selectBestCandidateId(appleSearch, "Song", "Artist", 245_000L))

        val spotifySearch = """{"tracks":{"items":[{"id":"sp_1","name":"Song","artists":[{"name":"Artist"}],"duration_ms":220000}]}}"""
        assertEquals("sp_1", PaxSenix.selectBestCandidateId(spotifySearch, "Song", "Artist", 220_000L))
        assertNull(PaxSenix.selectBestCandidateId(spotifySearch, "Song", "Artist", 245_000L))

        val mxmSearch = """[{"track_id":"mxm_1","track_name":"Song","artist_name":"Artist","track_length":220}]"""
        assertEquals("mxm_1", PaxSenix.selectBestCandidateId(mxmSearch, "Song", "Artist", 223_500L))
        assertNull(PaxSenix.selectBestCandidateId(mxmSearch, "Song", "Artist", 223_501L))

        val lrcGetJson = """
            {
              "lyrics": [
                {
                  "id": "cand_1",
                  "trackName": "Song",
                  "artistName": "Artist",
                  "duration": 220,
                  "syncedLyrics": "[00:10.00]Hello\n[03:38.00]Goodbye"
                }
              ]
            }
        """.trimIndent()
        assertNotNull(PaxSenix.parseLrcGet(lrcGetJson, "Song", "Artist", 222_000L))
        assertNull(PaxSenix.parseLrcGet(lrcGetJson, "Song", "Artist", 245_000L))
    }

    @Test
    fun `KuGou LrcLib and Musixmatch reject candidates past 3_5s drift`() {
        val kugouSongs = """{"data":{"info":[{"hash":"ok_hash","duration":223},{"hash":"drift_hash","duration":224}]}}"""
        assertEquals(listOf("ok_hash"), KuGou.filterSongHashes(kugouSongs, 220_000L))
        assertTrue(KuGou.filterSongHashes(kugouSongs, 245_000L).orEmpty().isEmpty())

        val kugouCandidates = """{"candidates":[{"id":"1","accesskey":"k1","duration":220000},{"id":"2","accesskey":"k2"}]}"""
        assertEquals(1, KuGou.filterCandidates(kugouCandidates, 222_000L, requireDuration = true)?.size)
        assertTrue(KuGou.filterCandidates(kugouCandidates, 245_000L, requireDuration = true).orEmpty().isEmpty())

        val lrcLibExact = """{"duration":220.0,"syncedLyrics":"[00:10.00]Hello"}"""
        assertNotNull(LrcLib.parseExactMatch(lrcLibExact, 223_500L))
        assertNull(LrcLib.parseExactMatch(lrcLibExact, 223_501L))
        assertNull(LrcLib.parseExactMatch(lrcLibExact, 245_000L))

        val lrcLibSearch = """[{"duration":220.0,"syncedLyrics":"[00:10.00]Hello"}]"""
        assertNotNull(LrcLib.selectBestSearchHit(lrcLibSearch, 223_500L))
        assertNull(LrcLib.selectBestSearchHit(lrcLibSearch, 223_600L))

        val mxmTracks = """
            {
              "message": {
                "header": { "status_code": 200 },
                "body": {
                  "track_list": [
                    { "track": { "track_id": 99, "track_name": "Song", "artist_name": "Artist", "track_length": 220 } }
                  ]
                }
              }
            }
        """.trimIndent()
        assertNotNull(Musixmatch.selectBestTrackFromJson(mxmTracks, "Song", "Artist", 223_500L))
        assertNull(Musixmatch.selectBestTrackFromJson(mxmTracks, "Song", "Artist", 223_501L))
        assertNull(Musixmatch.selectBestTrackFromJson(mxmTracks, "Song", "Artist", 245_000L))
    }

    @Test
    fun `VersionTimeMap remapping shifts words syllables and background vocals clamps skit ends and rebuilds gaps`() {
        // Studio 0..100s maps to Video 25..125s (25s spoken intro),
        // Video 125..160s is a 35s mid-video skit,
        // Studio 100..220s maps to Video 160..280s.
        val timeMap = VersionTimeMap(
            listOf(
                VersionTimeMap.Segment(fromStartMs = 0L, toStartMs = 25_000L, durationMs = 100_000L),
                VersionTimeMap.Segment(fromStartMs = 100_000L, toStartMs = 160_000L, durationMs = 120_000L),
            ),
        )

        val studioLines = listOf(
            // Stale studio intro gap should be stripped and rebuilt from video timestamps.
            LyricLine(timeMs = 0L, text = ""),
            LyricLine(
                timeMs = 10_000L,
                text = "Opening",
                words = listOf(
                    LyricWord(
                        startMs = 10_000L,
                        endMs = 12_000L,
                        text = "Opening",
                        syllables = listOf(
                            LyricSyllable(10_000L, 11_000L, 0, 4),
                            LyricSyllable(11_000L, 12_000L, 4, 7),
                        ),
                    ),
                ),
                sungUntilMs = 12_000L,
                background = LyricLine(
                    timeMs = 10_500L,
                    text = "yeah",
                    words = listOf(LyricWord(10_500L, 11_500L, "yeah")),
                    sungUntilMs = 11_500L,
                ),
            ),
            // Line right before the segment boundary whose tail crosses 100_000ms in studio time.
            LyricLine(
                timeMs = 99_000L,
                text = "Hold on",
                words = listOf(
                    LyricWord(99_000L, 99_500L, "Hold"),
                    LyricWord(
                        startMs = 99_500L,
                        endMs = 100_800L,
                        text = "on",
                        syllables = listOf(
                            LyricSyllable(99_500L, 99_900L, 0, 1),
                            LyricSyllable(99_900L, 100_800L, 1, 2),
                        ),
                    ),
                ),
                sungUntilMs = 100_800L,
            ),
            // Verse after the skit.
            LyricLine(
                timeMs = 100_500L,
                text = "Back again",
                words = listOf(
                    LyricWord(100_500L, 101_200L, "Back"),
                    LyricWord(101_200L, 102_000L, "again"),
                ),
                sungUntilMs = 102_000L,
            ),
        )

        val remapped = studioLines.remapped(timeMap)

        // Rebuilt 25s spoken-intro gap at 0ms.
        assertTrue(remapped.first().isGap)
        assertEquals(0L, remapped.first().timeMs)

        val opening = remapped.first { it.text == "Opening" }
        assertEquals(35_000L, opening.timeMs)
        assertEquals(37_000L, opening.endMs)
        assertEquals(35_000L, opening.words.single().syllables[0].startMs)
        assertEquals(36_000L, opening.words.single().syllables[1].startMs)
        assertEquals(35_500L, opening.background?.timeMs)
        assertEquals(36_500L, opening.background?.words?.single()?.endMs)

        // Pre-skit word/syllable/line end clamped to segment boundary 125_000ms (never stretches across 125s..160s skit).
        val beforeSkit = remapped.first { it.text == "Hold on" }
        assertEquals(124_000L, beforeSkit.timeMs)
        assertEquals(125_000L, beforeSkit.sungUntilMs)
        assertEquals(125_000L, beforeSkit.words[1].endMs)
        assertEquals(125_000L, beforeSkit.words[1].syllables[1].endMs)

        // Rebuilt instrumental/skit gap at 125_000ms before the 160_500ms verse!
        assertTrue(remapped.any { it.isGap && it.timeMs == 125_000L })

        val afterSkit = remapped.first { it.text == "Back again" }
        assertEquals(160_500L, afterSkit.timeMs)
        assertEquals(162_000L, afterSkit.endMs)
    }

    @Test
    fun `LyricsRepository skips studio providers and leaves ISRC cache untouched when durationMs is non-positive`() = runBlocking {
        val queried = mutableListOf<LyricsSource>()
        var identifyCalls = 0

        val result = LyricsRepository.lyricsInternal(
            videoId = "track_unknown_dur",
            title = "Song",
            artist = "Artist",
            durationMs = 0L,
            sources = setOf(LyricsSource.BINI_LYRICS, LyricsSource.BETTER_LYRICS, LyricsSource.YOUTUBE_TRANSCRIPT),
            order = listOf(LyricsSource.BINI_LYRICS, LyricsSource.BETTER_LYRICS, LyricsSource.YOUTUBE_TRANSCRIPT),
            overrides = LyricsRepository.Overrides(
                identifyOverride = { _, _, _, _ ->
                    identifyCalls++
                    BiniLyrics.Hit(isrc = "USUM71703861")
                },
                providerFetcher = { source, _, _, _, _, _, _, _ ->
                    queried += source
                    listOf(LyricLine(timeMs = 5_000L, text = "Video caption"))
                },
            ),
        )

        assertEquals(0, identifyCalls)
        assertEquals(listOf(LyricsSource.YOUTUBE_TRANSCRIPT), queried)
        assertEquals(LyricsSource.YOUTUBE_TRANSCRIPT, result?.source)
        assertNull(LyricsRepository.cachedIsrcForTest("track_unknown_dur"))
    }

    @Test
    fun `LyricsRepository resolves video counterpart remaps with VersionTimeMap and isolates ISRC to studio videoId`() = runBlocking {
        val studioSong = Song(
            videoId = "studio_atv_id",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
            durationText = "3:40",
            isVideo = false,
        )
        val timeMap = VersionTimeMap(
            listOf(VersionTimeMap.Segment(fromStartMs = 0L, toStartMs = 25_000L, durationMs = 220_000L)),
        )
        var studioQueriedDuration = -1L

        val result = LyricsRepository.lyricsInternal(
            videoId = "music_video_omv_id",
            title = "Song (Official Music Video)",
            artist = "Artist",
            durationMs = 245_000L,
            sources = setOf(LyricsSource.BINI_LYRICS, LyricsSource.BETTER_LYRICS),
            order = listOf(LyricsSource.BETTER_LYRICS, LyricsSource.BINI_LYRICS),
            prioritizeSyllableSync = true,
            isVideo = true,
            overrides = LyricsRepository.Overrides(
                counterpartResolver = { studioSong },
                timeMapLookup = { fromId, toId ->
                    if (fromId == "studio_atv_id" && toId == "music_video_omv_id") timeMap else null
                },
                identifyOverride = { _, _, dur, _ ->
                    studioQueriedDuration = dur
                    BiniLyrics.Hit(
                        trackName = "Song",
                        artistName = "Artist",
                        duration = 220,
                        isrc = "USUM71703861",
                    )
                },
                providerFetcher = { source, vid, _, _, dur, _, isrc, isVideoFlag ->
                    if (source == LyricsSource.BETTER_LYRICS) {
                        assertEquals("studio_atv_id", vid)
                        assertEquals(220_000L, dur)
                        assertEquals("USUM71703861", isrc)
                        assertFalse(isVideoFlag)
                        listOf(
                            LyricLine(
                                timeMs = 10_000L,
                                text = "Studio syllable",
                                words = listOf(LyricWord(10_000L, 11_500L, "Studio"), LyricWord(11_500L, 13_000L, "syllable")),
                            ),
                        )
                    } else null
                },
            ),
        )

        assertNotNull(result)
        assertEquals(LyricsSource.BETTER_LYRICS, result.source)
        assertEquals(220_000L, studioQueriedDuration)
        // ISRC cached under studio_atv_id, NEVER under music_video_omv_id!
        assertEquals("USUM71703861", LyricsRepository.cachedIsrcForTest("studio_atv_id"))
        assertNull(LyricsRepository.cachedIsrcForTest("music_video_omv_id"))
        // Remapped +25s onto video timeline with rebuilt 0ms intro gap.
        assertTrue(result.lines.first().isGap)
        val firstSung = result.lines.first { !it.isGap }
        assertEquals(35_000L, firstSung.timeMs)
        assertEquals(38_000L, firstSung.words.last().endMs)
    }

    @Test
    fun `LyricsRepository shifts via audio alignmentResolver and falls back to YouTubeLyrics when alignment returns null`() = runBlocking {
        val studioSong = Song(
            videoId = "studio_atv_id",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
            durationText = "3:40",
            isVideo = false,
        )

        // 1. Audio alignment succeeds (+25_000ms intro offset): studio source wins and is shifted.
        val aligned = LyricsRepository.lyricsInternal(
            videoId = "music_video_omv_id",
            title = "Song (Official Video)",
            artist = "Artist",
            durationMs = 245_000L,
            sources = setOf(LyricsSource.LRCLIB, LyricsSource.YOUTUBE_TRANSCRIPT),
            order = listOf(LyricsSource.LRCLIB, LyricsSource.YOUTUBE_TRANSCRIPT),
            isVideo = true,
            alignmentResolver = { _, _ -> 25_000L },
            overrides = LyricsRepository.Overrides(
                counterpartResolver = { studioSong },
                timeMapLookup = { _, _ -> null },
                providerFetcher = { source, _, _, _, dur, _, _, _ ->
                    when (source) {
                        LyricsSource.LRCLIB -> {
                            assertEquals(220_000L, dur)
                            listOf(LyricLine(timeMs = 12_000L, text = "Synced studio line"))
                        }
                        else -> listOf(LyricLine(timeMs = 37_000L, text = "YT caption"))
                    }
                },
            ),
        )
        assertEquals(LyricsSource.LRCLIB, aligned?.source)
        assertEquals(37_000L, aligned?.lines?.first { !it.isGap }?.timeMs)

        // 2. Manual single-provider selection also remaps when aligned!
        val manualPick = LyricsRepository.lyricsInternal(
            videoId = "music_video_omv_id",
            title = "Song (Official Video)",
            artist = "Artist",
            durationMs = 245_000L,
            sources = setOf(LyricsSource.LYRICS_PLUS),
            order = listOf(LyricsSource.LYRICS_PLUS),
            isVideo = true,
            alignmentResolver = { _, _ -> 25_000L },
            overrides = LyricsRepository.Overrides(
                counterpartResolver = { studioSong },
                timeMapLookup = { _, _ -> null },
                providerFetcher = { _, _, _, _, dur, _, _, _ ->
                    assertEquals(220_000L, dur)
                    listOf(LyricLine(timeMs = 8_000L, text = "Manual pick", words = listOf(LyricWord(8_000L, 9_500L, "Manual"))))
                },
            ),
        )
        assertEquals(LyricsSource.LYRICS_PLUS, manualPick?.source)
        assertEquals(33_000L, manualPick?.lines?.first { !it.isGap }?.timeMs)

        // 3. Alignment returns null on 245s video vs 220s studio cut: studio rejects on 245s guard, YouTube fallback wins!
        val fallback = LyricsRepository.lyricsInternal(
            videoId = "music_video_omv_id",
            title = "Song (Official Video)",
            artist = "Artist",
            durationMs = 245_000L,
            sources = setOf(LyricsSource.BETTER_LYRICS, LyricsSource.LRCLIB, LyricsSource.YOUTUBE_TRANSCRIPT, LyricsSource.YOUTUBE_MUSIC),
            order = listOf(LyricsSource.BETTER_LYRICS, LyricsSource.LRCLIB, LyricsSource.YOUTUBE_TRANSCRIPT, LyricsSource.YOUTUBE_MUSIC),
            prioritizeSyllableSync = true,
            isVideo = true,
            alignmentResolver = { _, _ -> null },
            overrides = LyricsRepository.Overrides(
                counterpartResolver = { studioSong },
                timeMapLookup = { _, _ -> null },
                providerFetcher = { source, vid, _, _, dur, _, _, _ ->
                    when (source) {
                        LyricsSource.BETTER_LYRICS -> {
                            // Unaligned video queries studio provider with video's 245_000ms, which rejects a 220s studio cut!
                            assertEquals(245_000L, dur)
                            BetterLyrics.matchingLines(
                                """{"ttml":"<tt><body dur=\"03:40.000\"><div><p begin=\"00:10.000\" end=\"00:13.000\">Studio</p></div></body></tt>"}""",
                                dur,
                            )
                        }
                        LyricsSource.LRCLIB -> {
                            assertEquals(245_000L, dur)
                            LrcLib.parseExactMatch("""{"duration":220.0,"syncedLyrics":"[00:10.00]Studio"}""", dur)
                                ?.let(LrcLib::parseLrc)
                        }
                        LyricsSource.YOUTUBE_TRANSCRIPT -> {
                            assertEquals("music_video_omv_id", vid)
                            listOf(LyricLine(timeMs = 35_000L, text = "Video transcript line"))
                        }
                        else -> null
                    }
                },
            ),
        )
        assertNotNull(fallback)
        assertEquals(LyricsSource.YOUTUBE_TRANSCRIPT, fallback.source)
        assertEquals(35_000L, fallback.lines.single().timeMs)
        assertNull(LyricsRepository.cachedIsrcForTest("studio_atv_id"))
        assertNull(LyricsRepository.cachedIsrcForTest("music_video_omv_id"))
    }
}
