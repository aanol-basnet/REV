package app.rev.feature.auth

import app.rev.domain.model.AuthState
import app.rev.domain.model.Result
import app.rev.domain.model.ServerConfig
import app.rev.domain.model.ServerInfo
import app.rev.domain.model.ServerUrl
import app.rev.domain.model.User
import app.rev.domain.repository.AuthRepository
import app.rev.domain.repository.LoginError
import app.rev.domain.repository.ProbeError
import app.rev.domain.repository.ServerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = kotlinx.coroutines.Dispatchers.resetMain()
}

class FakeServerRepository : ServerRepository {
    override val serverConfig = MutableStateFlow<ServerConfig?>(null)
    var probeResult: Result<ServerInfo, ProbeError> = Result.Ok(ServerInfo("0.1.0", "abc"))
    /** Per-address answers, overriding [probeResult]. */
    val probeResults = mutableMapOf<String, Result<ServerInfo, ProbeError>>()
    /** When set, probe() suspends until completed, to observe in-flight state. */
    var probeGate: CompletableDeferred<Unit>? = null
    val probed = mutableListOf<ServerUrl>()

    override suspend fun probe(url: ServerUrl): Result<ServerInfo, ProbeError> {
        probed += url
        probeGate?.await()
        return probeResults[url.value] ?: probeResult
    }

    override suspend fun setLocalUrl(url: ServerUrl?) {
        serverConfig.value = serverConfig.value?.copy(localUrl = url)
    }

    override suspend fun setServer(url: ServerUrl, info: ServerInfo) {
        serverConfig.value = ServerConfig(url, info.instanceId)
    }

    override suspend fun clearServer() {
        serverConfig.value = null
    }
}

class FakeAuthRepository(initial: AuthState = AuthState.NeedsServer) : AuthRepository {
    override val authState = MutableStateFlow(initial)
    var loginResult: Result<User, LoginError> = Result.Ok(User(1, "alice"))
    val logins = mutableListOf<Pair<String, String>>()

    override suspend fun login(username: String, password: String): Result<User, LoginError> {
        logins += username to password
        return loginResult
    }

    override suspend fun logout() = Unit
}
