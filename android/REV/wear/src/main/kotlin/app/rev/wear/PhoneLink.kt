package app.rev.wear

import android.content.Context
import app.rev.wearprotocol.Control
import app.rev.wearprotocol.WearPaths
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Messages from the watch to the paired phone running REV. */
class PhoneLink(context: Context) {
    private val nodes = Wearable.getNodeClient(context)
    private val messages = Wearable.getMessageClient(context)

    /** Sends to every connected phone; returns false when none is reachable. */
    suspend fun send(path: String, payload: ByteArray = ByteArray(0)): Boolean = try {
        val connected = nodes.connectedNodes.await()
        connected.forEach { messages.sendMessage(it.id, path, payload).await() }
        connected.isNotEmpty()
    } catch (e: Exception) {
        false
    }

    suspend fun control(command: Control) = send(WearPaths.CONTROL, command.name.toByteArray())

    suspend fun requestAlbums() = send(WearPaths.ALBUMS_REQUEST)

    suspend fun requestAlbum(collectionId: String) = send(WearPaths.ALBUM_SEND, collectionId.toByteArray())
}
