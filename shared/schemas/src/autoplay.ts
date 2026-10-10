import { z } from 'zod';
import { Album, Track } from './library.js';

const Id = z.string().min(1).max(200);

/** What to play after an album ends: the album that finished and albums already played. */
export const NextAlbumRequest = z.object({
  albumId: Id,
  artistId: Id.nullable(),
  genre: z.string().max(200).nullable(),
  /** Albums not to pick (already in the queue). */
  exclude: z.array(Id).max(100).default([]),
});
export type NextAlbumRequest = z.infer<typeof NextAlbumRequest>;

export const NextAlbum = z.object({ album: Album.nullable(), tracks: z.array(Track) });
export type NextAlbum = z.infer<typeof NextAlbum>;

/** More songs like the queue's: its main artists and genres. */
export const RadioRequest = z.object({
  artistIds: z.array(Id).max(10),
  genres: z.array(z.string().max(200)).max(10),
  /** Songs not to pick (already in the queue). */
  exclude: z.array(Id).max(1000).default([]),
  /** Artists not to pick from at all (ones just skipped). */
  avoidArtists: z.array(Id).max(50).default([]),
  /** How many songs to return at most; a bigger pool lets the app pick by taste. */
  size: z.number().int().min(1).max(60).default(25),
});
export type RadioRequest = z.infer<typeof RadioRequest>;

/**
 * More by one artist, after a queue of only their songs (such as their top songs). Once none of
 * their songs are left, similar artists and the genre, without the artist.
 */
export const ArtistContinuationRequest = z.object({
  artistId: Id,
  genres: z.array(z.string().max(200)).max(10).default([]),
  /** Songs not to pick (already in the queue). */
  exclude: z.array(Id).max(1000).default([]),
  /** Albums of theirs not to pick from (ones being skipped). */
  excludeAlbums: z.array(Id).max(100).default([]),
  size: z.number().int().min(1).max(60).default(25),
});
export type ArtistContinuationRequest = z.infer<typeof ArtistContinuationRequest>;

export const RadioTracks = z.object({ tracks: z.array(Track) });
export type RadioTracks = z.infer<typeof RadioTracks>;
