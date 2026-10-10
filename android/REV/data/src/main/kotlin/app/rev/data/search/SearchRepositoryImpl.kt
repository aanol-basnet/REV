package app.rev.data.search

import app.rev.data.remote.LibraryApi
import app.rev.domain.model.AlbumListType
import app.rev.domain.model.Result
import app.rev.domain.model.SearchResults
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.repository.SearchRepository
import app.rev.domain.search.LocalSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SearchRepositoryImpl(
    private val api: LibraryApi,
    private val library: LibraryRepository,
    private val offline: OfflineRepository,
) : SearchRepository {
    override suspend fun search(query: String): Result<SearchResults, LibraryError> = api.search(query.trim())

    override suspend fun searchOnDevice(query: String): SearchResults {
        val artists = library.observeArtists().first()
        val albums = library.observeAlbumList(AlbumListType.Alphabetical).first()
        val tracks = offline.observeDownloadedTracks().first() + library.observeFavorites().first().tracks
        return withContext(Dispatchers.Default) { LocalSearch.search(query.trim(), artists, albums, tracks) }
    }
}
