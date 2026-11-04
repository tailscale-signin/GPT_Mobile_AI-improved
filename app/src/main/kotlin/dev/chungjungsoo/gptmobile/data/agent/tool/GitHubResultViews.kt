package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    private data class Entry(val scope: String, val operation: String, val modelName: String, val fragments: List<JsonObject>, val metadata: JsonObject, val continuations: List<JsonObject>, val expires: Long, val bytes: Int, val originalError: Boolean, val originalErrorCode: String?, val pageBytes: Int, val context: JsonObject, val sourceRecovery: JsonObject?, val issuedCursors: MutableSet<String> = mutableSetOf(), val failedPersistenceCursors: MutableSet<String> = mutableSetOf())
    private data class EvictedView(val scope: String, val operation: String, val reason: String, val recovery: JsonObject?, val cursors: Set<String>, val expires: Long)
    private val entries = LinkedHashMap<String, Entry>()
    private val evicted = LinkedHashMap<String, EvictedView>()
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
        val sourceRecovery = immutableRecovery(operation, modelName, request, value)
        val small = buildJsonObject {
            put("schema", SCHEMA)
            put("operation", operation)
            put("local_page_complete", true)
            put("data", body)
            put("recovery_call", savedCall(modelName, "$id:0:$RECOVERY_PAGE_BYTES"))
            sourceRecovery?.let { put("source_recovery_call", it) }
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
            fragment("", body, buffer, minOf(pageBytes, RECOVERY_PAGE_BYTES))
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
        val defaults = if (operation in setOf("github_context", "github_ci", "github_pr")) mapOf("response_detail" to JsonPrimitive("compact")) else emptyMap()
        val context = JsonObject((defaults + (value as? JsonObject).orEmpty() + request).filterKeys { it in SAVED_CONTEXT_KEYS })
        val retained = buffer.bytes + metadata.toString().toByteArray().size * 2L + lastContinuations.sumOf { it.toString().toByteArray().size * 2L + 256 } + context.toString().toByteArray().size * 2L + sourceRecovery.toString().toByteArray().size * 2L + 1024
        if (retained > capacityBytes) return overflow(upstream, operation, modelName, value, request)
        while (entries.isNotEmpty() && (entries.size >= 32 || retainedBytes + retained > capacityBytes)) evict(entries.keys.first())
        val entry = Entry(scope, operation, modelName, fragments, metadata, lastContinuations, now() + 10 * 60_000, retained.toInt(), upstream.isError, upstream.errorCode, pageBytes, context, sourceRecovery)
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
        // A model sometimes drops the issued recovery page size. Do not authorize
        // that invented handle, but return the exact issued call for that offset.
        if (entry != null &&
            entry.scope == scope &&
            cursor !in entry.issuedCursors &&
            parts.size in 2..3 &&
            offset != null &&
            (parts.size == 2 || requestedBytes != null && requestedBytes in 1024..entry.pageBytes)
        ) {
            val issued = entry.issuedCursors.firstOrNull { it.split(':').getOrNull(1)?.toIntOrNull() == offset }
            if (issued != null) {
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("schema", "github.arguments.v1")
                            put("reason", "CURSOR_ALTERED")
                            put("message", "Use the exact issued cursor and originating tool in correction_call. Nothing was dispatched.")
                            put("correction_call", savedCall(entry.modelName, issued))
                            entry.sourceRecovery?.let { put("source_recovery_call", it) }
                        }
                    ),
                    true,
                    errorCode = "SAVED_VIEW_CURSOR_ALTERED",
                    dispatched = false
                )
            }
        }
        // Reveal a correction only for a cursor already issued in this authorized scope.
        if (entry != null && entry.scope == scope && cursor in entry.issuedCursors && entry.operation != operation) {
            return AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("schema", "github.arguments.v1")
                        put("message", "This cursor belongs to another operation. Use the exact correction_call; no operation was dispatched.")
                        put("correction_call", savedCall(entry.modelName, cursor))
                    }
                ),
                true,
                errorCode = "SAVED_VIEW_WRONG_OPERATION",
                dispatched = false
            )
        }
        if (parts.size !in 2..3 || entry == null || offset == null || requestedBytes == null || requestedBytes !in 1024..entry.pageBytes || entry.scope != scope || entry.operation != operation || offset !in entry.fragments.indices || cursor !in entry.issuedCursors) {
            val known = evicted[parts.firstOrNull()]?.takeIf { it.scope == scope && it.operation == operation && cursor in it.cursors }
            return AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("schema", "github.arguments.v1")
                        put("reason", known?.reason ?: "UNAVAILABLE_IN_AUTHORIZED_SCOPE")
                        put("message", "The saved page is unavailable. Execute source_recovery_call from the retained result or recovery_call below to reopen the same immutable source. If neither exists, use scoped recovery context. Never repeat a mutation to recover output.")
                        known?.recovery?.let { put("recovery_call", it) }
                    }
                ),
                true,
                errorCode = "SAVED_VIEW_UNAVAILABLE",
                dispatched = false
            )
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

    /** Durable bodies and issued handles are stored separately to avoid rewriting source on every page. */
    @Synchronized
    fun checkpoint(scope: String, result: AgentToolResult): Pair<String, JsonObject>? {
        val content = (result.content as? ToolResultContent.Json)?.value as? JsonObject ?: return null
        val recipe = content["recovery_call"] as? JsonObject ?: return null
        val args = recipe["arguments"] as? JsonObject ?: return null
        val cursor = (args["view_cursor"] as? JsonPrimitive)?.contentOrNull ?: return null
        val id = cursor.substringBefore(':')
        val entry = entries[id]?.takeIf { it.scope == scope && cursor in it.issuedCursors } ?: return null
        return id to buildJsonObject {
            put("schema", "github.saved-view.v1")
            put("scope", entry.scope)
            put("operation", entry.operation)
            put("model_name", entry.modelName)
            put("fragments", JsonArray(entry.fragments))
            put("metadata", entry.metadata)
            put("continuations", JsonArray(entry.continuations))
            put("original_error", entry.originalError)
            entry.originalErrorCode?.let { put("original_error_code", it) }
            put("page_bytes", entry.pageBytes)
            put("context", entry.context)
            entry.sourceRecovery?.let { put("source_recovery", it) }
            put("issued_cursors", JsonArray(entry.issuedCursors.map(::JsonPrimitive)))
            put("failed_persistence_cursors", JsonArray(entry.failedPersistenceCursors.map(::JsonPrimitive)))
        }
    }

    @Synchronized
    fun markPersistenceUnavailable(scope: String, id: String, cursors: List<JsonPrimitive>) {
        val entry = entries[id]?.takeIf { it.scope == scope } ?: return
        entry.failedPersistenceCursors += cursors.map { it.content }.filter { it in entry.issuedCursors }
    }

    /** Rehydrate only exact issued handles from the authorized durable store; this never calls a backend. */
    @Synchronized
    fun restore(scope: String, operation: String, modelName: String, cursor: String, snapshot: JsonObject): Boolean {
        fun text(key: String) = (snapshot[key] as? JsonPrimitive)?.contentOrNull
        if (text("schema") != "github.saved-view.v1" || text("scope") != scope || text("operation") != operation || text("model_name") != modelName) return false
        val id = cursor.substringBefore(':')
        if (runCatching { UUID.fromString(id) }.isFailure) return false
        val issued = (snapshot["issued_cursors"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: return false
        if (cursor !in issued || issued.size > 16_384 || issued.any { it.substringBefore(':') != id }) return false
        val failed = (snapshot["failed_persistence_cursors"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        if (failed.any { it !in issued }) return false
        val fragments = (snapshot["fragments"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return false
        if (fragments.isEmpty() || fragments.size != (snapshot["fragments"] as JsonArray).size) return false
        val pageSize = (snapshot["page_bytes"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 2048..pageBytes } ?: return false
        val metadata = snapshot["metadata"] as? JsonObject ?: return false
        val continuations = (snapshot["continuations"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return false
        val context = snapshot["context"] as? JsonObject ?: return false
        val recovery = snapshot["source_recovery"] as? JsonObject
        val originalError = (snapshot["original_error"] as? JsonPrimitive)?.booleanOrNull ?: return false
        val size = fragments.sumOf { it.toString().encodeToByteArray().size * 2L + 256 } + metadata.toString().encodeToByteArray().size * 2L +
            continuations.sumOf { it.toString().encodeToByteArray().size * 2L + 256 } + context.toString().encodeToByteArray().size * 2L + recovery.toString().encodeToByteArray().size * 2L + 1024
        if (size > capacityBytes || fragments.any { it.toString().encodeToByteArray().size > pageSize / 3 }) return false
        entries.remove(id)?.let { retainedBytes -= it.bytes }
        while (entries.isNotEmpty() && (entries.size >= 32 || retainedBytes + size > capacityBytes)) evict(entries.keys.first())
        val entry = Entry(
            scope, operation, modelName, fragments, metadata, continuations, now() + 10 * 60_000, size.toInt(),
            originalError, text("original_error_code"), pageSize, context, recovery, issued.toMutableSet(), failed.toMutableSet()
        )
        entries[id] = entry
        retainedBytes += entry.bytes
        evicted.remove(id)
        return true
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
            entry.sourceRecovery?.takeIf { it.toString().toByteArray().size <= pageBytes / 4 }?.let { put("source_recovery_call", it) }
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
        if (value.toString().toByteArray().size <= minOf(1024, pageBytes / 4)) {
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

    /** Persisted result/checkpoint envelopes carry this recipe beyond the in-memory cache. */
    private fun immutableRecovery(operation: String, name: String, request: JsonObject, value: JsonElement): JsonObject? {
        val root = value as? JsonObject ?: return null
        val allowed = operation in setOf("github_read", "github_search") || operation == "github_ci" && request["action"] == JsonPrimitive("definitions") || operation == "github_context" && request["action"] == JsonPrimitive("commit")
        if (!allowed) return null
        val sha = (root["commit_sha"] ?: request["commit_sha"] ?: root["sha"].takeIf { operation == "github_context" }) as? JsonPrimitive ?: return null
        if (!sha.content.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))) return null
        val values = request.filterKeys { it !in setOf("view_cursor", "evidence_id", "ref", "branch") }.toMutableMap()
        values["commit_sha"] = sha
        if (operation == "github_read" && "ranges" !in values) values["ranges"] = root["ranges"] ?: return null
        val args = JsonObject(values)
        if (args["owner"] == null || args["repo"] == null || args.toString().toByteArray().size > 4096) return null
        return buildJsonObject {
            put("tool", name)
            put("arguments", args)
        }
    }

    private fun purge() {
        entries.filterValues { it.expires <= now() }.keys.toList().forEach { evict(it, "EXPIRED") }
        evicted.entries.removeAll { it.value.expires <= now() }
    }
    private fun evict(id: String, reason: String = "CAPACITY_EVICTED") {
        entries.remove(id)?.let {
            retainedBytes -= it.bytes
            evicted[id] = EvictedView(it.scope, it.operation, reason, it.sourceRecovery, it.issuedCursors.toList().takeLast(128).toSet(), now() + 24 * 60 * 60_000)
            while (evicted.size > 64) evicted.remove(evicted.keys.first())
        }
    }
    companion object {
        // Only read selectors can accompany a cursor, and read() compares every
        // supplied value with the retained request. Never retain write payloads.
        val SAVED_CONTEXT_KEYS = setOf(
            "owner", "repo", "commit_sha", "action", "query", "mode", "path_prefix",
            "file_extension", "path_suffix", "case_sensitive", "fuzzy_match", "fuzzy_threshold",
            "min_lines", "max_lines", "context_lines", "max_new_blobs", "batch_size",
            "index_cursor", "search_id", "path", "ranges", "start_line", "end_line", "pull_number",
            "response_detail", "page", "per_page", "ref", "branch", "head_sha", "workflow_id", "run_id", "job_id"
        )

        /** Saved pages only deliver captured bytes; backend mutations keep their task-specific receipts. */
        fun scope(authorizationIdentity: String, connectionUid: String, conversationId: String, taskId: String, repository: String = ""): String =
            dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(listOf(authorizationIdentity, connectionUid, conversationId, taskId.takeIf { conversationId.isBlank() }.orEmpty(), repository.lowercase(java.util.Locale.ROOT)).joinToString("\u0000"))

        const val SCHEMA = "github.operation.v1"
        private const val MAX_SOURCE_BYTES = 1024 * 1024
        private const val RECOVERY_PAGE_BYTES = 1536
        private val METADATA = setOf("schema", "owner", "repo", "ref", "commit_sha", "tree_sha", "head_sha", "base_sha", "evidence_id", "complete", "status", "stage_id", "task_id", "execution_id", "id", "number", "sha", "html_url", "url", "run_id", "release_id", "pull_number")
        private val RECEIPTS = setOf("commit", "pull_request", "release", "workflow_run", "receipt", "expected_head_sha")
        val shared = GitHubResultViews()
    }
}
