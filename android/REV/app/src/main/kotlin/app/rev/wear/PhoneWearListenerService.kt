package app.rev.wear

import app.rev.data.di.ApplicationScope
import app.rev.wearprotocol.WearPaths
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Receives requests from the REV watch app. Long work runs in the app scope. */
@AndroidEntryPoint
class PhoneWearListenerService : WearableListenerService() {
    @Inject lateinit var bridge: WearBridge

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.CONTROL -> bridge.control(event.data.decodeToString())
            WearPaths.ALBUMS_REQUEST -> scope.launch { runCatching { bridge.sendAlbumList(event.sourceNodeId) } }
            WearPaths.ALBUM_SEND -> scope.launch { runCatching { bridge.sendAlbum(event.sourceNodeId, event.data.decodeToString()) } }
        }
    }
}
