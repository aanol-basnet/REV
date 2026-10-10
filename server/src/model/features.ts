/**
 * The skip model's features: the same on the server (training) and the phone (using and further
 * training the model), so the names must match exactly. Both are checked against
 * shared/model/feature-fixtures.json.
 */
export interface FeatureInput {
  trackId: string;
  artistId: string | null;
  albumId: string | null;
  genres: string[];
  year: number | null;
  localHour: number;
  /** 0 = Monday. */
  weekday: number;
  source: string | null;
  queueShape: string | null;
  shuffle: boolean | null;
  output: string | null;
  network: string | null;
  charging: boolean | null;
  favorite: boolean | null;
  /** play, skip or none for the song before; null at the start of a session. */
  previousOutcome: string | null;
  sessionSkips: number | null;
  sessionPosition: number | null;
  trackPlays: number;
  trackSkips: number;
}

const MAX_GENRES = 3;

export function features(input: FeatureInput): string[] {
  const out: string[] = [];
  const genres = input.genres
    .map((g) => g.trim().toLowerCase())
    .filter((g) => g.length > 0)
    .slice(0, MAX_GENRES);
  const daypart = Math.floor(input.localHour / 4);
  const flag = (b: boolean) => (b ? '1' : '0');

  out.push(`t:${input.trackId}`);
  if (input.artistId) out.push(`a:${input.artistId}`);
  if (input.albumId) out.push(`al:${input.albumId}`);
  for (const g of genres) out.push(`g:${g}`);
  if (input.year !== null) out.push(`d:${Math.floor(input.year / 10) * 10}`);
  out.push(
    `h:${input.localHour}`,
    `p:${daypart}`,
    `w:${input.weekday}`,
    `we:${flag(input.weekday >= 5)}`,
  );
  if (input.source) out.push(`src:${input.source}`);
  if (input.queueShape) out.push(`qs:${input.queueShape}`);
  if (input.shuffle !== null) out.push(`sh:${flag(input.shuffle)}`);
  if (input.output) out.push(`o:${input.output}`);
  if (input.network) out.push(`n:${input.network}`);
  if (input.charging !== null) out.push(`c:${flag(input.charging)}`);
  if (input.favorite !== null) out.push(`f:${flag(input.favorite)}`);
  if (input.previousOutcome) out.push(`po:${input.previousOutcome}`);
  if (input.sessionSkips !== null) out.push(`ss:${countBucket(input.sessionSkips)}`);
  if (input.sessionPosition !== null) out.push(`sp:${positionBucket(input.sessionPosition)}`);
  out.push(`tp:${countBucket(input.trackPlays)}`, `tk:${countBucket(input.trackSkips)}`);
  // Taste depends on the situation: an artist in the car, a genre late at night.
  if (input.artistId) {
    if (input.output) out.push(`a:${input.artistId}|o:${input.output}`);
    out.push(`a:${input.artistId}|p:${daypart}`);
  }
  for (const g of genres) {
    out.push(`g:${g}|p:${daypart}`);
    if (input.output) out.push(`g:${g}|o:${input.output}`);
    if (input.previousOutcome) out.push(`g:${g}|po:${input.previousOutcome}`);
  }
  return out;
}

/** 0, 1, 2–3, 4–7, 8+. */
export function countBucket(n: number): string {
  if (n <= 0) return '0';
  if (n === 1) return '1';
  if (n <= 3) return '2-3';
  if (n <= 7) return '4-7';
  return '8+';
}

/** 0, 1–2, 3–5, 6–10, 11+. */
export function positionBucket(n: number): string {
  if (n <= 0) return '0';
  if (n <= 2) return '1-2';
  if (n <= 5) return '3-5';
  if (n <= 10) return '6-10';
  return '11+';
}
