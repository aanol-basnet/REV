package app.rev.data.library

import app.rev.data.local.library.ListKeys
import app.rev.data.local.library.LibraryDao
import app.rev.data.local.library.toEntity
import app.rev.data.remote.LibraryApi
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
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.RefreshResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class LibraryRepositoryImpl(
    private val api: LibraryApi,
    private val dao: LibraryDao,
    private val now: () -> Long = System::currentTimeMillis,
) : LibraryRepository {

    override fun observeArtists(): Flow<List<Artist>> =
        dao.observeArtistList(ListKeys.ARTISTS).map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override suspend fun refreshArtists(): RefreshResult = api.artists().update { artists ->
        dao.upsertArtists(artists.map { it.toEntity() })
        dao.replaceList(ListKeys.ARTISTS, artists.map { it.id })
    }

    override fun observeArtist(id: String): Flow<ArtistDetail?> =
        combine(dao.observeArtist(id), dao.observeAlbumList(ListKeys.artistAlbums(id))) { artist, albums ->
            artist?.let { ArtistDetail(it.toDomain(), albums.map { a -> a.toDomain() }) }
        }.distinctUntilChanged()

    override suspend fun refreshArtist(id: String): RefreshResult = api.artist(id).update { detail ->
        dao.upsertArtists(listOf(detail.artist.toEntity()))
        dao.upsertAlbums(detail.albums.map { it.toEntity() })
        dao.replaceList(ListKeys.artistAlbums(id), detail.albums.map { it.id })
    }

    override fun observeAlbumList(type: AlbumListType): Flow<List<Album>> =
        dao.observeAlbumList(ListKeys.albums(type.apiName)).map { rows -> rows.map { it.toDomain() } }
            .distinctUntilChanged()

    override suspend fun refreshAlbumList(type: AlbumListType): RefreshResult {
        val albums = when (type) {
            AlbumListType.Alphabetical -> fetchAll { offset -> api.albums(type.apiName, offset, FULL_LIST_PAGE) }
            AlbumListType.Newest, AlbumListType.Recent ->
                when (val page = api.albums(type.apiName, 0, HOME_LIST_SIZE)) {
                    is Result.Ok -> Result.Ok(page.value.items)
                    is Result.Err -> page
                }
        }
        return albums.update { list ->
            dao.upsertAlbums(list.map { it.toEntity() })
            dao.replaceList(ListKeys.albums(type.apiName), list.map { it.id })
        }
    }

    override fun observeAlbum(id: String): Flow<AlbumDetail?> =
        combine(dao.observeAlbum(id), dao.observeAlbumTracks(id)) { album, tracks ->
            album?.let { AlbumDetail(it.toDomain(), tracks.map { t -> t.toDomain() }) }
        }.distinctUntilChanged()

    override suspend fun refreshAlbum(id: String): RefreshResult = api.album(id).update { detail ->
        dao.upsertAlbums(listOf(detail.album.toEntity(tracksCachedAt = now())))
        dao.replaceAlbumTracks(id, detail.tracks.map { it.toEntity() })
    }

    override fun observePlaylists(): Flow<List<Playlist>> =
        dao.observePlaylistList(ListKeys.PLAYLISTS).map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override suspend fun refreshPlaylists(): RefreshResult = api.playlists().update { playlists ->
        dao.upsertPlaylists(playlists.map { it.toEntity() })
        dao.replaceList(ListKeys.PLAYLISTS, playlists.map { it.id })
    }

    override fun observePlaylist(id: String): Flow<PlaylistDetail?> =
        combine(dao.observePlaylist(id), dao.observeTrackList(ListKeys.playlistTracks(id))) { playlist, tracks ->
            playlist?.let { PlaylistDetail(it.toDomain(), tracks.map { t -> t.toDomain() }) }
        }.distinctUntilChanged()

    /** Playlist tracks are cached like album tracks, so a downloaded playlist opens offline. */
    override suspend fun refreshPlaylist(id: String): RefreshResult = api.playlist(id).update { detail ->
        dao.upsertPlaylists(listOf(detail.playlist.toEntity()))
        dao.upsertTracks(detail.tracks.map { it.toEntity() })
        // A playlist may list the same track twice; positions keep both.
        dao.replaceList(ListKeys.playlistTracks(id), detail.tracks.map { it.id })
    }

    override fun observeGenres(): Flow<List<Genre>> =
        dao.observeGenres().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override suspend fun refreshGenres(): RefreshResult = api.genres().update { genres ->
        dao.replaceGenres(genres.map { it.toEntity() })
    }

    override fun observeFavorites(): Flow<Favorites> = combine(
        dao.observeStarredArtists(),
        dao.observeStarredAlbums(),
        dao.observeStarredTracks(),
    ) { artists, albums, tracks ->
        Favorites(artists.map { it.toDomain() }, albums.map { it.toDomain() }, tracks.map { it.toDomain() })
    }.distinctUntilChanged()

    override suspend fun refreshFavorites(): RefreshResult = api.favorites().update { fav ->
        dao.replaceFavorites(
            fav.artists.map { it.toEntity() },
            fav.albums.map { it.toEntity() },
            fav.tracks.map { it.toEntity() },
        )
    }

    override suspend fun artistTopTracks(artistId: String): Result<List<Track>, LibraryError> =
        api.artistTopTracks(artistId)

    override suspend fun track(id: String): Result<Track, LibraryError> =
        when (val remote = api.track(id)) {
            is Result.Ok -> remote
            // Offline: fall back to whatever we cached with the album.
            is Result.Err -> dao.track(id)?.let { Result.Ok(it.toDomain()) } ?: remote
        }

    override suspend fun tracks(offset: Int, size: Int): Result<Page<Track>, LibraryError> =
        api.tracks(offset, size)

    override suspend fun albumsByGenre(genre: String, offset: Int, size: Int): Result<Page<Album>, LibraryError> =
        api.albums("byGenre", offset, size, genre)

    override suspend fun setFavorite(kind: FavoriteKind, id: String, favorite: Boolean): Result<Unit, LibraryError> {
        setStarredLocally(kind, id, favorite)
        val result = api.setFavorite(kind, id, favorite)
        if (result is Result.Err) setStarredLocally(kind, id, !favorite)
        return result
    }

    private suspend fun setStarredLocally(kind: FavoriteKind, id: String, starred: Boolean) = when (kind) {
        FavoriteKind.Artist -> dao.setArtistStarred(id, starred)
        FavoriteKind.Album -> dao.setAlbumStarred(id, starred)
        FavoriteKind.Track -> dao.setTrackStarred(id, starred)
    }

    private suspend fun fetchAll(fetch: suspend (offset: Int) -> Result<Page<Album>, LibraryError>): Result<List<Album>, LibraryError> {
        val all = mutableListOf<Album>()
        var offset = 0
        while (true) {
            when (val page = fetch(offset)) {
                is Result.Err -> return page
                is Result.Ok -> {
                    all += page.value.items
                    if (!page.value.hasMore || page.value.items.isEmpty()) return Result.Ok(all)
                    offset += page.value.items.size
                }
            }
        }
    }

    private suspend inline fun <T> Result<T, LibraryError>.update(store: (T) -> Unit): RefreshResult = when (this) {
        is Result.Ok -> {
            store(value)
            Result.Ok(Unit)
        }
        is Result.Err -> this
    }

    private val AlbumListType.apiName: String
        get() = when (this) {
            AlbumListType.Alphabetical -> "alphabetical"
            AlbumListType.Newest -> "newest"
            AlbumListType.Recent -> "recent"
        }

    private companion object {
        const val FULL_LIST_PAGE = 500
        const val HOME_LIST_SIZE = 30
    }
}
