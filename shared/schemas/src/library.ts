import { z } from 'zod';

// REV's normalized library model. Navidrome/Subsonic shapes never reach clients.
// Durations are milliseconds; timestamps are epoch milliseconds.

export const Artist = z.object({
  id: z.string(),
  name: z.string(),
  albumCount: z.number().int().nonnegative(),
  coverArtId: z.string().nullable(),
  starred: z.boolean(),
});
export type Artist = z.infer<typeof Artist>;

export const Album = z.object({
  id: z.string(),
  name: z.string(),
  artist: z.string(),
  artistId: z.string().nullable(),
  year: z.number().int().nullable(),
  genre: z.string().nullable(),
  coverArtId: z.string().nullable(),
  trackCount: z.number().int().nonnegative(),
  durationMs: z.number().int().nonnegative(),
  starred: z.boolean(),
  /** When the album was added to the library. */
  createdAt: z.number().int().nullable(),
  /** When any track of the album was last played. */
  playedAt: z.number().int().nullable(),
  /**
   * Release types from the tags, lowercased MusicBrainz terms: `album`, `ep`, `single`, plus
   * secondary types such as `compilation` or `live`. Empty when untagged.
   */
  releaseTypes: z.array(z.string()),
});
export type Album = z.infer<typeof Album>;

/** An artist's most relevant songs, best first. */
export const TopTracks = z.object({ tracks: z.array(z.lazy(() => Track)) });
export type TopTracks = z.infer<typeof TopTracks>;

/** Loudness normalization data (dB / linear peak), from the file's ReplayGain tags via Navidrome. */
export const ReplayGain = z.object({
  trackGainDb: z.number().nullable(),
  albumGainDb: z.number().nullable(),
  trackPeak: z.number().nullable(),
  albumPeak: z.number().nullable(),
});
export type ReplayGain = z.infer<typeof ReplayGain>;

export const Track = z.object({
  id: z.string(),
  title: z.string(),
  album: z.string(),
  albumId: z.string().nullable(),
  artist: z.string(),
  artistId: z.string().nullable(),
  trackNumber: z.number().int().nullable(),
  discNumber: z.number().int().nullable(),
  year: z.number().int().nullable(),
  genre: z.string().nullable(),
  coverArtId: z.string().nullable(),
  durationMs: z.number().int().nonnegative(),
  starred: z.boolean(),
  /** File details, for the track-details view. */
  format: z.string().nullable(),
  bitRateKbps: z.number().int().nullable(),
  sampleRateHz: z.number().int().nullable(),
  bitDepth: z.number().int().nullable(),
  sizeBytes: z.number().int().nullable(),
  replayGain: ReplayGain.nullable(),
});
export type Track = z.infer<typeof Track>;

export const Genre = z.object({
  name: z.string(),
  albumCount: z.number().int().nonnegative(),
  trackCount: z.number().int().nonnegative(),
});
export type Genre = z.infer<typeof Genre>;

export const ArtistDetail = Artist.extend({ albums: z.array(Album) });
export type ArtistDetail = z.infer<typeof ArtistDetail>;

export const AlbumDetail = Album.extend({ tracks: z.array(Track) });
export type AlbumDetail = z.infer<typeof AlbumDetail>;

export const ArtistList = z.object({ artists: z.array(Artist) });
export type ArtistList = z.infer<typeof ArtistList>;

export const GenreList = z.object({ genres: z.array(Genre) });
export type GenreList = z.infer<typeof GenreList>;

export const AlbumListType = z.enum([
  'alphabetical',
  'newest',
  'recent',
  'frequent',
  'random',
  'starred',
  'byGenre',
]);
export type AlbumListType = z.infer<typeof AlbumListType>;

const Paging = {
  offset: z.coerce.number().int().min(0).max(1_000_000).default(0),
  size: z.coerce.number().int().min(1).max(500).default(50),
};

export const AlbumListQuery = z
  .object({
    type: AlbumListType.default('alphabetical'),
    genre: z.string().min(1).max(200).optional(),
    ...Paging,
  })
  .refine((q) => q.type !== 'byGenre' || q.genre !== undefined, {
    message: 'genre is required when type is byGenre',
    path: ['genre'],
  });
export type AlbumListQuery = z.infer<typeof AlbumListQuery>;

export const TrackListQuery = z.object(Paging);
export type TrackListQuery = z.infer<typeof TrackListQuery>;

/** A page of results. `hasMore` is false once the end of the collection is reached. */
export const AlbumPage = z.object({
  albums: z.array(Album),
  offset: z.number().int(),
  hasMore: z.boolean(),
});
export type AlbumPage = z.infer<typeof AlbumPage>;

export const TrackPage = z.object({
  tracks: z.array(Track),
  offset: z.number().int(),
  hasMore: z.boolean(),
});
export type TrackPage = z.infer<typeof TrackPage>;

export const Favorites = z.object({
  artists: z.array(Artist),
  albums: z.array(Album),
  tracks: z.array(Track),
});
export type Favorites = z.infer<typeof Favorites>;

export const FavoriteKind = z.enum(['artists', 'albums', 'tracks']);
export type FavoriteKind = z.infer<typeof FavoriteKind>;

/** Library item ids as issued by Navidrome. Restricted so they are safe to forward. */
export const LibraryId = z.string().regex(/^[A-Za-z0-9._-]{1,100}$/);

export const ArtworkQuery = z.object({
  size: z.coerce.number().int().min(32).max(2000).optional(),
});

/** Bit rates a client may request for transcoded streaming (kbps). Omit for the original file. */
export const StreamQuery = z.object({
  maxBitRate: z.coerce
    .number()
    .int()
    .refine((v) => [64, 96, 128, 160, 192, 256, 320].includes(v), 'unsupported bit rate')
    .optional(),
});
export type StreamQuery = z.infer<typeof StreamQuery>;

export const ScrobbleRequest = z.object({
  /** false: now playing; true: the track was listened to (record a play). */
  submission: z.boolean(),
  /** When playback started, epoch ms. Defaults to now. */
  playedAt: z.number().int().positive().optional(),
});
export type ScrobbleRequest = z.infer<typeof ScrobbleRequest>;

export const Playlist = z.object({
  id: z.string(),
  name: z.string(),
  comment: z.string().nullable(),
  owner: z.string().nullable(),
  trackCount: z.number().int().nonnegative(),
  durationMs: z.number().int().nonnegative(),
  coverArtId: z.string().nullable(),
  updatedAt: z.number().int().nullable(),
  /**
   * REV can change it: it belongs to the Navidrome account REV uses. Other users' (public)
   * playlists are read-only.
   */
  canEdit: z.boolean(),
});
export type Playlist = z.infer<typeof Playlist>;

export const PlaylistList = z.object({ playlists: z.array(Playlist) });
export type PlaylistList = z.infer<typeof PlaylistList>;

export const PlaylistDetail = Playlist.extend({ tracks: z.array(Track) });
export type PlaylistDetail = z.infer<typeof PlaylistDetail>;

const PlaylistName = z.string().trim().min(1).max(200);
const PlaylistComment = z.string().trim().max(1000);
/** Generous: a playlist of a whole library is fine, an unbounded request isn't. */
const PlaylistTrackIds = z.array(LibraryId).max(10_000);

export const CreatePlaylistRequest = z.object({
  name: PlaylistName,
  comment: PlaylistComment.optional(),
  /** Songs to start with, in order. */
  trackIds: PlaylistTrackIds.default([]),
});
export type CreatePlaylistRequest = z.infer<typeof CreatePlaylistRequest>;

export const UpdatePlaylistRequest = z
  .object({
    name: PlaylistName.optional(),
    /** An empty comment clears it. */
    comment: PlaylistComment.optional(),
  })
  .refine((r) => r.name !== undefined || r.comment !== undefined, 'nothing to change');
export type UpdatePlaylistRequest = z.infer<typeof UpdatePlaylistRequest>;

export const AddPlaylistTracksRequest = z.object({
  trackIds: PlaylistTrackIds.min(1),
  /** Leave out songs already in the playlist (default), or add them again. */
  skipDuplicates: z.boolean().default(true),
});
export type AddPlaylistTracksRequest = z.infer<typeof AddPlaylistTracksRequest>;

export const AddPlaylistTracksResult = z.object({
  playlist: PlaylistDetail,
  added: z.number().int().nonnegative(),
  /** Already in the playlist, so left out. */
  skipped: z.number().int().nonnegative(),
});
export type AddPlaylistTracksResult = z.infer<typeof AddPlaylistTracksResult>;

/** Copies a playlist (any, including read-only ones) into a new one REV can edit. */
export const DuplicatePlaylistRequest = z.object({
  /** Defaults to "<name> (copy)". */
  name: PlaylistName.optional(),
});
export type DuplicatePlaylistRequest = z.infer<typeof DuplicatePlaylistRequest>;

/** The playlist's complete new song list, in order: how songs are removed and reordered. */
export const SetPlaylistTracksRequest = z.object({ trackIds: PlaylistTrackIds });
export type SetPlaylistTracksRequest = z.infer<typeof SetPlaylistTracksRequest>;

/**
 * Lets a Cast receiver (Chromecast), which can't send REV's session token, fetch audio and artwork:
 * `<baseUrl>/api/library/tracks/<id>/stream?exp=<expiresAt seconds>&cast=<token>`. Media only,
 * time-limited.
 */
export const CastToken = z.object({
  baseUrl: z.string(),
  token: z.string(),
  /** Epoch seconds; goes into the URL as `exp`. */
  expiresAt: z.number().int(),
});
export type CastToken = z.infer<typeof CastToken>;
