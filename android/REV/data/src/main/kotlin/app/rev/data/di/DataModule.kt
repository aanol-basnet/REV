package app.rev.data.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import app.rev.data.acquisition.AcquisitionRepositoryImpl
import app.rev.data.auth.AuthRepositoryImpl
import app.rev.data.auth.SessionTerminator
import app.rev.data.connection.AndroidNetworkMonitor
import app.rev.data.connection.ConnectionMonitor
import app.rev.data.home.HomeRepositoryImpl
import app.rev.data.local.RevDatabase
import app.rev.data.stats.ListenQueue
import app.rev.data.stats.StatsRepositoryImpl
import app.rev.domain.repository.StatsRepository
import app.rev.data.library.PlaylistRepositoryImpl
import app.rev.domain.repository.PlaylistRepository
import app.rev.domain.repository.HomeRepository
import java.io.File
import app.rev.data.connection.NetworkMonitor
import app.rev.data.connection.RevConnectionManager
import dagger.Lazy
import app.rev.data.library.LibraryRepositoryImpl
import app.rev.data.downloads.DownloadsRepositoryImpl
import app.rev.data.downloads.SystemRepositoryImpl
import app.rev.data.events.ServerEventSync
import app.rev.data.events.ServerEventsClient
import app.rev.data.local.downloads.DownloadJobDao
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.rev.domain.model.AuthState
import app.rev.domain.repository.DownloadsRepository
import app.rev.domain.repository.ServerEvents
import app.rev.domain.repository.SystemRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import app.rev.data.search.SearchRepositoryImpl
import app.rev.data.local.library.LibraryDao
import app.rev.data.remote.LibraryApi
import app.rev.data.remote.RevApi
import app.rev.data.remote.RevHttpClient
import app.rev.data.server.ServerConfigStore
import app.rev.data.server.ServerRepositoryImpl
import app.rev.data.session.KeystoreSessionStore
import app.rev.data.session.SessionStore
import app.rev.domain.repository.AcquisitionRepository
import app.rev.domain.repository.AuthRepository
import app.rev.domain.repository.ConnectionRepository
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.repository.LyricsRepository
import app.rev.data.lyrics.LyricsRepositoryImpl
import app.rev.data.local.lyrics.LyricsDao
import app.rev.domain.repository.SearchRepository
import app.rev.domain.repository.ServerRepository
import app.rev.domain.model.Result
import app.rev.domain.model.ServerUrl
import app.rev.data.stats.PersonalModelImpl
import app.rev.data.stats.TasteRepositoryImpl
import app.rev.domain.playback.FavoriteEvents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "rev_settings")

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideSessionStore(@ApplicationContext context: Context): SessionStore =
        KeystoreSessionStore(context, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    fun provideServerConfigStore(
        dataStore: DataStore<Preferences>,
        @ApplicationScope scope: CoroutineScope,
    ): ServerConfigStore = ServerConfigStore(dataStore, scope)

    @Provides
    @Singleton
    fun provideNetworkMonitor(@ApplicationContext context: Context): NetworkMonitor = AndroidNetworkMonitor(context)

    /**
     * The one place that knows which address of the server (home network or remote) is in use.
     * Everything below asks it for the base URL instead of reading the configuration.
     */
    @Provides
    @Singleton
    fun provideConnectionManager(
        serverConfig: ServerConfigStore,
        network: NetworkMonitor,
        api: RevApi,
        // Lazy: the library API takes its base URL from this manager.
        libraryApi: Lazy<LibraryApi>,
        @ApplicationScope scope: CoroutineScope,
    ): RevConnectionManager = RevConnectionManager(
        config = serverConfig.config,
        network = network,
        probe = { url -> api.health(url, fast = true) },
        scope = scope,
        isForeground = { ProcessLifecycleOwner.get().lifecycle.currentStateFlow.value.isAtLeast(Lifecycle.State.STARTED) },
        // Follows the server to a new home-network address (e.g. a new DHCP lease).
        discover = {
            (libraryApi.get().serverLanUrls() as? Result.Ok)?.value.orEmpty()
                .mapNotNull { (ServerUrl.parse(it) as? Result.Ok)?.value }
        },
        adopt = { serverConfig.adoptLocalUrl(it) },
    )

    @Provides
    @Singleton
    fun provideRevHttpClient(sessionStore: SessionStore, connection: Lazy<RevConnectionManager>): RevHttpClient =
        // Lazy: the manager probes through this client's anonymous half.
        RevHttpClient(
            OkHttp.create(),
            sessionStore,
            trustedServers = { connection.get().trustedUrls() },
            onUnreachable = { connection.get().reportUnreachable(it) },
        ) { connection.get().activeUrl() }

    @Provides
    @Singleton
    fun provideRevApi(http: RevHttpClient): RevApi = RevApi(http)

    @Provides
    @Singleton
    fun provideSessionTerminator(
        api: RevApi,
        serverConfig: ServerConfigStore,
        sessionStore: SessionStore,
        connection: RevConnectionManager,
    ): SessionTerminator = SessionTerminator(api, serverConfig, sessionStore) { connection.activeUrl() }

    @Provides
    @Singleton
    fun provideServerRepository(
        api: RevApi,
        store: ServerConfigStore,
        terminator: SessionTerminator,
        libraryDao: LibraryDao,
        downloadJobDao: DownloadJobDao,
        home: HomeRepositoryImpl,
        taste: TasteRepositoryImpl,
        personalModel: PersonalModelImpl,
    ): ServerRepository = ServerRepositoryImpl(api, store, terminator) {
        libraryDao.clearAll()
        downloadJobDao.clear()
        home.clear()
        taste.clear()
        personalModel.clear()
        // Downloaded files stay: they are the user's music, and play without any server.
    }

    @Provides
    @Singleton
    fun provideLibraryApi(http: RevHttpClient, connection: RevConnectionManager): LibraryApi =
        LibraryApi(http, connection::activeUrl)

    @Provides
    @Singleton
    fun provideDownloadsRepositoryImpl(api: LibraryApi, dao: DownloadJobDao): DownloadsRepositoryImpl =
        DownloadsRepositoryImpl(api, dao)

    @Provides
    fun provideDownloadsRepository(impl: DownloadsRepositoryImpl): DownloadsRepository = impl

    @Provides
    @Singleton
    fun provideSystemRepository(api: LibraryApi): SystemRepository = SystemRepositoryImpl(api)

    @Provides
    @Singleton
    fun provideAcquisitionRepository(api: LibraryApi, downloads: DownloadsRepositoryImpl): AcquisitionRepository =
        AcquisitionRepositoryImpl(api, downloads)

    @Provides
    @Singleton
    fun provideServerEventsClient(
        okHttp: OkHttpClient,
        connection: RevConnectionManager,
        auth: AuthRepository,
    ): ServerEventsClient {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .flowOn(Dispatchers.Main)
        val signedIn = auth.authState.map { it is AuthState.SignedIn }
        return ServerEventsClient(
            okHttp,
            connection::activeUrl,
            combine(signedIn, foreground) { a, b -> a && b },
            connection.endpoint.map { it?.url },
        )
    }

    @Provides
    fun provideServerEvents(client: ServerEventsClient): ServerEvents = client

    @Provides
    @Singleton
    fun provideServerEventSync(
        events: ServerEventsClient,
        downloads: DownloadsRepositoryImpl,
        library: LibraryRepository,
    ): ServerEventSync = ServerEventSync(events, downloads, library)

    @Provides
    @Singleton
    fun provideLyricsRepository(api: LibraryApi, dao: LyricsDao): LyricsRepository = LyricsRepositoryImpl(api, dao)

    @Provides
    @Singleton
    fun provideHomeRepositoryImpl(@ApplicationContext context: Context, api: LibraryApi): HomeRepositoryImpl =
        HomeRepositoryImpl(api, File(context.filesDir, "home_feed.json"))

    @Provides
    fun provideHomeRepository(impl: HomeRepositoryImpl): HomeRepository = impl

    @Provides
    @Singleton
    fun providePlaylistRepository(
        api: LibraryApi,
        db: RevDatabase,
        library: LibraryRepository,
        offline: OfflineRepository,
    ): PlaylistRepository = PlaylistRepositoryImpl(api, db, library, offline)

    @Provides
    @Singleton
    fun provideListenQueue(db: RevDatabase, api: LibraryApi): ListenQueue = ListenQueue(db, api)

    @Provides
    @Singleton
    fun provideStatsRepository(@ApplicationContext context: Context, api: LibraryApi): StatsRepository =
        StatsRepositoryImpl(api, File(context.filesDir, "stats"))

    @Provides
    @Singleton
    fun provideSearchRepository(api: LibraryApi, library: LibraryRepository, offline: OfflineRepository): SearchRepository =
        SearchRepositoryImpl(api, library, offline)

    @Provides
    @Singleton
    fun provideLibraryRepository(api: LibraryApi, dao: LibraryDao, favoriteEvents: FavoriteEvents): LibraryRepository =
        LibraryRepositoryImpl(api, dao, favoriteEvents = favoriteEvents)

    @Provides
    @Singleton
    fun provideFavoriteEvents(): FavoriteEvents = FavoriteEvents()

    @Provides
    @Singleton
    fun provideAuthRepository(
        api: RevApi,
        serverConfig: ServerConfigStore,
        sessionStore: SessionStore,
        terminator: SessionTerminator,
        connection: RevConnectionManager,
    ): AuthRepository = AuthRepositoryImpl(
        api,
        serverConfig,
        sessionStore,
        terminator,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        serverUrl = { connection.awaitActiveUrl() },
    )

    @Provides
    @Singleton
    fun provideConnectionRepository(
        servers: ServerRepository,
        network: NetworkMonitor,
        connection: RevConnectionManager,
        @ApplicationScope scope: CoroutineScope,
    ): ConnectionRepository = ConnectionMonitor(
        servers,
        network,
        scope,
        endpoint = connection.endpoint,
        onRecheck = connection::recheck,
    )
}
