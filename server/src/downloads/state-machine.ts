import type { DownloadJobState } from '@rev/schemas';

/**
 * Download job state machine.
 *
 *   REQUESTED → CHECKING → ADDED_TO_LIDARR → WAITING_FOR_SOULARR → SEARCHING → QUEUED
 *     → DOWNLOADING → IMPORTING → INDEXING → AVAILABLE
 *
 * Jobs move forward and may skip steps (observation is periodic, so intermediate states can be
 * missed). Within the acquisition loop (WAITING_FOR_SOULARR … DOWNLOADING) they may also move
 * back, because Soularr retries other sources after a failed transfer. Any non-terminal state can
 * end in FAILED, TIMEOUT or CANCELLED. AVAILABLE, FAILED, TIMEOUT and CANCELLED are terminal.
 */
const PIPELINE: DownloadJobState[] = [
  'REQUESTED',
  'CHECKING',
  'ADDED_TO_LIDARR',
  'WAITING_FOR_SOULARR',
  'SEARCHING',
  'QUEUED',
  'DOWNLOADING',
  'IMPORTING',
  'INDEXING',
  'AVAILABLE',
];

const ACQUISITION_LOOP = new Set<DownloadJobState>([
  'WAITING_FOR_SOULARR',
  'SEARCHING',
  'QUEUED',
  'DOWNLOADING',
]);
const ENDINGS = new Set<DownloadJobState>(['FAILED', 'TIMEOUT', 'CANCELLED']);

export const TERMINAL_STATES: ReadonlySet<DownloadJobState> = new Set([
  'AVAILABLE',
  'FAILED',
  'TIMEOUT',
  'CANCELLED',
]);

export function isTerminal(state: DownloadJobState): boolean {
  return TERMINAL_STATES.has(state);
}

export function canTransition(from: DownloadJobState, to: DownloadJobState): boolean {
  if (isTerminal(from) || from === to) return false;
  if (ENDINGS.has(to)) return true;
  if (ACQUISITION_LOOP.has(from) && ACQUISITION_LOOP.has(to)) return true;
  return PIPELINE.indexOf(to) > PIPELINE.indexOf(from);
}

export class InvalidTransitionError extends Error {
  override name = 'InvalidTransitionError';
  constructor(
    readonly from: DownloadJobState,
    readonly to: DownloadJobState,
  ) {
    super(`Invalid download job transition ${from} → ${to}`);
  }
}
