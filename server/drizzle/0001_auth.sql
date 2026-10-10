CREATE TABLE `sessions` (
	`id` text PRIMARY KEY NOT NULL,
	`user_id` integer NOT NULL,
	`device_name` text,
	`access_token_hash` text NOT NULL,
	`access_token_expires_at` integer NOT NULL,
	`refresh_token_hash` text NOT NULL,
	`refresh_token_expires_at` integer NOT NULL,
	`previous_refresh_token_hash` text,
	`rotated_at` integer,
	`created_at` integer NOT NULL,
	`last_used_at` integer NOT NULL,
	`revoked_at` integer,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE UNIQUE INDEX `sessions_access_token_hash_unique` ON `sessions` (`access_token_hash`);--> statement-breakpoint
CREATE UNIQUE INDEX `sessions_refresh_token_hash_unique` ON `sessions` (`refresh_token_hash`);--> statement-breakpoint
CREATE INDEX `sessions_user_id_idx` ON `sessions` (`user_id`);--> statement-breakpoint
CREATE INDEX `sessions_previous_refresh_idx` ON `sessions` (`previous_refresh_token_hash`) WHERE "sessions"."previous_refresh_token_hash" IS NOT NULL;--> statement-breakpoint
CREATE TABLE `users` (
	`id` integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	`username` text NOT NULL,
	`password_hash` text NOT NULL,
	`created_at` integer NOT NULL,
	`updated_at` integer NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `users_username_unique` ON `users` (`username`);