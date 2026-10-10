package app.rev.core.ui.icons

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons not in material-icons-core. Paths from Material Symbols (Apache 2.0). */
object RevIcons {
    val Library: ImageVector by lazy {
        icon(
            "Library",
            "M20,2H8C6.9,2 6,2.9 6,4v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2z" +
                "M18,7h-3v5.5c0,1.38 -1.12,2.5 -2.5,2.5S10,13.88 10,12.5s1.12,-2.5 2.5,-2.5c0.57,0 1.08,0.19 1.5,0.51V5h4V7z" +
                "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6z",
        )
    }

    val Play: ImageVector by lazy { icon("Play", "M8,5v14l11,-7z") }
    val Pause: ImageVector by lazy { icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val SkipNext: ImageVector by lazy { icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val SkipPrevious: ImageVector by lazy { icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z") }
    val Shuffle: ImageVector by lazy {
        icon(
            "Shuffle",
            "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5z" +
                "M14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z",
        )
    }
    val Repeat: ImageVector by lazy {
        icon("Repeat", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z")
    }
    val RepeatOne: ImageVector by lazy {
        icon(
            "RepeatOne",
            "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4zM13,15V9h-1l-2,1v1h1.5v4H13z",
        )
    }
    val Queue: ImageVector by lazy {
        icon(
            "Queue",
            "M15,6H3v2h12V6zM15,10H3v2h12v-2zM3,16h8v-2H3v2zM17,6v8.18C16.69,14.07 16.35,14 16,14" +
                "c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3V8h3V6h-5z",
        )
    }
    val QueueAdd: ImageVector by lazy {
        icon("QueueAdd", "M14,10H3v2h11v-2zM14,6H3v2h11V6zM18,14v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zM3,16h7v-2H3v2z")
    }
    val PlaylistAdd: ImageVector by lazy {
        icon(
            "PlaylistAdd",
            "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4" +
                "c0,-1.1 -0.9,-2 -2,-2zM19,11h-4v4h-2v-4H9V9h4V5h2v4h4v2z",
        )
    }
    val Edit: ImageVector by lazy {
        icon(
            "Edit",
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41" +
                "l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z",
        )
    }
    /** "Not now". */
    val ThumbDown: ImageVector by lazy {
        icon(
            "ThumbDown",
            "M15,3H6c-0.83,0 -1.54,0.5 -1.84,1.22l-3.02,7.05c-0.09,0.23 -0.14,0.47 -0.14,0.73v2c0,1.1 0.9,2 2,2h6.31l-0.95,4.57 -0.03,0.32c0,0.41 0.17,0.79 0.44,1.06L9.83,23l6.59,-6.59c0.36,-0.36 0.58,-0.86 0.58,-1.41V5c0,-1.1 -0.9,-2 -2,-2zM19,3v12h4V3h-4z",
        )
    }

    /** Sparkles: something the smart queue did. */
    val AutoAwesome: ImageVector by lazy {
        icon(
            "AutoAwesome",
            "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4L6.5,9.5L1,12l5.5,2.5L9,20l2.5,-5.5L17,12l-5.5,-2.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z",
        )
    }
    val DragHandle: ImageVector by lazy { icon("DragHandle", "M20,9H4v2h16V9zM4,15h16v-2H4v2z") }
    val Collapse: ImageVector by lazy { icon("Collapse", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z") }

    val Insights: ImageVector by lazy { icon("Insights", "M5,9.2h3V19H5V9.2zM10.6,5h2.8v14h-2.8V5zM16.2,13H19v6h-2.8V13z") }

    val Download: ImageVector by lazy { icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }
    val Downloaded: ImageVector by lazy { icon("Downloaded", "M5,18h14v2H5v-2zM9.6,15.3L5,10.7l2,-1.9 2.6,2.6L17,4.6l2,1.9 -9.4,8.8z") }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
