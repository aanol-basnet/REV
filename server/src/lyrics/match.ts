import { artistKey, basicNormalize, stripFeaturing, titleKey } from '../library/normalize.js';

export interface TrackSignature {
  title: string;
  artist: string;
  album: string | null;
  /** Seconds; null when unknown. */
  durationSec: number | null;
}

export interface LyricsCandidate {
  title: string;
  artist: string;
  album: string | null;
  durationSec: number | null;
}

/** Durations further apart than this mean a different recording (or edit) of the song. */
const MAX_DURATION_DIFF_SEC = 10;

/**
 * Scores how well a lyrics candidate fits a track (higher is better), or returns null when it is
 * clearly a different song. Title and artist must match after normalization (edition suffixes and
 * "feat." credits ignored); album and duration refine the score. Never guesses: a near miss on the
 * title or a long duration mismatch is rejected rather than shown with wrong lyrics.
 */
export function scoreCandidate(track: TrackSignature, candidate: LyricsCandidate): number | null {
  if (titleKey(candidate.title) !== titleKey(track.title)) return null;
  if (!artistMatches(track.artist, candidate.artist)) return null;

  let score = 100;
  if (track.durationSec !== null && candidate.durationSec !== null) {
    const diff = Math.abs(track.durationSec - candidate.durationSec);
    if (diff > MAX_DURATION_DIFF_SEC) return null;
    score += diff <= 2 ? 40 : diff <= 5 ? 20 : 0;
  }
  if (track.album && candidate.album && titleKey(track.album) === titleKey(candidate.album))
    score += 15;
  return score;
}

/**
 * Artist credits vary: "Tool" vs "TOOL;Tool", "A & B" vs "A, B", "A feat. B" vs "A". A match on the
 * full credit or on its first credited artist counts.
 */
function artistMatches(expected: string, actual: string): boolean {
  const wanted = artistParts(expected);
  const got = artistParts(actual);
  return got.some((g) => wanted.includes(g));
}

function artistParts(credit: string): string[] {
  const base = stripFeaturing(credit);
  const parts = base
    .split(/\s*(?:;|,|&|\/|\bx\b|\band\b|\bwith\b)\s*/i)
    .map(artistKey)
    .filter(Boolean);
  return [...new Set([artistKey(base), basicNormalize(base), ...parts])];
}
