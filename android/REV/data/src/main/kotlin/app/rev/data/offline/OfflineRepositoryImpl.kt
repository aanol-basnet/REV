package app.rev.data.offline

import app.rev.data.local.offline.OfflineCollectionEntity
import app.rev.data.local.offline.OfflineCollectionSummary
import app.rev.data.local.offline.OfflineTrackDao
import app.rev.data.local.offline.OfflineTrackEntity
import app.rev.domain.model.Album
import app.rev.domain.model.OfflineCollection
import app.rev.domain.model.OfflineKind
import app.rev.domain.model.Playlist
import app.rev.domain.model.StorageUsage
import app.rev.domain.model.Track
import app.rev.domain.model.SmartDownloadSettings
import app.rev.domain.repository.ArtworkPrefetcher
import app.rev.domain.repository.AutomaticCollection
import app.rev.domain.repository.OfflineRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class OfflineRepositoryImpl(
    private val dao: OfflineTrackDao,
    private val scheduler: OfflineDownloadScheduler,
    private val files: OfflineFiles,
    private val settings: OfflineSettings,
    private val artwork: ArtworkPrefetcher,
    /** Re-plans smart downloads; called when their settings change. */
    private val onSmartSettingsChanged: suspend () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : OfflineRepository {

    override fun observeCollections(): Flow<List<OfflineCollection>> =
        dao.observeCollections().map { rows -> rows.mapNotNull { it.toDomain() } }.distinctUntilChanged()

    override fun observeCollection(id: String): Flow<OfflineCollection?> =
        observeCollections().map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    override fun observeDownloadedTrackIds(): Flow<Set<String>> =
        dao.observeDownloadedIds().map { it.toSet() }.distinctUntilChanged()

    override fun observeDownloadedTracks(): Flow<List<Track>> =
        dao.observeDownloaded().map { rows -> rows.map { it.toTrack() } }.distinctUntilChanged()

    override fun observeDownloadedTracks(artistId: String): Flow<List<Track>> =
        dao.observeDownloadedByArtist(artistId).map { rows -> rows.map { it.toTrack() } }.distinctUntilChanged()

    override fun observeStorage(): Flow<StorageUsage> =
        dao.observeUsage().map { StorageUsage(it.bytes, it.tracks, files.availableBytes()) }

    override val wifiOnly: Flow<Boolean> = settings.wifiOnly

    override suspend fun setWifiOnly(enabled: Boolean) {
        settings.setWifiOnly(enabled)
        // Pending downloads keep the constraints they were queued with; re-queue them.
        dao.pendingIds().forEach { scheduler.enqueue(it, enabled, replace = true) }
    }

    override suspend fun downloadAlbum(album: Album, tracks: List<Track>) = save(
        OfflineKind.Album, album.id, album.name, album.artist, album.coverArtId, tracks,
    )

    override suspend fun downloadPlaylist(playlist: Playlist, tracks: List<Track>) = save(
        OfflineKind.Playlist, playlist.id, playlist.name, playlist.owner, playlist.coverArtId, tracks,
    )

    override suspend fun downloadTrack(track: Track) = save(
        OfflineKind.Track, track.id, track.title, track.artist, track.coverArtId, listOf(track),
    )

    private suspend fun save(
        kind: OfflineKind,
        itemId: String,
        title: String,
        subtitle: String?,
        coverArtId: String?,
        tracks: List<Track>,
        automatic: Boolean = false,
    ) {
        val unique = tracks.distinctBy { it.id }
        if (unique.isEmpty()) return
        val time = now()
        val id = OfflineCollection.idFor(kind, itemId)
        // A manual download of something smart downloads already keep becomes manual (and stays).
        if (automatic && dao.isAutomatic(id) == false) return
        dao.saveCollection(
            OfflineCollectionEntity(id, kind.name, itemId, title, subtitle, coverArtId, time, automatic),
            unique.map { OfflineTrackEntity.queued(it, time) },
        )
        val wifiOnly = settings.wifiOnlyNow()
        for (track in unique) {
            dao.markRetrying(track.id) // a previously failed track gets another go
            val row = dao.get(track.id)
            if (row?.state != "Downloaded") scheduler.enqueue(track.id, wifiOnly)
        }
        artwork.prefetch(listOfNotNull(coverArtId) + unique.mapNotNull { it.coverArtId })
    }

    override suspend fun retry(collectionId: String) {
        val wifiOnly = settings.wifiOnlyNow()
        dao.failedIn(collectionId).forEach {
            dao.markRetrying(it.trackId)
            scheduler.enqueue(it.trackId, wifiOnly, replace = true)
        }
    }

    override suspend fun remove(collectionId: String) {
        for (orphan in dao.removeCollection(collectionId)) {
            scheduler.cancel(orphan.trackId)
            files.delete(orphan.trackId)
        }
    }

    override val smartDownloads: Flow<SmartDownloadSettings> = settings.smart

    override suspend fun setSmartDownloads(settings: SmartDownloadSettings) {
        this.settings.setSmart(settings)
        onSmartSettingsChanged()
    }

    override suspend fun syncAutomatic(wanted: List<AutomaticCollection>) {
        val keep = wanted.map { OfflineCollection.idFor(it.kind, it.itemId) }.toSet()
        for (c in wanted) save(c.kind, c.itemId, c.title, c.subtitle, c.coverArtId, c.tracks, automatic = true)
        dao.automaticIds().filterNot { it in keep }.forEach { remove(it) }
    }

    override suspend fun removeAll() {
        dao.collectionIds().forEach { remove(it) }
        scheduler.cancelAll()
    }
}

private fun OfflineCollectionSummary.toDomain(): OfflineCollection? {
    val kind = OfflineKind.entries.firstOrNull { it.name == kind } ?: return null
    return OfflineCollection(
        id, kind, itemId, title, subtitle, coverArtId, totalTracks, downloadedTracks, failedTracks,
        bytesDownloaded, bytesTotal, requestedAt, automatic,
    )
}
