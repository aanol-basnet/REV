package app.rev.data.library

import androidx.room.withTransaction
import app.rev.data.local.RevDatabase
import app.rev.data.local.library.ListKeys
import app.rev.data.local.library.toEntity
import app.rev.data.remote.LibraryApi
import app.rev.data.remote.PlaylistDetailDto
import app.rev.domain.model.PlaylistDetail
import app.rev.domain.model.Result
import app.rev.domain.repository.AddedToPlaylist
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.learning.InteractionKind
import app.rev.domain.learning.InteractionLog
import app.rev.domain.repository.PlaylistRepository
import app.rev.domain.model.OfflineCollection
import app.rev.domain.model.OfflineKind
import kotlinx.coroutines.flow.first

/**
 * Applies playlist changes on the server, then updates the device cache so screens follow at once.
 * A downloaded playlist is kept in step: new songs are downloaded, removed ones released.
 */
class PlaylistRepositoryImpl(
    private val api: LibraryApi,
    private val db: RevDatabase,
    private val library: LibraryRepository,
    private val offline: OfflineRepository,
    /** Adding songs to a playlist is a strong "I like this", and means you're browsing for songs. */
    private val interactions: InteractionLog? = null,
) : PlaylistRepository {
    private val dao = db.libraryDao()

    override suspend fun create(name: String, trackIds: List<String>): Result<PlaylistDetail, LibraryError> =
        api.createPlaylist(name, trackIds).stored().also {
            if (it is Result.Ok) {
                library.refreshPlaylists()
                interactions?.record(InteractionKind.PlaylistAdd, trackIds)
            }
        }

    override suspend fun duplicate(id: String): Result<PlaylistDetail, LibraryError> =
        api.duplicatePlaylist(id).stored().also { if (it is Result.Ok) library.refreshPlaylists() }

    override suspend fun update(id: String, name: String?, comment: String?): Result<PlaylistDetail, LibraryError> =
        api.updatePlaylist(id, name, comment).stored()

    override suspend fun add(id: String, trackIds: List<String>): Result<AddedToPlaylist, LibraryError> =
        when (val result = api.addPlaylistTracks(id, trackIds)) {
            is Result.Ok -> {
                val detail = store(result.value.playlist)
                interactions?.record(InteractionKind.PlaylistAdd, trackIds)
                Result.Ok(AddedToPlaylist(detail, result.value.added, result.value.skipped))
            }
            is Result.Err -> result
        }

    override suspend fun setTracks(id: String, trackIds: List<String>): Result<PlaylistDetail, LibraryError> =
        api.setPlaylistTracks(id, trackIds).stored()

    override suspend fun delete(id: String): Result<Unit, LibraryError> = when (val result = api.deletePlaylist(id)) {
        is Result.Ok -> {
            db.withTransaction {
                val remaining = dao.listItemIds(ListKeys.PLAYLISTS).filter { it != id }
                dao.replaceList(ListKeys.PLAYLISTS, remaining)
                dao.replaceList(ListKeys.playlistTracks(id), emptyList())
            }
            // Its downloads go with it (songs also kept for other reasons stay).
            val offlineId = OfflineCollection.idFor(OfflineKind.Playlist, id)
            if (offline.observeCollection(offlineId).first() != null) offline.remove(offlineId)
            result
        }
        is Result.Err -> result
    }

    private suspend fun Result<PlaylistDetailDto, LibraryError>.stored(): Result<PlaylistDetail, LibraryError> = when (this) {
        is Result.Ok -> Result.Ok(store(value))
        is Result.Err -> this
    }

    /** Same shape as a playlist refresh: the playlist, its tracks, and their order. */
    private suspend fun store(dto: PlaylistDetailDto): PlaylistDetail {
        val detail = dto.toDomain()
        db.withTransaction {
            dao.upsertPlaylists(listOf(detail.playlist.toEntity()))
            dao.upsertTracks(detail.tracks.map { it.toEntity() })
            dao.replaceList(ListKeys.playlistTracks(detail.playlist.id), detail.tracks.map { it.id })
        }
        val downloaded = offline.observeCollection(OfflineCollection.idFor(OfflineKind.Playlist, detail.playlist.id)).first() != null
        if (downloaded && detail.tracks.isNotEmpty()) offline.downloadPlaylist(detail.playlist, detail.tracks)
        return detail
    }
}
