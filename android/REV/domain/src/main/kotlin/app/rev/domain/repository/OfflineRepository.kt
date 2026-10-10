package app.rev.domain.repository

import app.rev.domain.model.Album
import app.rev.domain.model.OfflineCollection
import app.rev.domain.model.OfflineKind
import app.rev.domain.model.SmartDownloadSettings
import app.rev.domain.model.Playlist
import app.rev.domain.model.StorageUsage
import app.rev.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Music kept on the device for offline listening. Downloads run in the background, survive app
 * restarts, resume partially downloaded files, and respect the Wi-Fi-only setting. A track shared
 * by several downloaded albums/playlists is stored once and deleted only when nothing needs it.
 */
interface OfflineRepository {
    fun observeCollections(): Flow<List<OfflineCollection>>

    fun observeCollection(id: String): Flow<OfflineCollection?>

    /** Ids of tracks fully downloaded to the device. */
    fun observeDownloadedTrackIds(): Flow<Set<String>>

    /** Every track downloaded to this device, most recent first, with genres where the library cache knows them. */
    fun observeDownloadedTracks(): Flow<List<Track>>

    /** Tracks by [artistId] downloaded to this device, most recent first. */
    fun observeDownloadedTracks(artistId: String): Flow<List<Track>>

    fun observeStorage(): Flow<StorageUsage>

    val wifiOnly: Flow<Boolean>

    suspend fun setWifiOnly(enabled: Boolean)

    suspend fun downloadAlbum(album: Album, tracks: List<Track>)

    suspend fun downloadPlaylist(playlist: Playlist, tracks: List<Track>)

    suspend fun downloadTrack(track: Track)

    /** Retries the collection's failed tracks. */
    suspend fun retry(collectionId: String)

    suspend fun remove(collectionId: String)

    suspend fun removeAll()

    val smartDownloads: Flow<SmartDownloadSettings>

    suspend fun setSmartDownloads(settings: SmartDownloadSettings)

    /**
     * Makes the automatic collections match [wanted] exactly: adds/updates those, removes other
     * automatic ones. Never touches (or downgrades) collections the user downloaded themselves.
     */
    suspend fun syncAutomatic(wanted: List<AutomaticCollection>)
}

data class AutomaticCollection(
    val kind: OfflineKind,
    val itemId: String,
    val title: String,
    val subtitle: String?,
    val coverArtId: String?,
    val tracks: List<Track>,
)

/** Keeps artwork for downloaded music available offline. */
fun interface ArtworkPrefetcher {
    fun prefetch(coverArtIds: Collection<String>)
}
