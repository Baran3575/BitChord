package com.music.bitchord

import android.content.ContextWrapper
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.lyricsSettingChanges
import com.music.bitchord.playback.smart.VersionAudioAligner
import com.music.bitchord.ui.lyricsLookupKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VersionLyricsAlignmentTest {

    private val fakeContext = ContextWrapper(null)

    private val studioSong = Song(
        videoId = "studio_atv",
        title = "Track",
        artist = "Artist",
        thumbnailUrl = null,
        durationText = "3:40",
        isVideo = false,
    )

    private val videoSong = Song(
        videoId = "video_omv",
        title = "Track (Official Video)",
        artist = "Artist",
        thumbnailUrl = null,
        durationText = "4:05",
        isVideo = true,
    )

    @Before
    fun setUp() {
        VersionAudioAligner.clearCacheForTest()
    }

    @After
    fun tearDown() {
        VersionAudioAligner.clearCacheForTest()
    }

    @Test
    fun `resolveLyricsOffsetMs skips measurement completely when smartVersionAlignment is false`() = runBlocking {
        VersionAudioAligner.cacheOffsetForTest("studio_atv", "video_omv", 25_000L)
        var measured = false

        val offset = VersionAudioAligner.resolveLyricsOffsetMs(
            context = fakeContext,
            studioSong = studioSong,
            videoSong = videoSong,
            smartAlignmentEnabled = false,
            measure = { _, _ ->
                measured = true
                25_000L
            },
        )

        assertNull(offset)
        assertEquals(false, measured)
    }

    @Test
    fun `resolveLyricsOffsetMs returns cached studio-to-video offset or invokes measurement when enabled`() = runBlocking {
        var measuredCalls = 0
        val measuredOffset = VersionAudioAligner.resolveLyricsOffsetMs(
            context = fakeContext,
            studioSong = studioSong,
            videoSong = videoSong,
            smartAlignmentEnabled = true,
            measure = { from, to ->
                measuredCalls++
                assertEquals("studio_atv", from.videoId)
                assertEquals("video_omv", to.videoId)
                25_000L
            },
        )
        assertEquals(25_000L, measuredOffset)
        assertEquals(1, measuredCalls)

        // When cached in VersionAudioAligner, returns immediately without invoking measure again.
        VersionAudioAligner.cacheOffsetForTest("studio_atv", "video_omv", 24_800L)
        val cachedOffset = VersionAudioAligner.resolveLyricsOffsetMs(
            context = fakeContext,
            studioSong = studioSong,
            videoSong = videoSong,
            smartAlignmentEnabled = true,
            measure = { _, _ ->
                measuredCalls++
                99_000L
            },
        )
        assertEquals(24_800L, cachedOffset)
        assertEquals(1, measuredCalls)
    }

    @Test
    fun `lyricsLookupKey and lyricsSettingChanges react to isVideo and smartVersionAlignment`() = runTest {
        val sources = setOf(LyricsSource.BETTER_LYRICS, LyricsSource.YOUTUBE_TRANSCRIPT)
        val order = listOf(LyricsSource.BETTER_LYRICS, LyricsSource.YOUTUBE_TRANSCRIPT)

        val audioKey = lyricsLookupKey("vid1", sources, order, prioritizeSyllableSync = true, isVideo = false, smartVersionAlignment = true)
        val videoKey = lyricsLookupKey("vid1", sources, order, prioritizeSyllableSync = true, isVideo = true, smartVersionAlignment = true)
        val unalignedVideoKey = lyricsLookupKey("vid1", sources, order, prioritizeSyllableSync = true, isVideo = true, smartVersionAlignment = false)

        assertNotEquals(audioKey, videoKey)
        assertNotEquals(videoKey, unalignedVideoKey)

        val synced = MutableStateFlow(true)
        val sourcesFlow = MutableStateFlow(sources)
        val orderFlow = MutableStateFlow(order)
        val prioritize = MutableStateFlow(true)
        val smartAlign = MutableStateFlow(true)
        var emissions = 0

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            lyricsSettingChanges(synced, sourcesFlow, orderFlow, prioritize, smartAlign).collect { emissions++ }
        }

        smartAlign.value = false
        smartAlign.value = false // duplicate ignored
        smartAlign.value = true

        assertEquals(3, emissions)
        job.cancel()
    }
}
