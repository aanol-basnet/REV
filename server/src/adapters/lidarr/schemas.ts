import { z } from 'zod';

// Lidarr API v1 shapes (https://github.com/Lidarr/Lidarr/blob/develop/src/Lidarr.Api.V1/openapi.json).
// Only fields REV uses are declared; everything else is ignored.

const nullableString = z.string().nullish();

export const LidarrImage = z.object({
  coverType: z.string().nullish(),
  url: nullableString,
  remoteUrl: nullableString,
});

export const LidarrStatistics = z.object({
  trackFileCount: z.number().default(0),
  trackCount: z.number().default(0),
  totalTrackCount: z.number().default(0),
  sizeOnDisk: z.number().default(0),
});
export type LidarrStatistics = z.infer<typeof LidarrStatistics>;

export const LidarrArtist = z.object({
  id: z.number().default(0),
  artistName: nullableString,
  foreignArtistId: nullableString,
  disambiguation: nullableString,
  monitored: z.boolean().default(false),
  qualityProfileId: z.number().optional(),
  metadataProfileId: z.number().optional(),
  rootFolderPath: nullableString,
  images: z.array(LidarrImage).nullish(),
});
export type LidarrArtist = z.infer<typeof LidarrArtist>;

export const LidarrAlbum = z.object({
  id: z.number().default(0),
  title: nullableString,
  disambiguation: nullableString,
  artistId: z.number().default(0),
  foreignAlbumId: nullableString,
  monitored: z.boolean().default(false),
  albumType: nullableString,
  releaseDate: nullableString,
  artist: LidarrArtist.nullish(),
  statistics: LidarrStatistics.nullish(),
  images: z.array(LidarrImage).nullish(),
  remoteCover: nullableString,
});
export type LidarrAlbum = z.infer<typeof LidarrAlbum>;

export const LidarrAlbumList = z.array(LidarrAlbum);

export const LidarrSystemStatus = z.object({
  version: z.string(),
});
