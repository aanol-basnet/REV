package app.rev.domain.model

/** What a REV server reports about itself at `/api/system/health`. */
data class ServerInfo(
    val version: String,
    val instanceId: String,
)

/** The server this device is configured to use. */
data class ServerConfig(
    /** The address that works from anywhere (e.g. a public HTTPS tunnel). Always the fallback. */
    val url: ServerUrl,
    /** Instance id seen when the server was configured; used to detect a different server at the same address. */
    val instanceId: String,
    /**
     * Optional direct address on the home network, preferred whenever it answers as this same
     * installation ([instanceId]). Lets streaming and downloads skip the tunnel at home.
     */
    val localUrl: ServerUrl? = null,
    /**
     * Whether the app may find (and follow) the home-network address by asking the server, e.g.
     * after a new DHCP lease. Off once the user removes the address.
     */
    val localDiscovery: Boolean = true,
)

/** Which of the configured addresses requests currently go to. */
enum class ConnectionRoute { Local, Remote }

data class ActiveEndpoint(val url: ServerUrl, val route: ConnectionRoute)

data class User(
    val id: Int,
    val username: String,
)
