import { z } from 'zod';
import { LibraryId, Track } from './library.js';

// Listening statistics: REV keeps its own dated log of what each user plays (Navidrome only keeps
// a play count and the last play per track) and builds reports from it.

/** `play`: listened to long enough to count. `skip`: moved on to another track before that. */
export const ListenKind = z.enum(['play', 'skip']);
export type ListenKind = z.infer<typeof ListenKind>;

export const Listen = z.object({
  trackId: LibraryId,
  /** When the track started playing, epoch ms. Also what makes a listen unique (resends are ignored). */
  startedAt: z.number().int().positive(),
  kind: ListenKind,
  /** How long it was actually heard. Known for skips; plays count the track's length when absent. */
  listenedMs: z.number().int().nonnegative().max(86_400_000).optional(),
});
export type Listen = z.infer<typeof Listen>;

/** Listens reported together, e.g. those queued on the phone while the server was unreachable. */
export const ListenBatch = z.object({ listens: z.array(Listen).min(1).max(500) });
export type ListenBatch = z.infer<typeof ListenBatch>;

export const ListenBatchResult = z.object({
  /** Newly recorded. */
  recorded: z.number().int().nonnegative(),
  /** Already recorded earlier (a resend). */
  duplicates: z.number().int().nonnegative(),
  /** Dropped because the track no longer exists in the library. */
  unknown: z.number().int().nonnegative(),
});
export type ListenBatchResult = z.infer<typeof ListenBatchResult>;

export const ReportPeriodKind = z.enum(['week', 'month', 'year', 'all']);
export type ReportPeriodKind = z.infer<typeof ReportPeriodKind>;

export const ReportQuery = z.object({
  period: ReportPeriodKind.default('week'),
  /** 0 is the current period, 1 the one before, and so on. */
  offset: z.coerce.number().int().min(0).max(1000).default(0),
  /** IANA time zone the periods, days and hours are counted in. */
  tz: z
    .string()
    .max(64)
    .refine((tz) => {
      try {
        new Intl.DateTimeFormat('en', { timeZone: tz });
        return true;
      } catch {
        return false;
      }
    }, 'unknown time zone')
    .default('UTC'),
});
export type ReportQuery = z.infer<typeof ReportQuery>;

export const ReportPeriod = z.object({
  kind: ReportPeriodKind,
  offset: z.number().int(),
  timeZone: z.string(),
  /** Start (inclusive) and end (exclusive), epoch ms. The current period ends in the future. */
  from: z.number().int(),
  to: z.number().int(),
  /** Days of the period that have started (the whole period once it's over). */
  elapsedDays: z.number().int().nonnegative(),
  /** There are listens before this period, so an earlier report has something in it. */
  hasEarlier: z.boolean(),
});
export type ReportPeriod = z.infer<typeof ReportPeriod>;

export const ListeningTotals = z.object({
  plays: z.number().int().nonnegative(),
  skips: z.number().int().nonnegative(),
  /** Plays plus the heard part of skips. */
  listeningMs: z.number().int().nonnegative(),
  tracks: z.number().int().nonnegative(),
  artists: z.number().int().nonnegative(),
  albums: z.number().int().nonnegative(),
  genres: z.number().int().nonnegative(),
  /** Days with at least one play. */
  activeDays: z.number().int().nonnegative(),
  /** Listening time per elapsed day. */
  dailyAverageMs: z.number().int().nonnegative(),
  /** Skips out of everything started, 0–1. */
  skipRate: z.number().min(0).max(1),
});
export type ListeningTotals = z.infer<typeof ListeningTotals>;

const Ranked = {
  plays: z.number().int().nonnegative(),
  listeningMs: z.number().int().nonnegative(),
  /** Share of the period's plays, 0–1. */
  share: z.number().min(0).max(1),
  /** 1-based rank in the previous period; null when it wasn't played then. */
  previousRank: z.number().int().positive().nullable(),
};

export const RankedGenre = z.object({ name: z.string(), ...Ranked });
export type RankedGenre = z.infer<typeof RankedGenre>;

export const RankedArtist = z.object({
  id: z.string().nullable(),
  name: z.string(),
  coverArtId: z.string().nullable(),
  /** First ever listened to in this period. */
  isNew: z.boolean(),
  ...Ranked,
});
export type RankedArtist = z.infer<typeof RankedArtist>;

export const RankedAlbum = z.object({
  id: z.string().nullable(),
  name: z.string(),
  artist: z.string(),
  artistId: z.string().nullable(),
  coverArtId: z.string().nullable(),
  year: z.number().int().nullable(),
  ...Ranked,
});
export type RankedAlbum = z.infer<typeof RankedAlbum>;

export const StatsTrack = z.object({
  id: z.string(),
  title: z.string(),
  artist: z.string(),
  artistId: z.string().nullable(),
  album: z.string(),
  albumId: z.string().nullable(),
  coverArtId: z.string().nullable(),
  durationMs: z.number().int().nonnegative(),
});
export type StatsTrack = z.infer<typeof StatsTrack>;

export const RankedTrack = z.object({ track: StatsTrack, ...Ranked });
export type RankedTrack = z.infer<typeof RankedTrack>;

/** A song you kept coming back to: several plays in a single day. */
export const RepeatTrack = z.object({
  track: StatsTrack,
  plays: z.number().int().positive(),
  /** Local midnight of the day it was played most, epoch ms. */
  peakDay: z.number().int(),
  peakDayPlays: z.number().int().positive(),
  /** Days it was played on. */
  days: z.number().int().positive(),
});
export type RepeatTrack = z.infer<typeof RepeatTrack>;

export const SkippedTrack = z.object({
  track: StatsTrack,
  skips: z.number().int().positive(),
  plays: z.number().int().nonnegative(),
});
export type SkippedTrack = z.infer<typeof SkippedTrack>;

export const Discoveries = z.object({
  /** Artists first listened to in this period, most played first. */
  artists: z.array(RankedArtist),
  /** How many artists were new, including those beyond the list. */
  artistCount: z.number().int().nonnegative(),
  /** Plays of songs first heard in this period, out of all plays, 0–1. */
  newTrackShare: z.number().min(0).max(1),
  newTracks: z.number().int().nonnegative(),
});
export type Discoveries = z.infer<typeof Discoveries>;

export const ListeningRhythm = z.object({
  /** Plays per hour of the day, 0–23. */
  byHour: z.array(z.number().int().nonnegative()).length(24),
  /** Plays per weekday, Monday first. */
  byWeekday: z.array(z.number().int().nonnegative()).length(7),
  /** Plays per weekday (Monday first) and hour. */
  heatmap: z.array(z.array(z.number().int().nonnegative()).length(24)).length(7),
  peakHour: z.number().int().min(0).max(23).nullable(),
  peakWeekday: z.number().int().min(0).max(6).nullable(),
});
export type ListeningRhythm = z.infer<typeof ListeningRhythm>;

export const TimelineUnit = z.enum(['day', 'month']);
export type TimelineUnit = z.infer<typeof TimelineUnit>;

/** One day or month of the period, with what led it. */
export const TimelinePoint = z.object({
  start: z.number().int(),
  plays: z.number().int().nonnegative(),
  listeningMs: z.number().int().nonnegative(),
  topGenre: z.string().nullable(),
  topArtist: z.string().nullable(),
});
export type TimelinePoint = z.infer<typeof TimelinePoint>;

export const ListeningTimeline = z.object({
  unit: TimelineUnit,
  points: z.array(TimelinePoint),
});
export type ListeningTimeline = z.infer<typeof ListeningTimeline>;

export const ListeningStreaks = z.object({
  /** Longest run of consecutive days with plays within the period. */
  longestDays: z.number().int().nonnegative(),
  longestStart: z.number().int().nullable(),
  /** Run of days with plays ending today (or yesterday, if nothing yet today). */
  currentDays: z.number().int().nonnegative(),
});
export type ListeningStreaks = z.infer<typeof ListeningStreaks>;

/** Stretches of listening without a break of more than half an hour. */
export const ListeningSessions = z.object({
  count: z.number().int().nonnegative(),
  averageMs: z.number().int().nonnegative(),
  longestMs: z.number().int().nonnegative(),
  longestStart: z.number().int().nullable(),
  /** Songs played in the longest session. */
  longestPlays: z.number().int().nonnegative(),
});
export type ListeningSessions = z.infer<typeof ListeningSessions>;

export const ListeningVariety = z.object({
  /** How evenly plays are spread across artists, 0 (one artist) to 100. */
  score: z.number().int().min(0).max(100),
  /** Share of plays that went to the top artist, 0–1. */
  topArtistShare: z.number().min(0).max(1),
  /** Share of plays of songs played only once in the period, 0–1. */
  singlePlayShare: z.number().min(0).max(1),
});
export type ListeningVariety = z.infer<typeof ListeningVariety>;

/** A notable fact about the period, written by the server. */
export const ReportHighlight = z.object({
  kind: z.string(),
  title: z.string(),
  detail: z.string(),
});
export type ReportHighlight = z.infer<typeof ReportHighlight>;

export const ListeningReport = z.object({
  period: ReportPeriod,
  totals: ListeningTotals,
  /** The same totals for the period before; null for "all time". */
  previous: ListeningTotals.nullable(),
  /** A one-line character of how you listened, e.g. "Night owl". Null with too little to go on. */
  persona: ReportHighlight.nullable(),
  highlights: z.array(ReportHighlight),
  topGenres: z.array(RankedGenre),
  topArtists: z.array(RankedArtist),
  topAlbums: z.array(RankedAlbum),
  topTracks: z.array(RankedTrack),
  onRepeat: z.array(RepeatTrack),
  mostSkipped: z.array(SkippedTrack),
  discoveries: Discoveries,
  rhythm: ListeningRhythm,
  timeline: ListeningTimeline,
  streaks: ListeningStreaks,
  sessions: ListeningSessions,
  variety: ListeningVariety,
  /** When REV started keeping the log (the first recorded listen); null before any. */
  trackingSince: z.number().int().nullable(),
});
export type ListeningReport = z.infer<typeof ListeningReport>;

// Smart playlists: track lists made from the listening log, refreshed as you listen. Playable as
// they are, and savable to Navidrome as an ordinary playlist (saving again updates it).

export const SmartPlaylistKind = z.enum([
  /** What you've played most in the last 30 days. */
  'on-repeat',
  /** Songs you played a lot, but not in the last 90 days. */
  'forgotten',
  /** Your most played songs of a calendar year (`year`). */
  'year',
]);
export type SmartPlaylistKind = z.infer<typeof SmartPlaylistKind>;

/** `on-repeat`, `forgotten` or `year-<yyyy>`. */
export const SmartPlaylistId = z.string().regex(/^(on-repeat|forgotten|year-\d{4})$/u);

export const SmartPlaylistQuery = z.object({ tz: ReportQuery.shape.tz });

export const SmartPlaylist = z.object({
  id: z.string(),
  kind: SmartPlaylistKind,
  /** The year, for `year`; null otherwise. */
  year: z.number().int().nullable(),
  trackCount: z.number().int().nonnegative(),
  /** Up to four covers for a collage. */
  coverArtIds: z.array(z.string()),
  /** The Navidrome playlist it was last saved to; null if never saved (or that was deleted). */
  savedPlaylistId: z.string().nullable(),
});
export type SmartPlaylist = z.infer<typeof SmartPlaylist>;

export const SmartPlaylists = z.object({ playlists: z.array(SmartPlaylist) });
export type SmartPlaylists = z.infer<typeof SmartPlaylists>;

/** The songs, in order. Songs no longer in the library are left out. */
export const SmartPlaylistDetail = SmartPlaylist.extend({ tracks: z.array(Track) });
export type SmartPlaylistDetail = z.infer<typeof SmartPlaylistDetail>;

export const SaveSmartPlaylistResult = z.object({
  playlistId: z.string(),
  /** A new Navidrome playlist, rather than an update of the one saved before. */
  created: z.boolean(),
});
export type SaveSmartPlaylistResult = z.infer<typeof SaveSmartPlaylistResult>;

// Taste: what the app's smart queue knows about a user's listening, built from the log. Scores run
// from -1 (mostly skipped) to 1 (played a lot, rarely skipped); recent listens count more.

export const TasteQuery = z.object({ tz: ReportQuery.shape.tz });
export type TasteQuery = z.infer<typeof TasteQuery>;

const TasteScore = z.number().min(-1).max(1);

export const TasteArtist = z.object({ id: z.string(), score: TasteScore });
export const TasteGenre = z.object({ name: z.string(), score: TasteScore });

export const TasteProfile = z.object({
  generatedAt: z.number().int(),
  /** Listens the profile was built from. */
  listens: z.number().int().nonnegative(),
  artists: z.array(TasteArtist),
  genres: z.array(TasteGenre),
  decades: z.array(z.object({ decade: z.number().int(), score: TasteScore })),
  /** How often each song was played and skipped, for familiarity and songs you always skip. */
  tracks: z.array(
    z.object({
      id: z.string(),
      plays: z.number().int().nonnegative(),
      skips: z.number().int().nonnegative(),
    }),
  ),
  /**
   * Four-hour parts of the day (local time) and what's played more (positive) or less
   * (negative) in them than usual.
   */
  dayparts: z.array(
    z.object({
      fromHour: z.number().int().min(0).max(23),
      genres: z.array(TasteGenre),
      artists: z.array(TasteArtist),
    }),
  ),
});
export type TasteProfile = z.infer<typeof TasteProfile>;
