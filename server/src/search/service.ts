import type { Album, Artist, SearchAlbum, SearchArtist, SearchResponse } from '@rev/schemas';
import type { NavidromeLibrary } from '../adapters/navidrome/library.js';
import { artistKey } from '../library/normalize.js';

const LIBRARY_LIMITS = { artists: 20, albums: 40, tracks: 50 };
/** Library artists whose full discography is added when the query names them. */
const DISCOGRAPHY_ARTISTS = 3;

/**
 * Search over the playable library (Navidrome). When the query names an artist, the rest of
 * their albums are added after Navidrome's own hits, so "tool" finds every Tool album and not
 * only those whose title matches.
 *
 * The wider catalog (Lidarr/MusicBrainz) is not searched: results are always things you can
 * play. `sources.catalog` is kept in the response for older clients and is always
 * `not_configured`.
 */
export class SearchService {
  constructor(
    private readonly library: NavidromeLibrary | null,
    private readonly onSourceError: (source: 'library', err: unknown) => void = () => {},
  ) {}

  async search(query: string): Promise<SearchResponse> {
    if (!this.library) return empty(query, 'not_configured');
    let hits: { artists: Artist[]; albums: Album[]; tracks: SearchResponse['tracks'] };
    try {
      hits = await this.library.search(query, LIBRARY_LIMITS);
    } catch (err) {
      this.onSourceError('library', err);
      return empty(query, 'unavailable');
    }
    const discography = await this.discographyOf(query, hits.artists);
    const albums = dedupeById([...hits.albums, ...discography]).map(toSearchAlbum);
    return {
      query,
      artists: hits.artists.map(toSearchArtist),
      albums,
      tracks: hits.tracks,
      sources: { library: 'ok', catalog: 'not_configured' },
    };
  }

  private async discographyOf(query: string, artists: Artist[]): Promise<Album[]> {
    if (!this.library) return [];
    const wanted = artistKey(query);
    const relevant = artists
      .filter((a) => artistKey(a.name) === wanted)
      .slice(0, DISCOGRAPHY_ARTISTS);
    const library = this.library;
    const results = await Promise.allSettled(relevant.map((a) => library.artist(a.id)));
    return results.flatMap((r) => (r.status === 'fulfilled' ? r.value.albums : []));
  }
}

function empty(query: string, library: 'not_configured' | 'unavailable'): SearchResponse {
  return {
    query,
    artists: [],
    albums: [],
    tracks: [],
    sources: { library, catalog: 'not_configured' },
  };
}

function toSearchAlbum(album: Album): SearchAlbum {
  return {
    key: `lib:${album.id}`,
    title: album.name,
    artist: album.artist,
    year: album.year,
    albumType: null,
    availability: 'AVAILABLE',
    libraryId: album.id,
    coverArtId: album.coverArtId,
    catalogId: null,
    trackCount: album.trackCount,
  };
}

function toSearchArtist(artist: Artist): SearchArtist {
  return {
    name: artist.name,
    disambiguation: null,
    libraryId: artist.id,
    coverArtId: artist.coverArtId,
    catalogId: null,
  };
}

function dedupeById<T extends { id: string }>(items: T[]): T[] {
  const seen = new Set<string>();
  return items.filter((i) => (seen.has(i.id) ? false : (seen.add(i.id), true)));
}
