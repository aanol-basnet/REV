import type { Mix, RadioRequest, TimeOfDay, Track } from '@rev/schemas';
import { resolveTracks } from '../library/resolve.js';
import { timeOfDay, type TimeTaste } from '../stats/time-of-day.js';
import { Zone } from '../stats/zone.js';
import { spreadArtists, uniqueById } from './service.js';

/** What the time-of-day mix needs; fakes in tests. */
export interface TimeMixSources {
  /** What the user listens to in each part of the day. */
  times(userId: number, tz: string): TimeTaste[];
  track(id: string): Promise<Track>;
  /** Songs like the given artists and genres ([AutoplayService.radio]). */
  radio(req: RadioRequest): Promise<Track[]>;
  now: () => number;
  random: () => number;
}

const SIZE = 50;
/** Your own songs for this time; the rest are new songs that fit it. */
const OWN = 34;
/** A time of day needs this many songs of its own before it gets a mix. */
const MIN_TRACKS = 4;

/**
 * "Your morning mix" (afternoon, evening, night): the songs you play at this time of day, picked
 * at random but favouring the ones that belong to it most, with songs like them mixed in (the
 * artists and genres you lean toward then), about one in three. Songs you skip at this time stay
 * out.
 */
export class TimeMixService {
  constructor(private readonly sources: TimeMixSources) {}

  /** The mix for the time of day it is now in [tz]; null until there's enough listening then. */
  current(userId: number, tz: string): Mix | null {
    const time = timeOfDay(new Zone(tz).hour(this.sources.now()));
    const taste = this.sources.times(userId, tz).find((t) => t.time === time);
    if (!taste || taste.tracks.length < MIN_TRACKS) return null;
    const artists = [...new Set(taste.tracks.map((t) => t.artist))];
    return {
      id: `time:${time}`,
      kind: 'time',
      name: time,
      featuring: artists.slice(0, 3),
      coverArtIds: [
        ...new Set(taste.tracks.flatMap((t) => (t.coverArtId ? [t.coverArtId] : []))),
      ].slice(0, 4),
    };
  }

  async tracks(userId: number, time: TimeOfDay, tz: string): Promise<Track[]> {
    const taste = this.sources.times(userId, tz).find((t) => t.time === time);
    if (!taste) return [];
    const ownIds = weightedOrder(taste.tracks, this.sources.random).slice(0, OWN);
    const own = await resolveTracks(ownIds, (id) => this.sources.track(id));
    // Like the artists and genres you lean toward then; when nothing stands out (you play the
    // same things all day), like the artists of your songs for this time.
    const leaning = <T extends { score: number }>(items: T[]) => items.filter((i) => i.score > 0);
    const artistIds = leaning(taste.artists).map((a) => a.id);
    const seeds =
      artistIds.length > 0 ? artistIds : own.flatMap((t) => (t.artistId ? [t.artistId] : []));
    const fresh = await this.sources
      .radio({
        artistIds: [...new Set(seeds)].slice(0, 3),
        genres: leaning(taste.genres)
          .map((g) => g.name)
          .slice(0, 2),
        exclude: [...taste.tracks.map((t) => t.id), ...taste.skipped].slice(0, 1000),
        avoidArtists: [],
        size: SIZE - own.length + 5,
      })
      .catch(() => [] as Track[]);
    // Two of yours, then one new.
    const mixed: Track[] = [];
    let o = 0;
    let f = 0;
    while (mixed.length < SIZE && (o < own.length || f < fresh.length)) {
      const freshTurn = mixed.length % 3 === 2;
      if (freshTurn && f < fresh.length) mixed.push(fresh[f++] as Track);
      else if (o < own.length) mixed.push(own[o++] as Track);
      else mixed.push(fresh[f++] as Track);
    }
    return spreadArtists(uniqueById(mixed));
  }
}

/**
 * The songs in a random order that favours higher scores: a song with twice the score tends to
 * come earlier, but every song gets a turn now and then, so the mix differs each time.
 */
export function weightedOrder(
  tracks: { id: string; score: number }[],
  random: () => number,
): string[] {
  return tracks
    .map((t) => ({ id: t.id, key: Math.log(Math.max(random(), 1e-12)) / t.score }))
    .sort((a, b) => b.key - a.key)
    .map((t) => t.id);
}
