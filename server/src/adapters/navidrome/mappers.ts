import type { Album, Artist, Playlist, Track } from '@rev/schemas';
import type { SubsonicAlbum, SubsonicArtist, SubsonicPlaylist, SubsonicSong } from './schemas.js';

const SECOND = 1000;

function timestamp(value: string | undefined): number | null {
  if (!value) return null;
  const ms = Date.parse(value);
  return Number.isNaN(ms) ? null : ms;
}

/** Subsonic uses 0 for "unknown" in several numeric fields. */
function positive(value: number | undefined): number | null {
  return value !== undefined && value > 0 ? Math.round(value) : null;
}

export function toArtist(a: SubsonicArtist): Artist {
  return {
    id: a.id,
    name: a.name,
    albumCount: a.albumCount ?? 0,
    coverArtId: a.coverArt ?? null,
    starred: a.starred !== undefined,
  };
}

export function toAlbum(a: SubsonicAlbum): Album {
  return {
    id: a.id,
    name: a.name ?? a.title ?? 'Unknown album',
    artist: a.artist ?? 'Unknown artist',
    artistId: a.artistId ?? null,
    year: positive(a.year),
    genre: a.genre ?? null,
    coverArtId: a.coverArt ?? null,
    trackCount: a.songCount ?? 0,
    durationMs: Math.round((a.duration ?? 0) * SECOND),
    starred: a.starred !== undefined,
    createdAt: timestamp(a.created),
    playedAt: timestamp(a.played),
    releaseTypes: releaseTypes(a),
  };
}

function releaseTypes(a: SubsonicAlbum): string[] {
  const types = a.releaseTypes.map((t) => t.trim().toLowerCase()).filter(Boolean);
  if (a.isCompilation && !types.includes('compilation')) types.push('compilation');
  return [...new Set(types)];
}

export function toTrack(s: SubsonicSong): Track {
  return {
    id: s.id,
    title: s.title,
    album: s.album ?? 'Unknown album',
    albumId: s.albumId ?? null,
    artist: s.artist ?? 'Unknown artist',
    artistId: s.artistId ?? null,
    trackNumber: positive(s.track),
    discNumber: positive(s.discNumber),
    year: positive(s.year),
    genre: s.genre ?? null,
    coverArtId: s.coverArt ?? null,
    durationMs: Math.round((s.duration ?? 0) * SECOND),
    starred: s.starred !== undefined,
    format: s.suffix ?? null,
    bitRateKbps: positive(s.bitRate),
    sampleRateHz: positive(s.samplingRate),
    bitDepth: positive(s.bitDepth),
    sizeBytes: positive(s.size),
    replayGain: toReplayGain(s.replayGain),
  };
}

function toReplayGain(rg: SubsonicSong['replayGain']): Track['replayGain'] {
  if (!rg) return null;
  const value = (v: number | undefined) => (v === undefined || !Number.isFinite(v) ? null : v);
  // Navidrome reports 0 peak for "unknown".
  const peak = (v: number | undefined) => (v === undefined || v <= 0 ? null : v);
  const result = {
    trackGainDb: value(rg.trackGain),
    albumGainDb: value(rg.albumGain),
    trackPeak: peak(rg.trackPeak),
    albumPeak: peak(rg.albumPeak),
  };
  return result.trackGainDb === null && result.albumGainDb === null ? null : result;
}

/** [me]: the Navidrome account REV uses; only its own playlists can be changed. */
export function toPlaylist(p: SubsonicPlaylist, me: string): Playlist {
  return {
    id: p.id,
    name: p.name,
    comment: p.comment || null,
    owner: p.owner ?? null,
    trackCount: p.songCount ?? 0,
    durationMs: Math.round((p.duration ?? 0) * SECOND),
    coverArtId: p.coverArt ?? null,
    updatedAt: timestamp(p.changed),
    canEdit: p.owner === undefined || p.owner.toLowerCase() === me.toLowerCase(),
  };
}
