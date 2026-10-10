package app.rev.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import app.rev.data.local.downloads.DownloadJobDao
import app.rev.data.local.downloads.DownloadJobEntity
import app.rev.data.local.library.AlbumEntity
import app.rev.data.local.library.ArtistEntity
import app.rev.data.local.library.GenreEntity
import app.rev.data.local.library.LibraryDao
import app.rev.data.local.library.ListEntryEntity
import app.rev.data.local.library.TrackEntity
import app.rev.data.local.library.PlaylistEntity
import app.rev.data.local.lyrics.LyricsDao
import app.rev.data.local.lyrics.LyricsEntity
import app.rev.data.local.offline.OfflineCollectionEntity
import app.rev.data.local.offline.OfflineCollectionTrackEntity
import app.rev.data.local.offline.OfflineTrackDao
import app.rev.data.local.playback.PlaybackQueueDao
import app.rev.data.local.playback.PlaybackQueueEntity
import app.rev.data.local.offline.OfflineTrackEntity
import app.rev.data.local.stats.PendingListenDao
import app.rev.data.local.stats.PendingListenEntity

@Database(
    entities = [
        OfflineTrackEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        GenreEntity::class,
        ListEntryEntity::class,
        PlaybackQueueEntity::class,
        DownloadJobEntity::class,
        PlaylistEntity::class,
        OfflineCollectionEntity::class,
        OfflineCollectionTrackEntity::class,
        LyricsEntity::class,
        PendingListenEntity::class,
    ],
    version = 12,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
    ],
)
abstract class RevDatabase : RoomDatabase() {
    abstract fun offlineTrackDao(): OfflineTrackDao
    abstract fun libraryDao(): LibraryDao
    abstract fun playbackQueueDao(): PlaybackQueueDao
    abstract fun downloadJobDao(): DownloadJobDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun pendingListenDao(): PendingListenDao
}
