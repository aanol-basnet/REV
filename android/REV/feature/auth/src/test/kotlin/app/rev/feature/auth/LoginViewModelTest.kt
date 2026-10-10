package app.rev.feature.auth

import app.rev.domain.model.AuthState
import app.rev.domain.model.Result
import app.rev.domain.model.ServerConfig
import app.rev.domain.model.ServerUrl
import app.rev.domain.repository.LoginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoginViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val config = ServerConfig((ServerUrl.parse("100.64.0.1:4600") as Result.Ok).value, "abc")
    private val auth = FakeAuthRepository(AuthState.SignedOut(config))
    private val servers = FakeServerRepository().apply { serverConfig.value = config }
    private val vm by lazy { LoginViewModel(auth, servers) }

    @Test
    fun `shows which server the user is signing in to`() {
        assertEquals("http://100.64.0.1:4600", vm.state.value.serverUrl)
    }

    @Test
    fun `cannot submit without both fields`() {
        assertFalse(vm.state.value.canSubmit)
        vm.onUsernameChange("alice")
        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        assertTrue(auth.logins.isEmpty())
    }

    @Test
    fun `successful login clears the password from state`() {
        vm.onUsernameChange("alice")
        vm.onPasswordChange("secret password")
        vm.submit()
        assertEquals(listOf("alice" to "secret password"), auth.logins)
        assertEquals("", vm.state.value.password)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `failed login shows the error until the user edits a field`() {
        auth.loginResult = Result.Err(LoginError.InvalidCredentials)
        vm.onUsernameChange("alice")
        vm.onPasswordChange("wrong")
        vm.submit()
        assertEquals(LoginError.InvalidCredentials, vm.state.value.error)
        assertFalse(vm.state.value.isSubmitting)

        vm.onPasswordChange("wrong2")
        assertNull(vm.state.value.error)
    }

    @Test
    fun `change server forgets the configured server`() {
        vm.changeServer()
        assertNull(servers.serverConfig.value)
    }
}
