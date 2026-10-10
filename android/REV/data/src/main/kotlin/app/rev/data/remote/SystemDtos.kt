package app.rev.data.remote

import app.rev.domain.model.ServiceHealth
import app.rev.domain.model.ServiceState
import app.rev.domain.model.SoularrState
import app.rev.domain.model.SystemStatus
import kotlinx.serialization.Serializable

// Wire types for /api/system/status and /api/events, mirroring shared/schemas/src/events.ts.

@Serializable
internal data class ServiceStatusDto(
    val status: String,
    val version: String? = null,
    val detail: String? = null,
) {
    fun toDomain() = ServiceHealth(
        state = when (status) {
            "online" -> ServiceState.Online
            "degraded" -> ServiceState.Degraded
            "not_configured" -> ServiceState.NotConfigured
            else -> ServiceState.Offline
        },
        version = version,
        detail = detail,
    )
}

@Serializable
internal data class SoularrStatusDto(val status: String, val lastActivityAt: Long? = null)

@Serializable
internal data class ServicesDto(
    val navidrome: ServiceStatusDto,
    val lidarr: ServiceStatusDto,
    val slskd: ServiceStatusDto,
    val soularr: SoularrStatusDto,
)

@Serializable
internal data class LibraryStatsDto(val artists: Int, val albums: Int, val tracks: Int? = null)

@Serializable
internal data class DownloadStatsDto(val active: Int)

@Serializable
internal data class SystemStatusDto(
    val services: ServicesDto,
    val library: LibraryStatsDto? = null,
    val downloads: DownloadStatsDto,
) {
    fun toDomain() = SystemStatus(
        navidrome = services.navidrome.toDomain(),
        lidarr = services.lidarr.toDomain(),
        slskd = services.slskd.toDomain(),
        soularr = when (services.soularr.status) {
            "active" -> SoularrState.Active
            "idle" -> SoularrState.Idle
            else -> SoularrState.Unknown
        },
        soularrLastActivityAt = services.soularr.lastActivityAt,
        libraryArtists = library?.artists,
        libraryAlbums = library?.albums,
        libraryTracks = library?.tracks,
        activeDownloads = downloads.active,
    )
}

@Serializable
internal data class CastTokenDto(val baseUrl: String, val token: String, val expiresAt: Long)

@Serializable
internal data class ServerAddressesDto(val lanUrls: List<String> = emptyList())
