CREATE TABLE `lyrics` (
	`track_id` text PRIMARY KEY NOT NULL,
	`status` text NOT NULL,
	`source` text,
	`source_id` text,
	`plain_lyrics` text,
	`synced_lyrics` text,
	`is_instrumental` integer DEFAULT false NOT NULL,
	`fetched_at` integer NOT NULL,
	`updated_at` integer NOT NULL
);
