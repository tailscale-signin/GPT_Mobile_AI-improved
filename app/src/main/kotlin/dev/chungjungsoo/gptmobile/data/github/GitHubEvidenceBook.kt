package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Receipts survive replay compaction. Persistence uses the encrypted, append-only workspace journal. */
internal class GitHubEvidenceBook(
    private val binding: String,
    private val repository: String,
    private val branch: String,
    private val trustedTools: Set<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val load: suspend (String) -> JsonObject? = { null },
    private val save: suspend (String, JsonObject) -> Unit = { _, _ -> },
    private val retainedIds: suspend () -> List<String> = { emptyList() },
    private val latestVerdict: suspend () -> JsonObject? = { null },
    private val currentToolContracts: Map<String, JsonObject> = emptyMap(),
    private val retentionLimitRecords: Int = 4096,
    private val retentionLimitBytes: Int = 16 * 1024 * 1024,
    private val runtimeIdentity: () -> JsonObject = GitHubRuntimeIdentity::current,
    private val readSources: Set<String> = emptySet()
) {
    init {
        require(retentionLimitRecords in 1..4096)
        require(retentionLimitBytes in 1..16 * 1024 * 1024)
    }
    private data class AttemptKey(val tool: String, val arguments: JsonObject, val identity: String? = null)
    private data class AttemptHistory(val count: Int, val latest: kotlinx.serialization.json.JsonElement?, val allExcluded: Boolean)
    private val attempts = mutableMapOf<AttemptKey, AttemptHistory>()
    private val mutex = Mutex()
    private val records = linkedMapOf<String, JsonObject>()
    private var bytes = 0L
    private var epoch = 0L
    private var observedHead: String? = null
    private var overflow = false

    private fun attemptKey(tool: String, arguments: JsonObject): AttemptKey =
        GitHubActionIdentity.digest(binding, repository, tool, arguments)?.let { AttemptKey(tool, JsonObject(emptyMap()), it) }
            ?: AttemptKey(tool, arguments)

    @Volatile private var taskContract: JsonObject? = null

    /** Stable task identity already includes account, conversation, repository and branch scope. */
    fun checkpointBinding(): String = binding

    /** Explicit restoration never refreshes historical observations or borrows another task's proof. */
    suspend fun restore() = mutex.withLock {
        val ids = retainedIds().distinct()
        if (ids.size > retentionLimitRecords) overflow = true
        for (id in ids.take(retentionLimitRecords)) {
            if (id in records) continue
            val saved = requireNotNull(load(id)) { "Retained task evidence is unavailable" }
            val callId = (saved["call_id"] as? JsonPrimitive)?.contentOrNull
            check(
                saved["binding"] == JsonPrimitive(binding) &&
                    callId != null &&
                    id == "ev_" + digest("$binding|$callId").take(24)
            ) { "Evidence recovery binding changed" }
            val savedEpoch = (saved["epoch"] as? JsonPrimitive)?.longOrNull ?: 0
            if (savedEpoch >= epoch && saved["head_observation"] == JsonPrimitive(true)) {
                (saved["source_head_sha"] as? JsonPrimitive)?.contentOrNull?.let { observedHead = it }
            }
            epoch = maxOf(epoch, savedEpoch)
            if (!originalComplete(id, saved)) overflow = true
            retain(id, revalidate(saved))
        }
    }

    private fun revalidate(saved: JsonObject): JsonObject {
        val name = (saved["tool"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val arguments = saved["arguments"] as? JsonObject
        val contract = currentToolContracts[name]
        val exclusion = when {
            name !in trustedTools -> "TOOL_NOT_AUTHORIZED_IN_CURRENT_CATALOG"
            saved["repository_role"] == JsonPrimitive("READ_SOURCE") &&
                (saved["repository"] as? JsonPrimitive)?.contentOrNull?.lowercase(java.util.Locale.ROOT) !in readSources -> "READ_SOURCE_NOT_IN_CURRENT_TASK"
            contract != null && (arguments == null || dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(arguments, contract).isNotEmpty()) -> "TOOL_ARGUMENT_CONTRACT_CHANGED"
            else -> null
        }
        // Originals remain immutable. The current proof decision is a separate projection.
        return JsonObject(
            saved.filterKeys { it !in setOf("verification_eligible", "exclusion_reason") } + buildJsonObject {
                put("verification_eligible", exclusion == null && saved["trusted"] == JsonPrimitive(true))
                exclusion?.let { put("exclusion_reason", it) }
            }
        )
    }

    suspend fun observe(call: ProviderEvent.ToolCall, result: AgentToolResult) = mutex.withLock {
        val id = "ev_" + digest("$binding|${call.callId}").take(24)
        if (id in records) return@withLock
        val existing = load(id)
        if (existing != null) {
            check(existing["binding"] == JsonPrimitive(binding) && existing["call_id"] == JsonPrimitive(call.callId)) { "Evidence binding changed" }
            val savedEpoch = (existing["epoch"] as? JsonPrimitive)?.longOrNull ?: 0
            if (savedEpoch >= epoch && existing["head_observation"] == JsonPrimitive(true)) {
                (existing["source_head_sha"] as? JsonPrimitive)?.contentOrNull?.let { observedHead = it }
            }
            epoch = maxOf(epoch, savedEpoch)
            if (!originalComplete(id, existing)) overflow = true
            retain(id, revalidate(existing))
            return@withLock
        }
        val content = when (val body = result.retainedContent ?: result.content) {
            is ToolResultContent.Json -> body.value
            is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(body.text) }.getOrElse { JsonPrimitive(body.text) }
            is ToolResultContent.ResourceLinks -> JsonNull
        }
        val operation = GitHubOperationTool.operationName(call.name) ?: "github".takeIf { call.name.substringBefore("__") == it }
        val trusted = call.name in trustedTools && (operation != null || call.name == "discover_available_tools" || call.name.substringBefore("__") == "repository_graph")
        val effectiveArguments = (result.executionReceipt?.get("corrected_arguments") as? JsonObject) ?: call.arguments
        val owner = (effectiveArguments["owner"] as? JsonPrimitive)?.contentOrNull
        val repo = (effectiveArguments["repo"] as? JsonPrimitive)?.contentOrNull
        val selected = owner == null && repo == null || "$owner/$repo".equals(repository, true)
        val targetBranch = (effectiveArguments["ref"] as? JsonPrimitive)?.contentOrNull ?: (effectiveArguments["branch"] as? JsonPrimitive)?.contentOrNull ?: branch
        val immutable = GitHubOperationTool.immutableRead(call.name, effectiveArguments)
        val native = if (operation == "github") effectiveArguments else GitHubOperationTool.nativeArguments(call.name, effectiveArguments)
        val action = (native?.get("action") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val mutation = action in GitHubWorkspaceClient.writeActions - "stage_patch" ||
            action == "stage_patch" &&
            native?.get("commit_immediately") == JsonPrimitive(true) ||
            action in setOf("create_branch", "update_file", "create_pull_request", "create_issue_comment", "dispatch_workflow", "rerun_workflow", "rerun_failed_jobs", "cancel_workflow")
        val sourceRead = !selected &&
            !mutation &&
            GitHubTargetPolicy.isReadAction(action) &&
            "$owner/$repo".lowercase(java.util.Locale.ROOT) in readSources
        val lifecycle = action in GitHubRepositoryLifecycle.actions && action != "repository_lifecycle_capabilities"
        val root = (content as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
        val lifecycleTarget = if (action == "clone_repository" || action == "repository_lifecycle_status" && native?.get("operation") == JsonPrimitive("clone_repository")) "source" else "destination"
        val lifecycleRepository = listOf("${lifecycleTarget}_owner", "${lifecycleTarget}_repo").map { (effectiveArguments[it] as? JsonPrimitive)?.contentOrNull.orEmpty() }
            .takeIf { lifecycle && it.all(String::isNotBlank) }?.joinToString("/")
            ?: (root?.get("${lifecycleTarget}_repository") as? JsonPrimitive)?.contentOrNull?.takeIf { lifecycle && action == "repository_lifecycle_status" && it.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) }
        val evidenceRepository = lifecycleRepository ?: if (selected) repository else "$owner/$repo"
        val head = (
            listOf("head_sha", "commit_sha").firstNotNullOfOrNull { (root?.get(it) as? JsonPrimitive)?.contentOrNull }
                ?: ((root?.get("head") as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull
                ?: (((root?.get("repository") as? JsonObject)?.get("head") as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull
            )
            ?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
        val remoteChange = ((result.executionReceipt?.get("accepted_mutations") as? JsonPrimitive)?.longOrNull ?: 0) > 0 ||
            ((result.executionReceipt?.get("unknown_mutations") as? JsonPrimitive)?.longOrNull ?: 0) > 0 ||
            mutation &&
            result.dispatched &&
            !result.isError
        val origin = GitHubEvidenceOrigin.classify(result.dispatched, result.sharedResult, result.executionReceipt)
        val headObservation = !immutable &&
            action in setOf("repo_status", "get_branch_head", "resolve_context", "branch_context") &&
            effectiveArguments["commit_sha"] == null
        if (trusted && selected && !lifecycle && targetBranch == branch && !result.sharedResult) {
            if (remoteChange || !result.isError && headObservation && head != null && observedHead != null && head != observedHead) epoch++
            if (!result.isError && head != null && (headObservation || remoteChange)) observedHead = head
        }
        val receipt = buildJsonObject {
            put("schema", "github.evidence.v1")
            put("receipt_id", id)
            put("binding", binding)
            put("call_id", call.callId)
            put("tool", call.name)
            put("repository", evidenceRepository)
            put(
                "repository_role",
                if (lifecycleRepository != null) {
                    "LIFECYCLE_DESTINATION"
                } else if (sourceRead) {
                    "READ_SOURCE"
                } else if (selected) {
                    "WRITE_DESTINATION"
                } else {
                    "UNAUTHORIZED_TARGET"
                }
            )
            put("runtime_identity", runtimeIdentity())
            put("branch", targetBranch)
            put("workspace_branch", branch)
            val resourceBranch = (root?.get("head_branch") as? JsonPrimitive)?.contentOrNull
                ?: (root?.get("head_ref") as? JsonPrimitive)?.contentOrNull
                ?: ((root?.get("head") as? JsonObject)?.get("ref") as? JsonPrimitive)?.contentOrNull
            resourceBranch?.let { put("resource_branch", it) }
            head?.let { put("resource_sha", it) }
            put("arguments", effectiveArguments)
            GitHubActionIdentity.digest(binding, evidenceRepository, call.name, effectiveArguments)?.let { put("action_identity_sha256", it) }
            val priorAttempts = attempts[attemptKey(call.name, effectiveArguments)]
            if (priorAttempts != null) {
                put(
                    "repeat_reason",
                    if (priorAttempts.allExcluded) {
                        "CATALOG_REVALIDATION"
                    } else if (!immutable) {
                        "MUTABLE_OBSERVATION_REFRESH"
                    } else {
                        "EXPLICIT_RETRY_REQUIRES_REVIEW"
                    }
                )
                // Link to the predecessor; its receipt links to earlier attempts. Copying
                // every prior ID into every receipt grows the journal quadratically.
                put("previous_attempt_ids", kotlinx.serialization.json.JsonArray(listOfNotNull(priorAttempts.latest)))
                put("previous_attempt_count", priorAttempts.count)
                put("previous_attempt_reference_mode", "LINKED_PREDECESSOR")
            }
            val sourceTime = (result.executionReceipt?.get("source_observed_at_ms") as? JsonPrimitive)?.longOrNull
            put("observed_at_ms", sourceTime ?: if (origin == "LIVE" && !result.sharedResult) now() else 0)
            put("epoch", epoch)
            put("source_head_sha", head?.let(::JsonPrimitive) ?: JsonNull)
            put("head_observation", selected && !lifecycle && (headObservation || remoteChange))
            put("immutable", immutable)
            put("trusted", trusted && (selected || sourceRead || lifecycleRepository != null))
            put("verification_eligible", trusted && (selected || sourceRead || lifecycleRepository != null))
            put("success", !result.isError && result.errorCode == null)
            put("dispatched", result.dispatched)
            put("shared_result", result.sharedResult)
            put("evidence_origin", origin)
            put("remote_mutation", mutation)
            if (result.retainedContent != null && result.executionReceipt?.get("retained_original_complete") == JsonPrimitive(true)) {
                put("retained_result_complete", true)
                put("retained_remote_continuations", result.executionReceipt?.get("retained_remote_continuations") ?: kotlinx.serialization.json.JsonArray(emptyList()))
            }
            put(
                "tool_budget",
                buildJsonObject {
                    result.toolCallBudgetUsed?.let { put("used", it) }
                    result.toolCallBudgetLimit?.let { put("limit", it) }
                    result.toolCallBudgetConfigured?.let { put("configured", it) }
                    result.toolCallBudgetReserved?.let { put("final_response_reserved", it) }
                }
            )
            result.executionReceipt?.let { put("execution", it) }
            put("error_code", result.errorCode?.let(::JsonPrimitive) ?: JsonNull)
            put("result", content)
            val visible = when (val body = result.content) {
                is ToolResultContent.Json -> body.value
                is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(body.text) }.getOrNull()
                else -> null
            }
            if (visible is JsonObject && visible != content) put("view", visible)
            if (!result.isError && operation != null && effectiveArguments["view_cursor"] == null) {
                GitHubInventoryObservation.capture(content, visible)?.let { put("inventory_observation", it) }
            }
            put("result_sha256", digest(content.toString()))
            put("result_bytes", content.toString().encodeToByteArray().size)
        }
        // The bounded review projection must not discard the durable receipt of an
        // already executed mutation. Persist first; capacity only blocks verification.
        try {
            saveReceipt(id, receipt)
        } catch (failure: Exception) {
            overflow = true // A transient storage error must not turn an unpersisted receipt into proof.
            throw failure
        }
        retain(id, receipt)
    }

    private fun retain(id: String, value: JsonObject) {
        // Large source text belongs to the durable tier. Keep exact structural
        // facts and already bounded source excerpts in the review working set.
        val projected = if (value.toString().encodeToByteArray().size > 64 * 1024) GitHubEvidenceProjection.receipt(value) else value
        val size = projected.toString().encodeToByteArray().size
        if (bytes + size > retentionLimitBytes || records.size >= retentionLimitRecords) {
            overflow = true
            return
        }
        records[id] = projected
        bytes += size
        val tool = (value["tool"] as? JsonPrimitive)?.contentOrNull
        val arguments = value["arguments"] as? JsonObject
        if (tool != null && arguments != null) {
            val key = attemptKey(tool, arguments)
            val previous = attempts[key]
            attempts[key] = AttemptHistory((previous?.count ?: 0) + 1, value["receipt_id"], (previous?.allExcluded != false) && value["verification_eligible"] == JsonPrimitive(false))
        }
    }

    /** The existing encrypted workspace store has a per-record bound, not a total-result bound. */
    private suspend fun saveReceipt(id: String, receipt: JsonObject) {
        val text = receipt.toString()
        if (text.encodeToByteArray().size <= 1_400_000) {
            save(id, receipt)
            return
        }
        // A provider response is bounded at 4 MiB. This also admits its argument
        // and receipt envelope without ever publishing an unbounded manifest.
        require(text.encodeToByteArray().size <= 8 * 1024 * 1024) { "GitHub original receipt exceeds its bounded source allowance" }
        val originalHash = digest(text)
        val chunks = mutableListOf<JsonObject>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 192 * 1024, text.length)
            if (end < text.length && text[end].isLowSurrogate()) end--
            val chunk = text.substring(start, end)
            val key = "original_${id}_${originalHash.take(24)}_${chunks.size}"
            val hash = digest(chunk)
            save(
                key,
                buildJsonObject {
                    put("schema", "github.evidence-chunk.v1")
                    put("binding", binding)
                    put("receipt_id", id)
                    put("index", chunks.size)
                    put("offset_utf16", start)
                    put("sha256", hash)
                    put("text", chunk)
                }
            )
            chunks += buildJsonObject {
                put("id", key)
                put("offset_utf16", start)
                put("characters", chunk.length)
                put("sha256", hash)
            }
            start = end
        }
        // Publish only after every immutable chunk is durable. A restart never
        // observes an apparently complete manifest for an unfinished write.
        save(
            id,
            JsonObject(
                GitHubEvidenceProjection.receipt(receipt) + buildJsonObject {
                    put("original_chunks", JsonArray(chunks))
                    put("original_receipt_sha256", originalHash)
                    put("original_receipt_characters", text.length)
                }
            )
        )
    }

    /** Page exact originals by a stable receipt ID; this never dispatches the operation again. */
    suspend fun originalPage(id: String, page: Int): JsonObject? = mutex.withLock {
        if (page < 0 || !id.matches(Regex("ev_[a-f0-9]{24}"))) return@withLock null
        val saved = load(id)?.takeIf { it["binding"] == JsonPrimitive(binding) && it["receipt_id"] == JsonPrimitive(id) } ?: return@withLock null
        val chunks = saved["original_chunks"] as? JsonArray
        if (chunks == null) {
            if (page != 0) return@withLock null
            return@withLock buildJsonObject {
                put("receipt_id", id)
                put("index", 0)
                put("offset_utf16", 0)
                put("sha256", digest(saved.toString()))
                put("text", saved.toString())
                put("complete", true)
            }
        }
        val ref = chunks.getOrNull(page) as? JsonObject ?: return@withLock null
        val key = (ref["id"] as? JsonPrimitive)?.contentOrNull ?: return@withLock null
        val originalHash = (saved["original_receipt_sha256"] as? JsonPrimitive)?.contentOrNull ?: return@withLock null
        if (key != "original_${id}_${originalHash.take(24)}_$page") return@withLock null
        val chunk = load(key) ?: return@withLock null
        val text = (chunk["text"] as? JsonPrimitive)?.contentOrNull ?: return@withLock null
        if (chunk["binding"] != JsonPrimitive(binding) || chunk["receipt_id"] != JsonPrimitive(id) || chunk["index"] != JsonPrimitive(page) || chunk["offset_utf16"] != ref["offset_utf16"] || ref["sha256"] != JsonPrimitive(digest(text))) return@withLock null
        JsonObject(
            chunk + buildJsonObject {
                put("complete", page == chunks.lastIndex)
                put("total_pages", chunks.size)
                put("original_receipt_sha256", saved.getValue("original_receipt_sha256"))
            }
        )
    }

    private suspend fun originalComplete(id: String, manifest: JsonObject): Boolean {
        val chunks = manifest["original_chunks"] as? JsonArray ?: return true
        if (chunks.size !in 1..64) return false
        val expectedHash = (manifest["original_receipt_sha256"] as? JsonPrimitive)?.contentOrNull ?: return false
        val expectedCharacters = (manifest["original_receipt_characters"] as? JsonPrimitive)?.longOrNull ?: return false
        if (expectedCharacters !in 1..8L * 1024 * 1024) return false
        val hasher = MessageDigest.getInstance("SHA-256")
        var characters = 0L
        for ((index, raw) in chunks.withIndex()) {
            val ref = raw as? JsonObject ?: return false
            val key = (ref["id"] as? JsonPrimitive)?.contentOrNull ?: return false
            if (key != "original_${id}_${expectedHash.take(24)}_$index") return false
            val chunk = load(key) ?: return false
            val text = (chunk["text"] as? JsonPrimitive)?.contentOrNull ?: return false
            if (chunk["binding"] != JsonPrimitive(binding) || chunk["receipt_id"] != JsonPrimitive(id) || chunk["index"] != JsonPrimitive(index) || (chunk["offset_utf16"] as? JsonPrimitive)?.longOrNull != characters || ref["sha256"] != JsonPrimitive(digest(text))) return false
            hasher.update(text.encodeToByteArray())
            characters += text.length
            if (characters > expectedCharacters) return false
        }
        return characters == expectedCharacters && hasher.digest().joinToString("") { "%02x".format(it) } == expectedHash
    }

    suspend fun snapshot(): Pair<Map<String, JsonObject>, Long> = mutex.withLock {
        records.toMap() to epoch
    }

    suspend fun complete(): Boolean = mutex.withLock { !overflow }

    /** Page persistence can recover independently without invalidating an accepted mutation. */
    suspend fun retentionStatus(): JsonObject = mutex.withLock {
        val views = records.values.mapNotNull { receipt ->
            val execution = receipt["execution"] as? JsonObject ?: return@mapNotNull null
            val status = (execution["saved_view_persistence"] as? JsonPrimitive)?.contentOrNull
            if (status !in setOf("RETAINED", "UNAVAILABLE")) return@mapNotNull null
            val id = (execution["saved_view_id"] as? JsonPrimitive)?.contentOrNull ?: receipt["receipt_id"].toString()
            Triple(id, receipt, execution)
        }.groupBy { it.first }
        val unavailable = views.filterValues { observations ->
            val retained = observations.filter { it.third["saved_view_persistence"] == JsonPrimitive("RETAINED") }
                .flatMap { (it.third["saved_view_cursor_ids"] as? JsonArray).orEmpty() }.toSet()
            observations.filter { it.third["saved_view_persistence"] == JsonPrimitive("UNAVAILABLE") }.any {
                val failed = (it.third["saved_view_cursor_ids"] as? JsonArray).orEmpty()
                // The same number of handles can describe a different pagination
                // branch after restart. Only exact persisted handles repair proof.
                failed.isEmpty() || failed.any { cursor -> cursor !in retained }
            }
        }
        val limitations = buildList {
            if (overflow) {
                add(
                    buildJsonObject {
                        put("code", "PRIMARY_EVIDENCE_INCOMPLETE")
                        put("reason", "The complete primary evidence set could not be retained or reviewed within its storage allowance.")
                    }
                )
            }
            unavailable.forEach { (id, observations) ->
                add(
                    buildJsonObject {
                        put("code", "SAVED_VIEW_PERSISTENCE_UNAVAILABLE")
                        put("saved_view_id", id)
                        put("receipt_ids", JsonArray(observations.mapNotNull { it.second["receipt_id"] }.distinct()))
                        put("reason", "A captured result has saved pages that were not durably retained. The operation receipt remains recorded; restore and persist the same saved result without repeating its mutation.")
                    }
                )
            }
        }
        buildJsonObject {
            put("review_projection_bytes", bytes)
            put("review_projection_limit_bytes", retentionLimitBytes)
            put("review_receipt_count", records.size)
            put("projected_receipt_count", records.values.count { it["review_projection"] != null })
            put("original_result_bytes", records.values.sumOf { (it["result_bytes"] as? JsonPrimitive)?.longOrNull ?: 0 })
            put("primary_evidence_complete", !overflow)
            put("saved_view_retention_complete", unavailable.isEmpty())
            put("evidence_retention_complete", !overflow && unavailable.isEmpty())
            put("evidence_retention_limitations", JsonArray(limitations))
        }
    }

    suspend fun diagnostic(): JsonObject? = latestVerdict()?.takeIf { it["binding"] == JsonPrimitive(binding) }

    /** Freeze task identity and ceilings before the first provider round, in either chat surface. */
    suspend fun retainTaskContract(task: String, sourceMessageId: Int, tools: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition>, callLimit: Int) = mutex.withLock {
        val key = "task_contract"
        val previous = taskContract ?: load(key)
        if (previous != null) {
            check(previous["binding"] == JsonPrimitive(binding) && previous["repository"] == JsonPrimitive(repository) && previous["source_message_id"] == JsonPrimitive(sourceMessageId)) {
                "GITHUB_TASK_IDENTITY_CHANGED: The retained task belongs to a different accepted request. No operation was dispatched."
            }
            taskContract = previous
            return@withLock
        }
        val acceptedAt = now()
        val duration = GitHubExecutionLimits.requestedDurationMillis(task)
        val accepted = buildJsonObject {
            put("schema", "github.task-contract.v1")
            put("binding", binding)
            put("source_message_id", sourceMessageId)
            put("repository", repository)
            put("accepted_branch", branch)
            put("request_digest", digest(task))
            put("requested_actions", task)
            put("acceptance_criteria", JsonArray(GitHubRequestCompletion.requirements(task).map(::JsonPrimitive)))
            put("required_tool_names", JsonArray(tools.filter { it.modelVisible }.map { JsonPrimitive(it.name) }.distinct()))
            put("accepted_at_ms", acceptedAt)
            put("tool_call_limit", callLimit.coerceAtLeast(0))
            put("tool_call_limit_source", "AUTHORIZED_RUN_CONTRACT")
            put("tool_call_limit_rule", "Minimum of the profile/chat allowance, workflow/task policy and explicit request ceiling.")
            GitHubWorkflowPreset.fromRequest(task)?.let { put("workflow_preset_call_limit", it.toolCalls) }
            duration?.let { put("deadline_at_ms", acceptedAt + it.coerceAtMost(Long.MAX_VALUE - acceptedAt)) }
            put("receipt_store", "github-evidence:$binding")
            put("remaining_work_store", "verification_current_" + digest(binding).take(24))
            put("cleanup_policy", "Owned fixtures remain obligations until their deletion is verified; unknown writes require reconciliation before retry.")
            put("completion_policy", "Invocation, response delivery and task acceptance are independent. Only verified criteria complete the task.")
        }
        save(key, accepted)
        // Temporary chats intentionally omit persistent workspace rows. They still
        // obey this invocation's accepted deadline and cannot silently renew it.
        taskContract = accepted
    }

    suspend fun remainingTaskMillis(): Long? = ((taskContract ?: load("task_contract"))?.get("deadline_at_ms") as? JsonPrimitive)?.longOrNull
        ?.let { (it - now()).coerceAtLeast(0) }

    suspend fun acceptedTaskRequest(): String? = ((taskContract ?: load("task_contract"))?.get("requested_actions") as? JsonPrimitive)?.contentOrNull

    /** One bounded delivery snapshot, separate from immutable operation receipts and their verdict. */
    suspend fun retainAnswer(text: String, verdict: JsonObject) {
        require(text.isNotBlank() && text.length <= 1_048_576)
        val receiptDigest = digest(snapshot().first.keys.sorted().joinToString("\n"))
        saveCurrent(
            "answer",
            buildJsonObject {
                put("schema", "github.saved-answer.v1")
                put("binding", binding)
                put("text", text)
                put("text_sha256", digest(text))
                put("receipt_set_digest", receiptDigest)
                put("verdict", verdict)
            }
        )
    }

    suspend fun retainedAnswer(): Pair<String, JsonObject>? {
        val saved = load("answer_current_" + digest(binding).take(24)) ?: return null
        val text = (saved["text"] as? JsonPrimitive)?.contentOrNull ?: return null
        val verdict = saved["verdict"] as? JsonObject ?: return null
        if (saved["binding"] != JsonPrimitive(binding) ||
            text.isBlank() ||
            text.length > 1_048_576 ||
            saved["text_sha256"] != JsonPrimitive(digest(text)) ||
            saved["receipt_set_digest"] != JsonPrimitive(digest(snapshot().first.keys.sorted().joinToString("\n")))
        ) {
            return null
        }
        return text to verdict
    }

    suspend fun retainPolicy(revision: String, tools: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition>) {
        val policy = buildJsonObject {
            put("schema", "github.policy-snapshot.v1")
            put("binding", binding)
            put("authorization_revision", revision)
            put(
                "catalog",
                kotlinx.serialization.json.JsonArray(
                    tools.filter { it.modelVisible }.sortedBy { it.name }.map { tool ->
                        buildJsonObject {
                            put("name", tool.name)
                            put("contract_digest", dev.chungjungsoo.gptmobile.data.agent.ToolContractFingerprint.schema(tool.inputSchema))
                            put("contract_digest_kind", "SEMANTIC_SCHEMA_V1")
                        }
                    }
                )
            )
            put("redaction", "Names and contract digests only; no credentials, provider destinations, prompts or argument values")
        }
        save("policy_" + digest(policy.toString()).take(24), policy)
    }

    suspend fun retainProgress(task: String, tools: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition>, failureCode: String? = null, failure: ProviderEvent.Failed? = null) {
        val (receipts, _) = snapshot()
        val checkpoint = GitHubTestChecklist.checkpoint(task, receipts, tools)
        val retention = retentionStatus()
        val previous = diagnostic() ?: load("verification_current_" + digest(binding).take(24))
        if (previous == null) {
            save(
                "verification_plan_" + digest("$binding|$task").take(24),
                buildJsonObject {
                    put("binding", binding)
                    put("task_state", GitHubTaskLifecycle.State.PLANNED.name)
                    put("request_digest", digest(task))
                    put("completion_verified", false)
                    put("response_prepared", false)
                    put("response_delivered", false)
                    put("observed_at_ms", now())
                }
            )
        }
        val publishedProgressDigest = publishedChecklistDigest(previous)
        val requirements = if (GitHubTestChecklist.integration(task)) {
            GitHubIntegrationAcceptance.requirements(task, receipts, tools)
        } else {
            JsonArray(
                GitHubRequestCompletion.requirements(task).map { requirement ->
                    buildJsonObject {
                        put("id", GitHubRequestCompletion.id(requirement))
                        put("requirement", requirement)
                        put("status", if (failureCode == null) "UNTESTED" else "BLOCKED")
                        put("acceptance_state", if (failureCode == null) "NOT_TESTED" else "BLOCKED")
                        put("acceptance", if (failureCode == null) "Acceptance verification remains pending; completed API calls do not establish this requirement" else "Workflow interrupted by $failureCode before acceptance verification")
                    }
                }
            )
        }
        val progress = GitHubAcceptanceLedger.attach(
            buildJsonObject {
                put("schema", "github.verification.v1")
                put("binding", binding)
                put("phase", if (failureCode == null) "IN_PROGRESS" else "INTERRUPTED")
                put("request_digest", digest(task))
                put("observed_at_ms", now())
                publishedProgressDigest?.let { put("last_published_checklist_progress_digest", it) }
                put("completion_verified", false)
                retention.forEach { (key, value) -> put(key, value) }
                put("response_prepared", false)
                put("response_delivered", false)
                put("task_outcome", if (failure?.taskOutcome == "BLOCKED") "BLOCKED" else "PARTIAL")
                put("task_state", GitHubTaskLifecycle.progress(failure ?: failureCode?.let { ProviderEvent.Failed(it, errorCode = it) }, receipts.size).name)
                failure?.let { GitHubTaskLifecycle.failureMetadata(it).forEach { (key, value) -> put(key, value) } }
                failureCode?.let { put("failure_code", it) }
                put("requirements", requirements)
                put("remaining_requirements", JsonArray(requirements.filter { (it as JsonObject)["acceptance_state"] != JsonPrimitive("PASS") }))
                put("test_checklist", checkpoint)
                put("evidence_count", receipts.size)
            },
            receipts
        )
        // The existing latest-verification query can recover this status even if
        // the process dies before a final response or a final verifier exists.
        val durable = GitHubExecutionLedger.attach(progress, receipts)
        saveCurrent("verification", durable)
    }

    suspend fun retainChecklist(value: JsonObject) {
        val checkpoint = GitHubExecutionLedger.attach(GitHubAcceptanceLedger.attach(JsonObject(value + retentionStatus()), snapshot().first), snapshot().first)
        saveCurrent("checklist", JsonObject(checkpoint + ("binding" to JsonPrimitive(binding))))
    }

    suspend fun retainVerdict(value: JsonObject): JsonObject {
        val retention = retentionStatus()
        val limitation = if (retention["evidence_retention_complete"] == JsonPrimitive(false)) {
            buildJsonObject {
                put("completion_verified", false)
                put("partial", true)
                put("resume_required", true)
                put("task_outcome", if (value["task_outcome"] in setOf(JsonPrimitive("BLOCKED"), JsonPrimitive("CONFIGURATION_INVALIDATED"))) value.getValue("task_outcome") else JsonPrimitive("PARTIAL"))
            }
        } else {
            JsonObject(emptyMap())
        }
        val retained = GitHubAcceptanceLedger.attach(JsonObject(value + retention + limitation), snapshot().first)
        val checklistComplete = (retained["acceptance_ledger"] as? JsonObject)?.get("completion_verified") == JsonPrimitive(true)
        val checked = if (retained["task_outcome"] == JsonPrimitive("COMPLETED") && !checklistComplete) {
            JsonObject(
                retained + buildJsonObject {
                    put("task_outcome", "PARTIAL")
                    put("completion_verified", false)
                    put("partial", true)
                    put("resume_required", true)
                    if (retained["verification_failure"] == null) put("verification_failure", "GITHUB_ACCEPTANCE_CHECKLIST_REQUIRED")
                }
            )
        } else {
            retained
        }
        val execution = GitHubExecutionLedger.attach(checked, snapshot().first)
        val checkpoint = JsonObject(execution + ("task_state" to JsonPrimitive(GitHubTaskLifecycle.terminal(execution).name)))
        val durable = JsonObject(checkpoint + ("binding" to JsonPrimitive(binding)))
        saveCurrent("verification", durable)
        return durable
    }

    /** Full projections replace only their task-current slot; immutable operation evidence is never rewritten. */
    private suspend fun saveCurrent(kind: String, value: JsonObject) {
        val id = "${kind}_current_" + digest(binding).take(24)
        val previous = load(id)
        fun summary(snapshot: JsonObject): JsonObject = buildJsonObject {
            put("schema", "github.verification-event.v1")
            put("binding", binding)
            put("snapshot_id", id)
            listOf("phase", "task_state", "task_outcome", "failure_code", "verification_failure", "completion_verified", "response_prepared", "response_delivered", "evidence_count", "progress_state").forEach { field -> snapshot[field]?.let { put(field, it) } }
            (snapshot["stop_reason"] as? JsonObject)?.get("code")?.let { put("stop_code", it) }
            val requirements = (snapshot["requirements"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            put("requirements", JsonArray(requirements.take(64).map { row -> JsonObject(row.filterKeys { it in setOf("id", "status", "acceptance_state") }) }))
            ((snapshot["acceptance_ledger"] as? JsonObject)?.get("diagnostic_projection") as? JsonObject)?.takeIf(GitHubAcceptanceDiagnostics::valid)?.let { projection ->
                put("acceptance_diagnostics_sha256", digest(projection.toString()))
                put("criterion_count", projection.getValue("criterion_count"))
                put("criteria_omitted", projection.getValue("omitted_count"))
                put(
                    "acceptance_criteria",
                    JsonArray(
                        (projection["criteria"] as JsonArray).filterIsInstance<JsonObject>().filter { it["kind"] == JsonPrimitive("REQUIREMENT") }.map { row ->
                            JsonObject(row.filterKeys { it in setOf("ordinal", "id_sha256", "category", "label", "operation", "state", "blocker", "next_action", "evidence_receipt_count", "evidence_class_counts") })
                        }
                    )
                )
            }
            val checklist = (snapshot["test_checklist"] as? JsonObject) ?: snapshot
            val capabilities = (checklist["capabilities"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            put("capability_states", buildJsonObject { capabilities.groupingBy { (it["status"] as? JsonPrimitive)?.content.orEmpty() }.eachCount().toSortedMap().forEach { (state, count) -> put(state, count) } })
            (checklist["authorized_catalog"] as? JsonObject)?.get("inventory_version")?.let { put("inventory_version", it) }
        }
        val event = summary(value)
        save(id, value)
        if (previous == null || summary(previous) != event) {
            val observed = now()
            val delta = JsonObject(
                event + buildJsonObject {
                    put("observed_at_ms", observed)
                    put("snapshot_sha256", digest(value.toString()))
                }
            )
            save("verification_event_" + digest("$binding|$kind|$delta").take(24), delta)
        }
    }

    companion object {
        fun currentSnapshot(id: String): Boolean = id.matches(Regex("(?:verification|checklist|answer)_current_[a-f0-9]{24}"))

        /** Preparing a verdict does not prove that its visible delivery completed. */
        fun publishedChecklistDigest(checkpoint: JsonObject?): JsonPrimitive? =
            (
                checkpoint?.get("checklist_progress_digest").takeIf { checkpoint?.get("response_delivered") == JsonPrimitive(true) }
                    ?: checkpoint?.get("last_published_checklist_progress_digest")
                ) as? JsonPrimitive

        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
