import type {
  Album,
  ArtistContinuationRequest,
  NextAlbum,
  NextAlbumRequest,
  RadioRequest,
  Track,
} from '@rev/schemas';
import type { ListenedAlbum, NavidromeLibrary } from '../adapters/navidrome/library.js';
import { interleave, shuffle, spreadArtists, uniqueById } from './service.js';

/** The parts of the library autoplay needs; a fake in tests. */
export type AutoplaySource = Pick<
  NavidromeLibrary,
  'artistAlbums' | 'similarArtists' | 'albumTracks' | 'listenedAlbums' | 'randomSongs'
>;

const RADIO_SIZE = 25;
/** Similar artists asked for per seed artist. */
const SIMILAR_PER_SEED = 5;
const RADIO_ARTISTS = 10;
const TRACKS_PER_ARTIST = 4;

/**
 * Keeps music going when the queue runs out. After an album: the artist's next release, else an
 * album by a similar artist, else one in the same genre, else any album. After one artist's songs
 * (their top songs): the rest of their songs, then similar artists. After anything else
 * (a playlist, a mix, search results): songs like the queue's, from its artists, similar
 * artists and its genres. Everything comes from the library, so it all plays.
 */
export class AutoplayService {
  constructor(
    private readonly library: AutoplaySource,
    private readonly random: () => number = Math.random,
  ) {}

  async nextAlbum(req: NextAlbumRequest): Promise<NextAlbum> {
    const exclude = new Set([req.albumId, ...req.exclude]);
    const album = await this.findNextAlbum(req, exclude);
    if (!album) return { album: null, tracks: [] };
    return { album, tracks: await this.library.albumTracks(album.id) };
  }

  private async findNextAlbum(req: NextAlbumRequest, exclude: Set<string>): Promise<Album | null> {
    const fresh = (albums: ListenedAlbum[]) => albums.filter((l) => !exclude.has(l.album.id));
    if (req.artistId) {
      const artistId = req.artistId;
      // The artist's next release after the one that finished.
      const own = (await this.attempt(() => this.library.artistAlbums(artistId), []))
        .map((l) => l.album)
        .sort(byRelease);
      const current = own.findIndex((a) => a.id === req.albumId);
      const later = own.slice(current + 1).find((a) => !exclude.has(a.id));
      if (later) return later;

      // An album by a similar artist (one of the closest few, for variety), least played first.
      const similar = await this.attempt(() => this.library.similarArtists(artistId, 8), []);
      const order = [...shuffle(similar.slice(0, 3), this.random), ...similar.slice(3)];
      for (const artist of order) {
        const albums = fresh(await this.attempt(() => this.library.artistAlbums(artist.id), []));
        const pick = albums.sort((a, b) => a.playCount - b.playCount)[0];
        if (pick) return pick.album;
      }
    }
    if (req.genre) {
      const genre = req.genre;
      const albums = fresh(
        await this.attempt(() => this.library.listenedAlbums({ genre }, 50), []),
      );
      const pick = shuffle(albums, this.random)[0];
      if (pick) return pick.album;
    }
    const any = fresh(await this.attempt(() => this.library.listenedAlbums('random', 30), []));
    return any[0]?.album ?? null;
  }

  /**
   * More of the artist's songs not yet played, in random order. When there are none left: a radio
   * of similar artists and the genre, leaving the artist out.
   */
  async artistSongs(req: ArtistContinuationRequest): Promise<Track[]> {
    const exclude = new Set(req.exclude);
    const albums = await this.attempt(() => this.library.artistAlbums(req.artistId), []);
    const tracks = await Promise.all(
      albums.map((l) => this.attempt(() => this.library.albumTracks(l.album.id), [])),
    );
    const remaining = uniqueById(tracks.flat().filter((t) => !exclude.has(t.id)));
    if (remaining.length > 0) return shuffle(remaining, this.random).slice(0, RADIO_SIZE);
    return this.radio(
      { artistIds: [req.artistId], genres: req.genres, exclude: req.exclude },
      { withoutSeeds: true },
    );
  }

  /** [withoutSeeds]: leave the seed artists' own songs out. */
  async radio(req: RadioRequest, { withoutSeeds = false } = {}): Promise<Track[]> {
    const exclude = new Set(req.exclude);
    const seeds = req.artistIds.slice(0, 3);
    const similar = await Promise.all(
      seeds.map((id) => this.attempt(() => this.library.similarArtists(id, SIMILAR_PER_SEED), [])),
    );
    const seedSet = new Set(seeds);
    const others = uniqueStrings(interleave(similar).map((a) => a.id))
      .filter((id) => !seedSet.has(id))
      .slice(0, RADIO_ARTISTS);

    const allowed = (t: Track) => !withoutSeeds || t.artistId === null || !seedSet.has(t.artistId);
    const [seedTracks, otherTracks, genreTracks] = await Promise.all([
      withoutSeeds ? [] : Promise.all(seeds.map((id) => this.artistTracks(id, exclude))),
      Promise.all(others.map((id) => this.artistTracks(id, exclude))),
      Promise.all(
        req.genres
          .slice(0, 2)
          .map((g) =>
            this.attempt(() => this.library.randomSongs(20, g), []).then((s) =>
              s.map((l) => l.track).filter((t) => !exclude.has(t.id)),
            ),
          ),
      ),
    ]);

    // Mostly similar artists and the genre, with the queue's own artists every fourth song.
    const pool = spreadArtists(
      shuffle(
        uniqueById([...interleave(otherTracks), ...genreTracks.flat()]).filter(allowed),
        this.random,
      ),
    );
    const own = shuffle(seedTracks.flat(), this.random);
    const mixed: Track[] = [];
    let o = 0;
    let s = 0;
    while (mixed.length < RADIO_SIZE && (o < pool.length || s < own.length)) {
      const ownTurn = mixed.length % 4 === 3;
      if (ownTurn && s < own.length) mixed.push(own[s++] as Track);
      else if (o < pool.length) mixed.push(pool[o++] as Track);
      else mixed.push(own[s++] as Track);
    }
    let result = uniqueById(mixed);
    if (result.length < RADIO_SIZE) {
      // A small or unconnected library: fill up with anything.
      const random = await this.attempt(() => this.library.randomSongs(50), []);
      const taken = new Set(result.map((t) => t.id));
      result = [
        ...result,
        ...random
          .map((l) => l.track)
          .filter((t) => !exclude.has(t.id) && !taken.has(t.id) && allowed(t)),
      ].slice(0, RADIO_SIZE);
    }
    return result;
  }

  /** A few songs from a couple of the artist's albums. */
  private async artistTracks(artistId: string, exclude: Set<string>): Promise<Track[]> {
    const albums = shuffle(
      await this.attempt(() => this.library.artistAlbums(artistId), []),
      this.random,
    ).slice(0, 2);
    const tracks = await Promise.all(
      albums.map((l) => this.attempt(() => this.library.albumTracks(l.album.id), [])),
    );
    return shuffle(
      tracks.flat().filter((t) => !exclude.has(t.id)),
      this.random,
    ).slice(0, TRACKS_PER_ARTIST);
  }

  /** A failing lookup just narrows the choice. */
  private async attempt<T>(load: () => Promise<T>, fallback: T): Promise<T> {
    try {
      return await load();
    } catch {
      return fallback;
    }
  }
}

/** Oldest first; undated releases last, then by name. */
function byRelease(a: Album, b: Album): number {
  return (
    (a.year ?? Number.MAX_SAFE_INTEGER) - (b.year ?? Number.MAX_SAFE_INTEGER) ||
    a.name.localeCompare(b.name)
  );
}

function uniqueStrings(items: string[]): string[] {
  return [...new Set(items)];
}
