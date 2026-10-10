import type {
  ListeningReport,
  ListeningRhythm,
  ListeningSessions,
  ListeningStreaks,
  ListeningTimeline,
  ListeningTotals,
  ListeningVariety,
  RankedAlbum,
  RankedArtist,
  RankedGenre,
  RankedTrack,
  RepeatTrack,
  ReportHighlight,
  ReportPeriod,
  ReportPeriodKind,
  SkippedTrack,
  StatsTrack,
} from '@rev/schemas';
import type { Zone } from './zone.js';

/** One logged listen, as reports read it. */
export interface PlayRow {
  trackId: string;
  kind: 'play' | 'skip';
  startedAt: number;
  listenedMs: number;
  title: string;
  artist: string;
  artistId: string | null;
  album: string;
  albumId: string | null;
  coverArtId: string | null;
  year: number | null;
  genres: string[];
  durationMs: number;
  /** The listening mode the app recorded, if any. */
  mode?: string | null;
}

export interface Bounds {
  from: number;
  to: number;
}

/** Listening history beyond the period, for what's new and for streaks. */
export interface History {
  /** Artist key ([artistKey]) → first play, over all time. */
  firstArtistPlay: Map<string, number>;
  /** Track id → first play, over all time. */
  firstTrackPlay: Map<string, number>;
  /** Local day numbers with at least one play, over all time. */
  playDays: Set<number>;
  /** The first listen ever recorded. */
  earliest: number | null;
}

export interface ReportInput {
  kind: ReportPeriodKind;
  offset: number;
  zone: Zone;
  now: number;
  bounds: Bounds;
  rows: PlayRow[];
  /** The period before; null for "all time". */
  previousRows: PlayRow[] | null;
  history: History;
}

const TOP_LIST = 25;
const TOP_GENRES = 15;
const SHORT_LIST = 10;
/** A gap longer than this between songs ends a listening session. */
const SESSION_GAP_MS = 30 * 60_000;
/** Too little listening to say anything about someone's habits. */
const MIN_PLAYS_FOR_PERSONA = 10;

/** The start and end of a report period in [zone]; "all time" runs from the first listen. */
export function periodBounds(
  kind: ReportPeriodKind,
  offset: number,
  now: number,
  zone: Zone,
  earliest: number | null,
): Bounds {
  const { year, month, day } = zone.date(now);
  switch (kind) {
    case 'week': {
      const monday = day - zone.weekday(now) - 7 * offset;
      return { from: zone.startOf(year, month, monday), to: zone.startOf(year, month, monday + 7) };
    }
    case 'month':
      return {
        from: zone.startOf(year, month - offset, 1),
        to: zone.startOf(year, month - offset + 1, 1),
      };
    case 'year':
      return { from: zone.startOf(year - offset, 1, 1), to: zone.startOf(year - offset + 1, 1, 1) };
    case 'all': {
      const today = zone.startOf(year, month, day);
      const first = earliest === null ? today : zone.startOfDayNumber(zone.dayNumber(earliest));
      return { from: Math.min(first, today), to: zone.startOf(year, month, day + 1) };
    }
  }
}

/** Groups an artist by its library id, or by name when the tags had none. */
export function artistKey(row: Pick<PlayRow, 'artistId' | 'artist'>): string {
  return row.artistId ?? `name:${row.artist.trim().toLowerCase()}`;
}

function albumKey(row: PlayRow): string {
  return row.albumId ?? `name:${row.artist.trim().toLowerCase()}|${row.album.trim().toLowerCase()}`;
}

const genreKey = (name: string) => name.trim().toLowerCase();

export function buildReport(input: ReportInput): ListeningReport {
  const { kind, zone, now, bounds, rows, previousRows, history } = input;
  const plays = rows.filter((r) => r.kind === 'play');
  const elapsedDays = elapsed(bounds, now, zone);

  const totals = totalsOf(rows, zone, elapsedDays);
  const previousBounds = previousRows
    ? periodBounds(kind, input.offset + 1, now, zone, null)
    : null;
  const previous =
    previousRows && previousBounds
      ? totalsOf(previousRows, zone, elapsed(previousBounds, now, zone))
      : null;
  const previousPlays = previousRows?.filter((r) => r.kind === 'play') ?? [];

  const genres = rankGenres(plays, previousPlays);
  const artists = rankArtists(plays, previousPlays, history, bounds);
  const albums = rankAlbums(plays, previousPlays);
  const tracks = rankTracks(plays, previousPlays);
  const onRepeat = repeats(plays, zone);
  const rhythm = rhythmOf(plays, zone);
  const variety = varietyOf(plays, artists);
  const streaks = streaksOf(plays, zone, now, history.playDays);
  const sessions = sessionsOf(rows);

  const newTrackIds = new Set(
    plays
      .filter((p) => (history.firstTrackPlay.get(p.trackId) ?? p.startedAt) >= bounds.from)
      .map((p) => p.trackId),
  );
  const newTrackPlays = plays.filter((p) => newTrackIds.has(p.trackId)).length;
  const discoveries = {
    artists: artists.filter((a) => a.isNew).slice(0, SHORT_LIST),
    artistCount: artists.filter((a) => a.isNew).length,
    newTrackShare: plays.length > 0 ? newTrackPlays / plays.length : 0,
    newTracks: newTrackIds.size,
  };

  const period: ReportPeriod = {
    kind,
    offset: kind === 'all' ? 0 : input.offset,
    timeZone: zone.name,
    from: bounds.from,
    to: bounds.to,
    elapsedDays,
    hasEarlier: history.earliest !== null && history.earliest < bounds.from,
  };

  const report: ListeningReport = {
    period,
    totals,
    previous,
    persona: null,
    highlights: [],
    topGenres: genres.slice(0, TOP_GENRES),
    topArtists: artists.slice(0, TOP_LIST),
    topAlbums: albums.slice(0, TOP_LIST),
    topTracks: tracks.slice(0, TOP_LIST),
    onRepeat,
    mostSkipped: skipped(rows),
    discoveries,
    rhythm,
    timeline: timelineOf(kind, bounds, rows, zone, now, history.earliest),
    streaks,
    sessions,
    variety,
    trackingSince: history.earliest,
  };
  report.persona = persona(report, plays);
  report.highlights = highlights(report);
  return report;
}

function elapsed(bounds: Bounds, now: number, zone: Zone): number {
  if (now < bounds.from) return 0;
  const last = Math.min(now, bounds.to - 1);
  return zone.dayNumber(last) - zone.dayNumber(bounds.from) + 1;
}

function heard(row: PlayRow): number {
  return row.listenedMs;
}

function totalsOf(rows: PlayRow[], zone: Zone, elapsedDays: number): ListeningTotals {
  const plays = rows.filter((r) => r.kind === 'play');
  const skips = rows.length - plays.length;
  const listeningMs = rows.reduce((sum, r) => sum + heard(r), 0);
  const genres = new Set(plays.flatMap((p) => p.genres.map(genreKey)));
  return {
    plays: plays.length,
    skips,
    listeningMs,
    tracks: new Set(plays.map((p) => p.trackId)).size,
    artists: new Set(plays.map(artistKey)).size,
    albums: new Set(plays.map(albumKey)).size,
    genres: genres.size,
    activeDays: new Set(plays.map((p) => zone.dayNumber(p.startedAt))).size,
    dailyAverageMs: elapsedDays > 0 ? Math.round(listeningMs / elapsedDays) : 0,
    skipRate: rows.length > 0 ? skips / rows.length : 0,
  };
}

interface Tally<T> {
  key: string;
  value: T;
  plays: number;
  listeningMs: number;
  last: number;
}

/** Counts plays per key and orders them: most played, then most listened, then most recent. */
function tally<T>(
  plays: PlayRow[],
  keys: (row: PlayRow) => string[],
  value: (row: PlayRow, existing: T | undefined, key: string) => T,
): Tally<T>[] {
  const map = new Map<string, Tally<T>>();
  for (const row of plays) {
    for (const key of keys(row)) {
      const t = map.get(key);
      if (t) {
        t.plays += 1;
        t.listeningMs += heard(row);
        t.last = Math.max(t.last, row.startedAt);
        t.value = value(row, t.value, key);
      } else {
        map.set(key, {
          key,
          value: value(row, undefined, key),
          plays: 1,
          listeningMs: heard(row),
          last: row.startedAt,
        });
      }
    }
  }
  return [...map.values()].sort(
    (a, b) =>
      b.plays - a.plays ||
      b.listeningMs - a.listeningMs ||
      b.last - a.last ||
      a.key.localeCompare(b.key),
  );
}

function ranks(sorted: { key: string }[]): Map<string, number> {
  return new Map(sorted.map((t, i) => [t.key, i + 1]));
}

const share = (plays: number, total: number) => (total > 0 ? Math.min(1, plays / total) : 0);

function rankGenres(plays: PlayRow[], previous: PlayRow[]): RankedGenre[] {
  const keys = (r: PlayRow) => [...new Set(r.genres.map(genreKey).filter(Boolean))];
  // The first spelling seen names the genre.
  const sorted = tally(
    plays,
    keys,
    (r, name: string | undefined, key) =>
      name ?? r.genres.find((g) => genreKey(g) === key)?.trim() ?? key,
  );
  const before = ranks(tally(previous, keys, () => ''));
  // Share of plays that had a genre at all.
  const tagged = plays.filter((p) => keys(p).length > 0).length;
  return sorted.map((t) => ({
    name: t.value,
    plays: t.plays,
    listeningMs: t.listeningMs,
    share: share(t.plays, tagged),
    previousRank: before.get(t.key) ?? null,
  }));
}

function rankArtists(
  plays: PlayRow[],
  previous: PlayRow[],
  history: History,
  bounds: Bounds,
): RankedArtist[] {
  const covers = new Map<string, Map<string, number>>();
  for (const p of plays) {
    if (!p.coverArtId) continue;
    const key = artistKey(p);
    const counts = covers.get(key) ?? new Map<string, number>();
    counts.set(p.coverArtId, (counts.get(p.coverArtId) ?? 0) + 1);
    covers.set(key, counts);
  }
  const sorted = tally<{ id: string | null; name: string }>(
    plays,
    (r) => [artistKey(r)],
    (r, existing) => existing ?? { id: r.artistId, name: r.artist },
  );
  const before = ranks(
    tally(
      previous,
      (r) => [artistKey(r)],
      () => null,
    ),
  );
  return sorted.map((t) => ({
    id: t.value.id,
    name: t.value.name,
    coverArtId: mostCommon(covers.get(t.key)),
    isNew: (history.firstArtistPlay.get(t.key) ?? bounds.from) >= bounds.from,
    plays: t.plays,
    listeningMs: t.listeningMs,
    share: share(t.plays, plays.length),
    previousRank: before.get(t.key) ?? null,
  }));
}

function mostCommon(counts: Map<string, number> | undefined): string | null {
  if (!counts) return null;
  let best: string | null = null;
  let bestCount = 0;
  for (const [value, count] of counts) {
    if (count > bestCount) {
      best = value;
      bestCount = count;
    }
  }
  return best;
}

function rankAlbums(plays: PlayRow[], previous: PlayRow[]): RankedAlbum[] {
  const sorted = tally<Omit<RankedAlbum, 'plays' | 'listeningMs' | 'share' | 'previousRank'>>(
    plays,
    (r) => [albumKey(r)],
    (r, existing) =>
      existing ?? {
        id: r.albumId,
        name: r.album,
        artist: r.artist,
        artistId: r.artistId,
        coverArtId: r.coverArtId,
        year: r.year,
      },
  );
  const before = ranks(
    tally(
      previous,
      (r) => [albumKey(r)],
      () => null,
    ),
  );
  return sorted.map((t) => ({
    ...t.value,
    plays: t.plays,
    listeningMs: t.listeningMs,
    share: share(t.plays, plays.length),
    previousRank: before.get(t.key) ?? null,
  }));
}

function statsTrack(r: PlayRow): StatsTrack {
  return {
    id: r.trackId,
    title: r.title,
    artist: r.artist,
    artistId: r.artistId,
    album: r.album,
    albumId: r.albumId,
    coverArtId: r.coverArtId,
    durationMs: r.durationMs,
  };
}

function rankTracks(plays: PlayRow[], previous: PlayRow[]): RankedTrack[] {
  // Rows are oldest first, so the latest details win.
  const sorted = tally(
    plays,
    (r) => [r.trackId],
    (r) => statsTrack(r),
  );
  const before = ranks(
    tally(
      previous,
      (r) => [r.trackId],
      () => null,
    ),
  );
  return sorted.map((t) => ({
    track: t.value,
    plays: t.plays,
    listeningMs: t.listeningMs,
    share: share(t.plays, plays.length),
    previousRank: before.get(t.key) ?? null,
  }));
}

/** Songs played several times in a day, or over and over across the period. */
function repeats(plays: PlayRow[], zone: Zone): RepeatTrack[] {
  const byTrack = new Map<string, { track: StatsTrack; days: Map<number, number> }>();
  for (const p of plays) {
    const entry = byTrack.get(p.trackId) ?? { track: statsTrack(p), days: new Map() };
    entry.track = statsTrack(p);
    const day = zone.dayNumber(p.startedAt);
    entry.days.set(day, (entry.days.get(day) ?? 0) + 1);
    byTrack.set(p.trackId, entry);
  }
  const result: RepeatTrack[] = [];
  for (const { track, days } of byTrack.values()) {
    let peakDay = 0;
    let peakDayPlays = 0;
    let total = 0;
    for (const [day, count] of days) {
      total += count;
      if (count > peakDayPlays || (count === peakDayPlays && day > peakDay)) {
        peakDay = day;
        peakDayPlays = count;
      }
    }
    if (total < 3 || (peakDayPlays < 2 && total < 5)) continue;
    result.push({
      track,
      plays: total,
      peakDay: zone.startOfDayNumber(peakDay),
      peakDayPlays,
      days: days.size,
    });
  }
  return result
    .sort((a, b) => b.peakDayPlays - a.peakDayPlays || b.plays - a.plays || b.peakDay - a.peakDay)
    .slice(0, SHORT_LIST);
}

function skipped(rows: PlayRow[]): SkippedTrack[] {
  const byTrack = new Map<string, SkippedTrack>();
  for (const r of rows) {
    const entry = byTrack.get(r.trackId) ?? { track: statsTrack(r), skips: 0, plays: 0 };
    if (r.kind === 'skip') entry.skips += 1;
    else entry.plays += 1;
    byTrack.set(r.trackId, entry);
  }
  return [...byTrack.values()]
    .filter((s) => s.skips >= 2 && s.skips > s.plays)
    .sort((a, b) => b.skips - a.skips || a.plays - b.plays)
    .slice(0, SHORT_LIST);
}

function rhythmOf(plays: PlayRow[], zone: Zone): ListeningRhythm {
  const heatmap = Array.from({ length: 7 }, () => Array<number>(24).fill(0));
  for (const p of plays) {
    const row = heatmap[zone.weekday(p.startedAt)];
    const hour = zone.hour(p.startedAt);
    if (row) row[hour] = (row[hour] ?? 0) + 1;
  }
  const byHour = Array.from({ length: 24 }, (_, h) =>
    heatmap.reduce((s, row) => s + (row[h] ?? 0), 0),
  );
  const byWeekday = heatmap.map((row) => row.reduce((s, n) => s + n, 0));
  return { byHour, byWeekday, heatmap, peakHour: peak(byHour), peakWeekday: peak(byWeekday) };
}

function peak(values: number[]): number | null {
  let best = -1;
  let bestValue = 0;
  values.forEach((v, i) => {
    if (v > bestValue) {
      best = i;
      bestValue = v;
    }
  });
  return best >= 0 ? best : null;
}

function timelineOf(
  kind: ReportPeriodKind,
  bounds: Bounds,
  rows: PlayRow[],
  zone: Zone,
  now: number,
  earliest: number | null,
): ListeningTimeline {
  const byMonth = kind === 'year' || kind === 'all';
  const starts: number[] = [];
  if (byMonth) {
    const first = zone.date(kind === 'all' ? (earliest ?? now) : bounds.from);
    const last = zone.date(Math.min(now, bounds.to - 1));
    for (let i = 0; ; i++) {
      const start = zone.startOf(first.year, first.month + i, 1);
      if (start >= bounds.to || i > 600) break;
      starts.push(start);
      const d = zone.date(start);
      if (d.year === last.year && d.month === last.month) break;
    }
  } else {
    const firstDay = zone.dayNumber(bounds.from);
    const lastDay = zone.dayNumber(bounds.to - 1);
    for (let n = firstDay; n <= lastDay; n++) starts.push(zone.startOfDayNumber(n));
  }
  const buckets = starts.map(() => [] as PlayRow[]);
  for (const r of rows) {
    // Last bucket starting at or before the listen.
    let lo = 0;
    let hi = starts.length - 1;
    let found = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if ((starts[mid] ?? Infinity) <= r.startedAt) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    buckets[found]?.push(r);
  }
  return {
    unit: byMonth ? 'month' : 'day',
    points: starts.map((start, i) => {
      const bucket = buckets[i] ?? [];
      const plays = bucket.filter((r) => r.kind === 'play');
      return {
        start,
        plays: plays.length,
        listeningMs: bucket.reduce((s, r) => s + heard(r), 0),
        topGenre: rankGenres(plays, [])[0]?.name ?? null,
        topArtist:
          tally(
            plays,
            (r) => [artistKey(r)],
            (r, e: string | undefined) => e ?? r.artist,
          )[0]?.value ?? null,
      };
    }),
  };
}

function streaksOf(
  plays: PlayRow[],
  zone: Zone,
  now: number,
  allDays: Set<number>,
): ListeningStreaks {
  const days = [...new Set(plays.map((p) => zone.dayNumber(p.startedAt)))].sort((a, b) => a - b);
  let longest = 0;
  let longestStart: number | null = null;
  let run = 0;
  let runStart = 0;
  days.forEach((day, i) => {
    if (i > 0 && day === (days[i - 1] ?? NaN) + 1) {
      run += 1;
    } else {
      run = 1;
      runStart = day;
    }
    if (run > longest) {
      longest = run;
      longestStart = runStart;
    }
  });
  const today = zone.dayNumber(now);
  let day = allDays.has(today) ? today : today - 1;
  let current = 0;
  while (allDays.has(day)) {
    current += 1;
    day -= 1;
  }
  return {
    longestDays: longest,
    longestStart: longestStart === null ? null : zone.startOfDayNumber(longestStart),
    currentDays: current,
  };
}

function sessionsOf(rows: PlayRow[]): ListeningSessions {
  let count = 0;
  let longestMs = 0;
  let longestStart: number | null = null;
  let longestPlays = 0;
  let total = 0;
  let start = 0;
  let end = 0;
  let listened = 0;
  let plays = 0;
  const close = () => {
    if (plays === 0 && listened === 0) return;
    count += 1;
    total += listened;
    if (listened > longestMs) {
      longestMs = listened;
      longestStart = start;
      longestPlays = plays;
    }
  };
  for (const r of rows) {
    if (count === 0 && plays === 0 && listened === 0) {
      start = r.startedAt;
    } else if (r.startedAt - end > SESSION_GAP_MS) {
      close();
      start = r.startedAt;
      listened = 0;
      plays = 0;
    }
    listened += heard(r);
    if (r.kind === 'play') plays += 1;
    end = Math.max(end, r.startedAt + heard(r));
  }
  close();
  return {
    count,
    averageMs: count > 0 ? Math.round(total / count) : 0,
    longestMs,
    longestStart,
    longestPlays,
  };
}

function varietyOf(plays: PlayRow[], artists: RankedArtist[]): ListeningVariety {
  if (plays.length === 0) return { score: 0, topArtistShare: 0, singlePlayShare: 0 };
  // "Effective number of artists" (exp of Shannon entropy): 1 for a single artist, n for n
  // artists played equally. 2 → 50, 4 → 75, 10 → 90.
  let entropy = 0;
  for (const a of artists) {
    const p = a.plays / plays.length;
    entropy -= p * Math.log(p);
  }
  const effective = Math.exp(entropy);
  const perTrack = new Map<string, number>();
  for (const p of plays) perTrack.set(p.trackId, (perTrack.get(p.trackId) ?? 0) + 1);
  const singles = [...perTrack.values()].filter((n) => n === 1).length;
  return {
    score: Math.round(100 * (1 - 1 / effective)),
    topArtistShare: artists[0]?.share ?? 0,
    singlePlayShare: singles / plays.length,
  };
}

// --- Words -------------------------------------------------------------------------------------

const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

export function formatDuration(ms: number): string {
  const minutes = Math.round(ms / 60_000);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} hr` : `${hours} hr ${rest} min`;
}

export function formatHour(hour: number): string {
  if (hour === 0) return 'midnight';
  if (hour === 12) return 'noon';
  return hour < 12 ? `${hour} am` : `${hour - 12} pm`;
}

const percent = (fraction: number) => `${Math.round(fraction * 100)}%`;
const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;

function periodNoun(kind: ReportPeriodKind): string {
  return kind === 'all' ? 'all time' : kind;
}

/** A character sketch from the strongest pattern in the period. */
function persona(report: ListeningReport, plays: PlayRow[]): ReportHighlight | null {
  const n = plays.length;
  if (n < MIN_PLAYS_FOR_PERSONA) return null;
  const { rhythm, variety, discoveries, totals, period, topArtists, topGenres } = report;
  const hours = (from: number, to: number) => {
    let sum = 0;
    for (let h = from; h !== to; h = (h + 1) % 24) sum += rhythm.byHour[h] ?? 0;
    return sum / n;
  };
  const night = hours(22, 4);
  const morning = hours(5, 9);
  const weekend = ((rhythm.byWeekday[5] ?? 0) + (rhythm.byWeekday[6] ?? 0)) / n;
  const candidates: { strength: number; highlight: ReportHighlight }[] = [];
  const add = (strength: number, kind: string, title: string, detail: string) =>
    candidates.push({ strength, highlight: { kind, title, detail } });

  if (night >= 0.35)
    add(
      night / 0.35,
      'night-owl',
      'Night owl',
      `${percent(night)} of your plays came after 10 pm.`,
    );
  if (morning >= 0.3)
    add(
      morning / 0.3,
      'early-bird',
      'Early bird',
      `${percent(morning)} of your plays came before 9 am.`,
    );
  if (weekend >= 0.5)
    add(
      weekend / 0.5,
      'weekend',
      'Weekend warrior',
      `${percent(weekend)} of your plays were on Saturdays and Sundays.`,
    );
  if (discoveries.newTrackShare >= 0.5)
    add(
      discoveries.newTrackShare / 0.5,
      'explorer',
      'Explorer',
      `${percent(discoveries.newTrackShare)} of what you played was new to you.`,
    );
  const top = topArtists[0];
  if (top && variety.topArtistShare >= 0.35)
    add(
      variety.topArtistShare / 0.35,
      'devotee',
      `${top.name} devotee`,
      `${percent(variety.topArtistShare)} of your plays were ${top.name}.`,
    );
  if (n >= 20 && variety.singlePlayShare <= 0.25)
    add(
      0.25 / Math.max(variety.singlePlayShare, 0.05),
      'repeat',
      'Repeat listener',
      'You stuck with songs you love, playing most of them again and again.',
    );
  if (totals.genres >= 8 && variety.score >= 85)
    add(
      variety.score / 85,
      'eclectic',
      'Genre hopper',
      `${plural(totals.genres, 'genre')} and ${plural(totals.artists, 'artist')}, nothing for long.`,
    );
  if (period.elapsedDays >= 5 && totals.activeDays / period.elapsedDays >= 0.85)
    add(
      totals.activeDays / period.elapsedDays / 0.85,
      'daily',
      'Daily ritual',
      `Music on ${totals.activeDays} of ${period.elapsedDays} days.`,
    );
  candidates.sort((a, b) => b.strength - a.strength);
  if (candidates[0]) return candidates[0].highlight;

  const genre = topGenres[0];
  if (genre && genre.share >= 0.3)
    return {
      kind: 'genre',
      title: `${genre.name} at heart`,
      detail: `${percent(genre.share)} of your plays were ${genre.name}.`,
    };
  return {
    kind: 'balanced',
    title: 'All-rounder',
    detail: 'No single habit stood out — a little of everything.',
  };
}

function highlights(report: ListeningReport): ReportHighlight[] {
  const out: ReportHighlight[] = [];
  const { totals, previous, period, topGenres, topArtists, topTracks, onRepeat } = report;
  if (totals.plays === 0) return out;
  const noun = periodNoun(period.kind);
  const before = `the ${noun} before`;

  if (previous && previous.listeningMs > 0) {
    const change = (totals.listeningMs - previous.listeningMs) / previous.listeningMs;
    const finished = period.offset > 0;
    if (Math.abs(change) >= 0.1 && (finished || change > 0)) {
      add(
        out,
        'listening-change',
        change > 0
          ? `${percent(change)} more listening than ${before}`
          : `${percent(-change)} less listening than ${before}`,
        `${formatDuration(totals.listeningMs)}, against ${formatDuration(previous.listeningMs)}.`,
      );
    }
  }

  const genre = topGenres[0];
  if (genre) {
    const prevTop = topGenres.find((g) => g.previousRank === 1);
    const title =
      period.kind === 'all'
        ? `${genre.name} is your genre of all time`
        : `${genre.name} was your genre of the ${noun}`;
    const detail =
      prevTop && prevTop.name !== genre.name && genre.previousRank !== 1
        ? `It took over from ${prevTop.name}, with ${plural(genre.plays, 'play')}.`
        : `${plural(genre.plays, 'play')}, ${percent(genre.share)} of your tagged listening.`;
    add(out, 'top-genre', title, detail);
  }

  const artist = topArtists[0];
  if (artist) {
    const detail = artist.isNew
      ? `New to you this ${noun}, and already ${plural(artist.plays, 'play')}.`
      : artist.previousRank && artist.previousRank > 1
        ? `Up from #${artist.previousRank} ${before}, with ${plural(artist.plays, 'play')}.`
        : `${plural(artist.plays, 'play')} — ${percent(artist.share)} of everything you played.`;
    add(out, 'top-artist', `${artist.name} was your most played artist`, detail);
  }

  const obsession = onRepeat[0];
  if (obsession && obsession.peakDayPlays >= 3) {
    add(
      out,
      'on-repeat',
      `“${obsession.track.title}” on repeat`,
      `${obsession.peakDayPlays} times in one day, ${plural(obsession.plays, 'play')} in all.`,
    );
  } else if (topTracks[0] && topTracks[0].plays >= 3) {
    const t = topTracks[0];
    add(
      out,
      'top-track',
      `“${t.track.title}” was your song of the ${noun}`,
      `${plural(t.plays, 'play')} of ${t.track.artist}.`,
    );
  }

  const newArtists = report.discoveries.artistCount;
  const first = report.discoveries.artists[0];
  if (first && period.kind !== 'all') {
    add(
      out,
      'discoveries',
      `${plural(newArtists, 'new artist')} found`,
      `${first.name} made the biggest impression, with ${plural(first.plays, 'play')}.`,
    );
  }

  const { peakHour, peakWeekday } = report.rhythm;
  if (peakHour !== null && totals.plays >= 10) {
    const day = peakWeekday !== null ? `${WEEKDAYS[peakWeekday]}s` : null;
    add(
      out,
      'peak-time',
      `Your music peaks around ${formatHour(peakHour)}`,
      day ? `${day} were your biggest day for listening.` : 'Your busiest hour for music.',
    );
  }

  if (report.sessions.longestMs >= 60 * 60_000 && report.sessions.longestStart !== null) {
    add(
      out,
      'marathon',
      `A ${formatDuration(report.sessions.longestMs)} session`,
      `Your longest stretch: ${plural(report.sessions.longestPlays, 'song')} without a break.`,
    );
  }

  if (report.streaks.longestDays >= 3) {
    add(
      out,
      'streak',
      `${report.streaks.longestDays}-day streak`,
      report.streaks.currentDays >= 2
        ? `You're on ${plural(report.streaks.currentDays, 'day')} in a row right now.`
        : 'Days in a row with music.',
    );
  }

  if (totals.skips >= 10 && totals.skipRate >= 0.3) {
    add(
      out,
      'skips',
      `You skipped ${percent(totals.skipRate)} of what you started`,
      'Picky — or time for something new.',
    );
  }
  return out;
}

function add(out: ReportHighlight[], kind: string, title: string, detail: string): void {
  out.push({ kind, title, detail });
}
