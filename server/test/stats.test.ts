import { afterEach, describe, expect, it } from 'vitest';
import { ListenBatchResult, ListeningReport } from '@rev/schemas';
import { periodBounds } from '../src/stats/report.js';
import { Zone } from '../src/stats/zone.js';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { fakeNavidrome, NAVIDROME, subsonicError, subsonicOk } from './fake-navidrome.js';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
/** Friday 9 October 2026, 12:00 UTC. */
const NOW = Date.UTC(2026, 9, 9, 12);

describe('Zone', () => {
  it('finds local days, hours and weekdays across DST and odd offsets', () => {
    const ny = new Zone('America/New_York');
    // 2026-03-08 is the spring-forward day in New York: midnight is still UTC-5.
    expect(ny.startOf(2026, 3, 8)).toBe(Date.UTC(2026, 2, 8, 5));
    expect(ny.startOf(2026, 3, 9)).toBe(Date.UTC(2026, 2, 9, 4));
    expect(ny.hour(Date.UTC(2026, 6, 1, 3))).toBe(23);
    expect(ny.weekday(Date.UTC(2026, 9, 9, 2))).toBe(3); // still Thursday evening there

    const kathmandu = new Zone('Asia/Kathmandu'); // UTC+5:45
    expect(kathmandu.startOf(2026, 10, 9)).toBe(Date.UTC(2026, 9, 8, 18, 15));
    expect(
      kathmandu.dayNumber(Date.UTC(2026, 9, 8, 18, 15)) -
        kathmandu.dayNumber(Date.UTC(2026, 9, 8, 18, 14)),
    ).toBe(1);
  });

  it('works out report periods in the listener’s time zone', () => {
    const zone = new Zone('Europe/Berlin'); // UTC+2 in October
    expect(periodBounds('week', 0, NOW, zone, null)).toEqual({
      from: Date.UTC(2026, 9, 4, 22), // Monday 5 Oct, local midnight
      to: Date.UTC(2026, 9, 11, 22),
    });
    expect(periodBounds('week', 1, NOW, zone, null).from).toBe(Date.UTC(2026, 8, 27, 22));
    expect(periodBounds('month', 0, NOW, zone, null)).toEqual({
      from: Date.UTC(2026, 8, 30, 22),
      to: Date.UTC(2026, 9, 31, 23), // November starts after the clocks go back
    });
    expect(periodBounds('month', 10, NOW, zone, null).from).toBe(Date.UTC(2025, 11, 1) - HOUR);
    expect(periodBounds('year', 1, NOW, zone, null)).toEqual({
      from: Date.UTC(2025, 0, 1) - HOUR,
      to: Date.UTC(2026, 0, 1) - HOUR,
    });
    const all = periodBounds('all', 0, NOW, zone, Date.UTC(2026, 0, 15, 9));
    expect(all.from).toBe(Date.UTC(2026, 0, 14, 23));
  });
});

type Song = {
  id: string;
  title: string;
  artist: string;
  artistId?: string;
  album: string;
  albumId: string;
  genres: string[];
  duration: number;
};

const song = (
  id: string,
  artist: string,
  album: string,
  genres: string[],
  duration = 200,
): Song => ({
  id,
  title: `Song ${id}`,
  artist,
  artistId: `ar-${artist}`,
  album,
  albumId: `al-${album}`,
  genres,
  duration,
});

const SONGS: Record<string, Song> = {
  s1: song('s1', 'Tool', 'Lateralus', ['Progressive Metal']),
  s2: song('s2', 'Tool', 'Lateralus', ['Progressive Metal']),
  s3: song('s3', 'Deftones', 'White Pony', ['Alternative Metal', 'Nu Metal']),
  s4: song('s4', 'Bjork', 'Homogenic', ['Electronic']),
  s5: song('s5', 'Deftones', 'Diamond Eyes', ['Alternative Metal']),
};

describe('listening statistics', () => {
  let t: TestContext;
  let now = NOW;
  afterEach(async () => {
    await t.close();
    now = NOW;
  });

  async function setup(handlers: Parameters<typeof fakeNavidrome>[0] = {}) {
    const nav = fakeNavidrome({
      getSong: (params) => {
        const s = SONGS[params.get('id') ?? ''];
        if (!s) return subsonicError(70, 'not found');
        const { genres, ...rest } = s;
        return subsonicOk({
          song: {
            ...rest,
            coverArt: `cov-${s.albumId}`,
            genre: genres[0],
            genres: genres.map((name) => ({ name })),
          },
        });
      },
      scrobble: () => subsonicOk({}),
      ...handlers,
    });
    t = await createTestApp({ navidrome: NAVIDROME, fetch: nav.fetch, now: () => now });
    return { nav, headers: await signedInHeaders(t) };
  }

  type ListenInput = {
    trackId: string;
    startedAt: number;
    kind: 'play' | 'skip';
    listenedMs?: number;
  };

  async function send(headers: Record<string, string>, listens: ListenInput[]) {
    return t.app.inject({
      method: 'POST',
      url: '/api/stats/listens',
      headers,
      payload: { listens },
    });
  }

  async function report(headers: Record<string, string>, query: string) {
    const res = await t.app.inject({ url: `/api/stats/report?${query}`, headers });
    expect(res.statusCode).toBe(200);
    return ListeningReport.parse(res.json());
  }

  it('records listens once, passes plays on to Navidrome and drops unknown tracks', async () => {
    const { nav, headers } = await setup();
    const listens: ListenInput[] = [
      { trackId: 's1', startedAt: NOW - 2 * HOUR, kind: 'play' },
      { trackId: 's2', startedAt: NOW - HOUR, kind: 'skip', listenedMs: 12_000 },
      { trackId: 'gone', startedAt: NOW - 30 * 60_000, kind: 'play' },
    ];
    const first = await send(headers, listens);
    expect(first.statusCode).toBe(200);
    expect(ListenBatchResult.parse(first.json())).toEqual({
      recorded: 2,
      duplicates: 0,
      unknown: 1,
    });
    const scrobbles = nav.calls.filter((c) => c.endpoint === 'scrobble');
    expect(
      scrobbles.map((c) => [c.params.get('id'), c.params.get('submission'), c.params.get('time')]),
    ).toEqual([['s1', 'true', String(NOW - 2 * HOUR)]]);

    // A resend after a lost response changes nothing, and isn't scrobbled again.
    const again = await send(headers, listens.slice(0, 2));
    expect(again.json()).toEqual({ recorded: 0, duplicates: 2, unknown: 0 });
    expect(nav.calls.filter((c) => c.endpoint === 'scrobble')).toHaveLength(1);

    const r = await report(headers, 'period=week&tz=UTC');
    expect(r.totals).toMatchObject({
      plays: 1,
      skips: 1,
      listeningMs: 200_000 + 12_000,
      skipRate: 0.5,
    });
  });

  it('keeps what was recorded and fails the batch when Navidrome is down, so the app retries', async () => {
    let up = true;
    const { headers } = await setup({
      scrobble: () => (up ? subsonicOk({}) : new Response('down', { status: 503 })),
    });
    up = false;
    const res = await send(headers, [{ trackId: 's1', startedAt: NOW - HOUR, kind: 'play' }]);
    expect(res.statusCode).toBeGreaterThanOrEqual(500);
    up = true;
    const retry = await send(headers, [{ trackId: 's1', startedAt: NOW - HOUR, kind: 'play' }]);
    expect(retry.json()).toEqual({ recorded: 1, duplicates: 0, unknown: 0 });
  });

  it('logs completed plays sent to the scrobble endpoint', async () => {
    const { nav, headers } = await setup();
    const res = await t.app.inject({
      method: 'POST',
      url: '/api/library/tracks/s3/scrobble',
      headers,
      payload: { submission: true, playedAt: NOW - HOUR },
    });
    expect(res.statusCode).toBe(204);
    expect(nav.calls.filter((c) => c.endpoint === 'scrobble')).toHaveLength(1);
    const r = await report(headers, 'period=week&tz=UTC');
    expect(r.topArtists[0]).toMatchObject({ name: 'Deftones', plays: 1, isNew: true });
  });

  it('validates input and requires a session', async () => {
    const { headers } = await setup();
    expect((await t.app.inject({ url: '/api/stats/report' })).statusCode).toBe(401);
    expect(
      (await t.app.inject({ url: '/api/stats/report?tz=Mars/Olympus', headers })).statusCode,
    ).toBe(400);
    expect((await send(headers, [])).statusCode).toBe(400);
    expect(
      (await send(headers, [{ trackId: 's1', startedAt: NOW, kind: 'loved' as 'play' }]))
        .statusCode,
    ).toBe(400);
  });

  it('keeps each user’s log separate', async () => {
    const { headers } = await setup();
    await send(headers, [{ trackId: 's1', startedAt: NOW - HOUR, kind: 'play' }]);
    const { createUser } = await import('../src/auth/users.js');
    await createUser(t.handle.db, 'other', 'another long password');
    const login = await t.app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username: 'other', password: 'another long password' },
    });
    const other = {
      authorization: `Bearer ${(login.json() as { accessToken: string }).accessToken}`,
    };
    const r = await report(other, 'period=all&tz=UTC');
    expect(r.totals.plays).toBe(0);
    expect(r.trackingSince).toBeNull();
    expect(r.persona).toBeNull();
    expect(r.highlights).toEqual([]);
  });

  it('builds a full report: rankings, repeats, discoveries, rhythm, timeline and comparisons', async () => {
    const { headers } = await setup();
    const thisMonday = Date.UTC(2026, 9, 5);
    const lastMonday = thisMonday - 7 * DAY;
    const at = (monday: number, day: number, hour: number, minute = 0) =>
      monday + day * DAY + hour * HOUR + minute * 60_000;

    // Last week: mostly Deftones.
    const before: ListenInput[] = [
      { trackId: 's3', startedAt: at(lastMonday, 0, 20), kind: 'play' },
      { trackId: 's3', startedAt: at(lastMonday, 1, 20), kind: 'play' },
      { trackId: 's5', startedAt: at(lastMonday, 2, 20), kind: 'play' },
      { trackId: 's1', startedAt: at(lastMonday, 3, 20), kind: 'play' },
    ];
    // This week: Tool on repeat late at night, Bjork discovered, s5 skipped a lot.
    const week: ListenInput[] = [
      ...[0, 4, 8, 12].map((m) => ({
        trackId: 's1',
        startedAt: at(thisMonday, 2, 23, m),
        kind: 'play' as const,
      })),
      { trackId: 's2', startedAt: at(thisMonday, 2, 23, 16), kind: 'play' },
      { trackId: 's1', startedAt: at(thisMonday, 3, 23), kind: 'play' },
      { trackId: 's4', startedAt: at(thisMonday, 3, 23, 5), kind: 'play' },
      { trackId: 's4', startedAt: at(thisMonday, 4, 9), kind: 'play' },
      { trackId: 's3', startedAt: at(thisMonday, 0, 22), kind: 'play' },
      { trackId: 's5', startedAt: at(thisMonday, 1, 22), kind: 'skip', listenedMs: 5_000 },
      { trackId: 's5', startedAt: at(thisMonday, 1, 22, 1), kind: 'skip', listenedMs: 3_000 },
      { trackId: 's5', startedAt: at(thisMonday, 3, 22, 1), kind: 'skip', listenedMs: 4_000 },
    ];
    expect((await send(headers, [...before, ...week])).json()).toMatchObject({ recorded: 16 });

    const r = await report(headers, 'period=week&offset=0&tz=UTC');
    expect(r.period).toMatchObject({
      kind: 'week',
      from: thisMonday,
      to: thisMonday + 7 * DAY,
      elapsedDays: 5,
      hasEarlier: true,
    });
    expect(r.totals).toMatchObject({
      plays: 9,
      skips: 3,
      tracks: 4,
      artists: 3,
      albums: 3,
      activeDays: 4,
    });
    expect(r.previous).toMatchObject({ plays: 4, skips: 0 });

    expect(r.topArtists.map((a) => [a.name, a.plays, a.previousRank, a.isNew])).toEqual([
      ['Tool', 6, 2, false],
      ['Bjork', 2, null, true],
      ['Deftones', 1, 1, false],
    ]);
    expect(r.topArtists[0]?.coverArtId).toBe('cov-al-Lateralus');
    expect(r.topGenres[0]).toMatchObject({ name: 'Progressive Metal', plays: 6, previousRank: 3 });
    expect(r.topGenres.map((g) => g.name)).toContain('Nu Metal');
    expect(r.topAlbums[0]).toMatchObject({ name: 'Lateralus', artist: 'Tool', plays: 6 });
    expect(r.topTracks[0]).toMatchObject({
      track: { id: 's1', title: 'Song s1' },
      plays: 5,
      previousRank: 2,
    });

    expect(r.onRepeat[0]).toMatchObject({
      track: { id: 's1' },
      plays: 5,
      peakDayPlays: 4,
      peakDay: at(thisMonday, 2, 0),
      days: 2,
    });
    expect(r.mostSkipped).toEqual([
      expect.objectContaining({ track: expect.objectContaining({ id: 's5' }), skips: 3, plays: 0 }),
    ]);

    expect(r.discoveries.artists.map((a) => a.name)).toEqual(['Bjork']);
    expect(r.discoveries.artistCount).toBe(1);
    expect(r.discoveries.newTracks).toBe(2); // s2 and s4
    expect(r.discoveries.newTrackShare).toBeCloseTo(3 / 9);

    expect(r.rhythm.peakHour).toBe(23);
    expect(r.rhythm.byWeekday).toEqual([1, 0, 5, 2, 1, 0, 0]);
    expect(r.rhythm.heatmap[2]?.[23]).toBe(5);

    expect(r.timeline.unit).toBe('day');
    expect(r.timeline.points).toHaveLength(7);
    expect(r.timeline.points[2]).toMatchObject({
      plays: 5,
      topArtist: 'Tool',
      topGenre: 'Progressive Metal',
    });

    expect(r.streaks).toMatchObject({ longestDays: 3, longestStart: thisMonday + 2 * DAY }); // Tuesday had only skips
    expect(r.sessions.count).toBeGreaterThan(0);
    expect(r.variety.topArtistShare).toBeCloseTo(6 / 9);
    expect(r.persona).toBeNull(); // too few plays to judge
    const kinds = r.highlights.map((h) => h.kind);
    expect(kinds).toEqual(
      expect.arrayContaining(['top-genre', 'top-artist', 'on-repeat', 'discoveries']),
    );
    expect(r.highlights.find((h) => h.kind === 'top-genre')?.title).toBe(
      'Progressive Metal was your genre of the week',
    );
    expect(r.highlights.find((h) => h.kind === 'top-artist')?.detail).toContain('Up from #2');

    // The same listens seen from New York shift into the evening and onto the day before.
    const ny = await report(headers, `period=week&tz=${encodeURIComponent('America/New_York')}`);
    expect(ny.rhythm.peakHour).toBe(19);

    const year = await report(headers, 'period=year&tz=UTC');
    expect(year.timeline.unit).toBe('month');
    expect(year.timeline.points).toHaveLength(10); // January to October so far
    expect(year.timeline.points[8]).toMatchObject({ plays: 3, topArtist: 'Deftones' }); // 28–30 Sep
    expect(year.timeline.points[9]).toMatchObject({ plays: 10, topArtist: 'Tool' });
    expect(year.topArtists[0]).toMatchObject({ name: 'Tool', plays: 7 });

    const all = await report(headers, 'period=all&tz=UTC');
    expect(all.previous).toBeNull();
    expect(all.period.from).toBe(lastMonday);
    expect(all.trackingSince).toBe(at(lastMonday, 0, 20));
    expect(all.timeline.points).toHaveLength(2); // September and October

    const lastWeek = await report(headers, 'period=week&offset=1&tz=UTC');
    expect(lastWeek.totals.plays).toBe(4);
    expect(lastWeek.topArtists[0]).toMatchObject({ name: 'Deftones', isNew: true });
    expect(lastWeek.period.hasEarlier).toBe(false);
  });

  it('sums up how you listen once there is enough to go on', async () => {
    const { headers } = await setup();
    const monday = Date.UTC(2026, 9, 5);
    await send(
      headers,
      Array.from({ length: 12 }, (_, i) => ({
        trackId: ['s1', 's3', 's4', 's5'][i % 4] ?? 's1',
        startedAt: monday + (i % 4) * DAY + 23 * HOUR + i * 60_000,
        kind: 'play' as const,
      })),
    );
    const r = await report(headers, 'period=week&tz=UTC');
    expect(r.persona).toMatchObject({ kind: 'night-owl', title: 'Night owl' });
    expect(r.highlights.find((h) => h.kind === 'peak-time')?.title).toBe(
      'Your music peaks around 11 pm',
    );
  });

  it('counts the current streak up to today', async () => {
    const { headers } = await setup();
    const today = Date.UTC(2026, 9, 9);
    await send(
      headers,
      [1, 2, 3, 5].map((d) => ({
        trackId: 's1',
        startedAt: today - d * DAY + HOUR,
        kind: 'play' as const,
      })),
    );
    // Nothing yet today: the streak still runs through yesterday.
    expect((await report(headers, 'period=month&tz=UTC')).streaks.currentDays).toBe(3);
    await send(headers, [{ trackId: 's2', startedAt: today + HOUR, kind: 'play' }]);
    expect((await report(headers, 'period=month&tz=UTC')).streaks.currentDays).toBe(4);
    now = NOW + 2 * DAY;
    expect((await report(headers, 'period=month&tz=UTC')).streaks.currentDays).toBe(0);
  });
});
