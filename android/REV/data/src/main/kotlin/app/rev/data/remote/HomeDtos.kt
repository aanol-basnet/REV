package app.rev.data.remote

import app.rev.domain.model.HomeFeed
import app.rev.domain.model.HomeGenre
import app.rev.domain.model.HomeSection
import app.rev.domain.model.HomeSectionKind
import app.rev.domain.model.Mix
import app.rev.domain.model.MixKind
import kotlinx.serialization.Serializable

@Serializable
internal data class HomeFeedDto(
    val generatedAt: Long,
    val quickPicks: List<AlbumDto>,
    val mixes: List<MixDto>,
    val sections: List<HomeSectionDto>,
    val genres: List<HomeGenreDto>,
) {
    /** Unknown section or mix kinds (from a newer server) are skipped. */
    fun toDomain() = HomeFeed(
        generatedAt = generatedAt,
        quickPicks = quickPicks.map { it.toDomain() },
        mixes = mixes.mapNotNull { it.toDomain() },
        sections = sections.mapNotNull { it.toDomain() },
        genres = genres.map { HomeGenre(it.name, it.albumCount) },
    )
}

@Serializable
internal data class MixDto(
    val id: String,
    val kind: String,
    val name: String? = null,
    val featuring: List<String> = emptyList(),
    val coverArtIds: List<String> = emptyList(),
) {
    fun toDomain(): Mix? {
        val kind = MixKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: return null
        return Mix(id, kind, name, featuring, coverArtIds)
    }
}

@Serializable
internal data class HomeSectionDto(
    val id: String,
    val kind: String,
    val basedOn: String? = null,
    val basedOnId: String? = null,
    val albums: List<AlbumDto>,
) {
    fun toDomain(): HomeSection? {
        val kind = HomeSectionKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: return null
        return HomeSection(id, kind, basedOn, basedOnId, albums.map { it.toDomain() })
    }
}

@Serializable
internal data class HomeGenreDto(val name: String, val albumCount: Int)

@Serializable
internal data class MixTracksDto(val id: String, val tracks: List<TrackDto>)

@Serializable
internal data class NextAlbumRequestDto(
    val albumId: String,
    val artistId: String?,
    val genre: String?,
    val exclude: List<String>,
)

@Serializable
internal data class NextAlbumDto(val tracks: List<TrackDto>)

@Serializable
internal data class RadioRequestDto(val artistIds: List<String>, val genres: List<String>, val exclude: List<String>)

@Serializable
internal data class RadioTracksDto(val tracks: List<TrackDto>)
