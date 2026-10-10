package app.rev.wear

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.rev.wearprotocol.Control
import app.rev.wearprotocol.NowPlayingKeys
import app.rev.wearprotocol.WearPaths
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** What the phone is playing, from its DataItem. */
data class PhoneNowPlaying(val title: String, val artist: String, val playing: Boolean, val hasNext: Boolean, val artwork: Bitmap?)

/** What the watch itself is playing. */
data class WatchNowPlaying(val title: String, val artist: String, val playing: Boolean, val hasNext: Boolean)

class WatchViewModel(app: Application) : AndroidViewModel(app) {
    val library = WatchGraph.library(app)
    val phoneAlbums = WatchGraph.phoneAlbums
    val transfer = WatchGraph.transfer
    private val phone = PhoneLink(app)
    private val data = Wearable.getDataClient(app)

    private val _phoneNowPlaying = MutableStateFlow<PhoneNowPlaying?>(null)
    val phoneNowPlaying: StateFlow<PhoneNowPlaying?> = _phoneNowPlaying.asStateFlow()

    private val _watchNowPlaying = MutableStateFlow<WatchNowPlaying?>(null)
    val watchNowPlaying: StateFlow<WatchNowPlaying?> = _watchNowPlaying.asStateFlow()

    private var controller: MediaController? = null

    private val dataListener = DataClient.OnDataChangedListener { events ->
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearPaths.NOW_PLAYING }
            .lastOrNull()
            ?.let { event -> val map = DataMapItem.fromDataItem(event.dataItem).dataMap; viewModelScope.launch { showPhone(map) } }
    }

    init {
        data.addListener(dataListener)
        viewModelScope.launch {
            runCatching {
                val items = data.getDataItems(Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WearPaths.NOW_PLAYING).build()).await()
                val map = items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap }
                items.release()
                map?.let { showPhone(it) }
            }
        }
        viewModelScope.launch {
            val token = SessionToken(app, ComponentName(app, WatchPlaybackService::class.java))
            val c = MediaController.Builder(app, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = updateWatch(player)
            })
            updateWatch(c)
        }
    }

    private suspend fun showPhone(map: DataMap) {
        val title = map.getString(NowPlayingKeys.TITLE).orEmpty()
        if (title.isEmpty()) {
            _phoneNowPlaying.value = null
            return
        }
        val artwork = map.getAsset(NowPlayingKeys.ARTWORK)?.let { asset ->
            runCatching {
                data.getFdForAsset(asset).await().inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        _phoneNowPlaying.value = PhoneNowPlaying(
            title = title,
            artist = map.getString(NowPlayingKeys.ARTIST).orEmpty(),
            playing = map.getBoolean(NowPlayingKeys.PLAYING),
            hasNext = map.getBoolean(NowPlayingKeys.HAS_NEXT),
            artwork = artwork,
        )
    }

    private fun updateWatch(player: Player) {
        val meta = player.currentMediaItem?.mediaMetadata
        _watchNowPlaying.value = meta?.let {
            WatchNowPlaying(it.title?.toString().orEmpty(), it.artist?.toString().orEmpty(), player.isPlaying, player.hasNextMediaItem())
        }
    }

    fun phoneControl(command: Control) {
        viewModelScope.launch { phone.control(command) }
    }

    fun loadPhoneAlbums() {
        WatchGraph.phoneAlbums.value = PhoneAlbums.Loading
        viewModelScope.launch {
            if (!phone.requestAlbums()) WatchGraph.phoneAlbums.value = PhoneAlbums.Unreachable
        }
    }

    fun copyFromPhone(collectionId: String) {
        viewModelScope.launch { phone.requestAlbum(collectionId) }
    }

    fun play(album: WatchAlbum, startIndex: Int) {
        val c = controller ?: return
        val items = album.sortedTracks.map { track ->
            MediaItem.Builder()
                .setMediaId(library.fileOf(album, track).absolutePath)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(album.title)
                        .setTrackNumber(track.trackNumber)
                        .build(),
                )
                .build()
        }
        c.setMediaItems(items, startIndex, 0)
        c.prepare()
        c.play()
    }

    fun watchPlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun watchNext() {
        controller?.seekToNext()
    }

    fun watchPrevious() {
        controller?.seekToPrevious()
    }

    fun delete(album: WatchAlbum) {
        library.delete(album.id)
    }

    override fun onCleared() {
        data.removeListener(dataListener)
        controller?.release()
    }
}
