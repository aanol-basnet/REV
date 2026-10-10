package app.rev.data.events

import app.rev.data.remote.DownloadJobDto
import app.rev.data.remote.RevJson
import app.rev.data.remote.ServiceStatusDto
import app.rev.domain.model.ServerEvent
import app.rev.domain.model.ServerUrl
import app.rev.domain.repository.ServerEvents
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Keeps a WebSocket to the server's `/api/events` open while the user is signed in and the app is
 * in the foreground, reconnecting with backoff, and moving to the new address when the active
 * one changes (home network ↔ remote). The session token is attached by the
 * authenticated OkHttp client, exactly as for other requests to the configured server.
 */
class ServerEventsClient(
    httpClient: OkHttpClient,
    private val currentServer: () -> ServerUrl?,
    private val shouldConnect: Flow<Boolean>,
    /** The active server address; a change reconnects to the new one. */
    private val serverChanges: Flow<ServerUrl?> = flowOf(null),
) : ServerEvents {

    private val client = httpClient.newBuilder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(shouldConnect, serverChanges) { active, server -> active to server }
                .distinctUntilChanged()
                .collectLatest { (active, _) -> if (active) keepConnected() }
        }
    }

    private suspend fun keepConnected() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val wasOpen = session()
            backoffMs = if (wasOpen) INITIAL_BACKOFF_MS else minOf(backoffMs * 2, MAX_BACKOFF_MS)
            delay(backoffMs)
        }
    }

    /** One connection. Returns whether it ever opened. Cancelling the caller closes the socket. */
    private suspend fun session(): Boolean {
        val server = currentServer() ?: return false
        val url = server.value.replaceFirst("http", "ws") + "/api/events"
        val ended = CompletableDeferred<Boolean>()
        var opened = false
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    _connected.value = true
                    _events.tryEmit(ServerEvent.Connected)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseEvent(text)?.let { _events.tryEmit(it) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connected.value = false
                    ended.complete(opened)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _connected.value = false
                    ended.complete(opened)
                }
            },
        )
        return try {
            ended.await()
        } finally {
            socket.cancel()
            _connected.value = false
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}

/** Parses one event message; unknown or malformed messages are ignored. */
internal fun parseEvent(text: String): ServerEvent? = try {
    val root = RevJson.parseToJsonElement(text).jsonObject
    val type = root["type"]?.jsonPrimitive?.content
    val data = root["data"] as? JsonObject
    when {
        data == null -> null
        type?.startsWith("download.") == true ->
            ServerEvent.DownloadChanged(RevJson.decodeFromJsonElement(DownloadJobDto.serializer(), data).toDomain())
        type == "library.updated" ->
            data["libraryAlbumId"]?.jsonPrimitive?.content?.let { ServerEvent.LibraryUpdated(it) }
        type != null && type.startsWith("system.") && type.endsWith(".status") ->
            ServerEvent.ServiceChanged(
                service = type.removePrefix("system.").removeSuffix(".status"),
                health = RevJson.decodeFromJsonElement(ServiceStatusDto.serializer(), data).toDomain(),
            )
        else -> null
    }
} catch (_: Exception) {
    null
}
