package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Request-local completed receipts. A format repair cannot re-execute a successful action. */
internal class LocalToolRepairReceipts {
    private val completed = linkedMapOf<String, String>()

    fun remember(call: PromptToolCall, result: String) {
        val root = runCatching { Json.parseToJsonElement(result) as? JsonObject }.getOrNull() ?: return
        if ((root["status"] as? JsonPrimitive)?.content != "success") return
        completed[identity(call)] = result
    }

    fun completed(call: PromptToolCall): String? = completed[identity(call)]

    private fun identity(call: PromptToolCall): String = call.name + ":" + ScopedToolGrant.canonicalHash(Json.parseToJsonElement(call.argumentsJson) as JsonObject)
}
