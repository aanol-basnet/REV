package app.rev.playback.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as UnitColorProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.rev.playback.PlaybackService
import app.rev.playback.R
import java.io.File
import kotlinx.coroutines.guava.await

/** What the widget shows; pushed by the playback service. */
data class NowPlaying(
    val title: String?,
    val artist: String?,
    val coverArtId: String?,
    val isPlaying: Boolean,
    /** The cover, saved for the widget (see [WidgetArtwork]). */
    val artworkPath: String? = null,
    /** A dark colour from the cover for the card's background (ARGB). */
    val accent: Int? = null,
)

private val TITLE = stringPreferencesKey("title")
private val ARTIST = stringPreferencesKey("artist")
private val COVER = stringPreferencesKey("cover")
private val ART_PATH = stringPreferencesKey("art_path")
private val ACCENT = intPreferencesKey("accent")
private val PLAYING = booleanPreferencesKey("playing")

private val White = Color(0xFFF5F5F7)
private val Dim = Color(0xB3F5F5F7)
private val Ink = Color(0xFF111113)
private val DefaultCard = Color(0xFF1C1C1F)
private val Placeholder = Color(0xFF2A2A2E)

private fun fixed(color: Color) = ColorProvider(color, color)

// Layouts by size, like the big music apps: a compact pill, a 4×1 row with controls, a square
// tile with the cover filling it, and a large card.
private val COMPACT = DpSize(110.dp, 50.dp)
private val ROW = DpSize(220.dp, 50.dp)
private val SQUARE = DpSize(110.dp, 110.dp)
private val LARGE = DpSize(220.dp, 110.dp)

/**
 * Home-screen widget: the current cover on a card tinted from it, title and artist, and
 * previous / play-pause / next. Tapping the card opens REV. Play works even when REV isn't
 * running: it goes to the playback service as a media-button press, which resumes the last queue.
 */
class NowPlayingWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, ROW, SQUARE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content() }
    }

    @Composable
    private fun Content() {
        val context = LocalContext.current
        val prefs = currentState<Preferences>()
        val path = prefs[ART_PATH]
        // Read whenever the cover changes; the service keeps one small file per cover.
        val artwork = remember(path) { path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } }
        val state = WidgetUi(
            title = prefs[TITLE],
            artist = prefs[ARTIST],
            artwork = artwork,
            card = prefs[ACCENT]?.let { Color(it) } ?: DefaultCard,
            playing = prefs[PLAYING] ?: false,
            openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { actionStartActivity(it) },
            playPause = if (prefs[PLAYING] == true) actionRunCallback<PauseAction>() else playAction(context),
        )
        val size = LocalSize.current
        val tall = size.height >= SQUARE.height
        val wide = size.width >= ROW.width
        when {
            tall && wide -> Large(state, size)
            tall -> Square(state)
            wide -> Wide(state, size)
            else -> Compact(state, size)
        }
    }

    private class WidgetUi(
        val title: String?,
        val artist: String?,
        val artwork: Bitmap?,
        val card: Color,
        val playing: Boolean,
        val openApp: Action?,
        val playPause: Action,
    ) {
        val hasTrack get() = title != null
    }

    private fun GlanceModifier.card(ui: WidgetUi): GlanceModifier {
        val base = fillMaxSize().cornerRadius(24.dp).background(fixed(ui.card))
        return ui.openApp?.let { base.clickable(it) } ?: base
    }

    /** 4×2 and up: big cover, title and artist, and the full set of controls. */
    @Composable
    private fun Large(ui: WidgetUi, size: DpSize) {
        val cover = (size.height - 24.dp).coerceAtMost(size.width * 0.45f)
        Row(GlanceModifier.card(ui).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(ui.artwork, cover, radius = 16.dp)
            Spacer(GlanceModifier.width(14.dp))
            Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
                Titles(ui, titleSize = 17, lines = 2)
                Spacer(GlanceModifier.defaultWeight())
                if (ui.hasTrack) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(R.drawable.widget_previous, R.string.widget_previous, actionRunCallback<PreviousAction>())
                        Spacer(GlanceModifier.defaultWeight())
                        PlayButton(ui, 52.dp)
                        Spacer(GlanceModifier.defaultWeight())
                        IconButton(R.drawable.widget_next, R.string.widget_next, actionRunCallback<NextAction>())
                    }
                }
            }
        }
    }

    /** 2×2: the cover fills the tile, titles and play over a shade at the bottom. */
    @Composable
    private fun Square(ui: WidgetUi) {
        val base = GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(fixed(ui.card))
        Box(ui.openApp?.let { base.clickable(it) } ?: base) {
            if (ui.artwork != null) {
                Image(ImageProvider(ui.artwork), contentDescription = null, contentScale = ContentScale.Crop, modifier = GlanceModifier.fillMaxSize())
            }
            Image(ImageProvider(R.drawable.widget_scrim), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxSize())
            Column(GlanceModifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) { Titles(ui, titleSize = 14, lines = 1) }
                    if (ui.hasTrack) {
                        Spacer(GlanceModifier.width(8.dp))
                        PlayButton(ui, 40.dp)
                    }
                }
            }
        }
    }

    /** 4×1: a row with the cover, titles and controls. */
    @Composable
    private fun Wide(ui: WidgetUi, size: DpSize) {
        val cover = (size.height - 16.dp).coerceIn(36.dp, 64.dp)
        Row(GlanceModifier.card(ui).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(ui.artwork, cover, radius = 12.dp)
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) { Titles(ui, titleSize = 15, lines = 1) }
            if (ui.hasTrack) {
                if (size.width >= 280.dp) {
                    IconButton(R.drawable.widget_previous, R.string.widget_previous, actionRunCallback<PreviousAction>())
                }
                PlayButton(ui, 40.dp)
                IconButton(R.drawable.widget_next, R.string.widget_next, actionRunCallback<NextAction>())
            }
        }
    }

    /** Smallest: the cover and play/pause. */
    @Composable
    private fun Compact(ui: WidgetUi, size: DpSize) {
        val cover = (size.height - 16.dp).coerceIn(32.dp, 56.dp)
        Row(GlanceModifier.card(ui).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(ui.artwork, cover, radius = 12.dp)
            Spacer(GlanceModifier.defaultWeight())
            if (ui.hasTrack) PlayButton(ui, 40.dp)
        }
    }

    @Composable
    private fun Titles(ui: WidgetUi, titleSize: Int, lines: Int) {
        val context = LocalContext.current
        Text(
            ui.title ?: context.getString(R.string.widget_nothing_playing),
            maxLines = lines,
            style = TextStyle(color = fixed(White), fontSize = titleSize.sp, fontWeight = FontWeight.Bold),
        )
        Text(
            ui.artist ?: context.getString(R.string.widget_open),
            maxLines = 1,
            style = TextStyle(color = fixed(Dim), fontSize = 13.sp),
        )
    }

    @Composable
    private fun Cover(artwork: Bitmap?, size: Dp, radius: Dp) {
        Box(
            GlanceModifier.size(size).cornerRadius(radius).background(fixed(Placeholder)),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                Image(ImageProvider(artwork), contentDescription = null, contentScale = ContentScale.Crop, modifier = GlanceModifier.size(size).cornerRadius(radius))
            } else {
                Image(ImageProvider(R.drawable.widget_logo), contentDescription = null, modifier = GlanceModifier.size(size / 2))
            }
        }
    }

    /** Light circle with a dark glyph, the one strong accent on the card. */
    @Composable
    private fun PlayButton(ui: WidgetUi, size: Dp) {
        val context = LocalContext.current
        Box(
            GlanceModifier.size(size).background(ImageProvider(R.drawable.widget_circle)).clickable(ui.playPause),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                ImageProvider(if (ui.playing) R.drawable.widget_pause else R.drawable.widget_play),
                contentDescription = context.getString(R.string.widget_play_pause),
                colorFilter = ColorFilter.tint(UnitColorProvider(Ink)),
                modifier = GlanceModifier.size(size * 0.5f),
            )
        }
    }

    @Composable
    private fun IconButton(icon: Int, label: Int, action: Action) {
        val context = LocalContext.current
        Box(GlanceModifier.size(44.dp).cornerRadius(22.dp).clickable(action), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(icon),
                contentDescription = context.getString(label),
                colorFilter = ColorFilter.tint(fixed(White)),
                modifier = GlanceModifier.size(26.dp),
            )
        }
    }

    companion object {
        /** Pushes new state to every placed widget. Does nothing when none are placed. */
        suspend fun publish(context: Context, state: NowPlaying) {
            val ids = GlanceAppWidgetManager(context).getGlanceIds(NowPlayingWidget::class.java)
            if (ids.isEmpty()) return
            val widget = NowPlayingWidget()
            for (id in ids) {
                updateAppWidgetState(context, id) { prefs ->
                    prefs.put(TITLE, state.title)
                    prefs.put(ARTIST, state.artist)
                    prefs.put(COVER, state.coverArtId)
                    prefs.put(ART_PATH, state.artworkPath)
                    if (state.accent == null) prefs.remove(ACCENT) else prefs[ACCENT] = state.accent
                    prefs[PLAYING] = state.isPlaying
                }
                widget.update(context, id)
            }
        }

        /** Whether any widget is on the home screen (so the service can skip the artwork work). */
        suspend fun isPlaced(context: Context): Boolean =
            GlanceAppWidgetManager(context).getGlanceIds(NowPlayingWidget::class.java).isNotEmpty()

        private fun androidx.datastore.preferences.core.MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
            if (value == null) remove(key) else this[key] = value
        }
    }
}

/**
 * Play as a media-button press to the playback service, started in the foreground: the same path
 * the system uses to resume music, so it works when REV isn't running (the last queue is restored).
 */
private fun playAction(context: Context): Action {
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(context, PlaybackService::class.java))
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
    return actionStartService(intent, isForegroundService = true)
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/** Runs a command against the playback service through a short-lived MediaController. */
private suspend fun withController(context: Context, block: (MediaController) -> Unit) {
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val controller = MediaController.Builder(context, token).buildAsync().await()
    try {
        block(controller)
    } finally {
        controller.release()
    }
}

class PauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withController(context) { it.pause() }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withController(context) { it.seekToNext() }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withController(context) { it.seekToPrevious() }
}

/** Unused artwork files are deleted; see [WidgetArtwork]. */
internal fun widgetArtworkDir(context: Context) = File(context.filesDir, "widget")
