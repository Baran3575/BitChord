package com.music.bitchord.data.stats

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BitChordBridgeTest {

    private lateinit var tempDir: File
    private lateinit var peersDir: File
    private lateinit var stateDir: File

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("bitchord-bridge-test").toFile()
        peersDir = File(tempDir, "listening-peers")
        stateDir = File(tempDir, "state")
        PeerListening.init(peersDir)
        BitChordBridge.initForTesting(stateDir)
    }

    @After
    fun tearDown() {
        PeerListening.clear()
        tempDir.deleteRecursively()
    }

    private fun bucket(month: String, songId: String, ms: Long, plays: Int): StoredBucket = StoredBucket(
        month = month,
        tracks = listOf(
            TrackEntry(
                id = songId,
                title = "Track $songId",
                artist = "Artist",
                ms = ms,
                plays = plays,
                days = mutableMapOf("$month-01" to DayStats(ms, plays)),
            ),
        ),
        artists = listOf(NameEntry(name = "Artist", ms = ms, plays = plays)),
        hours = List(24) { if (it == 12) ms else 0L },
        days = mapOf(1 to ms),
        trackDays = mapOf("$month-01" to DayStats(ms, plays)),
    )

    private fun backupBytes(
        buckets: List<StoredBucket>,
        settings: Map<String, Backup.PrefValue> = emptyMap(),
        versionName: String = "1.10.0",
        exportedAt: String = "2026-11-26T10:00:00Z",
    ): ByteArray {
        val file = Backup.BackupFile(
            app = "bitchord",
            version = 1,
            versionName = versionName,
            exportedAt = exportedAt,
            settings = settings,
            listening = buckets,
        )
        return json.encodeToString(Backup.BackupFile.serializer(), file).toByteArray(Charsets.UTF_8)
    }

    @Test
    fun applyBackupBytes_mergesIntoDedicatedPeerPartition_withoutDoublingOnReimport() = runBlocking {
        // An existing Drive-synced peer stays intact alongside the bridge partition.
        val otherPeer = bucket("2026-11", "drive-song", ms = 120_000L, plays = 1)
        val otherBytes = BucketMath.canonicalBytes(otherPeer)
        PeerListening.putMonth("drive-tablet", "Tablet", 100L, "2026-11", otherBytes, BucketMath.hash(otherBytes))

        val initial = backupBytes(
            listOf(
                bucket("2026-10", "orig-1", ms = 300_000L, plays = 2),
                bucket("2026-11", "orig-2", ms = 600_000L, plays = 4),
            ),
        )

        val first = BitChordBridge.applyBackupBytes(initial, sourceName = "bitchord-backup-1.json")
        assertTrue(first.updated)
        assertEquals(2, first.months)
        assertEquals(900_000L, first.totalMs)

        // Exact same backup imported again skips writes and keeps totals identical.
        val second = BitChordBridge.applyBackupBytes(initial, sourceName = "bitchord-backup-1.json")
        assertFalse(second.updated)
        assertEquals(900_000L, second.totalMs)

        // Both November sources (Drive tablet + bridged BitChord) exist cleanly without doubling.
        val novBuckets = PeerListening.buckets("2026-11")
        assertEquals(2, novBuckets.size)
        assertEquals(720_000L, novBuckets.sumOf { b -> b.days.values.sum() })

        // Newer BitChord export updates 2026-11 and drops 2026-10: bridge replaces only its own partition.
        val updated = backupBytes(
            listOf(bucket("2026-11", "orig-2", ms = 900_000L, plays = 6)),
        )
        val third = BitChordBridge.applyBackupBytes(updated, sourceName = "bitchord-backup-2.json")
        assertTrue(third.updated)
        assertEquals(1, third.months)
        assertEquals(900_000L, third.totalMs)
        assertTrue(PeerListening.buckets("2026-10").isEmpty())
        assertEquals(1_020_000L, PeerListening.buckets("2026-11").sumOf { b -> b.days.values.sum() })
    }

    @Test
    fun applyBackupBytes_respectsIncludeSettingsOptIn() = runBlocking {
        val bytes = backupBytes(
            buckets = listOf(bucket("2026-11", "song-1", ms = 60_000L, plays = 1)),
            settings = mapOf(
                "dynamic_theme" to Backup.PrefValue.of(true)!!,
                "better_lyrics_api_key" to Backup.PrefValue.of("ignored-secret")!!,
            ),
        )

        var importedMap: Map<String, Any?>? = null
        BitChordBridge.applyBackupBytes(
            bytes = bytes,
            includeSettings = false,
            settingsImporter = { importedMap = it },
        )
        assertNull(importedMap)

        BitChordBridge.applyBackupBytes(
            bytes = bytes,
            includeSettings = true,
            settingsImporter = { importedMap = it },
        )
        assertNotNull(importedMap)
        assertEquals(true, importedMap!!["dynamic_theme"])
    }

    @Test
    fun protectedPeerPartition_survivesDriveDisconnectClear() = runBlocking {
        val driveBytes = BucketMath.canonicalBytes(bucket("2026-11", "drive-1", ms = 50_000L, plays = 1))
        PeerListening.putMonth("drive-phone", "Phone", 1L, "2026-11", driveBytes, BucketMath.hash(driveBytes))
        BitChordBridge.applyBackupBytes(backupBytes(listOf(bucket("2026-11", "orig-1", ms = 80_000L, plays = 1))))

        PeerListening.clear(exclude = BitChordBridge.PROTECTED_PEER_IDS)

        val remainingPeers = PeerListening.peers()
        assertEquals(listOf(BitChordBridge.BRIDGE_PEER_ID), remainingPeers.map { it.deviceId })
        assertEquals(80_000L, PeerListening.buckets("2026-11").sumOf { it.days.values.sum() })

        BitChordBridge.clearBridgedStats()
        assertTrue(PeerListening.peers().isEmpty())
        assertFalse(BitChordBridge.status.value.hasBridgedData)
    }

    @Test
    fun selectLatestBackupCandidate_prefersBitChordBackupPatternThenNewestModified() {
        val candidates = listOf(
            BitChordBridge.DocumentCandidate(
                documentId = "1",
                displayName = "other.json",
                mimeType = "application/json",
                lastModified = 9_999L,
                sizeBytes = 1024L,
            ),
            BitChordBridge.DocumentCandidate(
                documentId = "2",
                displayName = "bitchord-backup-2026-11-24.json",
                mimeType = "application/json",
                lastModified = 2_000L,
                sizeBytes = 2048L,
            ),
            BitChordBridge.DocumentCandidate(
                documentId = "3",
                displayName = "bitchord-backup-2026-11-26.json",
                mimeType = "application/json",
                lastModified = 5_000L,
                sizeBytes = 4096L,
            ),
            BitChordBridge.DocumentCandidate(
                documentId = "4",
                displayName = "bitchord-backup-2026-11-26.json",
                mimeType = "vnd.android.document/directory",
                lastModified = 10_000L,
                sizeBytes = 0L,
            ),
        )

        val chosen = BitChordBridge.selectLatestBackupCandidate(candidates)
        assertEquals("3", chosen?.documentId)
    }
}
