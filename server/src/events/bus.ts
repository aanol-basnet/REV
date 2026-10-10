import type { DownloadJobState, ServerEvent } from '@rev/schemas';
import { type JobRow, toApi } from '../downloads/jobs.js';

type Listener = (event: ServerEvent) => void;

/** In-process fan-out of server events to WebSocket clients (and anything else that listens). */
export class EventBus {
  private readonly listeners = new Set<Listener>();

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  publish(event: ServerEvent): void {
    for (const listener of this.listeners) {
      try {
        listener(event);
      } catch {
        // One broken subscriber must not stop the others.
      }
    }
  }

  get subscriberCount(): number {
    return this.listeners.size;
  }

  /** Translates a job change into the events clients care about. */
  publishJobChange(job: JobRow, previous: JobRow | null): void {
    const data = toApi(job);
    if (!previous) {
      this.publish({ type: 'download.created', data });
      return;
    }
    const state = job.state as DownloadJobState;
    if (state !== previous.state && state === 'AVAILABLE') {
      this.publish({ type: 'download.completed', data });
      if (job.libraryAlbumId)
        this.publish({ type: 'library.updated', data: { libraryAlbumId: job.libraryAlbumId } });
      return;
    }
    if (state !== previous.state && (state === 'FAILED' || state === 'TIMEOUT')) {
      this.publish({ type: 'download.failed', data });
      return;
    }
    this.publish({ type: 'download.updated', data });
  }
}
