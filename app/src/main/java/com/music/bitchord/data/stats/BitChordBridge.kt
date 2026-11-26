package com.music.bitchord.data.stats

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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

/**
 * Non-destructive bridge between original BitChord ([ORIGINAL_PACKAGE_ID])
 * and BitChord+ (`com.music.bitchord.plus`).
 *
 * Original BitChord runs in its own signed Android sandbox, so BitChord+ reads
 * only user-granted documents (a picked `bitchord-backup-*.json` file or a
 * persistent SAF folder) and folds its monthly [StoredBucket]s into
 * [PeerListening] under [BRIDGE_PEER_ID].
 *
 * Putting the bridged months into [PeerListening] rather than calling
 * [ListeningStats.importAll] avoids two traps:
 * 1. Re-importing a newer BitChord backup never double-counts plays and never
 *    erases months played in BitChord+.
 * 2. BitChord+'s own settings, credentials, and BetterLyrics/PaxSenix keys stay
 *    intact unless the listener explicitly opts to import preferences too.
 */
object BitChordBridge {

    const val ORIGINAL_PACKAGE_ID = "com.music.bitchord"
    const val BRIDGE_PEER_ID = "bitchord-original"
    const val BRIDGE_PEER_NAME = "BitChord"
    val PROTECTED_PEER_IDS: Set<String> = setOf(BRIDGE_PEER_ID)

    private const val STATE_FILE_NAME = "bitchord-bridge.json"
    private const val AUTO_SYNC_INTERVAL_MS = 60_000L
    private const val MAX_BACKUP_BYTES = 32L * 1024L * 1024L

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
        val lastError: String? = null,
    )

    data class BridgeStatus(
        val originalInstalled: Boolean = false,
        val linkedFolderUri: String? = null,
        val linkedFolderLabel: String? = null,
        val lastSyncedAt: Long = 0L,
        val lastSourceName: String? = null,
        val lastSourceHash: String? = null,
        val lastSourceVersion: String? = null,
        val lastExportedAt: String? = null,
        val bridgedMonths: Int = 0,
        val bridgedTotalMs: Long = 0L,
        val lastError: String? = null,
    ) {
        val hasLinkedFolder: Boolean get() = !linkedFolderUri.isNullOrBlank()
        val hasBridgedData: Boolean get() = bridgedMonths > 0 || bridgedTotalMs > 0L
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
    private var state: BridgeState = BridgeState()

    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    fun init(app: Application) {
        initForTesting(app.filesDir)
        refreshInstalledState(app)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                refreshInstalledState(app)
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
            lastAutoSyncAttemptAt = 0L
            emitStatus()
        }
    }

    /** Updates whether original BitChord ([ORIGINAL_PACKAGE_ID]) is installed on this device. */
    fun refreshInstalledState(context: Context): Boolean {
        val installed = isOriginalAppInstalled(context)
        originalInstalled = installed
        emitStatus()
        return installed
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

    /** Removes all bridged listening months from [PeerListening] without touching local stats. */
    fun clearBridgedStats() {
        PeerListening.removeDevice(BRIDGE_PEER_ID)
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
                lastError = null,
            )
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
            linkedFolderUri = s.linkedFolderUri,
            linkedFolderLabel = s.linkedFolderLabel,
            lastSyncedAt = s.lastSyncedAt,
            lastSourceName = s.lastSourceName,
            lastSourceHash = s.lastSourceHash,
            lastSourceVersion = s.lastSourceVersion,
            lastExportedAt = s.lastExportedAt,
            bridgedMonths = s.bridgedMonths,
            bridgedTotalMs = s.bridgedTotalMs,
            lastError = s.lastError,
        )
    }
}
