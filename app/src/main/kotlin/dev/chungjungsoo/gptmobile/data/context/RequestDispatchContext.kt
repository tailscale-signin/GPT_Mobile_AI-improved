package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.assist.AssistHandoffContract
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bridges a physical serializer to its existing invocation owner without retaining prompt bytes. */
internal class RequestDispatchContext(private val candidates: Set<JsonObject>, private val runId: String? = null, private val invocationId: String? = null, private val preparedEvidence: Set<JsonObject> = emptySet(), private val researchTrace: dev.chungjungsoo.gptmobile.data.research.ResearchStageTrace? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestDispatchContext>
    val queueScope: String? get() = runId ?: invocationId
    val queueInvocationId: String? get() = invocationId
    private val physicalOrdinal = java.util.concurrent.atomic.AtomicLong()
    val receipts = CopyOnWriteArrayList<RequestAdmissionReceipt>()
    val includedProducers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val httpTrace = java.util.concurrent.atomic.AtomicReference<dev.chungjungsoo.gptmobile.data.network.HttpAttemptTrace?>()
    private val attemptTraces = java.util.concurrent.ConcurrentHashMap<String, dev.chungjungsoo.gptmobile.data.network.HttpAttemptTrace>()
    private val pollTraces = java.util.concurrent.ConcurrentHashMap<String, dev.chungjungsoo.gptmobile.data.network.HttpAttemptTrace>()
    fun provenUnsentAttemptIds(): Set<String> {
        val unsent = attemptTraces.filterValues { it.provenUnsent }.keys
        (unsent + pollTraces.filterValues { it.provenUnsent }.keys).forEach { researchTrace?.notDispatched(it) }
        return unsent
    }

    fun armQueuePoll(jobId: String) {
        val observer = researchTrace ?: return
        val id = java.util.UUID.randomUUID().toString()
        observer.prepared(id, invocationId, "QUEUE_POLL", jobId)
        val trace = dev.chungjungsoo.gptmobile.data.network.HttpAttemptTrace { kind, elapsed ->
            if (kind in setOf(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.REQUEST_BODY_END, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.RESPONSE_HEADERS)) observer.transmitted(id, elapsed)
        }
        pollTraces[id] = trace
        httpTrace.set(trace)
    }

    fun queueJob(jobId: String, resumed: Boolean, cached: Boolean = false) {
        researchTrace?.queueJob(
            jobId,
            invocationId,
            if (cached) {
                "CACHED"
            } else if (resumed) {
                "RESUMED"
            } else {
                "SUBMITTED"
            },
            receipts.lastOrNull()?.physicalAttemptId?.takeUnless { resumed }
        )
    }

    fun armHttp() {
        val receipt = receipts.lastOrNull() ?: return
        val owner = runId
        val invocation = invocationId
        val attempt = receipt.physicalAttemptId ?: return
        val role = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(receipt.role)
        val trace = dev.chungjungsoo.gptmobile.data.network.HttpAttemptTrace { kind, elapsed ->
            if (kind in setOf(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.REQUEST_BODY_END, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.RESPONSE_HEADERS)) researchTrace?.transmitted(attempt, elapsed)
            owner?.let { dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(it, kind, role, invocationId = invocation, physicalAttemptId = attempt, elapsedMillis = elapsed) }
        }
        attemptTraces[attempt] = trace
        httpTrace.set(trace)
    }
    fun takeHttpTrace(): dev.chungjungsoo.gptmobile.data.network.HttpAttemptTrace? = httpTrace.getAndSet(null)
    fun transportWait(kind: dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind, elapsed: Long) {
        runId?.let { owner -> dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(owner, kind, invocationId = invocationId, elapsedMillis = elapsed.coerceAtLeast(0)) }
    }

    fun progress(stage: dev.chungjungsoo.gptmobile.data.agent.ObservedProgressStage, elapsedMillis: Long, deadline: dev.chungjungsoo.gptmobile.data.diagnostics.ProgressDeadlineCode? = null) {
        runId?.let { owner ->
            val receipt = receipts.lastOrNull()
            dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(
                owner,
                if (deadline == null) dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.PROGRESS_STAGE else dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.PROGRESS_DEADLINE,
                dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(receipt?.role.orEmpty()),
                invocationId = invocationId,
                physicalAttemptId = receipt?.physicalAttemptId,
                elapsedMillis = elapsedMillis.coerceAtLeast(0),
                progressStage = stage,
                progressDeadline = deadline
            )
        }
    }

    fun serialized(payload: String, receipt: RequestAdmissionReceipt) {
        val ordinal = physicalOrdinal.incrementAndGet()
        receipts += receipt
        receipt.physicalAttemptId?.let { researchTrace?.prepared(it, invocationId, outputTokens = receipt.outputTokens, contextTokens = receipt.contextTokens, capacityProvenance = receipt.capacityProvenance.name, inputMeasurement = receipt.measurement.name) }
        runId?.let { owner ->
            dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(owner, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.REQUEST_SERIALIZED, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(receipt.role), invocationId = invocationId, physicalAttempt = ordinal, physicalAttemptId = receipt.physicalAttemptId, inputTokens = receipt.promptTokens, outputTokens = receipt.outputTokens, safetyTokens = receipt.safetyTokens, contextTokens = receipt.contextTokens, measured = receipt.measurement == TokenMeasurementKind.SERVING_TOKENIZER)
        }
        val attemptIncluded = mutableSetOf<String>()
        fun inspect(node: JsonElement, depth: Int) {
            if (depth > 32) return
            when (node) {
                is JsonObject -> {
                    val assist = node["assist"] as? JsonArray
                    assist?.filterIsInstance<JsonObject>()?.filter { it in candidates }?.forEach { metadata ->
                        attemptIncluded += AssistHandoffContract.producerIds(AgentToolResult("receipt", ToolResultContent.Json(JsonObject(emptyMap())), false, assistMetadata = listOf(metadata)))
                    }
                    node.values.forEach { inspect(it, depth + 1) }
                }
                is JsonArray -> node.forEach { inspect(it, depth + 1) }
                is JsonPrimitive -> if (node.isString) {
                    // Trusted candidates include the complete claim, quote and source identity. A truncated
                    // packet, detached producer marker, or unrelated model text never counts as inclusion.
                    preparedEvidence.filter { node.content.contains(it.toString()) }.forEach { packet ->
                        attemptIncluded += AssistHandoffContract.producerIds(AgentToolResult("prepared-receipt", ToolResultContent.Json(JsonObject(emptyMap())), false, assistMetadata = listOf(packet)))
                    }
                    if (node.content.startsWith('{')) runCatching { Json.parseToJsonElement(node.content) }.getOrNull()?.let { inspect(it, depth + 1) }
                }
            }
        }
        runCatching { Json.parseToJsonElement(payload) }.getOrNull()?.let { inspect(it, 0) }
        includedProducers += attemptIncluded
        val offered = (candidates + preparedEvidence).flatMap { metadata ->
            AssistHandoffContract.producerIds(AgentToolResult("offered", ToolResultContent.Json(JsonObject(emptyMap())), false, assistMetadata = listOf(metadata)))
        }.toSet()
        dev.chungjungsoo.gptmobile.data.assist.AssistTelemetry.handoff(runId, invocationId, receipt.physicalAttemptId, offered, attemptIncluded)
    }
}
