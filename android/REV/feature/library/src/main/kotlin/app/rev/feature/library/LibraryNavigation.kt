package app.rev.feature.library

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute

@Serializable
data class ArtistRoute(val id: String)

@Serializable
data class AlbumRoute(val id: String)

/** Songs by an artist that you've liked or downloaded. */
@Serializable
data class ArtistSongsRoute(val id: String, val name: String)

@Serializable
data class GenreRoute(val name: String)

@Serializable
data class PlaylistRoute(val id: String)

@Serializable
data class PlaylistEditRoute(val id: String)

/** A Home or downloads mix, opened like a playlist. */
@Serializable
data class MixRoute(val id: String)

/** Callbacks the library screens use to move around; the app decides how. */
class LibraryNavigator(
    val toArtist: (id: String) -> Unit,
    val toAlbum: (id: String) -> Unit,
    val toGenre: (name: String) -> Unit,
    val toPlaylist: (id: String) -> Unit,
    val toArtistSongs: (id: String, name: String) -> Unit,
    val back: () -> Unit,
    val toPlaylistEdit: (id: String) -> Unit = {},
    /** Leaves a playlist that was just deleted (its page and the editor). */
    val playlistDeleted: (id: String) -> Unit = {},
)

fun NavGraphBuilder.libraryScreens(navigator: LibraryNavigator) {
    composable<LibraryRoute> { LibraryScreen(navigator) }
    composable<ArtistRoute> { ArtistScreen(navigator) }
    composable<ArtistSongsRoute> { ArtistSongsScreen(navigator) }
    composable<AlbumRoute> { AlbumScreen(navigator) }
    composable<GenreRoute> { GenreAlbumsScreen(navigator) }
    composable<PlaylistRoute> { PlaylistScreen(navigator) }
    composable<MixRoute> { MixScreen(navigator) }
    composable<PlaylistEditRoute> { entry ->
        val id = entry.toRoute<PlaylistEditRoute>().id
        PlaylistEditScreen(onClose = navigator.back, onDeleted = { navigator.playlistDeleted(id) })
    }
}
