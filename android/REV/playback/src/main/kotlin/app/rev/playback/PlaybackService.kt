package app.rev.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import app.rev.domain.playback.FavoriteEvents
import app.rev.domain.playback.QueueSource
import app.rev.domain.taste.ListeningSession
import app.rev.domain.learning.CurrentSituation
import app.rev.domain.learning.ListenFeatures
import app.rev.domain.learning.PersonalModel
import app.rev.domain.learning.Situation
import app.rev.domain.playback.FeedbackKind
import app.rev.domain.playback.ListenContext
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.rev.domain.taste.QueueDirector
import app.rev.domain.taste.TasteRanker
import app.rev.domain.taste.TasteRepository
import java.time.LocalTime
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.repository.SearchRepository
import app.rev.domain.model.Track
import app.rev.playback.widget.NowPlaying
import app.rev.playback.widget.NowPlayingWidget
import app.rev.playback.widget.WidgetArtwork
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import app.rev.domain.playback.ArtworkUrls
import app.rev.domain.playback.CastLinks
import app.rev.domain.playback.PlaybackReporter
import app.rev.domain.playback.PlaybackSettings
import app.rev.domain.playback.AutoplayRepository
import app.rev.domain.playback.PlaybackSettingsRepository
import app.rev.domain.playback.ReplayGainMath
import app.rev.domain.playback.QueueStore
import app.rev.domain.playback.TrackSourceResolver
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call

/**
 * Owns the player for the whole app. The UI talks to it only through a MediaController, so
 * playback survives activity destruction and is exposed to the notification, lock screen,
 * Bluetooth/headset controls and (later) Android Auto through the same MediaSession.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var sourceResolver: TrackSourceResolver
    @Inject lateinit var mediaCallFactory: Call.Factory
    @Inject lateinit var queueStore: QueueStore
    @Inject lateinit var reporter: PlaybackReporter
    @Inject lateinit var artworkUrls: ArtworkUrls
    @Inject lateinit var playbackSettings: PlaybackSettingsRepository
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var offlineRepository: OfflineRepository
    @Inject lateinit var searchRepository: SearchRepository
    @Inject lateinit var castLinks: CastLinks
    @Inject lateinit var autoplay: AutoplayRepository
    @Inject lateinit var listeningSession: ListeningSession
    @Inject lateinit var taste: TasteRepository
    @Inject lateinit var ranker: TasteRanker
    @Inject lateinit var favoriteEvents: FavoriteEvents
    @Inject lateinit var personalModel: PersonalModel
    @Inject lateinit var situation: CurrentSituation
    private lateinit var smartQueue: SmartQueue

    /** Android Auto is connected: listening in the car. */
    @Volatile private var carConnected = false
    private lateinit var tree: LibraryTree

    private val replayGain = ReplayGainAudioProcessor()
    @Volatile private var settings = PlaybackSettings()

    /** Main-thread scope: the player may only be touched from its application looper. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaLibrarySession? = null
    private lateinit var crossfader: Crossfader
    private lateinit var localPlayer: ExoPlayer
    private lateinit var queuePersister: QueuePersister
    private lateinit var playReports: PlayReportTracker
    private val audioEffects by lazy { AudioEffects(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        val player = buildPlayer(replayGain, ownsAudioFocus = true)
        localPlayer = player
        player.setShuffleOrder(QueueShuffleOrder())
        crossfader = Crossfader(
            main = player,
            scope = scope,
            createTail = {
                ReplayGainAudioProcessor().let { gain ->
                    // Same audio session as the main player, so the equalizer applies to the fade too.
                    buildPlayer(gain, ownsAudioFocus = false).apply { audioSessionId = player.audioSessionId } to gain
                }
            },
            crossfadeMs = { settings.crossfadeSeconds * 1000L },
            gainFor = { item -> ReplayGainMath.linearGain(item?.toTrack()?.replayGain, settings.replayGain, settings.preampDb) },
        ).also { it.start() }
        player.addListener(SkipUnplayableTracks(player))
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applyReplayGain(mediaItem)

            override fun onAudioSessionIdChanged(audioSessionId: Int) = audioEffects.attach(audioSessionId)

            // Turning shuffle on mid-queue shuffles everything after the current song, which plays on.
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled && player.mediaItemCount > 0) {
                    player.setShuffleOrder(QueueShuffleOrder.startingWith(player.currentMediaItemIndex, player.mediaItemCount))
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_TIMELINE_CHANGED)) {
                    publishWidget(player)
                }
            }
        })
        scope.launch {
            playbackSettings.settings.collect {
                settings = it
                applyReplayGain(player.currentMediaItem)
                audioEffects.apply(it.equalizer)
            }
        }

        audioEffects.attach(player.audioSessionId)

        queuePersister = QueuePersister(player, queueStore, scope) { it.systemArtworkUri() }.also { it.start() }
        AutoplayExtender(player, scope, autoplay, enabled = { settings.autoplay }, toItem = { queueItem(it, QueueSource.Autoplay) }).start()
        smartQueue = SmartQueue(
            player, scope, autoplay, listeningSession, taste, ranker, QueueDirector(hour = { LocalTime.now().hour }),
            enabled = { settings.smartQueue },
            toItem = { track, change -> queueItem(track, QueueSource.Smart, change.name) },
            onChange = ::publishQueueNote,
        ).also { it.start() }
        // Have the taste profile and personal model ready (and fresh) before the first skip.
        scope.launch {
            runCatching { taste.profile() }
            runCatching { personalModel.refresh() }
        }

        tree = LibraryTree(
            libraryRepository,
            offlineRepository,
            searchRepository,
            LibraryTree.Labels(
                recent = getString(R.string.auto_recently_played),
                newest = getString(R.string.auto_recently_added),
                playlists = getString(R.string.auto_playlists),
                downloaded = getString(R.string.auto_downloaded),
                favorites = getString(R.string.auto_favorites),
            ),
        )
        // Casting: the session's player switches between this device and a Cast receiver; the
        // notification, lock screen and app follow whichever is active.
        val sessionPlayer = castCapable(player)
        // Listens are counted on whichever player is active, so casting counts too.
        val deviceState = DeviceState(this, sessionPlayer) { carConnected }
        playReports = PlayReportTracker(
            sessionPlayer,
            reporter,
            scope,
            device = deviceState::snapshot,
            predict = { track -> ranker.scorer().handScore(track) },
            predictSkip = { track, context -> personalModel.skipChance(ListenFeatures.of(track, context, System.currentTimeMillis(), taste.profile())) },
            onStart = { situation.value = Situation.of(it) },
            onListen = { track, context, startedAt, skipped -> learn(track, context, startedAt, skipped, weight = 1.0) },
            favoriteChanges = favoriteEvents.changes,
        ).also { it.start() }
        scope.launch {
            while (true) {
                castLinks.refresh()
                kotlinx.coroutines.delay(CAST_TOKEN_CHECK_MS)
            }
        }
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, SessionCallback())
            .setBitmapLoader(CoilBitmapLoader(this, scope, artworkUrls))
            .setMediaButtonPreferences(feedbackButtons())
            .apply { launchAppIntent()?.let(::setSessionActivity) }
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )

        scope.launch { queuePersister.restoreInto(player, playWhenReady = false) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    @OptIn(UnstableApi::class)
    private fun castCapable(local: ExoPlayer): Player = try {
        val remote = RemoteCastPlayer.Builder(this).setMediaItemConverter(RevCastConverter(castLinks)).build()
        CastPlayer.Builder(this).setLocalPlayer(local).setRemotePlayer(remote).build()
    } catch (e: Exception) {
        Log.w(TAG, "Casting unavailable on this device", e) // e.g. no Google Play services
        local
    }

    /** The main player owns audio focus and noisy handling; the crossfade tail player doesn't. */
    private fun buildPlayer(gain: ReplayGainAudioProcessor, ownsAudioFocus: Boolean): ExoPlayer =
        ExoPlayer.Builder(this, replayGainRenderers(gain))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(revDataSourceFactory(this, mediaCallFactory, sourceResolver)),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                ownsAudioFocus,
            )
            .setHandleAudioBecomingNoisy(ownsAudioFocus)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

    private var lastWidgetState: NowPlaying? = null
    private var widgetJob: Job? = null
    private val widgetArtwork by lazy { WidgetArtwork(applicationContext, artworkUrls) }

    /** Keeps the home-screen widget in step with playback: track, cover, card colour, play state. */
    private fun publishWidget(player: Player) {
        val track = player.currentMediaItem?.toTrack()
        val state = NowPlaying(track?.title, track?.artist, track?.coverArtId, player.isPlaying)
        if (state == lastWidgetState) return
        lastWidgetState = state
        // Only the newest state matters.
        widgetJob?.cancel()
        widgetJob = scope.launch {
            if (!NowPlayingWidget.isPlaced(applicationContext)) return@launch
            val art = runCatching { widgetArtwork.forCover(state.coverArtId) }.getOrNull()
            runCatching { NowPlayingWidget.publish(applicationContext, state.copy(artworkPath = art?.path, accent = art?.accent)) }
        }
    }

    /** The personal model learns from a listen (or a feedback button, which counts double), offline too. */
    private fun learn(track: Track, context: ListenContext, startedAt: Long, skipped: Boolean, weight: Double) {
        scope.launch {
            runCatching {
                personalModel.learn(ListenFeatures.of(track, context, startedAt, taste.profile()), skipped, weight, startedAt)
                personalModel.refresh()
            }
        }
    }

    /** "Not now": skip it, and remember that strongly. */
    private fun notNow() {
        val player = mediaSession?.player ?: return
        val listen = playReports.feedbackGiven() ?: return
        smartQueue.notNow()
        feedback(listen, FeedbackKind.NotNow, skipped = true)
        player.seekToNextMediaItem()
    }

    /** "More like this": songs like it next, and remember that strongly. */
    private fun moreLikeThis() {
        val listen = playReports.feedbackGiven() ?: return
        smartQueue.moreLikeThis()
        feedback(listen, FeedbackKind.MoreLikeThis, skipped = false)
    }

    private fun feedback(listen: PlayReportTracker.FeedbackListen, kind: FeedbackKind, skipped: Boolean) {
        scope.launch { reporter.feedback(listen.track.id, listen.startedAt, kind, listen.positionMs) }
        listen.context?.let { learn(listen.track, it, listen.startedAt, skipped, weight = FEEDBACK_WEIGHT) }
    }

    /** The feedback buttons on the notification and lock screen, after the usual controls. */
    private fun feedbackButtons(): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(CommandButton.ICON_THUMB_DOWN_UNFILLED)
            .setDisplayName(getString(R.string.feedback_not_now))
            .setSessionCommand(FeedbackCommands.NOT_NOW)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_THUMB_UP_UNFILLED)
            .setDisplayName(getString(R.string.feedback_more_like_this))
            .setSessionCommand(FeedbackCommands.MORE_LIKE_THIS)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    /** Tells the app (through the session's extras) what the smart queue just changed. */
    private fun publishQueueNote(note: QueueNote) {
        mediaSession?.setSessionExtras(
            Bundle().apply {
                putString(QueueNotes.CHANGE, note.change.name)
                putString(QueueNotes.TITLE, note.title)
                putLong(QueueNotes.AT, System.currentTimeMillis())
            },
        )
    }

    private fun applyReplayGain(item: MediaItem?) {
        replayGain.setGain(ReplayGainMath.linearGain(item?.toTrack()?.replayGain, settings.replayGain, settings.preampDb))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in the background if something is playing; otherwise let the service go.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Nothing plays once the service is gone; don't leave the widget showing "pause".
        lastWidgetState?.takeIf { it.isPlaying }?.let { state ->
            runBlocking {
                withTimeoutOrNull(WIDGET_FINAL_UPDATE_MS) {
                    val art = runCatching { widgetArtwork.forCover(state.coverArtId) }.getOrNull()
                    runCatching {
                        NowPlayingWidget.publish(applicationContext, state.copy(isPlaying = false, artworkPath = art?.path, accent = art?.accent))
                    }
                }
            }
        }
        mediaSession?.run {
            // Last chance to remember where we were; the write is small and local.
            runBlocking { queuePersister.saveNow() }
            crossfader.release()
            audioEffects.release()
            player.release()
            localPlayer.release() // the session's player may be the CastPlayer wrapping it
            release()
        }
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    private fun launchAppIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {
        /** Controllers send items without URIs; restore the resolvable `rev:` URI. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                if (LibraryTree.isTreeId(item.mediaId)) {
                    val (tracks, start) = tree.resolve(item.mediaId)
                    listOfNotNull(tracks.getOrNull(start)?.let { queueItem(it, QueueSource.Car) })
                } else {
                    listOf(item.withRevUri())
                }
            }.toMutableList()
        }

        /**
         * Playing one track from a browser (Android Auto) queues its whole album/playlist, starting
         * at that track; playing an album or playlist starts at its top.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val single = mediaItems.singleOrNull()
            if (single == null || !LibraryTree.isTreeId(single.mediaId)) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            return scope.future {
                val (tracks, start) = tree.resolve(single.mediaId)
                MediaItemsWithStartPosition(tracks.map { queueItem(it, QueueSource.Car) }, start, C.TIME_UNSET)
            }
        }

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(FeedbackCommands.NOT_NOW)
                .add(FeedbackCommands.MORE_LIKE_THIS)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                FeedbackCommands.NOT_NOW.customAction -> notNow()
                FeedbackCommands.MORE_LIKE_THIS.customAction -> moreLikeThis()
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (controller.packageName in CAR_PACKAGES) carConnected = true
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (controller.packageName in CAR_PACKAGES) carConnected = false
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = tree.children(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, to), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val results = tree.search(query)
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = tree.search(query)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        /** System "resume playback" (e.g. from the media controls after a reboot). */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            queuePersister.loadForResumption() ?: throw UnsupportedOperationException("Nothing to resume")
        }
    }

    private fun queueItem(track: Track, source: QueueSource, smartChange: String? = null): MediaItem =
        track.toMediaItem(track.systemArtworkUri(), source, smartChange)

    /**
     * A track that can't load (not downloaded while offline, deleted on the server, ...) is skipped
     * instead of stopping the queue. Gives up once every remaining track has failed in a row.
     */
    private class SkipUnplayableTracks(private val player: Player) : Player.Listener {
        private var consecutiveFailures = 0

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error on item ${player.currentMediaItemIndex}: ${error.errorCodeName}", error)
            consecutiveFailures++
            if (player.hasNextMediaItem() && consecutiveFailures < player.mediaItemCount) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveFailures = 0
        }
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val CAST_TOKEN_CHECK_MS = 30 * 60_000L
        const val WIDGET_FINAL_UPDATE_MS = 1_000L
        const val FEEDBACK_WEIGHT = 2.0
        val CAR_PACKAGES = setOf("com.google.android.projection.gearhead", "com.google.android.apps.automotive.media")
    }
}
