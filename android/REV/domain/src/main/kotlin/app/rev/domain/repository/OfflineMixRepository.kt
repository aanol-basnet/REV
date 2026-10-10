package app.rev.domain.repository

import app.rev.domain.model.Mix
import app.rev.domain.model.Track
import kotlinx.coroutines.flow.Flow

/** Mixes played from music downloaded to this device; they need no server. */
interface OfflineMixRepository {
    /** The mixes the current downloads can make. */
    fun observeMixes(): Flow<List<Mix>>

    /** [mix]'s songs from downloads, shuffled; also rebuilds a server mix. Empty when nothing downloaded fits. */
    suspend fun tracks(mix: Mix): List<Track>
}
