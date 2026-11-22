package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
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
import kotlinx.serialization.json.intOrNull
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
    private val readSources: Set<String> = emptySet(),
    authorizationRevision: String? = null,
    internal val fixtureDecisionMode: GitHubDecisionMode? = null,
    private val approvalResolution: suspend (JsonObject) -> List<JsonObject> = { emptyList() },
    private val sourceRunId: String? = null,
    private val inferenceProfile: dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2? = null,
    private val latestDeadlineGrant: suspend () -> JsonObject? = { null },
    private val authorizeTaskContinuation: suspend () -> Boolean = { true },
    private val requireAcceptedContract: Boolean = false
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
    private val trustObservations = mutableMapOf<String, GitHubAcceptancePublicationEvidence.TrustFailure>()
    private var bytes = 0L
    private var epoch = 0L
    private var observedHead: String? = null
    private var overflow = false
    private val authorizationDigest = authorizationRevision?.takeIf(String::isNotBlank)?.let(::digest)
    private val fixturePreflightId = "fixture_preflight_" + digest(binding).take(24)
    private val fixtureKnownPreflightId = "fixture_preflight_known_" + digest(binding).take(24)
    private val fixtureClassificationId = "fixture_classification_current_" + digest(binding).take(24)

    /** Freeze the effective approval mode before an integration recipe may create a fixture. */
    suspend fun fixtureApprovalPreflight(): JsonObject = mutex.withLock {
        fixtureApprovalPreflightLocked()
    }

    private fun fixtureScopeMatches(value: JsonObject): Boolean =
        value["binding"] == JsonPrimitive(binding) &&
            value["repository"] == JsonPrimitive(repository) &&
            value["selected_branch"] == JsonPrimitive(branch)

    /** Approval bytes never change. Classification is a separately versioned, non-authorizing view. */
    private suspend fun fixtureApprovalPreflightLocked(): JsonObject {
        val saved = load(fixturePreflightId)
        val original = if (saved != null && fixtureScopeMatches(saved)) {
            saved
        } else {
            GitHubTestChecklist.fixtureApprovalPreflight(fixtureDecisionMode, binding, repository, branch, runtimeIdentity()).also {
                save(fixturePreflightId, it)
            }
        }
        val approval = if (original["decision_mode"] == JsonPrimitive("UNKNOWN")) {
            val originalDigest = digest(original.toString())
            val known = load(fixtureKnownPreflightId)?.takeIf {
                fixtureScopeMatches(it) &&
                    it["original_preflight_sha256"] == JsonPrimitive(originalDigest) &&
                    it["decision_mode"] != JsonPrimitive("UNKNOWN")
            }
            when {
                known != null -> known
                fixtureDecisionMode != null && authorizationDigest != null -> JsonObject(
                    GitHubTestChecklist.fixtureApprovalPreflight(fixtureDecisionMode, binding, repository, branch, runtimeIdentity()) + buildJsonObject {
                        put("recheck_source", "TASK_BOUND_AUTHORIZATION_REVISION")
                        put("authorization_revision_sha256", authorizationDigest)
                        put("original_preflight_sha256", originalDigest)
                    }
                ).also { save(fixtureKnownPreflightId, it) }
                // Legacy CONDITIONAL/UNKNOWN did not contain a user decision.
                original["state"] != JsonPrimitive("UNKNOWN") -> GitHubTestChecklist.fixtureApprovalPreflight(null, binding, repository, branch, original["runtime_identity"] as? JsonObject ?: runtimeIdentity())
                else -> original
            }
        } else {
            original
        }
        val approvalDigest = digest(JsonObject(approval.filterKeys { it != "capability_classification" }).toString())
        val current = load(fixtureClassificationId)?.takeIf { it["approval_sha256"] == JsonPrimitive(approvalDigest) && it["binding"] == JsonPrimitive(binding) }
        val version = (current?.get("classification_id") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.matches(Regex("fixture_classification_[a-f0-9]{24}")) }
            ?.let { load(it) }
            ?.takeIf { it["approval_sha256"] == JsonPrimitive(approvalDigest) && it["binding"] == JsonPrimitive(binding) }
        val classification = version?.get("capability_classification") as? JsonObject ?: return approval
        return JsonObject(approval + ("capability_classification" to classification))
    }

    suspend fun classifyBeforeAcquisition(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, preflight: JsonObject): JsonObject {
        if ("capability_classification" in preflight || tools.isEmpty() || receipts.values.any { it["tool"] != JsonPrimitive(GitHubAuthorizedCatalog.TOOL) }) return preflight
        val catalog = GitHubAuthorizedCatalog.snapshot(receipts, tools)
        if (catalog["complete"] != JsonPrimitive(true)) return preflight
        val states = buildJsonObject {
            GitHubTestChecklist.rows(receipts, tools, task, preflight).filterIsInstance<JsonObject>().filter { it["required"] == JsonPrimitive(true) }.forEach { row ->
                val blocker = (row["blocker_code"] as? JsonPrimitive)?.content.orEmpty()
                val state = when {
                    "APPROVAL" in blocker || blocker == "USER_DECISION_REQUIRED" -> "APPROVAL_BLOCKED"
                    "FIXTURE" in blocker -> "FIXTURE_BLOCKED"
                    blocker in setOf("WORKFLOW_RUN_OR_JOB_REQUIRED", "CHECK_SUBSCRIPTION_REQUIRED") -> "WORKFLOW_INPUT_REQUIRED"
                    blocker == "EXECUTION_NOT_EXERCISED" -> "READY"
                    else -> "LIVE_EVIDENCE_REQUIRED"
                }
                put((row.getValue("id") as JsonPrimitive).content, state)
            }
        }
        if (states.isEmpty()) return preflight
        val plan = buildJsonObject {
            put("request_digest", digest(task))
            put("inventory_version", catalog.getValue("inventory_version"))
            put("receipt_ids", catalog.getValue("receipt_ids"))
            put("states", states)
        }
        return mutex.withLock {
            val approval = fixtureApprovalPreflightLocked()
            if ("capability_classification" in approval) return@withLock approval
            val approvalDigest = digest(approval.toString())
            val version = buildJsonObject {
                put("schema", "github.fixture-classification.v1")
                put("binding", binding)
                put("approval_sha256", approvalDigest)
                put("capability_classification", plan)
            }
            val versionId = "fixture_classification_" + digest(version.toString()).take(24)
            save(versionId, version)
            save(
                fixtureClassificationId,
                buildJsonObject {
                    put("binding", binding)
                    put("approval_sha256", approvalDigest)
                    put("classification_id", versionId)
                }
            )
            JsonObject(approval + ("capability_classification" to plan))
        }
    }

    private fun attemptKey(tool: String, arguments: JsonObject): AttemptKey =
        GitHubActionIdentity.digest(binding, repository, tool, arguments)?.let { AttemptKey(tool, JsonObject(emptyMap()), it) }
            ?: AttemptKey(tool, arguments)

    @Volatile private var taskContract: JsonObject? = null

    @Volatile private var deadlineGrant: JsonObject? = null

    @Volatile private var renewalFailure: ProviderEvent.Failed? = null

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
            val original = restoredOriginal(id, saved)
            if (original == null) overflow = true
            retain(id, revalidate(original ?: saved))
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
        val current = JsonObject(
            saved.filterKeys { it !in setOf("verification_eligible", "exclusion_reason", GitHubPolicyReadRecovery.REVALIDATED) } + buildJsonObject {
                put("verification_eligible", exclusion == null && saved["trusted"] == JsonPrimitive(true))
                exclusion?.let { put("exclusion_reason", it) }
            }
        )
        val revalidated = GitHubPolicyReadRecovery.revalidated(current, authorizationDigest)
        val migrated = if (current["inventory_observation"] == null) {
            GitHubInventoryObservation.of(current)?.let { JsonObject(current + ("inventory_observation" to it)) } ?: current
        } else {
            current
        }
        return if (revalidated == null) migrated else JsonObject(migrated + (GitHubPolicyReadRecovery.REVALIDATED to JsonPrimitive(revalidated)))
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
            val original = restoredOriginal(id, existing)
            if (original == null) overflow = true
            retain(id, revalidate(original ?: existing))
            return@withLock
        }
        val content = when (val body = result.retainedContent ?: result.content) {
            is ToolResultContent.Json -> body.value
            is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(body.text) }.getOrElse { JsonPrimitive(body.text) }
            is ToolResultContent.ResourceLinks -> JsonNull
        }
        val operation = GitHubOperationTool.operationName(call.name) ?: "github".takeIf { call.name.substringBefore("__") == it }
        val authorizedTool = call.name in trustedTools
        val trusted = authorizedTool && (operation != null || call.name == "discover_available_tools" || call.name.isGatewayRepositoryTool())
        val effectiveArguments = (result.executionReceipt?.get("corrected_arguments") as? JsonObject) ?: call.arguments
        val savedSource = savedViewSource(call, result)
        val owner = (effectiveArguments["owner"] as? JsonPrimitive)?.contentOrNull
        val repo = (effectiveArguments["repo"] as? JsonPrimitive)?.contentOrNull
        val selected = owner == null && repo == null || "$owner/$repo".equals(repository, true)
        val targetBranch = (effectiveArguments["ref"] as? JsonPrimitive)?.contentOrNull ?: (effectiveArguments["branch"] as? JsonPrimitive)?.contentOrNull ?: branch
        val immutable = savedSource?.get("immutable")?.let { it == JsonPrimitive(true) }
            ?: GitHubOperationTool.immutableRead(call.name, effectiveArguments)
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
                ?: GitHubIssueRemediation.publishedHead(action, native, root)?.takeIf { !result.isError && result.errorCode == null }
                ?: ((root?.get("head") as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull
                ?: (((root?.get("repository") as? JsonObject)?.get("head") as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull
                ?: (savedSource?.get("source_head_sha") as? JsonPrimitive)?.contentOrNull
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
        val sourceSerialization = content.toString()
        val sourceDigest = digest(sourceSerialization)
        val trustedTarget = trusted && (selected || sourceRead || lifecycleRepository != null)
        val trustFailure = when {
            !authorizedTool -> GitHubAcceptancePublicationEvidence.TrustFailure.TOOL_NOT_IN_AUTHORIZED_CATALOG
            !trusted -> GitHubAcceptancePublicationEvidence.TrustFailure.UNRECOGNIZED_EVIDENCE_OPERATION
            !trustedTarget -> GitHubAcceptancePublicationEvidence.TrustFailure.TARGET_OUTSIDE_ACCEPTED_SCOPE
            else -> null
        }
        val receipt = buildJsonObject {
            put("schema", "github.evidence.v1")
            put("receipt_id", id)
            put("binding", binding)
            put("call_id", call.callId)
            put("original_invocation_fingerprint", call.originalInvocationFingerprint ?: digest("${call.callId}:${call.name}:${call.arguments}"))
            put("tool", call.name)
            sourceRunId?.let { put("source_run_id", it) }
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
            if (trustedTarget && call.name.githubOperationFamily() == "repository_workspace" && !result.isError && !result.sharedResult) {
                workspaceCheckObservation(inferenceProfile, call, root, repository, branch, sourceRunId)?.let { put("profile_workspace_check", it) }
            }
            if (selected &&
                trustedTarget &&
                origin == "LIVE" &&
                !result.isError &&
                !result.sharedResult &&
                call.name.startsWith("github_") &&
                inferenceProfile != null &&
                sourceRunId != null
            ) {
                directCodingObservation(inferenceProfile, call, action, root, result.executionReceipt, head, sourceRunId)?.let { put("direct_coding_observation", it) }
            }
            authorizationDigest?.let { put(GitHubPolicyReadRecovery.AUTHORITY, it) }
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
            val sourceTime = (savedSource?.get("observed_at_ms") as? JsonPrimitive)?.longOrNull
                ?: (result.executionReceipt?.get("source_observed_at_ms") as? JsonPrimitive)?.longOrNull
            val observedAt = sourceTime?.takeIf { it > 0 } ?: now().takeIf { sourceTime == null && origin == "LIVE" && !result.sharedResult }
            put("observed_at_ms", observedAt?.let(::JsonPrimitive) ?: JsonNull)
            put("epoch", savedSource?.get("epoch") ?: JsonPrimitive(epoch))
            savedSource?.get("receipt_id")?.let { put("source_observation_receipt_id", it) }
            put("source_head_sha", head?.let(::JsonPrimitive) ?: JsonNull)
            put("head_observation", selected && !lifecycle && (headObservation || remoteChange))
            put("immutable", immutable)
            put("trusted", trustedTarget)
            put("verification_eligible", trustedTarget)
            put("success", !result.isError && result.errorCode == null)
            put("dispatched", result.dispatched)
            put("shared_result", result.sharedResult)
            put("evidence_origin", origin)
            put("remote_mutation", mutation)
            put("remote_mutation_scope", "OPERATION_CLASSIFICATION_NOT_PROOF_OF_DISPATCH")
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
            result.executionReceipt?.get("semantic_summary")?.let { put("semantic_summary", it) }
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
            put("result_sha256", sourceDigest)
            put("source_result_sha256", sourceDigest)
            put("hash_scope", "IMMUTABLE_SOURCE_RESULT")
            visible?.let {
                put("projection_sha256", digest(it.toString()))
                put("projection_hash_scope", "MODEL_VISIBLE_RESULT")
            }
            put("result_bytes", sourceSerialization.encodeToByteArray().size)
        }
        // The bounded review projection must not discard the durable receipt of an
        // already executed mutation. Persist first; capacity only blocks verification.
        val classified = JsonObject(receipt + ("operation_outcome" to GitHubOperationOutcome.snapshot(receipt)))
        val observed = trustFailure?.let { withTrustObservation(classified, it) } ?: classified
        try {
            saveReceipt(id, observed)
        } catch (failure: Exception) {
            overflow = true // A transient storage error must not turn an unpersisted receipt into proof.
            throw failure
        }
        retain(id, observed)
    }

    private fun savedViewSource(call: ProviderEvent.ToolCall, result: AgentToolResult): JsonObject? {
        if (!result.sharedResult || result.dispatched || result.isError || "view_cursor" !in call.arguments) return null
        val execution = result.executionReceipt ?: return null
        val hash = (execution["saved_view_source_sha256"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
        val page = (result.content as? ToolResultContent.Json)?.value as? JsonObject ?: return null
        if (page["schema"] != JsonPrimitive("github.operation.v1") || page["source_sha256"] != JsonPrimitive(hash)) return null
        val callId = (execution["saved_view_source_call_id"] as? JsonPrimitive)?.contentOrNull
        val viewId = (call.arguments["view_cursor"] as? JsonPrimitive)?.contentOrNull?.substringBefore(':') ?: return null
        val candidates = if (callId != null) {
            listOfNotNull(records["ev_" + digest("$binding|$callId").take(24)])
        } else {
            // Older views can link through their executor-owned view identity.
            // The hash alone cannot distinguish a later identical network read.
            records.values.toList()
        }
        return candidates.filter { source ->
            source["binding"] == JsonPrimitive(binding) &&
                source["tool"] == JsonPrimitive(call.name) &&
                source["repository"] == JsonPrimitive(repository) &&
                source["shared_result"] != JsonPrimitive(true) &&
                source["trusted"] == JsonPrimitive(true) &&
                source["verification_eligible"] != JsonPrimitive(false) &&
                source["success"] == JsonPrimitive(true) &&
                (callId != null || (source["execution"] as? JsonObject)?.get("saved_view_id") == JsonPrimitive(viewId)) &&
                (
                    source["source_result_sha256"] == JsonPrimitive(hash) ||
                        (source["execution"] as? JsonObject)?.get("saved_view_source_sha256") == JsonPrimitive(hash)
                    )
        }.singleOrNull()
    }

    private fun withTrustObservation(receipt: JsonObject, failure: GitHubAcceptancePublicationEvidence.TrustFailure): JsonObject {
        val observed = JsonObject(receipt + (GitHubAcceptancePublicationEvidence.TRUST_KEY to GitHubAcceptancePublicationEvidence.trustObservation(failure)))
        val originalBytes = receipt.toString().encodeToByteArray().size
        val observedBytes = observed.toString().encodeToByteArray().size
        // Optional diagnostics cannot reject an otherwise retainable original or
        // switch its existing single-record write into a chunked write.
        return if (observedBytes > ORIGINAL_RECEIPT_MAX_BYTES || originalBytes <= SIMPLE_RECEIPT_MAX_BYTES && observedBytes > SIMPLE_RECEIPT_MAX_BYTES) receipt else observed
    }

    private fun retain(id: String, value: JsonObject) {
        // Diagnostic provenance belongs to the durable original, not the proof
        // working set. Its bytes cannot change catalog admission or projection.
        val proof = if (GitHubAcceptancePublicationEvidence.TRUST_KEY in value) JsonObject(value - GitHubAcceptancePublicationEvidence.TRUST_KEY) else value
        // Large source text belongs to the durable tier. Keep exact structural
        // facts and already bounded source excerpts in the review working set.
        val projected = if (proof.toString().encodeToByteArray().size > 64 * 1024) GitHubEvidenceProjection.receipt(proof) else proof
        val size = projected.toString().encodeToByteArray().size
        if (bytes + size > retentionLimitBytes || records.size >= retentionLimitRecords) {
            overflow = true
            return
        }
        records[id] = projected
        bytes += size
        if (value["binding"] == JsonPrimitive(binding) && value["receipt_id"] == JsonPrimitive(id)) {
            GitHubAcceptancePublicationEvidence.recordedTrustFailure(value)?.let { trustObservations[id] = it }
        }
        val tool = (value["tool"] as? JsonPrimitive)?.contentOrNull
        val arguments = value["arguments"] as? JsonObject
        if (tool != null && arguments != null) {
            val key = attemptKey(tool, arguments)
            val previous = attempts[key]
            attempts[key] = AttemptHistory((previous?.count ?: 0) + 1, value["receipt_id"], (previous?.allExcluded != false) && value["verification_eligible"] == JsonPrimitive(false))
        }
    }

    /** Same-verdict unresolved claims only; an older missing observation stays unknown. */
    suspend fun claimTrustFailures(verdict: JsonObject, publicationReceipts: Map<String, JsonObject>): List<GitHubAcceptancePublicationEvidence.TrustFailure> = mutex.withLock {
        (verdict["unresolved_claims"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { it["reason"] == JsonPrimitive("UNTRUSTED_RECEIPT") && it["status"] == JsonPrimitive("NOT_MEASURED") }
            .mapNotNull { claim ->
                val id = (claim["receipt_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
                val receipt = publicationReceipts[id] ?: return@mapNotNull null
                if (receipt["trusted"] != JsonPrimitive(false) || receipt["receipt_id"] != JsonPrimitive(id) || receipt["binding"] != JsonPrimitive(binding)) return@mapNotNull null
                trustObservations[id]
            }.distinct()
    }

    /** The existing encrypted workspace store has a per-record bound, not a total-result bound. */
    private suspend fun saveReceipt(id: String, receipt: JsonObject) {
        val text = receipt.toString()
        if (text.encodeToByteArray().size <= SIMPLE_RECEIPT_MAX_BYTES) {
            save(id, receipt)
            return
        }
        // A provider response is bounded at 4 MiB. This also admits its argument
        // and receipt envelope without ever publishing an unbounded manifest.
        require(text.encodeToByteArray().size <= ORIGINAL_RECEIPT_MAX_BYTES) { "GitHub original receipt exceeds its bounded source allowance" }
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
        if (chunk["binding"] != JsonPrimitive(binding) || chunk["receipt_id"] != JsonPrimitive(id) || chunk["index"] != JsonPrimitive(page) || chunk["offset_utf16"] != ref["offset_utf16"] || ref["characters"] != JsonPrimitive(text.length) || chunk["sha256"] != ref["sha256"] || ref["sha256"] != JsonPrimitive(digest(text))) return@withLock null
        JsonObject(
            chunk + buildJsonObject {
                put("complete", page == chunks.lastIndex)
                put("total_pages", chunks.size)
                put("original_receipt_sha256", saved.getValue("original_receipt_sha256"))
            }
        )
    }

    private suspend fun restoredOriginal(id: String, manifest: JsonObject): JsonObject? {
        val chunks = manifest["original_chunks"] as? JsonArray ?: return manifest
        if (chunks.size !in 1..64) return null
        val expectedHash = (manifest["original_receipt_sha256"] as? JsonPrimitive)?.contentOrNull ?: return null
        val expectedCharacters = (manifest["original_receipt_characters"] as? JsonPrimitive)?.longOrNull ?: return null
        if (expectedCharacters !in 1..8L * 1024 * 1024) return null
        val hasher = MessageDigest.getInstance("SHA-256")
        val original = StringBuilder()
        var characters = 0L
        for ((index, raw) in chunks.withIndex()) {
            val ref = raw as? JsonObject ?: return null
            val key = (ref["id"] as? JsonPrimitive)?.contentOrNull ?: return null
            if (key != "original_${id}_${expectedHash.take(24)}_$index") return null
            val chunk = load(key) ?: return null
            val text = (chunk["text"] as? JsonPrimitive)?.contentOrNull ?: return null
            if (chunk["binding"] != JsonPrimitive(binding) || chunk["receipt_id"] != JsonPrimitive(id) || chunk["index"] != JsonPrimitive(index) || (chunk["offset_utf16"] as? JsonPrimitive)?.longOrNull != characters || chunk["offset_utf16"] != ref["offset_utf16"] || ref["characters"] != JsonPrimitive(text.length) || chunk["sha256"] != ref["sha256"] || ref["sha256"] != JsonPrimitive(digest(text))) return null
            hasher.update(text.encodeToByteArray())
            characters += text.length
            if (characters > expectedCharacters) return null
            original.append(text)
        }
        if (characters != expectedCharacters || hasher.digest().joinToString("") { "%02x".format(it) } != expectedHash) return null
        val restored = runCatching { Json.parseToJsonElement(original.toString()) as? JsonObject }.getOrNull() ?: return null
        if (listOf("binding", "receipt_id", "call_id", "tool", "repository", "arguments", "result_sha256").any { restored[it] != manifest[it] }) return null
        // Restore exact data before constructing today's projection and inventory metadata.
        // The immutable saved manifest and chunks are never rewritten during migration.
        return restored
    }

    suspend fun snapshot(): Pair<Map<String, JsonObject>, Long> {
        val (saved, currentEpoch) = mutex.withLock { records.toMap() to epoch }
        val resolutions = saved.values.flatMap { receipt ->
            if (GitHubPendingApproval.taskId(receipt) == null) emptyList() else approvalResolution(receipt)
        }
        if (resolutions.isEmpty()) return saved to currentEpoch
        val augmented = LinkedHashMap(saved)
        resolutions.forEach { resolution ->
            val id = (resolution["receipt_id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            augmented.putIfAbsent(id, resolution)
        }
        return augmented to currentEpoch
    }

    suspend fun complete(): Boolean = mutex.withLock { !overflow }

    /** Page persistence can recover independently without invalidating an accepted mutation. */
    suspend fun retentionStatus(): JsonObject = mutex.withLock { retentionStatusLocked() }

    private fun retentionStatusLocked(): JsonObject {
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
        return buildJsonObject {
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
    suspend fun retainTaskContract(task: String, sourceMessageId: Int, tools: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition>, callLimit: Int, executionCallLimit: Int = callLimit) = mutex.withLock {
        val key = "task_contract"
        val previous = taskContract ?: load(key)
        if (previous != null) {
            check(previous["binding"] == JsonPrimitive(binding) && previous["repository"] == JsonPrimitive(repository) && previous["source_message_id"] == JsonPrimitive(sourceMessageId)) {
                "GITHUB_TASK_IDENTITY_CHANGED: The retained task belongs to a different accepted request. No operation was dispatched."
            }
            taskContract = previous
            deadlineGrant = latestDeadlineGrant()?.takeIf { validDeadlineGrant(it, previous) } ?: deadlineGrant?.takeIf { validDeadlineGrant(it, previous) }
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
            put("effective_tool_call_limit", executionCallLimit.coerceIn(0, callLimit.coerceAtLeast(0)))
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

    /** A time grant never grants mutation, approval, ownership or verification-only execution authority. */
    suspend fun authorizeTimeWindow(instruction: String, messageId: Int, messageCreatedSeconds: Long, executionAllowed: Boolean = true) = mutex.withLock {
        val duration = GitHubExecutionLimits.renewedDuration(instruction) ?: return@withLock
        if (!executionAllowed) return@withLock
        val contract = taskContract ?: return@withLock
        val source = (contract["source_message_id"] as? JsonPrimitive)?.intOrNull ?: return@withLock
        val previous = deadlineGrant ?: contract
        val end = (previous["deadline_at_ms"] as? JsonPrimitive)?.longOrNull ?: return@withLock
        val nowMs = now()
        if (messageId <= source || messageCreatedSeconds !in 1..(nowMs / 1000)) return@withLock
        val sent = messageCreatedSeconds * 1000
        if (sent + 999 < end || messageId <= ((previous["grant_message_id"] as? JsonPrimitive)?.intOrNull ?: source)) return@withLock
        if (!authorizeTaskContinuation()) {
            renewalFailure = GitHubExecutionLimits.admissionFailure("GITHUB_TASK_IDENTITY_CHANGED", "The saved task no longer belongs to this authorized run. Its time allowance and receipts remain unchanged.", "RESTORE_ACCEPTED_TASK_CONTRACT")
            return@withLock
        }
        val receipts = LinkedHashMap(records)
        records.values.forEach { receipt ->
            if (GitHubPendingApproval.taskId(receipt) != null) {
                approvalResolution(receipt).forEach { resolution ->
                    (resolution["receipt_id"] as? JsonPrimitive)?.contentOrNull?.let { receipts.putIfAbsent(it, resolution) }
                }
            }
        }
        val ledger = GitHubExecutionLedger.attach(JsonObject(emptyMap()), receipts)["execution_ledger"] as? JsonObject
        val unresolved = (ledger?.get("unresolved_remote_outcomes") as? JsonPrimitive)?.intOrNull ?: 0
        if (unresolved > 0) {
            renewalFailure = GitHubExecutionLimits.admissionFailure("TOOL_OUTCOME_UNKNOWN", "Reconcile the saved operation's unknown outcome before continuing this task with a new time allowance.", "RECONCILE_TOOL_RECEIPTS")
            return@withLock
        }
        if (GitHubPendingApproval.snapshot(receipts).isNotEmpty()) {
            renewalFailure = GitHubExecutionLimits.admissionFailure("WAITING_FOR_APPROVAL", "Resolve the task's existing approval card before continuing with a new time allowance.", "REVIEW_EXISTING_APPROVAL_CARD")
            return@withLock
        }
        val grant = buildJsonObject {
            put("schema", "github.deadline-grant.v1")
            put("binding", binding)
            put("repository", repository)
            put("source_message_id", source)
            put("grant_message_id", messageId)
            put("accepted_at_ms", sent)
            put("deadline_at_ms", sent + duration)
            put("previous_deadline_at_ms", end)
            put("authorized_duration_ms", duration)
            put("authorization_source", "EXPLICIT_USER_MESSAGE")
        }
        save("deadline_grant_$messageId", grant)
        deadlineGrant = grant
        renewalFailure = null
    }

    private fun validDeadlineGrant(grant: JsonObject, contract: JsonObject): Boolean {
        fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val source = contract.number("source_message_id") ?: return false
        val start = grant.number("accepted_at_ms") ?: return false
        val end = grant.number("deadline_at_ms") ?: return false
        val originalEnd = contract.number("deadline_at_ms") ?: return false
        val previousEnd = grant.number("previous_deadline_at_ms") ?: return false
        val duration = grant.number("authorized_duration_ms") ?: return false
        return grant["schema"] == JsonPrimitive("github.deadline-grant.v1") &&
            grant["binding"] == contract["binding"] &&
            grant["source_message_id"] == contract["source_message_id"] &&
            (grant["repository"] == null || grant["repository"] == contract["repository"]) &&
            grant["authorization_source"] == JsonPrimitive("EXPLICIT_USER_MESSAGE") &&
            (grant.number("grant_message_id") ?: 0) > source &&
            start in 0..now() &&
            previousEnd >= originalEnd &&
            previousEnd - start <= 999 &&
            duration in 60_000L..86_400_000L &&
            end >= start &&
            end - start == duration
    }

    suspend fun executionAdmission(): GitHubExecutionLimits.Admission {
        val contract = (taskContract ?: load("task_contract"))?.takeIf { it["binding"] == JsonPrimitive(binding) && it["repository"] == JsonPrimitive(repository) }
        val grant = deadlineGrant?.takeIf { contract != null && validDeadlineGrant(it, contract) }
        return GitHubExecutionLimits.admission(grant ?: contract, now(), requireAcceptedContract)
    }

    suspend fun taskAdmissionFailure(): ProviderEvent.Failed? = renewalFailure ?: executionAdmission().failure()

    suspend fun deadlineTiming(): JsonObject = executionAdmission().timing

    suspend fun remainingTaskMillis(): Long? = executionAdmission().remainingMillis

    @Volatile private var issueCohort: JsonObject? = null

    /** The first complete intake survives refreshed inventories and process restart. */
    suspend fun issueRemediation(task: String): GitHubIssueRemediation.Context? = mutex.withLock {
        val accepted = ((taskContract ?: load("task_contract"))?.get("requested_actions") as? JsonPrimitive)?.contentOrNull ?: task
        if (!GitHubIssueRemediation.requested(accepted)) return@withLock null
        val key = "issue_cohort_" + digest(binding).take(24)
        val saved = issueCohort ?: load(key)
        val cohort = if (saved != null) {
            check(GitHubIssueRemediation.valid(saved, accepted, binding, repository)) { "GITHUB_ISSUE_COHORT_CHANGED: Retained intake does not match the accepted task." }
            saved
        } else {
            GitHubIssueRemediation.freeze(accepted, binding, repository, records.toMap())?.also { save(key, it) } ?: return@withLock null
        }
        issueCohort = cohort
        val previous = load("verification_current_" + digest(binding).take(24))
        val rows = if (previous?.get("binding") == JsonPrimitive(binding) && previous["issue_cohort"] == cohort) {
            (previous["requirements"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        } else {
            emptyList()
        }
        GitHubIssueRemediation.Context(cohort, rows)
    }

    suspend fun acceptedTaskRequest(): String? = ((taskContract ?: load("task_contract"))?.get("requested_actions") as? JsonPrimitive)?.contentOrNull

    suspend fun acceptedPromptContract(): JsonObject? = (taskContract ?: load("task_contract"))?.let { contract ->
        JsonObject(contract.filterKeys { it in setOf("source_message_id", "repository", "accepted_branch", "request_digest", "acceptance_criteria", "tool_call_limit", "effective_tool_call_limit", "tool_call_limit_source") })
    }

    /** Report the same accepted ceiling and execution counter across process restart. */
    suspend fun stageBudget(receipts: Map<String, JsonObject>, current: Int?, fixtureCalls: Int): JsonObject {
        val contract = (taskContract ?: load("task_contract"))?.takeIf { it["binding"] == JsonPrimitive(binding) && it["repository"] == JsonPrimitive(repository) }
        val retained = if (current == null) {
            val previous = load("verification_current_" + digest(binding).take(24))
            val checklist = previous?.get("test_checklist") as? JsonObject
            checklist?.get("stage_budget") as? JsonObject ?: previous?.get("stage_budget") as? JsonObject
        } else {
            null
        }
        return GitHubChecklistBudget.checkpoint(receipts, current, fixtureCalls, contract, retained)
    }

    /** One bounded delivery snapshot, separate from immutable operation receipts and their verdict. */
    suspend fun retainAnswerIfCurrent(
        text: String,
        verdict: JsonObject,
        expectedEpoch: Long,
        expectedReceiptIds: Set<String>
    ): Boolean = mutex.withLock {
        if (epoch != expectedEpoch || records.keys != expectedReceiptIds) return@withLock false
        require(text.isNotBlank() && text.length <= 1_048_576)
        val receiptDigest = digest(expectedReceiptIds.sorted().joinToString("\n"))
        if (!substantiveReport(verdict)) {
            val key = digest(binding).take(24)
            val prior = load("answer_current_$key")
            if (prior != null &&
                prior["binding"] == JsonPrimitive(binding) &&
                prior["epoch"] == JsonPrimitive(epoch) &&
                prior["receipt_set_digest"] == JsonPrimitive(receiptDigest) &&
                substantiveReport(prior["verdict"] as? JsonObject)
            ) {
                val retained = JsonObject(prior.filterKeys { it in setOf("binding", "epoch", "text", "text_sha256", "receipt_set_digest") })
                if (load("answer_useful_current_$key") != retained) save("answer_useful_current_$key", retained)
            }
        }
        saveCurrent(
            "answer",
            buildJsonObject {
                put("schema", "github.saved-answer.v1")
                put("binding", binding)
                put("epoch", epoch)
                put("text", text)
                put("text_sha256", digest(text))
                put("receipt_set_digest", receiptDigest)
                put("verdict", verdict)
            }
        )
        true
    }

    private fun substantiveReport(verdict: JsonObject?): Boolean = verdict != null &&
        verdict["progress_state"] != JsonPrimitive("BLOCKED_UNCHANGED") &&
        (
            verdict["completion_verified"] == JsonPrimitive(true) ||
                (verdict["claims"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any {
                    it["status"] in setOf(JsonPrimitive("VERIFIED"), JsonPrimitive("HISTORICAL"), JsonPrimitive("INFERRED"))
                }
            )

    suspend fun retainedAnswer(includeEarlierFindings: Boolean = false): Pair<String, JsonObject>? {
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
        if (saved["epoch"] != null && saved["epoch"] != JsonPrimitive(epoch)) return null
        if (includeEarlierFindings && !substantiveReport(verdict)) {
            val prior = load("answer_useful_current_" + digest(binding).take(24))
            val earlier = (prior?.get("text") as? JsonPrimitive)?.contentOrNull
            if (!earlier.isNullOrBlank() &&
                earlier.length <= 1_048_576 &&
                prior["binding"] == saved["binding"] &&
                prior["epoch"] == JsonPrimitive(epoch) &&
                prior["receipt_set_digest"] == saved["receipt_set_digest"] &&
                prior["text_sha256"] == JsonPrimitive(digest(earlier))
            ) {
                return "$earlier\n\n## Latest checkpoint\n\n$text" to verdict
            }
        }
        return text to verdict
    }

    private fun claimBatchKey() = "claim_extraction_current_" + digest(binding).take(24)

    suspend fun retainedClaimExtraction(context: String? = null): JsonObject? = load(claimBatchKey())?.takeIf { saved ->
        saved["schema"] == JsonPrimitive("github.claim-extraction.v1") &&
            saved["binding"] == JsonPrimitive(binding) &&
            (context == null || saved["context"] == JsonPrimitive(context)) &&
            saved["authorization"] == (authorizationDigest?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
    }

    suspend fun retainClaimBatches(
        context: String,
        plan: String,
        draft: String,
        batches: List<JsonObject?>,
        requirementIds: List<Set<String>>,
        pending: Boolean
    ) {
        require(batches.size <= GitHubClaimBatches.MAX_BATCHES)
        require(batches.size == requirementIds.size)
        require(draft.length <= 1_048_576) { "Claim extraction draft exceeds the accepted session bound" }
        save(
            claimBatchKey(),
            buildJsonObject {
                put("schema", "github.claim-extraction.v1")
                put("binding", binding)
                put("context", context)
                put("plan", plan)
                put("authorization", authorizationDigest)
                put("pending", pending)
                // Keep the bounded source draft and finished batches after extraction.
                // A later read can then invalidate only claims that no longer verify.
                put("draft", draft)
                put("batch_requirement_ids", JsonArray(requirementIds.map { ids -> JsonArray(ids.sorted().map(::JsonPrimitive)) }))
                put("batches", JsonArray(batches.map { it ?: JsonNull }))
            }
        )
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
                            put("contract_digest", dev.chungjungsoo.gptmobile.data.agent.ToolContractFingerprint.definition(tool))
                            put("contract_digest_kind", "SEMANTIC_SCHEMA_V1")
                        }
                    }
                )
            )
            put("redaction", "Names and contract digests only; no credentials, provider destinations, prompts or argument values")
        }
        save("policy_" + digest(policy.toString()).take(24), policy)
    }

    suspend fun retainProgress(task: String, tools: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition>, failureCode: String? = null, failure: ProviderEvent.Failed? = null, remainingToolCalls: Int? = null) {
        val (receipts, _) = snapshot()
        val issueWork = issueRemediation(task)
        val fixturePreflight = if (GitHubTestChecklist.integration(task)) fixtureApprovalPreflight() else null
        val budget = stageBudget(receipts, remainingToolCalls, GitHubSelfTestFixtures.minimumCalls(tools))
        val checkpoint = GitHubTestChecklist.checkpoint(task, receipts, tools, remainingToolCalls, fixturePreflight, budget)
        val retention = retentionStatus()
        val previous = diagnostic() ?: load("verification_current_" + digest(binding).take(24))
        val planId = "verification_plan_" + digest("$binding|$task").take(24)
        if (previous == null && load(planId) == null) {
            save(
                planId,
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
            GitHubIntegrationAcceptance.requirements(task, receipts, tools, fixturePreflight = fixturePreflight)
        } else {
            JsonArray(
                GitHubRequestCompletion.requirements(task, issueWork).map { requirement ->
                    if (requirement == GitHubIssueRemediation.INVENTORY_REQUIREMENT && GitHubIssueRemediation.requested(task)) {
                        return@map GitHubIssueRemediation.inventory(issueWork)
                    }
                    GitHubIssueRemediation.group(requirement, issueWork)?.let { group ->
                        return@map GitHubIssueRemediation.assess(group, requireNotNull(issueWork), null, emptySet(), false, receipts, now())
                    }
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
                issueWork?.let { put("issue_cohort", it.cohort) }
                put("requirements", requirements)
                put("remaining_requirements", JsonArray(requirements.filter { (it as JsonObject)["acceptance_state"] != JsonPrimitive("PASS") }))
                put("test_checklist", checkpoint)
                put("evidence_count", receipts.size)
                if (retention["evidence_retention_complete"] == JsonPrimitive(true)) GitHubTestChecklist.sourceReadRecovery(tools, receipts)?.let { put("source_read_recovery", it) }
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

    suspend fun retainVerdict(value: JsonObject): JsonObject = mutex.withLock {
        retainVerdictLocked(value)
    }

    /**
     * A completion verdict is valid only for the exact receipt epoch that was
     * rendered. The mutex makes this check and current-verdict persistence one
     * operation, so a late child receipt cannot land between them.
     */
    suspend fun retainVerdictIfCurrent(value: JsonObject, expectedEpoch: Long, expectedReceiptIds: Set<String>): JsonObject? = mutex.withLock {
        if (epoch != expectedEpoch || records.keys != expectedReceiptIds) return@withLock null
        retainVerdictLocked(value)
    }

    private suspend fun retainVerdictLocked(value: JsonObject): JsonObject {
        val retention = retentionStatusLocked()
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
        val receipts = records.toMap()
        val provenance = sourceRunId?.let { currentRun ->
            val observations = receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) }
            val current = observations.filter { it["source_run_id"] == JsonPrimitive(currentRun) }
            val reused = observations.filterNot { it["source_run_id"] == JsonPrimitive(currentRun) }
            buildJsonObject {
                put("run_id", currentRun)
                put("current_run_receipts", current.size)
                put(
                    "current_run_live_operations",
                    current.count { receipt ->
                        val transport = receipt["execution"] as? JsonObject
                        listOf("read_requests", "mutation_requests").any { ((transport?.get(it) as? JsonPrimitive)?.longOrNull ?: 0L) > 0 }
                    }
                )
                put("reused_receipts", reused.size)
                put("unattributed_reused_receipts", reused.count { it["source_run_id"] == null })
                val sources = reused.mapNotNull { (it["source_run_id"] as? JsonPrimitive)?.contentOrNull }.distinct()
                put("source_run_count", sources.size)
                put("source_run_ids", kotlinx.serialization.json.JsonArray(sources.take(16).map(::JsonPrimitive)))
            }
        }
        val retained = GitHubAcceptanceLedger.attach(JsonObject(value + retention + limitation + listOfNotNull(provenance?.let { "verdict_evidence_provenance" to it })), receipts)
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
        val execution = GitHubExecutionLedger.attach(checked, receipts)
        val checkpoint = JsonObject(execution + ("task_state" to JsonPrimitive(GitHubTaskLifecycle.terminal(execution).name)))
        val sourceRecovery = if (retention["evidence_retention_complete"] == JsonPrimitive(true)) {
            val tools = currentToolContracts.map { (name, schema) -> dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition(name, "", schema) }
            GitHubTestChecklist.sourceReadRecovery(tools, receipts)
        } else {
            null
        }
        val durable = JsonObject(
            checkpoint.filterKeys { it != "source_read_recovery" } + buildJsonObject {
                put("binding", binding)
                sourceRecovery?.let { put("source_read_recovery", it) }
            }
        )
        val explained = JsonObject(durable + (GitHubAcceptanceExplanation.KEY to GitHubAcceptanceExplanation.record(durable, taskContract ?: load("task_contract"))))
        saveCurrent("verification", explained)
        return explained
    }

    /** Full projections replace only their task-current slot; immutable operation evidence is never rewritten. */
    private suspend fun saveCurrent(kind: String, snapshot: JsonObject) {
        val value = if (kind == "verification") {
            JsonObject(
                snapshot + mapOf(
                    "work_measurement" to GitHubWorkMeasurement.from(snapshot).json(),
                    GitHubAcceptanceExplanation.KEY to GitHubAcceptanceExplanation.record(snapshot, taskContract ?: load("task_contract"))
                )
            )
        } else {
            snapshot
        }
        val id = "${kind}_current_" + digest(binding).take(24)
        val previous = load(id)
        if (previous?.filterKeys { it != "observed_at_ms" } == value.filterKeys { it != "observed_at_ms" }) return
        fun summary(snapshot: JsonObject): JsonObject = buildJsonObject {
            put("schema", "github.verification-event.v1")
            put("binding", binding)
            put("snapshot_id", id)
            listOf("phase", "task_state", "task_outcome", "failure_code", "verification_failure", "completion_verified", "response_prepared", "response_delivered", "progress_state").forEach { field -> snapshot[field]?.let { put(field, it) } }
            (snapshot["stop_reason"] as? JsonObject)?.get("code")?.let { put("stop_code", it) }
            val requirements = (snapshot["requirements"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            put("requirements", JsonArray(requirements.take(64).map { row -> JsonObject(row.filterKeys { it in setOf("id", "status", "acceptance_state") }) }))
            ((snapshot["acceptance_ledger"] as? JsonObject)?.get("diagnostic_projection") as? JsonObject)?.takeIf(GitHubAcceptanceDiagnostics::valid)?.let { projection ->
                val states = JsonArray((projection["criteria"] as JsonArray).filterIsInstance<JsonObject>().map { row -> JsonObject(row.filterKeys { it in setOf("id_sha256", "state", "blocker", "next_action") }) })
                put("acceptance_states_sha256", digest(states.toString()))
                put("criterion_count", projection.getValue("criterion_count"))
                put("criteria_omitted", projection.getValue("omitted_count"))
                put(
                    "acceptance_criteria",
                    JsonArray(
                        (projection["criteria"] as JsonArray).filterIsInstance<JsonObject>().filter { it["kind"] == JsonPrimitive("REQUIREMENT") }.map { row ->
                            JsonObject(row.filterKeys { it in setOf("ordinal", "id_sha256", "category", "label", "operation", "state", "blocker", "next_action") })
                        }
                    )
                )
            }
            val checklist = (snapshot["test_checklist"] as? JsonObject) ?: snapshot
            val capabilities = (checklist["capabilities"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            put("capability_states", buildJsonObject { capabilities.groupingBy { (it["status"] as? JsonPrimitive)?.content.orEmpty() }.eachCount().toSortedMap().forEach { (state, count) -> put(state, count) } })
            put("capability_states_sha256", digest(JsonArray(capabilities.map { row -> JsonObject(row.filterKeys { it in setOf("id", "status", "acceptance_state", "blocker_code") }) }).toString()))
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
                    value["evidence_count"]?.let { put("evidence_count", it) }
                }
            )
            save("verification_event_" + digest("$binding|$kind|$delta").take(24), delta)
        }
    }

    companion object {
        private const val SIMPLE_RECEIPT_MAX_BYTES = 1_400_000
        private const val ORIGINAL_RECEIPT_MAX_BYTES = 8 * 1024 * 1024

        fun currentSnapshot(id: String): Boolean = id.matches(Regex("(?:verification|checklist|answer|answer_useful|claim_extraction|fixture_classification)_current_[a-f0-9]{24}"))

        /** Preparing a verdict does not prove that its visible delivery completed. */
        fun publishedChecklistDigest(checkpoint: JsonObject?): JsonPrimitive? =
            (
                checkpoint?.get("checklist_progress_digest").takeIf { checkpoint?.get("response_delivered") == JsonPrimitive(true) }
                    ?: checkpoint?.get("last_published_checklist_progress_digest")
                ) as? JsonPrimitive

        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
