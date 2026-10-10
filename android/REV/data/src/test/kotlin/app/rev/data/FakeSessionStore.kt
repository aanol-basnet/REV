package app.rev.data

import app.rev.data.session.SessionStore
import app.rev.data.session.StoredSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSessionStore(initial: StoredSession? = null) : SessionStore {
    private val state = MutableStateFlow(initial)
    override val session: StateFlow<StoredSession?> = state
    override suspend fun current(): StoredSession? = state.value
    override suspend fun save(session: StoredSession) {
        state.value = session
    }
    override suspend fun clear() {
        state.value = null
    }
}

fun session(access: String = "rev_at_old", refresh: String = "rev_rt_old") = StoredSession(
    userId = 1,
    username = "alice",
    accessToken = access,
    accessTokenExpiresAt = 0,
    refreshToken = refresh,
    refreshTokenExpiresAt = 0,
)
