package app.rev.feature.player

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object PlayerRoute

@Serializable
data object QueueRoute

class PlayerNavigator(
    val openQueue: () -> Unit,
    val openLyrics: () -> Unit,
    val toArtist: (String) -> Unit,
    val toAlbum: (String) -> Unit,
    val back: () -> Unit,
)

fun NavGraphBuilder.playerScreens(navigator: PlayerNavigator) {
    composable<PlayerRoute>(
        enterTransition = { slideInVertically { it } },
        popExitTransition = { slideOutVertically { it } },
    ) { PlayerScreen(navigator) }
    composable<QueueRoute> { QueueScreen(onBack = navigator.back) }
}
