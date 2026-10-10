package app.rev.ui

import app.rev.core.ui.components.CatalogCover
import app.rev.core.ui.components.CoverArt
import app.rev.domain.model.ServerUrl
import coil3.intercept.Interceptor
import coil3.map.Mapper
import coil3.request.ImageResult
import coil3.request.Options
import io.ktor.http.encodeURLPathPart

/** Turns a [CoverArt] reference into the configured server's authenticated artwork URL. */
class CoverArtMapper(private val currentServer: () -> ServerUrl?) : Mapper<CoverArt, String> {
    override fun map(data: CoverArt, options: Options): String? {
        val server = currentServer() ?: return null
        return server.value + artworkPath(data)
    }
}

private fun artworkPath(data: CoverArt) = "/api/library/artwork/${data.id.encodeURLPathPart()}?size=${data.size}"

private fun catalogPath(data: CatalogCover) = "/api/catalog/albums/${data.releaseGroupId.encodeURLPathPart()}/cover"

/**
 * Keeps Coil's disk cache independent of which address of the server fetched an image (home
 * network or remote): the key is always the remote address plus the path. Covers cached at home
 * then still show offline or away, and nothing is downloaded twice. Matches the keys used before
 * there were two addresses, so existing caches stay valid.
 */
class StableArtworkCacheKeys(
    private val remoteServer: () -> ServerUrl?,
    private val trustedServers: () -> List<ServerUrl>,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.diskCacheKey != null) return chain.proceed()
        val path = when (val data = request.data) {
            is CoverArt -> artworkPath(data)
            is CatalogCover -> catalogPath(data)
            is String, is coil3.Uri, is android.net.Uri -> {
                val url = data.toString()
                trustedServers().firstOrNull { url.startsWith(it.value + "/") }?.let { url.removePrefix(it.value) }
            }
            else -> null
        }
        val remote = remoteServer()
        if (path == null || remote == null) return chain.proceed()
        return chain.withRequest(request.newBuilder().diskCacheKey(remote.value + path).build()).proceed()
    }
}

/** Turns a [CatalogCover] into the server's catalog cover URL (Cover Art Archive, via REV). */
class CatalogCoverMapper(private val currentServer: () -> ServerUrl?) : Mapper<CatalogCover, String> {
    override fun map(data: CatalogCover, options: Options): String? {
        val server = currentServer() ?: return null
        return server.value + catalogPath(data)
    }
}
