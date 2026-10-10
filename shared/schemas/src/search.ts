import { z } from 'zod';
import { Track } from './library.js';
import { AlbumAvailability } from './states.js';

export const SearchQuery = z.object({
  q: z.string().trim().min(1).max(200),
});
export type SearchQuery = z.infer<typeof SearchQuery>;

/** MusicBrainz ids identify catalog (not yet owned) artists and albums. */
export const MusicBrainzId = z.string().uuid();

export const SearchArtist = z.object({
  name: z.string(),
  disambiguation: z.string().nullable(),
  /** Navidrome artist id when the artist is in the playable library. */
  libraryId: z.string().nullable(),
  coverArtId: z.string().nullable(),
  /** MusicBrainz artist id, when known from the catalog. */
  catalogId: z.string().nullable(),
});
export type SearchArtist = z.infer<typeof SearchArtist>;

export const SearchAlbum = z.object({
  /** Stable key for lists: `lib:<navidromeId>` or `mb:<musicBrainzReleaseGroupId>`. */
  key: z.string(),
  title: z.string(),
  artist: z.string(),
  year: z.number().int().nullable(),
  /** Album, EP, Single, ... when known from the catalog. */
  albumType: z.string().nullable(),
  availability: AlbumAvailability,
  /** Navidrome album id; set when the album is playable. */
  libraryId: z.string().nullable(),
  coverArtId: z.string().nullable(),
  /** MusicBrainz release-group id; set for catalog albums (needed to request one). */
  catalogId: z.string().nullable(),
  trackCount: z.number().int().nullable(),
});
export type SearchAlbum = z.infer<typeof SearchAlbum>;

/** Whether each source contributed. Search still answers when one is down. */
export const SourceStatus = z.enum(['ok', 'unavailable', 'not_configured']);
export type SourceStatus = z.infer<typeof SourceStatus>;

export const SearchResponse = z.object({
  query: z.string(),
  artists: z.array(SearchArtist),
  albums: z.array(SearchAlbum),
  tracks: z.array(Track),
  sources: z.object({ library: SourceStatus, catalog: SourceStatus }),
});
export type SearchResponse = z.infer<typeof SearchResponse>;
