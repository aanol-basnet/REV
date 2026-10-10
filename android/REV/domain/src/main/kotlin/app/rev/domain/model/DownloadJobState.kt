package app.rev.domain.model

/** Lifecycle of a server-side acquisition job. Mirrors `DownloadJobState` in shared/schemas. */
enum class DownloadJobState {
    REQUESTED,
    CHECKING,
    ADDED_TO_LIDARR,
    WAITING_FOR_SOULARR,
    SEARCHING,
    QUEUED,
    DOWNLOADING,
    IMPORTING,
    INDEXING,
    AVAILABLE,
    FAILED,
    TIMEOUT,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == AVAILABLE || this == FAILED || this == TIMEOUT || this == CANCELLED
}
