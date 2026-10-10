package app.rev.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * "Glass" for floating surfaces (mini player, bars): a translucent container, lightly tinted by the
 * current artwork, with a hairline highlight. Deliberately no blur or gloss.
 */
object Glass {
    @Composable
    fun container(tint: Color = Color.Transparent): Color {
        val base = MaterialTheme.colorScheme.surfaceContainer
        val tinted = if (tint.alpha == 0f) base else lerp(base, tint, 0.35f * tint.alpha)
        return tinted.copy(alpha = 0.92f)
    }

    val hairline = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.07f))
}
