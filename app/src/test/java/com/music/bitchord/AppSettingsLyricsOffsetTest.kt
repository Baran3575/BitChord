package com.music.bitchord

import android.content.SharedPreferences
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.playback.lyricSubtitleFor
import com.music.bitchord.ui.player.LyricsShareLine
import com.music.bitchord.ui.player.shareTimeline
import com.music.bitchord.ui.player.shiftedShareAudioSegments
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsLyricsOffsetTest {

    @Test
    fun `per-track offsets isolate videoIds, remove zero entries, and clamp to 60 seconds`() = runTest {
        val prefs = InMemorySharedPreferences()
        AppSettings.initForTest(prefs)

        val observedA = mutableListOf<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            AppSettings.lyricsOffsetMsFlow("video_a").collect { observedA += it }
        }

        AppSettings.setLyricsOffsetMs("video_a", 35_000)
        assertEquals(35_000, AppSettings.lyricsOffsetMs("video_a"))
        assertEquals(0, AppSettings.lyricsOffsetMs("video_b"))
        assertTrue(prefs.contains("lyrics_offsets_ms"))

        // Updating another track does not emit on video_a's distinct flow.
        AppSettings.setLyricsOffsetMs("video_b", -75_000)
        assertEquals(-60_000, AppSettings.lyricsOffsetMs("video_b"))
        assertEquals(listOf(0, 35_000), observedA)

        // Resetting both tracks removes the preference key completely.
        AppSettings.setLyricsOffsetMs("video_a", 0)
        AppSettings.setLyricsOffsetMs("video_b", 0)
        assertFalse(prefs.contains("lyrics_offsets_ms"))
        assertEquals(listOf(0, 35_000, 0), observedA)
        job.cancel()
    }

    @Test
    fun `legacy global lyrics_offset_ms key is ignored on upgrade and import`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putInt("lyrics_offset_ms", 4_200).apply()

        AppSettings.initForTest(prefs)
        assertTrue(AppSettings.lyricsOffsetsMs.value.isEmpty())
        assertEquals(0, AppSettings.lyricsOffsetMs("any_song"))

        AppSettings.importPrefs(mapOf("lyrics_offset_ms" to 3_500))
        assertTrue(AppSettings.lyricsOffsetsMs.value.isEmpty())
        assertEquals(0, AppSettings.lyricsOffsetMs("any_song"))
    }

    @Test
    fun `lru order persists across reload and evicts oldest entry past 500 tracks`() {
        val prefs = InMemorySharedPreferences()
        AppSettings.initForTest(prefs)

        for (i in 1..AppSettings.MAX_LYRICS_OFFSET_ENTRIES) {
            AppSettings.setLyricsOffsetMs("song_$i", 1_200)
        }
        // Touch song_1 with the same offset value to verify recency order still updates on disk.
        AppSettings.setLyricsOffsetMs("song_1", 1_200)

        AppSettings.reload()
        AppSettings.setLyricsOffsetMs("song_501", -2_400)

        assertEquals(AppSettings.MAX_LYRICS_OFFSET_ENTRIES, AppSettings.lyricsOffsetsMs.value.size)
        assertEquals(1_200, AppSettings.lyricsOffsetMs("song_1"))
        assertEquals(0, AppSettings.lyricsOffsetMs("song_2"))
        assertEquals(-2_400, AppSettings.lyricsOffsetMs("song_501"))
    }

    @Test
    fun `backup export and import round-trip per-track offsets under schema v1`() {
        val sourcePrefs = InMemorySharedPreferences()
        AppSettings.initForTest(sourcePrefs)
        AppSettings.setLyricsOffsetMs("yt_intro_video", 35_000)
        AppSettings.setLyricsOffsetMs("content://media/external/audio/media/99", -2_300)

        val json = Json { ignoreUnknownKeys = true }
        val backup = Backup.BackupFile(
            versionName = "test",
            exportedAt = "2026-11-24T00:00:00Z",
            settings = AppSettings.exportPrefs().mapNotNull { (k, v) ->
                Backup.PrefValue.of(v)?.let { k to it }
            }.toMap(),
        )
        assertEquals(1, backup.version)
        assertTrue("lyrics_offsets_ms" in backup.settings)

        val encoded = json.encodeToString(Backup.BackupFile.serializer(), backup)
        val targetPrefs = InMemorySharedPreferences()
        AppSettings.initForTest(targetPrefs)
        assertEquals(0, AppSettings.lyricsOffsetMs("yt_intro_video"))

        val restored = json.decodeFromString(Backup.BackupFile.serializer(), encoded)
        AppSettings.importPrefs(restored.settings.mapValues { it.value.decoded() })

        assertEquals(35_000, AppSettings.lyricsOffsetMs("yt_intro_video"))
        assertEquals(-2_300, AppSettings.lyricsOffsetMs("content://media/external/audio/media/99"))
    }

    @Test
    fun `lock-screen subtitle applies track offset including negative pre-roll`() {
        val lines = listOf(
            LyricLine(timeMs = 0L, text = "Opening vocal"),
            LyricLine(timeMs = 5_000L, text = "Second vocal"),
        )
        // With +35 s offset, playback at 10 s is at -25 s in lyric time -> shows artist during pre-roll.
        assertEquals(
            "The Artist",
            lyricSubtitleFor(lines, syncedLyrics = true, positionMs = 10_000L, offsetMs = 35_000, artist = "The Artist"),
        )
        // Once playback reaches 35 s, lyric time hits 0 s -> lights the opening vocal.
        assertEquals(
            "♪ Opening vocal",
            lyricSubtitleFor(lines, syncedLyrics = true, positionMs = 35_000L, offsetMs = 35_000, artist = "The Artist"),
        )
        // Negative offset (-2 s) brings the 5 s line forward to 3 s of track time.
        assertEquals(
            "♪ Second vocal",
            lyricSubtitleFor(lines, syncedLyrics = true, positionMs = 3_000L, offsetMs = -2_000, artist = "The Artist"),
        )
    }

    @Test
    fun `share video keeps overlay timeline in lyric time while shifting opus segments by track offset`() {
        val shareLines = listOf(
            LyricsShareLine(text = "Line one", subText = null, startMs = 1_000L, endMs = 4_000L),
            LyricsShareLine(text = "Line two", subText = null, startMs = 4_000L, endMs = 8_000L),
        )
        val timeline = shareTimeline(shareLines)
        assertNotNull(timeline)
        // Overlay timeline remains in unshifted lyric timestamps.
        assertEquals(listOf(1_000L to 8_000L), timeline!!.segments)
        assertEquals(1_000L, timeline.trackMsAt(0L))

        // Opus audio segments shift by +35 s for late music-video intros and clamp at 0 ms for negative offsets.
        assertEquals(
            listOf(36_000L to 43_000L),
            shiftedShareAudioSegments(timeline.segments, offsetMs = 35_000),
        )
        assertEquals(
            listOf(0L to 6_000L),
            shiftedShareAudioSegments(timeline.segments, offsetMs = -2_000),
        )
    }

    @Test
    fun `player settings bridge exposes isolated per-videoId flows and setters`() = runTest {
        val prefs = InMemorySharedPreferences()
        AppSettings.initForTest(prefs)

        val observed = mutableListOf<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            AppSettings.lyricsOffsetMsFlow("bridge_track_a").collect { observed += it }
        }

        assertEquals(0, AppSettings.lyricsOffsetMs(""))
        AppSettings.setLyricsOffsetMs("", 12_000)
        assertTrue(AppSettings.lyricsOffsetsMs.value.isEmpty())

        AppSettings.setLyricsOffsetMs("bridge_track_a", 35_000)
        AppSettings.setLyricsOffsetMs("bridge_track_b", -12_500)
        assertEquals(35_000, AppSettings.lyricsOffsetMs("bridge_track_a"))
        assertEquals(-12_500, AppSettings.lyricsOffsetMs("bridge_track_b"))
        assertEquals(listOf(0, 35_000), observed)

        job.cancel()
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val data = LinkedHashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)

    override fun getString(key: String?, defValue: String?): String? =
        (data[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (data[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        (data[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        (data[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        (data[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (data[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = key in data

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners -= listener
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = values?.toSet()
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = REMOVED
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            val changed = mutableListOf<String>()
            if (clearRequested) {
                changed += data.keys
                data.clear()
            }
            for ((k, v) in pending) {
                if (v === REMOVED || v == null) {
                    data.remove(k)
                } else {
                    data[k] = v
                }
                changed += k
            }
            for (key in changed) {
                listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, key) }
            }
        }
    }

    private object REMOVED
}
