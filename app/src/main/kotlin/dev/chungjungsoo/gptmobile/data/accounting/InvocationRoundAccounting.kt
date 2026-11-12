package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ProviderUsageCounter
import dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.research.ResearchStageTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString

/** Invocation-local state keeps stream event accounting out of the collecting coroutine. */
internal class InvocationRoundAccounting(
    private val record: ModelInvocation,
    private val price: ModelPrice?,
    private val dispatch: RequestDispatchContext,
    private val combinedAccounting: CombinedInvocationAccounting?,
    private val dao: InvocationDao,
    private val retainAccounting: Boolean,
    private val liveRequests: MutableStateFlow<Map<String, ModelInvocation>>,
    private val researchTrace: ResearchStageTrace?,
    private val turnKey: String
) {
    private val parentRunId = record.parentRunId
    private val kind = record.kind
    private val outputLimit = record.outputTokens
    val started = System.nanoTime()
    var first: Long? = null
    var input: Int? = null
    var output: Int? = null
    var inputSource = "estimated"
    var outputSource = "estimated"
    var actualRuntime: String? = null
    var actualAccelerator: String? = null
    var serverRequestId: String? = null
    var serializedOutputCap: Int? = null
    var localContextTokens: Long? = null
    var reasoningState: String? = null
    var generatedBytes = 0L
    val visibleFingerprint = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.takeIf { it.enabled }?.let { dev.chungjungsoo.gptmobile.data.diagnostics.OutputFingerprintAccumulator() }
    var gatewayTiming: dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayGenerationTiming? = null
    var requestSettings: kotlinx.serialization.json.JsonObject? = null
    var gatewayProvenance: kotlinx.serialization.json.JsonObject? = null
    var gatewayDelivery: kotlinx.serialization.json.JsonObject? = null
    var responseParsing: kotlinx.serialization.json.JsonObject? = null
    var firstReasoningRecorded = false
    var firstToolRecorded = false
    var toolCallCount = 0
    var visibleBytes = 0L
    var reasoningBytes = 0L
    var hasVisibleAnswer = false
    var completedWithoutAnswer = false
    private var providerGenerationCompleted = false
    val inputCounter = ProviderUsageCounter()
    val outputCounter = ProviderUsageCounter()
    private val recoveryAttempts = linkedMapOf<String, GatewayRecoveryUsage>()
    private val recoveryAttemptEvidence = linkedMapOf<String, kotlinx.serialization.json.JsonObject>()
    private val savedJobAttemptEvidence = linkedMapOf<String, kotlinx.serialization.json.JsonObject>()
    private var savedResultJobId: String? = null
    private fun recoveryEvidence() = GatewayRecoveryEvidence.combine(recoveryAttemptEvidence, savedJobAttemptEvidence)
    private fun observedRecoveryExecution() = recoveryAttempts.isNotEmpty() || recoveryAttemptEvidence.values.any(GatewayRecoveryEvidence::observedBackendExecution)
    private fun recordRecoveryEvidence(evidence: kotlinx.serialization.json.JsonObject) {
        val savedJob = savedResultJobId
        if (savedJob != null) {
            savedJobAttemptEvidence[savedJob] = evidence
        } else {
            val physical = dispatch.receipts.lastOrNull()?.physicalAttemptId ?: record.id
            recoveryAttemptEvidence[physical] = evidence
        }
        finishReason = GatewayRecoveryEvidence.finishReason(evidence) ?: finishReason
    }

    private fun observedRemoteAcceptance(): String? = when {
        observedRecoveryExecution() -> "YES"
        // HTTP 200 proves gateway HTTP acceptance, not a completed backend generation.
        recoveryAttemptEvidence.isNotEmpty() -> "UNKNOWN"
        else -> (responseParsing?.get("remote_acceptance") as? kotlinx.serialization.json.JsonPrimitive)?.content
    }
    private fun recoveryUsage(): GatewayRecoveryUsage? {
        val physical = dispatch.receipts.mapNotNull { it.physicalAttemptId }.toSet() - dispatch.provenUnsentAttemptIds()
        return GatewayRecoveryUsage.combine(recoveryAttempts, physical.isEmpty() || recoveryAttempts.keys.containsAll(physical))
    }
    private fun applyRecoveryUsage() {
        val usage = recoveryUsage()?.event(record.id) ?: return
        inputCounter.accept(usage.inputTokens, true, usage.inputSource, usage.phase, authoritativeReceipt = true)
        outputCounter.accept(usage.outputTokens, true, usage.outputSource, usage.phase, authoritativeReceipt = true)
        input = inputCounter.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        output = outputCounter.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        inputSource = inputCounter.source
        outputSource = outputCounter.source
    }
    fun recoveredUsageEvent(): ProviderEvent.Usage? = recoveryUsage()?.event(record.id)
    fun retainedInput(): Int = if (measuredUsageSource(inputSource)) input ?: record.inputTokens else maxOf(input ?: 0, record.inputTokens)
    fun retainedOutput(): Int = if (measuredUsageSource(outputSource)) output ?: 0 else maxOf(output ?: 0, ((generatedBytes + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    fun retainedInputSource(): String = if (inputSource == "not_dispatched" || input != null && retainedInput() == input) inputSource else "estimated"
    fun retainedOutputSource(): String = if (outputSource == "not_dispatched" || output != null && retainedOutput() == output) outputSource else "estimated"
    fun estimatedUsage(): Boolean = input == null || output == null || !measuredUsageSource(inputSource) || !measuredUsageSource(outputSource) || recoveryUsage()?.let { !it.inputComplete || !it.outputComplete } == true
    fun usageEvidence(costConfirmed: Boolean): String = kotlinx.serialization.json.buildJsonObject {
        put("invocation_id", kotlinx.serialization.json.JsonPrimitive(record.id))
        put("input_reservation", kotlinx.serialization.json.JsonPrimitive(record.inputTokens))
        put("output_reservation", kotlinx.serialization.json.JsonPrimitive(record.outputTokens))
        put("input", inputCounter.evidence())
        put("output", outputCounter.evidence())
        put("final_usage_complete", kotlinx.serialization.json.JsonPrimitive(!estimatedUsage()))
        put("cost_confirmed", kotlinx.serialization.json.JsonPrimitive(costConfirmed))
        dispatch.preflightRejection?.let { rejection ->
            put(
                "preflight_rejection",
                kotlinx.serialization.json.buildJsonObject {
                    put("reason", kotlinx.serialization.json.JsonPrimitive(rejection.reason.name))
                    put("phase", kotlinx.serialization.json.JsonPrimitive(rejection.phase))
                    put("attempt_dispatch", kotlinx.serialization.json.JsonPrimitive(rejection.dispatch))
                }
            )
        }
        val serializedSettings = dispatch.serializedRequestSettings
        (dispatch.latestSerializedRequestSettings?.get("settings") ?: requestSettings)?.let { put("request_settings", it) }
        if (serializedSettings.isNotEmpty()) put("serialized_requests", kotlinx.serialization.json.JsonArray(serializedSettings))
        if (dispatch.omittedSerializedRequestSettings > 0) {
            put("serialized_requests_omitted", kotlinx.serialization.json.JsonPrimitive(dispatch.omittedSerializedRequestSettings))
            dispatch.latestSerializedRequestSettings?.let { put("latest_serialized_request", it) }
        }
        gatewayProvenance?.let { put("gateway_provenance", it) }
        gatewayDelivery?.let { put("gateway_delivery", it) }
        responseParsing?.let { put("response_parsing", it) }
        recoveryUsage()?.let { recovered ->
            put("gateway_recovery", recovered.evidence)
            recovered.inputTokens?.let { put("known_recovery_input_tokens", kotlinx.serialization.json.JsonPrimitive(it)) }
            recovered.outputTokens?.let { put("known_recovery_output_tokens", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("recovery_usage_complete", kotlinx.serialization.json.JsonPrimitive(recovered.inputComplete && recovered.outputComplete))
        }
        recoveryEvidence()?.let { put("gateway_attempt_evidence", it) }
        diagnosticFailure?.let { put("failure", kotlinx.serialization.json.Json.encodeToJsonElement(dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.serializer(), it)) }
        gatewayTiming?.observedFields()?.takeIf { it.isNotEmpty() }?.let { timings ->
            put(
                "gateway_timing",
                kotlinx.serialization.json.buildJsonObject {
                    put("measurement_scope", kotlinx.serialization.json.JsonPrimitive("physical_backend_request"))
                    gatewayTiming?.deliveryMode?.takeIf { it in setOf("completion_buffered", "incremental_final_answer") }?.let { put("delivery_mode", kotlinx.serialization.json.JsonPrimitive(it)) }
                    timings.forEach { (name, value) -> put(name, kotlinx.serialization.json.JsonPrimitive(value)) }
                    // UI publication is measured by the run journal, never inferred from backend output.
                    put("first_user_visible_text_ms", kotlinx.serialization.json.JsonNull)
                }
            )
        }
    }.toString()
    var providerReasoningTokens: Int? = null
    var finishReason: String? = null
    var settingsConfirmed = false
    var status = "INTERRUPTED"
    private var diagnosticFailure: dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord? = null
    var lastPublished = started
    init {
        liveRequests.update { it + (record.id to record.copy(outputTokens = 0, usageEvidenceJson = usageEvidence(false))) }
    }

    fun preservePreflightFailure(event: ProviderEvent): ProviderEvent {
        if (event !is ProviderEvent.Failed) return event
        val correlated = event.copy(
            failureDetails = kotlinx.serialization.json.JsonObject(
                event.failureDetails.orEmpty() + mapOf(
                    "invocation_id" to kotlinx.serialization.json.JsonPrimitive(record.id),
                    "run_id" to kotlinx.serialization.json.JsonPrimitive(parentRunId)
                )
            )
        )
        val rejection = dispatch.preflightRejection ?: return correlated
        // A later local preflight cannot erase positive usage retained on the
        // failed response itself, before observe() has ingested that response.
        if (GatewayRecoveryUsage.from(correlated.failureDetails) != null || GatewayRecoveryEvidence.fromDetails(correlated.failureDetails) != null) return correlated
        if (!hasNoExecutionEvidence() || !dispatch.provenEntirelyUnsent()) return correlated
        val details = kotlinx.serialization.json.JsonObject(
            correlated.failureDetails.orEmpty() + kotlinx.serialization.json.buildJsonObject {
                put("code", kotlinx.serialization.json.JsonPrimitive(rejection.reason.name))
                put("failed_stage", kotlinx.serialization.json.JsonPrimitive(rejection.failedStage))
                put("dispatch_state", kotlinx.serialization.json.JsonPrimitive("NOT_DISPATCHED"))
                put("dispatched", kotlinx.serialization.json.JsonPrimitive(false))
                put("retryable", kotlinx.serialization.json.JsonPrimitive(false))
            }
        )
        return correlated.copy(errorCode = rejection.reason.name, failureDetails = details)
    }

    private fun hasNoExecutionEvidence() = generatedBytes == 0L &&
        recoveryAttempts.isEmpty() &&
        recoveryAttemptEvidence.isEmpty() &&
        savedJobAttemptEvidence.isEmpty() &&
        toolCallCount == 0 &&
        !hasVisibleAnswer &&
        (responseParsing?.get("http_status") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() == null &&
        (inputCounter.value ?: 0) == 0L &&
        (outputCounter.value ?: 0) == 0L

    private val visibleLineage = dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineageAccumulator()

    fun observe(event: ProviderEvent) {
        when (event) {
            is ProviderEvent.TextDelta -> {
                if (event.lineage.origin in setOf(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.SYSTEM_ERROR, dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.PROGRESS_NOTICE, dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.APPLICATION_REPORT, dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.RETAINED_CONTENT)) return
                if (event.text.isNotEmpty()) visibleLineage.append(event.lineage)
                if (first == null && event.text.isNotEmpty() && event.lineage.origin == dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.MODEL_TEXT) {
                    first = (System.nanoTime() - started) / 1_000_000
                    dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.FIRST_TEXT, role = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id, elapsedMillis = first, outputOrigin = dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.MODEL_TEXT)
                }
                val size = event.text.toByteArray().size
                visibleFingerprint?.append(event.text)
                visibleBytes += size
                generatedBytes += size
                hasVisibleAnswer = hasVisibleAnswer || event.text.any { !it.isWhitespace() }
            }
            is ProviderEvent.CombinedAnswer -> {
                hasVisibleAnswer = hasVisibleAnswer || event.text.any { !it.isWhitespace() }
            }
            is ProviderEvent.ToolCall -> {
                toolCallCount++
                if (!firstToolRecorded) {
                    firstToolRecorded = true
                    dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.FIRST_TOOL, role = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id, elapsedMillis = (System.nanoTime() - started) / 1_000_000)
                }
            }
            is ProviderEvent.ThinkingDelta -> {
                if (!firstReasoningRecorded && event.text.isNotEmpty()) {
                    firstReasoningRecorded = true
                    dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.FIRST_REASONING, role = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id, elapsedMillis = (System.nanoTime() - started) / 1_000_000)
                }
                val size = event.text.toByteArray().size
                reasoningBytes += size
                generatedBytes += size
            }
            is ProviderEvent.RequestConfigured -> {
                actualAccelerator = event.actualAccelerator ?: actualAccelerator
                serializedOutputCap = event.serializedOutputTokens ?: serializedOutputCap
                localContextTokens = event.localAdmissionReceipt?.contextTokens?.toLong() ?: localContextTokens
                reasoningState = event.reasoningEffort ?: reasoningState
                event.localAdmissionReceipt?.let { receipt ->
                    dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.REQUEST_SERIALIZED, role = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id, localAdmissionReceipt = receipt, contextTokens = receipt.contextTokens.toLong(), successful = receipt.accepted)
                }
                actualRuntime = event.actualRuntime ?: actualRuntime
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                    "Model",
                    "Configured ${record.id} · parentRunId=$parentRunId · requestKind=$kind · profileType=${event.configuredProfileType ?: "not_reported"} · actualRuntime=${event.actualRuntime ?: "not_reported"} · artifactRuntime=${event.artifactRuntime ?: "not_reported"} · actualAccelerator=${event.actualAccelerator ?: "not_reported"} · profileOutputCap=${event.configuredProfileOutputTokens ?: "unset"} · requestedOutputCap=${event.requestedOutputTokens ?: "unset"} · providerOutputCeiling=${event.providerOutputCeiling ?: "unknown"} · serializedOutputCap=${event.serializedOutputTokens ?: "pending"} · effectiveOutputCap=${event.effectiveOutputTokens ?: "unset"}"
                )
            }
            is ProviderEvent.Usage -> {
                if (event.invocationId != null && event.invocationId != record.id) return
                GatewayRecoveryEvidence.fromRecovery(event.gatewayRecovery)?.let(::recordRecoveryEvidence)
                event.reasoningControlReceipt?.let { requestSettings = it.evidence() }
                event.gatewayProvenance?.let { gatewayProvenance = it }
                event.gatewayDeliveryTiming?.evidence()?.let { gatewayDelivery = it }
                event.providerResponseTiming?.let {
                    responseParsing = it.evidence()
                    serverRequestId = it.serverRequestId ?: serverRequestId
                }
                event.gatewayTiming?.takeIf { it.observedFields().isNotEmpty() }?.let { gatewayTiming = it }
                inputCounter.accept(event.inputTokens, event.cumulative, event.inputSource, event.phase)
                outputCounter.accept(event.outputTokens, event.cumulative, event.outputSource, event.phase)
                input = inputCounter.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                output = outputCounter.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                inputSource = inputCounter.source
                outputSource = outputCounter.source
                providerReasoningTokens = mergeUsageCount(providerReasoningTokens, event.reasoningTokens, event.cumulative)
                actualRuntime = event.actualRuntime ?: actualRuntime
                finishReason = event.finishReason?.take(80) ?: finishReason
                settingsConfirmed = settingsConfirmed || event.effectiveSettingsConfirmed
                // A final error receipt may describe several backend attempts.
                // A later timing/interim frame must not replace that known sum.
                applyRecoveryUsage()
            }
            is ProviderEvent.GatewayMetadataCaptured -> {
                serverRequestId = event.metadata.requestId ?: serverRequestId
                savedResultJobId = event.metadata.takeIf { it.transportVerified && it.singleflightRole == "saved_result" }
                    ?.jobId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            }
            ProviderEvent.Completed -> {
                providerGenerationCompleted = true
                if (status !in setOf("FAILED", "PARTIAL", "WAITING_REMOTE_JOB")) {
                    completedWithoutAnswer = !hasVisibleAnswer && !firstToolRecorded
                    status = if (completedWithoutAnswer) "FAILED" else "COMPLETED"
                }
            }
            is ProviderEvent.Failed -> {
                if (event.invocationId == null || event.invocationId == record.id) {
                    GatewayRecoveryEvidence.fromDetails(event.failureDetails)?.let(::recordRecoveryEvidence)
                    GatewayRecoveryUsage.from(event.failureDetails)?.takeIf { savedResultJobId == null }?.let { usage ->
                        val physical = dispatch.receipts.lastOrNull()?.physicalAttemptId ?: record.id
                        recoveryAttempts[physical] = usage
                        applyRecoveryUsage()
                    }
                }
                status = when {
                    event.errorCode == "QUEUE_JOB_PENDING" -> "WAITING_REMOTE_JOB"
                    generatedBytes > 0 && (dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(event.message) || event.errorCode == "STREAM_DEGRADED" || event.transportComplete) -> "PARTIAL"
                    else -> "FAILED"
                }
                diagnosticFailure = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(event.message, event.errorCode, event.failureDetails)
                diagnosticFailure = diagnosticFailure?.let { failure ->
                    val observedAcceptance = observedRemoteAcceptance()
                    val provenUnsent = hasNoExecutionEvidence() && dispatch.provenEntirelyUnsent()
                    failure.copy(
                        dispatched = if (provenUnsent) false else true.takeIf { observedRecoveryExecution() } ?: failure.dispatched,
                        remoteAcceptance = when {
                            provenUnsent -> dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.NO
                            observedRecoveryExecution() -> dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.YES
                            failure.remoteAcceptance == dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN ->
                                dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.entries.firstOrNull { it.name == observedAcceptance } ?: failure.remoteAcceptance
                            else -> failure.remoteAcceptance
                        },
                        serverRequestId = failure.serverRequestId ?: serverRequestId
                    )
                }
                serverRequestId = diagnosticFailure?.serverRequestId ?: serverRequestId
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.recordEvent(
                    "Model",
                    "INVOCATION_FAILED",
                    mapOf(
                        "runId" to parentRunId,
                        "invocationId" to record.id,
                        "incidentId" to record.id,
                        "status" to status,
                        "errorCode" to diagnosticFailure?.category,
                        "failureMessage" to event.message,
                        "failedStage" to diagnosticFailure?.phase,
                        "dispatched" to diagnosticFailure?.dispatched,
                        "remoteAcceptance" to diagnosticFailure?.remoteAcceptance?.name,
                        "retryable" to diagnosticFailure?.retryAllowed
                    ),
                    if (status in setOf("PARTIAL", "WAITING_REMOTE_JOB")) "I" else "E"
                )
            }
            else -> Unit
        }
        val now = System.nanoTime()
        if (now - lastPublished >= 250_000_000 || event is ProviderEvent.Completed || event is ProviderEvent.Failed || (first != null && liveRequests.value[record.id]?.firstTokenMs == null)) {
            lastPublished = now
            liveRequests.update {
                it + (
                    record.id to record.copy(
                        inputTokens = retainedInput(),
                        outputTokens = retainedOutput(),
                        estimated = estimatedUsage(),
                        inputTokenSource = retainedInputSource(),
                        outputTokenSource = retainedOutputSource(),
                        finishReason = finishReason,
                        actualRuntime = actualRuntime,
                        // Publish the observed terminal event before passing it
                        // downstream: an export can start before finally persists it.
                        status = if (status == "INTERRUPTED") "RUNNING" else status,
                        observedInputTokens = input?.toLong(),
                        observedOutputTokens = output?.toLong(),
                        usageEvidenceJson = usageEvidence(!estimatedUsage() && price != null),
                        costMicros = if (!estimatedUsage()) price?.cost(requireNotNull(input), requireNotNull(output)) else record.costMicros,
                        durationMs = (now - started) / 1_000_000,
                        firstTokenMs = first
                    )
                    )
            }
        }
    }

    suspend fun finish() {
        visibleFingerprint?.snapshot()?.let { fingerprint ->
            visibleBytes = fingerprint.utf8Bytes
            generatedBytes = visibleBytes + reasoningBytes
            dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.OUTPUT_CHECKPOINT, invocationId = record.id, outputBoundary = dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceBoundary.PROVIDER_VISIBLE, outputRepresentation = dev.chungjungsoo.gptmobile.data.diagnostics.OutputTextRepresentation.VISIBLE_UTF8, outputFingerprint = fingerprint, outputOrigin = visibleLineage.lineage.origin)
        }
        val unsent = dispatch.provenUnsentAttemptIds()
        unsent.forEach { combinedAccounting?.notDispatched(it) }
        val entirelyUnsent = hasNoExecutionEvidence() && dispatch.provenEntirelyUnsent()
        if (entirelyUnsent) {
            input = 0
            output = 0
            inputSource = "not_dispatched"
            outputSource = "not_dispatched"
            status = "NOT_DISPATCHED"
        }
        dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.INVOCATION_FINISHED, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id, inputTokens = input?.toLong(), outputTokens = output?.toLong(), visibleBytes = visibleBytes, reasoningBytes = reasoningBytes, elapsedMillis = (System.nanoTime() - started) / 1_000_000, successful = (status == "COMPLETED").takeUnless { status == "WAITING_REMOTE_JOB" }, measured = !estimatedUsage())
        researchTrace?.usage(
            dev.chungjungsoo.gptmobile.data.research.ResearchUsageObservation(
                invocationId = record.id,
                inputTokens = if (entirelyUnsent) 0 else retainedInput().toLong(),
                outputTokens = if (entirelyUnsent) 0 else retainedOutput().toLong(),
                inputSource = retainedInputSource(),
                outputSource = retainedOutputSource(),
                visibleOutputEstimate = (visibleBytes + 2) / 3,
                reasoningTokens = providerReasoningTokens?.toLong(),
                firstTextMillis = first,
                durationMillis = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
            )
        )
        val recordedOutput = if (entirelyUnsent) 0 else retainedOutput()
        val admission = dispatch.receipts.lastOrNull()
        val roundOutcome = when {
            status == "NOT_DISPATCHED" -> "PRECHECK_REJECTED"
            status == "CANCELED" -> "CANCELLED"
            completedWithoutAnswer -> "EMPTY_MODEL_ROUND"
            status != "COMPLETED" -> status
            toolCallCount > 0 -> "TOOL_CALLS_PRODUCED"
            else -> "ANSWER_CANDIDATE_PRODUCED"
        }
        val providerReceipt = ProviderCallReceipt(
            clientRequestId = record.id,
            serverRequestId = serverRequestId,
            finishReason = finishReason,
            inputTokens = input,
            outputTokens = output,
            inputSource = inputSource,
            outputSource = outputSource,
            usageConfirmed = !estimatedUsage(),
            contextTokens = localContextTokens ?: admission?.contextTokens,
            contextSource = if (localContextTokens != null) "LOCAL_RUNTIME" else admission?.capacityProvenance?.name ?: "UNVERIFIED",
            outputCap = admission?.outputTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: serializedOutputCap ?: outputLimit,
            outputCapSource = if (admission?.outputTokens != null || serializedOutputCap != null) "SERIALIZED_REQUEST" else "CONFIGURED",
            reasoningState = reasoningState,
            settingsConfirmed = settingsConfirmed,
            runtime = actualRuntime,
            accelerator = actualAccelerator,
            roundOutcome = roundOutcome,
            toolCallCount = toolCallCount,
            visibleBytes = visibleBytes,
            reasoningBytes = reasoningBytes,
            reasoningTokens = providerReasoningTokens,
            dispatchEvidence = if (entirelyUnsent) {
                "NOT_DISPATCHED"
            } else if (diagnosticFailure?.dispatched == true || (responseParsing?.get("http_status") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() != null) {
                "DISPATCHED"
            } else {
                "UNKNOWN"
            },
            failurePhase = dispatch.preflightRejection?.phase ?: diagnosticFailure?.phase,
            reasonCode = dispatch.preflightRejection?.reason?.name ?: diagnosticFailure?.category,
            remoteAcceptance = if (entirelyUnsent) "NO" else diagnosticFailure?.remoteAcceptance?.name?.takeUnless { it == "UNKNOWN" } ?: observedRemoteAcceptance() ?: "UNKNOWN",
            generationCompleted = diagnosticFailure?.generationCompleted ?: providerGenerationCompleted,
            requestReplayAllowed = diagnosticFailure?.requestReplayAllowed,
            usageObservations = kotlinx.serialization.json.buildJsonObject {
                put("input", inputCounter.evidence())
                put("output", outputCounter.evidence())
                recoveryUsage()?.let { put("gateway_recovery", it.evidence) }
                recoveryEvidence()?.let { put("gateway_attempt_evidence", it) }
            }
        ).json()
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Model", "PROVIDER_RECEIPT · runId=$parentRunId · invocationId=${record.id} · receipt=$providerReceipt")
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.recordEvent(
            "Model",
            "INVOCATION_FINISHED",
            mapOf(
                "runId" to parentRunId,
                "invocationId" to record.id,
                "incidentId" to record.id,
                "status" to status,
                "completionDisposition" to roundOutcome,
                "errorCode" to diagnosticFailure?.category,
                "failedStage" to diagnosticFailure?.phase,
                "dispatched" to if (entirelyUnsent) false else diagnosticFailure?.dispatched,
                "toolCallCount" to toolCallCount,
                "durationMs" to (System.nanoTime() - started) / 1_000_000,
                "output" to recordedOutput,
                "estimated" to (!entirelyUnsent && estimatedUsage()),
                "inputSource" to inputSource,
                "outputSource" to outputSource,
                "usageBasis" to if (entirelyUnsent) "NOT_DISPATCHED" else "PROVIDER_OBSERVATIONS",
                "visibleBytes" to visibleBytes,
                "reasoningBytes" to reasoningBytes,
                "usageBreakdownReliable" to entirelyUnsent,
                "usageBreakdownUnavailable" to if (entirelyUnsent) emptyList() else listOfNotNull("visibleOutputTokens", "toolCallTokens", "reasoningTokens".takeIf { providerReasoningTokens == null }),
                "reportedReasoningTokens" to providerReasoningTokens,
                "toolCallTokens" to 0.takeIf { entirelyUnsent },
                "visibleOutputTokens" to 0.takeIf { entirelyUnsent },
                "finishReason" to finishReason,
                "settingsConfirmed" to settingsConfirmed,
                "turnKey" to turnKey,
                "requestKind" to kind
            ),
            if (status in setOf("COMPLETED", "CANCELED", "WAITING_REMOTE_JOB")) "I" else "W"
        )
        try {
            if (retainAccounting) {
                val observation = record.copy(
                    inputTokens = if (entirelyUnsent) 0 else retainedInput(),
                    outputTokens = if (entirelyUnsent) 0 else retainedOutput(),
                    estimated = !entirelyUnsent && estimatedUsage(),
                    inputTokenSource = retainedInputSource(),
                    outputTokenSource = retainedOutputSource(),
                    finishReason = finishReason,
                    actualRuntime = actualRuntime,
                    status = status,
                    consumedAssistInvocationIds = dispatch.includedProducers.sorted().takeIf { it.isNotEmpty() }?.let { kotlinx.serialization.json.Json.encodeToString(it) },
                    assistInclusionVerified = dispatch.receipts.isNotEmpty(),
                    requestAdmissionReceipts = dispatch.receipts.toList().takeIf { it.isNotEmpty() }?.let { kotlinx.serialization.json.Json.encodeToString(it) },
                    observedInputTokens = input?.toLong(),
                    observedOutputTokens = output?.toLong(),
                    usageEvidenceJson = usageEvidence(entirelyUnsent || !estimatedUsage() && price != null),
                    costMicros = if (entirelyUnsent) {
                        0
                    } else if (!estimatedUsage()) {
                        price?.cost(requireNotNull(input), requireNotNull(output))
                    } else {
                        record.costMicros
                    },
                    durationMs = (System.nanoTime() - started) / 1_000_000,
                    firstTokenMs = first
                )
                if (combinedAccounting != null) combinedAccounting.finish(observation, providerReasoningTokens) else dao.save(observation)
            }
        } finally {
            liveRequests.update { it - record.id }
        }
    }

    fun cancelled() {
        status = "CANCELED"
    }

    fun failed(error: Exception) {
        status = "FAILED"
        val typed = dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, generatedBytes > 0 || toolCallCount > 0)
        diagnosticFailure = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(typed?.message ?: error.message.orEmpty(), typed?.code, typed?.failureDetails)
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.recordEvent(
            "Model",
            "INVOCATION_FAILED",
            mapOf("runId" to parentRunId, "invocationId" to record.id, "incidentId" to record.id, "status" to status, "errorCode" to diagnosticFailure?.category, "exceptionType" to error.javaClass.simpleName, "failureMessage" to error.message),
            "E"
        )
    }
}
