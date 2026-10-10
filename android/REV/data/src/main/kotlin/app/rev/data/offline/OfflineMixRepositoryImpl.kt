package app.rev.data.offline

import app.rev.domain.model.Mix
import app.rev.domain.model.Track
import app.rev.domain.offline.OfflineMixes
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.OfflineMixRepository
import app.rev.domain.repository.OfflineRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Builds mixes from downloaded songs; liked songs come from the cached favorites. */
class OfflineMixRepositoryImpl(
    private val offline: OfflineRepository,
    private val library: LibraryRepository,
) : OfflineMixRepository {
    private fun likedIds(): Flow<Set<String>> = library.observeFavorites().map { f -> f.tracks.map { it.id }.toSet() }

    override fun observeMixes(): Flow<List<Mix>> =
        combine(offline.observeDownloadedTracks(), likedIds().onStart { emit(emptySet()) }) { tracks, liked ->
            OfflineMixes.available(tracks, liked)
        }.distinctUntilChanged()

    override suspend fun tracks(mix: Mix): List<Track> =
        OfflineMixes.tracks(mix, offline.observeDownloadedTracks().first(), likedIds().first())
}
