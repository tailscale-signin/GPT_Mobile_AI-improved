package dev.chungjungsoo.gptmobile.data.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/** Copies logical rows under the caller's Room transaction, including data still in the WAL. */
internal object CompleteBackupDatabase {
    fun snapshot(source: SupportSQLiteDatabase, target: File) {
        SQLiteDatabase.openOrCreateDatabase(target, null).use { copy ->
            copy.execSQL("DROP TABLE IF EXISTS android_metadata")
            copy.beginTransaction()
            try {
                source.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
                    while (cursor.moveToNext()) copy.execSQL(cursor.getString(0))
                }
                tables(source, includeMetadata = true).forEach { table ->
                    source.query("SELECT * FROM ${quote(table)}").use { rows -> copyRows(table, rows) { sql, values -> copy.execSQL(sql, values) } }
                }
                source.query("SELECT sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL").use { cursor ->
                    while (cursor.moveToNext()) copy.execSQL(cursor.getString(0))
                }
                copy.execSQL("DELETE FROM sqlite_sequence")
                source.query("SELECT name, seq FROM sqlite_sequence").use { rows ->
                    copyRows("sqlite_sequence", rows) { sql, values -> copy.execSQL(sql, values) }
                }
                copy.version = source.version
                copy.setTransactionSuccessful()
            } finally {
                copy.endTransaction()
            }
        }
    }

    fun validate(source: SupportSQLiteDatabase, destination: SupportSQLiteDatabase) {
        require(source.version == destination.version) { "Unsupported backup database version." }
        require(tables(source).toSet() == tables(destination).toSet()) { "Backup database tables do not match." }
        tables(destination).forEach { table ->
            fun columns(db: SupportSQLiteDatabase) = db.query("SELECT * FROM ${quote(table)} LIMIT 0").use { it.columnNames.toList() }
            require(columns(source) == columns(destination)) { "Backup database columns do not match." }
        }
        source.query("PRAGMA integrity_check").use { require(it.moveToFirst() && it.getString(0) == "ok") { "Damaged backup database." } }
        source.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "Backup contains broken data relationships." } }
    }

    fun restore(source: SupportSQLiteDatabase, destination: SupportSQLiteDatabase) {
        validate(source, destination)
        val order = dependencyOrder(destination)
        order.asReversed().forEach { destination.execSQL("DELETE FROM ${quote(it)}") }
        order.forEach { table ->
            source.query("SELECT * FROM ${quote(table)}").use { rows -> copyRows(table, rows) { sql, values -> destination.execSQL(sql, values) } }
        }
        destination.execSQL("DELETE FROM sqlite_sequence")
        source.query("SELECT name, seq FROM sqlite_sequence").use { rows ->
            copyRows("sqlite_sequence", rows) { sql, values -> destination.execSQL(sql, values) }
        }
        // History is portable; active jobs must never be replayed by service recovery.
        destination.execSQL("UPDATE agent_runs SET status = 'INTERRUPTED', terminal_error = 'BACKUP_RESTORED' WHERE status IN ('QUEUED', 'RUNNING')")
        destination.execSQL("UPDATE tool_events SET status = 'CANCELED', error = 'BACKUP_RESTORED' WHERE status IN ('PENDING', 'RUNNING')")
    }

    fun retainSections(database: SupportSQLiteDatabase, selection: CompleteBackupSelection) {
        val selectedTables = tablesFor(selection.normalized())
        val order = dependencyOrder(database)
        order.asReversed()
            .filterNot { it in selectedTables }
            .forEach { database.execSQL("DELETE FROM ${quote(it)}") }
        if (selectedTables.isEmpty()) {
            database.execSQL("DELETE FROM sqlite_sequence")
        } else {
            database.execSQL(
                "DELETE FROM sqlite_sequence WHERE name NOT IN (" +
                    selectedTables.joinToString(",") { "'${it.replace("'", "''")}'" } +
                    ")"
            )
        }
    }

    fun restoreSections(
        source: SupportSQLiteDatabase,
        destination: SupportSQLiteDatabase,
        selection: CompleteBackupSelection
    ) {
        validate(source, destination)
        val selectedTables = tablesFor(selection.normalized())
        if (selectedTables.isEmpty()) return

        val order = dependencyOrder(destination)
        order.asReversed()
            .filter { it in selectedTables }
            .forEach { destination.execSQL("DELETE FROM ${quote(it)}") }

        order.filter { it in selectedTables }.forEach { table ->
            source.query("SELECT * FROM ${quote(table)}").use { rows ->
                copyRows(table, rows) { sql, values -> destination.execSQL(sql, values) }
            }
        }

        selectedTables.forEach { table ->
            destination.execSQL("DELETE FROM sqlite_sequence WHERE name = ?", arrayOf<Any>(table))
        }
        source.query("SELECT name, seq FROM sqlite_sequence").use { rows ->
            while (rows.moveToNext()) {
                val name = rows.getString(0)
                if (name in selectedTables) {
                    destination.execSQL(
                        "INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)",
                        arrayOf<Any>(name, rows.getLong(1))
                    )
                }
            }
        }

        if (CompleteBackupSection.AGENT_HISTORY in selection.normalized().sections) {
            destination.execSQL(
                "UPDATE agent_runs SET status = 'INTERRUPTED', terminal_error = 'BACKUP_RESTORED' " +
                    "WHERE status IN ('QUEUED', 'RUNNING')"
            )
            destination.execSQL(
                "UPDATE tool_events SET status = 'CANCELED', error = 'BACKUP_RESTORED' " +
                    "WHERE status IN ('PENDING', 'RUNNING')"
            )
        }
    }

    private fun tablesFor(selection: CompleteBackupSelection): Set<String> {
        val sections = selection.normalized().sections
        return buildSet {
            if (CompleteBackupSection.CONVERSATIONS in sections) {
                add("chats_v2")
                add("messages_v2")
                add("chat_platform_model_v2")
            }
            if (CompleteBackupSection.PLATFORMS in sections) {
                add("platform_v2")
                add("provider_connections")
            }
            if (CompleteBackupSection.TOOLS in sections) {
                add("tool_connections")
                add("agent_tool_bindings")
            }
            if (CompleteBackupSection.LOCAL_MODELS in sections) {
                add("local_models")
            }
            if (CompleteBackupSection.AGENT_HISTORY in sections) {
                add("agent_runs")
                add("tool_events")
            }
            if (CompleteBackupSection.SETTINGS in sections) {
                // The queue cache is optional operational state, but keeping it with app
                // settings preserves existing complete-backup round trips.
                add("openrouter_batch_cache")
            }
        }
    }

    private fun tables(db: SupportSQLiteDatabase, includeMetadata: Boolean = false): List<String> = db.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (includeMetadata || name !in setOf("room_master_table", "android_metadata")) add(name)
            }
        }
    }

    private fun dependencyOrder(db: SupportSQLiteDatabase): List<String> {
        val tables = tables(db).toSet()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val result = mutableListOf<String>()
        fun visit(table: String) {
            if (table in visited) return
            require(visiting.add(table)) { "Unsupported circular database relationship." }
            db.query("PRAGMA foreign_key_list(${quote(table)})").use { rows ->
                while (rows.moveToNext()) rows.getString(2).takeIf { it in tables && it != table }?.let(::visit)
            }
            visiting.remove(table)
            visited.add(table)
            result.add(table)
        }
        tables.forEach(::visit)
        return result
    }

    private fun copyRows(table: String, rows: Cursor, insert: (String, Array<Any?>) -> Unit) {
        val sql = "INSERT INTO ${quote(table)} (${rows.columnNames.joinToString { quote(it) }}) VALUES (${rows.columnNames.joinToString { "?" }})"
        while (rows.moveToNext()) {
            insert(
                sql,
                Array(rows.columnCount) { column ->
                    when (rows.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> rows.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> rows.getDouble(column)
                        Cursor.FIELD_TYPE_BLOB -> rows.getBlob(column)
                        else -> rows.getString(column)
                    }
                }
            )
        }
    }

    private fun quote(name: String): String = "\"${name.replace("\"", "\"\"")}\""
}
