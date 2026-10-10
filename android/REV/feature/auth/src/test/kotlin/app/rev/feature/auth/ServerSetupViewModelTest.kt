package app.rev.feature.auth

import app.rev.domain.model.Result
import app.rev.domain.repository.ProbeError
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServerSetupViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val servers = FakeServerRepository()
    private val vm by lazy { ServerSetupViewModel(servers) }

    @Test
    fun `validates the address locally before probing`() {
        vm.connect()
        assertEquals(ServerSetupError.Empty, vm.state.value.error)

        vm.onUrlChange("ftp://nas")
        vm.connect()
        assertEquals(ServerSetupError.UnsupportedScheme, vm.state.value.error)
        assertTrue(servers.probed.isEmpty())
    }

    @Test
    fun `saves the normalized server when the probe succeeds`() {
        vm.onUrlChange(" 100.64.0.1:4600/ ")
        vm.connect()
        assertEquals("http://100.64.0.1:4600", servers.serverConfig.value?.url?.value)
        assertEquals("abc", servers.serverConfig.value?.instanceId)
        assertFalse(vm.state.value.isChecking)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `reports probe failures and keeps the server unset`() {
        servers.probeResult = Result.Err(ProbeError.NotRev)
        vm.onUrlChange("100.64.0.1:4533")
        vm.connect()
        assertEquals(ServerSetupError.NotRev, vm.state.value.error)
        assertNull(servers.serverConfig.value)

        servers.probeResult = Result.Err(ProbeError.Unreachable)
        vm.connect()
        assertEquals(ServerSetupError.Unreachable, vm.state.value.error)
    }

    @Test
    fun `shows progress and ignores repeat taps while checking`() {
        val gate = CompletableDeferred<Unit>()
        servers.probeGate = gate
        vm.onUrlChange("100.64.0.1:4600")
        vm.connect()
        vm.connect()
        assertTrue(vm.state.value.isChecking)
        assertEquals(1, servers.probed.size)
        gate.complete(Unit)
        assertFalse(vm.state.value.isChecking)
    }

    @Test
    fun `warns about plain http to public hosts only`() {
        vm.onUrlChange("music.example.com")
        assertTrue(vm.state.value.isInsecure)
        vm.onUrlChange("100.64.0.1:4600")
        assertFalse(vm.state.value.isInsecure)
        vm.onUrlChange("https://music.example.com")
        assertFalse(vm.state.value.isInsecure)
    }

    @Test
    fun `saves the optional home-network address with the server`() {
        vm.onUrlChange("https://rev.example.com")
        vm.onLocalUrlChange("192.168.1.69:4600")
        vm.connect()
        assertEquals("https://rev.example.com", servers.serverConfig.value?.url?.value)
        assertEquals("http://192.168.1.69:4600", servers.serverConfig.value?.localUrl?.value)
    }

    @Test
    fun `keeps a home address that is unreachable right now`() {
        servers.probeResults["http://192.168.1.69:4600"] = Result.Err(ProbeError.Unreachable)
        vm.onUrlChange("https://rev.example.com")
        vm.onLocalUrlChange("192.168.1.69:4600")
        vm.connect()
        assertEquals("http://192.168.1.69:4600", servers.serverConfig.value?.localUrl?.value)
    }

    @Test
    fun `refuses a plain-HTTP public host or another installation as the home address`() {
        vm.onUrlChange("https://rev.example.com")
        vm.onLocalUrlChange("http://203.0.113.5:4600")
        vm.connect()
        assertEquals(ServerSetupError.LocalNotLocal, vm.state.value.error)
        assertTrue(servers.probed.isEmpty())

        servers.probeResults["http://192.168.1.70:4600"] = Result.Ok(app.rev.domain.model.ServerInfo("0.1.0", "other"))
        vm.onLocalUrlChange("192.168.1.70:4600")
        vm.connect()
        assertEquals(ServerSetupError.LocalDifferentServer, vm.state.value.error)
        assertNull(servers.serverConfig.value)
    }
}
