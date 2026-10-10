import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, CatalogAlbumStatus, DownloadJob, DownloadJobList } from '@rev/schemas';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { fakeNavidrome, NAVIDROME, subsonicOk } from './fake-navidrome.js';
import { fakeLidarr, LIDARR, routeFetch } from './fake-services.js';
import { canTransition } from '../src/downloads/state-machine.js';

const OPIATE = 'aaaaaaaa-0000-4000-8000-000000000005';
const TOOL = '66fc5bf8-daa4-4241-b378-9bc9077939d2';

const lookupOpiate = {
  id: 0,
  title: 'Opiate',
  foreignAlbumId: OPIATE,
  albumType: 'EP',
  releaseDate: '1992-03-10T00:00:00Z',
  releases: [{ foreignReleaseId: 'rel-1', title: 'Opiate' }],
  artist: { artistName: 'Tool', foreignArtistId: TOOL, images: [] },
};

const rootFolders = [
  {
    path: '/srv/music/library',
    accessible: true,
    defaultQualityProfileId: 2,
    defaultMetadataProfileId: 3,
  },
];

interface LidarrWorld {
  existingAlbum?: object | null;
  artistMonitored?: boolean;
  failAdd?: boolean;
}

function lidarr(world: LidarrWorld = {}) {
  return fakeLidarr({
    'GET album/lookup': () => [lookupOpiate],
    'GET artist/lookup': () => [],
    // Both the per-album query and the full library index see the same single album (or none).
    'GET album': () => (world.existingAlbum ? [world.existingAlbum] : []),
    'GET rootfolder': () => rootFolders,
    'POST album': () => {
      if (world.failAdd) throw new Error('boom');
      return { id: 77, artistId: 9, title: 'Opiate' };
    },
    'GET artist/9': () => ({ id: 9, artistName: 'Tool', monitored: world.artistMonitored ?? true }),
    'PUT artist/editor': () => ({}),
    'PUT album/monitor': () => ({}),
    'POST command': () => ({ id: 1, name: 'AlbumSearch' }),
  });
}

/** Wraps a fake so a thrown handler becomes a 500, like a real server error. */
function serverErrors(f: typeof fetch): typeof fetch {
  return (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
    try {
      return await f(input, init);
    } catch {
      return new Response('Internal error: stack trace with secrets', { status: 500 });
    }
  }) as typeof fetch;
}

function navidrome(albums: object[] = []) {
  return fakeNavidrome({
    search3: () => subsonicOk({ searchResult3: { album: albums } }),
  });
}

describe('album requests', () => {
  let t: TestContext;
  let headers: { authorization: string };

  afterEach(async () => {
    await t.close();
  });

  async function setup(
    lid: ReturnType<typeof lidarr>,
    nav = navidrome(),
    opts: { lidarr?: boolean } = {},
  ) {
    t = await createTestApp({
      navidrome: NAVIDROME,
      ...(opts.lidarr === false ? {} : { lidarr: LIDARR }),
      fetch: routeFetch({
        'navidrome.test:4533': nav.fetch,
        'lidarr.test:8686': serverErrors(lid.fetch),
      }),
    });
    headers = await signedInHeaders(t);
  }

  const request = () =>
    t.app.inject({
      method: 'POST',
      url: '/api/downloads',
      headers,
      payload: { catalogAlbumId: OPIATE },
    });

  it('adds a new album and its artist to Lidarr, monitoring only that album', async () => {
    const lid = lidarr();
    await setup(lid);

    const res = await request();
    expect(res.statusCode).toBe(201);
    const job = DownloadJob.parse(res.json());
    expect(job).toMatchObject({
      state: 'ADDED_TO_LIDARR',
      title: 'Opiate',
      artist: 'Tool',
      year: 1992,
      error: null,
    });

    const add = lid.calls.find((c) => c.method === 'POST' && c.path === 'album');
    expect(add?.body).toMatchObject({
      foreignAlbumId: OPIATE,
      monitored: true,
      releases: [{ foreignReleaseId: 'rel-1' }], // lookup data is posted back intact
      addOptions: { addType: 'manual', searchForNewAlbum: true },
      artist: {
        foreignArtistId: TOOL,
        monitored: true,
        monitorNewItems: 'none',
        rootFolderPath: '/srv/music/library',
        qualityProfileId: 2,
        metadataProfileId: 3,
        addOptions: { monitor: 'none', searchForMissingAlbums: false },
      },
    });
    expect(lid.calls.find((c) => c.path === 'album/lookup')?.url.searchParams.get('term')).toBe(
      `lidarr:${OPIATE}`,
    );
    expect(lid.calls.some((c) => c.path === 'artist/editor')).toBe(false);
  });

  it('never creates two jobs for the same album, even for concurrent requests', async () => {
    const lid = lidarr();
    await setup(lid);
    const [a, b] = await Promise.all([request(), request()]);
    const c = await request();
    const ids = [a, b, c].map((r) => DownloadJob.parse(r.json()).id);
    expect(new Set(ids).size).toBe(1);
    expect(
      lid.calls.filter((call) => call.method === 'POST' && call.path === 'album'),
    ).toHaveLength(1);
  });

  it('monitors an album Lidarr already tracks and asks it to search', async () => {
    const lid = lidarr({
      existingAlbum: {
        id: 55,
        artistId: 9,
        title: 'Opiate',
        foreignAlbumId: OPIATE,
        monitored: false,
        releaseDate: '1992-03-10',
        artist: { artistName: 'Tool', foreignArtistId: TOOL },
        statistics: { trackFileCount: 0, trackCount: 6 },
      },
      artistMonitored: false,
    });
    await setup(lid);

    const job = DownloadJob.parse((await request()).json());
    expect(job.state).toBe('ADDED_TO_LIDARR');
    const writes = lid.calls
      .filter((c) => c.method !== 'GET')
      .map((c) => [c.method, c.path, c.body]);
    expect(writes).toEqual([
      ['PUT', 'artist/editor', { artistIds: [9], monitored: true }],
      ['PUT', 'album/monitor', { albumIds: [55], monitored: true }],
      ['POST', 'command', { name: 'AlbumSearch', albumIds: [55] }],
    ]);
  });

  it('skips straight to indexing when Lidarr already has every file', async () => {
    await setup(
      lidarr({
        existingAlbum: {
          id: 55,
          artistId: 9,
          title: 'Opiate',
          foreignAlbumId: OPIATE,
          monitored: true,
          statistics: { trackFileCount: 6, trackCount: 6 },
        },
      }),
    );
    expect(DownloadJob.parse((await request()).json()).state).toBe('INDEXING');
  });

  it('completes immediately when the album is already playable', async () => {
    const lid = lidarr();
    await setup(
      lid,
      navidrome([
        { id: 'al-op', name: 'Opiate (Remastered)', artist: 'Tool', songCount: 6, duration: 1600 },
      ]),
    );
    const job = DownloadJob.parse((await request()).json());
    expect(job).toMatchObject({ state: 'AVAILABLE', libraryAlbumId: 'al-op', progress: 1 });
    expect(job.completedAt).not.toBeNull();
    expect(lid.calls.filter((c) => c.method !== 'GET')).toEqual([]);
  });

  it('records a failure with a user-facing reason only', async () => {
    await setup(lidarr({ failAdd: true }));
    const job = DownloadJob.parse((await request()).json());
    expect(job.state).toBe('FAILED');
    expect(job.error).toBe("The library manager isn't responding. Try again later.");
    expect(JSON.stringify(job)).not.toContain('secrets');

    // A failed attempt doesn't block a retry.
    const retry = DownloadJob.parse((await request()).json());
    expect(retry.id).not.toBe(job.id);
  });

  it('cancelling withdraws only what REV changed, and is idempotent', async () => {
    const lid = lidarr();
    await setup(lid);
    const job = DownloadJob.parse((await request()).json());

    const res = await t.app.inject({ method: 'DELETE', url: `/api/downloads/${job.id}`, headers });
    expect(DownloadJob.parse(res.json()).state).toBe('CANCELLED');
    expect(lid.calls.at(-1)).toMatchObject({
      method: 'PUT',
      path: 'album/monitor',
      body: { albumIds: [77], monitored: false },
    });

    const again = await t.app.inject({
      method: 'DELETE',
      url: `/api/downloads/${job.id}`,
      headers,
    });
    expect(DownloadJob.parse(again.json()).state).toBe('CANCELLED');
    expect(lid.calls.filter((c) => c.path === 'album/monitor')).toHaveLength(1);
  });

  it('clears finished jobs from the history, leaving active ones', async () => {
    await setup(lidarr());
    const first = DownloadJob.parse((await request()).json());
    await t.app.inject({ method: 'DELETE', url: `/api/downloads/${first.id}`, headers });
    const second = DownloadJob.parse((await request()).json());
    await t.app.inject({ method: 'DELETE', url: `/api/downloads/${second.id}`, headers });
    const active = DownloadJob.parse((await request()).json());

    expect((await t.app.inject({ method: 'DELETE', url: '/api/downloads' })).statusCode).toBe(401);
    const res = await t.app.inject({ method: 'DELETE', url: '/api/downloads', headers });
    expect(res.json()).toEqual({ cleared: 2 });

    const left = DownloadJobList.parse(
      (await t.app.inject({ url: '/api/downloads', headers })).json(),
    );
    expect(left.jobs.map((j) => j.id)).toEqual([active.id]);
  });

  it('lists jobs, filters active ones, and reflects them in album status', async () => {
    await setup(lidarr());
    const job = DownloadJob.parse((await request()).json());

    const all = DownloadJobList.parse(
      (await t.app.inject({ url: '/api/downloads', headers })).json(),
    );
    expect(all.jobs.map((j) => j.id)).toEqual([job.id]);
    const active = DownloadJobList.parse(
      (await t.app.inject({ url: '/api/downloads?active=true', headers })).json(),
    );
    expect(active.jobs).toHaveLength(1);

    const status = CatalogAlbumStatus.parse(
      (await t.app.inject({ url: `/api/catalog/albums/${OPIATE}`, headers })).json(),
    );
    expect(status).toMatchObject({
      title: 'Opiate',
      artist: 'Tool',
      availability: 'REQUESTED',
      libraryAlbumId: null,
    });
    expect(status.job?.id).toBe(job.id);
  });

  it('rejects bad input and reports when downloading is not set up', async () => {
    await setup(lidarr(), navidrome(), { lidarr: false });
    const bad = await t.app.inject({
      method: 'POST',
      url: '/api/downloads',
      headers,
      payload: { catalogAlbumId: 'nope' },
    });
    expect(bad.statusCode).toBe(400);
    const off = await request();
    expect(off.statusCode).toBe(503);
    expect(ApiError.parse(off.json()).error.code).toBe('LIDARR_NOT_CONFIGURED');
    expect(
      (await t.app.inject({ url: '/api/downloads/00000000-0000-4000-8000-000000000000', headers }))
        .statusCode,
    ).toBe(404);
    expect(
      (
        await t.app.inject({
          method: 'POST',
          url: '/api/downloads',
          payload: { catalogAlbumId: OPIATE },
        })
      ).statusCode,
    ).toBe(401);
  });
});

describe('download job state machine', () => {
  it('moves forward, may skip steps, and loops within acquisition', () => {
    expect(canTransition('REQUESTED', 'CHECKING')).toBe(true);
    expect(canTransition('ADDED_TO_LIDARR', 'DOWNLOADING')).toBe(true);
    expect(canTransition('DOWNLOADING', 'SEARCHING')).toBe(true);
    expect(canTransition('INDEXING', 'AVAILABLE')).toBe(true);
    expect(canTransition('IMPORTING', 'DOWNLOADING')).toBe(false);
    expect(canTransition('CHECKING', 'REQUESTED')).toBe(false);
  });

  it('can end from any active state, never from a terminal one', () => {
    for (const end of ['FAILED', 'TIMEOUT', 'CANCELLED'] as const) {
      expect(canTransition('SEARCHING', end)).toBe(true);
    }
    for (const terminal of ['AVAILABLE', 'FAILED', 'TIMEOUT', 'CANCELLED'] as const) {
      expect(canTransition(terminal, 'REQUESTED')).toBe(false);
      expect(canTransition(terminal, 'CANCELLED')).toBe(false);
    }
  });
});
