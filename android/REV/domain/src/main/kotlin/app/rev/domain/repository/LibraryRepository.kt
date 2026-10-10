package app.rev.domain.repository

import app.rev.domain.model.Album
import app.rev.domain.model.AlbumDetail
import app.rev.domain.model.AlbumListType
import app.rev.domain.model.Artist
import app.rev.domain.model.ArtistDetail
import app.rev.domain.model.Favorites
import app.rev.domain.model.FavoriteKind
import app.rev.domain.model.Genre
import app.rev.domain.model.Page
import app.rev.domain.model.Playlist
import app.rev.domain.model.PlaylistDetail
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import kotlinx.coroutines.flow.Flow

sealed interface LibraryError {
    /** The REV server could not be reached (no network, server down, Tailscale off). */
    data object Unreachable : LibraryError

    /** REV is up but its music server (Navidrome) is not. */
    data class MusicServerUnavailable(val message: String) : LibraryError

    data object NotFound : LibraryError

    /** The session ended; the app will return to sign-in. */
    data object SignedOut : LibraryError

    data class Unexpected(val message: String) : LibraryError
}

typealias RefreshResult = Result<Unit, LibraryError>

/**
 * The music library. `observe*` functions read the on-device cache and keep emitting as it
 * changes; `refresh*` functions update that cache from the server. Screens show cached data
 * immediately and refresh in the background, so browsing works offline for anything seen before.
 *
 * Large, rarely revisited collections (all tracks, albums of a genre) are paged straight from
 * the server instead of cached.
 */
interface LibraryRepository {
    fun observeArtists(): Flow<List<Artist>>
    suspend fun refreshArtists(): RefreshResult

    fun observeArtist(id: String): Flow<ArtistDetail?>
    suspend fun refreshArtist(id: String): RefreshResult

    fun observeAlbumList(type: AlbumListType): Flow<List<Album>>
    suspend fun refreshAlbumList(type: AlbumListType): RefreshResult

    fun observeAlbum(id: String): Flow<AlbumDetail?>
    suspend fun refreshAlbum(id: String): RefreshResult

    fun observePlaylists(): Flow<List<Playlist>>
    suspend fun refreshPlaylists(): RefreshResult

    fun observePlaylist(id: String): Flow<PlaylistDetail?>
    suspend fun refreshPlaylist(id: String): RefreshResult

    fun observeGenres(): Flow<List<Genre>>
    suspend fun refreshGenres(): RefreshResult

    fun observeFavorites(): Flow<Favorites>
    suspend fun refreshFavorites(): RefreshResult

    /** An artist's most relevant songs, best first (your plays, then what they're known for). */
    suspend fun artistTopTracks(artistId: String): Result<List<Track>, LibraryError>

    suspend fun track(id: String): Result<Track, LibraryError>
    suspend fun tracks(offset: Int, size: Int): Result<Page<Track>, LibraryError>
    suspend fun albumsByGenre(genre: String, offset: Int, size: Int): Result<Page<Album>, LibraryError>

    /** Updates the cache immediately and syncs to the server; reverts if the server rejects it. */
    suspend fun setFavorite(kind: FavoriteKind, id: String, favorite: Boolean): Result<Unit, LibraryError>
}
