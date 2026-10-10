package app.rev.domain.repository

import app.rev.domain.model.Result
import app.rev.domain.model.SearchResults

interface SearchRepository {
    /** Searches the library on the server. */
    suspend fun search(query: String): Result<SearchResults, LibraryError>

    /** Searches what's on the device: cached artists and albums, downloaded and liked songs. */
    suspend fun searchOnDevice(query: String): SearchResults
}
