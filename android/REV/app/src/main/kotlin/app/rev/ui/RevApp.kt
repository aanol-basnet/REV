package app.rev.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.domain.model.AuthState
import app.rev.feature.auth.LoginRoute
import app.rev.feature.auth.ServerSetupRoute

/**
 * App root. The auth state alone decides what is shown, so signing out or a revoked session
 * anywhere in the app returns to sign-in automatically.
 */
@Composable
fun RevApp(viewModel: AppViewModel = hiltViewModel()) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = authState.screen, label = "auth") { screen ->
            when (screen) {
                Screen.Loading -> Box(Modifier.fillMaxSize())
                Screen.ServerSetup -> ServerSetupRoute()
                Screen.Login -> LoginRoute()
                Screen.Main -> MainScreen(connection)
            }
        }
    }
}

private enum class Screen { Loading, ServerSetup, Login, Main }

private val AuthState.screen: Screen
    get() = when (this) {
        AuthState.Loading -> Screen.Loading
        AuthState.NeedsServer -> Screen.ServerSetup
        is AuthState.SignedOut -> Screen.Login
        is AuthState.SignedIn -> Screen.Main
    }
