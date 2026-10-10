package app.rev.data.remote

import kotlinx.serialization.Serializable

// Wire types for /api/lyrics, mirroring shared/schemas/src/lyrics.ts.

@Serializable
internal data class LyricLineDto(val startMs: Long, val text: String)

@Serializable
internal data class LyricsResponseDto(
    val trackId: String,
    val status: String,
    val source: String? = null,
    val synced: List<LyricLineDto>? = null,
    val plain: String? = null,
    val fetchedAt: Long,
)
