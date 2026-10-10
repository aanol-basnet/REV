package app.rev.domain.model

/** Reachability of the configured REV server, as last observed. */
sealed interface ConnectionState {
    data object NotConfigured : ConnectionState

    data object Checking : ConnectionState

    data class Online(val info: ServerInfo, val route: ConnectionRoute = ConnectionRoute.Remote) : ConnectionState

    /** The device has no network at all. */
    data object NoNetwork : ConnectionState

    /** The device is online but the server did not answer (server down, Tailscale off, ...). */
    data object ServerUnreachable : ConnectionState

    /** Something answered, but it is a different REV installation than the one configured. */
    data class WrongServer(val info: ServerInfo) : ConnectionState
}
