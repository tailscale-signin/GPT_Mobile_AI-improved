package dev.chungjungsoo.gptmobile.data.agent

import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One initial rejection and one correction per unchanged constraint in the accepted task. */
internal class ToolArgumentRepairBudget {
    private val failures = linkedMapOf<String, Int>()

    @Synchronized
    fun snapshot(): List<JsonObject> = failures.map { (signature, attempt) ->
        buildJsonObject {
            put("argument_repair_signature_hash", signature)
            put("argument_repair_attempt", attempt)
        }
    }

    /** Receipts are restored only after the caller validates the durable task/recovery scope. */
    @Synchronized
    fun restore(receipts: Iterable<JsonObject>) {
        for (receipt in receipts) {
            val signature = (receipt["argument_repair_signature_hash"] as? JsonPrimitive)?.contentOrNull ?: continue
            val attempt = (receipt["argument_repair_attempt"] as? JsonPrimitive)?.intOrNull ?: continue
            if (!signature.matches(Regex("[a-f0-9]{64}")) || attempt !in 1..2) continue
            if (failures.size < 128 || signature in failures) failures[signature] = maxOf(failures[signature] ?: 0, attempt)
        }
    }

    @Synchronized
    fun rejection(callId: String, definition: AgentToolDefinition, arguments: JsonObject, errors: List<String>, readRules: Set<dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.ReadRule> = emptySet(), ciRejections: Set<dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.CiRejection> = emptySet(), searchValidationOwner: GitHubSearchValidationEvidence.Owner? = null, catalog: ProgressiveToolCatalog? = null): AgentToolResult {
        require(errors.isNotEmpty())
        val identity = buildJsonObject {
            put("schema", "tool.argument-repair.v1")
            put("tool", definition.name)
            put("contract", ToolContractFingerprint.schema(definition.inputSchema))
            arguments["action"]?.let { put("action", it) }
            if (catalog != null && arguments["names"] is JsonArray) put("names", arguments.getValue("names"))
            put("constraints", JsonArray(errors.sorted().map(::JsonPrimitive)))
        }
        val signature = MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val attempt = ((failures[signature] ?: 0) + 1).coerceAtMost(2)
        if (failures.size < 128 || signature in failures) failures[signature] = attempt
        val rejected = ToolArgumentValidator.rejection(callId, definition.name, arguments, definition.inputSchema, errors, readRules, ciRejections, searchValidationOwner, catalog)
        val exhausted = attempt >= 2
        val content = if (exhausted) {
            val payload = (rejected.content as ToolResultContent.Json).value as JsonObject
            ToolResultContent.Json(
                JsonObject(
                    (payload - "correction_calls") + buildJsonObject {
                        if ("correction_calls" in payload) put("corrected_proposal_limit", 0)
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
