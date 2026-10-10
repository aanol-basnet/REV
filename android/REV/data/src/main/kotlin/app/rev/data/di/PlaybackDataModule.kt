package app.rev.data.di

import app.rev.data.local.offline.OfflineTrackDao
import app.rev.data.local.playback.PlaybackQueueDao
import app.rev.data.playback.ArtworkUrlsImpl
import app.rev.data.playback.PlaybackReporterImpl
import app.rev.data.playback.QueueStoreImpl
import app.rev.data.stats.ListenQueue
import app.rev.data.playback.TrackSourceResolverImpl
import app.rev.data.playback.authenticatedMediaHttpClient
import app.rev.data.remote.LibraryApi
import app.rev.data.playback.AutoplayRepositoryImpl
import app.rev.domain.playback.AutoplayRepository
import app.rev.domain.repository.OfflineRepository
import app.rev.data.remote.RevHttpClient
import app.rev.data.connection.RevConnectionManager
import app.rev.data.playback.CastLinksImpl
import app.rev.domain.playback.ArtworkUrls
import app.rev.domain.playback.CastLinks
import app.rev.domain.playback.PlaybackReporter
import app.rev.domain.playback.QueueStore
import app.rev.domain.playback.TrackSourceResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object PlaybackDataModule {

    /** HTTP stack for audio streams (ExoPlayer): carries the REV session to the configured server only. */
    @Provides
    @Singleton
    fun provideAuthenticatedOkHttp(http: RevHttpClient): OkHttpClient = authenticatedMediaHttpClient(http)

    @Provides
    @Singleton
    fun provideMediaCallFactory(client: OkHttpClient): Call.Factory = client

    @Provides
    @Singleton
    fun provideTrackSourceResolver(offlineTracks: OfflineTrackDao, connection: RevConnectionManager): TrackSourceResolver =
        TrackSourceResolverImpl(offlineTracks, connection::activeUrl)

    @Provides
    @Singleton
    fun provideArtworkUrls(connection: RevConnectionManager): ArtworkUrls = ArtworkUrlsImpl(connection::activeUrl)

    @Provides
    @Singleton
    fun provideCastLinks(api: LibraryApi): CastLinks = CastLinksImpl(api)

    @Provides
    @Singleton
    fun provideAutoplay(api: LibraryApi, offline: OfflineRepository): AutoplayRepository = AutoplayRepositoryImpl(api, offline)

    @Provides
    @Singleton
    fun providePlaybackReporter(api: LibraryApi, listens: ListenQueue): PlaybackReporter = PlaybackReporterImpl(api, listens)

    @Provides
    @Singleton
    fun provideQueueStore(dao: PlaybackQueueDao): QueueStore = QueueStoreImpl(dao)
}
