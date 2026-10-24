-- Published Room v2 schema, preserved for the v2-to-v3 migration test.
CREATE TABLE IF NOT EXISTS `benchmark_runs` (`id` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS `benchmark_snapshots` (`generation` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`generation`));
CREATE TABLE IF NOT EXISTS `benchmark_current` (`id` INTEGER NOT NULL, `generation` INTEGER NOT NULL, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS `runtime_trials` (`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS `runtime_qualifications` (`id` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS `runtime_decisions` (`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT);
INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '08dbbdd57a3aa8a272d0a031e7cc65b9');
