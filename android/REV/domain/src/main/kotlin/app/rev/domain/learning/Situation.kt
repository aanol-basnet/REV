package app.rev.domain.learning

import app.rev.domain.model.Track
import app.rev.domain.playback.ListenContext
import app.rev.domain.taste.TasteProfile
import java.time.Instant
import java.time.ZoneOffset

/** The listening situation as of the song playing now, for predicting how other songs would go next. */
data class Situation(
    val output: String?,
    val network: String?,
    val charging: Boolean?,
    val shuffle: Boolean?,
    val queueShape: String?,
    val previousOutcome: String?,
    val sessionSkips: Int?,
    val sessionPosition: Int?,
) {
    companion object {
        fun of(context: ListenContext) = Situation(
            output = context.output.wire,
            network = context.device?.network,
            charging = context.device?.charging,
            shuffle = context.shuffle,
            queueShape = context.queueShape,
            previousOutcome = context.previousOutcome,
            sessionSkips = context.sessionSkips,
            sessionPosition = context.sessionPosition,
        )
    }
}

/** Kept up to date by playback as each song starts; read when ranking songs. */
class CurrentSituation {
    @Volatile var value: Situation? = null
}

/** Builds the skip model's [FeatureInput] for listens that happened and songs that might play. */
object ListenFeatures {
    /** A listen as it happened, with the context recorded for it. */
    fun of(track: Track, context: ListenContext, startedAt: Long, profile: TasteProfile): FeatureInput {
        val local = Instant.ofEpochMilli(startedAt).atOffset(ZoneOffset.ofTotalSeconds(context.utcOffsetMinutes * 60))
        val history = profile.tracks[track.id]
        return FeatureInput(
            trackId = track.id,
            artistId = track.artistId,
            albumId = track.albumId,
            genres = listOfNotNull(track.genre),
            year = track.year,
            localHour = local.hour,
            weekday = local.dayOfWeek.value - 1,
            source = context.source.wire,
            queueShape = context.queueShape,
            shuffle = context.shuffle,
            output = context.output.wire,
            network = context.device?.network,
            charging = context.device?.charging,
            favorite = context.favorite,
            previousOutcome = context.previousOutcome,
            sessionSkips = context.sessionSkips,
            sessionPosition = context.sessionPosition,
            trackPlays = history?.plays ?: 0,
            trackSkips = history?.skips ?: 0,
        )
    }

    /** A song that might play next, from [source] (smart, autoplay), in the current [situation]. */
    fun candidate(track: Track, situation: Situation?, source: String, hour: Int, weekday: Int, profile: TasteProfile): FeatureInput {
        val history = profile.tracks[track.id]
        return FeatureInput(
            trackId = track.id,
            artistId = track.artistId,
            albumId = track.albumId,
            genres = listOfNotNull(track.genre),
            year = track.year,
            localHour = hour,
            weekday = weekday,
            source = source,
            queueShape = situation?.queueShape,
            shuffle = situation?.shuffle,
            output = situation?.output,
            network = situation?.network,
            charging = situation?.charging,
            favorite = track.starred,
            previousOutcome = situation?.previousOutcome,
            sessionSkips = situation?.sessionSkips,
            sessionPosition = situation?.sessionPosition?.plus(1),
            trackPlays = history?.plays ?: 0,
            trackSkips = history?.skips ?: 0,
        )
    }
}
