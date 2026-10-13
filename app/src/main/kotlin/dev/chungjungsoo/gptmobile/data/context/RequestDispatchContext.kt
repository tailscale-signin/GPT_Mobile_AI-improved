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
internal class RequestDispatchContext(private val candidates: Set<JsonObject>, private val runId: String? = null, private val invocationId: String? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestDispatchContext>
    val receipts = CopyOnWriteArrayList<RequestAdmissionReceipt>()
    val includedProducers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun serialized(payload: String, receipt: RequestAdmissionReceipt) {
        receipts += receipt
        runId?.let { owner ->
            dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(owner, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.REQUEST_SERIALIZED, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(receipt.role), invocationId = invocationId, physicalAttempt = receipts.size.toLong(), inputTokens = receipt.promptTokens, outputTokens = receipt.outputTokens, safetyTokens = receipt.safetyTokens, contextTokens = receipt.contextTokens, measured = receipt.measurement == TokenMeasurementKind.SERVING_TOKENIZER)
        }
        fun inspect(node: JsonElement, depth: Int) {
            if (depth > 32) return
            when (node) {
                is JsonObject -> {
                    val assist = node["assist"] as? JsonArray
                    assist?.filterIsInstance<JsonObject>()?.filter { it in candidates }?.forEach { metadata ->
                        includedProducers += AssistHandoffContract.producerIds(AgentToolResult("receipt", ToolResultContent.Json(JsonObject(emptyMap())), false, assistMetadata = listOf(metadata)))
                    }
                    node.values.forEach { inspect(it, depth + 1) }
                }
                is JsonArray -> node.forEach { inspect(it, depth + 1) }
                is JsonPrimitive -> if (node.isString && node.content.startsWith('{')) {
                    runCatching { Json.parseToJsonElement(node.content) }.getOrNull()?.let { inspect(it, depth + 1) }
                }
            }
        }
        runCatching { Json.parseToJsonElement(payload) }.getOrNull()?.let { inspect(it, 0) }
    }
}
