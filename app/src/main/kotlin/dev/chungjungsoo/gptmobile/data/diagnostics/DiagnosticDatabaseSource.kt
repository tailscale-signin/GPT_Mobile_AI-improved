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
import kotlinx.serialization.json.JsonArray
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
    /** A small, bounded failure index is emitted before heavier diagnostic sections. */
    internal suspend fun latestFailureSummary(
        chatId: Int,
        appVersion: String,
        appBuild: Int,
        appCommit: String,
        beforeRead: suspend () -> Unit,
        omitted: (String) -> Unit
    ): JsonObject = withContext(Dispatchers.IO) {
        require(chatId > 0)
        beforeRead()
        val run = database.openHelper.readableDatabase.query(
            SimpleSQLiteQuery(
                "SELECT run_id, status, terminal_error FROM agent_runs WHERE chat_id = ? ORDER BY created_at DESC, rowid DESC LIMIT 1",
                arrayOf<Any?>(chatId)
            )
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
        }
        val evidence = mutableListOf<DiagnosticFailureEvidence>()
        val limitations = mutableListOf<JsonObject>()
        if (run != null) {
            suspend fun load(predicate: String, identity: String? = null): Triple<String, String, String>? {
                beforeRead()
                return database.openHelper.readableDatabase.query(
                    SimpleSQLiteQuery(
                        "SELECT id, kind, CASE WHEN length(CAST(payload AS BLOB)) <= 262144 THEN payload ELSE NULL END " +
                            "FROM workspace_records WHERE chatId = ? AND runId = ? AND kind IN ('github_evidence','synthesis_checkpoint') AND $predicate " +
                            "ORDER BY updatedAt DESC, rowid DESC LIMIT 1",
                        if (identity == null) arrayOf<Any?>(chatId, run.first) else arrayOf<Any?>(chatId, run.first, identity)
                    )
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2).orEmpty())
                }
            }
            suspend fun project(stored: Triple<String, String, String>?, source: String): JsonObject? {
                if (stored == null) return null
                val reasons = mutableListOf<String>()
                val protected = workspace.diagnosticPayload(chatId, stored.first, stored.second, stored.third, 256 * 1024, beforeRead, reasons::add)
                val payload = if (stored.third.isEmpty()) {
                    reasons += "PROTECTED_METADATA_LIMIT"
                    null
                } else if (protected.protected) {
                    (protected.payload as? JsonObject).also {
                        if (it == null) reasons += protected.omissionReason ?: "LATEST_FAILURE_EVIDENCE_UNAVAILABLE"
                    }
                } else {
                    DiagnosticValueProjector(256 * 1024, reasons::add).text(stored.third) as? JsonObject
                }
                reasons.distinct().forEach { reason ->
                    limitations += buildJsonObject {
                        put("record_id", stored.first)
                        put("source", source)
                        put("reason", reason)
                        put("scope", "COMPACT_SUMMARY_PROJECTION; full records are captured independently")
                    }
                    // A small projection limit is not missing source evidence. The full
                    // latest-state/workspace collector independently records real omissions.
                    if (reason !in setOf("PROTECTED_PAYLOAD_BYTE_LIMIT", "PROTECTED_METADATA_LIMIT")) omitted(reason)
                }
                if (payload != null) evidence += DiagnosticFailureEvidence(stored.first, source, payload)
                return payload
            }
            val current = load("id GLOB 'github-evidence:*:verification_current_*'")
            val report = project(current, "VERIFICATION_CURRENT")
            if (report == null) {
                val event = load("id GLOB 'github-evidence:*:verification_event_*'")
                val projected = project(event, "VERIFICATION_EVENT")
                val snapshot = (projected?.get("snapshot_id") as? JsonPrimitive)?.contentOrNull
                if (current != null && snapshot != current.first.substringAfterLast(':')) {
                    evidence.removeAll { it.source == "VERIFICATION_EVENT" }
                    limitations += buildJsonObject {
                        put("source", "VERIFICATION_EVENT")
                        put("reason", "LATEST_FAILURE_SNAPSHOT_MISMATCH")
                    }
                }
            }
            // The output checkpoint supplies invocation/dispatch/recovery detail when
            // the verification event deliberately contains only a compact verdict.
            if (report?.get("completion_verified") != JsonPrimitive(true) &&
                (report == null || listOf("invocation_id", "failed_stage", "recovery_action").any { report[it] == null || report[it] == JsonNull })
            ) {
                val binding = current?.first?.removePrefix("github-evidence:")?.substringBeforeLast(':')
                val output = if (binding == null) load("id GLOB 'github-output:*'") else load("id = ?", "github-output:$binding")
                project(output, "OUTPUT_CHECKPOINT")
            }
        }
        diagnosticFailureSummary(run?.first, run?.second, run?.third, appVersion, appBuild, appCommit, evidence, limitations)
    }

    /** References only: accepted GitHub policies also live outside the legacy policy tables. */
    internal suspend fun latestPolicyReferences(chatId: Int, beforeRead: suspend () -> Unit): JsonObject = withContext(Dispatchers.IO) {
        require(chatId > 0)
        beforeRead()
        val references = database.openHelper.readableDatabase.query(
            SimpleSQLiteQuery(
                "SELECT rowid, id, runId FROM workspace_records WHERE chatId = ? " +
                    "AND runId IN (SELECT run_id FROM agent_runs WHERE chat_id = ? ORDER BY created_at DESC, rowid DESC LIMIT 1) " +
                    "AND (id GLOB 'github-run-policy:*' OR id GLOB 'github-evidence:*:policy_*') ORDER BY updatedAt DESC, rowid DESC LIMIT 17",
                arrayOf<Any?>(chatId, chatId)
            )
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        buildJsonObject {
                            put("table", "workspace_records")
                            put("rowId", cursor.getLong(0))
                            put("record_id", cursor.getString(1))
                            put("run_id", cursor.getString(2))
                        }
                    )
                }
            }
        }
        buildJsonObject {
            put("kind", "accepted_policy_workspace_references")
            put("availability", if (references.isEmpty()) "NO_RETAINED_WORKSPACE_POLICY_REFERENCES" else "RETAINED_WORKSPACE_POLICY_REFERENCES")
            put("scope", "Latest run only. References identify retained accepted policies, not successful tool tests or process admission snapshots.")
            put("lookup", "Latest matching policy records are in latest-state.jsonl; full-history captures also include workspace.jsonl.")
            put("references", JsonArray(references.take(16)))
            put("referencesTruncated", references.size > 16)
        }
    }

    internal suspend fun capture(
        chatId: Int,
        section: String,
        maximumCharacters: Int,
        beforeRead: suspend () -> Unit,
        consume: suspend (JsonObject) -> Unit,
        omitted: (String) -> Unit,
        maximumSourceBytes: Int = maximumCharacters,
        metadata: suspend (JsonObject) -> Unit = {},
        resumeCursor: JsonObject? = null,
        checkpoint: (JsonObject) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        require(chatId > 0 && maximumCharacters in 1024..1_048_576 && maximumSourceBytes in maximumCharacters..1_048_576)
        require(
            resumeCursor == null ||
                resumeCursor.number("schema") == 1L &&
                resumeCursor.text("section") == section &&
                resumeCursor.number("chatId") == chatId.toLong() &&
                resumeCursor["tables"] is JsonObject
        ) { "DIAGNOSTIC_DATABASE_CURSOR_SCOPE_CHANGED" }
        val tableStates = (resumeCursor?.get("tables") as? JsonObject).orEmpty().toMutableMap()
        fun save(table: String, state: JsonObject) {
            tableStates[table] = state
            checkpoint(
                buildJsonObject {
                    put("schema", 1)
                    put("section", section)
                    put("chatId", chatId)
                    put("tables", JsonObject(tableStates))
                }
            )
        }
        val source = if (section == "research_database") researchDatabase.openHelper.readableDatabase else database.openHelper.readableDatabase
        for (table in tables(section)) {
            val state = tableStates[table.name] as? JsonObject
            if (state?.get("completed") == JsonPrimitive(true)) continue
            try {
                captureTable(source, table, chatId, maximumSourceBytes, beforeRead, consume, omitted, metadata, state) { save(table.name, it) }
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
        omitted: (String) -> Unit,
        metadata: suspend (JsonObject) -> Unit,
        saved: JsonObject?,
        checkpoint: (JsonObject) -> Unit
    ) {
        var omissionCount = saved?.number("omissionCount") ?: 0L
        fun recordOmission(reason: String) {
            omissionCount++
            omitted(reason)
        }
        val startedAt = saved?.number("startedAtMillis") ?: System.currentTimeMillis()
        beforeRead()
        val schemaColumns = database.query("PRAGMA table_info(${table.name})").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        val columns = table.columns ?: schemaColumns
        if (columns.isEmpty() || !schemaColumns.containsAll(columns)) {
            recordOmission("DIAGNOSTIC_TABLE_UNAVAILABLE")
            return
        }
        // Table/column identifiers come only from the static allow-list and SQLite schema.
        check(columns.all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) })
        // SQLite's length(TEXT) counts code points. Measure UTF-8 bytes before a CursorWindow
        // hydrates the row; otherwise non-ASCII text can overrun the same nominal limit.
        val selectedColumns = columns.joinToString { "t.\"$it\"" } + if (table.parentRunColumn == null) {
            ""
        } else {
            ", (SELECT substr(status, 1, 64) FROM agent_runs parent WHERE parent.run_id = t.\"${table.parentRunColumn}\") AS parentRunStatus"
        }
        val rowLength = columns.joinToString(" + ") { "COALESCE(length(CAST(t.\"$it\" AS BLOB)), 0)" }
        val where = table.where.replace(":chatId", chatId.toString())
        val highWatermark = saved?.number("highWatermark") ?: database.query("SELECT COALESCE(MAX(t.rowid), 0) FROM ${table.name} t WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        val expectedRows = saved?.number("expectedRows") ?: database.query("SELECT COUNT(*) FROM ${table.name} t WHERE ($where) AND t.rowid <= $highWatermark").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        var inspectedRows = saved?.number("inspectedRows") ?: 0L
        var emittedRows = saved?.number("emittedRows") ?: 0L
        val previousEmittedRows = emittedRows
        var after = saved?.number("afterRowId") ?: if (table.newestFirst) Long.MAX_VALUE else Long.MIN_VALUE
        var afterSort = saved?.number("afterSort") ?: after
        val sort = if (table.newestFirst && "updatedAt" in schemaColumns) "COALESCE(t.updatedAt, 0)" else "t.rowid"
        fun save(completed: Boolean = false) {
            checkpoint(
                buildJsonObject {
                    put("highWatermark", highWatermark)
                    put("expectedRows", expectedRows)
                    put("inspectedRows", inspectedRows)
                    put("emittedRows", emittedRows)
                    put("omissionCount", omissionCount)
                    put("afterRowId", after)
                    put("afterSort", afterSort)
                    put("newestFirst", table.newestFirst)
                    put("completed", completed)
                    put("startedAtMillis", startedAt)
                }
            )
        }
        fun advance(rowId: Long, order: Long) {
            after = rowId
            afterSort = order
            save()
        }
        save()
        while (true) {
            currentCoroutineContext().ensureActive()
            beforeRead()
            val page = database.query(
                SimpleSQLiteQuery(
                    if (table.newestFirst) {
                        "SELECT t.rowid, $rowLength, $sort FROM ${table.name} t WHERE ($where) AND ($sort < ? OR ($sort = ? AND t.rowid < ?)) AND t.rowid <= ? ORDER BY $sort DESC, t.rowid DESC LIMIT 16"
                    } else {
                        "SELECT t.rowid, $rowLength, t.rowid FROM ${table.name} t WHERE ($where) AND t.rowid > ? AND t.rowid <= ? ORDER BY t.rowid LIMIT 16"
                    },
                    if (table.newestFirst) arrayOf<Any?>(afterSort, afterSort, after, highWatermark) else arrayOf<Any?>(after, highWatermark)
                )
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))) } }
            if (page.isEmpty()) break
            for ((rowId, sourceBytes, order) in page) {
                beforeRead()
                inspectedRows++
                if (sourceBytes > maximumCharacters) {
                    recordOmission("RECORD_BYTE_LIMIT")
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
                    advance(rowId, order)
                    continue
                }
                // Recheck the bound in this query too: a live generation may update the row.
                val projector = DiagnosticValueProjector(maximumCharacters, ::recordOmission)
                val fields = database.query(
                    SimpleSQLiteQuery("SELECT $selectedColumns FROM ${table.name} t WHERE t.rowid = ? AND ($where) AND ($rowLength) <= ?", arrayOf<Any?>(rowId, maximumCharacters))
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
                                    put(key, cell(cursor, index, projector, ::recordOmission))
                                }
                            }
                        }
                    }
                }
                if (fields == null) {
                    recordOmission("SOURCE_CHANGED_DURING_CAPTURE")
                    advance(rowId, order)
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
                        ::recordOmission
                    )
                    val payload = if (!protected.protected) {
                        projector.text(storedPayload)
                    } else if (protected.payload != null) {
                        projector.element(protected.payload)
                    } else {
                        val reason = protected.omissionReason ?: "PROTECTED_PAYLOAD_UNAVAILABLE"
                        recordOmission(reason)
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
                emittedRows++
                advance(rowId, order)
            }
        }
        if (inspectedRows != expectedRows) recordOmission("DATABASE_ROW_COUNT_CHANGED")
        metadata(
            buildJsonObject {
                put("kind", "database_capture_coverage")
                put("table", table.name)
                put(
                    "availability",
                    if (expectedRows == 0L) {
                        "EMPTY"
                    } else if (emittedRows == expectedRows && omissionCount == 0L) {
                        "AVAILABLE"
                    } else {
                        "PARTIAL"
                    }
                )
                put("tableHighWatermark", highWatermark)
                put("expectedRows", expectedRows)
                put("inspectedRows", inspectedRows)
                put("emittedRows", emittedRows)
                put("emittedRowsThisPart", emittedRows - previousEmittedRows)
                put("resumed", saved != null)
                put("newestFirst", table.newestFirst)
                put("omittedValuesOrRows", omissionCount)
                put("pageSize", 16)
                put("rowLimit", kotlinx.serialization.json.JsonNull)
                put("startedAtMillis", startedAt)
                put("completedAtMillis", System.currentTimeMillis())
                put("consistency", "Frozen row-id boundary; values observed during this table capture. Changes and resumed stages are not a transaction snapshot.")
            }
        )
        save(completed = true)
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

    private data class Table(val name: String, val where: String, val columns: List<String>? = null, val parentRunColumn: String? = null, val newestFirst: Boolean = false)

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content
    private fun JsonObject.number(key: String): Long? = text(key)?.toLongOrNull()

    private fun tables(section: String): List<Table> {
        val runs = "SELECT run_id FROM agent_runs WHERE chat_id = :chatId"
        val latestRun = "SELECT run_id FROM agent_runs WHERE chat_id = :chatId ORDER BY created_at DESC, rowid DESC LIMIT 1"
        val latestWorkspace = listOf(
            "w.id GLOB 'github-evidence:*:verification_*' AND w.id NOT GLOB 'github-evidence:*:verification_plan_*' AND w.id NOT GLOB 'github-evidence:*:verification_progress_*' AND w.id NOT GLOB 'github-evidence:*:verification_event_*'",
            "w.id GLOB 'github-evidence:*:verification_progress_*'",
            "w.id GLOB 'github-evidence:*:verification_event_*'",
            "w.id GLOB 'github-evidence:*:checklist_*'",
            "w.id GLOB 'github-evidence:*:policy_*'",
            "w.id GLOB 'github-run-policy:*'",
            "w.id GLOB 'github-review:*'",
            "w.id GLOB 'github-active-task:*'",
            "w.id GLOB 'github-tool-budget:*'",
            "w.id GLOB 'github-output:*'"
        ).joinToString(" OR ") { predicate ->
            "t.rowid IN (SELECT w.rowid FROM workspace_records w WHERE w.chatId = :chatId AND w.runId IN ($latestRun) AND ($predicate) ORDER BY w.updatedAt DESC, w.rowid DESC LIMIT 1)"
        }
        val invocationScope = "t.parentRunId IN ($runs) OR (t.turnKey LIKE CAST(:chatId AS TEXT) || ':%' AND NOT EXISTS (SELECT 1 FROM agent_runs ar WHERE ar.run_id = t.parentRunId AND ar.chat_id != :chatId))"
        val researchWorks = "SELECT workId FROM research_save_receipts WHERE chatId = :chatId UNION SELECT workId FROM research_jobs WHERE chatId = :chatId"
        return when (section) {
            "latest_state" -> listOf(
                Table(
                    "agent_runs",
                    "t.run_id IN ($latestRun)",
                    listOf("run_id", "profile_uid", "provider_snapshot", "model_snapshot", "status", "created_at", "started_at", "completed_at", "terminal_error", "gateway_job_id", "gateway_last_sequence", "input_tokens", "output_tokens", "total_tokens", "ownership_fence")
                ),
                Table(
                    "provider_waits",
                    "t.runId IN ($latestRun) AND EXISTS (SELECT 1 FROM agent_runs r WHERE r.run_id = t.runId AND r.status = 'WAITING_PROVIDER_LIMIT' AND r.ownership_fence = t.fence)",
                    listOf("runId", "fence", "retryAt")
                ),
                Table("agent_run_policy_refs", "t.runId IN ($latestRun)"),
                Table("run_policy_snapshots", "t.chatId = :chatId AND t.digest IN (SELECT digest FROM agent_run_policy_refs WHERE runId IN ($latestRun))"),
                Table("workspace_records", "t.chatId = :chatId AND ($latestWorkspace)", newestFirst = true),
                Table("tool_events", "t.rowid IN (SELECT rowid FROM tool_events WHERE run_id IN ($latestRun) ORDER BY rowid DESC LIMIT 8)", newestFirst = true),
                Table("pending_prompts", "t.rowid IN (SELECT rowid FROM pending_prompts WHERE chatId = :chatId ORDER BY rowid DESC LIMIT 1)")
            )
            "run_index" -> listOf(Table("agent_runs", "t.chat_id = :chatId", listOf("run_id")))
            "timeline" -> listOf(
                Table("agent_runs", "t.chat_id = :chatId", listOf("run_id", "status", "created_at", "started_at", "completed_at")),
                Table("tool_events", "t.run_id IN ($runs)", listOf("event_id", "run_id", "call_id", "sequence", "status", "started_at", "completed_at"), "run_id"),
                Table("model_invocations", invocationScope, listOf("id", "parentRunId", "status", "startedAt", "durationMs"), "parentRunId")
            )
            "conversation" -> listOf(Table("messages_v2", "t.chat_id = :chatId"))
            "tool_receipts" -> listOf(Table("tool_events", "t.run_id IN ($runs)"))
            "runs" -> listOf(Table("agent_runs", "t.chat_id = :chatId"), Table("run_policy_snapshots", "t.chatId = :chatId"), Table("agent_run_policy_refs", "t.chatId = :chatId"))
            "invocations" -> listOf(Table("model_invocations", invocationScope))
            "workspace" -> listOf(Table("workspace_records", "t.chatId = :chatId", newestFirst = true), Table("pending_prompts", "t.chatId = :chatId", newestFirst = true))
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
