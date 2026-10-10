package app.rev.wear

import android.content.Context
import app.rev.wearprotocol.PhoneAlbum
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface PhoneAlbums {
    data object Idle : PhoneAlbums
    data object Loading : PhoneAlbums
    data object Unreachable : PhoneAlbums
    data class Loaded(val albums: List<PhoneAlbum>) : PhoneAlbums
}

/** A copy in progress: which album, and how many of its tracks have arrived. */
data class Transfer(val albumTitle: String, val received: Int, val total: Int)

/** The watch app's few shared objects. Small enough that a DI framework isn't worth it here. */
object WatchGraph {
    @Volatile private var library: WatchLibrary? = null

    fun library(context: Context): WatchLibrary =
        library ?: synchronized(this) {
            library ?: WatchLibrary(File(context.applicationContext.filesDir, "music")).also { library = it }
        }

    val phoneAlbums = MutableStateFlow<PhoneAlbums>(PhoneAlbums.Idle)
    val transfer = MutableStateFlow<Transfer?>(null)
}
