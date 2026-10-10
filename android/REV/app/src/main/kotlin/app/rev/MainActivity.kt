package app.rev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.ui.theme.RevTheme
import app.rev.core.ui.theme.ThemeMode
import app.rev.domain.repository.AppTheme
import app.rev.ui.AppViewModel
import app.rev.ui.RevApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val app: AppViewModel = hiltViewModel()
            val theme by app.theme.collectAsStateWithLifecycle()
            RevTheme(mode = if (theme == AppTheme.Amoled) ThemeMode.Amoled else ThemeMode.Dark) {
                RevApp(app)
            }
        }
    }
}
