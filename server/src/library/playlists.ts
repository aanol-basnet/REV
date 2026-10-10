import type {
  AddPlaylistTracksRequest,
  AddPlaylistTracksResult,
  CreatePlaylistRequest,
  PlaylistDetail,
  UpdatePlaylistRequest,
} from '@rev/schemas';
import type { NavidromeLibrary } from '../adapters/navidrome/library.js';
import { HttpError } from '../api/errors.js';

/**
 * Playlist editing. Playlists live in Navidrome, so what you make in REV also shows up in Navidrome
 * and other Subsonic apps. Only playlists of REV's Navidrome account can be changed; other users'
 * public playlists are read-only (Navidrome would refuse anyway, with a confusing auth error).
 */
export class PlaylistService {
  constructor(private readonly library: NavidromeLibrary) {}

  async create(request: CreatePlaylistRequest): Promise<PlaylistDetail> {
    const id = await this.library.createPlaylist(request.name, request.trackIds);
    if (request.comment) await this.library.updatePlaylist(id, { comment: request.comment });
    return this.library.playlist(id);
  }

  /** A new playlist with the same songs, order and description; works on read-only ones too. */
  async duplicate(id: string, name?: string): Promise<PlaylistDetail> {
    const source = await this.library.playlist(id);
    return this.create({
      name: (name ?? `${source.name} (copy)`).slice(0, 200),
      ...(source.comment ? { comment: source.comment } : {}),
      trackIds: source.tracks.map((t) => t.id),
    });
  }

  async update(id: string, request: UpdatePlaylistRequest): Promise<PlaylistDetail> {
    await this.editable(id);
    await this.library.updatePlaylist(id, {
      ...(request.name !== undefined ? { name: request.name } : {}),
      ...(request.comment !== undefined ? { comment: request.comment } : {}),
    });
    return this.library.playlist(id);
  }

  async add(id: string, request: AddPlaylistTracksRequest): Promise<AddPlaylistTracksResult> {
    const current = await this.editable(id);
    let toAdd = request.trackIds;
    if (request.skipDuplicates) {
      const present = new Set(current.tracks.map((t) => t.id));
      toAdd = request.trackIds.filter((trackId) => {
        if (present.has(trackId)) return false;
        present.add(trackId); // also once per request
        return true;
      });
    }
    if (toAdd.length > 0) await this.library.updatePlaylist(id, { addTrackIds: toAdd });
    return {
      playlist: toAdd.length > 0 ? await this.library.playlist(id) : current,
      added: toAdd.length,
      skipped: request.trackIds.length - toAdd.length,
    };
  }

  /** Replaces the song list: how the app removes and reorders songs. */
  async setTracks(id: string, trackIds: readonly string[]): Promise<PlaylistDetail> {
    const current = await this.editable(id);
    await this.library.updatePlaylist(id, {
      removeIndexes: current.tracks.map((_, index) => index),
      addTrackIds: trackIds,
    });
    return this.library.playlist(id);
  }

  async delete(id: string): Promise<void> {
    await this.editable(id);
    await this.library.deletePlaylist(id);
  }

  private async editable(id: string): Promise<PlaylistDetail> {
    const playlist = await this.library.playlist(id);
    if (!playlist.canEdit) {
      throw new HttpError(
        403,
        'PLAYLIST_READ_ONLY',
        `"${playlist.name}" belongs to ${playlist.owner ?? 'another user'} in Navidrome, so REV can't change it`,
      );
    }
    return playlist;
  }
}
