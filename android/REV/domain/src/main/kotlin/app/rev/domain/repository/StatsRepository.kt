package app.rev.domain.repository

import app.rev.domain.model.ListeningReport
import app.rev.domain.model.ReportPeriodKind
import app.rev.domain.model.Result
import app.rev.domain.model.SavedSmartPlaylist
import app.rev.domain.model.SmartPlaylist
import app.rev.domain.model.Track

/** Listening reports, built by the server from REV's log of what you played. */
interface StatsRepository {
    /** The last report fetched for the current [period], kept for when the server can't be reached. */
    suspend fun cachedReport(period: ReportPeriodKind): ListeningReport?

    /** [offset] 0 is the current period, 1 the one before; days and hours follow [timeZone]. */
    suspend fun report(period: ReportPeriodKind, offset: Int, timeZone: String): Result<ListeningReport, LibraryError>

    /** Playlists made from the log: on repeat, forgotten favorites, top songs of each year. */
    suspend fun smartPlaylists(timeZone: String): Result<List<SmartPlaylist>, LibraryError>

    /** A smart playlist's songs, in order. */
    suspend fun smartPlaylistTracks(id: String, timeZone: String): Result<List<Track>, LibraryError>

    /** Saves it to Navidrome as an ordinary playlist; saving again updates that playlist. */
    suspend fun saveSmartPlaylist(id: String, timeZone: String): Result<SavedSmartPlaylist, LibraryError>
}
