package app.rev.domain.repository

import app.rev.domain.model.HomeFeed
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import kotlinx.coroutines.flow.Flow

/** Home recommendations, cached on the device so Home opens instantly and works offline. */
interface HomeRepository {
    fun observeFeed(): Flow<HomeFeed?>

    /** [force]: ask the server to rebuild rather than reuse its recent result (pull to refresh). */
    suspend fun refresh(force: Boolean = false): RefreshResult

    /** A mix's tracks, freshly shuffled by the server. */
    suspend fun mixTracks(mixId: String): Result<List<Track>, LibraryError>
}
