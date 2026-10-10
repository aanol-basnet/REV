import { fakeNavidrome, NAVIDROME, subsonicError, subsonicOk } from './fake-navidrome.js';

type Handlers = Parameters<typeof fakeNavidrome>[0];

export interface FakePlaylist {
  id: string;
  name: string;
  comment?: string;
  owner: string;
  songs: string[];
}

const song = (id: string) => ({
  id,
  title: `Song ${id}`,
  artist: 'Tool',
  album: 'Lateralus',
  duration: 100,
});

/** Navidrome's playlist endpoints over an in-memory store, with Subsonic's semantics. */
export function playlistServer(initial: FakePlaylist[] = [], extra: Handlers = {}) {
  const store = new Map(initial.map((p) => [p.id, { ...p, songs: [...p.songs] }]));
  let next = 1;
  const view = (p: FakePlaylist) => ({
    id: p.id,
    name: p.name,
    ...(p.comment ? { comment: p.comment } : {}),
    owner: p.owner,
    songCount: p.songs.length,
    duration: p.songs.length * 100,
    changed: '2026-10-10T10:00:00Z',
  });
  const nav = fakeNavidrome({
    getPlaylists: () => subsonicOk({ playlists: { playlist: [...store.values()].map(view) } }),
    getPlaylist: (params) => {
      const p = store.get(params.get('id') ?? '');
      if (!p) return subsonicError(70, 'not found');
      return subsonicOk({ playlist: { ...view(p), entry: p.songs.map(song) } });
    },
    createPlaylist: (params) => {
      const p = {
        id: `pl-${next++}`,
        name: params.get('name') ?? '',
        owner: NAVIDROME.username,
        songs: params.getAll('songId'),
      };
      store.set(p.id, p);
      return subsonicOk({ playlist: { ...view(p), entry: p.songs.map(song) } });
    },
    updatePlaylist: (params) => {
      const p = store.get(params.get('playlistId') ?? '');
      if (!p) return subsonicError(70, 'not found');
      if (p.owner !== NAVIDROME.username) return subsonicError(50, 'not authorized');
      if (params.has('name')) p.name = params.get('name') ?? '';
      if (params.has('comment')) p.comment = params.get('comment') ?? '';
      const remove = new Set(params.getAll('songIndexToRemove').map(Number));
      p.songs = p.songs.filter((_, i) => !remove.has(i)).concat(params.getAll('songIdToAdd'));
      return subsonicOk({});
    },
    deletePlaylist: (params) => {
      store.delete(params.get('id') ?? '');
      return subsonicOk({});
    },
    ...extra,
  });
  return { nav, store };
}
