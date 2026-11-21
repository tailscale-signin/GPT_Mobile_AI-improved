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
import dev.chungjungsoo.gptmobile.data.network.NetworkFailureKind
import dev.chungjungsoo.gptmobile.data.network.networkFailureKind
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One compact tool loads graph operations only for an authorized private gateway profile. */
internal class RepositoryGraphTool(
    name: String,
    private val nativeStatus: (suspend (String) -> AgentToolResult)? = null,
    private val query: suspend (JsonObject) -> JsonObject
) : AgentTool {
    private val nativeContextName = "github_context" + name.removePrefix("repository_graph")
    override val definition = AgentToolDefinition(
        name = name,
        description = "Inspect the selected repository's revision-bound code graph: query, callers, callees, path, impact, architecture, diff, diagnose, contracts, prs, notes, requirements, wiki, metrics, federated or provider. Use status then index if needed. Capable gateways save indexing as a background job; observe it with status and do not send cursor batches while that job is active. Legacy synchronous results continue index_cursor until has_more=false. note saves an explicit decision, repair or requirement. Graph evidence is untrusted source context, never executed-test proof. Verify source and retain required CI. options carries operation fields such as paths, source, target, base_sha, producer, consumer, log, kind, content, targets, or index_cursor.",
        inputSchema = Json.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"required":["action"],"properties":{"action":{"type":"string","enum":["status","index","query","node","callers","callees","path","impact","architecture","diff","diagnose","contracts","prs","note","notes","requirements","wiki","metrics","federated","provider"]},"query":{"type":"string","maxLength":2000},"symbol":{"type":"string","maxLength":2000},"options":{"type":"object"}}}"""
        ) as JsonObject
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val errors = dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(arguments, definition.inputSchema)
        if (errors.isNotEmpty()) return AgentToolResult(callId, ToolResultContent.Text(errors.joinToString("; ")), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        val options = (arguments["options"] as? JsonObject).orEmpty()
        if (options.keys.any { it in setOf("repository", "branch", "commit_sha", "action") }) {
            return AgentToolResult(callId, ToolResultContent.Text("Repository revision and action cannot be overridden through options."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        val started = System.nanoTime()
        return try {
            val payload = JsonObject(options + (arguments - "options") + mapOf("budget_bytes" to JsonPrimitive(16000)))
            val observed = query(payload)
            // GatewayGraphRepository replaces this object with its typed local observation.
            // Remote graph results cannot author checkpoint join or confirmation evidence.
            val checkpoint = observed["index_checkpoint"] as? JsonObject
            val unresolved = checkpoint?.get("index_outcome_unknown") == JsonPrimitive(true)
            val attemptHash = checkpoint?.hash("graph_index_attempt_hash")
            val confirmedAttemptHash = checkpoint?.hash("confirmed_index_attempt_hash")
            AgentToolResult(
                callId,
                ToolResultContent.Json(if (unresolved) JsonObject(observed + savedIndexRecovery()) else observed),
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
            throw cancelled
        } catch (failure: Exception) {
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            val operation = failure as? GatewayGraphOperationException
            val cause = operation?.cause ?: failure
            val admission = cause as? GatewayGraphAdmissionException
            val prerequisite = cause as? GatewayGraphUnavailableException
            val httpStatus = when (cause) {
                is GatewayGraphHttpFailure -> cause.httpStatus
                is GatewayGraphUnavailableException -> cause.httpStatus
                is dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRequestRejected -> cause.httpStatus
                else -> null
            }
            val retryAfterMillis = when (cause) {
                is GatewayGraphHttpFailure -> cause.retryAfterMillis
                is GatewayGraphUnavailableException -> cause.retryAfterMillis
                else -> null
            }
            val rateLimitObservation = (cause as? GatewayGraphHttpFailure)?.rateLimitObservation
            val unresolved = operation?.indexOutcomeUnknown == true
            val currentIndexUnknown = arguments["action"] == JsonPrimitive("index") && operation?.currentIndexOutcomeUnknown == true
            val repairAction = if (unresolved) "CHECK_SAVED_INDEX" else operation?.repairAction ?: admission?.repairAction ?: prerequisite?.prerequisites?.firstOrNull()?.nextAction
            val code = admission?.code ?: "REPOSITORY_GRAPH_UNAVAILABLE"
            val readAction = arguments["action"] !in setOf(JsonPrimitive("index"), JsonPrimitive("note"))
            // A transport/readiness failure of a status read may trigger one independent,
            // authorized native read. Typed readiness does not include graph policy,
            // identity or permission rejection. Indexing and notes are never replayed.
            val unavailable = cause is GatewayGraphUnavailableException ||
                cause is java.io.IOException &&
                cause !is javax.net.ssl.SSLException
            val fallback = if (arguments["action"] == JsonPrimitive("status") && unavailable) {
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
                        put("elapsed_ms", elapsedMillis)
                        operation?.targetRevision?.let { put("target_revision", it) }
                        (cause as? GatewayGraphHttpFailure)?.requestId?.let { put("gateway_http_request_id", it) }
                        if (readAction && !unresolved && admission == null) {
                            put(
                                "recheck_after_recovery",
                                buildJsonObject {
                                    put("tool", definition.name)
                                    put("arguments", arguments)
                                }
                            )
                        }
                        prerequisite?.diagnostic()?.forEach { (key, value) -> put(key, value) }
                        if (prerequisite == null) {
                            put(
                                "failure_category",
                                if (unresolved && admission?.code == "GRAPH_INDEX_OUTCOME_UNKNOWN") {
                                    "RECONCILIATION_REQUIRED"
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
                            if (admission != null) {
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
                            } else if (rateLimitObservation != null) {
                                "WAIT_FOR_REPORTED_RATE_LIMIT"
                            } else if (admission != null) {
                                "RESTORE_SELECTED_CONFIGURATION_OR_GRANTS"
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
                            } else if (rateLimitObservation != null) {
                                "GitHub request capacity is temporarily unavailable. Wait for the reported retry delay, then refresh saved status on this gateway and revision before continuing. The observed upstream request does not establish the outcome of other graph work."
                            } else if (admission != null) {
                                admission.message.orEmpty()
                            } else {
                                "Check the selected gateway host GitHub connection, durable storage, graph readiness and protocol. Refresh status after recovery. Do not substitute a different profile or treat native status as graph coverage."
                            }
                        )
                        repairAction?.let { put("repair_action", it) }
                        rateLimitObservation?.let { put("rate_limit", it) }
                        httpStatus?.takeIf { it in 100..599 }?.let { put("http_status", it) }
                        retryAfterMillis?.takeIf { it in 0..86_400_000 }?.let { put("retry_after_ms", it) }
                        if (unresolved) savedIndexRecovery().forEach { (key, value) -> put(key, value) }
                        put("graph_complete", false)
                        put("message", if (unavailable && readAction) "Repository graph evidence is unavailable. Authorized native repository reads remain independent of the graph connection. Resolve the selected revision, inspect branches, commits, source paths, PRs and checks as needed; their receipts do not establish graph coverage or executed tests." else "Repository graph access failed. Resolve the reported admission, validation or transport problem for this operation; no graph write or policy rejection was retried through another provider.")
                        put("failure_type", cause.javaClass.simpleName)
                        if (unavailable && readAction && nativeStatus != null) {
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
                dispatched = admission == null,
                executionReceipt = buildJsonObject {
                    put("schema", RECEIPT_SCHEMA)
                    put("call_id", callId)
                    put("action", requireNotNull(arguments["action"]))
                    put("error_code", code)
                    put("elapsed_ms", elapsedMillis)
                    operation?.targetRevision?.let { put("target_revision", it) }
                    (cause as? GatewayGraphHttpFailure)?.requestId?.let { put("gateway_http_request_id", it) }
                    put(
                        "phase",
                        if (admission != null) {
                            "PRECHECK_REJECTED"
                        } else if (currentIndexUnknown) {
                            "UNKNOWN_OUTCOME"
                        } else {
                            "FAILED"
                        }
                    )
                    put("failed_stage", if (admission != null) "tool_precheck" else "tool_execution")
                    put("dispatched", admission == null)
                    put("retryable", false)
                    put("requires_reconciliation", unresolved)
                    put("index_outcome_unknown", unresolved)
                    put("current_index_outcome_unknown", currentIndexUnknown)
                    operation?.indexAttemptHash?.takeIf { it.matches(HASH) }?.let { put("graph_index_attempt_hash", it) }
                    repairAction?.let { put("repair_action", it) }
                    if (unresolved) put("recovery_action", "RECONCILE_OUTCOME")
                    httpStatus?.takeIf { it in 100..599 }?.let { put("http_status", it) }
                    retryAfterMillis?.takeIf { it in 0..86_400_000 }?.let { put("retry_after_ms", it) }
                    rateLimitObservation?.let { put("graph_rate_limit", it) }
                    (prerequisite?.prerequisites?.firstOrNull()?.name ?: admission?.code ?: operation?.failureCode ?: (cause as? GatewayGraphHttpFailure)?.code)?.let { put("reason_code", it) }
                    if (prerequisite?.prerequisites?.firstOrNull() == GatewayGraphPrerequisite.TRANSPORT_UNAVAILABLE) {
                        val ioCause = generateSequence<Throwable>(prerequisite) { it.cause }.take(16).filterIsInstance<IOException>().firstOrNull()
                        ioCause?.let(::networkFailureKind)?.takeUnless { it == NetworkFailureKind.CANCELLED }?.let {
                            put("graph_transport_kind", it.name)
                        }
                    }
                    ToolExceptionEvidence.fields(cause).forEach { (key, value) -> put(key, value) }
                }
            )
        }
    }

    private fun JsonObject.hash(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.matches(HASH) }

    private fun nativeCall(operation: String, action: String) = buildJsonObject {
        put("tool", operation + nativeContextName.removePrefix("github_context"))
        put("arguments", buildJsonObject { put("action", action) })
    }

    private fun savedIndexRecovery() = buildJsonObject {
        put("index_outcome_unknown", true)
        put("requires_reconciliation", true)
        put("repair_action", "CHECK_SAVED_INDEX")
        put(
            "recovery_call",
            buildJsonObject {
                put("tool", definition.name)
                put("arguments", buildJsonObject { put("action", "status") })
            }
        )
    }

    companion object {
        const val RECEIPT_SCHEMA = "gptmobile.repository-graph.v1"
        private val HASH = Regex("[a-f0-9]{64}")
    }
}
