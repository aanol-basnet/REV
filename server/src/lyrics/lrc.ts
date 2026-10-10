/** One line of synchronized lyrics. Empty `text` marks an instrumental gap. */
export interface LyricLine {
  startMs: number;
  text: string;
}

const TIMESTAMP = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;
const OFFSET_TAG = /^\[offset:\s*([+-]?\d+)\s*\]$/i;
const METADATA_TAG = /^\[[a-z]+:.*\]$/i;
/** Enhanced LRC word timings: <00:12.34> */
const WORD_TIMING = /<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>/g;

/**
 * Parses LRC into time-ordered lines. Handles several timestamps per line ("[00:10.00][01:20.00]"),
 * the [offset:] tag (positive offset = lyrics appear earlier), metadata tags and word-level timings.
 * Returns an empty list when the text has no timestamps (i.e. it isn't synced).
 */
export function parseLrc(lrc: string): LyricLine[] {
  let offsetMs = 0;
  const lines: LyricLine[] = [];
  for (const raw of lrc.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    const offset = OFFSET_TAG.exec(line);
    if (offset) {
      offsetMs = Number(offset[1]);
      continue;
    }
    const stamps = [...line.matchAll(TIMESTAMP)];
    if (stamps.length === 0) {
      if (METADATA_TAG.test(line)) continue;
      continue; // stray text without a time can't be placed
    }
    const text = line.replace(TIMESTAMP, '').replace(WORD_TIMING, '').replace(/\s+/g, ' ').trim();
    for (const stamp of stamps) {
      const minutes = Number(stamp[1]);
      const seconds = Number(stamp[2]);
      const fraction = stamp[3] ?? '0';
      // ".5" = 500 ms, ".05" = 50 ms, ".005" = 5 ms
      const ms = Number(fraction.padEnd(3, '0').slice(0, 3));
      lines.push({ startMs: minutes * 60_000 + seconds * 1000 + ms, text });
    }
  }
  return lines
    .map((l) => ({ startMs: Math.max(0, l.startMs - offsetMs), text: l.text }))
    .sort((a, b) => a.startMs - b.startMs);
}

/** Plain text from synced lines, for clients or providers that only have LRC. */
export function plainFromLines(lines: LyricLine[]): string {
  return lines
    .map((l) => l.text)
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}
