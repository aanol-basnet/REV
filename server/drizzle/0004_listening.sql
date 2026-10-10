CREATE TABLE `plays` (
	`id` integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	`user_id` integer NOT NULL,
	`track_id` text NOT NULL,
	`kind` text NOT NULL,
	`started_at` integer NOT NULL,
	`listened_ms` integer NOT NULL,
	`title` text NOT NULL,
	`artist` text NOT NULL,
	`artist_id` text,
	`album` text NOT NULL,
	`album_id` text,
	`cover_art_id` text,
	`year` integer,
	`genres` text NOT NULL,
	`duration_ms` integer NOT NULL,
	`recorded_at` integer NOT NULL,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE UNIQUE INDEX `plays_user_track_started_idx` ON `plays` (`user_id`,`track_id`,`started_at`);--> statement-breakpoint
CREATE INDEX `plays_user_started_idx` ON `plays` (`user_id`,`started_at`);