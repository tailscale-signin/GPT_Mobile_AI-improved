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
    private val retentionLimitBytes: Int = 16 * 1024 * 1024
) {
    init {
        require(retentionLimitRecords in 1..4096)
        require(retentionLimitBytes in 1..16 * 1024 * 1024)
    }
    private data class AttemptKey(val tool: String, val arguments: JsonObject)
    private data class AttemptHistory(val count: Int, val latest: kotlinx.serialization.json.JsonElement?, val allExcluded: Boolean)
    private val attempts = mutableMapOf<AttemptKey, AttemptHistory>()
    private val mutex = Mutex()
    private val records = linkedMapOf<String, JsonObject>()
    private var bytes = 0L
    private var epoch = 0L
    private var observedHead: String? = null
    private var overflow = false

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
            retain(id, revalidate(saved))
        }
    }

    private fun revalidate(saved: JsonObject): JsonObject {
        val name = (saved["tool"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val arguments = saved["arguments"] as? JsonObject
        val contract = currentToolContracts[name]
        val exclusion = when {
            name !in trustedTools -> "TOOL_NOT_AUTHORIZED_IN_CURRENT_CATALOG"
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
        val mutation = action in GitHubWorkspaceClient.writeActions - "stage_patch" || action in setOf("create_branch", "update_file", "create_pull_request", "create_issue_comment", "dispatch_workflow", "rerun_workflow", "rerun_failed_jobs", "cancel_workflow")
        val root = (content as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
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
        val headObservation = !immutable &&
            action in setOf("repo_status", "get_branch_head", "resolve_context", "branch_context") &&
            effectiveArguments["commit_sha"] == null
        if (trusted && selected && targetBranch == branch && !result.sharedResult) {
            if (remoteChange || !result.isError && headObservation && head != null && observedHead != null && head != observedHead) epoch++
            if (!result.isError && head != null && (headObservation || remoteChange)) observedHead = head
        }
        val receipt = buildJsonObject {
            put("schema", "github.evidence.v1")
            put("receipt_id", id)
            put("binding", binding)
            put("call_id", call.callId)
            put("tool", call.name)
            put("repository", if (selected) repository else "$owner/$repo")
            put("branch", targetBranch)
            put("workspace_branch", branch)
            val resourceBranch = (root?.get("head_branch") as? JsonPrimitive)?.contentOrNull
                ?: (root?.get("head_ref") as? JsonPrimitive)?.contentOrNull
                ?: ((root?.get("head") as? JsonObject)?.get("ref") as? JsonPrimitive)?.contentOrNull
            resourceBranch?.let { put("resource_branch", it) }
            head?.let { put("resource_sha", it) }
            put("arguments", effectiveArguments)
            val priorAttempts = attempts[AttemptKey(call.name, effectiveArguments)]
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
            put("observed_at_ms", sourceTime ?: if (result.sharedResult || !result.dispatched) 0 else now())
            put("epoch", epoch)
            put("source_head_sha", head?.let(::JsonPrimitive) ?: JsonNull)
            put("head_observation", headObservation || remoteChange)
            put("immutable", immutable)
            put("trusted", trusted && selected)
            put("verification_eligible", trusted && selected)
            put("success", !result.isError && result.errorCode == null)
            put("dispatched", result.dispatched)
            result.executionReceipt?.let { put("execution", it) }
            put("error_code", result.errorCode?.let(::JsonPrimitive) ?: JsonNull)
            put("result", content)
            val visible = when (val body = result.content) {
                is ToolResultContent.Json -> body.value
                is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(body.text) }.getOrNull()
                else -> null
            }
            if (visible is JsonObject && visible != content) put("view", visible)
            put("result_sha256", digest(content.toString()))
        }
        // The bounded review projection must not discard the durable receipt of an
        // already executed mutation. Persist first; capacity only blocks verification.
        try {
            save(id, receipt)
        } catch (failure: Exception) {
            overflow = true // A transient storage error must not turn an unpersisted receipt into proof.
            throw failure
        }
        retain(id, receipt)
    }

    private fun retain(id: String, value: JsonObject) {
        val size = value.toString().encodeToByteArray().size
        if (bytes + size > retentionLimitBytes || records.size >= retentionLimitRecords) {
            overflow = true
            return
        }
        records[id] = value
        bytes += size
        val tool = (value["tool"] as? JsonPrimitive)?.contentOrNull
        val arguments = value["arguments"] as? JsonObject
        if (tool != null && arguments != null) {
            val key = AttemptKey(tool, arguments)
            val previous = attempts[key]
            attempts[key] = AttemptHistory((previous?.count ?: 0) + 1, value["receipt_id"], (previous?.allExcluded != false) && value["verification_eligible"] == JsonPrimitive(false))
        }
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
            put("primary_evidence_complete", !overflow)
            put("saved_view_retention_complete", unavailable.isEmpty())
            put("evidence_retention_complete", !overflow && unavailable.isEmpty())
            put("evidence_retention_limitations", JsonArray(limitations))
        }
    }

    suspend fun diagnostic(): JsonObject? = latestVerdict()?.takeIf { it["binding"] == JsonPrimitive(binding) }

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
                            put("contract_digest", digest(tool.inputSchema.toString()))
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
        val previous = diagnostic()
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
        val requirements = GitHubRequestCompletion.requirements(task).map { requirement ->
            buildJsonObject {
                put("id", GitHubRequestCompletion.id(requirement))
                put("requirement", requirement)
                put("status", if (failureCode == null) "UNTESTED" else "BLOCKED")
                put("acceptance_state", if (failureCode == null) "NOT_TESTED" else "BLOCKED")
                put("acceptance", if (failureCode == null) "Acceptance verification remains pending; completed API calls do not establish this requirement" else "Workflow interrupted by $failureCode before acceptance verification")
            }
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
                put("requirements", kotlinx.serialization.json.JsonArray(requirements))
                put("remaining_requirements", kotlinx.serialization.json.JsonArray(requirements))
                put("test_checklist", checkpoint)
                put("evidence_count", receipts.size)
            }
        )
        // The existing latest-verification query can recover this status even if
        // the process dies before a final response or a final verifier exists.
        save("verification_progress_" + digest("$binding|$progress").take(24), progress)
    }

    suspend fun retainChecklist(value: JsonObject) {
        val checkpoint = GitHubAcceptanceLedger.attach(JsonObject(value + retentionStatus()))
        save("checklist_" + digest("$binding|$checkpoint").take(24), JsonObject(checkpoint + ("binding" to JsonPrimitive(binding))))
    }

    suspend fun retainVerdict(value: JsonObject) {
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
        val retained = GitHubAcceptanceLedger.attach(JsonObject(value + retention + limitation))
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
        val checkpoint = JsonObject(checked + ("task_state" to JsonPrimitive(GitHubTaskLifecycle.terminal(checked).name)))
        save("verification_" + digest("$binding|$checkpoint").take(24), JsonObject(checkpoint + ("binding" to JsonPrimitive(binding))))
    }

    companion object {
        /** Preparing a verdict does not prove that its visible delivery completed. */
        fun publishedChecklistDigest(checkpoint: JsonObject?): JsonPrimitive? =
            (
                checkpoint?.get("checklist_progress_digest").takeIf { checkpoint?.get("response_delivered") == JsonPrimitive(true) }
                    ?: checkpoint?.get("last_published_checklist_progress_digest")
                ) as? JsonPrimitive

        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
