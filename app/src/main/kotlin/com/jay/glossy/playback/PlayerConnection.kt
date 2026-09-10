/**
 * Glossy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.jay.glossy.playback

import com.jay.glossy.R

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.jay.glossy.constants.SleepTimerCustomDaysKey
import com.jay.glossy.constants.SleepTimerDayTimesKey
import com.jay.glossy.constants.SleepTimerDefaultKey
import com.jay.glossy.constants.SleepTimerEnabledKey
import com.jay.glossy.constants.SleepTimerEndTimeKey
import com.jay.glossy.constants.SleepTimerRepeatKey
import com.jay.glossy.constants.SleepTimerStartTimeKey
import com.jay.glossy.constants.EnableCanvasKey
import com.jay.glossy.db.MusicDatabase
import com.jay.glossy.extensions.currentMetadata
import com.jay.glossy.extensions.getCurrentQueueIndex
import com.jay.glossy.extensions.getQueueWindows
import com.jay.glossy.extensions.metadata
import com.jay.glossy.extensions.togglePlayPause
import com.jay.glossy.playback.MusicService.MusicBinder
import com.jay.glossy.playback.queues.Queue
import com.jay.glossy.utils.dataStore
import com.jay.glossy.utils.get
import com.jay.glossy.utils.reportException
import com.j.glossycanvas.core.providers.MonochromeAlbumCanvas
import com.j.glossycanvas.core.providers.MonochromeApiCanvas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// GLOBAL CANVAS URL CACHE
object CanvasUrlCache {
    private val cache = mutableMapOf<String, String>()
    fun get(key: String): String? = cache[key]
    fun put(key: String, url: String) { cache[key] = url }
}

//  TRUE VIDEO DISK CACHE SYSTEM (Like Spotify) 
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object CanvasPlayerCache {
    private var exoPlayer: ExoPlayer? = null
    private var currentUrl: String? = null
    private var simpleCache: SimpleCache? = null

    fun getPlayer(context: Context, url: String): ExoPlayer {
        // 1. Initialize Disk Cache (Saves video files to storage for instant load later)
        if (simpleCache == null) {
            val cacheDir = File(context.cacheDir, "canvas_video_cache")
            val evictor = LeastRecentlyUsedCacheEvictor(200 * 1024 * 1024) // 200MB Max Cache
            simpleCache = SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(context))
        }

        // 2. Initialize Player
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    false
                )
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
            }
        }
        
        // 3. Play from Cache or Network
        if (url != currentUrl) {
            currentUrl = url
            
            val dataSourceFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(simpleCache!!)
                .setUpstreamDataSourceFactory(dataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val mimeType = if (url.lowercase().contains("mp4")) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8
            val mediaItem = MediaItem.Builder().setUri(url).setMimeType(mimeType).build()
            
            val mediaSource = DefaultMediaSourceFactory(cacheDataSourceFactory).createMediaSource(mediaItem)
            
            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
        }
        return exoPlayer!!
    }

    fun clearCache(context: Context) {
        val cacheDir = File(context.cacheDir, "canvas_video_cache")
        if (cacheDir.exists()) cacheDir.deleteRecursively()
        simpleCache?.release()
        simpleCache = null
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        currentUrl = null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConnection(
    context: Context,
    binder: MusicBinder,
    val database: MusicDatabase,
    private val scope: CoroutineScope,
) : Player.Listener {
    private companion object {
        private const val TAG = "PlayerConnection"
    }

    val service = binder.service
    private val playerReadinessFlow = service.isPlayerReady

    val currentCanvasUrl = MutableStateFlow<String?>(null)
    private val isCanvasEnabled = MutableStateFlow(true)

    init {
        // Observe Settings Toggle for Canvas
        scope.launch {
            context.dataStore.data.map { it[EnableCanvasKey] ?: true }.collect {
                isCanvasEnabled.value = it
            }
        }
    }

    private fun getPlayerSafe(): ExoPlayer {
        check(playerReadinessFlow.value) {
            "Player not yet initialized in MusicService; " +
                "service.isPlayerReady=${playerReadinessFlow.value}"
        }
        return try {
            service.player
        } catch (e: UninitializedPropertyAccessException) {
            throw IllegalStateException(
                "MusicService.player field not initialized despite isPlayerReady=true; " +
                    "possible race condition in service startup",
                e,
            )
        }
    }

    private fun getPlayerOrNull(): ExoPlayer? =
        try {
            if (!playerReadinessFlow.value) return null
            service.player
        } catch (_: UninitializedPropertyAccessException) {
            null
        } catch (_: NullPointerException) {
            null
        }

    val player: ExoPlayer
        get() = getPlayerSafe()

    private val isPlayerInitialized = MutableStateFlow(service.isPlayerReady.value)

    val playbackState: MutableStateFlow<Int>
    private val playWhenReady: MutableStateFlow<Boolean>
    val isPlaying: kotlinx.coroutines.flow.StateFlow<Boolean>

    private val initialState: Triple<Int, Boolean, Boolean> =
        try {
            val initialPlayer = getPlayerOrNull()
            if (initialPlayer != null) {
                Triple(
                    initialPlayer.playbackState,
                    initialPlayer.playWhenReady,
                    initialPlayer.playWhenReady && initialPlayer.playbackState != STATE_ENDED,
                )
            } else {
                Timber.tag(TAG).w("Player not ready during construction; using safe defaults")
                Triple(Player.STATE_IDLE, false, false)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during PlayerConnection initialization, using defaults")
            Triple(Player.STATE_IDLE, false, false)
        }

    init {
        playbackState = MutableStateFlow(initialState.first)
        playWhenReady = MutableStateFlow(initialState.second)
        isPlaying =
            combine(playbackState, playWhenReady) { state, ready ->
                ready && state != STATE_ENDED
            }.stateIn(
                scope,
                SharingStarted.Lazily,
                initialState.third,
            )

        scope.launch {
            playerReadinessFlow.collect { ready ->
                isPlayerInitialized.value = ready
            }
        }
    }

    val isEffectivelyPlaying =
        combine(
            isPlaying,
            service.castConnectionHandler?.isCasting ?: MutableStateFlow(false),
            service.castConnectionHandler?.castIsPlaying ?: MutableStateFlow(false),
        ) { localPlaying, isCasting, castPlaying ->
            if (isCasting) castPlaying else localPlaying
        }.stateIn(
            scope,
            SharingStarted.Lazily,
            initialState.third,
        )

    val mediaMetadata = MutableStateFlow(getPlayerOrNull()?.currentMetadata)
    val currentSong =
        mediaMetadata.flatMapLatest {
            database.song(it?.id)
        }.stateIn(scope, SharingStarted.Lazily, null)
    val currentLyrics =
        mediaMetadata.flatMapLatest { mediaMetadata ->
            database.lyrics(mediaMetadata?.id)
        }.stateIn(scope, SharingStarted.Lazily, null)
    val currentFormat =
        mediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }.stateIn(scope, SharingStarted.Lazily, null)

    val queueTitle = MutableStateFlow<String?>(null)
    val queueWindows = MutableStateFlow<List<Timeline.Window>>(emptyList())
    val currentMediaItemIndex = MutableStateFlow(-1)
    val currentWindowIndex = MutableStateFlow(-1)

    val shuffleModeEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(REPEAT_MODE_OFF)

    val canSkipPrevious = MutableStateFlow(true)
    val canSkipNext = MutableStateFlow(true)

    val error = MutableStateFlow<PlaybackException?>(null)
    val isMuted = service.isMuted
    val currentStreamClient = service.currentStreamClient

    val waitingForNetworkConnection = service.waitingForNetworkConnection

    var shouldBlockPlaybackChanges: (() -> Boolean)? = null
    @Volatile
    var allowInternalSync: Boolean = false

    var onSkipPrevious: (() -> Unit)? = null
    var onSkipNext: (() -> Unit)? = null

    private var attachedPlayer: Player? = null

    init {
        scope.launch {
            service.playerFlow.collect { newPlayer ->
                if (newPlayer != null && newPlayer != attachedPlayer) {
                    updateAttachedPlayer(newPlayer)
                }
            }
        }
        val readyPlayer = getPlayerOrNull()
        if (attachedPlayer == null && readyPlayer != null) {
            updateAttachedPlayer(readyPlayer)
        }
    }

    private fun updateAttachedPlayer(newPlayer: Player) {
        attachedPlayer?.removeListener(this)
        attachedPlayer = newPlayer
        newPlayer.addListener(this)
        
        playbackState.value = newPlayer.playbackState
        playWhenReady.value = newPlayer.playWhenReady
        mediaMetadata.value = newPlayer.currentMetadata
        queueTitle.value = service.queueTitle
        queueWindows.value = newPlayer.getQueueWindows()
        currentWindowIndex.value = newPlayer.getCurrentQueueIndex()
        currentMediaItemIndex.value = newPlayer.currentMediaItemIndex
        shuffleModeEnabled.value = newPlayer.shuffleModeEnabled
        repeatMode.value = newPlayer.repeatMode
        
        prefetchCanvasUrls() 
    }

    fun playQueue(queue: Queue) {
        if (!allowInternalSync && shouldBlockPlaybackChanges?.invoke() == true) return
        service.playQueue(queue)
    }

    fun startRadioSeamlessly() {
        if (shouldBlockPlaybackChanges?.invoke() == true) return
        service.startRadioSeamlessly()
    }

    fun playNext(item: MediaItem) = playNext(listOf(item))

    fun playNext(items: List<MediaItem>) {
        if (!allowInternalSync && shouldBlockPlaybackChanges?.invoke() == true) return
        service.playNext(items)
    }

    fun addToQueue(item: MediaItem) = addToQueue(listOf(item))

    fun addToQueue(items: List<MediaItem>) {
        if (!allowInternalSync && shouldBlockPlaybackChanges?.invoke() == true) return
        service.addToQueue(items)
    }

    fun toggleLike() = service.toggleLike()
    fun toggleMute() = service.toggleMute()
    fun setMuted(muted: Boolean) = service.setMuted(muted)
    fun toggleLibrary() = service.toggleLibrary()

    fun togglePlayPause() {
        if (!allowInternalSync && shouldBlockPlaybackChanges?.invoke() == true) return
        val castHandler = service.castConnectionHandler
        if (castHandler?.isCasting?.value == true) {
            if (castHandler.castIsPlaying.value) castHandler.pause() else castHandler.play()
        } else {
            player.togglePlayPause()
        }
    }

    fun play() {
        val castHandler = service.castConnectionHandler
        if (castHandler?.isCasting?.value == true) {
            castHandler.play()
        } else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
        }
    }

    fun pause() {
        val castHandler = service.castConnectionHandler
        if (castHandler?.isCasting?.value == true) {
            castHandler.pause()
        } else {
            player.playWhenReady = false
        }
    }

    fun seekTo(position: Long) {
        val castHandler = service.castConnectionHandler
        if (castHandler?.isCasting?.value == true) {
            castHandler.seekTo(position)
        } else {
            player.seekTo(position)
        }
    }

    fun seekToNext() {
        val castHandler = service.castConnectionHandler
        if (castHandler?.isCasting?.value == true) {
            castHandler.skipToNext()
            return
        }
        player.seekToNext()
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            player.prepare()
        }
        player.playWhenReady = true
        onSkipNext?.invoke()
    }

    var onRestartSong: (() -> Unit)? = null

    fun seekToPrevious() {
        val castHandler = service.castConnectionHandler
        if (castHandler?.isCasting?.value == true) {
            castHandler.skipToPrevious()
            return
        }
        if (player.currentPosition > 3000 || !player.hasPreviousMediaItem()) {
            player.seekTo(0)
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                player.prepare()
            }
            player.playWhenReady = true
            onRestartSong?.invoke()
        } else {
            player.seekToPreviousMediaItem()
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                player.prepare()
            }
            player.playWhenReady = true
            onSkipPrevious?.invoke()
        }
    }

    private fun parseDayTimes(raw: String): Map<Int, Pair<String, String>> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size != 2) return@mapNotNull null
            val dayIndex = parts[0].toIntOrNull() ?: return@mapNotNull null
            val times = parts[1].split("-")
            if (times.size != 2) return@mapNotNull null
            dayIndex to (times[0] to times[1])
        }.toMap()
    }

    private fun checkAndStartAutomaticSleepTimer(): Boolean {
        return false 
    }

    override fun onPlaybackStateChanged(state: Int) {
        playbackState.value = state
        error.value = player.playerError
    }

    override fun onPlayWhenReadyChanged(newPlayWhenReady: Boolean, reason: Int) {
        playWhenReady.value = newPlayWhenReady
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaMetadata.value = mediaItem?.metadata
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
        
        prefetchCanvasUrls()
    }

    private fun prefetchCanvasUrls() {
        if (!isCanvasEnabled.value) {
            currentCanvasUrl.value = null
            return
        }

        val currentItem = getPlayerOrNull()?.currentMediaItem
        val nextIndex = getPlayerOrNull()?.nextMediaItemIndex ?: C.INDEX_UNSET
        val nextItem = if (nextIndex != C.INDEX_UNSET) getPlayerOrNull()?.getMediaItemAt(nextIndex) else null

        if (currentItem != null) {
            val mediaId = currentItem.mediaId
            val cachedUrl = CanvasUrlCache.get(mediaId)
            currentCanvasUrl.value = cachedUrl 
        } else {
            currentCanvasUrl.value = null
        }

        scope.launch(Dispatchers.IO) {
            if (currentItem != null && CanvasUrlCache.get(currentItem.mediaId) == null) {
                val url = fetchCanvasUrl(currentItem)
                currentCanvasUrl.value = url
            }
            if (nextItem != null) {
                fetchCanvasUrl(nextItem)
            }
        }
    }

    private suspend fun fetchCanvasUrl(item: MediaItem): String? {
        val mediaId = item.mediaId
        val cachedUrl = CanvasUrlCache.get(mediaId)
        if (cachedUrl != null) return cachedUrl

        val titleRaw = item.mediaMetadata.title?.toString() ?: ""
        val artistRaw = item.mediaMetadata.artist?.toString() ?: ""
        val albumRaw = item.mediaMetadata.albumTitle?.toString() ?: ""

        val cleanTitle = normalizeCanvasSongTitle(titleRaw)
        val cleanArtist = normalizeCanvasArtistName(artistRaw)

        if (cleanTitle.isBlank()) return null

        return try {
            var videoUrl: String? = null
            if (albumRaw.isNotBlank()) {
                val albumCanvas = MonochromeAlbumCanvas.getByAlbumArtist(albumRaw, cleanArtist)
                videoUrl = albumCanvas?.preferredAnimationUrl
            }
            if (videoUrl == null) {
                val songCanvas = MonochromeApiCanvas.getBySongArtist(cleanTitle, cleanArtist)
                videoUrl = songCanvas?.preferredAnimationUrl
            }
            if (videoUrl != null) {
                CanvasUrlCache.put(mediaId, videoUrl) 
            }
            videoUrl
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun normalizeCanvasSongTitle(raw: String): String = raw
        .replace(Regex("\\s*\\[[^]]*]"), "")
        .replace(Regex("\\s*\\((?:feat\\.?|ft\\.?|featuring|with)\\b[^)]*\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*\\((?:from|official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix)[^)]*\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*-\\s*(?:from|official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix)\\b.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('-')
        .trim()

    private fun normalizeCanvasArtistName(raw: String): String = raw
        .split(Regex("(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)", RegexOption.IGNORE_CASE), limit = 2)
        .firstOrNull()
        .orEmpty()
        .replace(Regex("\\s+"), " ")
        .trim()

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        queueWindows.value = player.getQueueWindows()
        queueTitle.value = service.queueTitle
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
        shuffleModeEnabled.value = enabled
        queueWindows.value = player.getQueueWindows()
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
        prefetchCanvasUrls() 
    }

    override fun onRepeatModeChanged(mode: Int) {
        repeatMode.value = mode
        updateCanSkipPreviousAndNext()
    }

    override fun onPlayerErrorChanged(playbackError: PlaybackException?) {
        if (playbackError != null) reportException(playbackError)
        error.value = playbackError
    }

    private fun updateCanSkipPreviousAndNext() {
        if (!player.currentTimeline.isEmpty) {
            val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            canSkipPrevious.value = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
                !window.isLive || player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            canSkipNext.value = window.isLive && window.isDynamic || player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            canSkipPrevious.value = false
            canSkipNext.value = false
        }
    }

    fun dispose() {
        try {
            attachedPlayer?.removeListener(this)
            attachedPlayer = null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during PlayerConnection disposal")
        }
    }
}
