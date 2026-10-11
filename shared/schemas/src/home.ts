import { z } from 'zod';
import { Album, Track } from './library.js';
import { TimeZone } from './stats.js';

/**
 * Home screen recommendations, built by the server from the listening history Navidrome keeps
 * (play counts, last played), its similar-artist data and the library's genres. Titles are left to
 * clients (localized); each item says what it is and what it's based on.
 */

/** `time`: what you play at this time of day (`name` is the `TimeOfDay`), and songs that fit it. */
export const MixKind = z.enum(['artist', 'genre', 'favorites', 'discovery', 'time']);
export type MixKind = z.infer<typeof MixKind>;

/** A playable, generated track list. Fetch its tracks with `GET /api/mixes/:id`. */
export const Mix = z.object({
  /** `artist:<artistId>`, `genre:<name>`, `favorites`, `discovery` or `time:<TimeOfDay>`. */
  id: z.string(),
  kind: MixKind,
  /** The artist or genre it's built around, or the time of day; null for favorites/discovery. */
  name: z.string().nullable(),
  /** Other artists that feature, for a subtitle ("with Slipknot, KoЯn…"). */
  featuring: z.array(z.string()),
  /** Up to four covers for a collage. */
  coverArtIds: z.array(z.string()),
});
export type Mix = z.infer<typeof Mix>;

export const HomeSectionKind = z.enum([
  /** Recently played. */
  'recent',
  /** Most played. */
  'frequent',
  /** Albums by artists similar to `basedOn` (an artist you play a lot). */
  'similar',
  /** More of `basedOn` (a genre you play a lot), unheard first. */
  'genre',
  /** Albums you played a lot but not lately. */
  'rediscover',
  /** Albums in your library you've never played, leaning to your genres. */
  'unheard',
  /** Recently added to the library. */
  'newest',
]);
export type HomeSectionKind = z.infer<typeof HomeSectionKind>;

export const HomeSection = z.object({
  id: z.string(),
  kind: HomeSectionKind,
  /** Artist name (similar) or genre (genre); null otherwise. */
  basedOn: z.string().nullable(),
  /** Artist id for `similar`, genre name for `genre`; for linking. */
  basedOnId: z.string().nullable(),
  albums: z.array(Album),
});
export type HomeSection = z.infer<typeof HomeSection>;

export const HomeGenre = z.object({ name: z.string(), albumCount: z.number().int().nonnegative() });
export type HomeGenre = z.infer<typeof HomeGenre>;

export const HomeFeed = z.object({
  generatedAt: z.number().int(),
  /** A handful of albums for quick access: what you've been playing. */
  quickPicks: z.array(Album),
  mixes: z.array(Mix),
  sections: z.array(HomeSection),
  /** Genres you listen to most, for browsing. */
  genres: z.array(HomeGenre),
});
export type HomeFeed = z.infer<typeof HomeFeed>;

export const HomeQuery = z.object({
  /** Rebuild now instead of using the recent result. */
  refresh: z.enum(['true', 'false']).optional(),
  /** The listener's time zone (IANA); with it, the feed includes the mix for this time of day. */
  tz: TimeZone.optional(),
});

/** For `time:` mixes: the listener's time zone (IANA), UTC if left out. */
export const MixQuery = z.object({ tz: TimeZone.optional() });

export const MixId = z
  .string()
  .max(300)
  .regex(
    /^(artist:[A-Za-z0-9._-]{1,100}|genre:.{1,200}|favorites|discovery|time:(morning|afternoon|evening|night))$/u,
  );

export const MixTracks = z.object({ id: z.string(), tracks: z.array(Track) });
export type MixTracks = z.infer<typeof MixTracks>;
