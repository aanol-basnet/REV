package app.rev.data.playback

import app.rev.data.remote.CastTokenDto
import app.rev.data.remote.LibraryApi
import app.rev.domain.model.Result
import app.rev.domain.playback.CastLinks
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodeURLQueryComponent
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Builds Cast receiver URLs from a server-issued media token, refreshing it before it expires. */
class CastLinksImpl(
    private val api: LibraryApi,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : CastLinks {
    private val token = AtomicReference<CastTokenDto?>(null)
    private val mutex = Mutex()

    override fun streamUrl(trackId: String): String? = current()?.let {
        "${it.baseUrl}/api/library/tracks/${trackId.encodeURLPathPart()}/stream?${query(it)}"
    }

    override fun artworkUrl(coverArtId: String): String? = current()?.let {
        "${it.baseUrl}/api/library/artwork/${coverArtId.encodeURLPathPart()}?size=600&${query(it)}"
    }

    override suspend fun refresh() = mutex.withLock {
        val existing = token.get()
        if (existing != null && existing.expiresAt - nowSeconds() > REFRESH_MARGIN_SECONDS) return@withLock
        val fresh = api.castToken()
        if (fresh is Result.Ok) token.set(fresh.value)
    }

    private fun current(): CastTokenDto? = token.get()?.takeIf { it.expiresAt > nowSeconds() }

    private fun query(t: CastTokenDto) = "exp=${t.expiresAt}&cast=${t.token.encodeURLQueryComponent()}"

    private companion object {
        const val REFRESH_MARGIN_SECONDS = 3_600L
    }
}
