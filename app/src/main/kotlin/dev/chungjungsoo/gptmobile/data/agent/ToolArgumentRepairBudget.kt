package dev.chungjungsoo.gptmobile.data.agent

import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One initial rejection and one correction per unchanged failed constraint, scoped to this turn. */
internal class ToolArgumentRepairBudget {
    private val failures = linkedMapOf<String, Int>()

    @Synchronized
    fun rejection(callId: String, definition: AgentToolDefinition, arguments: JsonObject, errors: List<String>): AgentToolResult {
        require(errors.isNotEmpty())
        val identity = buildJsonObject {
            put("schema", "tool.argument-repair.v1")
            put("tool", definition.name)
            put("contract", ToolContractFingerprint.schema(definition.inputSchema))
            arguments["action"]?.let { put("action", it) }
            put("constraints", JsonArray(errors.sorted().map(::JsonPrimitive)))
        }
        val signature = MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val attempt = ((failures[signature] ?: 0) + 1).coerceAtMost(2)
        if (failures.size < 128 || signature in failures) failures[signature] = attempt
        val rejected = ToolArgumentValidator.rejection(callId, definition.name, arguments, definition.inputSchema, errors)
        val exhausted = attempt >= 2
        val content = if (exhausted) {
            val payload = (rejected.content as ToolResultContent.Json).value as JsonObject
            ToolResultContent.Json(
                JsonObject(
                    payload + buildJsonObject {
                        put("reason", "ARGUMENT_REPAIR_EXHAUSTED")
                        put("message", "Repeated proposals still violate the same declared constraints. No action was dispatched. Finish from retained evidence if this is the only remaining work; valid corrected arguments and other authorized operations remain available.")
                    }
                )
            )
        } else {
            rejected.content
        }
        return rejected.copy(
            content = content,
            sharedResult = attempt > 1,
            executionReceipt = JsonObject(
                rejected.executionReceipt.orEmpty() + buildJsonObject {
                    put("argument_repair_signature_hash", signature)
                    put("argument_repair_attempt", attempt)
                    put("argument_repair_exhausted", exhausted)
                }
            )
        )
    }
}

/** Fresh per flow collection; parallel tool workers inherit the same bounded repair ledger. */
internal class ToolArgumentRepairScope(val budget: ToolArgumentRepairBudget = ToolArgumentRepairBudget()) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolArgumentRepairScope>
}
