package app.rev.data.remote

import app.rev.data.session.SessionStore
import app.rev.data.session.StoredSession
import app.rev.domain.model.ServerUrl
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

internal val RevJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Paths that must never carry an access token. */
private val UNAUTHENTICATED_PATHS = setOf("/api/auth/login", "/api/auth/refresh", "/api/system/health")

/**
 * HTTP clients for talking to REV servers.
 *
 * [client] carries the session, but only on requests to the configured server's addresses
 * ([trustedServers]: the remote one and, if set, the home-network one): on a 401 it refreshes once
 * (single-flight) and retries; if the refresh token is rejected the stored session is cleared,
 * which signs the user out. Requests to any other host are sent untouched. Redirects are never
 * followed, so no response can steer a token to another host or scheme. (Ktor's Auth plugin is
 * deliberately not used: it re-sends cached tokens to any host that answers with a Bearer
 * challenge.)
 *
 * [anonymousClient] has no credentials at all and is used for probing arbitrary addresses.
 */
class RevHttpClient(
    engine: HttpClientEngine,
    private val sessionStore: SessionStore,
    /** Every address of the configured server; only these get the token. Defaults to [currentServer]. */
    trustedServers: (() -> List<ServerUrl>)? = null,
    /** Told when a request couldn't reach its server (connection-level failure). */
    private val onUnreachable: (url: String) -> Unit = {},
    /** Where new requests go (refreshes included), or null. Must be cheap: called per request. */
    private val currentServer: () -> ServerUrl?,
) {
    private val trustedServers: () -> List<ServerUrl> = trustedServers ?: { listOfNotNull(currentServer()) }

    private val refreshMutex = Mutex()

    val anonymousClient: HttpClient = HttpClient(engine) { commonConfig() }

    val client: HttpClient = HttpClient(engine) { commonConfig() }.apply {
        plugin(HttpSend).intercept { request ->
            if (!shouldAuthenticate(request.url.build())) return@intercept execute(request)
            val session = sessionStore.current() ?: return@intercept execute(request)

            request.headers[HttpHeaders.Authorization] = "Bearer ${session.accessToken}"
            val call = try {
                execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onUnreachable(request.url.buildString())
                throw e
            }
            if (call.response.status != HttpStatusCode.Unauthorized) return@intercept call

            val refreshed = refreshAfterRejection(session.accessToken) ?: return@intercept call
            request.headers[HttpHeaders.Authorization] = "Bearer ${refreshed.accessToken}"
            execute(request)
        }
    }

    /**
     * The access token to send to [url], or null if [url] is not on the configured server.
     * For HTTP stacks other than Ktor (the media player's OkHttp).
     */
    suspend fun accessTokenFor(url: String): String? {
        val parsed = try {
            Url(url)
        } catch (_: Exception) {
            return null
        }
        return if (shouldAuthenticate(parsed)) sessionStore.current()?.accessToken else null
    }

    /** For other HTTP stacks: a request to [url] failed to connect. */
    fun reportUnreachable(url: String) = onUnreachable(url)

    /** Refreshes after [rejectedAccessToken] got a 401; null if the session is gone or unreachable. */
    suspend fun refreshedAccessToken(rejectedAccessToken: String): String? =
        refreshAfterRejection(rejectedAccessToken)?.accessToken

    private fun HttpClientConfig<*>.commonConfig() {
        expectSuccess = false
        // REV never redirects; following one could carry the token to whatever it points at.
        followRedirects = false
        install(ContentNegotiation) { json(RevJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
    }

    private fun shouldAuthenticate(url: Url): Boolean {
        val full = url.toString()
        val server = trustedServers().firstOrNull { full.startsWith(it.value + "/") } ?: return false
        // Path relative to the server base, which may itself include a path prefix.
        val path = full.removePrefix(server.value).substringBefore('?')
        return path !in UNAUTHENTICATED_PATHS
    }

    /** Refreshes the session unless a concurrent request already did. Null if the session is gone. */
    private suspend fun refreshAfterRejection(rejectedAccessToken: String): StoredSession? =
        refreshMutex.withLock {
            val current = sessionStore.current() ?: return@withLock null
            if (current.accessToken != rejectedAccessToken) return@withLock current
            refresh(current.refreshToken)
        }

    private suspend fun refresh(refreshToken: String): StoredSession? {
        val server = currentServer() ?: return null
        val response = try {
            anonymousClient.post("${server.value}/api/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshRequestDto(refreshToken))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null // Unreachable: keep the session and try again later.
        }
        return when {
            response.status.isSuccess() ->
                response.body<TokenResponseDto>().toStoredSession().also { sessionStore.save(it) }
            response.status == HttpStatusCode.Unauthorized -> {
                sessionStore.clear()
                null
            }
            else -> null
        }
    }
}

internal fun TokenResponseDto.toStoredSession() = StoredSession(
    userId = user.id,
    username = user.username,
    accessToken = accessToken,
    accessTokenExpiresAt = accessTokenExpiresAt,
    refreshToken = refreshToken,
    refreshTokenExpiresAt = refreshTokenExpiresAt,
)
