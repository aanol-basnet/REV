import { z } from 'zod';

export const LyricLine = z.object({
  startMs: z.number().int().nonnegative(),
  /** Empty for an instrumental gap. */
  text: z.string(),
});
export type LyricLine = z.infer<typeof LyricLine>;

export const LyricsSource = z.enum(['navidrome', 'lrclib']);
export type LyricsSource = z.infer<typeof LyricsSource>;

/**
 * Lyrics for a track. `synced` lines are pre-parsed (clients never parse LRC); `plain` is always
 * present when any lyrics exist. `status: "not_found"` is a definite answer (cached on the server).
 */
export const LyricsResponse = z.object({
  trackId: z.string(),
  status: z.enum(['found', 'instrumental', 'not_found']),
  source: LyricsSource.nullable(),
  synced: z.array(LyricLine).nullable(),
  plain: z.string().nullable(),
  fetchedAt: z.number().int(),
});
export type LyricsResponse = z.infer<typeof LyricsResponse>;
