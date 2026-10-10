import type { Album } from '@rev/schemas';
import type { NavidromeLibrary } from '../adapters/navidrome/library.js';
import { artistKey, titleKey } from './normalize.js';

/** The playable copy of an album in Navidrome, matched by normalized artist and title. */
export async function findLibraryAlbum(
  library: NavidromeLibrary,
  album: { title: string; artist: string },
): Promise<Album | null> {
  const { albums } = await library.search(album.title, { artists: 0, albums: 20, tracks: 0 });
  const artist = artistKey(album.artist);
  const title = titleKey(album.title);
  return albums.find((a) => artistKey(a.artist) === artist && titleKey(a.name) === title) ?? null;
}
