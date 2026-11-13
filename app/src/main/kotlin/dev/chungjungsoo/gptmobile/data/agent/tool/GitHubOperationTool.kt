package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.AgentToolWorkflowLink
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentRepairScope
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubArgumentProvenance
import dev.chungjungsoo.gptmobile.data.github.GitHubSourceReadRecovery
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Small model-facing contracts backed by the same authorized native integration as the UI. */
internal class GitHubOperationTool(
    val operation: String,
    private val backend: AgentTool,
    private val scope: String,
    modelName: String = operation,
    private val views: GitHubResultViews = GitHubResultViews.shared,
    private val loadSavedView: suspend (String) -> JsonObject? = { null },
    private val saveSavedView: (suspend (String, JsonObject) -> Unit)? = null,
    private val validateSavedViewAccess: suspend () -> Unit = {}
) : AgentTool {
    override val managesExecutionBudget get() = backend.managesExecutionBudget
    override val definition = definition(operation, modelName).let { definition ->
        (backend as? GitHubTool)?.let { definition.copy(description = definition.description + it.planningDescription()) } ?: definition
    }
    private val pagination = GitHubPaginationBoundary()
    private val rejectedCalls = linkedMapOf<JsonObject, AgentToolResult>()

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val arguments = dev.chungjungsoo.gptmobile.data.github.GitHubActionIdentity.canonicalArguments(operation, arguments)
        val started = System.nanoTime()
        val repairScope = currentCoroutineContext()[ToolArgumentRepairScope]
        val previous = synchronized(rejectedCalls) { rejectedCalls[arguments] }
            ?.takeUnless { repairScope != null && it.executionReceipt?.containsKey("argument_repair_attempt") == true }
        // Reuse an identical pre-dispatch rejection so a provider cannot spin on
        // the same invalid call. Never execute the suggested correction on its
        // behalf: saved-view selectors are an authorization boundary, and only
        // the caller can submit the exact cursor-only correction.
        val result = dev.chungjungsoo.gptmobile.data.agent.currentGitHubInvocationReceipt(
            previous?.copy(callId = callId, sharedResult = true) ?: executeOperation(callId, arguments)
        )
        if (result.isError && !result.dispatched && result.errorCode in setOf("INVALID_ARGUMENTS", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED")) {
            synchronized(rejectedCalls) {
                rejectedCalls[arguments] = result
                if (rejectedCalls.size > 128) rejectedCalls.remove(rejectedCalls.keys.first())
            }
        }
        val trace = buildJsonObject {
            put("schema", "github.trace.v1")
            put("call_id", callId)
            put("tool", operation)
            put("action", (arguments["action"] as? JsonPrimitive)?.content.orEmpty())
            put("response_time_ms", ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0))
            put("success", !result.isError)
            put("dispatched", result.dispatched)
            put("reused_receipt", result.sharedResult)
            put("pages_returned", if (result.isError) 0 else 1)
            put("page_kind", if ("view_cursor" in arguments) "LOCAL_FRAGMENT" else "OPERATION_RESULT")
            put("remote_read_requests", result.executionReceipt?.get("read_requests") ?: JsonPrimitive(0))
            put("arguments_fingerprint", dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(scope + "|" + JsonObject(arguments.toSortedMap())))
            put("result_fingerprint", dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(scope + "|" + result.content.toString()))
            put("fingerprint_scope", "AUTHORIZED_CONVERSATION")
            result.errorCode?.let { put("error_code", it) }
        }
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("GitHubToolTrace", "GITHUB_TOOL_COMPLETED · trace=$trace")
        val incident = result.executionReceipt?.get("incident_id") ?: JsonPrimitive("github:" + dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(scope + "|" + callId).take(24))
        return result.copy(
            executionReceipt = JsonObject(
                result.executionReceipt.orEmpty() + buildJsonObject {
                    put("tool_trace", trace)
                    put("incident_id", incident)
                    put("model_tool_name", definition.name)
                    put("original_arguments", dev.chungjungsoo.gptmobile.data.github.GitHubArgumentProvenance.sanitize(arguments))
                    put("argument_provenance", "SANITIZED_BOUNDED_PROJECTION")
                    if ("resolved_arguments" !in result.executionReceipt.orEmpty()) put("resolved_arguments", kotlinx.serialization.json.JsonNull)
                }
            )
        )
    }

    private suspend fun executeOperation(callId: String, arguments: JsonObject): AgentToolResult {
        val normalized = try {
            if ("view_cursor" in arguments || operation == "github_repository") arguments else (backend as? GitHubTool)?.normalizeOperationRepository(arguments) ?: arguments
        } catch (invalid: IllegalArgumentException) {
            return AgentToolResult(callId, ToolResultContent.Text(invalid.message.orEmpty()), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        return executeNormalized(callId, normalized)
    }

    private suspend fun executeNormalized(callId: String, arguments: JsonObject): AgentToolResult {
        if (operation == "github_ci" && arguments["action"] == JsonPrimitive("status") && arguments["workflow_id"] != null && arguments["run_id"] == null && arguments["job_id"] == null && arguments["view_cursor"] == null) {
            return AgentToolResult(callId, ToolResultContent.Text("Workflow status requires an observed run_id. Use action=runs with workflow_id first; commit status cannot be filtered by workflow_id. Nothing was dispatched."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        val wrongHandles = arguments.keys.intersect(setOf("cursor", "next_cursor", "evidence_id", "tracking_id")) - if (operation == "github_read" && "view_cursor" !in arguments) setOf("evidence_id") else emptySet()
        if (wrongHandles.isNotEmpty() && "view_cursor" !in arguments) {
            return AgentToolResult(callId, ToolResultContent.Text("${wrongHandles.sorted().joinToString()} cannot paginate this operation. Use the exact continuation_call returned by this tool. evidence_id only reopens source through github_read; it is never a pagination cursor. Nothing was dispatched."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        if ("view_cursor" in arguments) {
            savedViewAuthorizationFailure(callId)?.let { return it }
            // Providers often carry repository/action defaults into a saved-page call.
            // Validate those against the retained read instead of forcing a model repair loop.
            if (arguments.keys.any { it != "view_cursor" && it !in GitHubResultViews.SAVED_CONTEXT_KEYS }) {
                val evidence = (arguments["evidence_id"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }
                val restoreEvidence = operation == "github_read" && evidence != null && (arguments["view_cursor"] as? JsonPrimitive)?.content?.contains(':') != true
                val corrected = if (restoreEvidence) {
                    buildJsonObject {
                        put("evidence_id", requireNotNull(evidence))
                        listOf("owner", "repo").forEach { key -> arguments[key]?.let { put(key, it) } }
                    }
                } else {
                    buildJsonObject { put("view_cursor", arguments.getValue("view_cursor")) }
                }
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("schema", "github.arguments.v1")
                            put("operation", operation)
                            put("message", if (restoreEvidence) "evidence_id and view_cursor are different handles. Reopen source evidence with evidence_id alone, without an invented cursor. Nothing was dispatched." else "Use only view_cursor. Repository, commit and range fields belong to a new read, not a saved page. Nothing was dispatched.")
                            put(
                                "correction_call",
                                buildJsonObject {
                                    put("tool", definition.name)
                                    put("arguments", corrected)
                                }
                            )
                        }
                    ),
                    true,
                    errorCode = "INVALID_ARGUMENTS",
                    dispatched = false
                )
            }
            val cursor = (arguments["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return AgentToolResult(callId, ToolResultContent.Text("view_cursor must be the exact saved cursor string."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
            if (!cursor.contains(':')) {
                return AgentToolResult(callId, ToolResultContent.Text("Invalid saved-view cursor. Use the exact continuation_call arguments returned by the previous result. To read an evidence_id, omit view_cursor and provide evidence_id alone with the repository context."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
            }
            val context = JsonObject(arguments - "view_cursor")
            var saved = views.read(scope, operation, definition.name, cursor, callId, context)
            if (saved.errorCode == "SAVED_VIEW_UNAVAILABLE") {
                var restoreState = "NOT_FOUND"
                var restoreErrorHash: String? = null
                val snapshot = try {
                    loadSavedView(cursor.substringBefore(':'))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (missing: dev.chungjungsoo.gptmobile.data.github.GitHubSavedViewStore.MissingSavedView) {
                    restoreState = missing.reason
                    null
                } catch (error: Exception) {
                    restoreState = "LOAD_FAILED"
                    restoreErrorHash = dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest("github.saved-view.restore-error.v1\u0000${error.javaClass.name}")
                    null
                }
                if (snapshot != null) {
                    if (views.restore(scope, operation, definition.name, cursor, snapshot)) {
                        restoreState = "RESTORED"
                        saved = views.read(scope, operation, definition.name, cursor, callId, context)
                    } else {
                        restoreState = "IDENTITY_REJECTED"
                    }
                }
                saved = saved.copy(
                    executionReceipt = JsonObject(
                        saved.executionReceipt.orEmpty() + buildJsonObject {
                            put("saved_view_restore_state", restoreState)
                            restoreErrorHash?.let { put("saved_view_restore_error_hash", it) }
                        }
                    )
                )
            }
            val retained = retainSavedView(saved, cursor)
            savedViewAuthorizationFailure(callId)?.let { return it }
            return retained
        }
        // Explicit repository overrides must be complete; both absent uses the pinned task context.
        val explicitOwner = (arguments["owner"] as? JsonPrimitive)?.content?.trim()
        val explicitRepo = (arguments["repo"] as? JsonPrimitive)?.content?.trim()
        if ((explicitOwner != null || explicitRepo != null) &&
            (explicitOwner.isNullOrBlank() || explicitRepo.isNullOrBlank())
        ) {
            return AgentToolResult(
                callId,
                ToolResultContent.Text("Specify both owner and repo, or omit both to use the selected GitHub repository."),
                true,
                errorCode = "INVALID_ARGUMENTS",
                dispatched = false
            )
        }
        if (operation in names) {
            val readRules = linkedSetOf<GitHubOperationArguments.ReadRule>()
            val errors = ToolArgumentValidator.errors(arguments, definition.inputSchema).ifEmpty {
                GitHubOperationArguments.errors(operation, arguments, readRules)
            }
            if (errors.isNotEmpty()) {
                val budget = currentCoroutineContext()[ToolArgumentRepairScope]?.budget
                return budget?.rejection(callId, definition, arguments, errors, readRules)
                    ?: ToolArgumentValidator.rejection(callId, definition.name, arguments, definition.inputSchema, errors, readRules)
            }
        }
        if (operation == "github_write") {
            val paths = mutableSetOf<String>()
            val invalid = (arguments["files"] as? JsonArray).orEmpty().mapIndexedNotNull { index, raw ->
                val path = ((raw as? JsonObject)?.get("path") as? JsonPrimitive)?.content.orEmpty()
                val failure = runCatching { dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient.filePath(path) }.exceptionOrNull()
                when {
                    failure != null -> "files[$index].path: ${failure.message}"
                    !paths.add(path) -> "files[$index].path duplicates another change: $path"
                    else -> null
                }
            }
            if (invalid.isNotEmpty()) return AgentToolResult(callId, ToolResultContent.Text(invalid.joinToString("; ") + ". No files were staged."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        if (operation == "github_context" && (arguments["action"] as? JsonPrimitive)?.content == "changes") {
            val base = (arguments["base_sha"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (base.isNullOrBlank()) {
                return AgentToolResult(
                    callId,
                    ToolResultContent.Text("github_context action=changes requires a non-blank base_sha (commit SHA or ref). Resolve the intended comparison base first; for PR files use github_pr action=context with include_files=true. Nothing was dispatched."),
                    true,
                    errorCode = "INVALID_ARGUMENTS",
                    dispatched = false
                )
            }
        }
        if (operation == "github_search") {
            val query = (arguments["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (query.isNullOrBlank() || query.length > 200) {
                return AgentToolResult(
                    callId,
                    ToolResultContent.Text("github_search requires a non-blank query of at most 200 characters. Use mode=paths to find file names; use index_cursor to continue a previous search with the same query."),
                    true,
                    errorCode = "INVALID_ARGUMENTS",
                    dispatched = false
                )
            }
        }
        if (operation == "github_pr") {
            val action = (arguments["action"] as? JsonPrimitive)?.content.orEmpty().ifBlank { "context" }
            val requiredFields = when (action) {
                "headers", "context", "comments", "reviews", "threads" -> listOf("pull_number")
                "create" -> listOf("title", "head", "base")
                in dev.chungjungsoo.gptmobile.data.github.GitHubCollaboration.prActions -> listOf("pull_number", "expected_head_sha")
                "update", "merge", "ready", "draft" -> listOf("pull_number", "expected_head_sha")
                "comment" -> listOf("pull_number", "body", "expected_head_sha")
                "review" -> listOf("pull_number", "expected_head_sha")
                "reply" -> listOf("pull_number", "comment_id", "body", "expected_head_sha")
                else -> emptyList()
            }
            val shaActions = dev.chungjungsoo.gptmobile.data.github.GitHubCollaboration.prActions + setOf("update", "merge", "comment", "reply", "review", "ready", "draft")
            if (action in shaActions) {
                val expected = (arguments["expected_head_sha"] as? JsonPrimitive)?.content.orEmpty()
                if (!expected.matches(Regex("[a-fA-F0-9]{40}"))) {
                    return AgentToolResult(
                        callId,
                        ToolResultContent.Text("GitHub PR $action requires a current 40-character expected_head_sha. Refresh the PR head before retrying."),
                        true,
                        errorCode = "INVALID_ARGUMENTS",
                        dispatched = false
                    )
                }
            }
            val missing = requiredFields.filter { key ->
                val value = arguments[key]
                value == null || (value is JsonPrimitive && value.content.isBlank())
            }
            if (missing.isNotEmpty()) {
                return AgentToolResult(
                    callId,
                    ToolResultContent.Text("GitHub PR $action requires: " + missing.joinToString(", ") + ". For an unknown PR number, use github_pr action=list to discover pull requests, then retry with the observed pull_number. Nothing was dispatched."),
                    true,
                    errorCode = "INVALID_ARGUMENTS",
                    dispatched = false
                )
            }
        }
        if (arguments["verify_live"] == JsonPrimitive(true) && !dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(definition.name, arguments)) {
            return AgentToolResult(callId, ToolResultContent.Text("Live verification is available only for read-only operations."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        val native = nativeArguments(operation, JsonObject(arguments - "response_detail"))
            ?: return AgentToolResult(callId, ToolResultContent.Text("Unsupported GitHub operation."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        pagination.completedPage(operation, native)?.let { lastPage ->
            val error = pagination.completionError(operation, native)
            return AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("pagination_complete", error == null)
                        put("last_remote_page", lastPage)
                        put("message", if (error != null) "Remote pagination stopped with incomplete coverage. Use retained results or deliberately refresh page 1 with verify_live=true." else "The retained response ended remote pagination. Read its saved view_cursor for local fragments, or refresh page 1 with verify_live=true for a new remote observation.")
                    }
                ),
                error != null,
                errorCode = error,
                dispatched = false
            )
        }
        var resolved = native
        var effectiveArguments = arguments
        var executed = backend.execute(callId, native)
        val rejectedRead = (executed.content as? ToolResultContent.Json)?.value as? JsonObject
        val initialReceipt = executed.executionReceipt
        if (backend is GitHubTool &&
            executed.isError &&
            rejectedRead != null &&
            initialReceipt != null &&
            listOf("mutation_requests", "accepted_mutations", "unknown_mutations", "unobserved_read_requests", "read_http_failures", "observation_mismatches")
                .all { initialReceipt[it] == JsonPrimitive(0) } &&
            initialReceipt["requires_reconciliation"] == JsonPrimitive(false)
        ) {
            val corrected = GitHubSourceReadRecovery.correctedArguments(operation, native, rejectedRead)
            if (corrected != null) {
                // Keep the provider's call identity and re-enter the authorized native
                // backend exactly once. Saved-view selectors and writes are ineligible.
                val modelArguments = JsonObject(arguments + (corrected - "action"))
                if (ToolArgumentValidator.errors(modelArguments, definition.inputSchema).isEmpty() &&
                    GitHubOperationArguments.errors(operation, modelArguments).isEmpty() &&
                    nativeArguments(operation, JsonObject(modelArguments - "response_detail")) == corrected
                ) {
                    val rejected = executed
                    resolved = corrected
                    effectiveArguments = modelArguments
                    val recovered = try {
                        backend.execute(callId, corrected)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata.authorityPrecheck(callId, definition.name, failure)
                            ?: AgentToolResult(callId, ToolResultContent.Text("GitHub authorization could not be revalidated for the corrected source read. Restore the original authorized workspace before resuming."), true, errorCode = "GITHUB_AUTHORIZATION_UNVERIFIED", dispatched = false)
                    }
                    executed = recovered.copy(
                        dispatched = rejected.dispatched || recovered.dispatched,
                        executionReceipt = sourceReadRecoveryReceipt(rejected, recovered, native, corrected, modelArguments)
                    )
                }
            }
        }
        val original = executed.copy(
            executionReceipt = JsonObject(
                executed.executionReceipt.orEmpty() + buildJsonObject {
                    put("resolved_arguments", GitHubArgumentProvenance.sanitize(resolved))
                }
            )
        )
        val validation = ((original.content as? ToolResultContent.Json)?.value as? JsonObject)
            ?.takeIf { original.isError && it["schema"] in setOf(JsonPrimitive("github.read_validation.v1"), JsonPrimitive("github.ref-admission.v1"), JsonPrimitive("github.repository-admission.v1")) }
        var result = if (validation != null) {
            fun scopedCorrection(correction: JsonObject): JsonObject {
                val name = (correction["tool"] as? JsonPrimitive)?.content.orEmpty()
                return if (name in setOf("github_search", "github_read", "github_context")) {
                    JsonObject(correction + ("tool" to JsonPrimitive(name + definition.name.removePrefix(operation))))
                } else {
                    correction
                }
            }
            val scoped = buildJsonObject {
                validation.forEach { (name, value) -> put(name, value) }
                (validation["corrective_actions"] as? JsonArray)?.let { actions -> put("corrective_actions", JsonArray(actions.map { raw -> (raw as? JsonObject)?.let(::scopedCorrection) ?: raw })) }
                listOf("recovery_call", "correction_call", "source_recovery_call").forEach { key ->
                    (validation[key] as? JsonObject)?.let { put(key, scopedCorrection(it)) }
                }
            }
            original.copy(content = ToolResultContent.Json(scoped))
        } else if (operation == "github_read" && original.errorCode == "EVIDENCE_UNAVAILABLE") {
            original.copy(
                content = ToolResultContent.Json(
                    buildJsonObject {
                        put("schema", "github.arguments.v1")
                        put("error_code", "EVIDENCE_UNAVAILABLE")
                        put("message", "Source evidence is unavailable in this authorized scope. Use the exact recovery_call to find retained sources, or source_recovery_call from the original receipt to reopen the pinned ranges.")
                        put(
                            "recovery_call",
                            buildJsonObject {
                                put("tool", "github_context" + definition.name.removePrefix(operation))
                                put(
                                    "arguments",
                                    buildJsonObject {
                                        put("action", "recovery")
                                        listOf("owner", "repo").forEach { key -> arguments[key]?.let { put(key, it) } }
                                    }
                                )
                            }
                        )
                    }
                )
            )
        } else if (operation == "github_read" && original.isError && original.errorCode == "INVALID_ARGUMENTS" && original.content.toString().contains("Path is not in the pinned tree")) {
            val requestedRanges = (arguments["ranges"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val failureText = (original.content as? ToolResultContent.Text)?.text.orEmpty()
            val rejectedPath = failureText.substringAfter("Path is not in the pinned tree:", "").lineSequence().firstOrNull()?.trim().orEmpty()
            val range = requestedRanges.firstOrNull { (it["path"] as? JsonPrimitive)?.content == rejectedPath } ?: requestedRanges.firstOrNull()
            val path = (range?.get("path") as? JsonPrimitive)?.content.orEmpty()
            val searchName = "github_search" + definition.name.removePrefix(operation)
            original.copy(
                content = ToolResultContent.Json(
                    buildJsonObject {
                        put("error_code", "INVALID_ARGUMENTS")
                        put("message", "Requested path is absent from the pinned tree. Discover existing paths before reading; no write was dispatched.")
                        put(
                            "correction_call",
                            buildJsonObject {
                                put("tool", searchName)
                                put(
                                    "arguments",
                                    buildJsonObject {
                                        listOf("owner", "repo", "commit_sha").forEach { key -> arguments[key]?.let { put(key, it) } }
                                        put("mode", "paths")
                                        put("query", "*")
                                        path.substringBeforeLast('/', "").takeIf(String::isNotBlank)?.let { put("path_prefix", it) }
                                    }
                                )
                            }
                        )
                    }
                ),
                retainedContent = original.retainedContent ?: original.content
            )
        } else {
            original
        }
        var data = when (val content = result.content) {
            is ToolResultContent.Json -> content.value as? JsonObject
            is ToolResultContent.Text -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(content.text) as? JsonObject }.getOrNull()
            else -> null
        }
        if (!result.isError && data != null) {
            val assessment = pagination.assessPage(operation, resolved, data)
            if (assessment.errorCode != null) {
                return withPaginationState(result, assessment.data).copy(
                    executionReceipt = JsonObject(result.executionReceipt.orEmpty() + mapOf("traversal_state" to assessment.data.getValue("traversal_state"), "has_more" to JsonPrimitive(false), "pagination_progress" to assessment.data.getValue("pagination_progress"))),
                    isError = true,
                    errorCode = assessment.errorCode
                )
            }
            if (assessment.data != data) {
                result = withPaginationState(result, assessment.data).copy(
                    executionReceipt = JsonObject(result.executionReceipt.orEmpty() + ("pagination_progress" to assessment.data.getValue("pagination_progress")))
                )
                data = assessment.data
            } else {
                pagination.observe(operation, resolved, data)
            }
        }
        val paged = if (!result.isError && data?.get("has_more") == JsonPrimitive(true) && "continuation" !in data && operation != "github_search") {
            withPaginationState(result, JsonObject(data + ("continuation" to JsonObject(resolved + ("page" to JsonPrimitive(((effectiveArguments["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1) + 1))))))
        } else {
            result
        }
        val outputAllowance = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.ToolOutputAllowance]?.bytes ?: 8 * 1024
        val body = (paged.content as? ToolResultContent.Json)?.value as? JsonObject ?: data
        val projected = body?.let { dev.chungjungsoo.gptmobile.data.github.GitHubResultProjection.project(operation, effectiveArguments, it) }
        val presented = if (projected != null && projected != body && !paged.isError) {
            paged.copy(content = ToolResultContent.Json(projected), retainedContent = paged.retainedContent ?: paged.content)
        } else {
            paged
        }
        return retainSavedView(views.present(scope, operation, definition.name, presented, effectiveArguments, maxPageBytes = outputAllowance))
    }

    /** Original rows stay intact, but every consumer must see the same traversal state and exact recipe. */
    private fun withPaginationState(result: AgentToolResult, data: JsonObject): AgentToolResult {
        val retained = result.retainedContent ?: result.content
        val source = when (retained) {
            is ToolResultContent.Json -> retained.value as? JsonObject
            is ToolResultContent.Text -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(retained.text) as? JsonObject }.getOrNull()
            else -> null
        }
        val stateFields = setOf("pagination_progress", "has_more", "complete", "continuation", "continuation_call", "remote_continuations", "next_page", "traversal_state", "upstream_has_more", "reason", "message")
        val evidence = source?.let { JsonObject(it.filterKeys { key -> key !in stateFields } + data.filterKeys { key -> key in stateFields }) }
        return result.copy(content = ToolResultContent.Json(data), retainedContent = evidence?.let { ToolResultContent.Json(it) } ?: retained)
    }

    private suspend fun retainSavedView(result: AgentToolResult, requestedCursor: String? = null): AgentToolResult {
        val (id, checkpoint) = views.checkpoint(scope, result) ?: return result
        val body = (result.content as? ToolResultContent.Json)?.value as? JsonObject
        fun cursor(key: String): JsonPrimitive? = (((body?.get(key) as? JsonObject)?.get("arguments") as? JsonObject)?.get("view_cursor") as? JsonPrimitive)
        val issued = (checkpoint["issued_cursors"] as? JsonArray).orEmpty().toSet()
        // Every newly emitted handle is included, along with the handle consumed
        // by this call. Recovery proves exact handles without quadratic copies of
        // the entire grant set in every primary evidence receipt.
        val proofs = listOfNotNull(requestedCursor?.let(::JsonPrimitive), cursor("recovery_call"), cursor("continuation_call")).distinct().filter { it in issued }
        val status = try {
            if (saveSavedView == null) {
                "SESSION_ONLY"
            } else {
                saveSavedView.invoke(id, checkpoint)
                "RETAINED"
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Returning an accepted write is essential even if optional page retention fails.
            // Its mutation journal and primary evidence receipt remain authoritative.
            "UNAVAILABLE"
        }
        if (status == "UNAVAILABLE") views.markPersistenceUnavailable(scope, id, proofs)
        // Failed handles remain in the captured snapshot even if the process
        // stops after saving it but before the primary receipt is recorded.
        val retainedProofs = if (status == "RETAINED") {
            (proofs + (checkpoint["failed_persistence_cursors"] as? JsonArray).orEmpty()).distinct().filter { it in issued }
        } else {
            proofs
        }
        return result.copy(
            executionReceipt = JsonObject(
                result.executionReceipt.orEmpty() + mapOf(
                    "saved_view_id" to JsonPrimitive(id),
                    "saved_view_issued_cursors" to JsonPrimitive((checkpoint["issued_cursors"] as? JsonArray).orEmpty().size),
                    "saved_view_cursor_ids" to JsonArray(retainedProofs),
                    "saved_view_persistence" to JsonPrimitive(status)
                )
            )
        )
    }

    private fun sourceReadRecoveryReceipt(rejected: AgentToolResult, recovered: AgentToolResult, original: JsonObject, corrected: JsonObject, modelArguments: JsonObject): JsonObject {
        val before = rejected.executionReceipt.orEmpty()
        val after = recovered.executionReceipt.orEmpty()
        fun count(key: String) = ((before[key] as? JsonPrimitive)?.intOrNull ?: 0) + ((after[key] as? JsonPrimitive)?.intOrNull ?: 0)
        val observed = count("read_requests") > 0 && count("read_response_observations") + count("recovered_read_requests") == count("read_requests") && count("observation_mismatches") == 0
        return JsonObject(
            after + buildJsonObject {
                put("schema", "github.dispatch.v1")
                listOf("read_requests", "read_response_observations", "recovered_read_requests", "read_http_failures", "omitted_failed_requests", "expected_absence_responses", "unobserved_read_requests", "observation_mismatches", "cached_read_observations").forEach { put(it, count(it)) }
                listOf("mutation_requests", "accepted_mutations", "rejected_mutations", "unknown_mutations").forEach { put(it, count(it)) }
                put("requires_reconciliation", false)
                put("parent_result_successful", !recovered.isError)
                recovered.errorCode?.let { put("error_code", it) }
                put("failed_requests", JsonArray((before["failed_requests"] as? JsonArray).orEmpty() + (after["failed_requests"] as? JsonArray).orEmpty()))
                put("live_read_performed", observed)
                put(
                    "live_read_status",
                    if (observed) {
                        "PERFORMED"
                    } else if (count("read_requests") > 0) {
                        "RESPONSE_NOT_FULLY_OBSERVED"
                    } else {
                        after["live_read_status"]?.let { (it as? JsonPrimitive)?.content }.orEmpty().ifBlank { "NOT_REQUESTED" }
                    }
                )
                put(
                    "read_source",
                    if (observed) if (count("cached_read_observations") > 0) "MIXED" else "LIVE" else if (count("cached_read_observations") > 0) {
                        "RETAINED"
                    } else {
                        "UNVERIFIED"
                    }
                )
                if (!recovered.isError && observed) put("phase", "OBSERVED")
                if ("phase" !in after) put("phase", if (count("read_requests") > 0) "READ_FAILED" else "PREFLIGHT_REJECTED")
                put("corrected_arguments", modelArguments)
                put(
                    "source_read_recovery",
                    buildJsonObject {
                        put("schema", "github.source-read-recovery.v1")
                        put("attempts", 1)
                        put("call_id", recovered.callId)
                        put("original_error_code", rejected.errorCode.orEmpty())
                        put("original_arguments", GitHubArgumentProvenance.sanitize(original))
                        put("corrected_arguments", GitHubArgumentProvenance.sanitize(corrected))
                        put("precheck_receipt", JsonObject(before))
                        put("corrected_receipt", JsonObject(after))
                        put("successful", !recovered.isError)
                    }
                )
            }
        )
    }

    private suspend fun savedViewAuthorizationFailure(callId: String): AgentToolResult? = try {
        validateSavedViewAccess()
        null
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AgentToolResult(
            callId,
            ToolResultContent.Text("The current GitHub account or repository authorization could not be verified for this saved result. Reconnect or restore the original authorized workspace. No saved content was disclosed and no operation was repeated."),
            true,
            errorCode = "SAVED_VIEW_AUTHORIZATION_CHANGED",
            dispatched = false
        )
    }

    companion object {
        val names = setOf("github_context", "github_search", "github_read", "github_pr", "github_ci", "github_watch_checks", "github_stage_patch", "github_commit", "github_write", "github_release", "github_issue", "github_repository")
        fun operationName(name: String): String? = name.substringBefore("__").takeIf { it in names }
        fun immutableRead(name: String, arguments: JsonObject): Boolean = operationName(name) in setOf("github_read", "github_search") &&
            (arguments["commit_sha"] as? JsonPrimitive)?.content?.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) == true &&
            "view_cursor" !in arguments

        fun nativeArguments(name: String, args: JsonObject): JsonObject? {
            val op = operationName(name) ?: return null
            if ("view_cursor" in args) return JsonObject(args + ("action" to JsonPrimitive("read_saved_result")))
            val canonical = if (op == "github_pr") dev.chungjungsoo.gptmobile.data.github.GitHubActionIdentity.canonicalArguments(name, args) else args
            val requested = (canonical["action"] as? JsonPrimitive)?.content.orEmpty()
            val action = when (op) {
                "github_repository" -> when (requested) {
                    "create" -> "create_repository"
                    "fork" -> "fork_repository"
                    "import" -> "import_repository"
                    "clone" -> "clone_repository"
                    "status" -> "repository_lifecycle_status"
                    "", "capabilities" -> "repository_lifecycle_capabilities"
                    else -> return null
                }
                "github_context" -> when (requested) {
                    "", "resolve" -> "resolve_context"
                    "status" -> "repo_status"
                    "index" -> "index_status"
                    "changes" -> "changed_since"
                    "efficiency" -> "efficiency"
                    "capabilities" -> "write_capabilities"
                    "branches" -> "list_branches"
                    "branch" -> "branch_context"
                    "branch_dependencies" -> "branch_dependencies"
                    "commits" -> "list_commits"
                    "commit" -> "get_commit_detail"
                    "commit_search" -> "search_commits"
                    "checks" -> "get_commit_status_summary"
                    "repository" -> "repository_details"
                    "repositories" -> "search_repositories"
                    "recovery" -> "recovery_context"
                    else -> return null
                }
                "github_search" -> if (dev.chungjungsoo.gptmobile.data.github.GitHubInspection.issueSearchQualifier.containsMatchIn((args["query"] as? JsonPrimitive)?.content.orEmpty())) "search_issues" else "search_repository"
                "github_read" -> "read_ranges"
                "github_stage_patch" -> "stage_patch"
                "github_watch_checks" -> "watch_checks"
                "github_commit" -> when (requested) {
                    "", "commit", "commit_staged_patch" -> "commit_staged_patch"
                    "branch" -> "create_branch"
                    "delete_branch" -> "delete_branch"
                    "delete_fixture_branch" -> "delete_fixture_branch"
                    else -> return null
                }
                "github_write" -> when (requested) {
                    "", "commit" -> "commit_files"
                    else -> return null
                }
                "github_pr" -> when (requested) {
                    "headers" -> "pr_headers"
                    "", "context" -> "pr_context"
                    "list" -> "list_pull_requests"
                    "comments" -> "get_pull_request_comments"
                    "reviews" -> "pull_request_reviews"
                    "threads" -> "pr_threads"
                    in dev.chungjungsoo.gptmobile.data.github.GitHubCollaboration.prActions -> "pr_$requested"
                    "create" -> "create_pull_request"
                    "update" -> "update_pull_request"
                    "ready" -> "mark_ready_for_review"
                    "draft" -> "convert_pull_request_to_draft"
                    "comment" -> "create_issue_comment"
                    "reply" -> "reply_pull_request_comment"
                    "review" -> if ((args["comments"] as? JsonArray).isNullOrEmpty()) "submit_pull_request_review" else "create_pull_request_review"
                    "merge" -> "merge_pull_request"
                    else -> return null
                }
                "github_ci" -> when (requested) {
                    "" -> "list_workflows"
                    "failure" -> "ci_failure_digest"
                    "workflows" -> "list_workflows"
                    "definitions" -> "workflow_catalog"
                    "runs" -> "list_workflow_runs"
                    "run" -> "get_workflow_run"
                    "status" -> when {
                        args["job_id"] != null -> "get_workflow_job"
                        args["run_id"] != null -> "get_workflow_run"
                        else -> "get_commit_status_summary"
                    }
                    "jobs" -> if (args["job_id"] != null && args["run_id"] == null) "get_workflow_job" else "list_workflow_jobs"
                    "job" -> "get_workflow_job"
                    "logs" -> "get_job_logs"
                    "artifacts" -> "list_workflow_artifacts"
                    "dispatch" -> "dispatch_workflow"
                    "rerun", "rerun_failed_jobs" -> "rerun_failed_jobs"
                    "rerun_all" -> "rerun_workflow"
                    "cancel" -> "cancel_workflow"
                    else -> return null
                }
                "github_release" -> when (requested) {
                    "", "status" -> "release_status"
                    "publish" -> "publish_release"
                    "tags" -> "list_tags"
                    "tag" -> "tag_context"
                    "create_tag" -> "create_tag"
                    "list" -> "list_releases"
                    "detail" -> "get_release_by_tag"
                    else -> return null
                }
                "github_issue" -> when (requested) {
                    "headers" -> "issue_headers"
                    "" -> if (args["issue_number"] == null) "list_issues" else "issue_context"
                    "list" -> "list_issues"
                    "context" -> "issue_context"
                    "comments" -> "issue_comments"
                    "history" -> "issue_events"
                    in dev.chungjungsoo.gptmobile.data.github.GitHubCollaboration.issueActions -> "issue_$requested"
                    "create" -> "create_issue"
                    "update" -> "update_issue"
                    else -> return null
                }
                else -> return null
            }
            val translated = args.toMutableMap()
            if (action == "create_branch") args["from_branch"]?.let { translated["base"] = it }
            if (action == "changed_since") args["base_sha"]?.let { translated["base"] = it }
            if (action == "pr_context") args["expected_head_sha"]?.let { translated["expected_head"] = it }
            if (action == "get_commit_status_summary" && args["ref"] == null) args["branch"]?.let { translated["ref"] = it }
            translated["action"] = JsonPrimitive(action)
            return JsonObject(translated)
        }

        fun taskTools(task: String): Set<String> {
            if (dev.chungjungsoo.gptmobile.data.github.GitHubTestChecklist.integration(task)) return linkedSetOf("github_context", "github_pr", "github_ci", "github_search", "github_read", "github_watch_checks")
            if (dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest.matches(task)) return linkedSetOf("github_context", "github_pr")
            val text = task.lowercase(java.util.Locale.ROOT).replace("github_pull_request", "github_pr")
            val explicitlyNamed = names.filter { name ->
                Regex("(?<![a-z0-9_])" + Regex.escape(name) + "(?:__[a-z0-9_-]+)?(?![a-z0-9_])").containsMatchIn(text)
            }
            val issueRequest = Regex("\\b(?:list|show|inspect|read|create|update|close|reopen|triage)\\s+(?:(?:the|all|open|closed|github|repository)\\s+)*issues?\\b|\\bissue\\s*#?\\d+\\b").containsMatchIn(text)
            if (explicitlyNamed.isEmpty() && !isGitHubTask(text) && !issueRequest && !Regex("\\b(code|source|ci|pr)\\b").containsMatchIn(text)) return emptySet()
            val selected = linkedSetOf("github_context", "github_search", "github_read")
            if (dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryLifecycle.intent(task) != null) selected += "github_repository"
            selected += explicitlyNamed
            if (Regex("\\b(pr|pull request|review|merge)\\b").containsMatchIn(text)) selected += "github_pr"
            if (Regex("\\b(ci|build|test failure|workflow|pipeline)\\b").containsMatchIn(text)) selected += "github_ci"
            if (Regex("\\b(fix(?:es|ed|ing)?|repair(?:s|ed|ing)?|correct(?:ions?|ed|ing)?|patch(?:es|ed|ing)?|edit(?:s|ed|ing)?|changes?|implement(?:ation|ed|ing)?)\\b").containsMatchIn(text)) selected += "github_stage_patch"
            if (Regex("\\b(commit|committing|push|pushing|branch)\\b").containsMatchIn(text)) selected += "github_commit"
            if (Regex("\\b(write|writing|create files?|edit(?:s|ed|ing)?|modify|changes?|fix(?:es|ed|ing)?|repair(?:s|ed|ing)?|correct(?:ions?|ed|ing)?|add(?:ed|ing)?|updat(?:e|ed|ing)|delet(?:e|ed|ing)|remove files?|commit|committing|push|pushing|patch(?:es|ed|ing)?|implement(?:ation|ed|ing)?|apply changes)\\b").containsMatchIn(text)) selected += "github_write"
            if (Regex("\\bissues?\\b").containsMatchIn(text) || issueRequest) selected += "github_issue"
            if ("release" in text || "tag" in text) selected += "github_release"
            if (Regex("\\b(watch|monitor|subscribe|ci|checks)\\b").containsMatchIn(text)) selected += "github_watch_checks"
            return selected
        }

        fun definition(operation: String, modelName: String = operation): AgentToolDefinition {
            require(operation in names)
            fun sourceName(source: String): String = if (source == operation) {
                modelName
            } else if ("__" in modelName) {
                source + "__" + modelName.substringAfter("__")
            } else {
                source
            }
            fun link(action: String, source: String, sourceAction: String, purpose: String) = AgentToolWorkflowLink(action, sourceName(source), sourceAction, purpose)
            val workflowLinks = when (operation) {
                "github_write" -> listOf(link("commit", "github_read", "ranges", "Inspect existing file content and blob SHA before preparing a replacement."))
                "github_stage_patch" -> listOf(link("default", "github_read", "ranges", "Inspect the pinned file before selecting an exact patch anchor."))
                "github_commit" -> listOf(link("commit_staged_patch", "github_stage_patch", "default", "A staged or restored patch can provide the stage ID."))
                "github_pr" -> listOf(link("review", "github_pr", "context", "Read the current PR head before submitting a review."), link("merge", "github_pr", "context", "Inspect current PR merge health before requesting a merge."), link("merge", "github_ci", "status", "Inspect current checks before requesting a merge."))
                "github_ci" -> listOf(link("logs", "github_ci", "jobs", "Job listings can provide the job ID required for logs."))
                "github_watch_checks" -> listOf(link("default", "github_pr", "context", "A PR context can identify the pull request and current head."), link("default", "github_ci", "runs", "Workflow runs can provide a run ID for a run-specific watch."))
                else -> emptyList()
            }
            fun field(type: String) = buildJsonObject { put("type", type) }
            fun integer(
                min: Int = 1,
                max: Int = Int.MAX_VALUE,
                description: String = "",
                default: Int? = null
            ) = buildJsonObject {
                put("type", "integer")
                put("minimum", min)
                put("maximum", max)
                if (description.isNotBlank()) put("description", description)
                default?.let { put("default", it) }
            }
            fun choice(vararg names: String) = buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(names.map(::JsonPrimitive)))
            }
            val properties = linkedMapOf("owner" to field("string"), "repo" to field("string"), "commit_sha" to field("string"), "view_cursor" to field("string"))
            // Snapshot-backed search/read do not qualify remote transport. Keep
            // the cache-bypass control on remote facades without repeated prose.
            if (operation in setOf("github_context", "github_pr", "github_ci", "github_issue", "github_release")) {
                properties["verify_live"] = field("boolean")
            }
            when (operation) {
                "github_repository" -> {
                    listOf("owner", "repo", "commit_sha").forEach(properties::remove)
                    properties["action"] = choice("capabilities", "create", "fork", "import", "clone", "status")
                    listOf("destination_owner", "destination_repo", "source_owner", "source_repo", "source_ref", "source_commit_sha", "description", "expected_head_sha", "operation_id").forEach { properties[it] = field("string") }
                    properties["owner_type"] = choice("user", "organization")
                    properties["visibility"] = choice("public", "private")
                    properties["copy_mode"] = choice("snapshot", "history")
                    properties["operation"] = choice("create_repository", "fork_repository", "import_repository", "clone_repository")
                    properties["expected_repository_id"] = buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                    }
                    properties["auto_init"] = field("boolean")
                    properties["default_branch_only"] = field("boolean")
                }
                "github_context" -> {
                    properties["action"] = choice("resolve", "status", "index", "changes", "efficiency", "capabilities", "branches", "branch", "branch_dependencies", "commits", "commit", "commit_search", "checks", "repository", "repositories", "recovery")
                    properties["ref"] = field("string")
                    properties["branch"] = field("string")
                    properties["query"] = field("string")
                    properties["base_sha"] = field("string")
                    properties["stage_id"] = field("string")
                    properties["fixture_plan"] = buildJsonObject { put("enum", JsonArray(listOf(JsonPrimitive(dev.chungjungsoo.gptmobile.data.github.GitHubOwnedFixtures.PLAN)))) }
                    properties["engineering_task_id"] = buildJsonObject {
                        put("type", "string")
                        put("pattern", "^[A-Za-z0-9_-]{1,128}$")
                    }
                    properties["refresh"] = field("boolean")
                    properties["page"] = field("integer")
                    properties["per_page"] = integer(1, 100, default = 30)
                    properties["include_patches"] = field("boolean")
                }
                "github_search" -> {
                    properties["action"] = choice("search", "search_repository", "search_code")
                    properties["path_prefix"] = field("string")
                    properties["query"] = buildJsonObject {
                        put("type", "string")
                        put("minLength", 1)
                        put("maxLength", 200)
                    }
                    properties["mode"] = choice("literal", "definitions", "references", "paths", "tests", "patterns")
                    properties["index_cursor"] = field("integer")
                    properties["search_id"] = field("string")
                    properties["file_extension"] = field("string")
                    properties["path_suffix"] = field("string")
                    properties["case_sensitive"] = field("boolean")
                    properties["fuzzy_match"] = field("boolean")
                    properties["fuzzy_threshold"] = buildJsonObject {
                        put("type", "number")
                        put("minimum", 0.5)
                        put("maximum", 1.0)
                    }
                    listOf("min_lines", "max_lines").forEach { properties[it] = field("integer") }
                    properties["context_lines"] = integer(0, 100)
                    properties["max_new_blobs"] = integer(1, 100, default = 4)
                    properties["batch_size"] = integer(1, 1000, default = 1000)
                    properties["page"] = field("integer")
                }
                "github_read" -> {
                    properties["action"] = choice("ranges")
                    properties["evidence_id"] = field("string")
                    properties["ranges"] = buildJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 8)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("path", field("string"))
                                        put("start_line", integer())
                                        put("end_line", integer(-1))
                                        put("max_lines", integer(1, 2000))
                                        put("start_offset", integer(0))
                                    }
                                )
                                put("required", JsonArray(listOf("path").map(::JsonPrimitive)))
                                put("additionalProperties", false)
                            }
                        )
                    }
                }
                "github_stage_patch" -> {
                    listOf("target_file", "search_snippet", "replacement_snippet").forEach { properties[it] = field("string") }
                    properties["commit_immediately"] = buildJsonObject {
                        put("type", "boolean")
                        put("description", "Persist the exact stage, then submit its guarded commit in the same invocation. Requires branch, expected_head_sha and message. Recovery reuses the durable stage/publication fence; this is not a cross-endpoint GitHub transaction.")
                    }
                    properties["message"] = field("string")
                    properties["restore_stage_id"] = field("string")
                    properties["expected_head_sha"] = field("string")
                    properties["branch"] = field("string")
                    properties["edits"] = buildJsonObject {
                        put("type", "array")
                        put("maxItems", 20)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        listOf("path", "expected_blob_sha", "old_text", "new_text").forEach { put(it, field("string")) }
                                        put("occurrence", field("integer"))
                                    }
                                )
                                put("required", JsonArray(listOf("path", "expected_blob_sha", "old_text", "new_text").map(::JsonPrimitive)))
                            }
                        )
                    }
                }
                "github_commit" -> {
                    properties["action"] = choice("commit", "commit_staged_patch", "branch", "delete_branch", "delete_fixture_branch")
                    listOf("stage_id", "branch", "message", "expected_head_sha", "from_branch", "origin_head_sha").forEach { properties[it] = field("string") }
                    properties["fixture_plan"] = choice(dev.chungjungsoo.gptmobile.data.github.GitHubOwnedFixtures.PLAN)
                    properties["select_created_branch"] = buildJsonObject {
                        put("type", "boolean")
                        put("description", "For action=branch only. Defaults to true. Use false for an explicitly authorized disposable fixture; read back its ref without changing the selected workspace.")
                    }
                    properties["expected_head_sha"] = buildJsonObject {
                        put("type", "string")
                        put("pattern", "^[a-fA-F0-9]{40}$")
                        put("description", "Required safety lease. For action=branch use the observed source branch head; commit_sha is not a substitute. For commit/delete_branch use the current target branch head.")
                    }
                }
                "github_write" -> {
                    properties["action"] = choice("commit")
                    listOf("branch", "message").forEach { properties[it] = field("string") }
                    properties["expected_head_sha"] = buildJsonObject {
                        put("type", "string")
                        put("pattern", "^[a-fA-F0-9]{40}$")
                    }
                    properties["files"] = buildJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 20)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put(
                                            "path",
                                            buildJsonObject {
                                                put("type", "string")
                                                put("minLength", 1)
                                                put("description", "Required for every file: an observed repository-relative file path, never a directory or an inferred path.")
                                            }
                                        )
                                        listOf("content", "expected_blob_sha").forEach { put(it, field("string")) }
                                        put("delete", field("boolean"))
                                    }
                                )
                                put("required", JsonArray(listOf("path").map(::JsonPrimitive)))
                                put(
                                    "oneOf",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("required", JsonArray(listOf(JsonPrimitive("content"))))
                                                put("properties", buildJsonObject { put("delete", buildJsonObject { put("const", false) }) })
                                            },
                                            buildJsonObject {
                                                put("required", JsonArray(listOf("delete", "expected_blob_sha").map(::JsonPrimitive)))
                                                put(
                                                    "properties",
                                                    buildJsonObject {
                                                        put("delete", buildJsonObject { put("const", true) })
                                                        put("content", buildJsonObject { put("const", "") })
                                                    }
                                                )
                                            }
                                        )
                                    )
                                )
                            }
                        )
                    }
                }
                "github_pr" -> {
                    properties["action"] = JsonObject(
                        choice("list", "headers", "context", "comments", "reviews", "create", "update", "comment", "reply", "review", "merge", "ready", "draft", "threads", *dev.chungjungsoo.gptmobile.data.github.GitHubCollaboration.prActions.toTypedArray()) + mapOf(
                            "description" to JsonPrimitive("Omit action for list when no PR selector is supplied, or for context when an observed pull_number is supplied. Explicit single-PR reads require pull_number.")
                        )
                    )
                    properties["pull_number"] = buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("description", "Required for headers/context/comments/reviews and updates. Discover an existing pull_number with action=list first; never invent one.")
                    }
                    properties["state"] = choice("open", "closed", "all")
                    listOf("title", "body", "head", "base", "expected_head_sha", "expected_base_sha").forEach { properties[it] = field("string") }
                    properties["comment_id"] = field("integer")
                    properties["event"] = choice("APPROVE", "REQUEST_CHANGES", "COMMENT")
                    properties["comments"] = buildJsonObject {
                        put("type", "array")
                        put("maxItems", 30)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("path", field("string"))
                                        put("line", field("integer"))
                                        put("side", choice("LEFT", "RIGHT"))
                                        put("body", field("string"))
                                        put("start_line", field("integer"))
                                        put("start_side", choice("LEFT", "RIGHT"))
                                    }
                                )
                                put("required", JsonArray(listOf("path", "line", "body").map(::JsonPrimitive)))
                                put("additionalProperties", false)
                            }
                        )
                    }
                    properties["draft"] = field("boolean")
                    properties["per_page"] = integer(1, 100, "Pull requests per page; default 20.")
                    properties["page"] = integer(1, 1000)
                    properties["check_page"] = field("integer")
                    properties["include_patches"] = field("boolean")
                    properties["include_files"] = field("boolean")
                    properties["include_checks"] = field("boolean")
                    properties["include_health"] = field("boolean")
                }
                "github_ci" -> {
                    properties["action"] = choice("failure", "workflows", "definitions", "runs", "run", "status", "jobs", "job", "logs", "artifacts", "dispatch", "rerun", "rerun_failed_jobs", "rerun_all", "cancel")
                    properties["execution_id"] = buildJsonObject {
                        put("type", "string")
                        put("maxLength", 80)
                        put("description", "Reuse for recovery. Supply a new ID only for a separately requested execution after reconciling the previous receipt; never change it to retry a lost response.")
                    }
                    listOf("run_id", "job_id", "run_attempt").forEach { key ->
                        properties[key] = buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("description", if (key == "run_id") "Run ID from action=runs; different from workflow_id." else "Job ID from action=jobs; never a step number.")
                        }
                    }
                    properties["page"] = integer(1, 1000)
                    properties["index_cursor"] = integer(0)
                    properties["per_page"] = integer(1, 100)
                    properties["start_line"] = integer()
                    properties["end_line"] = integer()
                    properties["start_offset"] = integer(0)
                    properties["expected_head_sha"] = field("string")
                    listOf("workflow_id", "branch", "ref", "status", "actor", "event", "created", "head_sha").forEach { properties[it] = field("string") }
                    properties["workflow_id"] = buildJsonObject {
                        put("type", JsonArray(listOf("string", "integer").map(::JsonPrimitive)))
                        put("minimum", 1)
                        put("minLength", 1)
                        put("description", "Workflow ID or YAML filename from workflows; never a run ID.")
                    }
                    properties["inputs"] = field("object")
                }
                "github_watch_checks" -> {
                    properties["follow_pr"] = field("boolean")
                    properties["pr_number"] = field("integer")
                    properties["run_id"] = field("integer")
                    properties["commit_sha"] = field("string")
                    properties["wait_timeout_seconds"] = integer(1, 30, "Bound initial reads and inline polling to this interval; returns observed outcomes without creating a background subscription. Omit for background watching.")
                }
                "github_release" -> {
                    properties["execution_id"] = buildJsonObject {
                        put("type", "string")
                        put("maxLength", 80)
                        put("description", "Reuse for recovery; a separately requested publication needs a new ID after reconciling the earlier receipt.")
                    }
                    properties["action"] = choice("status", "publish", "tags", "tag", "create_tag", "list", "detail")
                    listOf("tag_name", "target_commitish", "name", "body", "workflow_id", "expected_head_sha", "ref").forEach { properties[it] = field("string") }
                    properties["workflow_run_id"] = buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", Long.MAX_VALUE)
                        put("description", "Exact 64-bit run ID from the accepted release dispatch receipt; never a workflow ID.")
                    }
                    properties["inputs"] = field("object")
                    properties["release_strategy"] = choice("auto", "workflow", "direct")
                    properties["draft"] = field("boolean")
                    properties["prerelease"] = field("boolean")
                    properties["page"] = field("integer")
                    properties["per_page"] = integer(1, 100, default = 30)
                }
                "github_issue" -> {
                    properties["action"] = JsonObject(choice("list", "headers", "context", "comments", "history", "create", "update", *dev.chungjungsoo.gptmobile.data.github.GitHubCollaboration.issueActions.toTypedArray()) + ("description" to JsonPrimitive("Use list to discover issues. headers/context/comments/history read one observed issue and require issue_number; state=open alone is a list filter.")))
                    properties["issue_number"] = buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("description", "Required for headers/context/comments/history/update. Discover an existing issue_number with action=list first; never invent one.")
                    }
                    properties["page"] = field("integer")
                    listOf("title", "body", "expected_updated_at").forEach { properties[it] = field("string") }
                    properties["per_page"] = integer(1, 100, default = 30)
                    properties["sort"] = choice("created", "updated", "comments")
                    properties["direction"] = choice("asc", "desc")
                    properties["state"] = choice("open", "closed", "all")
                }
            }
            if (operation in setOf("github_pr", "github_issue")) {
                listOf("thread_id", "after", "label", "expected_comment_updated_at").forEach { properties[it] = field("string") }
                listOf("review_id", "reaction_id", "comment_id").forEach { properties[it] = integer(1) }
                listOf("reviewers", "team_reviewers", "labels", "assignees").forEach { key ->
                    properties[key] = buildJsonObject {
                        put("type", "array")
                        put("maxItems", 30)
                        put("items", field("string"))
                    }
                }
                properties["reaction"] = choice("+1", "-1", "laugh", "confused", "heart", "hooray", "rocket", "eyes")
                properties["lock_reason"] = choice("off-topic", "too heated", "resolved", "spam")
                properties["merge_method"] = choice("squash", "merge", "rebase")
            }
            if (operation in setOf("github_context", "github_ci", "github_pr", "github_read", "github_issue")) properties["response_detail"] = choice("compact", "full")
            val description = when (operation) {
                "github_repository" -> "Create a repository, fork upstream, import an independent snapshot/history, or clone on the gateway. Explicit destination_owner, destination_repo, owner_type and visibility are required for remote creation; source_owner/source_repo stay separate. Import requires source_ref and copy_mode. Snapshot copies the exact file tree without history; history, Git LFS and local clone require a qualified Git gateway. Status uses expected_repository_id for native creation or operation_id for a gateway transfer, plus operation from the retained receipt. Asynchronous acceptance alone is not completion. Creation never changes the selected workspace."
                "github_context" -> "Changes:base_sha; fixture recovery:base_sha=origin,commit_sha=head."
                "github_search" -> "Pinned glob search using exact continuation_call."
                "github_read" -> "end_line=-1: bounded EOF. Follow source_coverage.next_action."
                "github_stage_patch" -> "Stage an exact replacement with target_file, search_snippet and replacement_snippet, or SHA-checked edits. Reads the pinned blob and rejects ambiguous anchors. Returns immutable version/stage_id and preserves publication receipts. Supply restore_stage_id alone to select a saved local patch at the same branch/base. Commit on a permitted working branch using its current expected_head_sha."
                "github_commit" -> "Create a working branch, commit a validated stage_id, or delete_branch with expected_head_sha after dependency inspection. Ordinary deletion blocks selected/default/protected branches, unmerged commits, open PRs and active runs. delete_fixture_branch additionally requires durable same-task ownership from fixture_plan=content_v1, origin_head_sha and a verified chain containing only its exact synthetic file; it never grants generic unmerged-branch deletion. GitHub deletion is not an atomic SHA lease."
                "github_write" -> "Every files item requires a repository-relative path (for example app/src/main/kotlin/.../MainActivity.kt), never an absolute path or directory. Stage up to 20 UTF-8 file creations, replacements or deletions for exact user approval and one atomic commit on a working branch. Supply expected_head_sha and expected_blob_sha for existing files. Replacements require content; deletions require delete=true and no content. Never write to the default branch."
                "github_pr" -> "Canonical pull-request tool (also known as github_pull_request). Start with {\"action\":\"list\"} when the PR number is unknown. For one known PR use {\"action\":\"context\",\"pull_number\":<observed positive number>}. Use list to discover pull_number. Prefer headers for title/state/head/base only; it uses a minimal API field selection with no bodies or threads. Use context for files/checks and include_health=true for merge readiness. Use github_watch_checks once for ongoing CI; do not repeatedly poll from the model. Writes require the current expected head SHA. Use ready/draft for explicit draft transitions; update does not change draft state. threads returns observed thread IDs for resolve_thread/reopen_thread; follow its pageInfo using after. edit_comment changes discussion comments; edit_review_comment changes inline comments and both require expected_comment_updated_at. enable_auto_merge queues GitHub auto-merge under repository rules and is not evidence that a merge completed."
                "github_ci" -> "For repository summaries, inspect the selected branch/head and recent relevant runs; traverse full history only when requested. Discover workflows, then runs (100/page), then jobs using run_id. job/logs require job_id; artifacts requires run_id. status inspects run_id/job_id or selected commit/ref checks. Empty collections are valid. definitions reads pinned YAML outlines. Logs use start_line/end_line/start_offset continuations. dispatch requires a discovered workflow_id and explicit ref; a run_id is not a workflow_id. rerun/rerun_failed_jobs retries failed jobs only; rerun_all retries the full completed run. Never cancel an active run to force a retry. Follow continuation_call exactly."
                "github_watch_checks" -> "Watch pr_number and/or run_id at its exact head. Omit wait_timeout_seconds for background updates in a saved chat; supply 1..30 for bounded inline reads and polling, returning an observed outcome or pending timeout without creating a subscription. Optional follow_pr=true follows new PR revisions."
                "github_issue" -> "Discover issues with action=list and optional state=open (PRs excluded). action=headers reads ONE observed issue and requires issue_number; it is not an inventory or listing action. After listing, use headers for title/state/updated_at, or context/comments/history when needed. Create/update requires exact approval; update needs expected_updated_at. Timeline is not a full body-edit history."
                else -> "Inspect release notes (detail), tags/annotations (tag), paginated tags/list, create_tag or explicitly publish the selected release."
            } + when (operation) {
                // The shared GitHub tool instruction already requires verbatim continuation_call.
                "github_read", "github_context", "github_search" -> ""
                else -> " Exact continuation_call; owner+repo."
            }
            return AgentToolDefinition(
                modelName,
                description,
                buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(properties))
                    put("additionalProperties", false)
                    if (operation == "github_write") {
                        put("required", JsonArray(listOf("branch", "expected_head_sha", "message", "files").map(::JsonPrimitive)))
                    }
                    GitHubOperationArguments.schemaAlternatives(operation)?.let { put("anyOf", it) }
                },
                workflowLinks = workflowLinks
            )
        }
    }
}
