package dev.chungjungsoo.gptmobile.data.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** SQLite's nullable connection key needs a separate constraint for built-in tools. */
object AgentToolBindingDatabase {
    val callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            enforceBuiltInUniqueness(db)
        }
    }

    fun enforceBuiltInUniqueness(db: SupportSQLiteDatabase) {
        // A staged backup already owns its migration/validation transaction.
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            // Equivalent built-ins have no connection or per-binding configuration. Keep
            // the newest row, with binding UID as a deterministic tie-breaker, on upgrade
            // and when opening an older backup before copying its rows into the live DB.
            db.execSQL(
                """
                DELETE FROM agent_tool_bindings
                WHERE connection_uid IS NULL AND EXISTS (
                    SELECT 1 FROM agent_tool_bindings AS retained
                    WHERE retained.connection_uid IS NULL
                        AND retained.profile_uid = agent_tool_bindings.profile_uid
                        AND retained.tool_name = agent_tool_bindings.tool_name
                        AND (retained.created_at > agent_tool_bindings.created_at
                            OR (retained.created_at = agent_tool_bindings.created_at
                                AND retained.binding_uid > agent_tool_bindings.binding_uid))
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS agent_tool_binding_builtin_insert_unique
                BEFORE INSERT ON agent_tool_bindings
                WHEN NEW.connection_uid IS NULL AND EXISTS (
                    SELECT 1 FROM agent_tool_bindings
                    WHERE connection_uid IS NULL
                        AND profile_uid = NEW.profile_uid AND tool_name = NEW.tool_name
                        AND binding_uid != NEW.binding_uid
                )
                BEGIN
                    SELECT RAISE(ABORT, 'UNIQUE constraint failed: agent_tool_bindings.profile_uid, agent_tool_bindings.tool_name');
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS agent_tool_binding_builtin_update_unique
                BEFORE UPDATE OF profile_uid, connection_uid, tool_name ON agent_tool_bindings
                WHEN NEW.connection_uid IS NULL AND EXISTS (
                    SELECT 1 FROM agent_tool_bindings
                    WHERE connection_uid IS NULL
                        AND profile_uid = NEW.profile_uid AND tool_name = NEW.tool_name
                        AND binding_uid != OLD.binding_uid
                )
                BEGIN
                    SELECT RAISE(ABORT, 'UNIQUE constraint failed: agent_tool_bindings.profile_uid, agent_tool_bindings.tool_name');
                END
                """.trimIndent()
            )
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }
}
