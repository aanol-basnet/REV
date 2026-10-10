import type {
  Album,
  AlbumDetail,
  AlbumListQuery,
  AlbumPage,
  Artist,
  ArtistDetail,
  Playlist,
  PlaylistDetail,
  FavoriteKind,
  Favorites,
  Genre,
  Track,
  TrackPage,
} from '@rev/schemas';
import type { AudioStream, CoverArt, StreamOptions, SubsonicClient } from './client.js';
import { toAlbum, toArtist, toPlaylist, toTrack } from './mappers.js';
import {
  EmptyResponse,
  GetAlbumList2Response,
  GetAlbumResponse,
  GetArtistResponse,
  GetArtistInfo2Response,
  GetArtistsResponse,
  GetRandomSongsResponse,
  GetTopSongsResponse,
  GetGenresResponse,
  GetLyricsBySongIdResponse,
  CreatePlaylistResponse,
  GetPlaylistResponse,
  GetPlaylistsResponse,
  GetSongResponse,
  GetStarred2Response,
  ScanStatusResponse,
  Search3Response,
} from './schemas.js';
import type { SubsonicAlbum, SubsonicSong } from './schemas.js';
import { UpstreamError } from '../errors.js';

const ALBUM_LIST_TYPES: Record<AlbumListQuery['type'], string> = {
  alphabetical: 'alphabeticalByName',
  newest: 'newest',
  recent: 'recent',
  frequent: 'frequent',
  random: 'random',
  starred: 'starred',
  byGenre: 'byGenre',
};

const STAR_PARAM: Record<FavoriteKind, string> = {
  artists: 'artistId',
  albums: 'albumId',
  tracks: 'id',
};

/** An album with the listening data recommendations use. */
export interface ListenedAlbum {
  album: Album;
  playCount: number;
  genres: string[];
}

/** A track with its play count. */
export interface ListenedTrack {
  track: Track;
  playCount: number;
}

/** The library as REV presents it, backed by Navidrome's Subsonic API. */
export class NavidromeLibrary {
  constructor(private readonly client: SubsonicClient) {}

  async artists(): Promise<Artist[]> {
    const res = await this.client.call('getArtists', {}, GetArtistsResponse);
    return res.artists.index.flatMap((i) => i.artist.map(toArtist));
  }

  async artist(id: string): Promise<ArtistDetail> {
    const { artist } = await this.client.call('getArtist', { id }, GetArtistResponse);
    return { ...toArtist(artist), albums: artist.album.map(toAlbum) };
  }

  async albums(query: AlbumListQuery): Promise<AlbumPage> {
    const res = await this.client.call(
      'getAlbumList2',
      {
        type: ALBUM_LIST_TYPES[query.type],
        genre: query.genre,
        size: query.size,
        offset: query.offset,
      },
      GetAlbumList2Response,
    );
    const albums = res.albumList2.album.map(toAlbum);
    return { albums, offset: query.offset, hasMore: albums.length === query.size };
  }

  /** An album list with play counts and every genre tag (for recommendations). */
  async listenedAlbums(
    type: Exclude<AlbumListQuery['type'], 'byGenre'> | { genre: string },
    size: number,
  ): Promise<ListenedAlbum[]> {
    const res = await this.client.call(
      'getAlbumList2',
      typeof type === 'string'
        ? { type: ALBUM_LIST_TYPES[type], size }
        : { type: 'byGenre', genre: type.genre, size },
      GetAlbumList2Response,
    );
    return res.albumList2.album.map(listened);
  }

  /** An artist's albums with play counts. */
  async artistAlbums(id: string): Promise<ListenedAlbum[]> {
    const { artist } = await this.client.call('getArtist', { id }, GetArtistResponse);
    return artist.album.map(listened);
  }

  /** Similar artists that are in the library (Navidrome's metadata agents, e.g. Last.fm). */
  async similarArtists(id: string, count: number): Promise<Artist[]> {
    const res = await this.client.call('getArtistInfo2', { id, count }, GetArtistInfo2Response);
    return res.artistInfo2.similarArtist.map(toArtist);
  }

  async randomSongs(size: number, genre?: string): Promise<ListenedTrack[]> {
    const res = await this.client.call('getRandomSongs', { size, genre }, GetRandomSongsResponse);
    return res.randomSongs.song.map(listenedTrack);
  }

  /** An album's tracks with play counts. */
  async albumSongs(id: string): Promise<ListenedTrack[]> {
    const { album } = await this.client.call('getAlbum', { id }, GetAlbumResponse);
    return album.song.map(listenedTrack);
  }

  /** An artist's best-known songs per Navidrome's metadata agents (e.g. Last.fm), in the library. */
  async topSongs(artistName: string, count: number): Promise<Track[]> {
    const res = await this.client.call(
      'getTopSongs',
      { artist: artistName, count },
      GetTopSongsResponse,
    );
    return res.topSongs.song.map(toTrack);
  }

  async albumTracks(id: string): Promise<Track[]> {
    const { album } = await this.client.call('getAlbum', { id }, GetAlbumResponse);
    return album.song.map(toTrack);
  }

  async album(id: string): Promise<AlbumDetail> {
    const { album } = await this.client.call('getAlbum', { id }, GetAlbumResponse);
    return { ...toAlbum(album), tracks: album.song.map(toTrack) };
  }

  async track(id: string): Promise<Track> {
    const { song } = await this.client.call('getSong', { id }, GetSongResponse);
    return toTrack(song);
  }

  /** A track with every genre tag (for listening statistics). */
  async trackWithGenres(id: string): Promise<{ track: Track; genres: string[] }> {
    const { song } = await this.client.call('getSong', { id }, GetSongResponse);
    const genres = song.genres.map((g) => g.name).filter(Boolean);
    return {
      track: toTrack(song),
      genres: genres.length > 0 ? genres : song.genre ? [song.genre] : [],
    };
  }

  /** All tracks, paged. Navidrome returns the whole library for an empty search3 query. */
  async tracks(offset: number, size: number): Promise<TrackPage> {
    const res = await this.client.call(
      'search3',
      { query: '', artistCount: 0, albumCount: 0, songCount: size, songOffset: offset },
      Search3Response,
    );
    const tracks = res.searchResult3.song.map(toTrack);
    return { tracks, offset, hasMore: tracks.length === size };
  }

  async search(
    query: string,
    limits: { artists: number; albums: number; tracks: number },
  ): Promise<{ artists: Artist[]; albums: Album[]; tracks: Track[] }> {
    const { searchResult3: r } = await this.client.call(
      'search3',
      { query, artistCount: limits.artists, albumCount: limits.albums, songCount: limits.tracks },
      Search3Response,
    );
    return {
      artists: r.artist.map(toArtist),
      albums: r.album.map(toAlbum),
      tracks: r.song.map(toTrack),
    };
  }

  async playlists(): Promise<Playlist[]> {
    const res = await this.client.call('getPlaylists', {}, GetPlaylistsResponse);
    return res.playlists.playlist.map((p) => toPlaylist(p, this.client.username));
  }

  async playlist(id: string): Promise<PlaylistDetail> {
    const { playlist } = await this.client.call('getPlaylist', { id }, GetPlaylistResponse);
    return { ...toPlaylist(playlist, this.client.username), tracks: playlist.entry.map(toTrack) };
  }

  /** Creates a playlist owned by REV's Navidrome account; returns its id. */
  async createPlaylist(name: string, trackIds: readonly string[]): Promise<string> {
    const res = await this.client.call(
      'createPlaylist',
      { name, songId: trackIds },
      CreatePlaylistResponse,
    );
    if (res.playlist) return res.playlist.id;
    // An older server doesn't say: the newest of ours with that name is it.
    const mine = (await this.playlists())
      .filter((p) => p.canEdit && p.name === name)
      .sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0));
    const created = mine[0];
    if (!created) throw new UpstreamError('navidrome', 'protocol', 'createPlaylist: not found');
    return created.id;
  }

  /**
   * Changes a playlist in one call: name and comment, songs to remove (by position in the
   * current list) and songs to append, in that order.
   */
  async updatePlaylist(
    id: string,
    change: {
      name?: string;
      comment?: string;
      removeIndexes?: readonly number[];
      addTrackIds?: readonly string[];
    },
  ): Promise<void> {
    await this.client.call(
      'updatePlaylist',
      {
        playlistId: id,
        name: change.name,
        comment: change.comment,
        songIndexToRemove: change.removeIndexes?.map(String),
        songIdToAdd: change.addTrackIds,
      },
      EmptyResponse,
    );
  }

  async deletePlaylist(id: string): Promise<void> {
    await this.client.call('deletePlaylist', { id }, EmptyResponse);
  }

  async genres(): Promise<Genre[]> {
    const res = await this.client.call('getGenres', {}, GetGenresResponse);
    return res.genres.genre
      .map((g) => ({ name: g.value, albumCount: g.albumCount ?? 0, trackCount: g.songCount ?? 0 }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }

  async favorites(): Promise<Favorites> {
    const { starred2 } = await this.client.call('getStarred2', {}, GetStarred2Response);
    return {
      artists: starred2.artist.map(toArtist),
      albums: starred2.album.map(toAlbum),
      tracks: starred2.song.map(toTrack),
    };
  }

  async setFavorite(kind: FavoriteKind, id: string, favorite: boolean): Promise<void> {
    await this.client.call(favorite ? 'star' : 'unstar', { [STAR_PARAM[kind]]: id }, EmptyResponse);
  }

  coverArt(id: string, size?: number): Promise<CoverArt> {
    return this.client.coverArt(id, size);
  }

  /**
   * Lyrics Navidrome has for a track (embedded tags or sidecar .lrc files), via the OpenSubsonic
   * songLyrics extension. Synced lyrics come back already timed.
   */
  async lyrics(
    id: string,
  ): Promise<{ synced: { startMs: number; text: string }[] | null; plain: string | null } | null> {
    const res = await this.client.call('getLyricsBySongId', { id }, GetLyricsBySongIdResponse);
    const all = res.lyricsList.structuredLyrics;
    const synced = all.find((l) => l.synced && l.line.length > 0);
    const any = synced ?? all.find((l) => l.line.length > 0);
    if (!any) return null;
    const plain =
      any.line
        .map((l) => l.value)
        .join('\n')
        .trim() || null;
    if (!synced) return { synced: null, plain };
    const offset = synced.offset ?? 0;
    return {
      synced: synced.line
        .map((l) => ({
          startMs: Math.max(0, Math.round((l.start ?? 0) - offset)),
          text: l.value.trim(),
        }))
        .sort((a, b) => a.startMs - b.startMs),
      plain,
    };
  }

  /** Health check; resolves when Navidrome answers and accepts REV's credentials. */
  async ping(): Promise<void> {
    await this.client.call('ping', {}, EmptyResponse);
  }

  /**
   * Asks Navidrome to scan for new files now instead of waiting for its schedule. Needs an admin
   * account; returns false (without failing) when REV's account isn't allowed to.
   */
  async startScan(): Promise<boolean> {
    try {
      await this.client.call('startScan', {}, EmptyResponse);
      return true;
    } catch (err) {
      if (err instanceof UpstreamError && err.kind === 'auth') return false;
      throw err;
    }
  }

  /** Library size and server version, for the system page. */
  async stats(): Promise<{
    artists: number;
    albums: number;
    tracks: number | null;
    version: string | null;
  }> {
    const [artists, scan] = await Promise.all([
      this.artists(),
      this.client.call('getScanStatus', {}, ScanStatusResponse).catch(() => null),
    ]);
    return {
      artists: artists.length,
      albums: artists.reduce((sum, a) => sum + a.albumCount, 0),
      tracks: scan?.scanStatus.count ?? null,
      version: scan?.serverVersion ?? null,
    };
  }

  stream(id: string, options: StreamOptions): Promise<AudioStream> {
    return this.client.stream(id, options);
  }

  /**
   * Reports playback so Navidrome's play counts and "recently played" stay accurate.
   * `submission: false` marks the track as now playing; `true` records a completed play.
   */
  async scrobble(id: string, submission: boolean, playedAt?: number): Promise<void> {
    await this.client.call('scrobble', { id, submission, time: playedAt }, EmptyResponse);
  }
}

function listened(a: SubsonicAlbum): ListenedAlbum {
  const genres = a.genres.map((g) => g.name).filter(Boolean);
  return {
    album: toAlbum(a),
    playCount: a.playCount ?? 0,
    genres: genres.length > 0 ? genres : a.genre ? [a.genre] : [],
  };
}

function listenedTrack(s: SubsonicSong): ListenedTrack {
  return { track: toTrack(s), playCount: s.playCount ?? 0 };
}
