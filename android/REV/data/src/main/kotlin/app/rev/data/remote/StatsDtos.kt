package app.rev.data.remote

import app.rev.domain.learning.SkipModel
import app.rev.domain.playback.ListenContext
import app.rev.domain.taste.Daypart
import app.rev.domain.taste.TasteProfile
import app.rev.domain.taste.TrackHistory
import app.rev.domain.model.Discoveries
import app.rev.domain.model.ListeningReport
import app.rev.domain.model.ListeningRhythm
import app.rev.domain.model.ListeningSessions
import app.rev.domain.model.ListeningStreaks
import app.rev.domain.model.ListeningTimeline
import app.rev.domain.model.ListeningTotals
import app.rev.domain.model.ListeningVariety
import app.rev.domain.model.RankedAlbum
import app.rev.domain.model.RankedArtist
import app.rev.domain.model.RankedGenre
import app.rev.domain.model.RankedTrack
import app.rev.domain.model.Ranking
import app.rev.domain.model.RepeatTrack
import app.rev.domain.model.ReportHighlight
import app.rev.domain.model.ReportPeriod
import app.rev.domain.model.ReportPeriodKind
import app.rev.domain.model.SavedSmartPlaylist
import app.rev.domain.model.SkippedTrack
import app.rev.domain.model.SmartPlaylist
import app.rev.domain.model.SmartPlaylistKind
import app.rev.domain.model.StatsTrack
import app.rev.domain.model.TimelinePoint
import app.rev.domain.model.TimelineUnit
import kotlinx.serialization.Serializable

// Wire types for /api/stats, mirroring shared/schemas/src/stats.ts.

@Serializable
internal data class ListenDto(
    val trackId: String,
    val startedAt: Long,
    val kind: String,
    val listenedMs: Long? = null,
    val context: ListenContextDto? = null,
)

/**
 * A listen's context on the wire (`ListenContext` in the server's schemas), flat. No defaults, so
 * every value is sent (the JSON setup leaves out values equal to a default).
 */
@Serializable
internal data class ListenContextDto(
    val v: Int,
    val source: String,
    val smartChange: String?,
    val queueShape: String?,
    val shuffle: Boolean,
    val repeat: String,
    val queuePosition: Int,
    val queueLength: Int,
    val previousTrackId: String?,
    val previousOutcome: String?,
    val sessionPosition: Int,
    val sessionSkips: Int,
    val endReason: String?,
    val output: String,
    val utcOffsetMinutes: Int,
    val favorite: Boolean,
    val outputName: String?,
    val network: String?,
    val charging: Boolean?,
    val screenOn: Boolean?,
    val volume: Double?,
    val startPositionMs: Long,
    val endPositionMs: Long?,
    val seeksForward: Int,
    val seeksBack: Int,
    val seekedForwardMs: Long,
    val seekedBackMs: Long,
    val pauses: Int,
    val pausedMs: Long,
    val volumeEnd: Double?,
    val favoritedDuring: Boolean?,
    val predictedScore: Double?,
    val predictedSkip: Double?,
)

internal fun ListenContext.toDto() = ListenContextDto(
    v = CONTEXT_VERSION,
    source = source.wire,
    smartChange = smartChange,
    queueShape = queueShape,
    shuffle = shuffle,
    repeat = repeat.name.lowercase(),
    queuePosition = queuePosition,
    queueLength = queueLength,
    previousTrackId = previousTrackId,
    previousOutcome = previousOutcome,
    sessionPosition = sessionPosition,
    sessionSkips = sessionSkips,
    endReason = endReason?.wire,
    output = output.wire,
    utcOffsetMinutes = utcOffsetMinutes,
    favorite = favorite,
    outputName = device?.outputName,
    network = device?.network,
    charging = device?.charging,
    screenOn = device?.screenOn,
    volume = device?.volume?.let { kotlin.math.round(it * 1000) / 1000 },
    startPositionMs = behaviour.startPositionMs,
    endPositionMs = behaviour.endPositionMs,
    seeksForward = behaviour.seeksForward,
    seeksBack = behaviour.seeksBack,
    seekedForwardMs = behaviour.seekedForwardMs,
    seekedBackMs = behaviour.seekedBackMs,
    pauses = behaviour.pauses,
    pausedMs = behaviour.pausedMs,
    volumeEnd = behaviour.volumeEnd?.let { kotlin.math.round(it * 1000) / 1000 },
    favoritedDuring = behaviour.favoritedDuring,
    predictedScore = predictedScore?.let { kotlin.math.round(it * 1000) / 1000 },
    predictedSkip = predictedSkip?.let { kotlin.math.round(it * 1000) / 1000 },
)

@Serializable
internal data class FeedbackEventDto(val trackId: String, val listenStartedAt: Long, val kind: String, val at: Long, val positionMs: Long? = null)

@Serializable
internal data class FeedbackBatchDto(val events: List<FeedbackEventDto>)

@Serializable
internal data class SkipModelDto(
    val trainedAt: Long,
    val dataUntil: Long,
    val examples: Int,
    val bias: Double,
    val weights: Map<String, Double>,
) {
    fun toDomain() = SkipModel(trainedAt, dataUntil, examples, bias, weights)
}

@Serializable
internal data class SkipModelResponseDto(val model: SkipModelDto? = null)

/** Version of the context shape; bump when fields change meaning. */
internal const val CONTEXT_VERSION = 1

@Serializable
internal data class ListenBatchDto(val listens: List<ListenDto>)

@Serializable
internal data class ListenBatchResultDto(val recorded: Int, val duplicates: Int, val unknown: Int)

internal fun ReportPeriodKind.wire(): String = name.lowercase()

@Serializable
internal data class ReportPeriodDto(
    val kind: String,
    val offset: Int,
    val timeZone: String,
    val from: Long,
    val to: Long,
    val elapsedDays: Int,
    val hasEarlier: Boolean,
) {
    fun toDomain(): ReportPeriod? {
        val kind = ReportPeriodKind.entries.firstOrNull { it.wire() == kind } ?: return null
        return ReportPeriod(kind, offset, timeZone, from, to, elapsedDays, hasEarlier)
    }
}

@Serializable
internal data class ListeningTotalsDto(
    val plays: Int,
    val skips: Int,
    val listeningMs: Long,
    val tracks: Int,
    val artists: Int,
    val albums: Int,
    val genres: Int,
    val activeDays: Int,
    val dailyAverageMs: Long,
    val skipRate: Double,
) {
    fun toDomain() = ListeningTotals(plays, skips, listeningMs, tracks, artists, albums, genres, activeDays, dailyAverageMs, skipRate)
}

@Serializable
internal data class RankedGenreDto(
    val name: String,
    val plays: Int,
    val listeningMs: Long,
    val share: Double,
    val previousRank: Int? = null,
) {
    fun toDomain() = RankedGenre(name, Ranking(plays, listeningMs, share, previousRank))
}

@Serializable
internal data class RankedArtistDto(
    val id: String? = null,
    val name: String,
    val coverArtId: String? = null,
    val isNew: Boolean,
    val plays: Int,
    val listeningMs: Long,
    val share: Double,
    val previousRank: Int? = null,
) {
    fun toDomain() = RankedArtist(id, name, coverArtId, isNew, Ranking(plays, listeningMs, share, previousRank))
}

@Serializable
internal data class RankedAlbumDto(
    val id: String? = null,
    val name: String,
    val artist: String,
    val artistId: String? = null,
    val coverArtId: String? = null,
    val year: Int? = null,
    val plays: Int,
    val listeningMs: Long,
    val share: Double,
    val previousRank: Int? = null,
) {
    fun toDomain() = RankedAlbum(id, name, artist, artistId, coverArtId, year, Ranking(plays, listeningMs, share, previousRank))
}

@Serializable
internal data class StatsTrackDto(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String,
    val albumId: String? = null,
    val coverArtId: String? = null,
    val durationMs: Long,
) {
    fun toDomain() = StatsTrack(id, title, artist, artistId, album, albumId, coverArtId, durationMs)
}

@Serializable
internal data class RankedTrackDto(
    val track: StatsTrackDto,
    val plays: Int,
    val listeningMs: Long,
    val share: Double,
    val previousRank: Int? = null,
) {
    fun toDomain() = RankedTrack(track.toDomain(), Ranking(plays, listeningMs, share, previousRank))
}

@Serializable
internal data class RepeatTrackDto(
    val track: StatsTrackDto,
    val plays: Int,
    val peakDay: Long,
    val peakDayPlays: Int,
    val days: Int,
) {
    fun toDomain() = RepeatTrack(track.toDomain(), plays, peakDay, peakDayPlays, days)
}

@Serializable
internal data class SkippedTrackDto(val track: StatsTrackDto, val skips: Int, val plays: Int) {
    fun toDomain() = SkippedTrack(track.toDomain(), skips, plays)
}

@Serializable
internal data class DiscoveriesDto(
    val artists: List<RankedArtistDto>,
    val artistCount: Int,
    val newTrackShare: Double,
    val newTracks: Int,
) {
    fun toDomain() = Discoveries(artists.map { it.toDomain() }, artistCount, newTrackShare, newTracks)
}

@Serializable
internal data class ListeningRhythmDto(
    val byHour: List<Int>,
    val byWeekday: List<Int>,
    val heatmap: List<List<Int>>,
    val peakHour: Int? = null,
    val peakWeekday: Int? = null,
) {
    fun toDomain() = ListeningRhythm(byHour, byWeekday, heatmap, peakHour, peakWeekday)
}

@Serializable
internal data class TimelinePointDto(
    val start: Long,
    val plays: Int,
    val listeningMs: Long,
    val topGenre: String? = null,
    val topArtist: String? = null,
)

@Serializable
internal data class ListeningTimelineDto(val unit: String, val points: List<TimelinePointDto>) {
    fun toDomain() = ListeningTimeline(
        unit = if (unit == "month") TimelineUnit.Month else TimelineUnit.Day,
        points = points.map { TimelinePoint(it.start, it.plays, it.listeningMs, it.topGenre, it.topArtist) },
    )
}

@Serializable
internal data class ListeningStreaksDto(val longestDays: Int, val longestStart: Long? = null, val currentDays: Int) {
    fun toDomain() = ListeningStreaks(longestDays, longestStart, currentDays)
}

@Serializable
internal data class ListeningSessionsDto(
    val count: Int,
    val averageMs: Long,
    val longestMs: Long,
    val longestStart: Long? = null,
    val longestPlays: Int,
) {
    fun toDomain() = ListeningSessions(count, averageMs, longestMs, longestStart, longestPlays)
}

@Serializable
internal data class ListeningVarietyDto(val score: Int, val topArtistShare: Double, val singlePlayShare: Double) {
    fun toDomain() = ListeningVariety(score, topArtistShare, singlePlayShare)
}

@Serializable
internal data class ReportHighlightDto(val kind: String, val title: String, val detail: String) {
    fun toDomain() = ReportHighlight(kind, title, detail)
}

@Serializable
internal data class ListeningReportDto(
    val period: ReportPeriodDto,
    val totals: ListeningTotalsDto,
    val previous: ListeningTotalsDto? = null,
    val persona: ReportHighlightDto? = null,
    val highlights: List<ReportHighlightDto>,
    val topGenres: List<RankedGenreDto>,
    val topArtists: List<RankedArtistDto>,
    val topAlbums: List<RankedAlbumDto>,
    val topTracks: List<RankedTrackDto>,
    val onRepeat: List<RepeatTrackDto>,
    val mostSkipped: List<SkippedTrackDto>,
    val discoveries: DiscoveriesDto,
    val rhythm: ListeningRhythmDto,
    val timeline: ListeningTimelineDto,
    val streaks: ListeningStreaksDto,
    val sessions: ListeningSessionsDto,
    val variety: ListeningVarietyDto,
    val trackingSince: Long? = null,
) {
    /** Null for a period kind this app doesn't know (a newer server). */
    fun toDomain(): ListeningReport? = period.toDomain()?.let { period ->
        ListeningReport(
            period = period,
            totals = totals.toDomain(),
            previous = previous?.toDomain(),
            persona = persona?.toDomain(),
            highlights = highlights.map { it.toDomain() },
            topGenres = topGenres.map { it.toDomain() },
            topArtists = topArtists.map { it.toDomain() },
            topAlbums = topAlbums.map { it.toDomain() },
            topTracks = topTracks.map { it.toDomain() },
            onRepeat = onRepeat.map { it.toDomain() },
            mostSkipped = mostSkipped.map { it.toDomain() },
            discoveries = discoveries.toDomain(),
            rhythm = rhythm.toDomain(),
            timeline = timeline.toDomain(),
            streaks = streaks.toDomain(),
            sessions = sessions.toDomain(),
            variety = variety.toDomain(),
            trackingSince = trackingSince,
        )
    }
}

@Serializable
internal data class SmartPlaylistDto(
    val id: String,
    val kind: String,
    val year: Int? = null,
    val trackCount: Int,
    val coverArtIds: List<String> = emptyList(),
    val savedPlaylistId: String? = null,
) {
    /** Null for a kind this app doesn't know (a newer server). */
    fun toDomain(): SmartPlaylist? {
        val kind = when (kind) {
            "on-repeat" -> SmartPlaylistKind.OnRepeat
            "forgotten" -> SmartPlaylistKind.Forgotten
            "year" -> SmartPlaylistKind.Year
            else -> return null
        }
        return SmartPlaylist(id, kind, year, trackCount, coverArtIds, savedPlaylistId)
    }
}

@Serializable
internal data class SmartPlaylistsDto(val playlists: List<SmartPlaylistDto>)

@Serializable
internal data class SmartPlaylistDetailDto(val id: String, val tracks: List<TrackDto>)

@Serializable
internal data class SaveSmartPlaylistResultDto(val playlistId: String, val created: Boolean) {
    fun toDomain() = SavedSmartPlaylist(playlistId, created)
}

@Serializable
internal data class TasteProfileDto(
    val artists: List<TasteArtistDto> = emptyList(),
    val genres: List<TasteGenreDto> = emptyList(),
    val decades: List<TasteDecadeDto> = emptyList(),
    val tracks: List<TasteTrackDto> = emptyList(),
    val dayparts: List<TasteDaypartDto> = emptyList(),
) {
    fun toDomain() = TasteProfile(
        artists = artists.associate { it.id to it.score },
        genres = genres.associate { it.name.lowercase() to it.score },
        decades = decades.associate { it.decade to it.score },
        tracks = tracks.associate { it.id to TrackHistory(it.plays, it.skips) },
        dayparts = dayparts.map { part ->
            Daypart(part.fromHour, part.genres.associate { it.name.lowercase() to it.score }, part.artists.associate { it.id to it.score })
        },
    )
}

@Serializable
internal data class TasteArtistDto(val id: String, val score: Double)

@Serializable
internal data class TasteGenreDto(val name: String, val score: Double)

@Serializable
internal data class TasteDecadeDto(val decade: Int, val score: Double)

@Serializable
internal data class TasteTrackDto(val id: String, val plays: Int, val skips: Int)

@Serializable
internal data class TasteDaypartDto(
    val fromHour: Int,
    val genres: List<TasteGenreDto> = emptyList(),
    val artists: List<TasteArtistDto> = emptyList(),
)
