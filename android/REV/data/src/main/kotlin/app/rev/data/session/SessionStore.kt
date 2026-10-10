package app.rev.data.session

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** REV session credentials for the configured server. Never contains the user's password. */
@Serializable
data class StoredSession(
    val userId: Int,
    val username: String,
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
    val refreshTokenExpiresAt: Long,
)

interface SessionStore {
    val session: Flow<StoredSession?>

    suspend fun current(): StoredSession?

    suspend fun save(session: StoredSession)

    suspend fun clear()
}
