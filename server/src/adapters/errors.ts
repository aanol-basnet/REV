/** A failure talking to a backend service (Navidrome, Lidarr, slskd). Mapped to safe HTTP errors. */
export class UpstreamError extends Error {
  override name = 'UpstreamError';
  constructor(
    readonly service: 'navidrome' | 'lidarr' | 'slskd' | 'coverart' | 'lrclib',
    /** unavailable: no/bad connection; auth: REV's credentials rejected; not_found; protocol: unexpected response. */
    readonly kind: 'unavailable' | 'auth' | 'not_found' | 'protocol',
    message: string,
    options?: { cause?: unknown },
  ) {
    super(message, options);
  }
}
