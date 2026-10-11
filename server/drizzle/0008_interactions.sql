CREATE TABLE `interactions` (
	`id` integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	`user_id` integer NOT NULL,
	`kind` text NOT NULL,
	`track_id` text NOT NULL,
	`at` integer NOT NULL,
	`artist_id` text,
	`artist` text NOT NULL,
	`genres` text NOT NULL,
	`recorded_at` integer NOT NULL,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE UNIQUE INDEX `interactions_user_kind_track_at_idx` ON `interactions` (`user_id`,`kind`,`track_id`,`at`);--> statement-breakpoint
CREATE INDEX `interactions_user_at_idx` ON `interactions` (`user_id`,`at`);