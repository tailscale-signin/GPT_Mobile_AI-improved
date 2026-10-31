package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** An authorized local cursor delivers every record before advancing a remote page. */
internal class GitHubResultViews(
    private val now: () -> Long = System::currentTimeMillis,
    private val pageBytes: Int = 8 * 1024,
    private val capacityBytes: Int = 4 * 1024 * 1024
) {
    init {
        require(pageBytes >= 2048)
    }
    private data class Entry(val scope: String, val operation: String, val fragments: List<JsonObject>, val metadata: JsonObject, val continuations: List<JsonObject>, val expires: Long, val bytes: Int, val originalError: Boolean, val originalErrorCode: String?, val pageBytes: Int, val context: JsonObject, val issuedCursors: MutableSet<String> = mutableSetOf())
    private val entries = LinkedHashMap<String, Entry>()
    private var retainedBytes = 0
    private class FragmentCapacity : RuntimeException()
    private class FragmentBuffer(private val limit: Int) {
        val records = mutableListOf<JsonObject>()
        var bytes = 0L
            private set

        operator fun plusAssign(record: JsonObject) {
            val cost = record.toString().toByteArray().size * 2L + 256
            if (bytes + cost > limit) throw FragmentCapacity()
            bytes += cost
            records += record
        }
    }

    @Synchronized
    fun present(scope: String, operation: String, modelName: String, upstream: AgentToolResult, request: JsonObject = JsonObject(emptyMap()), maxPageBytes: Int = pageBytes): AgentToolResult {
        val pageBytes = minOf(this.pageBytes, maxPageBytes.coerceAtLeast(2048))
        val value = when (val content = upstream.content) {
            is ToolResultContent.Json -> content.value
            is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(content.text) }.getOrNull() ?: JsonPrimitive(content.text)
            is ToolResultContent.ResourceLinks -> return upstream
        }
        val bytes = value.toString().toByteArray().size
        if (bytes > MAX_SOURCE_BYTES) return overflow(upstream, operation, modelName, value, request)
        val continuations = mutableListOf<JsonObject>()
        val body = retainContinuations(value, "", operation, modelName, request, continuations)
        val id = UUID.randomUUID().toString()
        val small = buildJsonObject {
            put("schema", SCHEMA)
            put("operation", operation)
            put("local_page_complete", true)
            put("data", body)
            put("recovery_call", savedCall(modelName, "$id:0:$RECOVERY_PAGE_BYTES"))
            if (continuations.isNotEmpty()) {
                put("remote_continuations", JsonArray(continuations))
                if (continuations.size == 1) put("continuation_call", continuations.single().getValue("call"))
            }
        }
        purge()
        var metadataBytes = 0
        val metadata = JsonObject(
            (value as? JsonObject).orEmpty().filter { (key, item) ->
                val cost = key.length + item.toString().toByteArray().size
                (key in METADATA && cost <= 256 && metadataBytes + cost <= minOf(768, pageBytes / 8)).also { if (it) metadataBytes += cost }
            }
        )
        val buffer = FragmentBuffer(capacityBytes)
        // Continuations too large for the final envelope remain exact, retrievable records.
        // They are appended only after every data fragment; no remote record is discarded.
        val lastContinuations = if (continuations.toString().toByteArray().size <= pageBytes / 4) continuations else emptyList()
        try {
            fragment("", body, buffer, pageBytes)
            if (lastContinuations.isEmpty()) {
                continuations.forEach { continuation ->
                    buffer += buildJsonObject {
                        put("kind", "remote_continuation")
                        put("continuation", continuation)
                    }
                }
            }
            if (lastContinuations.isNotEmpty()) buffer += buildJsonObject { put("kind", "continuation_boundary") }
        } catch (_: FragmentCapacity) {
            return overflow(upstream, operation, modelName, value, request)
        }
        val fragments = buffer.records
        if (fragments.any { it.toString().toByteArray().size > pageBytes / 3 } || fragments.isEmpty()) return overflow(upstream, operation, modelName, value, request)
        // Retain fragments and compact metadata only, without a duplicate source tree.
        // Account for strings, object/collection overhead and continuation copies.
        val context = JsonObject(((value as? JsonObject).orEmpty() + request).filterKeys { it in SAVED_CONTEXT_KEYS })
        val retained = buffer.bytes + metadata.toString().toByteArray().size * 2L + lastContinuations.sumOf { it.toString().toByteArray().size * 2L + 256 } + context.toString().toByteArray().size * 2L + 1024
        if (retained > capacityBytes) return overflow(upstream, operation, modelName, value, request)
        while (entries.isNotEmpty() && (entries.size >= 32 || retainedBytes + retained > capacityBytes)) evict(entries.keys.first())
        val entry = Entry(scope, operation, fragments, metadata, lastContinuations, now() + 10 * 60_000, retained.toInt(), upstream.isError, upstream.errorCode, pageBytes, context)
        entries[id] = entry
        retainedBytes += entry.bytes
        if (small.toString().toByteArray().size <= pageBytes) {
            authorize(entry, small)
            return upstream.copy(content = ToolResultContent.Json(small))
        }
        val first = page(id, entry, 0, modelName)
        authorize(entry, first)
        return upstream.copy(content = ToolResultContent.Json(first), retainedContent = upstream.retainedContent ?: upstream.content)
    }

    @Synchronized
    fun read(scope: String, operation: String, modelName: String, cursor: String, callId: String, context: JsonObject = JsonObject(emptyMap())): AgentToolResult {
        purge()
        val parts = cursor.split(':')
        val entry = parts.firstOrNull()?.let(entries::get)
        val offset = parts.getOrNull(1)?.toIntOrNull()
        val requestedBytes = if (parts.size == 3) parts[2].toIntOrNull() else entry?.pageBytes
        if (parts.size !in 2..3 || entry == null || offset == null || requestedBytes == null || requestedBytes !in 1024..entry.pageBytes || entry.scope != scope || entry.operation != operation || offset !in entry.fragments.indices || cursor !in entry.issuedCursors) {
            val evidenceRecovery = if (operation == "github_read") " Reopen retained source with evidence_id and no view_cursor." else ""
            return AgentToolResult(callId, ToolResultContent.Text("This saved GitHub view is unavailable or outside the authorized conversation. Use the exact continuation_call from the latest result in this conversation; never invent or reuse a cursor from another account or conversation.$evidenceRecovery For an expired immutable read, request a smaller range at the same commit_sha. Reconcile a prior mutation using its receipt; do not repeat the mutation to recover output."), true, errorCode = "SAVED_VIEW_UNAVAILABLE", dispatched = false)
        }
        val conflictingContext = context.any { (key, value) ->
            val expected = entry.context[key]
            if (key in setOf("owner", "repo")) {
                expected !is JsonPrimitive || value !is JsonPrimitive || !expected.isString || !value.isString || !expected.content.equals(value.content, true)
            } else {
                expected != value
            }
        }
        if (conflictingContext) {
            return AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("schema", "github.arguments.v1")
                        put("operation", operation)
                        put("message", "Saved-page context differs from the retained request. Use only the exact view_cursor; a saved read cannot change repository, revision, action or read selectors. Nothing was dispatched.")
                        put("correction_call", savedCall(modelName, cursor))
                    }
                ),
                true,
                errorCode = "INVALID_ARGUMENTS",
                dispatched = false
            )
        }
        // Active readers can take longer than ten minutes to consume a large result.
        // Inactive views still expire and the existing byte/entry bounds still apply.
        val refreshed = entry.copy(expires = now() + 10 * 60_000)
        entries.remove(parts[0])
        entries[parts[0]] = refreshed
        val page = page(parts[0], refreshed, offset, modelName, requestedBytes)
        authorize(refreshed, page)
        if (page["page_capacity_exceeded"] == JsonPrimitive(true)) {
            return AgentToolResult(callId, ToolResultContent.Json(page), true, errorCode = "SAVED_VIEW_PAGE_CAPACITY", sharedResult = true, dispatched = false)
        }
        return AgentToolResult(callId, ToolResultContent.Json(page), entry.originalError, errorCode = entry.originalErrorCode, sharedResult = true, dispatched = false)
    }

    private fun authorize(entry: Entry, page: JsonObject) {
        // Only cursors actually emitted by this view may advance it. Source evidence
        // and a guessed fragment offset never grant pagination access.
        listOf("recovery_call", "continuation_call").forEach { key ->
            val arguments = ((page[key] as? JsonObject)?.get("arguments") as? JsonObject)
            (arguments?.get("view_cursor") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(entry.issuedCursors::add)
        }
    }

    private fun savedCall(modelName: String, cursor: String) = buildJsonObject {
        put("tool", modelName)
        put("arguments", buildJsonObject { put("view_cursor", cursor) })
    }

    private fun page(id: String, entry: Entry, offset: Int, modelName: String, pageBytes: Int = entry.pageBytes): JsonObject {
        var metadataBytes = 0
        val metadata = if (pageBytes < 2048) {
            JsonObject(
                entry.metadata.filter { (key, value) ->
                    val bytes = key.length + value.toString().toByteArray().size
                    (metadataBytes + bytes <= 128).also { if (it) metadataBytes += bytes }
                }
            )
        } else {
            entry.metadata
        }
        val records = mutableListOf<JsonObject>()
        var next = offset
        fun envelope(): JsonObject = buildJsonObject {
            val complete = next == entry.fragments.size
            put("schema", SCHEMA)
            put("operation", entry.operation)
            put("local_page_complete", complete)
            put("metadata", metadata)
            put("fragments", JsonArray(records))
            put("fragment_offset", offset)
            put("total_fragments", entry.fragments.size)
            put("reconstruction", if (pageBytes < 2048) "JSON pointers; UTF-16 string offsets. Read every local page before remote continuation." else "JSON pointers refer to the original result; string offsets count UTF-16 characters. Finish all local pages before executing any remote continuation.")
            put("recovery_call", savedCall(modelName, "$id:$offset:$RECOVERY_PAGE_BYTES"))
            if (!complete) {
                put(
                    "continuation_call",
                    savedCall(modelName, "$id:$next" + if (pageBytes < entry.pageBytes) ":$pageBytes" else "")
                )
            } else if (entry.continuations.isNotEmpty()) {
                put("remote_continuations", JsonArray(entry.continuations))
                if (entry.continuations.size == 1) put("continuation_call", entry.continuations.single().getValue("call"))
            }
        }
        while (next < entry.fragments.size) {
            records += entry.fragments[next]
            next++
            if (envelope().toString().toByteArray().size > pageBytes) {
                records.removeAt(records.lastIndex)
                next--
                break
            }
        }
        if (records.isEmpty()) {
            return buildJsonObject {
                put("schema", SCHEMA)
                put("operation", entry.operation)
                put("local_page_complete", false)
                put("page_capacity_exceeded", true)
                put("message", "This saved record exceeds the admitted page allowance. Increase the result/context allowance or narrow the immutable read; no cursor advanced and no upstream action was repeated.")
            }
        }
        return envelope()
    }

    private fun retainContinuations(value: JsonElement, pointer: String, operation: String, modelName: String, request: JsonObject, target: MutableList<JsonObject>): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.mapNotNull { (key, child) ->
                if ((key == "continuation" || key.endsWith("_continuation")) && child is JsonObject) {
                    val args = continuationArguments(operation, request, child)
                    target += buildJsonObject {
                        put("pointer", pointer + "/" + key.replace("~", "~0").replace("/", "~1"))
                        put("native", child)
                        put(
                            "call",
                            buildJsonObject {
                                put("tool", modelName)
                                put("arguments", args)
                            }
                        )
                    }
                    null
                } else {
                    key to retainContinuations(child, pointer + "/" + key.replace("~", "~0").replace("/", "~1"), operation, modelName, request, target)
                }
            }.toMap()
        )
        is JsonArray -> JsonArray(value.mapIndexed { index, child -> retainContinuations(child, "$pointer/$index", operation, modelName, request, target) })
        else -> value
    }

    private fun continuationArguments(operation: String, request: JsonObject, continuation: JsonObject): JsonObject {
        val properties = (GitHubOperationTool.definition(operation).inputSchema["properties"] as JsonObject).keys
        val values = (request - "view_cursor").toMutableMap()
        continuation.filterKeys { it in properties && it != "action" }.forEach { (key, value) -> values[key] = value }
        if (operation == "github_context") {
            val native = (continuation["action"] as? JsonPrimitive)?.content
            val action = mapOf("changed_since" to "changes", "list_branches" to "branches", "branch_context" to "branch", "branch_dependencies" to "branch_dependencies", "list_commits" to "commits", "get_commit_detail" to "commit", "search_commits" to "commit_search", "recovery_context" to "recovery", "get_commit_status_summary" to "checks", "search_repositories" to "repositories")[native]
            if (action != null) values["action"] = JsonPrimitive(action)
            continuation["base"]?.let { values["base_sha"] = it }
        }
        if (operation == "github_pr") {
            values["action"] = JsonPrimitive(
                when ((continuation["action"] as? JsonPrimitive)?.content) {
                    "list_pull_requests" -> "list"
                    "get_pull_request_comments" -> "comments"
                    "pull_request_reviews" -> "reviews"
                    else -> "context"
                }
            )
            continuation["expected_head"]?.let { values["expected_head_sha"] = it }
        }
        if (operation == "github_issue") {
            val action = mapOf("list_issues" to "list", "issue_context" to "context", "issue_comments" to "comments", "issue_events" to "history")[(continuation["action"] as? JsonPrimitive)?.content]
            if (action != null) values["action"] = JsonPrimitive(action)
        }
        if (operation == "github_release") {
            val action = mapOf("tag_context" to "tag", "list_tags" to "tags", "list_releases" to "list", "get_release_by_tag" to "detail")[(continuation["action"] as? JsonPrimitive)?.content]
            if (action != null) values["action"] = JsonPrimitive(action)
        }
        if (operation == "github_ci") {
            val action = mapOf(
                "workflow_catalog" to "definitions", "list_workflows" to "workflows",
                "list_workflow_runs" to "runs", "get_workflow_run" to "run",
                "list_workflow_jobs" to "jobs", "get_workflow_job" to "job",
                "list_workflow_artifacts" to "artifacts", "get_job_logs" to "logs",
                "ci_failure_digest" to "failure", "get_commit_status_summary" to "status"
            )[(continuation["action"] as? JsonPrimitive)?.content]
            if (action != null) values["action"] = JsonPrimitive(action)
        }
        if (operation == "github_read" && "path" in continuation) {
            val start = (continuation["start_line"] as? JsonPrimitive)?.intOrNull ?: 1
            values["ranges"] = JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", continuation.getValue("path"))
                        put("start_line", start)
                        put("end_line", continuation["end_line"] ?: JsonPrimitive(start + 199))
                        continuation["start_offset"]?.let { put("start_offset", it) }
                    }
                )
            )
        }
        return JsonObject(values.filterKeys { it in properties })
    }

    private fun fragment(pointer: String, value: JsonElement, target: FragmentBuffer, pageBytes: Int) {
        if (value.toString().toByteArray().size <= minOf(160, pageBytes / 4)) {
            target += buildJsonObject {
                put("pointer", pointer)
                put("value", value)
            }
            return
        }
        when (value) {
            is JsonObject -> value.forEach { (key, child) -> fragment(pointer + "/" + key.replace("~", "~0").replace("/", "~1"), child, target, pageBytes) }
            is JsonArray -> value.forEachIndexed { index, child -> fragment("$pointer/$index", child, target, pageBytes) }
            is JsonPrimitive -> {
                // Splitting a number into quoted chunks changes its JSON type. Preserve it
                // as one scalar; the normal capacity guard rejects an oversized scalar.
                if (!value.isString) {
                    target += buildJsonObject {
                        put("pointer", pointer)
                        put("value", value)
                    }
                    return
                }
                val text = value.content
                var start = 0
                while (start < text.length) {
                    var end = minOf(start + minOf(96, (pageBytes / 24).coerceAtLeast(1)), text.length)
                    if (end < text.length && text[end].isLowSurrogate()) end--
                    if (end == start) end = minOf(start + 2, text.length)
                    fun record() = buildJsonObject {
                        put("pointer", pointer)
                        put("value", text.substring(start, end))
                        put("offset", start)
                        put("total_characters", text.length)
                    }
                    while (record().toString().toByteArray().size > 384 && end - start > 2) {
                        end = start + (end - start) / 2
                        if (end < text.length && text[end].isLowSurrogate()) end--
                        if (end == start) end = minOf(start + 2, text.length)
                    }
                    target += record()
                    start = end
                }
            }
        }
    }

    private fun overflow(upstream: AgentToolResult, operation: String, modelName: String, value: JsonElement, request: JsonObject): AgentToolResult = upstream.copy(
        content = ToolResultContent.Json(
            buildJsonObject {
                put("schema", SCHEMA)
                put("operation", operation)
                put("local_page_complete", false)
                put("error", "RETAINED_RESULT_CAPACITY")
                put("upstream_completed", !upstream.isError)
                put("upstream_dispatched", upstream.dispatched)
                upstream.errorCode?.let { put("upstream_error_code", it) }
                var receiptBytes = 0
                put(
                    "receipt",
                    JsonObject(
                        (value as? JsonObject).orEmpty().filter { (key, item) ->
                            val size = item.toString().toByteArray().size
                            (key in METADATA + RECEIPTS && size <= 1024 && receiptBytes + size <= 2048).also { if (it) receiptBytes += size }
                        }
                    )
                )
                val sha = ((value as? JsonObject)?.get("sha") as? JsonPrimitive)?.content
                val firstFilePage = (((request["page"] as? JsonPrimitive)?.intOrNull ?: 1).toLong() - 1) *
                    ((request["per_page"] as? JsonPrimitive)?.intOrNull ?: 30) + 1
                if (operation == "github_context" &&
                    request["action"] == JsonPrimitive("commit") &&
                    sha?.matches(Regex("[a-fA-F0-9]{40}")) == true &&
                    firstFilePage in 1..1000 &&
                    (request["per_page"] != JsonPrimitive(1) || request["include_patches"] == JsonPrimitive(true))
                ) {
                    put(
                        "recovery_call",
                        buildJsonObject {
                            put("tool", modelName)
                            put(
                                "arguments",
                                buildJsonObject {
                                    request.filterKeys { it in setOf("owner", "repo") }.forEach { (key, item) -> put(key, item) }
                                    put("action", "commit")
                                    put("commit_sha", sha)
                                    put("per_page", 1)
                                    put("page", firstFilePage)
                                    put("include_patches", false)
                                }
                            )
                        }
                    )
                }
                put("next_step", "The upstream operation already returned. Reconcile its commit, stage, PR, run or release receipt with a read; never repeat a mutation to recover its output. Immutable reads can request a smaller range or page.")
            }
        ),
        isError = true,
        errorCode = "RETAINED_RESULT_CAPACITY"
    )

    private fun purge() {
        entries.filterValues { it.expires <= now() }.keys.toList().forEach(::evict)
    }
    private fun evict(id: String) {
        entries.remove(id)?.let { retainedBytes -= it.bytes }
    }
    companion object {
        // Only read selectors can accompany a cursor, and read() compares every
        // supplied value with the retained request. Never retain write payloads.
        val SAVED_CONTEXT_KEYS = setOf(
            "owner", "repo", "commit_sha", "action", "query", "mode", "path_prefix",
            "file_extension", "path_suffix", "case_sensitive", "fuzzy_match", "fuzzy_threshold",
            "min_lines", "max_lines", "context_lines", "max_new_blobs", "batch_size",
            "index_cursor", "search_id", "path", "ranges", "start_line", "end_line", "pull_number"
        )

        /** Saved pages only deliver captured bytes; backend mutations keep their task-specific receipts. */
        fun scope(credential: String, connectionUid: String, conversationId: String, taskId: String): String =
            dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(listOf(credential, connectionUid, conversationId, taskId.takeIf { conversationId.isBlank() }.orEmpty()).joinToString("|"))

        const val SCHEMA = "github.operation.v1"
        private const val MAX_SOURCE_BYTES = 1024 * 1024
        private const val RECOVERY_PAGE_BYTES = 1536
        private val METADATA = setOf("schema", "owner", "repo", "ref", "commit_sha", "tree_sha", "head_sha", "base_sha", "evidence_id", "complete", "status", "stage_id", "task_id", "execution_id", "id", "number", "sha", "html_url", "url", "run_id", "release_id", "pull_number")
        private val RECEIPTS = setOf("commit", "pull_request", "release", "workflow_run", "receipt", "expected_head_sha")
        val shared = GitHubResultViews()
    }
}
