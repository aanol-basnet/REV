import type { ListenContext } from '@rev/schemas';
import { OUTCOME_SIGNAL, outcomeOf } from './outcomes.js';
import type { Zone } from './zone.js';

/** A row of the listening log as stored, context still JSON. */
export interface LogRow {
  id: number;
  trackId: string;
  kind: string;
  startedAt: number;
  listenedMs: number;
  title: string;
  artist: string;
  artistId: string | null;
  album: string;
  albumId: string | null;
  year: number | null;
  genres: string[];
  durationMs: number;
  context: string | null;
}

/** One listen as a training example: the song, when, the situation, and what you did. */
export type DatasetRecord = Record<string, string | number | boolean | null>;

/** Listens further apart than this start a new session (the same rule as the reports). */
const SESSION_GAP_MS = 30 * 60_000;
const EARLY_SKIP_MS = 30_000;

/**
 * Turns one user's listening log into training examples, oldest first. Besides what the app
 * recorded (the context), each row gets features worked out from the log itself, so listens from
 * before the app sent context are still useful: local time, the session, the song before, and how
 * often you'd played or skipped this song and artist before.
 */
export function buildDataset(
  rows: LogRow[],
  zone: Zone,
  /** Feedback per listen, keyed by [listenKey]. */
  feedback: Map<string, string> = new Map(),
): DatasetRecord[] {
  const sorted = [...rows].sort((a, b) => a.startedAt - b.startedAt);
  const trackPlays = new Map<string, number>();
  const trackSkips = new Map<string, number>();
  const artistPlays = new Map<string, number>();
  const artistSkips = new Map<string, number>();
  const out: DatasetRecord[] = [];
  let session = 0;
  let sessionPosition = 0;
  let sessionSkips = 0;
  let previous: LogRow | null = null;

  for (const row of sorted) {
    const gap = previous ? row.startedAt - previous.startedAt : Infinity;
    if (gap > SESSION_GAP_MS) {
      session += 1;
      sessionPosition = 0;
      sessionSkips = 0;
    }
    const context = parseContext(row.context);
    // The phone's own offset when it sent one, else the zone asked for.
    const offset = context?.utcOffsetMinutes;
    const local = (ms: number) =>
      offset === undefined
        ? { hour: zone.hour(ms), weekday: zone.weekday(ms), date: zone.date(ms) }
        : localAt(ms + offset * 60_000);
    const when = local(row.startedAt);
    const skipped = row.kind === 'skip';
    const outcome = outcomeOf({ ...row, context });
    const signal = OUTCOME_SIGNAL[outcome];
    const artistKey = row.artistId ?? row.artist.toLowerCase();

    out.push({
      id: row.id,
      started_at: new Date(row.startedAt).toISOString(),
      started_at_ms: row.startedAt,
      local_date: `${when.date.year}-${pad(when.date.month)}-${pad(when.date.day)}`,
      local_hour: when.hour,
      weekday: when.weekday,
      track_id: row.trackId,
      title: row.title,
      artist: row.artist,
      artist_id: row.artistId,
      album: row.album,
      album_id: row.albumId,
      year: row.year,
      genres: row.genres.join('|'),
      duration_ms: row.durationMs,
      listened_ms: row.listenedMs,
      listened_fraction:
        row.durationMs > 0 ? round(Math.min(1, row.listenedMs / row.durationMs)) : null,
      kind: row.kind,
      skipped,
      early_skip: skipped && row.listenedMs < EARLY_SKIP_MS,
      outcome,
      label: signal.label,
      label_weight: signal.weight,
      session_id: session,
      session_position: sessionPosition,
      session_skips_before: sessionSkips,
      previous_track_id: gap > SESSION_GAP_MS ? null : (previous?.trackId ?? null),
      previous_kind: gap > SESSION_GAP_MS ? null : (previous?.kind ?? null),
      minutes_since_previous: Number.isFinite(gap) ? round(gap / 60_000) : null,
      track_plays_before: trackPlays.get(row.trackId) ?? 0,
      track_skips_before: trackSkips.get(row.trackId) ?? 0,
      artist_plays_before: artistPlays.get(artistKey) ?? 0,
      artist_skips_before: artistSkips.get(artistKey) ?? 0,
      has_context: context !== null,
      source: context?.source ?? null,
      smart_change: context?.smartChange ?? null,
      queue_shape: context?.queueShape ?? null,
      shuffle: context?.shuffle ?? null,
      repeat: context?.repeat ?? null,
      queue_position: context?.queuePosition ?? null,
      queue_length: context?.queueLength ?? null,
      end_reason: context?.endReason ?? null,
      output: context?.output ?? null,
      favorite: context?.favorite ?? null,
      mode: context?.mode ?? null,
      mode_name: context?.modeName ?? null,
      mode_source: context?.modeSource ?? null,
      utc_offset_minutes: offset ?? null,
      output_name: context?.outputName ?? null,
      network: context?.network ?? null,
      charging: context?.charging ?? null,
      screen_on: context?.screenOn ?? null,
      volume: context?.volume ?? null,
      start_position_ms: context?.startPositionMs ?? null,
      end_position_ms: context?.endPositionMs ?? null,
      seeks_forward: context?.seeksForward ?? null,
      seeks_back: context?.seeksBack ?? null,
      seeked_forward_ms: context?.seekedForwardMs ?? null,
      seeked_back_ms: context?.seekedBackMs ?? null,
      pauses: context?.pauses ?? null,
      paused_ms: context?.pausedMs ?? null,
      volume_end: context?.volumeEnd ?? null,
      favorited_during: context?.favoritedDuring ?? null,
      predicted_score: context?.predictedScore ?? null,
      predicted_skip: context?.predictedSkip ?? null,
      previous_outcome: context?.previousOutcome ?? null,
      session_skips: context?.sessionSkips ?? null,
      arrived_by: context?.arrivedBy ?? null,
      paused_at_end: context?.pausedAtEnd ?? null,
      last_pause_ms: context?.lastPauseMs ?? null,
      heard_since_resume_ms: context?.heardSinceResumeMs ?? null,
      system_pause: context?.systemPause ?? null,
      added_to_playlist: context?.addedToPlaylist ?? null,
      curating: context?.curating ?? null,
      feedback: feedback.get(listenKey(row.trackId, row.startedAt)) ?? null,
    });

    // Counted the way the app counts them: interruptions and browsing are neither.
    if (signal.label !== null) {
      const tracks = signal.label === 1 ? trackSkips : trackPlays;
      const artists = signal.label === 1 ? artistSkips : artistPlays;
      tracks.set(row.trackId, (tracks.get(row.trackId) ?? 0) + 1);
      artists.set(artistKey, (artists.get(artistKey) ?? 0) + 1);
    }
    sessionPosition += 1;
    if (signal.label === 1) sessionSkips += 1;
    previous = row;
  }
  return out;
}

/** Identifies one listen: a song and when it started. */
export function listenKey(trackId: string, startedAt: number): string {
  return `${trackId}@${startedAt}`;
}

/** Records as CSV with a header; text is quoted when it needs to be. */
export function toCsv(records: DatasetRecord[]): string {
  const first = records[0];
  if (!first) return '';
  const columns = Object.keys(first);
  const cell = (v: string | number | boolean | null) => {
    if (v === null) return '';
    const s = typeof v === 'boolean' ? (v ? '1' : '0') : String(v);
    return /[",\n\r]/u.test(s) ? `"${s.replaceAll('"', '""')}"` : s;
  };
  return (
    [
      columns.join(','),
      ...records.map((r) => columns.map((c) => cell(r[c] ?? null)).join(',')),
    ].join('\n') + '\n'
  );
}

function parseContext(json: string | null): ListenContext | null {
  if (!json) return null;
  try {
    const value: unknown = JSON.parse(json);
    return typeof value === 'object' && value !== null ? (value as ListenContext) : null;
  } catch {
    return null;
  }
}

function localAt(shifted: number) {
  const d = new Date(shifted);
  return {
    hour: d.getUTCHours(),
    weekday: (d.getUTCDay() + 6) % 7,
    date: { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() },
  };
}

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

function round(n: number): number {
  return Math.round(n * 1000) / 1000;
}
