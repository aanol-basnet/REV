package app.rev.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.WorkManager
import app.rev.data.local.offline.OfflineTrackDao
import app.rev.data.offline.OfflineDownloadScheduler
import app.rev.data.offline.OfflineFiles
import app.rev.data.offline.OfflineMixRepositoryImpl
import app.rev.data.offline.OfflineRepositoryImpl
import app.rev.data.offline.OfflineSettings
import app.rev.data.offline.SmartDownloadScheduler
import app.rev.data.AppearanceStore
import app.rev.data.PlaybackSettingsStore
import app.rev.domain.playback.PlaybackSettingsRepository
import app.rev.domain.repository.AppearanceRepository
import app.rev.domain.repository.ArtworkPrefetcher
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.OfflineMixRepository
import app.rev.domain.repository.OfflineRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OfflineModule {
    @Provides
    @Singleton
    fun provideOfflineFiles(@ApplicationContext context: Context): OfflineFiles = OfflineFiles(context)

    @Provides
    @Singleton
    fun provideAppearance(dataStore: DataStore<Preferences>): AppearanceRepository = AppearanceStore(dataStore)

    @Provides
    @Singleton
    fun providePlaybackSettings(dataStore: DataStore<Preferences>): PlaybackSettingsRepository =
        PlaybackSettingsStore(dataStore)

    @Provides
    @Singleton
    fun provideOfflineSettings(dataStore: DataStore<Preferences>): OfflineSettings = OfflineSettings(dataStore)

    /** WorkManager is initialized on demand with the app's Hilt worker factory (see RevApplication). */
    @Provides
    @Singleton
    fun provideScheduler(@ApplicationContext context: Context): OfflineDownloadScheduler =
        OfflineDownloadScheduler(WorkManager.getInstance(context))

    @Provides
    @Singleton
    fun provideSmartScheduler(@ApplicationContext context: Context, settings: OfflineSettings): SmartDownloadScheduler =
        SmartDownloadScheduler(context, settings)

    @Provides
    @Singleton
    fun provideOfflineRepository(
        dao: OfflineTrackDao,
        scheduler: OfflineDownloadScheduler,
        files: OfflineFiles,
        settings: OfflineSettings,
        artwork: ArtworkPrefetcher,
        smart: SmartDownloadScheduler,
    ): OfflineRepository = OfflineRepositoryImpl(dao, scheduler, files, settings, artwork, onSmartSettingsChanged = smart::runNow)

    @Provides
    @Singleton
    fun provideOfflineMixes(offline: OfflineRepository, library: LibraryRepository): OfflineMixRepository =
        OfflineMixRepositoryImpl(offline, library)
}
