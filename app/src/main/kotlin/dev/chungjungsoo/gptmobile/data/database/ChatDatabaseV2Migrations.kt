package dev.chungjungsoo.gptmobile.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ChatDatabaseV2Migrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages_v2 ADD COLUMN image_paths TEXT NOT NULL DEFAULT '[]'")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages_v2 ADD COLUMN audio_path TEXT DEFAULT NULL")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_rooms_v2 ADD COLUMN system_prompt TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE chat_rooms_v2 ADD COLUMN temperature REAL DEFAULT NULL")
            db.execSQL("ALTER TABLE chat_rooms_v2 ADD COLUMN top_p REAL DEFAULT NULL")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages_v2 ADD COLUMN file_attachments TEXT NOT NULL DEFAULT '[]'")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_rooms_v2 ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_rooms_v2 ADD COLUMN category TEXT DEFAULT NULL")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_rooms_v2 ADD COLUMN model_routing_rules TEXT NOT NULL DEFAULT '[]'")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages_v2 ADD COLUMN revisions TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE messages_v2 ADD COLUMN current_revision_index INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_rooms_v2 ADD COLUMN is_archived INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_rooms_v2 ADD COLUMN export_format TEXT DEFAULT NULL")
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_rooms_v2 ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages_v2 ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13
    )
}
