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
});
export type RadioRequest = z.infer<typeof RadioRequest>;

export const RadioTracks = z.object({ tracks: z.array(Track) });
export type RadioTracks = z.infer<typeof RadioTracks>;
