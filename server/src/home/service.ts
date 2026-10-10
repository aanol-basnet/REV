import type { Album, Genre, HomeFeed, HomeGenre, HomeSection, Mix, Track } from '@rev/schemas';
import type {
  ListenedAlbum,
  ListenedTrack,
  NavidromeLibrary,
} from '../adapters/navidrome/library.js';

/** The parts of the library recommendations need; a fake in tests. */
export type ListeningSource = Pick<
  NavidromeLibrary,
  | 'listenedAlbums'
  | 'artistAlbums'
  | 'similarArtists'
  | 'randomSongs'
  | 'albumTracks'
  | 'genres'
  | 'favorites'
>;

export interface HomeOptions {
  /** How long a built feed is reused unless a refresh is asked for. */
  cacheMs: number;
  now: () => number;
  /** Shuffles; a seeded one in tests. */
  random: () => number;
  onSectionError: (section: string, err: unknown) => void;
}

const ROW = 12;
/** A row with fewer albums than this isn't worth showing. */
const MIN_ROW = 4;
const QUICK_PICKS = 6;
const TOP_ARTISTS = 2;
const TOP_GENRES = 3;
const MIX_SIZE = 50;
const DAY_MS = 86_400_000;
/** "Rediscover" albums haven't been played for at least this long. */
const REDISCOVER_AFTER_MS = 45 * DAY_MS;

interface Weighted<T> {
  item: T;
  weight: number;
}

/**
 * Builds the Home feed from Navidrome's listening history (play counts, last played), its
 * similar-artist data and the library's genres. Everything is drawn from the library itself, so
 * every recommendation is playable. A row whose source fails is left out; the rest still shows.
 *
 * The layout is stable within a day (shuffles are seeded by date) so Home doesn't reshuffle on every
 * refresh, and changes from one day to the next.
 */
export class HomeService {
  private cached: { feed: HomeFeed; at: number } | null = null;
  private building: Promise<HomeFeed> | null = null;

  constructor(
    private readonly library: ListeningSource,
    private readonly options: HomeOptions,
  ) {}

  async feed(refresh = false): Promise<HomeFeed> {
    const now = this.options.now();
    if (!refresh && this.cached && now - this.cached.at < this.options.cacheMs) {
      return this.cached.feed;
    }
    // Concurrent requests share one build.
    this.building ??= this.build(now).finally(() => {
      this.building = null;
    });
    const feed = await this.building;
    this.cached = { feed, at: now };
    return feed;
  }

  private async build(now: number): Promise<HomeFeed> {
    const daily = seeded(Math.floor(now / DAY_MS));
    const [frequent, recent, newest, random, genres, favorites] = await Promise.all([
      this.attempt('frequent', () => this.library.listenedAlbums('frequent', 60), []),
      this.attempt('recent', () => this.library.listenedAlbums('recent', 24), []),
      this.attempt('newest', () => this.library.listenedAlbums('newest', ROW), []),
      this.attempt('random', () => this.library.listenedAlbums('random', 120), []),
      this.attempt('genres', () => this.library.genres(), [] as Genre[]),
      this.attempt('favorites', () => this.library.favorites(), null),
    ]);

    const topArtists = rankArtists(frequent, recent).slice(0, TOP_ARTISTS);
    const topGenres = rankGenres(frequent, genres).slice(0, TOP_GENRES);
    const shown = new Set<string>();
    const take = (albums: Album[], limit = ROW) => {
      const picked = albums.filter((a) => !shown.has(a.id)).slice(0, limit);
      return picked;
    };
    const commit = (section: HomeSection | null): HomeSection | null => {
      if (!section || section.albums.length < MIN_ROW) return null;
      for (const a of section.albums) shown.add(a.id);
      return section;
    };

    const quickPicks = uniqueById([
      ...recent.slice(0, 4).map((l) => l.album),
      ...frequent.map((l) => l.album),
    ]).slice(0, QUICK_PICKS);

    const sections: (HomeSection | null)[] = [];
    // Recently played is always first and may repeat quick picks: it's the "continue" row.
    sections.push(commit(row('recent', recent.map((l) => l.album).slice(0, ROW))));
    // "On repeat" is what you play a lot *now*; heavy rotation from months ago is "Rediscover".
    const lately = (a: Album) => a.playedAt === null || now - a.playedAt <= REDISCOVER_AFTER_MS;
    sections.push(commit(row('frequent', take(frequent.map((l) => l.album).filter(lately)))));

    const similar = await Promise.all(
      topArtists.map(({ item }) =>
        this.attempt(`similar:${item.name}`, () => this.similarAlbums(item.id, daily), {
          albums: [] as Album[],
          artists: [] as string[],
        }),
      ),
    );
    topArtists.forEach(({ item }, i) => {
      sections.push(
        commit({
          id: `similar:${item.id}`,
          kind: 'similar',
          basedOn: item.name,
          basedOnId: item.id,
          albums: take(similar[i]?.albums ?? []),
        }),
      );
    });

    const byGenre = await Promise.all(
      topGenres.map(({ item }) =>
        this.attempt(
          `genre:${item.name}`,
          () => this.library.listenedAlbums({ genre: item.name }, 100),
          [],
        ),
      ),
    );
    const genreRows: HomeSection[] = [];
    topGenres.forEach(({ item }, i) => {
      // Unheard first, then the rest, each in today's order.
      const candidates = byGenre[i] ?? [];
      const ordered = [
        ...shuffle(
          candidates.filter((l) => l.playCount === 0),
          daily,
        ),
        ...shuffle(
          candidates.filter((l) => l.playCount > 0),
          daily,
        ),
      ].map((l) => l.album);
      const section = commit({
        id: `genre:${item.name}`,
        kind: 'genre',
        basedOn: item.name,
        basedOnId: item.name,
        albums: take(ordered),
      });
      if (section) genreRows.push(section);
      sections.push(section);
    });

    sections.push(
      commit(row('rediscover', take(frequent.map((l) => l.album).filter((a) => !lately(a))))),
    );

    const genreNames = new Set(topGenres.map((g) => g.item.name.toLowerCase()));
    const unheard = random
      .filter((l) => l.playCount === 0)
      .map((l) => ({ l, match: l.genres.some((g) => genreNames.has(g.toLowerCase())) ? 1 : 0 }))
      .sort((a, b) => b.match - a.match)
      .map(({ l }) => l.album);
    sections.push(commit(row('unheard', take(unheard))));
    sections.push(
      commit(
        row(
          'newest',
          newest
            .map((l) => l.album)
            .filter((a) => !shown.has(a.id))
            .slice(0, ROW),
        ),
      ),
    );

    const mixes: Mix[] = [];
    topArtists.forEach(({ item }, i) => {
      const s = similar[i];
      const own = frequent.find((l) => l.album.artistId === item.id)?.album.coverArtId;
      mixes.push({
        id: `artist:${item.id}`,
        kind: 'artist',
        name: item.name,
        featuring: (s?.artists ?? []).slice(0, 3),
        coverArtIds: covers([own ?? null, ...(s?.albums ?? []).map((a) => a.coverArtId)]),
      });
    });
    topGenres.slice(0, 2).forEach(({ item }) => {
      const row = genreRows.find((r) => r.basedOn === item.name);
      mixes.push({
        id: `genre:${item.name}`,
        kind: 'genre',
        name: item.name,
        featuring: uniqueStrings((row?.albums ?? []).map((a) => a.artist)).slice(0, 3),
        coverArtIds: covers((row?.albums ?? []).map((a) => a.coverArtId)),
      });
    });
    if (favorites && favorites.tracks.length >= MIN_ROW) {
      mixes.push({
        id: 'favorites',
        kind: 'favorites',
        name: null,
        featuring: uniqueStrings(favorites.tracks.map((t) => t.artist)).slice(0, 3),
        coverArtIds: covers([...favorites.albums, ...favorites.tracks].map((x) => x.coverArtId)),
      });
    }
    if (unheard.length >= MIN_ROW) {
      mixes.push({
        id: 'discovery',
        kind: 'discovery',
        name: null,
        featuring: uniqueStrings(unheard.map((a) => a.artist)).slice(0, 3),
        coverArtIds: covers(unheard.map((a) => a.coverArtId)),
      });
    }

    const homeGenres: HomeGenre[] = rankGenres(frequent, genres)
      .slice(0, 10)
      .map(({ item }) => ({ name: item.name, albumCount: item.albumCount }));

    return {
      generatedAt: now,
      quickPicks,
      mixes,
      sections: sections.filter((s): s is HomeSection => s !== null),
      genres: homeGenres,
    };
  }

  /** Albums by artists similar to [artistId], a couple each, least played first, interleaved. */
  private async similarAlbums(
    artistId: string,
    random: () => number,
  ): Promise<{ albums: Album[]; artists: string[] }> {
    const similar = (await this.library.similarArtists(artistId, 8)).slice(0, 6);
    const perArtist = await Promise.all(
      similar.map(async (artist) => {
        try {
          const albums = await this.library.artistAlbums(artist.id);
          return shuffle(albums, random)
            .sort((a, b) => a.playCount - b.playCount)
            .slice(0, 2)
            .map((l) => l.album);
        } catch {
          return [];
        }
      }),
    );
    return { albums: interleave(perArtist), artists: similar.map((a) => a.name) };
  }

  /** The tracks of a mix, freshly shuffled each time it's played. */
  async mix(id: string): Promise<Track[]> {
    const random = this.options.random;
    if (id === 'favorites') {
      const favorites = await this.library.favorites();
      return spreadArtists(shuffle(favorites.tracks, random)).slice(0, MIX_SIZE);
    }
    if (id === 'discovery') {
      const songs = await this.library.randomSongs(200);
      return spreadArtists(
        pickTracks(
          songs.filter((s) => s.playCount === 0),
          MIX_SIZE,
        ),
      );
    }
    if (id.startsWith('genre:')) {
      const songs = await this.library.randomSongs(MIX_SIZE, id.slice('genre:'.length));
      return spreadArtists(pickTracks(songs, MIX_SIZE));
    }
    const artistId = id.slice('artist:'.length);
    // Built from the library's albums rather than Navidrome's similar-songs lookup, which queries
    // external services per artist and can take longer than a tap should.
    const similar = (await this.library.similarArtists(artistId, 8)).slice(0, 6);
    const [own, ...others] = await Promise.all([
      this.ownTracks(artistId, random, 17),
      ...similar.map((artist) => this.ownTracks(artist.id, random, 7).catch(() => [] as Track[])),
    ]);
    const pool = spreadArtists(shuffle(interleave(others), random));
    // An artist radio: mostly similar artists, with the artist itself every third track.
    const mixed: Track[] = [];
    let o = 0;
    let a = 0;
    const mine = own ?? [];
    while (mixed.length < MIX_SIZE && (o < pool.length || a < mine.length)) {
      const ownTurn = mixed.length % 3 === 0;
      if (ownTurn && a < mine.length) mixed.push(mine[a++] as Track);
      else if (o < pool.length) mixed.push(pool[o++] as Track);
      else mixed.push(mine[a++] as Track);
    }
    return uniqueById(mixed);
  }

  /** A few tracks from a few of the artist's albums. */
  private async ownTracks(artistId: string, random: () => number, count: number): Promise<Track[]> {
    const albums = shuffle(await this.library.artistAlbums(artistId), random).slice(0, 3);
    const tracks = await Promise.all(albums.map((l) => this.library.albumTracks(l.album.id)));
    return shuffle(tracks.flat(), random).slice(0, count);
  }

  private async attempt<T>(section: string, build: () => Promise<T>, fallback: T): Promise<T> {
    try {
      return await build();
    } catch (err) {
      this.options.onSectionError(section, err);
      return fallback;
    }
  }
}

function row(kind: HomeSection['kind'], albums: Album[]): HomeSection {
  return { id: kind, kind, basedOn: null, basedOnId: null, albums };
}

/** Artists by plays, with a boost for what's been played lately. */
export function rankArtists(
  frequent: ListenedAlbum[],
  recent: ListenedAlbum[],
): Weighted<{ id: string; name: string }>[] {
  const weights = new Map<string, Weighted<{ id: string; name: string }>>();
  const add = (album: Album, weight: number) => {
    if (!album.artistId) return;
    const entry = weights.get(album.artistId) ?? {
      item: { id: album.artistId, name: album.artist },
      weight: 0,
    };
    entry.weight += weight;
    weights.set(album.artistId, entry);
  };
  for (const l of frequent) add(l.album, l.playCount);
  recent.slice(0, 10).forEach((l, i) => add(l.album, 10 - i));
  return [...weights.values()].sort((a, b) => b.weight - a.weight);
}

/**
 * Genres by plays, scaled down for genres covering much of the library, so a specific taste
 * ("Metalcore") beats a catch-all ("Rock").
 */
export function rankGenres(frequent: ListenedAlbum[], genres: Genre[]): Weighted<Genre>[] {
  const size = new Map(genres.map((g) => [g.name.toLowerCase(), g]));
  const plays = new Map<string, number>();
  for (const l of frequent) {
    for (const g of l.genres)
      plays.set(g.toLowerCase(), (plays.get(g.toLowerCase()) ?? 0) + l.playCount);
  }
  return [...plays.entries()]
    .map(([name, count]) => {
      const genre = size.get(name);
      if (!genre || genre.albumCount < MIN_ROW) return null;
      return { item: genre, weight: count / Math.sqrt(genre.albumCount) };
    })
    .filter((g): g is Weighted<Genre> => g !== null && g.weight > 0)
    .sort((a, b) => b.weight - a.weight);
}

/** Songs by one artist within this many of each other are spread apart where possible. */
const ARTIST_GAP = 3;

/**
 * Reorders [tracks] so the same artist doesn't come up again within [ARTIST_GAP] songs, keeping
 * the given order otherwise. When only one artist is left, the rest go at the end as they are.
 */
export function spreadArtists<T extends { artist: string }>(tracks: T[]): T[] {
  const remaining = [...tracks];
  const out: T[] = [];
  while (remaining.length > 0) {
    const recent = new Set(out.slice(-ARTIST_GAP).map((t) => t.artist.toLowerCase()));
    const index = remaining.findIndex((t) => !recent.has(t.artist.toLowerCase()));
    out.push(remaining.splice(Math.max(index, 0), 1)[0] as T);
  }
  return out;
}

function pickTracks(songs: ListenedTrack[], size: number): Track[] {
  return uniqueById(songs.map((s) => s.track)).slice(0, size);
}

export function interleave<T>(lists: T[][]): T[] {
  const out: T[] = [];
  for (let i = 0; lists.some((l) => i < l.length); i++) {
    for (const l of lists) if (i < l.length) out.push(l[i] as T);
  }
  return out;
}

export function uniqueById<T extends { id: string }>(items: T[]): T[] {
  const seen = new Set<string>();
  return items.filter((i) => !seen.has(i.id) && seen.add(i.id));
}

function uniqueStrings(items: string[]): string[] {
  return [...new Set(items)];
}

function covers(ids: (string | null)[]): string[] {
  return uniqueStrings(ids.filter((id): id is string => id !== null)).slice(0, 4);
}

export function shuffle<T>(items: T[], random: () => number): T[] {
  const out = [...items];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [out[i], out[j]] = [out[j] as T, out[i] as T];
  }
  return out;
}

/** A small deterministic generator (mulberry32). */
export function seeded(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
