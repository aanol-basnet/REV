package app.rev.domain.model

/** Report periods; reports are built on the server from REV's listening log. */
enum class ReportPeriodKind { Week, Month, Year, All }

data class ReportPeriod(
    val kind: ReportPeriodKind,
    /** 0 is the current period, 1 the one before, and so on. */
    val offset: Int,
    val timeZone: String,
    /** Start (inclusive) and end (exclusive), epoch ms. */
    val from: Long,
    val to: Long,
    val elapsedDays: Int,
    /** There are listens before this period. */
    val hasEarlier: Boolean,
)

data class ListeningTotals(
    val plays: Int,
    val skips: Int,
    val listeningMs: Long,
    val tracks: Int,
    val artists: Int,
    val albums: Int,
    val genres: Int,
    val activeDays: Int,
    val dailyAverageMs: Long,
    /** 0–1. */
    val skipRate: Double,
)

/** Plays, listening time, share of the period's plays (0–1) and rank in the period before. */
data class Ranking(
    val plays: Int,
    val listeningMs: Long,
    val share: Double,
    val previousRank: Int?,
)

data class RankedGenre(val name: String, val ranking: Ranking)

data class RankedArtist(
    val id: String?,
    val name: String,
    val coverArtId: String?,
    /** First listened to in this period. */
    val isNew: Boolean,
    val ranking: Ranking,
)

data class RankedAlbum(
    val id: String?,
    val name: String,
    val artist: String,
    val artistId: String?,
    val coverArtId: String?,
    val year: Int?,
    val ranking: Ranking,
)

data class StatsTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String,
    val albumId: String?,
    val coverArtId: String?,
    val durationMs: Long,
)

data class RankedTrack(val track: StatsTrack, val ranking: Ranking)

data class RepeatTrack(
    val track: StatsTrack,
    val plays: Int,
    /** Local midnight of the day it was played most. */
    val peakDay: Long,
    val peakDayPlays: Int,
    val days: Int,
)

data class SkippedTrack(val track: StatsTrack, val skips: Int, val plays: Int)

data class Discoveries(
    val artists: List<RankedArtist>,
    val artistCount: Int,
    /** 0–1. */
    val newTrackShare: Double,
    val newTracks: Int,
)

data class ListeningRhythm(
    /** Plays per hour, 0–23. */
    val byHour: List<Int>,
    /** Plays per weekday, Monday first. */
    val byWeekday: List<Int>,
    /** [weekday][hour], Monday first. */
    val heatmap: List<List<Int>>,
    val peakHour: Int?,
    val peakWeekday: Int?,
)

enum class TimelineUnit { Day, Month }

data class TimelinePoint(
    val start: Long,
    val plays: Int,
    val listeningMs: Long,
    val topGenre: String?,
    val topArtist: String?,
)

data class ListeningTimeline(val unit: TimelineUnit, val points: List<TimelinePoint>)

data class ListeningStreaks(val longestDays: Int, val longestStart: Long?, val currentDays: Int)

data class ListeningSessions(
    val count: Int,
    val averageMs: Long,
    val longestMs: Long,
    val longestStart: Long?,
    val longestPlays: Int,
)

data class ListeningVariety(
    /** 0–100. */
    val score: Int,
    val topArtistShare: Double,
    val singlePlayShare: Double,
)

/** A notable fact about the period, worded by the server. */
data class ReportHighlight(val kind: String, val title: String, val detail: String)

data class ListeningReport(
    val period: ReportPeriod,
    val totals: ListeningTotals,
    val previous: ListeningTotals?,
    val persona: ReportHighlight?,
    val highlights: List<ReportHighlight>,
    val topGenres: List<RankedGenre>,
    val topArtists: List<RankedArtist>,
    val topAlbums: List<RankedAlbum>,
    val topTracks: List<RankedTrack>,
    val onRepeat: List<RepeatTrack>,
    val mostSkipped: List<SkippedTrack>,
    val discoveries: Discoveries,
    val rhythm: ListeningRhythm,
    val timeline: ListeningTimeline,
    val streaks: ListeningStreaks,
    val sessions: ListeningSessions,
    val variety: ListeningVariety,
    /** When REV started keeping the log; null before the first listen. */
    val trackingSince: Long?,
)

/** What a smart playlist is made from; see [SmartPlaylist]. */
enum class SmartPlaylistKind {
    /** Most played in the last 30 days. */
    OnRepeat,

    /** Played a lot, but not in the last 90 days. */
    Forgotten,

    /** Most played songs of [SmartPlaylist.year]. */
    Year,
}

/** A track list made by the server from the listening log; it changes as you listen. */
data class SmartPlaylist(
    val id: String,
    val kind: SmartPlaylistKind,
    val year: Int?,
    val trackCount: Int,
    val coverArtIds: List<String>,
    /** The Navidrome playlist it was last saved to, if any. */
    val savedPlaylistId: String?,
)

/** A smart playlist saved to Navidrome; [created] is false when an earlier save was updated. */
data class SavedSmartPlaylist(val playlistId: String, val created: Boolean)
