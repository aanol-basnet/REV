package app.rev

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import androidx.work.Configuration
import app.rev.data.di.ApplicationScope
import app.rev.data.events.ServerEventSync
import app.rev.data.offline.SmartDownloadScheduler
import app.rev.data.stats.ListenQueue
import app.rev.domain.repository.ConnectionRepository
import kotlinx.coroutines.launch
import app.rev.data.remote.RevHttpClient
import app.rev.data.connection.RevConnectionManager
import app.rev.ui.StableArtworkCacheKeys
import app.rev.ui.CatalogCoverMapper
import app.rev.wear.WearBridge
import app.rev.ui.CoverArtMapper
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

@HiltAndroidApp
class RevApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject lateinit var http: RevHttpClient

    @Inject lateinit var connection: RevConnectionManager

    @Inject lateinit var eventSync: ServerEventSync

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var smartDownloads: SmartDownloadScheduler

    @Inject lateinit var wearBridge: WearBridge

    @Inject lateinit var listens: ListenQueue

    @Inject lateinit var connectionState: ConnectionRepository

    /** Offline downloads run as Hilt-injected WorkManager workers. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Live server events keep downloads and the library current while the app is open.
        eventSync.start(appScope)
        // Google Cast; unavailable without Google Play services, which is fine.
        runCatching { Cast.getSingletonInstance(this).initialize() }
        appScope.launch { smartDownloads.schedule() }
        wearBridge.start(appScope)
        // Plays and skips heard while the server was out of reach go up as soon as it's back.
        listens.start(appScope, connectionState.connectionState)
    }

    /**
     * Artwork is fetched through the authenticated REV client and kept in Coil's disk cache, so
     * covers seen once stay visible offline.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                // Whichever address of the server is active (home network or remote).
                add(CoverArtMapper(connection::activeUrl))
                add(CatalogCoverMapper(connection::activeUrl))
                add(StableArtworkCacheKeys(connection::remoteUrl, connection::trustedUrls))
                add(KtorNetworkFetcherFactory(httpClient = { http.client }))
            }
            .crossfade(true)
            .build()
}
