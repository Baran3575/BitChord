package com.music.bitchord.data.stats

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Passive live-listening bridge built on Android's Notification Listener /
 * MediaSession API.
 *
 * When enabled in Android settings, it observes active playback sessions
 * owned by original BitChord (`com.music.bitchord`), samples playing time, and
 * writes ongoing minutes, play counts, tracks, artists, and albums into
 * [BitChordBridge.recordLivePlayback].
 */
class BitChordLiveBridgeService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enrichCache = ConcurrentHashMap<String, Song>()
    private val enrichAsked = ConcurrentHashMap.newKeySet<String>()

    private var sessionManager: MediaSessionManager? = null
    private var attachedController: MediaController? = null
    private var tickerJob: Job? = null

    private var activeKey: String? = null
    private var activeMeta: SessionTrack? = null
    private var isPlaying = false
    private var lastSampleAt = 0L
    private var playedThisTrack = 0L
    private var playCounted = false

    private data class SessionTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val albumId: String?,
        val artistId: String?,
        val artUrl: String?,
        val durationMs: Long,
    )

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        syncFromControllers(controllers.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handleSessionUpdate(attachedController)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleSessionUpdate(attachedController)
        }

        override fun onSessionDestroyed() {
            flushPendingDelta()
            detachController()
            refreshSessions()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        BitChordBridge.refreshSystemState(applicationContext)
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        runCatching {
            val component = ComponentName(this, BitChordLiveBridgeService::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            refreshSessions()
        }
    }

    override fun onListenerDisconnected() {
        flushPendingDelta()
        detachController()
        tickerJob?.cancel()
        tickerJob = null
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        super.onListenerDisconnected()
        BitChordBridge.refreshSystemState(applicationContext)
    }

    override fun onDestroy() {
        flushPendingDelta()
        detachController()
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == BitChordBridge.ORIGINAL_PACKAGE_ID) {
            refreshSessions()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == BitChordBridge.ORIGINAL_PACKAGE_ID) {
            refreshSessions()
        }
    }

    private fun refreshSessions() {
        val manager = sessionManager ?: return
        val component = ComponentName(this, BitChordLiveBridgeService::class.java)
        val controllers = runCatching { manager.getActiveSessions(component) }.getOrNull().orEmpty()
        syncFromControllers(controllers)
    }

    @Synchronized
    private fun syncFromControllers(controllers: List<MediaController>) {
        val candidate = controllers.firstOrNull { controller ->
            controller.packageName == BitChordBridge.ORIGINAL_PACKAGE_ID
        }
        if (candidate?.sessionToken != attachedController?.sessionToken) {
            flushPendingDelta()
            detachController()
            if (candidate != null) {
                attachedController = candidate
                candidate.registerCallback(controllerCallback)
            }
        }
        handleSessionUpdate(attachedController)
    }

    @Synchronized
    private fun detachController() {
        attachedController?.let { controller ->
            runCatching { controller.unregisterCallback(controllerCallback) }
        }
        attachedController = null
        isPlaying = false
        activeKey = null
        activeMeta = null
        lastSampleAt = 0L
        playedThisTrack = 0L
        playCounted = false
        tickerJob?.cancel()
        tickerJob = null
    }

    @Synchronized
    private fun handleSessionUpdate(controller: MediaController?) {
        if (controller == null) {
            flushPendingDelta()
            isPlaying = false
            tickerJob?.cancel()
            tickerJob = null
            return
        }
        val state = controller.playbackState
        val playingNow = state?.state == PlaybackState.STATE_PLAYING
        val extracted = extractTrack(controller.metadata)

        if (extracted == null) {
            flushPendingDelta()
            isPlaying = false
            activeKey = null
            activeMeta = null
            tickerJob?.cancel()
            tickerJob = null
            return
        }

        val trackKey = "${extracted.id}|${extracted.title}|${extracted.artist}"
        if (trackKey != activeKey) {
            flushPendingDelta()
            activeKey = trackKey
            activeMeta = extracted
            lastSampleAt = System.currentTimeMillis()
            playedThisTrack = 0L
            playCounted = false
            maybeFetchEnrichment(extracted)
        } else {
            // Merge newly available metadata (e.g. album or duration loaded slightly after title).
            val prev = activeMeta
            activeMeta = prev?.copy(
                album = prev.album ?: extracted.album,
                albumId = prev.albumId ?: extracted.albumId,
                artistId = prev.artistId ?: extracted.artistId,
                artUrl = prev.artUrl ?: extracted.artUrl,
                durationMs = if (extracted.durationMs > 0L) extracted.durationMs else prev.durationMs,
            ) ?: extracted
        }

        if (isPlaying && !playingNow) {
            flushPendingDelta()
            isPlaying = false
            tickerJob?.cancel()
            tickerJob = null
        } else if (!isPlaying && playingNow) {
            isPlaying = true
            lastSampleAt = System.currentTimeMillis()
            startTicker()
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                flushPendingDelta()
            }
        }
    }

    @Synchronized
    private fun flushPendingDelta() {
        if (!isPlaying) return
        val baseTrack = activeMeta ?: return
        val now = System.currentTimeMillis()
        val step = (now - lastSampleAt).coerceIn(0L, MAX_STEP_MS)
        lastSampleAt = now
        if (step <= 0L) return

        playedThisTrack += step
        val threshold = if (baseTrack.durationMs > 0L) {
            min(baseTrack.durationMs / 2L, PLAY_CEILING_MS).coerceAtLeast(PLAY_FLOOR_MS)
        } else {
            PLAY_FLOOR_MS
        }
        val countsAsPlay = !playCounted && playedThisTrack >= threshold
        if (countsAsPlay) playCounted = true

        val enriched = enrichCache[baseTrack.id]
        val finalAlbum = baseTrack.album ?: enriched?.albumName
        val finalAlbumId = baseTrack.albumId ?: enriched?.albumId
        val finalArtistId = baseTrack.artistId ?: enriched?.artistId
        val finalArt = baseTrack.artUrl ?: enriched?.thumbnailUrl

        scope.launch {
            BitChordBridge.recordLivePlayback(
                trackId = baseTrack.id,
                title = baseTrack.title,
                artist = baseTrack.artist,
                album = finalAlbum,
                albumId = finalAlbumId,
                artistId = finalArtistId,
                artUrl = finalArt,
                playedMs = step,
                countsAsPlay = countsAsPlay,
                atMs = now,
            )
        }
    }

    private fun maybeFetchEnrichment(track: SessionTrack) {
        if (track.album != null && track.artistId != null) return
        if (track.id.length != YOUTUBE_ID_LENGTH) return
        if (!enrichAsked.add(track.id)) return
        scope.launch {
            YtMusicRepository.trackLinks(track.id).getOrNull()?.let { fetched ->
                enrichCache[track.id] = fetched
            }
        }
    }

    private fun extractTrack(metadata: MediaMetadata?): SessionTrack? {
        metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val artist = (
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            )?.trim().orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() }
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val rawId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim()?.takeIf { it.isNotEmpty() }
        val trackId = rawId ?: "live:${title.lowercase(Locale.ROOT)}::${artist.lowercase(Locale.ROOT)}"
        val rawArt = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        val safeArt = rawArt?.takeIf {
            it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
        }
        return SessionTrack(
            id = trackId,
            title = title,
            artist = artist,
            album = album,
            albumId = null,
            artistId = null,
            artUrl = safeArt,
            durationMs = durationMs,
        )
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 8_000L
        const val MAX_STEP_MS = 15_000L
        const val PLAY_FLOOR_MS = 30_000L
        const val PLAY_CEILING_MS = 4 * 60_000L
        const val YOUTUBE_ID_LENGTH = 11
    }
}
