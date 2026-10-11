import type { ListenContext } from '@rev/schemas';

/**
 * How a listen went, beyond "play" or "skip". The app works this out as each song ends and sends
 * it with the listen; listens from before that are classified here from what was recorded. The
 * app's copy (ListenOutcomes.kt) must agree: both are checked against
 * shared/model/outcome-fixtures.json.
 */
export type ListenOutcome =
  /** Added to a playlist while it played. */
  | 'saved'
  /** Went back to it (previous) and listened. */
  | 'replayed'
  /** Played to the end. */
  | 'completed'
  /** Counted as a play, then you moved on with some of it left: skipping a long outro. */
  | 'outro'
  /** Counted as a play; how it ended isn't known (yet). */
  | 'played'
  /** Left partway, after the first half-minute: tired of it. */
  | 'bored'
  /** Left within the first half-minute: not what you wanted. */
  | 'rejected'
  /** Left while paused, or just after a long pause or one the phone made (a call, unplugged headphones): not about the song. */
  | 'interrupted'
  /** Left quickly while putting a playlist together: looking for songs, not judging them. */
  | 'browsing'
  /** Went back to the song before: not a verdict on this one. */
  | 'went_back';

export const LISTEN_OUTCOMES: readonly ListenOutcome[] = [
  'saved',
  'replayed',
  'completed',
  'outro',
  'played',
  'bored',
  'rejected',
  'interrupted',
  'browsing',
  'went_back',
];

/** What learning makes of an outcome: skipped (1) or not (0), and how much it counts; null label: nothing to learn. */
export const OUTCOME_SIGNAL: Record<ListenOutcome, { label: 0 | 1 | null; weight: number }> = {
  saved: { label: 0, weight: 1.5 },
  replayed: { label: 0, weight: 1.5 },
  completed: { label: 0, weight: 1 },
  outro: { label: 0, weight: 1 },
  played: { label: 0, weight: 1 },
  bored: { label: 1, weight: 0.5 },
  rejected: { label: 1, weight: 1 },
  interrupted: { label: null, weight: 0 },
  browsing: { label: null, weight: 0 },
  went_back: { label: null, weight: 0 },
};

/** What's known about a listen when it ends. */
export interface ListenFacts {
  /** It was heard long enough to count as a play. */
  counted: boolean;
  /** 0 when unknown. */
  durationMs: number;
  /** Actually heard (not counting pauses or seeking). */
  heardMs: number;
  endPositionMs: number | null;
  /** next, previous, jump, new_queue, stopped or ended (played to the end); null while it's playing. */
  endReason: string | null;
  /** Paused at the moment you left it. */
  pausedAtEnd: boolean;
  /** The most recent pause's length (up to leaving, if still paused); 0 for none. */
  lastPauseMs: number;
  /** Heard after that pause ended; null with no pause or when still paused. */
  heardSinceResumeMs: number | null;
  /** That pause was the phone's doing: a call, unplugged headphones, another app's sound. */
  systemPause: boolean;
  /** How the song began: previous, next, jump, auto or new_queue. */
  arrivedBy: string | null;
  addedToPlaylist: boolean;
  /** Songs were being added to a playlist lately. */
  curating: boolean;
}

/** A skip within this long (or half the song, if shorter) is a rejection. */
const QUICK_MS = 30_000;
/** Leaving a counted play with more than this left is skipping the outro, not finishing it. */
const OUTRO_LEFT_MS = 15_000;
/** Leaving while paused this long was an interruption. */
const PAUSED_LEAVE_MS = 20_000;
/** A pause this long, then leaving soon after resuming, was an interruption. */
const LONG_PAUSE_MS = 60_000;
const RESUME_GRACE_MS = 15_000;

export function classifyListen(f: ListenFacts): ListenOutcome {
  if (f.addedToPlaylist) return 'saved';
  if (f.counted) {
    if (f.arrivedBy === 'previous') return 'replayed';
    if (f.endReason === 'ended') return 'completed';
    if (
      f.endReason !== null &&
      f.endReason !== 'stopped' &&
      f.endPositionMs !== null &&
      f.durationMs > 0
    ) {
      return f.durationMs - f.endPositionMs > OUTRO_LEFT_MS ? 'outro' : 'completed';
    }
    return 'played';
  }
  const resumedJustNow = f.heardSinceResumeMs !== null && f.heardSinceResumeMs < RESUME_GRACE_MS;
  const interrupted =
    (f.pausedAtEnd && f.lastPauseMs >= PAUSED_LEAVE_MS) ||
    (f.lastPauseMs >= LONG_PAUSE_MS && resumedJustNow) ||
    (f.systemPause && (f.pausedAtEnd || resumedJustNow)) ||
    (f.endReason === 'stopped' && f.pausedAtEnd);
  if (interrupted) return 'interrupted';
  if (f.endReason === 'previous') return 'went_back';
  const quick = f.heardMs < (f.durationMs > 0 ? Math.min(QUICK_MS, f.durationMs / 2) : QUICK_MS);
  if (quick) return f.curating ? 'browsing' : 'rejected';
  return 'bored';
}

/**
 * A logged listen's outcome: the one the app sent, else worked out from what was recorded (older
 * listens: a skip in the first half-minute is a rejection, a later one boredom, a play a play).
 */
export function outcomeOf(row: {
  kind: string;
  listenedMs: number;
  durationMs: number;
  context: ListenContext | null;
}): ListenOutcome {
  const c = row.context;
  const sent = c?.outcome;
  if (typeof sent === 'string' && (LISTEN_OUTCOMES as readonly string[]).includes(sent)) {
    return sent as ListenOutcome;
  }
  return classifyListen({
    counted: row.kind === 'play',
    durationMs: row.durationMs,
    heardMs: row.listenedMs,
    endPositionMs: c?.endPositionMs ?? null,
    endReason: c?.endReason ?? null,
    pausedAtEnd: c?.pausedAtEnd ?? false,
    lastPauseMs: c?.lastPauseMs ?? 0,
    heardSinceResumeMs: c?.heardSinceResumeMs ?? null,
    systemPause: c?.systemPause ?? false,
    arrivedBy: c?.arrivedBy ?? null,
    addedToPlaylist: c?.addedToPlaylist ?? false,
    curating: c?.curating ?? false,
  });
}
