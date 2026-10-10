package app.rev.data.di

import android.content.Context
import androidx.room.Room
import app.rev.data.local.RevDatabase
import app.rev.data.local.downloads.DownloadJobDao
import app.rev.data.local.library.LibraryDao
import app.rev.data.local.lyrics.LyricsDao
import app.rev.data.local.offline.OfflineTrackDao
import app.rev.data.local.playback.PlaybackQueueDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RevDatabase =
        Room.databaseBuilder(context, RevDatabase::class.java, "rev.db").build()

    @Provides
    fun provideOfflineTrackDao(db: RevDatabase): OfflineTrackDao = db.offlineTrackDao()

    @Provides
    fun provideLibraryDao(db: RevDatabase): LibraryDao = db.libraryDao()

    @Provides
    fun providePlaybackQueueDao(db: RevDatabase): PlaybackQueueDao = db.playbackQueueDao()

    @Provides
    fun provideDownloadJobDao(db: RevDatabase): DownloadJobDao = db.downloadJobDao()

    @Provides
    fun provideLyricsDao(db: RevDatabase): LyricsDao = db.lyricsDao()
}
