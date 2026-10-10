package app.rev.wear

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import app.rev.core.ui.components.CoverArt
import app.rev.data.local.offline.OfflineTrackDao
import app.rev.domain.model.OfflineKind
import app.rev.domain.model.OfflineState
import app.rev.domain.repository.OfflineRepository
import app.rev.playback.PlaybackController
import app.rev.wearprotocol.Control
import app.rev.wearprotocol.NowPlayingKeys
import app.rev.wearprotocol.PhoneAlbum
import app.rev.wearprotocol.TrackHeader
import app.rev.wearprotocol.WearPaths
import app.rev.wearprotocol.encodeAlbums
import app.rev.wearprotocol.writeHeader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The phone's side of the REV watch app: publishes what's playing, executes remote controls, lists
 * albums downloaded on the phone, and copies one to the watch on request. Everything goes over the
 * Wearable Data Layer (Bluetooth/Wi-Fi to the paired watch); the watch never talks to the server.
 */
@Singleton
class WearBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playback: PlaybackController,
    private val offline: OfflineRepository,
    private val offlineTracks: OfflineTrackDao,
) {
    private val data by lazy { Wearable.getDataClient(context) }
    private val messages by lazy { Wearable.getMessageClient(context) }
    private val channels by lazy { Wearable.getChannelClient(context) }

    /** Keeps the watch's "Phone" card in step with playback. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            playback.state
                .map { s -> Snapshot(s.current?.title, s.current?.artist, s.current?.coverArtId, s.isPlaying, s.hasNext) }
                .distinctUntilChanged()
                .collect { publish(it) }
        }
    }

    private data class Snapshot(val title: String?, val artist: String?, val coverArtId: String?, val playing: Boolean, val hasNext: Boolean)

    private suspend fun publish(s: Snapshot) {
        try {
            val request = PutDataMapRequest.create(WearPaths.NOW_PLAYING).apply {
                dataMap.putString(NowPlayingKeys.TITLE, s.title.orEmpty())
                dataMap.putString(NowPlayingKeys.ARTIST, s.artist.orEmpty())
                dataMap.putBoolean(NowPlayingKeys.PLAYING, s.playing)
                dataMap.putBoolean(NowPlayingKeys.HAS_NEXT, s.hasNext)
                s.coverArtId?.let { id -> artwork(id)?.let { dataMap.putAsset(NowPlayingKeys.ARTWORK, it) } }
                dataMap.putLong(NowPlayingKeys.UPDATED_AT, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            data.putDataItem(request).await()
        } catch (e: Exception) {
            // No Wear OS app/watch or no Play services: nothing to do.
            Log.d(TAG, "Not publishing to watch: ${e.message}")
        }
    }

    private suspend fun artwork(coverArtId: String): Asset? {
        val request = ImageRequest.Builder(context).data(CoverArt(coverArtId, WATCH_ART_SIZE)).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        return Asset.createFromBytes(bytes)
    }

    fun control(command: String) {
        when (runCatching { Control.valueOf(command) }.getOrNull()) {
            Control.PlayPause -> playback.togglePlayPause()
            Control.Next -> playback.next()
            Control.Previous -> playback.previous()
            null -> Unit
        }
    }

    /** Albums and playlists fully downloaded on the phone. */
    suspend fun sendAlbumList(nodeId: String) {
        val albums = offline.observeCollections().first()
            .filter { (it.kind == OfflineKind.Album || it.kind == OfflineKind.Playlist) && it.state == OfflineState.Downloaded }
            .map { PhoneAlbum(it.id, it.title, it.subtitle, it.totalTracks, it.bytesTotal) }
        messages.sendMessage(nodeId, WearPaths.ALBUMS_LIST, encodeAlbums(albums)).await()
    }

    /** Copies a downloaded album to the watch, one channel per track. */
    suspend fun sendAlbum(nodeId: String, collectionId: String) {
        val collection = offline.observeCollection(collectionId).first() ?: return
        val tracks = offlineTracks.downloadedIn(collectionId)
        tracks.forEachIndexed { index, track ->
            val file = File(track.localUri)
            if (!file.isFile) return@forEachIndexed
            val channel = channels.openChannel(nodeId, WearPaths.TRACK_CHANNEL).await()
            try {
                channels.getOutputStream(channel).await().use { out ->
                    writeHeader(
                        out,
                        TrackHeader(
                            albumId = collection.id,
                            albumTitle = collection.title,
                            artist = track.artist,
                            trackId = track.trackId,
                            title = track.title,
                            trackNumber = track.trackNumber,
                            discNumber = track.discNumber,
                            durationMs = track.durationMs,
                            extension = file.extension.ifEmpty { "audio" },
                            sizeBytes = file.length(),
                            index = index,
                            total = tracks.size,
                        ),
                    )
                    file.inputStream().use { it.copyTo(out) }
                }
            } finally {
                channels.close(channel)
            }
        }
    }

    private companion object {
        const val TAG = "WearBridge"
        const val WATCH_ART_SIZE = 160
    }
}
