package com.music.bitchord

import android.content.SharedPreferences
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.addon.AddonAuth
import com.music.bitchord.ui.LyricsProviderState
import com.music.bitchord.ui.LyricsSelectionAction
import com.music.bitchord.ui.lyricsSelectionAction
import com.music.bitchord.ui.player.isLyricsProviderRowEnabled
import com.music.bitchord.ui.shouldRetryLyricsWithBackfilledMetadata
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMetadataAndSettingsTest {

    // ---- Requirement 3: Embedded ISRC extraction across FLAC, WebM/Matroska, MP4, ID3 ----

    @Test
    fun `extracts and normalizes ISRC from FLAC Vorbis comments`() {
        val flac = flacWithComment("ISRC=GB-AYE-97-01374")
        assertEquals("GBAYE9701374", EmbeddedLyrics.isrcFromBytes(flac))
        assertNull(EmbeddedLyrics.isrcFromBytes(flacWithComment("TITLE=Let Down")))
    }

    @Test
    fun `extracts and normalizes ISRC from Matroska and WebM SimpleTag`() {
        val webm = webmWithSimpleTag("ISRC", "gbaye9701374")
        assertEquals("GBAYE9701374", EmbeddedLyrics.isrcFromBytes(webm))
        assertNull(EmbeddedLyrics.isrcFromBytes(webmWithSimpleTag("TITLE", "Let Down")))
    }

    @Test
    fun `extracts and normalizes ISRC from MP4 freeform ISRC and standard isr atoms`() {
        val freeformMp4 = mp4WithFreeformIsrc("GB-AYE-97-01374")
        assertEquals("GBAYE9701374", EmbeddedLyrics.isrcFromBytes(freeformMp4))

        val isrMp4 = mp4WithStandardIsr("USUG11904206")
        assertEquals("USUG11904206", EmbeddedLyrics.isrcFromBytes(isrMp4))
    }

    @Test
    fun `extracts and normalizes ISRC from ID3v2 TSRC TRC and TXXX frames`() {
        assertEquals("GBAYE9701374", EmbeddedLyrics.isrcFromBytes(id3v2TextFrame(major = 4, id = "TSRC", value = "GB-AYE-97-01374")))
        assertEquals("GBAYE9701374", EmbeddedLyrics.isrcFromBytes(id3v22TrcFrame("GBAYE9701374")))
        assertEquals("QZXDB2300005", EmbeddedLyrics.isrcFromBytes(id3v2TxxxFrame(major = 3, description = "isrc", value = "QZXDB2300005")))
        assertNull(EmbeddedLyrics.isrcFromBytes(id3v2TxxxFrame(major = 3, description = "REPLAYGAIN", value = "-6.0 dB")))
    }

    // ---- Requirement 3: Addon ISRC extraction into Song ----

    @Test
    fun `AddonSource maps track isrc onto Song`() = runBlocking {
        val server = MockWebServer()
        AddonAuth.testHeaders = { _, _ ->
            mapOf(AddonAuth.TOKEN_HEADER to "test-token", AddonAuth.PROOF_HEADER to "test-proof")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/manifest.json" -> MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"com.test.addon","name":"Test","version":"1.0.0","bitchordAuth":true,"resources":["search","stream"]}""")
                "/search" -> MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {"tracks":[
                          {"id":"t1","title":"Let Down","artist":"Radiohead","album":"OK Computer","duration":299,"format":"flac","isrc":"GBAYE9701374"},
                          {"id":"t2","title":"Untitled","artist":"Radiohead","duration":120,"format":"flac","isrc":"   "}
                        ]}
                        """.trimIndent(),
                    )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val config = SourceConfig(
                kind = SourceKind.ADDON,
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
            val songs = AddonSource(config).search("let down", limit = 10)
            assertEquals(2, songs.size)
            assertEquals("GBAYE9701374", songs[0].isrc)
            assertNull(songs[1].isrc)
        } finally {
            AddonAuth.testHeaders = null
            server.shutdown()
        }
    }

    // ---- Requirement 4: Folder-album stripping & backfilled album forwarding ----

    @Test
    fun `LocalMediaRepository strips generic folder names and unknown album tags`() {
        for (noisy in listOf("Download", "Downloads", "download", "Music", "BitChord", "Audio", "<unknown>", "   ", "")) {
            assertNull("Expected '$noisy' to be stripped", LocalMediaRepository.cleanAlbumTag(noisy))
        }
        assertNull(LocalMediaRepository.cleanAlbumTag(null))
        assertEquals("OK Computer", LocalMediaRepository.cleanAlbumTag("  OK Computer  "))
        assertEquals("Random Access Memories", LocalMediaRepository.cleanAlbumTag("Random Access Memories"))
    }

    @Test
    fun `MainActivity and MainViewModel retry with backfilled album or ISRC only when synced lyrics are absent`() {
        val untimed = listOf(LyricLine(timeMs = 0L, text = "plain line"))
        val synced = listOf(LyricLine(timeMs = 1_500L, text = "timed line"))

        assertEquals("OK Computer", lyricsAlbumForPlayer(songAlbum = null, backfilledAlbum = "OK Computer", currentLyrics = null))
        assertEquals("OK Computer", lyricsAlbumForPlayer(songAlbum = null, backfilledAlbum = "OK Computer", currentLyrics = untimed))
        assertEquals("Initial Album", lyricsAlbumForPlayer(songAlbum = "Initial Album", backfilledAlbum = "OK Computer", currentLyrics = synced))

        assertTrue(
            shouldRetryLyricsWithBackfilledMetadata(
                previousAlbum = null,
                mergedAlbum = "OK Computer",
                previousIsrc = null,
                mergedIsrc = null,
                hasSyncedLyrics = false,
                lyricsChecked = true,
            ),
        )
        assertTrue(
            shouldRetryLyricsWithBackfilledMetadata(
                previousAlbum = "OK Computer",
                mergedAlbum = "OK Computer",
                previousIsrc = null,
                mergedIsrc = "GBAYE9701374",
                hasSyncedLyrics = false,
                lyricsChecked = true,
            ),
        )
        assertFalse(
            shouldRetryLyricsWithBackfilledMetadata(
                previousAlbum = null,
                mergedAlbum = "OK Computer",
                previousIsrc = null,
                mergedIsrc = "GBAYE9701374",
                hasSyncedLyrics = true,
                lyricsChecked = true,
            ),
        )
        assertFalse(
            shouldRetryLyricsWithBackfilledMetadata(
                previousAlbum = null,
                mergedAlbum = "OK Computer",
                previousIsrc = null,
                mergedIsrc = null,
                hasSyncedLyrics = false,
                lyricsChecked = false,
            ),
        )
    }

    // ---- Requirement 5: Default prioritizeSyllableSync = true while preserving saved prefs ----

    @Test
    fun `AppSettings defaults prioritizeSyllableSync to true and preserves saved SharedPreferences values`() {
        val previous = AppSettings.prioritizeSyllableSync.value
        val freshPrefs = InMemorySharedPreferences()
        try {
            AppSettings.attachPrefsForTests(freshPrefs)
            assertTrue(AppSettings.prioritizeSyllableSync.value)

            // Explicit false saved in SharedPreferences is preserved on reload.
            val savedFalsePrefs = InMemorySharedPreferences(mutableMapOf("prioritize_syllable_sync" to false))
            assertFalse(AppSettings.readPrioritizeSyllableSync(savedFalsePrefs))
            AppSettings.attachPrefsForTests(savedFalsePrefs)
            assertFalse(AppSettings.prioritizeSyllableSync.value)

            // Explicit true saved in SharedPreferences is preserved on reload.
            val savedTruePrefs = InMemorySharedPreferences(mutableMapOf("prioritize_syllable_sync" to true))
            assertTrue(AppSettings.readPrioritizeSyllableSync(savedTruePrefs))

            // Toggling off writes false, and resetLyricsSourceSettings restores true.
            AppSettings.attachPrefsForTests(freshPrefs)
            AppSettings.setPrioritizeSyllableSync(false)
            assertFalse(AppSettings.prioritizeSyllableSync.value)
            assertEquals(false, freshPrefs.getBoolean("prioritize_syllable_sync", true))

            AppSettings.resetLyricsSourceSettings()
            assertTrue(AppSettings.prioritizeSyllableSync.value)
            assertEquals(true, freshPrefs.getBoolean("prioritize_syllable_sync", false))
        } finally {
            AppSettings.prioritizeSyllableSync.value = previous
        }
    }

    // ---- Requirement 6: NOT_FOUND manual retries in LyricsProviderSheet & MainViewModel ----

    @Test
    fun `LyricsProviderSheet and MainViewModel allow retrying NOT_FOUND providers`() {
        assertTrue(isLyricsProviderRowEnabled(current = false, state = LyricsProviderState.NOT_FOUND))
        assertFalse(isLyricsProviderRowEnabled(current = true, state = LyricsProviderState.NOT_FOUND))

        assertEquals(LyricsSelectionAction.FETCH_NOW, lyricsSelectionAction(LyricsProviderState.NOT_FOUND))
        assertEquals(LyricsSelectionAction.FETCH_NOW, lyricsSelectionAction(LyricsProviderState.NOT_FETCHED))
        assertEquals(LyricsSelectionAction.FETCH_NOW, lyricsSelectionAction(null))
        assertEquals(LyricsSelectionAction.APPLY_CACHED, lyricsSelectionAction(LyricsProviderState.FOUND))
        assertEquals(LyricsSelectionAction.AWAIT_IN_FLIGHT, lyricsSelectionAction(LyricsProviderState.FETCHING))
    }

    // ---- Container byte fixtures ----

    private fun flacWithComment(entry: String): ByteArray {
        val streamInfo = ByteArray(34)
        val vendor = "BitChord".toByteArray(Charsets.UTF_8)
        val commentBytes = entry.toByteArray(Charsets.UTF_8)
        val vorbis = le32(vendor.size) + vendor + le32(1) + le32(commentBytes.size) + commentBytes
        return "fLaC".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00, 0, 0, streamInfo.size.toByte()) + streamInfo +
            byteArrayOf(
                (0x80 or 4).toByte(),
                ((vorbis.size ushr 16) and 0xFF).toByte(),
                ((vorbis.size ushr 8) and 0xFF).toByte(),
                (vorbis.size and 0xFF).toByte(),
            ) + vorbis
    }

    private fun webmWithSimpleTag(name: String, value: String): ByteArray {
        val header = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x84.toByte(), 0, 0, 0, 0)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        val tagName = byteArrayOf(0x45, 0xA3.toByte(), (0x80 or nameBytes.size).toByte()) + nameBytes
        val tagString = byteArrayOf(0x44, 0x87.toByte(), (0x80 or valueBytes.size).toByte()) + valueBytes
        val body = tagName + tagString
        val segment = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01, 0, 0, 0, 0, 0, 0, body.size.toByte()) + body
        return header + segment
    }

    private fun mp4WithFreeformIsrc(isrc: String): ByteArray {
        val mean = box("mean", byteArrayOf(0, 0, 0, 0) + "com.apple.iTunes".toByteArray(Charsets.UTF_8))
        val name = box("name", byteArrayOf(0, 0, 0, 0) + "ISRC".toByteArray(Charsets.UTF_8))
        val data = box("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + isrc.toByteArray(Charsets.UTF_8))
        val item = box("----", mean + name + data)
        return box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1)) + box("moov", item)
    }

    private fun mp4WithStandardIsr(isrc: String): ByteArray {
        val data = box("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + isrc.toByteArray(Charsets.UTF_8))
        val isrType = String(byteArrayOf(0xA9.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'r'.code.toByte()), Charsets.ISO_8859_1)
        val item = box(isrType, data)
        return box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1)) + box("moov", item)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    private fun id3v2TextFrame(major: Int, id: String, value: String): ByteArray {
        val body = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
        val sizeBytes = if (major == 4) syncsafe(body.size) else be32(body.size)
        val frame = id.toByteArray(Charsets.ISO_8859_1) + sizeBytes + byteArrayOf(0, 0) + body
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, 0) +
            syncsafe(frame.size + 8) + frame + ByteArray(8)
    }

    private fun id3v22TrcFrame(value: String): ByteArray {
        val body = byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)
        val sizeBytes = byteArrayOf(
            ((body.size ushr 16) and 0xFF).toByte(),
            ((body.size ushr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(),
        )
        val frame = "TRC".toByteArray(Charsets.ISO_8859_1) + sizeBytes + body
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 2, 0, 0) +
            syncsafe(frame.size + 8) + frame + ByteArray(8)
    }

    private fun id3v2TxxxFrame(major: Int, description: String, value: String): ByteArray {
        val body = byteArrayOf(3) + description.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + value.toByteArray(Charsets.UTF_8)
        val sizeBytes = if (major == 4) syncsafe(body.size) else be32(body.size)
        val frame = "TXXX".toByteArray(Charsets.ISO_8859_1) + sizeBytes + byteArrayOf(0, 0) + body
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, 0) +
            syncsafe(frame.size + 8) + frame + ByteArray(8)
    }

    private fun syncsafe(v: Int) = byteArrayOf(
        ((v ushr 21) and 0x7F).toByte(),
        ((v ushr 14) and 0x7F).toByte(),
        ((v ushr 7) and 0x7F).toByte(),
        (v and 0x7F).toByte(),
    )

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(),
        (v ushr 16).toByte(),
        (v ushr 8).toByte(),
        v.toByte(),
    )

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private class InMemorySharedPreferences(
        private val values: MutableMap<String, Any?> = mutableMapOf(),
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(values)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private class Editor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var cleared = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) removals += key
            }
            override fun clear(): SharedPreferences.Editor = apply { cleared = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (cleared) target.clear()
                removals.forEach(target::remove)
                target.putAll(staged)
            }
        }
    }
}
