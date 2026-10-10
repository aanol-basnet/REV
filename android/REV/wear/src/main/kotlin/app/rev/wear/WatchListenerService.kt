package app.rev.wear

import android.util.Log
import app.rev.wearprotocol.WearPaths
import app.rev.wearprotocol.decodeAlbums
import app.rev.wearprotocol.readHeader
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Receives the phone's album list and the tracks it copies over. */
class WatchListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WearPaths.ALBUMS_LIST) {
            WatchGraph.phoneAlbums.value =
                runCatching { PhoneAlbums.Loaded(decodeAlbums(event.data)) }.getOrDefault(PhoneAlbums.Unreachable)
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WearPaths.TRACK_CHANNEL) return
        val channels = Wearable.getChannelClient(this)
        scope.launch {
            try {
                channels.getInputStream(channel).await().use { input ->
                    val header = readHeader(input)
                    WatchGraph.transfer.value = Transfer(header.albumTitle, header.index, header.total)
                    WatchGraph.library(this@WatchListenerService).receive(header, input)
                    WatchGraph.transfer.value =
                        if (header.index + 1 >= header.total) null else Transfer(header.albumTitle, header.index + 1, header.total)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Track transfer failed", e)
                WatchGraph.transfer.value = null
            } finally {
                runCatching { channels.close(channel).await() }
            }
        }
    }

    override fun onDestroy() {
        // Transfers in flight keep the service alive through their open channel; stopping cancels them.
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WatchListener"
    }
}
