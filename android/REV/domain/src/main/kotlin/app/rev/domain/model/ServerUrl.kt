package app.rev.domain.model

import java.net.URI
import java.net.URISyntaxException

/** A validated, normalized REV server base URL, e.g. `http://100.101.102.103:4600`. No trailing slash. */
@JvmInline
value class ServerUrl private constructor(val value: String) {

    val isHttps: Boolean get() = value.startsWith("https://")

    /** Host as written in the URL, without IPv6 brackets. */
    val host: String get() = URI(value).host.removeSurrounding("[", "]")

    /**
     * True when traffic would travel unencrypted over a network REV can't vouch for.
     * Plain HTTP is fine over Tailscale (WireGuard-encrypted) and on a private LAN.
     */
    val isInsecure: Boolean get() = !isHttps && !isPrivateNetworkHost(host)

    override fun toString(): String = value

    sealed interface ParseError {
        data object Empty : ParseError
        data object Invalid : ParseError
        data object UnsupportedScheme : ParseError
    }

    companion object {
        /** Restores a URL previously produced by [parse]. */
        fun fromNormalized(value: String): ServerUrl = ServerUrl(value)

        fun parse(input: String): Result<ServerUrl, ParseError> {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return Result.Err(ParseError.Empty)
            // Tailscale/LAN addresses are usually typed without a scheme.
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"

            val uri = try {
                URI(withScheme)
            } catch (_: URISyntaxException) {
                return Result.Err(ParseError.Invalid)
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return Result.Err(ParseError.UnsupportedScheme)
            val host = uri.host?.lowercase()
            if (host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
                return Result.Err(ParseError.Invalid)
            }

            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = (uri.rawPath ?: "").trimEnd('/')
            return Result.Ok(ServerUrl("$scheme://$host$port$path"))
        }

        internal fun isPrivateNetworkHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || '.' !in h && ':' !in h) return true // MagicDNS / LAN short names
            if (PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true
            parseIpv4(h)?.let { (a, b) ->
                return a == 10 || a == 127 ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) ||
                    (a == 169 && b == 254) ||
                    (a == 100 && b in 64..127) // CGNAT range used by Tailscale
            }
            if (':' in h) {
                // ::1 loopback, fc00::/7 unique-local (includes Tailscale's fd7a:115c:a1e0::/48), fe80::/10 link-local
                return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") ||
                    h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
            }
            return false
        }

        private val PRIVATE_SUFFIXES = listOf(".ts.net", ".local", ".lan", ".home.arpa", ".internal")

        private fun parseIpv4(host: String): Pair<Int, Int>? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val octets = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
            return octets[0] to octets[1]
        }
    }
}
