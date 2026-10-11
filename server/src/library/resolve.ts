import type { Track } from '@rev/schemas';
import { UpstreamError } from '../adapters/errors.js';

/** Track lookups in flight at once. */
const LOOKUP_CONCURRENCY = 8;

/** The tracks for [ids], in order, leaving out those no longer in the library. */
export async function resolveTracks(
  ids: string[],
  track: (id: string) => Promise<Track>,
): Promise<Track[]> {
  const found: (Track | null)[] = new Array<Track | null>(ids.length).fill(null);
  let next = 0;
  const worker = async () => {
    while (next < ids.length) {
      const i = next++;
      found[i] = await track(ids[i] as string).catch((err: unknown) => {
        if (err instanceof UpstreamError && err.kind === 'not_found') return null;
        throw err;
      });
    }
  };
  await Promise.all(Array.from({ length: Math.min(LOOKUP_CONCURRENCY, ids.length) }, worker));
  return found.filter((t): t is Track => t !== null);
}
