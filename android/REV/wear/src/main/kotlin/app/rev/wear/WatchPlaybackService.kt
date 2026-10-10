package app.rev.wear

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * Plays music stored on the watch. Wear OS expects music to go to Bluetooth headphones, so
 * playback on the watch speaker is suppressed and the system prompts to connect some instead.
 */
@OptIn(UnstableApi::class)
class WatchPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSuppressPlaybackOnUnsuitableOutput(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        session = MediaSession.Builder(this, player).setCallback(Callback()).build()
    }

    /** Media ids are absolute paths of files in the [WatchLibrary]; anything else is dropped. */
    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val root = File(filesDir, "music").canonicalPath
            val resolved = mediaItems.mapNotNull { item ->
                val file = File(item.mediaId)
                if (!file.canonicalPath.startsWith(root) || !file.isFile) return@mapNotNull null
                item.buildUpon().setUri(Uri.fromFile(file)).build()
            }
            return Futures.immediateFuture(resolved)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
