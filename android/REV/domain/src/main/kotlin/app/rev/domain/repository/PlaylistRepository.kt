package app.rev.domain.repository

import app.rev.domain.model.PlaylistDetail
import app.rev.domain.model.Result

/**
 * Making and changing playlists. They live in Navidrome (so they also show up there and in other
 * apps); only playlists with `canEdit` can be changed. Every change needs the server.
 */
interface PlaylistRepository {
    suspend fun create(name: String, trackIds: List<String> = emptyList()): Result<PlaylistDetail, LibraryError>

    /** Null leaves a field as it is; an empty [comment] clears it. */
    suspend fun update(id: String, name: String? = null, comment: String? = null): Result<PlaylistDetail, LibraryError>

    /** Appends songs, leaving out ones already in the playlist. */
    suspend fun add(id: String, trackIds: List<String>): Result<AddedToPlaylist, LibraryError>

    /** Replaces the whole song list: how songs are removed and reordered. */
    suspend fun setTracks(id: String, trackIds: List<String>): Result<PlaylistDetail, LibraryError>

    suspend fun delete(id: String): Result<Unit, LibraryError>

    /** A new, editable copy named "<name> (copy)"; works on read-only playlists too. */
    suspend fun duplicate(id: String): Result<PlaylistDetail, LibraryError>
}

data class AddedToPlaylist(val playlist: PlaylistDetail, val added: Int, val skipped: Int)
