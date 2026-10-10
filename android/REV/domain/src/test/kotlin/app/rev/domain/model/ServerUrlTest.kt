package app.rev.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    private fun ok(input: String): ServerUrl =
        (ServerUrl.parse(input) as? Result.Ok)?.value ?: error("expected $input to parse")

    private fun err(input: String): ServerUrl.ParseError =
        (ServerUrl.parse(input) as? Result.Err)?.error ?: error("expected $input to fail")

    @Test
    fun `adds http scheme and strips trailing slashes`() {
        assertEquals("http://100.101.102.103:4600", ok(" 100.101.102.103:4600/ ").value)
        assertEquals("https://rev.example.com/rev", ok("HTTPS://Rev.Example.com/rev//").value)
    }

    @Test
    fun `rejects empty, malformed and non-http input`() {
        assertEquals(ServerUrl.ParseError.Empty, err("  "))
        assertEquals(ServerUrl.ParseError.UnsupportedScheme, err("ftp://server"))
        assertEquals(ServerUrl.ParseError.Invalid, err("http://"))
        assertEquals(ServerUrl.ParseError.Invalid, err("http://user:pw@server"))
        assertEquals(ServerUrl.ParseError.Invalid, err("http://server/?a=b"))
        assertEquals(ServerUrl.ParseError.Invalid, err("http://ser ver"))
    }

    @Test
    fun `plain http is considered safe on tailscale and private networks`() {
        listOf(
            "100.64.0.1", "100.127.255.255", "192.168.1.10", "10.0.0.2", "172.20.0.1",
            "localhost", "myserver", "server.tail1234.ts.net", "nas.local", "[fd7a:115c:a1e0::1]:4600",
        ).forEach { assertFalse(it, ok(it).isInsecure) }
    }

    @Test
    fun `plain http to public hosts is insecure, https never is`() {
        assertTrue(ok("100.128.0.1").isInsecure)
        assertTrue(ok("8.8.8.8").isInsecure)
        assertTrue(ok("rev.example.com").isInsecure)
        assertFalse(ok("https://rev.example.com").isInsecure)
    }
}
