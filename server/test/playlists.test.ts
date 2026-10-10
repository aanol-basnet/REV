import { afterEach, describe, expect, it } from 'vitest';
import { AddPlaylistTracksResult, PlaylistDetail, PlaylistList } from '@rev/schemas';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { NAVIDROME } from './fake-navidrome.js';
import { type FakePlaylist, playlistServer } from './fake-playlists.js';

describe('playlist editing', () => {
  let t: TestContext;
  afterEach(async () => {
    await t.close();
  });

  async function setup(initial: FakePlaylist[] = []) {
    const server = playlistServer(initial);
    t = await createTestApp({ navidrome: NAVIDROME, fetch: server.nav.fetch });
    return { ...server, headers: await signedInHeaders(t) };
  }

  it('creates a playlist with songs and a description, owned by REV', async () => {
    const { headers, store, nav } = await setup();
    const res = await t.app.inject({
      method: 'POST',
      url: '/api/library/playlists',
      headers,
      payload: { name: '  Late night  ', comment: 'for the drive home', trackIds: ['t1', 't2'] },
    });
    expect(res.statusCode).toBe(201);
    const created = PlaylistDetail.parse(res.json());
    expect(created).toMatchObject({
      name: 'Late night',
      comment: 'for the drive home',
      canEdit: true,
      trackCount: 2,
    });
    expect(created.tracks.map((t) => t.id)).toEqual(['t1', 't2']);
    expect(store.get(created.id)?.songs).toEqual(['t1', 't2']);

    // Song lists go as a form body, not in the URL.
    const create = nav.calls.find((c) => c.endpoint === 'createPlaylist');
    expect(create?.params.getAll('songId')).toEqual(['t1', 't2']);

    const empty = await t.app.inject({
      method: 'POST',
      url: '/api/library/playlists',
      headers,
      payload: { name: 'Empty' },
    });
    expect(empty.statusCode).toBe(201);
    expect(PlaylistDetail.parse(empty.json()).tracks).toEqual([]);
  });

  it('renames, adds without duplicates, reorders, removes and deletes', async () => {
    const { headers, store } = await setup([
      { id: 'p1', name: 'Mine', owner: NAVIDROME.username, songs: ['a', 'b', 'c'] },
    ]);

    const renamed = await t.app.inject({
      method: 'PATCH',
      url: '/api/library/playlists/p1',
      headers,
      payload: { name: 'Gym' },
    });
    expect(PlaylistDetail.parse(renamed.json()).name).toBe('Gym');

    const added = await t.app.inject({
      method: 'POST',
      url: '/api/library/playlists/p1/tracks',
      headers,
      payload: { trackIds: ['b', 'd', 'd', 'e'] },
    });
    const result = AddPlaylistTracksResult.parse(added.json());
    expect(result).toMatchObject({ added: 2, skipped: 2 });
    expect(store.get('p1')?.songs).toEqual(['a', 'b', 'c', 'd', 'e']);

    const again = await t.app.inject({
      method: 'POST',
      url: '/api/library/playlists/p1/tracks',
      headers,
      payload: { trackIds: ['a'], skipDuplicates: false },
    });
    expect(AddPlaylistTracksResult.parse(again.json()).added).toBe(1);

    const reordered = await t.app.inject({
      method: 'PUT',
      url: '/api/library/playlists/p1/tracks',
      headers,
      payload: { trackIds: ['e', 'a', 'c'] },
    });
    expect(PlaylistDetail.parse(reordered.json()).tracks.map((t) => t.id)).toEqual(['e', 'a', 'c']);

    const cleared = await t.app.inject({
      method: 'PUT',
      url: '/api/library/playlists/p1/tracks',
      headers,
      payload: { trackIds: [] },
    });
    expect(PlaylistDetail.parse(cleared.json()).tracks).toEqual([]);

    const deleted = await t.app.inject({
      method: 'DELETE',
      url: '/api/library/playlists/p1',
      headers,
    });
    expect(deleted.statusCode).toBe(204);
    expect(store.has('p1')).toBe(false);
  });

  it('shows other users’ playlists as read-only and refuses to change them', async () => {
    const { headers, store } = await setup([
      { id: 'p1', name: 'Mine', owner: NAVIDROME.username, songs: [] },
      { id: 'p2', name: 'Shared', owner: 'alex', songs: ['a'] },
    ]);
    const list = PlaylistList.parse(
      (await t.app.inject({ url: '/api/library/playlists', headers })).json(),
    );
    expect(list.playlists.map((p) => [p.name, p.canEdit])).toEqual([
      ['Mine', true],
      ['Shared', false],
    ]);

    for (const req of [
      { method: 'PATCH' as const, url: '/api/library/playlists/p2', payload: { name: 'x' } },
      {
        method: 'POST' as const,
        url: '/api/library/playlists/p2/tracks',
        payload: { trackIds: ['b'] },
      },
      {
        method: 'PUT' as const,
        url: '/api/library/playlists/p2/tracks',
        payload: { trackIds: [] },
      },
      { method: 'DELETE' as const, url: '/api/library/playlists/p2' },
    ]) {
      const res = await t.app.inject({ ...req, headers });
      expect(res.statusCode).toBe(403);
      expect(res.json()).toMatchObject({ error: { code: 'PLAYLIST_READ_ONLY' } });
    }
    expect(store.get('p2')).toMatchObject({ name: 'Shared', songs: ['a'] });
  });

  it('duplicates any playlist, read-only ones included, into an editable copy', async () => {
    const { headers, store } = await setup([
      { id: 'p2', name: 'Shared', comment: 'from alex', owner: 'alex', songs: ['a', 'b', 'a'] },
    ]);
    const res = await t.app.inject({
      method: 'POST',
      url: '/api/library/playlists/p2/duplicate',
      headers,
    });
    expect(res.statusCode).toBe(201);
    const copy = PlaylistDetail.parse(res.json());
    expect(copy).toMatchObject({ name: 'Shared (copy)', comment: 'from alex', canEdit: true });
    expect(store.get(copy.id)?.songs).toEqual(['a', 'b', 'a']);
    expect(store.get('p2')?.songs).toEqual(['a', 'b', 'a']);

    const named = await t.app.inject({
      method: 'POST',
      url: '/api/library/playlists/p2/duplicate',
      headers,
      payload: { name: 'Mine now' },
    });
    expect(PlaylistDetail.parse(named.json()).name).toBe('Mine now');
  });

  it('validates input', async () => {
    const { headers } = await setup([
      { id: 'p1', name: 'Mine', owner: NAVIDROME.username, songs: [] },
    ]);
    const bad = [
      { method: 'POST' as const, url: '/api/library/playlists', payload: { name: '   ' } },
      {
        method: 'POST' as const,
        url: '/api/library/playlists',
        payload: { name: 'x', trackIds: ['../etc'] },
      },
      { method: 'PATCH' as const, url: '/api/library/playlists/p1', payload: {} },
      {
        method: 'POST' as const,
        url: '/api/library/playlists/p1/tracks',
        payload: { trackIds: [] },
      },
    ];
    for (const req of bad) expect((await t.app.inject({ ...req, headers })).statusCode).toBe(400);
    expect(
      (await t.app.inject({ method: 'DELETE', url: '/api/library/playlists/nope', headers }))
        .statusCode,
    ).toBe(404);
    expect(
      (
        await t.app.inject({
          method: 'POST',
          url: '/api/library/playlists',
          payload: { name: 'x' },
        })
      ).statusCode,
    ).toBe(401);
  });
});
