package app.rev.data.local.offline

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.rev.data.local.library.replayGainOf
import app.rev.domain.model.OfflineTrackState
import app.rev.domain.model.Track

/**
 * A track chosen for offline listening (§23). The row exists from the moment it's requested;
 * [state] says whether the file is there yet. Holds just enough metadata to show and play the
 * track with no server and no library cache.
 */
@Entity(
    tableName = "offline_tracks",
    indices = [Index(value = ["trackId"], unique = true)],
)
data class OfflineTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Navidrome track id. */
    val trackId: String,
    /** Absolute path of the downloaded file; empty until downloaded. */
    val localUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** Final file size once downloaded. */
    val fileSize: Long,
    /** When the download finished; 0 until then. */
    val downloadedAt: Long,
    @ColumnInfo(defaultValue = "Downloaded") val state: String = OfflineTrackState.Downloaded.name,
    @ColumnInfo(defaultValue = "0") val bytesDone: Long = 0,
    /** Expected size: known from metadata up front, confirmed by the server response. */
    @ColumnInfo(defaultValue = "0") val bytesTotal: Long = 0,
    val error: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArtId: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val format: String? = null,
    @ColumnInfo(defaultValue = "0") val requestedAt: Long = 0,
    /** ReplayGain, so downloaded tracks are level-matched offline too. */
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
) {
    val trackState: OfflineTrackState
        get() = OfflineTrackState.entries.firstOrNull { it.name == state } ?: OfflineTrackState.Queued

    fun toTrack() = Track(
        id = trackId, title = title, album = album, albumId = albumId, artist = artist, artistId = artistId,
        trackNumber = trackNumber, discNumber = discNumber, year = null, genre = null, coverArtId = coverArtId,
        durationMs = durationMs, starred = false, format = format, bitRateKbps = null, sampleRateHz = null,
        bitDepth = null, sizeBytes = fileSize.takeIf { it > 0 },
        replayGain = replayGainOf(trackGainDb, albumGainDb, trackPeak, albumPeak),
    )

    companion object {
        fun queued(track: Track, now: Long) = OfflineTrackEntity(
            trackId = track.id,
            localUri = "",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            fileSize = 0,
            downloadedAt = 0,
            state = OfflineTrackState.Queued.name,
            bytesTotal = track.sizeBytes ?: 0,
            albumId = track.albumId,
            artistId = track.artistId,
            coverArtId = track.coverArtId,
            trackNumber = track.trackNumber,
            discNumber = track.discNumber,
            format = track.format,
            requestedAt = now,
            trackGainDb = track.replayGain?.trackGainDb,
            albumGainDb = track.replayGain?.albumGainDb,
            trackPeak = track.replayGain?.trackPeak,
            albumPeak = track.replayGain?.albumPeak,
        )
    }
}

/** An album, playlist or single track the user chose to keep offline. */
@Entity(tableName = "offline_collections")
data class OfflineCollectionEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val itemId: String,
    val title: String,
    val subtitle: String?,
    val coverArtId: String?,
    val requestedAt: Long,
    /** Added by smart downloads rather than by the user. */
    @ColumnInfo(defaultValue = "0") val automatic: Boolean = false,
)

/** Which tracks a collection needs. A track file lives as long as any collection references it. */
@Entity(
    tableName = "offline_collection_tracks",
    primaryKeys = ["collectionId", "trackId"],
    indices = [Index("trackId")],
)
data class OfflineCollectionTrackEntity(
    val collectionId: String,
    val trackId: String,
    val position: Int,
)

/** A collection with its download progress, aggregated from its tracks. */
data class OfflineCollectionSummary(
    val id: String,
    val kind: String,
    val itemId: String,
    val title: String,
    val subtitle: String?,
    val coverArtId: String?,
    val requestedAt: Long,
    val totalTracks: Int,
    val downloadedTracks: Int,
    val failedTracks: Int,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val automatic: Boolean,
)
