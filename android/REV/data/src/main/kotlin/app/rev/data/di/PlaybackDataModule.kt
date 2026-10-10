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
import android.content.Context
import app.rev.data.stats.TasteRepositoryImpl
import app.rev.domain.taste.ListeningSession
import app.rev.domain.taste.TasteRanker
import app.rev.domain.taste.TasteRepository
import dagger.Module
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalTime
import java.util.TimeZone
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
    fun provideAutoplay(api: LibraryApi, offline: OfflineRepository, ranker: TasteRanker): AutoplayRepository =
        AutoplayRepositoryImpl(api, offline, ranker)

    /** The current listening session, shared by the smart queue and autoplay. */
    @Provides
    @Singleton
    fun provideListeningSession(): ListeningSession = ListeningSession()

    @Provides
    @Singleton
    fun provideTasteRepositoryImpl(@ApplicationContext context: Context, api: LibraryApi): TasteRepositoryImpl =
        TasteRepositoryImpl(api, File(context.filesDir, "taste_profile.json"), timeZone = { TimeZone.getDefault().id })

    @Provides
    fun provideTasteRepository(impl: TasteRepositoryImpl): TasteRepository = impl

    @Provides
    @Singleton
    fun provideTasteRanker(session: ListeningSession, taste: TasteRepository): TasteRanker =
        TasteRanker(session, taste, hour = { LocalTime.now().hour })

    @Provides
    @Singleton
    fun providePlaybackReporter(api: LibraryApi, listens: ListenQueue): PlaybackReporter = PlaybackReporterImpl(api, listens)

    @Provides
    @Singleton
    fun provideQueueStore(dao: PlaybackQueueDao): QueueStore = QueueStoreImpl(dao)
}
