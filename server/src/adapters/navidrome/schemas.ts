import { z } from 'zod';

// Subsonic/OpenSubsonic response shapes, as returned by Navidrome with f=json. Only the fields REV
// uses are declared; unknown fields are ignored. Navidrome omits empty arrays and unset values.
// Reference: https://opensubsonic.netlify.app/docs/responses/

const list = <T extends z.ZodType>(item: T) => z.array(item).optional().default([]);

export const SubsonicArtist = z.object({
  id: z.string(),
  name: z.string(),
  albumCount: z.number().optional(),
  coverArt: z.string().optional(),
  starred: z.string().optional(),
});
export type SubsonicArtist = z.infer<typeof SubsonicArtist>;

export const SubsonicAlbum = z.object({
  id: z.string(),
  name: z.string().optional(),
  title: z.string().optional(),
  artist: z.string().optional(),
  artistId: z.string().optional(),
  year: z.number().optional(),
  genre: z.string().optional(),
  coverArt: z.string().optional(),
  songCount: z.number().optional(),
  duration: z.number().optional(),
  starred: z.string().optional(),
  created: z.string().optional(),
  played: z.string().optional(),
  playCount: z.number().optional(),
  /** OpenSubsonic: MusicBrainz release types, e.g. ["album"], ["ep"], ["album", "live"]. */
  releaseTypes: list(z.string()),
  isCompilation: z.boolean().optional(),
  /** OpenSubsonic: every genre tag, not just the first. */
  genres: list(z.object({ name: z.string() })),
});
export type SubsonicAlbum = z.infer<typeof SubsonicAlbum>;

export const SubsonicSong = z.object({
  id: z.string(),
  title: z.string(),
  album: z.string().optional(),
  albumId: z.string().optional(),
  artist: z.string().optional(),
  artistId: z.string().optional(),
  track: z.number().optional(),
  discNumber: z.number().optional(),
  year: z.number().optional(),
  genre: z.string().optional(),
  coverArt: z.string().optional(),
  duration: z.number().optional(),
  starred: z.string().optional(),
  suffix: z.string().optional(),
  bitRate: z.number().optional(),
  samplingRate: z.number().optional(),
  bitDepth: z.number().optional(),
  size: z.number().optional(),
  playCount: z.number().optional(),
  /** OpenSubsonic: every genre tag, not just the first. */
  genres: list(z.object({ name: z.string() })),
  replayGain: z
    .object({
      trackGain: z.number().optional(),
      albumGain: z.number().optional(),
      trackPeak: z.number().optional(),
      albumPeak: z.number().optional(),
    })
    .optional(),
});
export type SubsonicSong = z.infer<typeof SubsonicSong>;

export const GetArtistsResponse = z.object({
  artists: z.object({
    index: list(z.object({ name: z.string(), artist: list(SubsonicArtist) })),
  }),
});

export const GetArtistResponse = z.object({
  artist: SubsonicArtist.extend({ album: list(SubsonicAlbum) }),
});

export const GetAlbumResponse = z.object({
  album: SubsonicAlbum.extend({ song: list(SubsonicSong) }),
});

export const GetSongResponse = z.object({ song: SubsonicSong });

export const GetAlbumList2Response = z.object({
  albumList2: z.object({ album: list(SubsonicAlbum) }),
});

export const GetStarred2Response = z.object({
  starred2: z.object({
    artist: list(SubsonicArtist),
    album: list(SubsonicAlbum),
    song: list(SubsonicSong),
  }),
});

export const GetGenresResponse = z.object({
  genres: z.object({
    genre: list(
      z.object({
        value: z.string(),
        songCount: z.number().optional(),
        albumCount: z.number().optional(),
      }),
    ),
  }),
});

export const Search3Response = z.object({
  searchResult3: z.object({
    artist: list(SubsonicArtist),
    album: list(SubsonicAlbum),
    song: list(SubsonicSong),
  }),
});

export const GetArtistInfo2Response = z.object({
  artistInfo2: z.object({ similarArtist: list(SubsonicArtist) }),
});

export const GetTopSongsResponse = z.object({
  topSongs: z.object({ song: list(SubsonicSong) }),
});

export const GetRandomSongsResponse = z.object({
  randomSongs: z.object({ song: list(SubsonicSong) }),
});

export const EmptyResponse = z.object({});

export const ScanStatusResponse = z.object({
  serverVersion: z.string().optional(),
  scanStatus: z.object({ scanning: z.boolean().optional(), count: z.number().optional() }),
});

export const SubsonicEnvelope = z.object({
  'subsonic-response': z
    .object({
      status: z.enum(['ok', 'failed']),
      error: z.object({ code: z.number(), message: z.string().optional() }).optional(),
    })
    .passthrough(),
});

export const SubsonicPlaylist = z.object({
  id: z.string(),
  name: z.string(),
  comment: z.string().optional(),
  owner: z.string().optional(),
  songCount: z.number().optional(),
  duration: z.number().optional(),
  coverArt: z.string().optional(),
  changed: z.string().optional(),
});
export type SubsonicPlaylist = z.infer<typeof SubsonicPlaylist>;

export const GetPlaylistsResponse = z.object({
  playlists: z.object({ playlist: list(SubsonicPlaylist) }),
});

export const GetPlaylistResponse = z.object({
  playlist: SubsonicPlaylist.extend({ entry: list(SubsonicSong) }),
});

/** Navidrome returns the new playlist (Subsonic 1.14+); older servers return nothing. */
export const CreatePlaylistResponse = z.object({
  playlist: SubsonicPlaylist.optional(),
});

/** OpenSubsonic songLyrics extension: getLyricsBySongId. */
export const GetLyricsBySongIdResponse = z.object({
  lyricsList: z.object({
    structuredLyrics: list(
      z.object({
        synced: z.boolean().default(false),
        offset: z.number().optional(),
        lang: z.string().optional(),
        line: list(z.object({ start: z.number().optional(), value: z.string().default('') })),
      }),
    ),
  }),
});
