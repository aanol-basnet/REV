package app.rev.domain.model

/** What a set of tracks saved on the device is: a whole album, a playlist, or a single track. */
/** [Favorites] is the automatic "smart download" of all favorite tracks. */
enum class OfflineKind { Album, Playlist, Track, Favorites }

/** Per-track download state on the device. */
enum class OfflineTrackState { Queued, Downloading, Downloaded, Failed }

/** Summary state of a collection, as shown in the UI (§48). */
enum class OfflineState { Queued, Downloading, Downloaded, Failed }

/** An album, playlist or track the user chose to keep on the device. */
data class OfflineCollection(
    /** "album:<id>", "playlist:<id>" or "track:<id>". */
    val id: String,
    val kind: OfflineKind,
    /** Library id of the album/playlist/track. */
    val itemId: String,
    val title: String,
    val subtitle: String?,
    val coverArtId: String?,
    val totalTracks: Int,
    val downloadedTracks: Int,
    val failedTracks: Int,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val requestedAt: Long,
    /** Added by smart downloads (not by the user); may be removed automatically. */
    val automatic: Boolean = false,
) {
    val state: OfflineState
        get() = when {
            totalTracks > 0 && downloadedTracks == totalTracks -> OfflineState.Downloaded
            failedTracks > 0 && downloadedTracks + failedTracks == totalTracks -> OfflineState.Failed
            bytesDownloaded > 0 || downloadedTracks > 0 -> OfflineState.Downloading
            else -> OfflineState.Queued
        }

    /** 0–1 by bytes when sizes are known, otherwise by track count. */
    val progress: Float
        get() = when {
            bytesTotal > 0 -> (bytesDownloaded.toFloat() / bytesTotal).coerceIn(0f, 1f)
            totalTracks > 0 -> downloadedTracks.toFloat() / totalTracks
            else -> 0f
        }

    companion object {
        fun idFor(kind: OfflineKind, itemId: String) = "${kind.name.lowercase()}:$itemId"
    }
}

data class StorageUsage(val bytes: Long, val trackCount: Int, val availableBytes: Long)

/** Smart downloads: music kept on the device automatically, within a space limit. */
data class SmartDownloadSettings(
    val favorites: Boolean = false,
    /** How many recently played albums to keep; 0 = off. */
    val recentAlbums: Int = 0,
    val limitBytes: Long = 5L * 1024 * 1024 * 1024,
) {
    val enabled: Boolean get() = favorites || recentAlbums > 0
}
