package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.classifyDiagnostic
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticCorrelation
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticEventMessage
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticExecutionEvidence
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticFields
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticText
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticToolIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Typed local events and legacy text cross the same allowlist; neither sends free-form messages. */
internal fun gatewayAppDiagnosticProjection(entry: AppLogEntry): JsonObject? {
    val message = diagnosticEventMessage(entry.message)
    val eventName = message.substringBefore(" · ")
    if (entry.tag == "Gateway" && eventName == "CLIENT_EXCHANGE") return null
    val classification = classifyDiagnostic(entry.level, entry.tag, entry.message)
    val fields = diagnosticFields(entry.message)
    val execution = diagnosticExecutionEvidence(fields, eventName)
    fun text(vararg names: String): String? = names.firstNotNullOfOrNull { (fields[it] as? JsonPrimitive)?.contentOrNull?.takeUnless { value -> value in setOf("null", "unknown", "none", "unavailable") } }
    val terminal = text("terminalStatus", "status").takeIf { eventName == "RUN_TERMINAL" }
    val criterion = text("criterion_state").takeIf { eventName == "GITHUB_ACCEPTANCE_CRITERION" }
    val requiredCriterion = criterion != null && (fields["criterion_required"] as? JsonPrimitive)?.booleanOrNull == true
    val receipt = fields["executionReceipt"] as? JsonObject
    val reason = text("reasonCode", "reason_code") ?: receipt?.diagnosticText("reason_code")
        ?: execution.errorCode ?: text("failureCode", "failure_code")
    val tool = diagnosticToolIdentity(fields, message, entry.tag)
    val toolPrecheck = tool.callId != null && execution.state == "PRECHECK_REJECTED"
    val toolResult = tool.callId != null && (eventName == "Finished" || toolPrecheck)
    val transportEvidence = receipt?.get("execution") as? JsonObject
    fun count(key: String) = (transportEvidence?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L
    val origin = when {
        toolPrecheck -> "precheck_rejected"
        reason == "DUPLICATE_INVOCATION" && execution.dispatched == false -> "duplicate_suppressed"
        receipt?.diagnosticText("evidence_origin") == "MOCKED" -> "mocked"
        count("read_requests") > 0 || count("mutation_requests") > 0 -> "live_remote"
        count("cached_read_observations") > 0 || receipt?.diagnosticText("evidence_origin") == "CACHED" -> "cached"
        receipt?.diagnosticText("evidence_origin") == "LOCAL" -> "local"
        else -> "unobserved"
    }
    val correlation = diagnosticCorrelation(fields, message, entry.tag)
    val mcpPhase = when (text("mcpPhase", "stage", "failedStage")?.lowercase()) {
        "connection", "initialization", "protocol_negotiation", "dns", "tls" -> "connection"
        "discovery" -> "discovery"
        "execution" -> "execution"
        else -> if (eventName.contains("DISCOVERY") || eventName.contains("CATALOG")) "discovery" else "unknown"
    }
    val frame = if (eventName == "FRAME_TIMING") GatewayFrameDiagnostic.from(fields) else null
    val stage = when {
        eventName in setOf("RUN_TERMINAL", "GITHUB_ACCEPTANCE_CRITERION", "GITHUB_VERDICT_EVIDENCE") -> "terminal_delivery"
        entry.tag in setOf("Lifecycle", "Application", "AppCrash", "Diagnostics", "ProcessExit") -> "lifecycle"
        entry.tag in setOf("Frame", "Rendering", "ChatRendering") -> "renderer"
        entry.tag in setOf("Network", "Connectivity") -> "network"
        entry.tag in setOf("MCP", "Mcp", "McpRuntime") -> if (mcpPhase == "discovery") "mcp_discovery" else "mcp_call"
        entry.tag == "GatewayAvailability" -> "request"
        entry.tag in setOf("Model", "LocalConnection", "Admission") -> "request"
        entry.tag in setOf("Agent", "AgentRun", "Tool", "ToolExecution", "GitHub", "GitHubTool") -> "operation_dispatch"
        classification.severity in setOf("error", "critical", "warning") -> "unknown"
        else -> return null
    }
    val error = when {
        execution.state == "UNKNOWN_OUTCOME" -> "DISPATCH_UNCERTAIN"
        execution.dispatched == false && classification.actionable -> "PRECHECK_REJECTED"
        reason == "TRANSPORT_EOF" -> "TRANSPORT_EOF"
        reason == "COMPLETION_REPAIR_BUDGET_EXHAUSTED" -> "COMPLETION_REPAIR_BUDGET_EXHAUSTED"
        reason in setOf("INCOMPLETE", "TASK_INCOMPLETE", "GITHUB_TASK_INCOMPLETE") -> "TASK_INCOMPLETE"
        classification.category in setOf("fatal", "memory_recovery") && message.contains("memory", true) -> "JAVA_HEAP_PRESSURE"
        classification.category in setOf("transport_availability", "network_callback_lifecycle") -> "TRANSPORT_FAILURE"
        frame != null -> frame.errorCode
        stage in setOf("mcp_call", "mcp_discovery") && classification.actionable -> when (mcpPhase) {
            "discovery" -> "MCP_DISCOVERY_FAILED"
            "execution" -> "MCP_EXECUTION_FAILED"
            else -> "MCP_UNAVAILABLE"
        }
        terminal == "EVIDENCE_MISSING" -> "EVIDENCE_MISSING"
        terminal == "BLOCKED" -> "TASK_BLOCKED"
        terminal == "PARTIAL" -> "TASK_PARTIAL"
        requiredCriterion && criterion == "BLOCKED" -> "TASK_BLOCKED"
        requiredCriterion && criterion in setOf("FAIL", "NOT_TESTED") -> "EVIDENCE_MISSING"
        classification.severity in setOf("error", "critical") -> "UNKNOWN_CAUSE"
        else -> "NONE"
    }
    return GatewayDiagnosticEvent.fields(
        buildJsonObject {
            put("stage", stage)
            put("direction", "local")
            put("owner", "android")
            put("severity", classification.severity)
            put("error_code", error)
            put(
                "dispatch_state",
                if (execution.dispatched == false) {
                    "not_dispatched"
                } else if (execution.dispatched == true) {
                    "dispatched"
                } else {
                    "unknown"
                }
            )
            put(
                "retry_class",
                if (error == "NONE" && !classification.actionable) {
                    "none"
                } else if ((fields["retryable"] as? JsonPrimitive)?.booleanOrNull == false) {
                    "manual"
                } else {
                    "unknown"
                }
            )
            put(
                "category",
                when (stage) {
                    "renderer" -> "renderer"
                    "network" -> "network"
                    "mcp_call", "mcp_discovery", "operation_dispatch", "terminal_delivery" -> "tool"
                    "lifecycle" -> "lifecycle"
                    "request" -> "model"
                    else -> "unknown"
                }
            )
            val identifiers = mapOf(
                "run_id" to correlation.runId,
                "incident_id" to (text("causalIncidentId", "incidentId", "incident_id") ?: correlation.invocationId),
                "job_id" to text("jobId", "job_id", "remoteJobId"),
                "request_id" to text("requestDigest", "requestId", "request_id"),
                "operation_id" to text("operationId", "operation_id", "actionId"),
                "tool_call_id" to tool.callId,
                "binding_id" to text("bindingId", "binding_id")
            )
            identifiers.forEach { (name, value) ->
                value?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) }?.let {
                    put(name, if (it.matches(Regex("[a-f0-9]{32,64}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}|[a-z]{2,16}_[a-f0-9]{32,64}"))) it else GatewayDiagnosticEvent.digest("$name:$it"))
                }
            }
            listOf("trace_id" to "traceId", "span_id" to "spanId", "parent_span_id" to "parentSpanId").forEach { (name, alias) -> text(name, alias)?.let { put(name, it) } }
            text("elapsedMs", "durationMs", "local_duration_ms")?.toLongOrNull()?.let { put("local_duration_ms", it) }
            put(
                "bounded_attributes",
                buildJsonObject {
                    put("occurrence_count", 1)
                    put("source_event_digest", GatewayDiagnosticEvent.digest(entry.eventId))
                    put("vendor_log_available", false)
                    put("app_version_code", BuildConfig.VERSION_CODE)
                    put("app_revision", BuildConfig.APP_COMMIT)
                    put("android_sdk", android.os.Build.VERSION.SDK_INT)
                    // Every value still passes the shared strict allowlist below.
                    GatewayDiagnosticSchema.enumAttributes.keys.forEach { name -> fields[name]?.let { put(name, it) } }
                    GatewayDiagnosticSchema.numericAttributes.forEach { name -> fields[name]?.let { put(name, it) } }
                    GatewayDiagnosticSchema.digestAttributes.forEach { name -> fields[name]?.let { put(name, it) } }
                    GatewayDiagnosticSchema.boolAttributes.forEach { name -> fields[name]?.let { put(name, it) } }
                    val sourceEvent = when {
                        toolPrecheck -> "tool_precheck"
                        toolResult -> "tool_result"
                        frame != null -> "frame_timing"
                        terminal != null -> "run_terminal"
                        criterion != null -> "acceptance_criterion"
                        eventName == "GITHUB_VERDICT_EVIDENCE" -> "verdict_evidence"
                        entry.tag == "GatewayAvailability" -> "gateway_availability"
                        stage == "mcp_discovery" -> "mcp_discovery"
                        classification.severity == "warning" && stage == "unknown" && reason == null -> "unclassified_warning"
                        classification.severity == "warning" -> "typed_warning"
                        else -> null
                    }
                    sourceEvent?.let { put("source_event_type", it) }
                    if (toolResult) {
                        put("execution_origin", origin)
                        tool.name?.let { put("tool_digest", GatewayDiagnosticEvent.digest(it)) }
                        tool.action?.let { put("tool_action_digest", GatewayDiagnosticEvent.digest(it)) }
                        text("argumentShapeHash")?.let { put("argument_shape_digest", it) }
                    }
                    reason?.let { put("reason_code", it) }
                    if (stage in setOf("mcp_call", "mcp_discovery")) {
                        text("declaredRpcErrorCode")?.takeIf { it in GatewayDiagnosticSchema.enumAttributes.getValue("mcp_rpc_error_code") }?.let { put("mcp_rpc_error_code", it) }
                    }
                    if ("dependency" !in fields) gatewayDiagnosticDependency(reason)?.let { put("dependency", it) }
                    execution.stage?.lowercase()?.let { put("failed_stage", it) }
                    terminal?.let { put("terminal_status", it) }
                    (text("completionDisposition", "completion_disposition") ?: execution.state?.takeIf { it in GatewayDiagnosticSchema.enumAttributes.getValue("completion_disposition") } ?: terminal)?.let { put("completion_disposition", it) }
                    text("lastCompletedStage", "last_completed_stage")?.let { put("last_completed_stage", it) }
                    text("lastObservedStage", "last_observed_stage")?.let { put("last_observed_stage", it) }
                    put(
                        "dispatch_scope",
                        when (text("dispatchEvidenceScope")) {
                            "MODEL_INVOCATION", "GATEWAY_JOB_SUBMISSION", "BLOCKED_ACTION" -> "blocked_action"
                            "RUN_FAILURE", "RUN" -> "run"
                            else -> "unknown"
                        }
                    )
                    val recovery = when {
                        error == "NONE" && !classification.actionable -> "none"
                        requiredCriterion && criterion != "PASS" -> "verify_acceptance"
                        text("recoveryAction", "recovery_action") == "RESUME_SAVED_EVIDENCE_WITH_REVISED_OUTPUT_ALLOWANCE" -> "resume_checkpoint"
                        else -> text("recoveryAction", "recovery_action")?.takeIf { it in GatewayDiagnosticSchema.enumAttributes.getValue("recovery_action") }
                            ?: gatewayDiagnosticRecovery(reason, terminal, execution.state)
                    }
                    put("recovery_action", recovery)
                    put("recovery_condition", gatewayRecoveryCondition(recovery))
                    (fields["retryable"] as? JsonPrimitive)?.booleanOrNull?.let { put("retryable", it) }
                    if (eventName == "RUN_TERMINAL") {
                        put("run_state", "terminal")
                        val required = text("requiredCriteria", "required_criteria")?.toLongOrNull()
                        val verified = text("verifiedCriteria", "verified_criteria")?.toLongOrNull()
                        required?.let { put("required_criteria", it) }
                        verified?.let { put("verified_criteria", it) }
                        if (required != null && verified != null && verified in 0..required) {
                            put("remaining_criteria", required - verified)
                            put("acceptance_verified", required > 0 && required == verified && text("taskVerification") == "VERIFIED")
                        }
                    }
                    if (stage in setOf("mcp_call", "mcp_discovery")) {
                        put("mcp_phase", mcpPhase)
                        put("target_class", "mcp")
                        text("connection", "connectionUid", "serverId")?.let { put("server_digest", GatewayDiagnosticEvent.digest(it)) }
                        text("tool", "toolName")?.let { put("tool_digest", GatewayDiagnosticEvent.digest(it)) }
                    }
                    text("checkpointId", "checkpoint")?.let { put("checkpoint_digest", GatewayDiagnosticEvent.digest(it)) }
                    frame?.attributes()?.forEach { (name, value) -> put(name, value) }
                }
            )
        }
    )
}
