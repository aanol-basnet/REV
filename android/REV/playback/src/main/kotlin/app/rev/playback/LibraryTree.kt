package app.rev.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.rev.domain.model.Album
import app.rev.domain.model.AlbumListType
import app.rev.domain.model.OfflineKind
import app.rev.domain.model.Playlist
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.repository.SearchRepository
import kotlinx.coroutines.flow.first

/**
 * The browse tree offered to Android Auto (and any other media browser):
 *
 *   root
 *   ├── Recently played   → albums → tracks
 *   ├── Recently added    → albums → tracks
 *   ├── Playlists         → playlists → tracks
 *   ├── Downloaded        → albums/playlists on this device → tracks
 *   └── Favorites         → tracks
 *
 * Lists come from the on-device cache (refreshed when possible), so browsing works offline.
 * Track ids carry their context ("album:<id>/<trackId>") so playing one queues its whole album or
 * playlist, starting at that track.
 */
internal class LibraryTree(
    private val library: LibraryRepository,
    private val offline: OfflineRepository,
    private val search: SearchRepository,
    private val labels: Labels,
) {
    data class Labels(val recent: String, val newest: String, val playlists: String, val downloaded: String, val favorites: String)

    val root: MediaItem = folder(ROOT, "REV")

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(RECENT, labels.recent),
            folder(NEWEST, labels.newest),
            folder(PLAYLISTS, labels.playlists),
            folder(DOWNLOADED, labels.downloaded),
            folder(FAVORITES, labels.favorites),
        )
        parentId == RECENT -> albumList(AlbumListType.Recent)
        parentId == NEWEST -> albumList(AlbumListType.Newest)
        parentId == PLAYLISTS -> {
            library.refreshPlaylists()
            library.observePlaylists().first().map(::playlistItem)
        }
        parentId == DOWNLOADED -> offline.observeCollections().first().mapNotNull { c ->
            when (c.kind) {
                OfflineKind.Album -> browsable("$ALBUM${c.itemId}", c.title, c.subtitle, c.coverArtId, MediaMetadata.MEDIA_TYPE_ALBUM)
                OfflineKind.Playlist -> browsable("$PLAYLIST${c.itemId}", c.title, c.subtitle, c.coverArtId, MediaMetadata.MEDIA_TYPE_PLAYLIST)
                OfflineKind.Track, OfflineKind.Favorites -> null
            }
        }
        parentId == FAVORITES -> {
            library.refreshFavorites()
            val tracks = library.observeFavorites().first().tracks
            tracks.map { trackItem(it, "$FAVORITES/${it.id}") }
        }
        parentId.startsWith(ALBUM) -> albumTracks(parentId.removePrefix(ALBUM)).map { trackItem(it, "$parentId/${it.id}") }
        parentId.startsWith(PLAYLIST) ->
            playlistTracks(parentId.removePrefix(PLAYLIST)).mapIndexed { i, t -> trackItem(t, "$parentId/$i") }
        else -> emptyList()
    }

    suspend fun item(id: String): MediaItem? = resolve(id).let { (tracks, start) -> tracks.getOrNull(start)?.let { trackItem(it, id) } }

    suspend fun search(query: String): List<MediaItem> {
        val results = (search.search(query) as? Result.Ok)?.value ?: return emptyList()
        return results.tracks.map { trackItem(it, "$SEARCH${it.id}") }
    }

    /**
     * What to queue for a requested id: the album/playlist/favorites the track belongs to, and
     * where to start. Albums and playlists themselves play from the top.
     */
    suspend fun resolve(id: String): Pair<List<Track>, Int> = when {
        id.startsWith(ALBUM) -> {
            val (albumId, trackId) = id.removePrefix(ALBUM).split('/', limit = 2).let { it[0] to it.getOrNull(1) }
            val tracks = albumTracks(albumId)
            tracks to (tracks.indexOfFirst { it.id == trackId }.takeIf { it >= 0 } ?: 0)
        }
        id.startsWith(PLAYLIST) -> {
            val (playlistId, index) = id.removePrefix(PLAYLIST).split('/', limit = 2).let { it[0] to it.getOrNull(1)?.toIntOrNull() }
            playlistTracks(playlistId) to (index ?: 0)
        }
        id.startsWith("$FAVORITES/") -> {
            val tracks = library.observeFavorites().first().tracks
            tracks to tracks.indexOfFirst { it.id == id.substringAfter('/') }.coerceAtLeast(0)
        }
        id.startsWith(SEARCH) -> when (val track = library.track(id.removePrefix(SEARCH))) {
            is Result.Ok -> listOf(track.value) to 0
            is Result.Err -> emptyList<Track>() to 0
        }
        else -> emptyList<Track>() to 0
    }

    private suspend fun albumList(type: AlbumListType): List<MediaItem> {
        library.refreshAlbumList(type)
        return library.observeAlbumList(type).first().map(::albumItem)
    }

    private suspend fun albumTracks(albumId: String): List<Track> {
        val cached = library.observeAlbum(albumId).first()?.tracks.orEmpty()
        if (cached.isNotEmpty()) return cached
        library.refreshAlbum(albumId)
        return library.observeAlbum(albumId).first()?.tracks.orEmpty()
    }

    private suspend fun playlistTracks(playlistId: String): List<Track> {
        library.refreshPlaylist(playlistId)
        return library.observePlaylist(playlistId).first()?.tracks.orEmpty()
    }

    private fun albumItem(album: Album) =
        browsable("$ALBUM${album.id}", album.name, album.artist, album.coverArtId, MediaMetadata.MEDIA_TYPE_ALBUM)

    private fun playlistItem(playlist: Playlist) =
        browsable("$PLAYLIST${playlist.id}", playlist.name, playlist.owner, playlist.coverArtId, MediaMetadata.MEDIA_TYPE_PLAYLIST)

    /** Albums/playlists are both browsable and playable (play from the top). */
    private fun browsable(id: String, title: String, subtitle: String?, coverArtId: String?, type: Int) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(coverArtId?.let(ArtworkProvider::uriFor))
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(type)
                .build(),
        )
        .build()

    private fun trackItem(track: Track, id: String): MediaItem {
        val item = track.toMediaItem(null)
        return item.buildUpon()
            .setMediaId(id)
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setSubtitle(track.artist)
                    .setArtworkUri(track.coverArtId?.let(ArtworkProvider::uriFor))
                    .build(),
            )
            .build()
    }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val NEWEST = "newest"
        const val PLAYLISTS = "playlists"
        const val DOWNLOADED = "downloaded"
        const val FAVORITES = "favorites"
        private const val ALBUM = "album:"
        private const val PLAYLIST = "playlist:"
        private const val SEARCH = "search:"

        /** Whether an id names something from this tree (as opposed to a plain queue item). */
        fun isTreeId(id: String) =
            id.startsWith(ALBUM) || id.startsWith(PLAYLIST) || id.startsWith("$FAVORITES/") || id.startsWith(SEARCH)
    }
}
