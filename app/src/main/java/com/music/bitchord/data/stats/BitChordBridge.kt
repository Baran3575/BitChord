package com.music.bitchord.data.stats

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * Hybrid non-destructive bridge between original BitChord ([ORIGINAL_PACKAGE_ID])
 * and BitChord+ (`com.music.bitchord.plus`).
 *
 * Combines two complementary Android-sanctioned channels without ever bypassing
 * sandboxes or wiping local BitChord+ data:
 * 1. **Historical backup merge ([BRIDGE_PEER_ID])**: Imports exported
 *    `bitchord-backup-*.json` files (manually or automatically from a linked
 *    SAF folder) into [PeerListening] so historical listening time, top songs,
 *    top artists, and top albums appear in Replay cleanly.
 * 2. **Live MediaSession tracking ([LIVE_PEER_ID])**: Once Notification Access
 *    is granted, [BitChordLiveBridgeService] watches playback from original
 *    BitChord in real time and folds ongoing minutes, plays, artists, and
 *    albums into [PeerListening] automatically.
 */
object BitChordBridge {

    const val ORIGINAL_PACKAGE_ID = "com.music.bitchord"
    const val BRIDGE_PEER_ID = "bitchord-original"
    const val BRIDGE_PEER_NAME = "BitChord"
    const val LIVE_PEER_ID = "bitchord-live"
    const val LIVE_PEER_NAME = "BitChord (Live)"
    val PROTECTED_PEER_IDS: Set<String> = setOf(BRIDGE_PEER_ID, LIVE_PEER_ID)

    private const val STATE_FILE_NAME = "bitchord-bridge.json"
    private const val AUTO_SYNC_INTERVAL_MS = 60_000L
    private const val MAX_BACKUP_BYTES = 32L * 1024L * 1024L
    private const val UNIT_SEPARATOR = 31

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    internal data class BridgeState(
        val linkedFolderUri: String? = null,
        val linkedFolderLabel: String? = null,
        val lastSyncedAt: Long = 0L,
        val lastSourceName: String? = null,
        val lastSourceHash: String? = null,
        val lastSourceVersion: String? = null,
        val lastExportedAt: String? = null,
        val bridgedMonths: Int = 0,
        val bridgedTotalMs: Long = 0L,
        val liveMonths: Int = 0,
        val liveTotalMs: Long = 0L,
        val liveLastEventAt: Long = 0L,
        val promptDismissed: Boolean = false,
        val bannerDismissed: Boolean = false,
        val lastError: String? = null,
    )

    data class BridgeStatus(
        val originalInstalled: Boolean = false,
        val notificationAccessEnabled: Boolean = false,
        val linkedFolderUri: String? = null,
        val linkedFolderLabel: String? = null,
        val lastSyncedAt: Long = 0L,
        val lastSourceName: String? = null,
        val lastSourceHash: String? = null,
        val lastSourceVersion: String? = null,
        val lastExportedAt: String? = null,
        val bridgedMonths: Int = 0,
        val bridgedTotalMs: Long = 0L,
        val liveMonths: Int = 0,
        val liveTotalMs: Long = 0L,
        val liveLastEventAt: Long = 0L,
        val promptDismissed: Boolean = false,
        val bannerDismissed: Boolean = false,
        val lastError: String? = null,
    ) {
        val hasLinkedFolder: Boolean get() = !linkedFolderUri.isNullOrBlank()
        val hasHistoricalBackup: Boolean get() = bridgedMonths > 0 || bridgedTotalMs > 0L
        val hasLiveStats: Boolean get() = liveMonths > 0 || liveTotalMs > 0L
        val hasBridgedData: Boolean get() = hasHistoricalBackup || hasLiveStats
        val combinedTotalMs: Long get() = bridgedTotalMs + liveTotalMs
        val combinedMonths: Int get() = maxOf(bridgedMonths, liveMonths)
    }

    data class ImportResult(
        val months: Int,
        val totalMs: Long,
        val settingsCount: Int,
        val from: String,
        val at: String,
        val updated: Boolean,
    )

    internal data class DocumentCandidate(
        val documentId: String,
        val displayName: String,
        val mimeType: String?,
        val lastModified: Long,
        val sizeBytes: Long,
    )

    private var stateFile: File? = null
    private val stateLock = Any()
    private val syncMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastAutoSyncAttemptAt: Long = 0L

    @Volatile
    private var originalInstalled: Boolean = false

    @Volatile
    private var notificationAccessEnabled: Boolean = false

    @Volatile
    private var state: BridgeState = BridgeState()

    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    fun init(app: Application) {
        initForTesting(app.filesDir)
        refreshSystemState(app)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                refreshSystemState(app)
                val uriString = state.linkedFolderUri ?: return
                val now = System.currentTimeMillis()
                if (now - lastAutoSyncAttemptAt < AUTO_SYNC_INTERVAL_MS) return
                lastAutoSyncAttemptAt = now
                scope.launch {
                    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@launch
                    syncFolderUri(app, uri, force = false)
                }
            }
        })
    }

    internal fun initForTesting(stateDir: File) {
        synchronized(stateLock) {
            stateDir.mkdirs()
            val file = File(stateDir, STATE_FILE_NAME)
            stateFile = file
            state = if (file.exists()) {
                runCatching { json.decodeFromString(BridgeState.serializer(), file.readText()) }
                    .getOrElse { BridgeState() }
            } else {
                BridgeState()
            }
            originalInstalled = false
            notificationAccessEnabled = false
            lastAutoSyncAttemptAt = 0L
            emitStatus()
        }
    }

    /** Updates whether original BitChord is installed and whether Notification Access is granted. */
    fun refreshInstalledState(context: Context): Boolean {
        refreshSystemState(context)
        return originalInstalled
    }

    fun refreshSystemState(context: Context) {
        originalInstalled = isOriginalAppInstalled(context)
        notificationAccessEnabled = isNotificationAccessGranted(context)
        emitStatus()
    }

    /** Returns true when original BitChord (`com.music.bitchord`) is installed alongside BitChord+. */
    fun isOriginalAppInstalled(context: Context): Boolean {
        if (context.packageName == ORIGINAL_PACKAGE_ID) return false
        return runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(ORIGINAL_PACKAGE_ID, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ORIGINAL_PACKAGE_ID, 0)
            }
            true
        }.getOrDefault(false)
    }

    /** Returns true when this app holds system Notification Listener permission for live bridging. */
    fun isNotificationAccessGranted(context: Context): Boolean = runCatching {
        val component = ComponentName(context, BitChordLiveBridgeService::class.java)
        val flat = component.flattenToString()
        val shortFlat = component.flattenToShortString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        enabled.split(':').any { it.equals(flat, ignoreCase = true) || it.equals(shortFlat, ignoreCase = true) }
    }.getOrDefault(false)

    /** Opens Android Notification Listener settings so the user can enable live BitChord tracking. */
    fun openNotificationListenerSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** Opens original BitChord so the user can export a fresh backup file. */
    fun launchOriginalApp(context: Context): Boolean {
        if (context.packageName == ORIGINAL_PACKAGE_ID) return false
        val intent = context.packageManager.getLaunchIntentForPackage(ORIGINAL_PACKAGE_ID) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun dismissStartupPrompt() {
        updateState { it.copy(promptDismissed = true) }
    }

    fun dismissReplayBanner() {
        updateState { it.copy(bannerDismissed = true) }
    }

    /**
     * Imports a single BitChord backup document chosen by the listener and merges
     * its listening stats into [PeerListening] under [BRIDGE_PEER_ID].
     */
    suspend fun importFromUri(
        context: Context,
        source: Uri,
        includeSettings: Boolean = false,
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(source)
                ?.use { readBytesLimited(it) }
                ?: error("Couldn't open that file")
            val displayName = resolveDocumentDisplayName(context, source)
            applyBackupBytes(
                bytes = bytes,
                sourceName = displayName,
                includeSettings = includeSettings,
                force = true,
            )
        }.onFailure { err ->
            updateState { it.copy(lastError = err.message) }
        }
    }

    /**
     * Links a SAF folder so BitChord+ can automatically detect and merge newer
     * `bitchord-backup-*.json` exports placed inside it.
     */
    fun linkFolder(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val label = folderLabel(treeUri)
        updateState {
            it.copy(
                linkedFolderUri = treeUri.toString(),
                linkedFolderLabel = label,
                lastError = null,
            )
        }
    }

    /** Stops watching the previously linked backup folder while keeping bridged stats. */
    fun unlinkFolder(context: Context? = null) {
        val previous = state.linkedFolderUri
        if (context != null && !previous.isNullOrBlank()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(previous),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        updateState {
            it.copy(
                linkedFolderUri = null,
                linkedFolderLabel = null,
                lastError = null,
            )
        }
    }

    /** Scans the currently linked backup folder and imports the newest backup if changed. */
    suspend fun syncLinkedFolder(
        context: Context,
        force: Boolean = false,
    ): Result<ImportResult?> = withContext(Dispatchers.IO) {
        val uriString = state.linkedFolderUri
            ?: return@withContext Result.failure(IllegalStateException("No BitChord folder linked"))
        val treeUri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("Invalid linked folder"))
        syncFolderUri(context, treeUri, force)
    }

    private suspend fun syncFolderUri(
        context: Context,
        treeUri: Uri,
        force: Boolean,
    ): Result<ImportResult?> = runCatching {
        val candidate = findLatestBackupDocument(context, treeUri) ?: return@runCatching null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, candidate.documentId)
        val bytes = context.contentResolver.openInputStream(docUri)
            ?.use { readBytesLimited(it) }
            ?: error("Couldn't read ${candidate.displayName}")
        applyBackupBytes(
            bytes = bytes,
            sourceName = candidate.displayName,
            includeSettings = false,
            force = force,
        )
    }.onFailure { err ->
        updateState { it.copy(lastError = err.message) }
    }

    /**
     * Parses [bytes] as a BitChord backup JSON and folds its months into
     * [PeerListening] under [BRIDGE_PEER_ID]. Repeated calls with the same or
     * newer export replace only [BRIDGE_PEER_ID]'s partition, preserving all
     * listening recorded directly in BitChord+.
     */
    internal suspend fun applyBackupBytes(
        bytes: ByteArray,
        sourceName: String? = null,
        includeSettings: Boolean = false,
        force: Boolean = false,
        importedAtMs: Long = System.currentTimeMillis(),
        settingsImporter: (Map<String, Any?>) -> Unit = ::applySettingsToDevice,
    ): ImportResult = syncMutex.withLock {
        val file = Backup.parse(ByteArrayInputStream(bytes))
        val sourceHash = BucketMath.hash(bytes)
        val existingPeer = PeerListening.peers().firstOrNull { it.deviceId == BRIDGE_PEER_ID }
        val bucketsByMonth = foldBucketsByMonth(file.listening)
        val totalMs = bucketsByMonth.values.sumOf(::bucketTotalMs)

        if (!force && !includeSettings && state.lastSourceHash == sourceHash && existingPeer != null) {
            return@withLock ImportResult(
                months = bucketsByMonth.size,
                totalMs = totalMs,
                settingsCount = file.settings.size,
                from = file.versionName,
                at = file.exportedAt,
                updated = false,
            )
        }

        val existingMonths = existingPeer?.months.orEmpty()
        (existingMonths.keys - bucketsByMonth.keys).forEach { removedMonth ->
            PeerListening.removeMonth(BRIDGE_PEER_ID, removedMonth)
        }

        var changed = (existingMonths.keys - bucketsByMonth.keys).isNotEmpty()
        if (bucketsByMonth.isEmpty()) {
            PeerListening.touch(BRIDGE_PEER_ID, BRIDGE_PEER_NAME, importedAtMs)
        } else {
            bucketsByMonth.forEach { (month, bucket) ->
                val canonical = BucketMath.canonicalBytes(bucket)
                val monthHash = BucketMath.hash(canonical)
                if (existingMonths[month] != monthHash) {
                    PeerListening.putMonth(
                        deviceId = BRIDGE_PEER_ID,
                        name = BRIDGE_PEER_NAME,
                        updatedAt = importedAtMs,
                        month = month,
                        bytes = canonical,
                        md5 = monthHash,
                    )
                    changed = true
                }
            }
        }

        // If a newer backup snapshot already covers everything captured live up to now,
        // drop the temporary live partition so those plays are not counted twice.
        val exportedMs = runCatching { Instant.parse(file.exportedAt).toEpochMilli() }.getOrNull() ?: importedAtMs
        var liveMonths = state.liveMonths
        var liveTotalMs = state.liveTotalMs
        var liveLastEventAt = state.liveLastEventAt
        if (liveLastEventAt > 0L && exportedMs >= liveLastEventAt) {
            PeerListening.removeDevice(LIVE_PEER_ID)
            liveMonths = 0
            liveTotalMs = 0L
            liveLastEventAt = 0L
            changed = true
        }

        if (changed) {
            ListeningStats.peersChanged()
        }

        if (includeSettings) {
            settingsImporter(file.settings.mapValues { it.value.decoded() })
        }

        updateState {
            it.copy(
                lastSyncedAt = importedAtMs,
                lastSourceName = sourceName ?: it.lastSourceName,
                lastSourceHash = sourceHash,
                lastSourceVersion = file.versionName,
                lastExportedAt = file.exportedAt,
                bridgedMonths = bucketsByMonth.size,
                bridgedTotalMs = totalMs,
                liveMonths = liveMonths,
                liveTotalMs = liveTotalMs,
                liveLastEventAt = liveLastEventAt,
                lastError = null,
            )
        }

        ImportResult(
            months = bucketsByMonth.size,
            totalMs = totalMs,
            settingsCount = file.settings.size,
            from = file.versionName,
            at = file.exportedAt,
            updated = changed || includeSettings,
        )
    }

    /**
     * Records a live listening slice observed from original BitChord into [PeerListening]
     * under [LIVE_PEER_ID], including track, artist, album, hour, and per-day breakdowns
     * so Replay's listening time, top songs, top artists, and top albums stay current.
     */
    suspend fun recordLivePlayback(
        trackId: String,
        title: String,
        artist: String,
        album: String?,
        albumId: String? = null,
        artistId: String? = null,
        artUrl: String? = null,
        playedMs: Long,
        countsAsPlay: Boolean,
        atMs: Long = System.currentTimeMillis(),
    ) {
        if (playedMs <= 0L && !countsAsPlay) return
        val cleanTitle = title.trim()
        val cleanId = trackId.trim()
        if (cleanTitle.isEmpty() || cleanId.isEmpty()) return
        val cleanArtist = artist.trim()
        val cleanAlbum = album?.trim()?.takeIf { it.isNotEmpty() }

        syncMutex.withLock {
            val at = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())
            val monthKey = YearMonth.from(at).toString()
            val dayKey = "%04d-%02d-%02d".format(Locale.ROOT, at.year, at.monthValue, at.dayOfMonth)
            val current = PeerListening.bucket(LIVE_PEER_ID, monthKey) ?: StoredBucket(month = monthKey)

            val tracks = current.tracks.associateByTo(LinkedHashMap()) { it.id }
            val track = tracks.getOrPut(cleanId) {
                TrackEntry(
                    id = cleanId,
                    title = cleanTitle,
                    artist = cleanArtist,
                    album = cleanAlbum,
                    albumId = albumId,
                    artistId = artistId,
                    art = artUrl,
                )
            }
            track.ms += playedMs
            track.last = atMs
            if (track.album == null) track.album = cleanAlbum
            if (track.albumId == null) track.albumId = albumId
            if (track.artistId == null) track.artistId = artistId
            if (track.art == null) track.art = artUrl
            if (countsAsPlay) track.plays++
            track.days.computeIfAbsent(dayKey) { DayStats() }.also { d ->
                d.ms += playedMs
                if (countsAsPlay) d.plays++
            }

            val trackDays = current.trackDays.orEmpty().mapValuesTo(LinkedHashMap()) { it.value.copy() }
            trackDays.computeIfAbsent(dayKey) { DayStats() }.also { d ->
                d.ms += playedMs
                if (countsAsPlay) d.plays++
            }

            val artists = current.artists.associateByTo(LinkedHashMap()) { it.name.lowercase(Locale.ROOT) }
            val artistDays = current.artistDays.orEmpty().mapValuesTo(LinkedHashMap()) { it.value.copy() }
            ListeningStats.primaryArtist(cleanArtist)?.let { lead ->
                ArtistFacts.noticed(lead)
                val entry = artists.getOrPut(lead.lowercase(Locale.ROOT)) {
                    NameEntry(name = lead, art = artUrl, id = artistId)
                }
                entry.ms += playedMs
                if (countsAsPlay) entry.plays++
                if (entry.art == null) entry.art = artUrl
                if (entry.id == null) entry.id = artistId
                entry.days.computeIfAbsent(dayKey) { DayStats() }.also { d ->
                    d.ms += playedMs
                    if (countsAsPlay) d.plays++
                }
                artistDays.computeIfAbsent(dayKey) { DayStats() }.also { d ->
                    d.ms += playedMs
                    if (countsAsPlay) d.plays++
                }
            }

            val sep = Char(UNIT_SEPARATOR).toString()
            fun liveAlbumKey(id: String?, name: String, artistName: String): String =
                id ?: (name.lowercase(Locale.ROOT) + sep + artistName.lowercase(Locale.ROOT))

            val albums = current.albums.associateByTo(LinkedHashMap()) {
                liveAlbumKey(it.id, it.name, it.sub.orEmpty())
            }
            val albumDays = current.albumDays.orEmpty().mapValuesTo(LinkedHashMap()) { it.value.copy() }
            if (cleanAlbum != null) {
                val aKey = liveAlbumKey(albumId, cleanAlbum, cleanArtist)
                val entry = albums.getOrPut(aKey) {
                    NameEntry(name = cleanAlbum, sub = cleanArtist, art = artUrl, id = albumId)
                }
                entry.ms += playedMs
                if (countsAsPlay) entry.plays++
                if (entry.art == null) entry.art = artUrl
                if (entry.id == null) entry.id = albumId
                entry.days.computeIfAbsent(dayKey) { DayStats() }.also { d ->
                    d.ms += playedMs
                    if (countsAsPlay) d.plays++
                }
                albumDays.computeIfAbsent(dayKey) { DayStats() }.also { d ->
                    d.ms += playedMs
                    if (countsAsPlay) d.plays++
                }
            }

            val hours = LongArray(24) { current.hours.getOrElse(it) { 0L } }
            hours[at.hour] += playedMs
            val days = current.days.toMutableMap()
            days[at.dayOfMonth] = (days[at.dayOfMonth] ?: 0L) + playedMs

            val updatedBucket = StoredBucket(
                month = monthKey,
                tracks = tracks.values.toList(),
                artists = artists.values.toList(),
                albums = albums.values.toList(),
                hours = hours.toList(),
                days = days,
                trackDays = trackDays,
                artistDays = artistDays,
                albumDays = albumDays,
            )

            val canonical = BucketMath.canonicalBytes(updatedBucket)
            val monthHash = BucketMath.hash(canonical)
            PeerListening.putMonth(
                deviceId = LIVE_PEER_ID,
                name = LIVE_PEER_NAME,
                updatedAt = atMs,
                month = monthKey,
                bytes = canonical,
                md5 = monthHash,
            )
            ListeningStats.peersChanged()

            val allLive = PeerListening.deviceBuckets(LIVE_PEER_ID)
            val totalLiveMs = allLive.sumOf(::bucketTotalMs)
            updateState {
                it.copy(
                    liveMonths = allLive.size,
                    liveTotalMs = totalLiveMs,
                    liveLastEventAt = maxOf(it.liveLastEventAt, atMs),
                )
            }
        }
    }

    /** Removes all bridged and live BitChord stats from [PeerListening] without touching local stats. */
    fun clearBridgedStats() {
        PeerListening.removeDevice(BRIDGE_PEER_ID)
        PeerListening.removeDevice(LIVE_PEER_ID)
        ListeningStats.peersChanged()
        updateState {
            it.copy(
                lastSyncedAt = 0L,
                lastSourceName = null,
                lastSourceHash = null,
                lastSourceVersion = null,
                lastExportedAt = null,
                bridgedMonths = 0,
                bridgedTotalMs = 0L,
                liveMonths = 0,
                liveTotalMs = 0L,
                liveLastEventAt = 0L,
                lastError = null,
            )
        }
    }

    internal fun formatDuration(context: Context, totalMs: Long): String {
        val totalMinutes = (totalMs / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) {
            context.getString(R.string.bitchord_bridge_duration_hm, hours, minutes)
        } else {
            context.getString(R.string.bitchord_bridge_duration_m, minutes)
        }
    }

    private fun applySettingsToDevice(values: Map<String, Any?>) {
        AppSettings.importPrefs(values)
        SearchHistory.reload()
    }

    private fun foldBucketsByMonth(buckets: List<StoredBucket>): Map<String, StoredBucket> {
        val merged = linkedMapOf<String, StoredBucket>()
        buckets.forEach { bucket ->
            if (bucket.month.isBlank()) return@forEach
            val prev = merged[bucket.month]
            merged[bucket.month] = if (prev == null) bucket else BucketMath.plus(prev, bucket)
        }
        return merged
    }

    internal fun bucketTotalMs(bucket: StoredBucket): Long {
        val daySum = bucket.days.values.sum()
        return if (daySum > 0L) daySum else bucket.tracks.sumOf { it.ms }
    }

    internal fun selectLatestBackupCandidate(candidates: List<DocumentCandidate>): DocumentCandidate? =
        candidates.asSequence()
            .filter { candidate ->
                candidate.mimeType != DocumentsContract.Document.MIME_TYPE_DIR &&
                    candidate.displayName.endsWith(".json", ignoreCase = true) &&
                    candidate.sizeBytes in 0L..MAX_BACKUP_BYTES
            }
            .sortedWith(
                compareByDescending<DocumentCandidate> {
                    it.displayName.startsWith("bitchord-backup-", ignoreCase = true)
                }.thenByDescending { it.lastModified }
                    .thenByDescending { it.displayName },
            )
            .firstOrNull()

    internal fun folderLabel(treeUri: Uri): String? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        if (documentId.startsWith("raw:")) {
            return@runCatching documentId.removePrefix("raw:")
                .trimEnd('/', '\\')
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .takeIf { it.isNotBlank() }
        }
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "").trim('/')
        relative.ifBlank { volume }
    }.getOrNull()

    private fun findLatestBackupDocument(context: Context, treeUri: Uri): DocumentCandidate? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val candidates = ArrayList<DocumentCandidate>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val docId = if (idIdx >= 0) cursor.getString(idIdx) else null
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                if (docId.isNullOrBlank() || name.isNullOrBlank()) continue
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                val modified = if (modifiedIdx >= 0 && !cursor.isNull(modifiedIdx)) cursor.getLong(modifiedIdx) else 0L
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                candidates += DocumentCandidate(
                    documentId = docId,
                    displayName = name,
                    mimeType = mime,
                    lastModified = modified,
                    sizeBytes = size,
                )
            }
        }
        return selectLatestBackupCandidate(candidates)
    }

    private fun resolveDocumentDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun readBytesLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            require(total <= MAX_BACKUP_BYTES) { "Backup file is too large" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun updateState(transform: (BridgeState) -> BridgeState) {
        synchronized(stateLock) {
            val updated = transform(state)
            state = updated
            stateFile?.let { file ->
                runCatching {
                    file.parentFile?.mkdirs()
                    val temp = File(file.parentFile, "${file.name}.tmp")
                    temp.writeText(json.encodeToString(BridgeState.serializer(), updated))
                    if (!temp.renameTo(file)) {
                        file.delete()
                        temp.renameTo(file)
                    }
                }
            }
            emitStatus()
        }
    }

    private fun emitStatus() {
        val s = state
        _status.value = BridgeStatus(
            originalInstalled = originalInstalled,
            notificationAccessEnabled = notificationAccessEnabled,
            linkedFolderUri = s.linkedFolderUri,
            linkedFolderLabel = s.linkedFolderLabel,
            lastSyncedAt = s.lastSyncedAt,
            lastSourceName = s.lastSourceName,
            lastSourceHash = s.lastSourceHash,
            lastSourceVersion = s.lastSourceVersion,
            lastExportedAt = s.lastExportedAt,
            bridgedMonths = s.bridgedMonths,
            bridgedTotalMs = s.bridgedTotalMs,
            liveMonths = s.liveMonths,
            liveTotalMs = s.liveTotalMs,
            liveLastEventAt = s.liveLastEventAt,
            promptDismissed = s.promptDismissed,
            bannerDismissed = s.bannerDismissed,
            lastError = s.lastError,
        )
    }
}
