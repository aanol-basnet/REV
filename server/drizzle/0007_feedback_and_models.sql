CREATE TABLE `feedback` (
	`id` integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	`user_id` integer NOT NULL,
	`track_id` text NOT NULL,
	`listen_started_at` integer NOT NULL,
	`kind` text NOT NULL,
	`at` integer NOT NULL,
	`position_ms` integer,
	`recorded_at` integer NOT NULL,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE UNIQUE INDEX `feedback_user_listen_kind_idx` ON `feedback` (`user_id`,`track_id`,`listen_started_at`,`kind`);--> statement-breakpoint
CREATE TABLE `skip_models` (
	`user_id` integer PRIMARY KEY NOT NULL,
	`model` text NOT NULL,
	`trained_at` integer NOT NULL,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
