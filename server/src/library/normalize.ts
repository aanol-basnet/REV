/**
 * Text normalization for matching the same release across services (Navidrome, Lidarr, lyrics
 * providers) whose metadata differs in punctuation, case, accents and edition suffixes.
 */

/** Parenthetical/bracketed or dash-suffixed qualifiers that don't change which work it is. */
const EDITION_WORDS =
  /\b(remaster(ed)?|deluxe|expanded|edition|anniversary|bonus|special|collector'?s|reissue|version|mono|stereo|explicit|clean)\b/i;

/** Lowercase, accent-free, punctuation-free, single-spaced. */
export function basicNormalize(text: string): string {
  return text
    .normalize('NFKD')
    .replace(/\p{M}/gu, '') // combining marks (accents)
    .toLowerCase()
    .replace(/[’‘`´]/g, "'")
    .replace(/&/g, ' and ')
    .replace(/'/g, '')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim()
    .replace(/\s+/g, ' ');
}

/** Removes featuring credits: "Song (feat. X)", "Song ft. X", "Song [featuring X]". */
export function stripFeaturing(text: string): string {
  return text
    .replace(/\s*[([]\s*(feat\.?|ft\.?|featuring)\s[^)\]]*[)\]]/gi, '')
    .replace(/\s+(feat\.?|ft\.?|featuring)\s.*$/i, '')
    .trim();
}

/** Removes edition qualifiers: "Ænima (2021 Remaster)", "OK Computer - Deluxe Edition". */
export function stripEditions(text: string): string {
  return text
    .replace(/\s*[([][^)\]]*[)\]]/g, (segment) => (EDITION_WORDS.test(segment) ? '' : segment))
    .replace(/\s+[-–—]\s+[^-–—]*$/, (suffix) => (EDITION_WORDS.test(suffix) ? '' : suffix))
    .trim();
}

/** Key for comparing album or track titles. */
export function titleKey(title: string): string {
  return basicNormalize(stripEditions(stripFeaturing(title)));
}

/** Key for comparing artist names. A leading "The" is ignored ("The Beatles" = "Beatles"). */
export function artistKey(name: string): string {
  return basicNormalize(name).replace(/^the /, '');
}
