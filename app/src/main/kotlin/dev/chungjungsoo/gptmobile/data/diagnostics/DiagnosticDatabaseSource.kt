package dev.chungjungsoo.gptmobile.data.diagnostics

import android.database.Cursor
import android.database.SQLException
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A diagnostic projection, not a database backup. SQLite measures each row before its
 * text is loaded. At most one bounded row is decoded and redacted at a time; Combined
 * documents remain separate durable blocks instead of being hydrated into messages.
 */
class DiagnosticDatabaseSource @Inject constructor(
    private val database: ChatDatabaseV2,
    private val researchDatabase: ResearchDatabase,
    private val workspace: WorkspaceRepository
) {
    internal suspend fun capture(
        chatId: Int,
        section: String,
        maximumCharacters: Int,
        beforeRead: suspend () -> Unit,
        consume: suspend (JsonObject) -> Unit,
        omitted: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(chatId > 0 && maximumCharacters in 1024..1_048_576)
        val source = if (section == "research_database") researchDatabase.openHelper.readableDatabase else database.openHelper.readableDatabase
        for (table in tables(section)) {
            try {
                captureTable(source, table, chatId, maximumCharacters, beforeRead, consume, omitted)
            } catch (_: SQLException) {
                omitted("DIAGNOSTIC_TABLE_READ_FAILED")
                consume(
                    buildJsonObject {
                        put("table", table.name)
                        put("omitted", true)
                        put("reason", "DIAGNOSTIC_TABLE_READ_FAILED")
                    }
                )
            }
        }
    }

    private suspend fun captureTable(
        database: SupportSQLiteDatabase,
        table: Table,
        chatId: Int,
        maximumCharacters: Int,
        beforeRead: suspend () -> Unit,
        consume: suspend (JsonObject) -> Unit,
        omitted: (String) -> Unit
    ) {
        beforeRead()
        val columns = database.query("PRAGMA table_info(${table.name})").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        if (columns.isEmpty()) {
            omitted("DIAGNOSTIC_TABLE_UNAVAILABLE")
            return
        }
        // Table/column identifiers come only from the static allow-list and SQLite schema.
        check(columns.all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) })
        // SQLite's length(TEXT) counts code points. Measure UTF-8 bytes before a CursorWindow
        // hydrates the row; otherwise non-ASCII text can overrun the same nominal limit.
        val rowLength = columns.joinToString(" + ") { "COALESCE(length(CAST(t.\"$it\" AS BLOB)), 0)" }
        val where = table.where.replace(":chatId", chatId.toString())
        val highWatermark = database.query("SELECT COALESCE(MAX(t.rowid), 0) FROM ${table.name} t WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        var after = Long.MIN_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            beforeRead()
            val page = database.query(
                SimpleSQLiteQuery(
                    "SELECT t.rowid, $rowLength FROM ${table.name} t WHERE ($where) AND t.rowid > ? AND t.rowid <= ? ORDER BY t.rowid LIMIT 16",
                    arrayOf<Any?>(after, highWatermark)
                )
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getLong(1)) } }
            if (page.isEmpty()) break
            for ((rowId, sourceBytes) in page) {
                beforeRead()
                after = rowId
                if (sourceBytes > maximumCharacters) {
                    omitted("RECORD_BYTE_LIMIT")
                    consume(
                        buildJsonObject {
                            put("table", table.name)
                            put("rowId", rowId)
                            put("omitted", true)
                            put("reason", "RECORD_BYTE_LIMIT")
                            put("sourceBytes", sourceBytes)
                            put("maximumBytes", maximumCharacters)
                        }
                    )
                    continue
                }
                // Recheck the bound in this query too: a live generation may update the row.
                val projector = DiagnosticValueProjector(maximumCharacters, omitted)
                val fields = database.query(
                    SimpleSQLiteQuery("SELECT t.* FROM ${table.name} t WHERE t.rowid = ? AND ($where) AND ($rowLength) <= ?", arrayOf<Any?>(rowId, maximumCharacters))
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        null
                    } else {
                        buildJsonObject {
                            for (index in 0 until cursor.columnCount) {
                                val key = cursor.getColumnName(index)
                                if (table.name == "workspace_records" && key == "payload") {
                                    put(key, JsonPrimitive(cursor.getString(index)))
                                } else {
                                    put(key, cell(cursor, index, projector, omitted))
                                }
                            }
                        }
                    }
                }
                if (fields == null) {
                    omitted("SOURCE_CHANGED_DURING_CAPTURE")
                    continue
                }
                // Protected receipts are expanded only after the bounded row cursor is closed.
                val projectedFields = if (table.name == "workspace_records") {
                    val storedPayload = (fields["payload"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val protected = workspace.diagnosticPayload(
                        chatId,
                        (fields["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        (fields["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        storedPayload,
                        maximumCharacters,
                        beforeRead,
                        omitted
                    )
                    val payload = if (!protected.protected) {
                        projector.text(storedPayload)
                    } else if (protected.payload != null) {
                        projector.element(protected.payload)
                    } else {
                        val reason = protected.omissionReason ?: "PROTECTED_PAYLOAD_UNAVAILABLE"
                        omitted(reason)
                        buildJsonObject {
                            put("availability", "omitted")
                            put("reason", reason)
                        }
                    }
                    JsonObject(fields + ("payload" to payload))
                } else {
                    fields
                }
                consume(
                    buildJsonObject {
                        put("table", table.name)
                        put("rowId", rowId)
                        put("tableHighWatermark", highWatermark)
                        put("fields", projectedFields)
                    }
                )
            }
        }
    }

    private fun cell(cursor: Cursor, index: Int, projector: DiagnosticValueProjector, omitted: (String) -> Unit): JsonElement = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(index))
        Cursor.FIELD_TYPE_BLOB -> {
            omitted("BINARY_PAYLOAD_NOT_EXPORTED")
            JsonPrimitive("[binary payload omitted]")
        }
        else -> projector.text(cursor.getString(index))
    }

    private data class Table(val name: String, val where: String)

    private fun tables(section: String): List<Table> {
        val runs = "SELECT run_id FROM agent_runs WHERE chat_id = :chatId"
        val researchWorks = "SELECT workId FROM research_save_receipts WHERE chatId = :chatId UNION SELECT workId FROM research_jobs WHERE chatId = :chatId"
        return when (section) {
            "conversation" -> listOf(Table("messages_v2", "t.chat_id = :chatId"))
            "tool_receipts" -> listOf(Table("tool_events", "t.run_id IN ($runs)"))
            "runs" -> listOf(Table("agent_runs", "t.chat_id = :chatId"), Table("run_policy_snapshots", "t.chatId = :chatId"), Table("agent_run_policy_refs", "t.chatId = :chatId"))
            "invocations" -> listOf(Table("model_invocations", "t.parentRunId IN ($runs) OR (t.turnKey LIKE CAST(:chatId AS TEXT) || ':%' AND NOT EXISTS (SELECT 1 FROM agent_runs ar WHERE ar.run_id = t.parentRunId AND ar.chat_id != :chatId))"))
            "workspace" -> listOf(Table("workspace_records", "t.chatId = :chatId"), Table("pending_prompts", "t.chatId = :chatId"))
            "answer_segments" -> listOf(Table("answer_segments", "t.runId IN ($runs)"))
            "combined_documents" -> listOf(
                Table("combined_durable_blocks", "t.runId IN ($runs)"),
                Table("combined_delivery_records", "t.runId IN ($runs)"),
                Table("combined_final_manifests", "t.runId IN ($runs)"),
                Table("combined_final_block_index", "t.manifestId IN (SELECT id FROM combined_final_manifests WHERE runId IN ($runs))")
            )
            "research_database" -> listOf(
                Table("research_save_receipts", "t.chatId = :chatId"),
                Table("research_jobs", "t.chatId = :chatId"),
                Table("research_bindings", "t.chatId = :chatId"),
                Table("research_report_sections", "t.chatId = :chatId"),
                Table("research_archive", "t.workId IN ($researchWorks)"),
                Table("research_job_transitions", "t.jobId IN (SELECT jobId FROM research_jobs WHERE chatId = :chatId)")
            )
            else -> error("UNKNOWN_DIAGNOSTIC_SECTION")
        }
    }
}
