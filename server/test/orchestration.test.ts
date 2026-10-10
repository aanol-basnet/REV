import { afterEach, describe, expect, it } from 'vitest';
import { DownloadJob, type ServerEvent, SystemStatus } from '@rev/schemas';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { fakeNavidrome, NAVIDROME, subsonicOk } from './fake-navidrome.js';
import {
  fakeLidarr,
  fakeSlskd,
  LIDARR,
  routeFetch,
  SLSKD,
  type SlskdWorld,
} from './fake-services.js';

const LATERALUS = 'aaaaaaaa-0000-4000-8000-000000000001';
const T0 = Date.parse('2026-10-01T12:00:00Z');
const iso = (ms: number) => new Date(ms).toISOString().replace('Z', ''); // slskd omits the zone

/** A small simulated household: Lidarr, slskd and Navidrome whose state the test advances. */
function world() {
  const state = {
    lidarrAlbum: null as null | { monitored: boolean; trackCount: number; trackFileCount: number },
    imports: [] as {
      name: string;
      status: string;
      message: string;
      queued: string;
      body: { path: string };
    }[],
    inNavidrome: false,
    scanRequested: 0,
    slskd: { transfers: [], searches: [], serverState: 'Connected, LoggedIn' } as SlskdWorld,
  };
  const lidarr = fakeLidarr({
    'GET album/lookup': () => [
      {
        title: 'Lateralus',
        foreignAlbumId: LATERALUS,
        releaseDate: '2001-05-15',
        artist: { artistName: 'Tool', foreignArtistId: 'ar-mb' },
      },
    ],
    'GET artist/lookup': () => [],
    'GET album': () => [],
    'GET rootfolder': () => [
      { path: '/music', accessible: true, defaultQualityProfileId: 1, defaultMetadataProfileId: 1 },
    ],
    'POST album': () => {
      state.lidarrAlbum = { monitored: true, trackCount: 13, trackFileCount: 0 };
      return { id: 77, artistId: 9 };
    },
    'GET artist/9': () => ({ id: 9, monitored: true }),
    'GET album/77': () => ({
      id: 77,
      artistId: 9,
      monitored: state.lidarrAlbum?.monitored,
      statistics: state.lidarrAlbum,
    }),
    'GET queue/details': () => [],
    'GET command': () => state.imports,
    'GET system/status': () => ({ version: '2.9.6' }),
  });
  const navidrome = fakeNavidrome({
    search3: () =>
      subsonicOk({
        searchResult3: state.inNavidrome
          ? {
              album: [
                { id: 'al-lat', name: 'Lateralus', artist: 'Tool', songCount: 13, duration: 4700 },
              ],
            }
          : {},
      }),
    startScan: () => {
      state.scanRequested++;
      return subsonicOk({ scanStatus: { scanning: true } });
    },
    ping: () => subsonicOk({}),
    getArtists: () =>
      subsonicOk({
        artists: { index: [{ name: 'T', artist: [{ id: 'ar-1', name: 'Tool', albumCount: 4 }] }] },
      }),
    getScanStatus: () => subsonicOk({ scanStatus: { scanning: false, count: 1234 } }),
  });
  return { state, lidarr, navidrome, slskd: fakeSlskd(state.slskd) };
}

describe('download orchestration', () => {
  let t: TestContext;
  let headers: { authorization: string };
  let now = T0;

  afterEach(async () => {
    await t.close();
  });

  async function setup(w: ReturnType<typeof world>, opts: { slskdDown?: boolean } = {}) {
    now = T0;
    const down = (async () => {
      throw new TypeError('fetch failed');
    }) as typeof fetch;
    t = await createTestApp({
      navidrome: NAVIDROME,
      lidarr: LIDARR,
      slskd: SLSKD,
      now: () => now,
      fetch: routeFetch({
        'navidrome.test:4533': w.navidrome.fetch,
        'lidarr.test:8686': w.lidarr.fetch,
        'slskd.test:5030': opts.slskdDown ? down : w.slskd,
      }),
    });
    headers = await signedInHeaders(t);
  }

  const request = async () =>
    DownloadJob.parse(
      (
        await t.app.inject({
          method: 'POST',
          url: '/api/downloads',
          headers,
          payload: { catalogAlbumId: LATERALUS },
        })
      ).json(),
    );
  const job = async (id: string) =>
    DownloadJob.parse((await t.app.inject({ url: `/api/downloads/${id}`, headers })).json());

  it('follows a request from Lidarr through Soularr/slskd to Navidrome, emitting events', async () => {
    const w = world();
    await setup(w);
    const events: ServerEvent[] = [];
    t.app.rev.bus.subscribe((e) => events.push(e));

    const created = await request();
    expect(created.state).toBe('ADDED_TO_LIDARR');

    const step = async () => {
      now += 60_000;
      await t.app.rev.monitor.tick();
      return job(created.id);
    };

    expect((await step()).state).toBe('WAITING_FOR_SOULARR');

    // An unrelated download from before the request is ignored.
    w.state.slskd.transfers.push({
      username: 'old',
      directory: '@@x\\Music\\Tool\\Lateralus',
      filename: '01.flac',
      state: 'InProgress',
      size: 10,
      bytesTransferred: 5,
      requestedAt: iso(T0 - 3_600_000),
    });
    w.state.slskd.searches.push({
      id: 's1',
      searchText: 'Lateralus',
      state: 'InProgress',
      startedAt: iso(now),
    });
    expect((await step()).state).toBe('SEARCHING');

    for (const search of w.state.slskd.searches) search.state = 'Completed, ResponseLimitReached';
    w.state.slskd.transfers.push(
      ...['01 The Grudge.flac', '02 Eon Blue.flac'].map((filename, i) => ({
        username: 'peer',
        directory: '@@peer\\Music\\Tool\\2001 - Lateralus [FLAC]',
        filename,
        state: i === 0 ? 'InProgress' : 'Queued, Remotely',
        size: 100,
        bytesTransferred: i === 0 ? 50 : 0,
        requestedAt: iso(now),
      })),
    );
    const downloading = await step();
    expect(downloading).toMatchObject({ state: 'DOWNLOADING', progress: 0.25 });

    for (const tr of w.state.slskd.transfers.slice(1)) {
      tr.state = 'Completed, Succeeded';
      tr.bytesTransferred = tr.size;
    }
    w.state.slskd.transfers.shift(); // the unrelated one finishes elsewhere
    expect((await step()).state).toBe('IMPORTING');

    w.state.imports.push({
      name: 'DownloadedAlbumsScan',
      status: 'started',
      message: '',
      queued: new Date(now).toISOString(),
      body: { path: '/srv/music/downloads/Tool - Lateralus (2001)' },
    });
    expect((await step()).state).toBe('IMPORTING');

    for (const command of w.state.imports) command.status = 'completed';
    w.state.lidarrAlbum = { monitored: true, trackCount: 13, trackFileCount: 13 };
    expect((await step()).state).toBe('INDEXING');
    expect(w.state.scanRequested).toBe(1);

    w.state.inNavidrome = true;
    const done = await step();
    expect(done).toMatchObject({ state: 'AVAILABLE', libraryAlbumId: 'al-lat', progress: 1 });
    expect(done.completedAt).not.toBeNull();

    const types = events.map((e) => e.type);
    expect(types[0]).toBe('download.created');
    expect(types).toContain('download.updated');
    expect(types.slice(-2)).toEqual(['download.completed', 'library.updated']);
    expect(events.at(-1)).toEqual({ type: 'library.updated', data: { libraryAlbumId: 'al-lat' } });
  });

  it('marks a failed Soularr import as failed', async () => {
    const w = world();
    await setup(w);
    const created = await request();
    w.state.slskd.transfers.push({
      username: 'peer',
      directory: '@@peer\\Tool\\Lateralus',
      filename: '01.flac',
      state: 'Completed, Succeeded',
      size: 1,
      bytesTransferred: 1,
      requestedAt: iso(now + 1000),
    });
    w.state.imports.push({
      name: 'DownloadedAlbumsScan',
      status: 'completed',
      message: 'Failed to import',
      queued: new Date(now + 2000).toISOString(),
      body: { path: '/srv/music/downloads/Tool - Lateralus (2001)' },
    });
    now += 60_000;
    await t.app.rev.monitor.tick();
    const failed = await job(created.id);
    expect(failed.state).toBe('FAILED');
    expect(failed.error).toBe("The downloaded files couldn't be added to your library.");
  });

  it('keeps jobs where they are while a service cannot be observed', async () => {
    const w = world();
    await setup(w, { slskdDown: true });
    const created = await request();
    now += 60_000;
    await t.app.rev.monitor.tick();
    expect((await job(created.id)).state).toBe('WAITING_FOR_SOULARR');
  });

  it('reports system status, inferring Soularr activity from slskd', async () => {
    const w = world();
    await setup(w);
    w.state.slskd.searches.push({
      id: 's',
      searchText: 'x',
      state: 'Completed',
      startedAt: iso(now - 5 * 60_000),
    });
    w.state.slskd.serverState = 'Connected';
    const res = await t.app.inject({ url: '/api/system/status', headers });
    expect(res.statusCode).toBe(200);
    const status = SystemStatus.parse(res.json());
    expect(status.services.navidrome.status).toBe('online');
    expect(status.services.lidarr).toMatchObject({ status: 'online', version: '2.9.6' });
    expect(status.services.slskd).toMatchObject({
      status: 'degraded',
      detail: 'Not connected to the Soulseek network',
    });
    expect(status.services.soularr.status).toBe('active');
    expect(status.library).toEqual({ artists: 1, albums: 4, tracks: 1234 });
    expect(status.downloads.active).toBe(0);
    expect(res.body).not.toContain(SLSKD.apiKey);
  });

  it('streams events over WebSocket to authenticated clients only', async () => {
    const w = world();
    await setup(w);
    await t.app.ready();

    await expect(t.app.injectWS('/api/events')).rejects.toThrow();

    const socket = await t.app.injectWS('/api/events', { headers });
    const received = new Promise<ServerEvent>((resolve) =>
      socket.on('message', (data: Buffer) => resolve(JSON.parse(data.toString()) as ServerEvent)),
    );
    await request();
    const first = await received;
    expect(first.type).toBe('download.created');
    socket.terminate();
  });
});
