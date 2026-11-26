package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolExceptionEvidence
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphAdmissionException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphHttpFailure
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphOperationException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphPrerequisite
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphUnavailableException
import dev.chungjungsoo.gptmobile.data.github.GitHubCooldownActive
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy
import dev.chungjungsoo.gptmobile.data.github.GitHubReadValidation
import dev.chungjungsoo.gptmobile.data.github.GitHubRequestRejected
import dev.chungjungsoo.gptmobile.data.github.PhoneTaskContextFailure
import dev.chungjungsoo.gptmobile.data.network.MetadataResponseObservation
import dev.chungjungsoo.gptmobile.data.network.NetworkFailureKind
import dev.chungjungsoo.gptmobile.data.network.isLocalPrivacyPolicyRejection
import dev.chungjungsoo.gptmobile.data.network.networkFailureKind
import dev.chungjungsoo.gptmobile.data.privacy.UnverifiedServiceTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Revision-bound map reads run on the phone by default; Gateway is an explicit optional source. */
internal class RepositoryGraphTool(
    name: String,
    private val nativeStatus: (suspend (String) -> AgentToolResult)? = null,
    private val repositoryScope: dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext? = null,
    private val accountIdentity: String? = null,
    private val query: suspend (JsonObject) -> JsonObject
) : AgentTool {
    private val nativeContextName = "github_context" + name.removePrefix("repository_graph")
    override val definition = AgentToolDefinition(
        name = name,
        description = "Inspect the selected repository map. Phone is the default source: status shows structure immediately; index prepares bounded source batches; task_context_bundle returns task-ranked exact source and document sections with reference freshness, coverage, test candidates and optional variant CURRENT/REFERENCE/TASK/SEMANTIC; architecture, query, node, callers, callees, path, impact and metrics use cached source. Follow index_cursor while has_more=true. Phone relationships include explicit lexical candidates, never compiler or executed-test proof; retain coverage and confidence. Set map_source=gateway only for an explicitly configured and authorized Gateway. Gateway impact accepts a source symbol, path or exact JVM method descriptor. Gateway diff uses options.base_sha to compare indexed revisions, including changed compiled methods, callers and test paths when both revisions have qualified workflow artifacts. Retain compiler_impact confidence and fallback reason. Gateway additionally supports diff, diagnose, contracts, prs, notes, requirements, wiki, federated and provider. Observe background Gateway jobs with status; do not replay indexing or notes after unknown outcomes. options carries paths, source, target, index_cursor or result_cursor. Verify source and required CI.",
        inputSchema = schema
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val errors = dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(arguments, definition.inputSchema)
        if (errors.isNotEmpty()) return AgentToolResult(callId, ToolResultContent.Text(errors.joinToString("; ")), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        val requestedOptions = (arguments["options"] as? JsonObject).orEmpty()
        val requestedCommit = requestedOptions["commit_sha"] as? JsonPrimitive
        // A copied commit on a status read is an assertion about the selected workspace.
        // Check it locally, then leave the repository binding as the only revision sent downstream.
        val options = if (repositoryScope != null && arguments["action"] == JsonPrimitive("status") && "commit_sha" in requestedOptions) {
            if (requestedCommit?.isString != true || !repositoryScope.commitSha.equals(requestedCommit.content, ignoreCase = true)) {
                return AgentToolResult(
                    callId,
                    ToolResultContent.Text("Select the intended repository revision before reading its map. An explicit commit in options must match the selected commit."),
                    isError = true,
                    errorCode = "GITHUB_VALIDATION_FAILED",
                    dispatched = false,
                    executionReceipt = buildJsonObject {
                        put("call_id", callId)
                        put("phase", "PREFLIGHT_REJECTED")
                        put("reason_code", "EVIDENCE_REVISION_MISMATCH")
                    }
                )
            }
            requestedOptions - "commit_sha"
        } else {
            requestedOptions
        }
        if (options.keys.any { it in setOf("repository", "owner", "repo", "branch", "commit_sha", "ref", "action", "map_source") }) {
            return AgentToolResult(callId, ToolResultContent.Text("Repository revision, action and map source cannot be overridden through options."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        val mapSource = arguments["map_source"] ?: JsonPrimitive("phone")
        val phone = mapSource == JsonPrimitive("phone")
        val phoneAccounting = if (phone) GitHubDispatchAccounting() else null
        val phoneInterruption = if (phone) currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.PhoneIndexInterruption]?.begin(callId, definition.name, arguments, repositoryScope, accountIdentity) else null
        val gatewayObservation = if (phone) null else MetadataResponseObservation(currentCoroutineContext()[MetadataResponseObservation])
        val started = System.nanoTime()
        val result = try {
            val payload = JsonObject(options + (arguments - "options") + mapOf("budget_bytes" to JsonPrimitive(16000), "map_source" to mapSource))
            val observed = if (phoneAccounting != null) withContext(phoneAccounting + (phoneInterruption ?: kotlin.coroutines.EmptyCoroutineContext)) { query(payload) } else withContext(requireNotNull(gatewayObservation)) { query(payload) }
            // GatewayGraphRepository replaces this object with its typed local observation.
            // Remote graph results cannot author checkpoint join or confirmation evidence.
            val checkpoint = (observed["index_checkpoint"] as? JsonObject)?.takeUnless { phone }
            val unresolved = checkpoint?.get("index_outcome_unknown") == JsonPrimitive(true)
            val attemptHash = checkpoint?.hash("graph_index_attempt_hash")
            val confirmedAttemptHash = checkpoint?.hash("confirmed_index_attempt_hash")
            AgentToolResult(
                callId,
                ToolResultContent.Json(if (unresolved) JsonObject(observed + savedIndexRecovery(mapSource)) else observed),
                isError = false,
                executionReceipt = if (unresolved || attemptHash != null || confirmedAttemptHash != null) {
                    buildJsonObject {
                        put("schema", RECEIPT_SCHEMA)
                        put("call_id", callId)
                        put("action", requireNotNull(arguments["action"]))
                        put("phase", "COMPLETED")
                        put("current_index_outcome_unknown", false)
                        put("index_outcome_unknown", unresolved)
                        // This operation completed. An earlier saved index remains a
                        // separate obligation and must not block independent read handoff.
                        put("requires_reconciliation", false)
                        attemptHash?.let { put("graph_index_attempt_hash", it) }
                        confirmedAttemptHash?.let { put("confirmed_index_attempt_hash", it) }
                        if (unresolved) {
                            put("repair_action", "CHECK_SAVED_INDEX")
                            put("recovery_action", "RECONCILE_OUTCOME")
                        }
                    }
                } else {
                    null
                }
            )
        } catch (cancelled: CancellationException) {
            phoneAccounting?.cancelled()
            if (phoneAccounting != null) phoneInterruption?.cancelledAfterUnwind(phoneAccounting)
            throw cancelled
        } catch (failure: Exception) {
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            val operation = failure as? GatewayGraphOperationException
            val cause = operation?.cause ?: failure
            val localContextFailure = (cause as? PhoneTaskContextFailure)?.takeIf { phone }
            val privacyDenied = failure.isLocalPrivacyPolicyRejection()
            val admission = cause as? GatewayGraphAdmissionException
            val prerequisite = cause as? GatewayGraphUnavailableException
            val phoneRejection = (cause as? GitHubRequestRejected)?.takeIf { phone }
            val phoneValidation = (cause as? GitHubReadValidation)?.takeIf { phone }
            val phoneDeadline = phone && (phoneValidation?.code == "READ_DEADLINE" || (cause as? dev.chungjungsoo.gptmobile.data.github.GatewayGraphResultException)?.code == "READ_DEADLINE")
            if (phoneDeadline) {
                phoneAccounting?.cancelled()
                if (phoneAccounting != null) phoneInterruption?.cancelledAfterUnwind(phoneAccounting)
            }
            val phoneCooldown = if (phone) {
                cause as? GitHubCooldownActive ?: phoneRejection?.takeIf { it.status == 0 }?.cause as? GitHubCooldownActive
            } else {
                null
            }
            val phoneCode = phoneValidation?.code ?: phoneCooldown?.errorCode ?: phoneRejection?.let {
                it.message.orEmpty().substringBefore(" · ").takeIf(GitHubFailurePolicy.codes::contains) ?: GitHubFailurePolicy.classify(it.status)
            }
            val phoneRateLimit = phoneRejection?.let { phoneRateLimitEvidence(it, requireNotNull(phoneCode)) }
            val httpStatus = when (cause) {
                is GatewayGraphHttpFailure -> cause.httpStatus
                is GatewayGraphUnavailableException -> cause.httpStatus
                is dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRequestRejected -> cause.httpStatus
                is GitHubRequestRejected -> phoneRejection?.status?.takeIf { it in 100..599 }
                else -> null
            }
            val retryAfterMillis = phoneCooldown?.delayMillis ?: (phoneRateLimit?.get("retry_after_ms") as? JsonPrimitive)?.longOrNull ?: when (cause) {
                is GatewayGraphHttpFailure -> cause.retryAfterMillis
                is GatewayGraphUnavailableException -> cause.retryAfterMillis
                else -> null
            }
            val rateLimitObservation = (cause as? GatewayGraphHttpFailure)?.rateLimitObservation
            val unresolved = !phone && operation?.indexOutcomeUnknown == true
            val currentIndexUnknown = !phone && arguments["action"] == JsonPrimitive("index") && operation?.currentIndexOutcomeUnknown == true
            val repairAction = if (unresolved) {
                "CHECK_SAVED_INDEX"
            } else if (privacyDenied) {
                "REVIEW_PRIVACY_MODE"
            } else {
                operation?.repairAction ?: admission?.repairAction ?: prerequisite?.prerequisites?.firstOrNull()?.nextAction
            }
            val code = if (privacyDenied) UnverifiedServiceTransport.CODE else phoneCode ?: admission?.code ?: (cause as? dev.chungjungsoo.gptmobile.data.github.GatewayGraphResultException)?.code ?: "REPOSITORY_GRAPH_UNAVAILABLE"
            val locallyDeniedGateway = privacyDenied && gatewayObservation != null && gatewayObservation.observedAtEpochMs == null && !gatewayObservation.hasInterruptedRead
            val localRejection = admission != null || phoneCooldown != null || phoneRejection?.status == 0 || locallyDeniedGateway
            val readAction = arguments["action"] !in setOf(JsonPrimitive("index"), JsonPrimitive("note"))
            // A transport/readiness failure of a status read may trigger one independent,
            // authorized native read. Typed readiness does not include graph policy,
            // identity or permission rejection. Indexing and notes are never replayed.
            val unavailable = !privacyDenied &&
                (
                    cause is GatewayGraphUnavailableException ||
                        cause is java.io.IOException &&
                        cause !is javax.net.ssl.SSLException
                    )
            val fallback = if (!phone && arguments["action"] == JsonPrimitive("status") && unavailable) {
                try {
                    nativeStatus?.invoke("$callId-native-status")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("code", code)
                        put("operation", requireNotNull(arguments["action"]))
                        put("map_source", mapSource)
                        put("elapsed_ms", elapsedMillis)
                        operation?.targetRevision?.let { put("target_revision", it) }
                        (cause as? GatewayGraphHttpFailure)?.requestId?.let { put("gateway_http_request_id", it) }
                        if (readAction && !unresolved && admission == null && !privacyDenied) {
                            put(
                                "recheck_after_recovery",
                                buildJsonObject {
                                    put("tool", definition.name)
                                    put("source_call_id", callId)
                                }
                            )
                        }
                        prerequisite?.diagnostic()?.forEach { (key, value) -> put(key, value) }
                        if (prerequisite == null) {
                            put(
                                "failure_category",
                                if (unresolved && admission?.code == "GRAPH_INDEX_OUTCOME_UNKNOWN") {
                                    "RECONCILIATION_REQUIRED"
                                } else if (privacyDenied) {
                                    "LOCAL_POLICY_OR_CONFIGURATION"
                                } else if (phoneDeadline) {
                                    "LOCAL_READ_DEADLINE"
                                } else if (localContextFailure != null) {
                                    "LOCAL_CONTEXT_PREPARATION"
                                } else if (phoneCooldown != null) {
                                    "LOCAL_RATE_LIMIT"
                                } else if (phoneRejection != null) {
                                    if (phoneRejection.status == 0) "LOCAL_REQUEST_ADMISSION" else "GITHUB_HTTP_REJECTED"
                                } else if (admission != null) {
                                    "LOCAL_POLICY_OR_CONFIGURATION"
                                } else if (cause is java.io.IOException && cause !is javax.net.ssl.SSLException) {
                                    "TRANSPORT_UNAVAILABLE"
                                } else {
                                    "GRAPH_REQUEST_REJECTED"
                                }
                            )
                            put("selected_revision_qualification", "NOT_QUALIFIED")
                        }
                        put("graph_available", false)
                        put(
                            "state",
                            if (privacyDenied) {
                                "POLICY_BLOCKED"
                            } else if (phoneDeadline) {
                                "INTERRUPTED"
                            } else if (phoneCooldown != null) {
                                "WAITING"
                            } else if (localRejection) {
                                "PRECHECK_REJECTED"
                            } else if (fallback?.isError == false) {
                                "DEGRADED"
                            } else {
                                "UNAVAILABLE"
                            }
                        )
                        put(
                            "recovery_kind",
                            if (unresolved) {
                                "RECONCILE_SAVED_INDEX"
                            } else if (privacyDenied) {
                                "REVIEW_PRIVACY_MODE"
                            } else if (rateLimitObservation != null || phoneCooldown != null || phoneRateLimit != null) {
                                "WAIT_FOR_REPORTED_RATE_LIMIT"
                            } else if (admission != null) {
                                "RESTORE_SELECTED_CONFIGURATION_OR_GRANTS"
                            } else if (phoneDeadline) {
                                "READ_SAVED_PHONE_STATUS"
                            } else if (localContextFailure != null) {
                                "READ_PINNED_SOURCE_INDEPENDENTLY"
                            } else if (phone) {
                                "RESTORE_PHONE_REPOSITORY_ACCESS"
                            } else if (unavailable) {
                                "RESTORE_SELECTED_GATEWAY_READINESS"
                            } else {
                                "INSPECT_GRAPH_REJECTION"
                            }
                        )
                        put("automatic_retry", false)
                        put(
                            "next_action",
                            if (unresolved) {
                                "The earlier index request remains unconfirmed. Wait for the reported retry delay if present, then read status on this same gateway and revision to reconcile its saved checkpoint; do not repeat indexing until the checkpoint is acknowledged."
                            } else if (privacyDenied) {
                                "Review Privacy Mode for the selected connection, then resume this repository map under the current settings. Completed reads remain recorded."
                            } else if (phoneDeadline) {
                                "The bounded phone read stopped. Completed source remains local. Read status on this same phone repository under normal authorization, then continue only the exact issued index_cursor; coverage is incomplete and no index replay was authorized."
                            } else if (localContextFailure != null) {
                                "Local task-context preparation failed after source admission. Retain the selected revision and use independently authorized github_search mode=paths, then github_read with exact commit_sha and ranges. Do not repeat the same failed bundle without a recovery change; native reads do not establish graph coverage or executed tests."
                            } else if (phoneCooldown != null) {
                                "Wait until the recorded GitHub cooldown deadline before another phone map read. This HTTP request was blocked locally; retained dispatch counts describe any earlier reads in this operation. Keep the selected GitHub connection and revision."
                            } else if (phoneRejection != null) {
                                GitHubFailurePolicy.recoveryHint(requireNotNull(phoneCode))
                            } else if (rateLimitObservation != null) {
                                "GitHub request capacity is temporarily unavailable. Wait for the reported retry delay, then refresh saved status on this gateway and revision before continuing. The observed upstream request does not establish the outcome of other graph work."
                            } else if (admission != null) {
                                admission.message.orEmpty()
                            } else if (phone) {
                                "Check the selected GitHub connection and phone source cache, then retry this revision. Uncached files require GitHub connectivity. Gateway is optional and is never selected automatically."
                            } else {
                                "Check the selected gateway host GitHub connection, durable storage, graph readiness and protocol. Refresh status after recovery. Do not substitute a different profile or treat native status as graph coverage."
                            }
                        )
                        repairAction?.let { put("repair_action", it) }
                        phoneCooldown?.failureFields()?.forEach { (key, value) -> put(key, value) }
                        phoneRateLimit?.forEach { (key, value) -> put(key, value) }
                        rateLimitObservation?.let { put("rate_limit", it) }
                        httpStatus?.takeIf { it in 100..599 }?.let { put("http_status", it) }
                        retryAfterMillis?.takeIf { it in 0..86_400_000 }?.let { put("retry_after_ms", it) }
                        if (unresolved) savedIndexRecovery(mapSource).forEach { (key, value) -> put(key, value) }
                        put("graph_complete", false)
                        if (phoneDeadline) {
                            put("local_work_stopped", true)
                            put("requires_reconciliation", false)
                            put(
                                "recovery_call",
                                buildJsonObject {
                                    put("tool", definition.name)
                                    put(
                                        "arguments",
                                        buildJsonObject {
                                            put("action", "status")
                                            put("map_source", "phone")
                                        }
                                    )
                                }
                            )
                        }
                        put(
                            "message",
                            if (phoneDeadline) {
                                "The foreground phone read reached its deadline. Completed immutable source and local progress remain available through an authorized status read; complete graph coverage was not observed."
                            } else if (unavailable && readAction) {
                                "Repository graph evidence is unavailable. Authorized native repository reads remain independent of the graph connection. Resolve the selected revision, inspect branches, commits, source paths, PRs and checks as needed; their receipts do not establish graph coverage or executed tests."
                            } else {
                                "Repository graph access failed. Resolve the reported admission, validation or transport problem for this operation; no graph write or policy rejection was retried through another provider."
                            }
                        )
                        put("failure_type", (localContextFailure?.cause ?: cause).javaClass.simpleName)
                        localContextFailure?.let {
                            put("graph_local_phase", it.phase.name)
                            put("graph_local_reason", it.reason.name)
                        }
                        if (readAction && (!phone && unavailable && nativeStatus != null || localContextFailure != null)) {
                            if (!unresolved) put("recovery_call", nativeCall("github_context", "status"))
                            put("native_read_alternatives", JsonArray(listOf("resolve", "branches", "commits", "checks").map { nativeCall("github_context", it) } + nativeCall("github_pr", "list")))
                            put("source_inspection", "After resolving the revision, use authorized github_search mode=paths with a concrete query, then github_read with exact ranges and commit_sha. Native source inspection does not supply graph callers/callees, complete graph coverage, or CI execution.")
                            put("native_authorization", "Every alternative still requires the current account, repository, privacy policy and tool budget. No additional tool is exposed by this response.")
                        }
                        fallback?.let { observed ->
                            put(
                                "native_fallback",
                                buildJsonObject {
                                    put("tool", nativeContextName)
                                    put("success", !observed.isError)
                                    put("dispatched", observed.dispatched)
                                    put("result", dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.element(observed.content))
                                    observed.errorCode?.let { put("error_code", it) }
                                    observed.executionReceipt?.let { put("execution_receipt", it) }
                                }
                            )
                        }
                    }
                ),
                true,
                errorCode = code,
                dispatched = !localRejection,
                executionReceipt = buildJsonObject {
                    put("schema", RECEIPT_SCHEMA)
                    put("call_id", callId)
                    put("action", requireNotNull(arguments["action"]))
                    put("map_source", mapSource)
                    put("error_code", code)
                    put("elapsed_ms", elapsedMillis)
                    operation?.targetRevision?.let { put("target_revision", it) }
                    (cause as? GatewayGraphHttpFailure)?.requestId?.let { put("gateway_http_request_id", it) }
                    put(
                        "phase",
                        if (localRejection) {
                            "PRECHECK_REJECTED"
                        } else if (currentIndexUnknown) {
                            "UNKNOWN_OUTCOME"
                        } else {
                            "FAILED"
                        }
                    )
                    put("failed_stage", if (localRejection) "tool_precheck" else "tool_execution")
                    put("dispatched", !localRejection)
                    put("retryable", false)
                    if (phoneDeadline) {
                        put("local_work_stopped", true)
                        put("replay_safe", false)
                        put("request_replay_allowed", false)
                        put("authorization_recheck_required", true)
                        put("reason_code", "READ_DEADLINE")
                    }
                    put("requires_reconciliation", unresolved)
                    put("index_outcome_unknown", unresolved)
                    put("current_index_outcome_unknown", currentIndexUnknown)
                    operation?.indexAttemptHash?.takeIf { it.matches(HASH) }?.let { put("graph_index_attempt_hash", it) }
                    repairAction?.let { put("repair_action", it) }
                    phoneCooldown?.failureFields()?.forEach { (key, value) -> put(key, value) }
                    phoneRateLimit?.forEach { (key, value) -> put(key, value) }
                    if (unresolved) put("recovery_action", "RECONCILE_OUTCOME")
                    httpStatus?.takeIf { it in 100..599 }?.let { put("http_status", it) }
                    retryAfterMillis?.takeIf { it in 0..86_400_000 }?.let { put("retry_after_ms", it) }
                    rateLimitObservation?.let { put("graph_rate_limit", it) }
                    (if (privacyDenied) code else phoneCode ?: prerequisite?.prerequisites?.firstOrNull()?.name ?: admission?.code ?: operation?.failureCode ?: (cause as? GatewayGraphHttpFailure)?.code)?.let { put("reason_code", it) }
                    if (privacyDenied) {
                        put("authority_invalidation_code", code)
                        put("authority_failed_stage", if (localRejection) "tool_precheck" else "tool_execution")
                        put("resume_required", true)
                    }
                    if (prerequisite?.prerequisites?.firstOrNull() == GatewayGraphPrerequisite.TRANSPORT_UNAVAILABLE) {
                        val ioCause = generateSequence<Throwable>(prerequisite) { it.cause }.take(16).filterIsInstance<IOException>().firstOrNull()
                        ioCause?.let(::networkFailureKind)?.takeUnless { it == NetworkFailureKind.CANCELLED }?.let {
                            put("graph_transport_kind", it.name)
                        }
                    }
                    ToolExceptionEvidence.fields(cause).forEach { (key, value) -> put(key, value) }
                    localContextFailure?.let {
                        put("graph_local_phase", it.phase.name)
                        put("graph_local_reason", it.reason.name)
                        dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule.exceptionFields(
                            it,
                            dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule.ExceptionCaptureSite.REPOSITORY_GRAPH_CATCH
                        ).forEach { (key, value) -> put(key, value) }
                    }
                }
            )
        }
        return if (phoneAccounting == null) result else phoneResult(phoneAccounting, result, arguments)
    }

    /** The rejected request cannot erase earlier reads or turn local indexing into a remote write. */
    private fun phoneResult(accounting: GitHubDispatchAccounting, result: AgentToolResult, arguments: JsonObject): AgentToolResult {
        val measured = accounting.finish(result.copy(dispatched = false), mutation = false)
        // A typed HTTP response remains observed even if a custom query adapter
        // does not participate in native per-request accounting. Do not attach
        // the unavailable callback counts as zero requests to that response.
        val observed = if (result.isError && (result.executionReceipt?.get("http_status") as? JsonPrimitive)?.intOrNull in 100..599 && !measured.dispatched) result else measured
        val privacyDenied = result.errorCode == UnverifiedServiceTransport.CODE
        val localDeadline = result.errorCode == "READ_DEADLINE" && result.executionReceipt?.get("local_work_stopped") == JsonPrimitive(true)
        val failedStage = if (observed.dispatched) "tool_execution" else "tool_precheck"
        val content = (result.content as? ToolResultContent.Json)?.value as? JsonObject
        return observed.copy(
            content = if (result.isError && content != null) {
                ToolResultContent.Json(
                    JsonObject(
                        content + when {
                            localDeadline -> mapOf("state" to JsonPrimitive("INTERRUPTED"))
                            content["state"] == JsonPrimitive("WAITING") -> emptyMap()
                            !observed.dispatched -> mapOf("state" to JsonPrimitive("PRECHECK_REJECTED"))
                            privacyDenied || content["state"] == JsonPrimitive("PRECHECK_REJECTED") -> mapOf("state" to JsonPrimitive("POLICY_BLOCKED"))
                            else -> emptyMap()
                        }
                    )
                )
            } else {
                result.content
            },
            executionReceipt = buildJsonObject {
                result.executionReceipt?.forEach { (key, value) -> put(key, value) }
                observed.executionReceipt?.forEach { (key, value) -> put(key, value) }
                put("schema", RECEIPT_SCHEMA)
                put("call_id", result.callId)
                put("action", requireNotNull(arguments["action"]))
                put("map_source", "phone")
                if (repositoryScope != null && !accountIdentity.isNullOrBlank()) {
                    put("scope_hash", dev.chungjungsoo.gptmobile.data.agent.PhoneIndexInterruption.scopeHash(repositoryScope, accountIdentity))
                    put("source_head_sha", repositoryScope.commitSha)
                }
                put(
                    "phase",
                    if (!result.isError) {
                        "COMPLETED"
                    } else if (localDeadline) {
                        "CANCELLED_LOCALLY"
                    } else if (observed.dispatched) {
                        "FAILED"
                    } else {
                        "PRECHECK_REJECTED"
                    }
                )
                put("dispatched", observed.dispatched)
                if (result.isError) put("failed_stage", failedStage)
                if (localDeadline) put("failed_stage", "phone_read_deadline")
                put("requires_reconciliation", false)
                put("index_outcome_unknown", false)
                put("current_index_outcome_unknown", false)
                if (privacyDenied) put("authority_failed_stage", failedStage)
            }
        )
    }

    /** Match native GitHub's bounded response evidence; neither prose nor a later quota snapshot supplies wait authority. */
    private fun phoneRateLimitEvidence(failure: GitHubRequestRejected, code: String): JsonObject? {
        val evidence = failure.evidence ?: return null
        if (failure.status !in setOf(403, 429) || !GitHubFailurePolicy.isRateLimited(code) || evidence["code"] != JsonPrimitive(code) || evidence["http_status"] != JsonPrimitive(failure.status)) return null
        return buildJsonObject {
            for ((key, allowed) in listOf(
                "github_cooldown_resource" to GitHubCooldownActive.Resource.entries.map { it.name },
                "github_cooldown_basis" to GitHubCooldownActive.Basis.entries.map { it.name }
            )) {
                (evidence[key] as? JsonPrimitive)?.takeIf { it.isString && it.content in allowed }?.let { put(key, it) }
            }
            for (key in listOf("github_cooldown_delay_ms", "github_cooldown_deadline_ms", "github_cooldown_observed_at_ms", "github_cooldown_remaining_requests", "retry_after_ms")) {
                val limit = when (key) {
                    "github_cooldown_remaining_requests" -> 16_000_000L
                    "retry_after_ms" -> 86_400_000L
                    else -> Long.MAX_VALUE
                }
                (evidence[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..limit }?.let { put(key, it) }
            }
        }
    }

    private fun JsonObject.hash(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.matches(HASH) }

    private fun nativeCall(operation: String, action: String) = buildJsonObject {
        put("tool", operation + nativeContextName.removePrefix("github_context"))
        put("arguments", buildJsonObject { put("action", action) })
    }

    private fun savedIndexRecovery(mapSource: kotlinx.serialization.json.JsonElement) = buildJsonObject {
        put("index_outcome_unknown", true)
        put("requires_reconciliation", true)
        put("repair_action", "CHECK_SAVED_INDEX")
        put(
            "recovery_call",
            buildJsonObject {
                put("tool", definition.name)
                put(
                    "arguments",
                    buildJsonObject {
                        put("action", "status")
                        put("map_source", mapSource)
                    }
                )
            }
        )
    }

    companion object {
        val schema = Json.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"required":["action"],"properties":{"action":{"type":"string","enum":["status","index","task_context_bundle","query","node","callers","callees","path","impact","architecture","diff","diagnose","contracts","prs","note","notes","requirements","wiki","metrics","federated","provider"]},"map_source":{"type":"string","enum":["phone","gateway"],"default":"phone"},"query":{"type":"string","maxLength":2000},"symbol":{"type":"string","maxLength":2000},"options":{"type":"object"}}}"""
        ) as JsonObject

        const val RECEIPT_SCHEMA = "gptmobile.repository-graph.v1"
        private val HASH = Regex("[a-f0-9]{64}")
    }
}
