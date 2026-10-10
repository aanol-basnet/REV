CREATE TABLE `smart_playlist_saves` (
	`user_id` integer NOT NULL,
	`smart_id` text NOT NULL,
	`playlist_id` text NOT NULL,
	`saved_at` integer NOT NULL,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE UNIQUE INDEX `smart_playlist_saves_user_smart_idx` ON `smart_playlist_saves` (`user_id`,`smart_id`);