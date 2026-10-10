package app.rev.domain.model

enum class ServiceState { Online, Degraded, Offline, NotConfigured }

data class ServiceHealth(
    val state: ServiceState,
    val version: String?,
    /** Short explanation when not online. */
    val detail: String?,
)

enum class SoularrState { Active, Idle, Unknown }

data class SystemStatus(
    val navidrome: ServiceHealth,
    val lidarr: ServiceHealth,
    val slskd: ServiceHealth,
    val soularr: SoularrState,
    val soularrLastActivityAt: Long?,
    val libraryArtists: Int?,
    val libraryAlbums: Int?,
    val libraryTracks: Int?,
    val activeDownloads: Int,
)

/** Live events from the server. */
sealed interface ServerEvent {
    /** The live connection (re)opened; anything cached may be stale. */
    data object Connected : ServerEvent

    data class DownloadChanged(val job: DownloadJob) : ServerEvent

    /** An album became playable. */
    data class LibraryUpdated(val libraryAlbumId: String) : ServerEvent

    data class ServiceChanged(val service: String, val health: ServiceHealth) : ServerEvent
}
