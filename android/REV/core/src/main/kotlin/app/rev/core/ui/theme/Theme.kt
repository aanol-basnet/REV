package app.rev.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RevDarkColors = darkColorScheme(
    primary = DefaultAccent,
    onPrimary = Black,
    secondary = Mist,
    onSecondary = Black,
    background = Coal,
    onBackground = White,
    surface = Coal,
    onSurface = White,
    surfaceVariant = Graphite,
    onSurfaceVariant = Ash,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Charcoal,
    surfaceContainer = Charcoal,
    surfaceContainerHigh = Graphite,
    surfaceContainerHighest = Smoke,
    outline = Smoke,
    outlineVariant = Graphite,
    error = ErrorRed,
    onError = White,
)

/** True black for OLED screens: pixels off, containers only just lifted so structure stays legible. */
private val RevAmoledColors = RevDarkColors.copy(
    background = Black,
    surface = Black,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Color(0xFF0A0A0B),
    surfaceContainer = Color(0xFF101012),
    surfaceContainerHigh = Color(0xFF18181B),
    surfaceContainerHighest = Color(0xFF222226),
    outlineVariant = Color(0xFF18181B),
)

enum class ThemeMode { Dark, Amoled }

/** REV is dark only: smoky Dark by default, or AMOLED black. Artwork supplies colour per screen. */
@Composable
fun RevTheme(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (mode == ThemeMode.Amoled) RevAmoledColors else RevDarkColors,
        typography = RevTypography,
        content = content,
    )
}
