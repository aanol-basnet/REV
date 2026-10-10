CREATE TABLE `download_jobs` (
	`id` text PRIMARY KEY NOT NULL,
	`catalog_album_id` text NOT NULL,
	`catalog_artist_id` text,
	`title` text NOT NULL,
	`artist` text NOT NULL,
	`year` integer,
	`state` text NOT NULL,
	`progress` real,
	`error` text,
	`lidarr_album_id` integer,
	`lidarr_artist_id` integer,
	`added_to_lidarr` integer DEFAULT false NOT NULL,
	`monitored_by_rev` integer DEFAULT false NOT NULL,
	`library_album_id` text,
	`requested_by` integer,
	`started_at` integer NOT NULL,
	`updated_at` integer NOT NULL,
	`completed_at` integer,
	FOREIGN KEY (`requested_by`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE set null
);
--> statement-breakpoint
CREATE INDEX `download_jobs_catalog_album_idx` ON `download_jobs` (`catalog_album_id`);--> statement-breakpoint
CREATE INDEX `download_jobs_state_idx` ON `download_jobs` (`state`);