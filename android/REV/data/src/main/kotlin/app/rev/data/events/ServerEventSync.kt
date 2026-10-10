package app.rev.data.events

import app.rev.data.downloads.DownloadsRepositoryImpl
import app.rev.domain.model.AlbumListType
import app.rev.domain.model.ServerEvent
import app.rev.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Applies live server events to the on-device caches. */
class ServerEventSync(
    private val events: ServerEventsClient,
    private val downloads: DownloadsRepositoryImpl,
    private val library: LibraryRepository,
) {
    fun start(scope: CoroutineScope) {
        events.start(scope)
        scope.launch {
            events.events.collect { event ->
                when (event) {
                    // Anything may have changed while disconnected.
                    ServerEvent.Connected -> downloads.refresh()
                    is ServerEvent.DownloadChanged -> downloads.store(event.job)
                    is ServerEvent.LibraryUpdated -> library.refreshAlbumList(AlbumListType.Newest)
                    is ServerEvent.ServiceChanged -> Unit // shown on the system page when opened
                }
            }
        }
    }
}
