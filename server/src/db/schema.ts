import { sql } from 'drizzle-orm';
import { index, integer, real, sqliteTable, text, uniqueIndex } from 'drizzle-orm/sqlite-core';

/** Small key/value store for per-installation server state (e.g. the instance id). */
export const serverMeta = sqliteTable('server_meta', {
  key: text('key').primaryKey(),
  value: text('value').notNull(),
});

/** REV accounts. Created from the CLI (`npm run user`); there is no self-registration. */
export const users = sqliteTable('users', {
  id: integer('id').primaryKey({ autoIncrement: true }),
  /** Stored lowercased; usernames are case-insensitive. */
  username: text('username').notNull().unique(),
  passwordHash: text('password_hash').notNull(),
  createdAt: integer('created_at').notNull(),
  updatedAt: integer('updated_at').notNull(),
});

/**
 * One row per logged-in device. Only SHA-256 hashes of tokens are stored.
 * Refresh tokens rotate on every use; `previousRefreshTokenHash` enables reuse detection.
 */
export const sessions = sqliteTable(
  'sessions',
  {
    id: text('id').primaryKey(),
    userId: integer('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    deviceName: text('device_name'),
    accessTokenHash: text('access_token_hash').notNull().unique(),
    accessTokenExpiresAt: integer('access_token_expires_at').notNull(),
    refreshTokenHash: text('refresh_token_hash').notNull().unique(),
    refreshTokenExpiresAt: integer('refresh_token_expires_at').notNull(),
    previousRefreshTokenHash: text('previous_refresh_token_hash'),
    rotatedAt: integer('rotated_at'),
    createdAt: integer('created_at').notNull(),
    lastUsedAt: integer('last_used_at').notNull(),
    revokedAt: integer('revoked_at'),
  },
  (t) => [
    index('sessions_user_id_idx').on(t.userId),
    index('sessions_previous_refresh_idx')
      .on(t.previousRefreshTokenHash)
      .where(sql`${t.previousRefreshTokenHash} IS NOT NULL`),
  ],
);

/**
 * REV's own record of each album acquisition. Lidarr ids and what REV changed in Lidarr are kept
 * so a cancelled request can be undone without touching things the user set up themselves.
 */
export const downloadJobs = sqliteTable(
  'download_jobs',
  {
    id: text('id').primaryKey(),
    catalogAlbumId: text('catalog_album_id').notNull(),
    catalogArtistId: text('catalog_artist_id'),
    title: text('title').notNull(),
    artist: text('artist').notNull(),
    year: integer('year'),
    state: text('state').notNull(),
    progress: real('progress'),
    error: text('error'),
    lidarrAlbumId: integer('lidarr_album_id'),
    lidarrArtistId: integer('lidarr_artist_id'),
    /** REV added the album to Lidarr (it wasn't tracked before). */
    addedToLidarr: integer('added_to_lidarr', { mode: 'boolean' }).notNull().default(false),
    /** REV switched the album from unmonitored to monitored. */
    monitoredByRev: integer('monitored_by_rev', { mode: 'boolean' }).notNull().default(false),
    libraryAlbumId: text('library_album_id'),
    requestedBy: integer('requested_by').references(() => users.id, { onDelete: 'set null' }),
    startedAt: integer('started_at').notNull(),
    updatedAt: integer('updated_at').notNull(),
    completedAt: integer('completed_at'),
  },
  (t) => [
    index('download_jobs_catalog_album_idx').on(t.catalogAlbumId),
    index('download_jobs_state_idx').on(t.state),
  ],
);

/**
 * REV's lyrics cache (§30), one row per track. A row with status "not_found" is a cached negative
 * answer, re-checked after a while; providers' failures are never cached.
 */
export const lyrics = sqliteTable('lyrics', {
  trackId: text('track_id').primaryKey(),
  status: text('status').notNull(),
  source: text('source'),
  sourceId: text('source_id'),
  plainLyrics: text('plain_lyrics'),
  /** JSON array of { startMs, text }. */
  syncedLyrics: text('synced_lyrics'),
  isInstrumental: integer('is_instrumental', { mode: 'boolean' }).notNull().default(false),
  fetchedAt: integer('fetched_at').notNull(),
  updatedAt: integer('updated_at').notNull(),
});

/**
 * REV's dated listening log, one row per listen. Navidrome only keeps a count and the last play per
 * track, so this is what reports are built from. Track details are copied in at the time of the
 * listen so the history stays readable after files are retagged or removed.
 */
export const plays = sqliteTable(
  'plays',
  {
    id: integer('id').primaryKey({ autoIncrement: true }),
    userId: integer('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    trackId: text('track_id').notNull(),
    /** `play` or `skip`. */
    kind: text('kind').notNull(),
    startedAt: integer('started_at').notNull(),
    listenedMs: integer('listened_ms').notNull(),
    title: text('title').notNull(),
    artist: text('artist').notNull(),
    artistId: text('artist_id'),
    album: text('album').notNull(),
    albumId: text('album_id'),
    coverArtId: text('cover_art_id'),
    year: integer('year'),
    /** JSON array of genre names. */
    genres: text('genres').notNull(),
    durationMs: integer('duration_ms').notNull(),
    recordedAt: integer('recorded_at').notNull(),
  },
  (t) => [
    uniqueIndex('plays_user_track_started_idx').on(t.userId, t.trackId, t.startedAt),
    index('plays_user_started_idx').on(t.userId, t.startedAt),
  ],
);

/** Where each smart playlist was last saved in Navidrome, so saving again updates it. */
export const smartPlaylistSaves = sqliteTable(
  'smart_playlist_saves',
  {
    userId: integer('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    smartId: text('smart_id').notNull(),
    playlistId: text('playlist_id').notNull(),
    savedAt: integer('saved_at').notNull(),
  },
  (t) => [uniqueIndex('smart_playlist_saves_user_smart_idx').on(t.userId, t.smartId)],
);
