package com.music.bitchord

import androidx.compose.runtime.mutableStateOf
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.data.settings.PlayerTheme
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.player.LYRICS_OFFSET_COARSE_STEP_MS
import com.music.bitchord.ui.player.LYRICS_OFFSET_FINE_STEP_MS
import com.music.bitchord.ui.player.LyricPlayhead
import com.music.bitchord.ui.player.MAX_LYRICS_OFFSET_ENTRIES
import com.music.bitchord.ui.player.MAX_LYRICS_OFFSET_MS
import com.music.bitchord.ui.player.MIN_LYRICS_OFFSET_MS
import com.music.bitchord.ui.player.PlayerSettingsSource
import com.music.bitchord.ui.player.activeLyricRows
import com.music.bitchord.ui.player.adjustedLyricsPosition
import com.music.bitchord.ui.player.adjustedLyricsSeekTarget
import com.music.bitchord.ui.player.decodeLyricsOffsets
import com.music.bitchord.ui.player.encodeLyricsOffsets
import com.music.bitchord.ui.player.formatOffset
import com.music.bitchord.ui.player.fractionToOffset
import com.music.bitchord.ui.player.offsetToFraction
import com.music.bitchord.ui.player.shiftedShareAudioSegments
import com.music.bitchord.ui.player.stepLyricsOffset
import com.music.bitchord.ui.player.updateLyricsOffsets
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsOffsetTest {
    @Test
    fun `positive offset delays lyrics`() {
        assertEquals(8_500L, adjustedLyricsPosition(positionMs = 10_000L, offsetMs = 1_500))
    }

    @Test
    fun `negative offset advances lyrics`() {
        assertEquals(11_500L, adjustedLyricsPosition(positionMs = 10_000L, offsetMs = -1_500))
    }

    @Test
    fun `positive offset produces negative pre-roll before offset has elapsed`() {
        assertEquals(-1_000L, adjustedLyricsPosition(positionMs = 500L, offsetMs = 1_500))
        assertEquals(-35_000L, adjustedLyricsPosition(positionMs = 0L, offsetMs = 35_000))
        assertEquals(-60_000L, adjustedLyricsPosition(positionMs = 0L, offsetMs = MAX_LYRICS_OFFSET_MS))
    }

    @Test
    fun `seeking a lyric includes its offset and clamps negative targets at zero`() {
        assertEquals(11_500L, adjustedLyricsSeekTarget(lineTimeMs = 10_000L, offsetMs = 1_500))
        assertEquals(45_000L, adjustedLyricsSeekTarget(lineTimeMs = 10_000L, offsetMs = 35_000))
        assertEquals(8_500L, adjustedLyricsSeekTarget(lineTimeMs = 10_000L, offsetMs = -1_500))
        assertEquals(0L, adjustedLyricsSeekTarget(lineTimeMs = 500L, offsetMs = -1_500))
        assertEquals(0L, adjustedLyricsSeekTarget(lineTimeMs = 0L, offsetMs = MIN_LYRICS_OFFSET_MS))
    }

    @Test
    fun `active lyric rows remain empty during negative pre-roll even when first line starts at zero`() {
        val lines = listOf(
            LyricLine(timeMs = 0L, text = "First line immediately at LRC zero"),
            LyricLine(timeMs = 4_000L, text = "Second line"),
        )
        assertTrue(activeLyricRows(lines, adjustedLyricsPosition(0L, 35_000)).isEmpty())
        assertTrue(activeLyricRows(lines, adjustedLyricsPosition(34_999L, 35_000)).isEmpty())
        assertEquals(listOf(0), activeLyricRows(lines, adjustedLyricsPosition(35_000L, 35_000)))
    }

    @Test
    fun `per-videoId map isolates tracks, drops zero, and clamps to 60 second bounds`() {
        var map = emptyMap<String, Int>()
        map = updateLyricsOffsets(map, "video_a", 35_000)
        assertEquals(35_000, map["video_a"])
        assertEquals(0, map["video_b"] ?: 0)

        map = updateLyricsOffsets(map, "video_b", -90_000)
        map = updateLyricsOffsets(map, "video_c", 95_000)
        assertEquals(MIN_LYRICS_OFFSET_MS, map["video_b"])
        assertEquals(MAX_LYRICS_OFFSET_MS, map["video_c"])

        map = updateLyricsOffsets(map, "video_a", 0)
        assertFalse("video_a" in map)
        assertEquals(2, map.size)
    }

    @Test
    fun `lru eviction caps map at 500 entries and refreshes touched tracks`() {
        var map: Map<String, Int> = emptyMap()
        for (i in 1..MAX_LYRICS_OFFSET_ENTRIES) {
            map = updateLyricsOffsets(map, "track_$i", 1_000)
        }
        assertEquals(MAX_LYRICS_OFFSET_ENTRIES, map.size)

        // Refresh track_1 so track_2 becomes the oldest entry.
        map = updateLyricsOffsets(map, "track_1", 2_500)
        map = updateLyricsOffsets(map, "track_501", -1_500)

        assertEquals(MAX_LYRICS_OFFSET_ENTRIES, map.size)
        assertEquals(2_500, map["track_1"])
        assertFalse("track_2 should be evicted as oldest", "track_2" in map)
        assertEquals(-1_500, map["track_501"])
    }

    @Test
    fun `json encoding preserves insertion order and arbitrary videoId characters`() {
        val ids = listOf(
            "dQw4w9WgXcQ",
            "content://media/external/audio/media/42",
            "file:///storage/emulated/0/Music/Artist, \"Live\" = #1.flac",
            "addon:tidal:track:12345",
        )
        var map: Map<String, Int> = emptyMap()
        ids.forEachIndexed { index, id ->
            map = updateLyricsOffsets(map, id, (index + 1) * 5_500)
        }

        val encoded = encodeLyricsOffsets(map)
        val decoded = decodeLyricsOffsets(encoded)
        assertEquals(ids, decoded.keys.toList())
        ids.forEachIndexed { index, id ->
            assertEquals((index + 1) * 5_500, decoded[id])
        }

        assertTrue(decodeLyricsOffsets(null).isEmpty())
        assertTrue(decodeLyricsOffsets("").isEmpty())
        assertTrue(decodeLyricsOffsets("not-json").isEmpty())
        assertEquals(
            mapOf("valid" to MAX_LYRICS_OFFSET_MS),
            decodeLyricsOffsets("""{"zero":0,"valid":120000,"bad":"abc"}"""),
        )
    }

    @Test
    fun `sheet slider and fine or coarse step buttons span plus or minus 60 seconds`() {
        assertEquals(100, LYRICS_OFFSET_FINE_STEP_MS)
        assertEquals(1_000, LYRICS_OFFSET_COARSE_STEP_MS)

        assertEquals(35_100, stepLyricsOffset(35_000, LYRICS_OFFSET_FINE_STEP_MS))
        assertEquals(36_000, stepLyricsOffset(35_000, LYRICS_OFFSET_COARSE_STEP_MS))
        assertEquals(MAX_LYRICS_OFFSET_MS, stepLyricsOffset(59_500, LYRICS_OFFSET_COARSE_STEP_MS))
        assertEquals(MIN_LYRICS_OFFSET_MS, stepLyricsOffset(-59_950, -LYRICS_OFFSET_FINE_STEP_MS))

        assertEquals(0f, offsetToFraction(MIN_LYRICS_OFFSET_MS), 0.0001f)
        assertEquals(0.5f, offsetToFraction(0), 0.0001f)
        assertEquals(1f, offsetToFraction(MAX_LYRICS_OFFSET_MS), 0.0001f)

        assertEquals(MIN_LYRICS_OFFSET_MS, fractionToOffset(0f))
        assertEquals(0, fractionToOffset(0.5f))
        assertEquals(MAX_LYRICS_OFFSET_MS, fractionToOffset(1f))
        assertEquals(35_000, fractionToOffset(offsetToFraction(35_000)))
        assertEquals(-35_000, fractionToOffset(offsetToFraction(-35_000)))
    }

    @Test
    fun `shiftedShareAudioSegments shifts audio clips by track offset and clamps negative bounds`() {
        val segments = listOf(500L to 4_000L, 10_000L to 15_000L)
        assertEquals(
            listOf(35_500L to 39_000L, 45_000L to 50_000L),
            shiftedShareAudioSegments(segments, offsetMs = 35_000),
        )
        assertEquals(
            listOf(0L to 2_500L, 8_500L to 13_500L),
            shiftedShareAudioSegments(segments, offsetMs = -1_500),
        )
        assertEquals(
            listOf(5_000L to 10_000L),
            shiftedShareAudioSegments(segments, offsetMs = -5_000),
        )
    }

    @Test
    fun `formatOffset renders unsigned zero and signed non-zero offsets`() {
        val prev = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("0.0 s", formatOffset(0))
            assertEquals("+35.0 s", formatOffset(35_000))
            assertEquals("−35.0 s", formatOffset(-35_000))
            assertEquals("−60.0 s", formatOffset(MIN_LYRICS_OFFSET_MS))
            assertEquals("+60.0 s", formatOffset(MAX_LYRICS_OFFSET_MS))
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `lyric playhead exposes negative pre-roll position and offset discontinuities`() {
        val position = PlaybackPosition()
        val offset = mutableStateOf(35_000)
        val playhead = LyricPlayhead(position, offset)

        position.report(positionMs = 1_200L, sampledAtNanos = 2_000_000_000L, isPlaying = true)
        assertEquals(-33_800L, playhead.positionMs)
        val d1 = playhead.discontinuity

        offset.value = -1_500
        assertEquals(2_700L, playhead.positionMs)
        val d2 = playhead.discontinuity
        assertNotEquals(d1, d2)

        position.seeks++
        position.report(positionMs = 5_000L, sampledAtNanos = 3_000_000_000L, isPlaying = true)
        val d3 = playhead.discontinuity
        assertNotEquals(d2, d3)
    }

    @Test
    fun `PlayerSettingsSource lookup and flow isolate per-videoId offsets`() = runBlocking {
        val settings = FakePlayerSettingsSource()
        assertEquals(0, settings.lyricsOffsetMs(""))
        assertEquals(0, settings.lyricsOffsetMsFlow("").first())
        assertEquals(0, settings.lyricsOffsetMs("track_a"))

        val collected = mutableListOf<Int>()
        val job = launch {
            settings.lyricsOffsetMsFlow("track_a").take(3).toList(collected)
        }
        yield()

        settings.setLyricsOffsetMs("track_a", 35_000)
        yield()
        // Changing another track must not emit on track_a's distinct flow.
        settings.setLyricsOffsetMs("track_b", -4_500)
        yield()
        assertEquals(35_000, settings.lyricsOffsetMs("track_a"))
        assertEquals(-4_500, settings.lyricsOffsetMs("track_b"))

        settings.setLyricsOffsetMs("track_a", 0)
        job.join()
        assertEquals(listOf(0, 35_000, 0), collected)
        assertFalse("track_a" in settings.lyricsOffsetsMs.value)
    }

    private class FakePlayerSettingsSource : PlayerSettingsSource {
        override val animatedCanvas = MutableStateFlow(true)
        override val audioQualityCellular = MutableStateFlow(AudioQuality.HIGH)
        override val audioQualityWifi = MutableStateFlow(AudioQuality.HIGH)
        override val canvasOverCellular = MutableStateFlow(false)
        override val fullBleedArtwork = MutableStateFlow(true)
        override val showTotalDuration = MutableStateFlow(false)
        override val hideSongStatus = MutableStateFlow(false)
        override val hideLyricsPreview = MutableStateFlow(false)
        override val showQueueHistory = MutableStateFlow(false)
        override val hideVolumeBar = MutableStateFlow(false)
        override val hideStatusBar = MutableStateFlow(false)
        override val lastPlayerScreen = MutableStateFlow(LastPlayerScreen.MAIN)
        override val playerTheme = MutableStateFlow(PlayerTheme.DEFAULT)
        override val lyricsBlur = MutableStateFlow(true)
        override val lyricsOffsetsMs = MutableStateFlow<Map<String, Int>>(emptyMap())
        override val lyricsSourceOrder = MutableStateFlow(LyricsSource.offered)
        override val meteredConnection = MutableStateFlow<Boolean?>(false)
        override val streamCeilingOverride = MutableStateFlow<AudioQuality?>(null)
        override val preferUsbDac = MutableStateFlow(false)
        override val disableSpotifyCanvas = MutableStateFlow(false)
        override val prioritizeSpotifyCanvas = MutableStateFlow(false)
        override val reduceAnimation = MutableStateFlow(false)
        override val reduceDynamicBlur = MutableStateFlow(false)
        override val disableBlendEffects = MutableStateFlow(false)
        override val showNerdStats = MutableStateFlow(false)
        override val showBestPart = MutableStateFlow(true)
        override val showListenTogetherButton = MutableStateFlow(false)
        override val smartAnalysis = MutableStateFlow(SmartAnalysis())
        override val automixDetail = MutableStateFlow<String?>(null)
        override val smartFadeEnabled = MutableStateFlow(false)
        override val smartMixInProgress = MutableStateFlow(false)
        override val smartMixBlend = MutableStateFlow<MixBlend?>(null)
        override val smartMixView = MutableStateFlow<MixTransitionView?>(null)
        override val smartMixProgress = MutableStateFlow(0f)
        override val smartMixPreview = MutableStateFlow<MixTransitionView?>(null)
        override val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)
        override val spotifyCanvasAutoHide = MutableStateFlow(true)
        override val syncedLyrics = MutableStateFlow(true)
        override val translationLanguage = MutableStateFlow("")
        override val versionAlignmentInProgress = MutableStateFlow(false)

        private var order = LinkedHashMap<String, Int>()

        override fun setLastPlayerScreen(value: LastPlayerScreen) {
            lastPlayerScreen.value = value
        }

        override fun setLyricsOffsetMs(videoId: String, value: Int) {
            order = updateLyricsOffsets(order, videoId, value)
            lyricsOffsetsMs.value = LinkedHashMap(order)
        }

        override fun setMixViewOpen(open: Boolean) = Unit
    }
}
