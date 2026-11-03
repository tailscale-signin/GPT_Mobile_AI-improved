package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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
    private val currentToolContracts: Map<String, JsonObject> = emptyMap()
) {
    private val mutex = Mutex()
    private val records = linkedMapOf<String, JsonObject>()
    private var bytes = 0L
    private var epoch = 0L
    private var observedHead: String? = null
    private var overflow = false

    /** Explicit restoration never refreshes historical observations or borrows another task's proof. */
    suspend fun restore() = mutex.withLock {
        val ids = retainedIds().distinct()
        check(ids.size <= 4096) { "Retained task evidence exceeds the recovery allowance" }
        for (id in ids) {
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
        check(!overflow) { "Retained task evidence exceeds the recovery allowance" }
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
        val trusted = call.name in trustedTools && (operation != null || call.name == "discover_available_tools")
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
            val priorAttempts = records.values.filter { it["tool"] == JsonPrimitive(call.name) && it["arguments"] == effectiveArguments }
            if (priorAttempts.isNotEmpty()) {
                put(
                    "repeat_reason",
                    if (priorAttempts.all { it["verification_eligible"] == JsonPrimitive(false) }) {
                        "CATALOG_REVALIDATION"
                    } else if (!immutable) {
                        "MUTABLE_OBSERVATION_REFRESH"
                    } else {
                        "EXPLICIT_RETRY_REQUIRES_REVIEW"
                    }
                )
                put("previous_attempt_ids", kotlinx.serialization.json.JsonArray(priorAttempts.mapNotNull { it["receipt_id"] }))
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
        check(records.size < 4096 && bytes + receipt.toString().encodeToByteArray().size <= 16 * 1024 * 1024) {
            overflow = true
            "Evidence capacity reached; no unverified response can be published"
        }
        try {
            save(id, receipt)
        } catch (failure: Exception) {
            overflow = true // A transient storage error must not turn an unpersisted receipt into proof.
            throw failure
        }
        retain(id, receipt)
    }

    private fun retain(id: String, value: JsonObject) {
        bytes += value.toString().encodeToByteArray().size
        if (bytes > 16 * 1024 * 1024 || records.size >= 4096) overflow = true else records[id] = value
    }

    suspend fun snapshot(): Pair<Map<String, JsonObject>, Long> = mutex.withLock {
        check(!overflow) { "Evidence exceeds the retained verification allowance" }
        records.toMap() to epoch
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
                    tools.sortedBy { it.name }.map { tool ->
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

    suspend fun retainChecklist(value: JsonObject) {
        save("checklist_" + digest("$binding|$value").take(24), JsonObject(value + ("binding" to JsonPrimitive(binding))))
    }

    suspend fun retainVerdict(value: JsonObject) {
        save("verification_" + digest("$binding|$value").take(24), JsonObject(value + ("binding" to JsonPrimitive(binding))))
    }

    companion object {
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
