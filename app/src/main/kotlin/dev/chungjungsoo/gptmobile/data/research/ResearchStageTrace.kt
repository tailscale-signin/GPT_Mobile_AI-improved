package dev.chungjungsoo.gptmobile.data.research

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.Serializable

@Serializable
data class ResearchTransportObservation(
    val id: String,
    val operation: String,
    val state: String = "PREPARED",
    val invocationId: String? = null,
    val queueJobId: String? = null,
    val elapsedMillis: Long? = null,
    val outputTokens: Long? = null,
    val contextTokens: Long? = null,
    val capacityProvenance: String = "UNKNOWN",
    val inputMeasurement: String = "UNKNOWN"
)

@Serializable
data class ResearchUsageObservation(
    val invocationId: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val inputSource: String = "unknown",
    val outputSource: String = "unknown",
    val visibleOutputEstimate: Long? = null,
    val reasoningTokens: Long? = null,
    val toolCallTokens: Long? = null,
    val firstTextMillis: Long? = null,
    val durationMillis: Long = 0
)

@Serializable
data class ResearchQueueObservation(val id: String, val invocationId: String, val state: String, val generationAttemptId: String? = null)

/** Stable IDs make repeated session emissions replacements, never additional requests. */
@Serializable
data class ResearchStageMetrics(
    val schemaVersion: Int = 1,
    val rootWorkId: String,
    val logicalStageId: String,
    val stageAttemptId: String,
    val invocationIds: List<String> = emptyList(),
    val transports: List<ResearchTransportObservation> = emptyList(),
    val usage: List<ResearchUsageObservation> = emptyList(),
    val queueJobs: List<ResearchQueueObservation> = emptyList()
)

/** Observation only. This context never grants permission, admits work or retries a request. */
internal class ResearchStageTrace(
    private val rootWorkId: String,
    private val logicalStageId: String,
    private val stageAttemptId: String
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResearchStageTrace>

    private val invocations = ConcurrentHashMap.newKeySet<String>()
    private val transport = ConcurrentHashMap<String, ResearchTransportObservation>()
    private val usage = ConcurrentHashMap<String, ResearchUsageObservation>()
    private val queueJobs = ConcurrentHashMap<String, ResearchQueueObservation>()

    fun invocation(id: String) {
        invocations.add(id)
    }

    fun prepared(id: String, invocationId: String?, operation: String = "GENERATION", queueJobId: String? = null, outputTokens: Long? = null, contextTokens: Long? = null, capacityProvenance: String = "UNKNOWN", inputMeasurement: String = "UNKNOWN") {
        require(operation in setOf("GENERATION", "QUEUE_POLL"))
        invocationId?.let(invocations::add)
        transport.putIfAbsent(id, ResearchTransportObservation(id, operation, invocationId = invocationId, queueJobId = queueJobId, outputTokens = outputTokens, contextTokens = contextTokens, capacityProvenance = capacityProvenance, inputMeasurement = inputMeasurement))
    }

    fun transmitted(id: String, elapsedMillis: Long) {
        transport.computeIfPresent(id) { _, before -> before.copy(state = "TRANSMITTED", elapsedMillis = elapsedMillis.coerceAtLeast(0)) }
    }

    fun notDispatched(id: String) {
        transport.computeIfPresent(id) { _, before ->
            if (before.state == "TRANSMITTED") before else before.copy(state = "NOT_DISPATCHED")
        }
    }

    fun usage(value: ResearchUsageObservation) {
        invocations.add(value.invocationId)
        usage[value.invocationId] = value
    }

    fun queueJob(id: String, invocationId: String?, state: String, generationAttemptId: String? = null) {
        if (invocationId == null) return
        require(state in setOf("SUBMITTED", "RESUMED", "CACHED"))
        invocations.add(invocationId)
        queueJobs["$invocationId:$id"] = ResearchQueueObservation(id, invocationId, state, generationAttemptId)
        generationAttemptId?.let { attempt -> transport.computeIfPresent(attempt) { _, before -> before.copy(queueJobId = id) } }
    }

    fun snapshot(): ResearchStageMetrics = ResearchStageMetrics(
        rootWorkId = rootWorkId,
        logicalStageId = logicalStageId,
        stageAttemptId = stageAttemptId,
        invocationIds = invocations.sorted(),
        transports = transport.values.sortedBy { it.id },
        usage = usage.values.sortedBy { it.invocationId },
        queueJobs = queueJobs.values.sortedWith(compareBy<ResearchQueueObservation> { it.id }.thenBy { it.invocationId })
    )
}
