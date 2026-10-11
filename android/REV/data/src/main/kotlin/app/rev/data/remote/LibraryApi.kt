package app.rev.data.remote

import app.rev.domain.playback.Autoplay
import app.rev.domain.model.Album
import app.rev.domain.model.AlbumDetail
import app.rev.domain.model.Artist
import app.rev.domain.model.ArtistDetail
import app.rev.domain.model.CatalogAlbumStatus
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.Favorites
import app.rev.domain.model.FavoriteKind
import app.rev.domain.model.Genre
import app.rev.domain.model.Page
import app.rev.domain.model.Playlist
import app.rev.domain.model.PlaylistDetail
import app.rev.domain.model.Result
import app.rev.domain.model.SearchResults
import app.rev.domain.model.SystemStatus
import app.rev.domain.model.ServerUrl
import app.rev.domain.model.Track
import app.rev.domain.repository.LibraryError
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/** REV library, search, catalog and download endpoints. */
class LibraryApi(
    private val http: RevHttpClient,
    private val currentServer: () -> ServerUrl?,
) {
    suspend fun artists(): Result<List<Artist>, LibraryError> =
        get<ArtistListDto, _>("library/artists") { it.artists.map(ArtistDto::toDomain) }

    suspend fun artist(id: String): Result<ArtistDetail, LibraryError> =
        get<ArtistDetailDto, _>("library/artists/${id.encodeURLPathPart()}") { it.toDomain() }

    /** [type] is the server's list type: alphabetical, newest, recent, byGenre, ... */
    suspend fun albums(type: String, offset: Int, size: Int, genre: String? = null): Result<Page<Album>, LibraryError> =
        get<AlbumPageDto, _>("library/albums", {
            parameter("type", type)
            parameter("offset", offset)
            parameter("size", size)
            genre?.let { parameter("genre", it) }
        }) { Page(it.albums.map(AlbumDto::toDomain), it.offset, it.hasMore) }

    suspend fun album(id: String): Result<AlbumDetail, LibraryError> =
        get<AlbumDetailDto, _>("library/albums/${id.encodeURLPathPart()}") { it.toDomain() }

    suspend fun track(id: String): Result<Track, LibraryError> =
        get<TrackDto, _>("library/tracks/${id.encodeURLPathPart()}") { it.toDomain() }

    suspend fun tracks(offset: Int, size: Int): Result<Page<Track>, LibraryError> =
        get<TrackPageDto, _>("library/tracks", {
            parameter("offset", offset)
            parameter("size", size)
        }) { Page(it.tracks.map(TrackDto::toDomain), it.offset, it.hasMore) }

    suspend fun playlists(): Result<List<Playlist>, LibraryError> =
        get<PlaylistListDto, _>("library/playlists") { it.playlists.map(PlaylistDto::toDomain) }

    suspend fun playlist(id: String): Result<PlaylistDetail, LibraryError> =
        get<PlaylistDetailDto, _>("library/playlists/${id.encodeURLPathPart()}") { it.toDomain() }

    suspend fun genres(): Result<List<Genre>, LibraryError> =
        get<GenreListDto, _>("library/genres") { it.genres.map(GenreDto::toDomain) }

    suspend fun favorites(): Result<Favorites, LibraryError> =
        get<FavoritesDto, _>("library/favorites") { it.toDomain() }

    suspend fun setFavorite(kind: FavoriteKind, id: String, favorite: Boolean): Result<Unit, LibraryError> {
        val segment = when (kind) {
            FavoriteKind.Artist -> "artists"
            FavoriteKind.Album -> "albums"
            FavoriteKind.Track -> "tracks"
        }
        val path = "library/favorites/$segment/${id.encodeURLPathPart()}"
        return send(path, { url -> if (favorite) http.client.put(url) else http.client.delete(url) }) { }
    }

    suspend fun search(query: String): Result<SearchResults, LibraryError> =
        get<SearchResponseDto, _>("search", { parameter("q", query) }) { it.toDomain() }

    suspend fun catalogAlbumStatus(catalogAlbumId: String): Result<CatalogAlbumStatus, LibraryError> =
        get<CatalogAlbumStatusDto, _>("catalog/albums/${catalogAlbumId.encodeURLPathPart()}") { it.toDomain() }

    suspend fun downloads(activeOnly: Boolean): Result<List<DownloadJob>, LibraryError> =
        get<DownloadJobListDto, _>("downloads", { if (activeOnly) parameter("active", "true") }) { list ->
            list.jobs.map { it.toDomain() }
        }

    suspend fun requestDownload(catalogAlbumId: String): Result<DownloadJob, LibraryError> =
        send("downloads", { url ->
            http.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(CreateDownloadDto(catalogAlbumId))
            }
        }) { it.body<DownloadJobDto>().toDomain() }

    suspend fun cancelDownload(jobId: String): Result<DownloadJob, LibraryError> =
        send("downloads/${jobId.encodeURLPathPart()}", { url -> http.client.delete(url) }) {
            it.body<DownloadJobDto>().toDomain()
        }

    /** Deletes finished jobs on the server. */
    suspend fun clearFinishedDownloads(): Result<Unit, LibraryError> =
        send("downloads", { url -> http.client.delete(url) }) { }

    suspend fun artistTopTracks(artistId: String): Result<List<Track>, LibraryError> =
        get<TopTracksDto, _>("library/artists/${artistId.encodeURLPathPart()}/top-tracks") { dto ->
            dto.tracks.map { it.toDomain() }
        }

    /** The server's current home-network address(es), e.g. `http://192.168.1.10:4600`. */
    suspend fun serverLanUrls(): Result<List<String>, LibraryError> =
        get<ServerAddressesDto, _>("system/addresses") { it.lanUrls }

    /** [timeZone]: the phone's, so the feed includes the mix for this time of day. */
    internal suspend fun home(refresh: Boolean, timeZone: String): Result<HomeFeedDto, LibraryError> =
        get<HomeFeedDto, _>("home", {
            if (refresh) parameter("refresh", "true")
            parameter("tz", timeZone)
        }) { it }

    suspend fun mixTracks(mixId: String, timeZone: String): Result<List<Track>, LibraryError> =
        get<MixTracksDto, _>("mixes/${mixId.encodeURLPathPart()}", { parameter("tz", timeZone) }) { dto ->
            dto.tracks.map { it.toDomain() }
        }

    /** The album to play after [albumId] (autoplay); empty when the library has nothing else. */
    suspend fun nextAlbum(albumId: String, artistId: String?, genre: String?, exclude: List<String>): Result<List<Track>, LibraryError> =
        send("autoplay/next-album", { url ->
            http.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(NextAlbumRequestDto(albumId, artistId, genre, exclude))
            }
        }) { response -> response.body<NextAlbumDto>().tracks.map { it.toDomain() } }

    /**
     * Songs like the given artists and genres (autoplay after a playlist or mix), never by
     * [avoidArtists]; up to [size] for the smart queue to pick from.
     */
    suspend fun radio(
        artistIds: List<String>,
        genres: List<String>,
        exclude: List<String>,
        avoidArtists: List<String> = emptyList(),
        size: Int = Autoplay.RADIO_SIZE,
    ): Result<List<Track>, LibraryError> =
        send("autoplay/radio", { url ->
            http.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(RadioRequestDto(artistIds, genres, exclude, avoidArtists, size))
            }
        }) { response -> response.body<RadioTracksDto>().tracks.map { it.toDomain() } }

    /** More of one artist's songs, then similar artists once theirs run out (autoplay after their top songs). */
    suspend fun artistContinuation(
        artistId: String,
        genres: List<String>,
        exclude: List<String>,
        excludeAlbums: List<String> = emptyList(),
        size: Int = Autoplay.RADIO_SIZE,
    ): Result<List<Track>, LibraryError> =
        send("autoplay/artist", { url ->
            http.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(ArtistContinuationRequestDto(artistId, genres, exclude, excludeAlbums, size))
            }
        }) { response -> response.body<RadioTracksDto>().tracks.map { it.toDomain() } }

    suspend fun systemStatus(): Result<SystemStatus, LibraryError> =
        get<SystemStatusDto, _>("system/status") { it.toDomain() }

    internal suspend fun lyrics(trackId: String): Result<LyricsResponseDto, LibraryError> =
        get<LyricsResponseDto, _>("lyrics/${trackId.encodeURLPathPart()}") { it }

    internal suspend fun castToken(): Result<CastTokenDto, LibraryError> =
        send("cast/token", { url -> http.client.post(url) }) { it.body<CastTokenDto>() }

    suspend fun scrobble(trackId: String, submission: Boolean, playedAt: Long?): Result<Unit, LibraryError> =
        send("library/tracks/${trackId.encodeURLPathPart()}/scrobble", { url ->
            http.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(ScrobbleRequestDto(submission, playedAt))
            }
        }) { }

    internal suspend fun createPlaylist(name: String, trackIds: List<String>): Result<PlaylistDetailDto, LibraryError> =
        send("library/playlists", { url ->
            http.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(CreatePlaylistDto(name, trackIds))
            }
        }) { it.body<PlaylistDetailDto>() }

    /** Null [name] or [comment] leaves it as it is; an empty comment clears it. */
    internal suspend fun updatePlaylist(id: String, name: String?, comment: String?): Result<PlaylistDetailDto, LibraryError> =
        send("library/playlists/${id.encodeURLPathPart()}", { url ->
            http.client.patch(url) {
                contentType(ContentType.Application.Json)
                setBody(UpdatePlaylistDto(name, comment))
            }
        }) { it.body<PlaylistDetailDto>() }

    internal suspend fun duplicatePlaylist(id: String): Result<PlaylistDetailDto, LibraryError> =
        send("library/playlists/${id.encodeURLPathPart()}/duplicate", { url -> http.client.post(url) }) {
            it.body<PlaylistDetailDto>()
        }

    suspend fun deletePlaylist(id: String): Result<Unit, LibraryError> =
        send("library/playlists/${id.encodeURLPathPart()}", { url -> http.client.delete(url) }) { }

    internal suspend fun addPlaylistTracks(id: String, trackIds: List<String>): Result<AddPlaylistTracksResultDto, LibraryError> =
        send("library/playlists/${id.encodeURLPathPart()}/tracks", { url ->
            http.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(AddPlaylistTracksDto(trackIds))
            }
        }) { it.body<AddPlaylistTracksResultDto>() }

    internal suspend fun setPlaylistTracks(id: String, trackIds: List<String>): Result<PlaylistDetailDto, LibraryError> =
        send("library/playlists/${id.encodeURLPathPart()}/tracks", { url ->
            http.client.put(url) {
                contentType(ContentType.Application.Json)
                setBody(SetPlaylistTracksDto(trackIds))
            }
        }) { it.body<PlaylistDetailDto>() }

    /** Plays and skips for REV's listening log (the server passes plays on to Navidrome). */
    internal suspend fun listens(listens: List<ListenDto>): Result<ListenBatchResultDto, LibraryError> =
        send("stats/listens", { url ->
            http.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(ListenBatchDto(listens))
            }
        }) { it.body<ListenBatchResultDto>() }

    internal suspend fun listeningReport(period: String, offset: Int, timeZone: String): Result<ListeningReportDto, LibraryError> =
        get<ListeningReportDto, _>("stats/report", {
            parameter("period", period)
            parameter("offset", offset)
            parameter("tz", timeZone)
        }) { it }

    internal suspend fun feedback(events: List<FeedbackEventDto>): Result<Unit, LibraryError> =
        send("stats/feedback", { url ->
            http.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(FeedbackBatchDto(events))
            }
        }) { }

    /** Things you did with songs (`POST /api/stats/interactions`). */
    internal suspend fun interactions(events: List<InteractionDto>): Result<Unit, LibraryError> =
        send("stats/interactions", { url ->
            http.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(InteractionBatchDto(events))
            }
        }) { }

    /** Your personal skip model as last trained by the server; null before it has trained one. */
    internal suspend fun skipModel(): Result<SkipModelDto?, LibraryError> =
        get<SkipModelResponseDto, _>("stats/model") { it.model }

    /** What the smart queue knows about your listening (`GET /api/stats/taste`). */
    internal suspend fun taste(timeZone: String): Result<TasteProfileDto, LibraryError> =
        get<TasteProfileDto, _>("stats/taste", { parameter("tz", timeZone) }) { it }

    internal suspend fun smartPlaylists(timeZone: String): Result<SmartPlaylistsDto, LibraryError> =
        get<SmartPlaylistsDto, _>("stats/playlists", { parameter("tz", timeZone) }) { it }

    internal suspend fun smartPlaylistTracks(id: String, timeZone: String): Result<List<Track>, LibraryError> =
        get<SmartPlaylistDetailDto, _>("stats/playlists/${id.encodeURLPathPart()}", { parameter("tz", timeZone) }) { dto ->
            dto.tracks.map { it.toDomain() }
        }

    internal suspend fun saveSmartPlaylist(id: String, timeZone: String): Result<SaveSmartPlaylistResultDto, LibraryError> =
        send("stats/playlists/${id.encodeURLPathPart()}/save", { url ->
            http.client.post(url) { parameter("tz", timeZone) }
        }) { it.body<SaveSmartPlaylistResultDto>() }

    private suspend inline fun <reified D, T> get(
        path: String,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
        crossinline map: (D) -> T,
    ): Result<T, LibraryError> = send(path, { url -> http.client.get(url) { configure() } }) { map(it.body<D>()) }

    private suspend inline fun <T> send(
        path: String,
        request: (String) -> HttpResponse,
        read: (HttpResponse) -> T,
    ): Result<T, LibraryError> {
        val server = currentServer() ?: return Result.Err(LibraryError.SignedOut)
        return try {
            val response = request("${server.value}/api/$path")
            if (response.status.isSuccess()) Result.Ok(read(response)) else Result.Err(response.toLibraryError())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.Err(LibraryError.Unreachable)
        }
    }

    private suspend fun HttpResponse.toLibraryError(): LibraryError {
        val message = try {
            body<ApiErrorDto>().error.message
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "Server error (${status.value})"
        }
        return when (status) {
            HttpStatusCode.Unauthorized -> LibraryError.SignedOut
            HttpStatusCode.NotFound -> LibraryError.NotFound
            HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable -> LibraryError.MusicServerUnavailable(message)
            else -> LibraryError.Unexpected(message)
        }
    }
}
