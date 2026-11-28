package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubTestChecklist
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** An explicit tool checklist owes catalog coverage, without turning discovery into source evidence. */
internal class GitHubChecklistDiscoveryProgress(task: String) {
    private val required = GitHubTestChecklist.integration(task)
    private var inventoryVersion: String? = null
    private var inventoryCount: Int? = null
    private var nextPage: JsonObject? = null
    private val inventoryNames = linkedSetOf<String>()
    private val acquiredSchemas = linkedSetOf<Pair<String, String>>()

    fun advances(call: ProviderEvent.ToolCall, result: AgentToolResult): Boolean {
        if (!required || call.name != ProgressiveToolCatalog.NAME || result.isError || result.sharedResult || result.callId != call.callId) return false
        val data = ((result.retainedContent ?: result.content) as? ToolResultContent.Json)?.value as? JsonObject ?: return false
        if (data["authorized_inventory"] is JsonArray) return directory(call, data)
        val requested = (call.arguments["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content } ?: return false
        if (requested.isEmpty() || requested.size > 12 || data["wire_context_budget_enforced"] != JsonPrimitive(true)) return false
        val action = call.arguments.text("action")
        val selections = data["selected_actions"] as? JsonObject
        val available = (data["available_next_round"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { it.text("name") }.toSet()
        // One allowance per exact requested tool/action. Re-advertising or changing
        // descriptions cannot replenish it; actual dispatch still needs round admission.
        var advanced = false
        for (name in requested) {
            if (name in available && (action.isBlank() || selections?.get(name) == JsonPrimitive(action)) && acquiredSchemas.size < MAX_SCHEMAS) {
                if (acquiredSchemas.add(name to action)) advanced = true
            }
        }
        return advanced
    }

    private fun directory(call: ProviderEvent.ToolCall, data: JsonObject): Boolean {
        val inventory = data["authorized_inventory"] as JsonArray
        val entries = inventory.filterIsInstance<JsonObject>()
        val names = entries.map { it.text("name") }
        val version = data.text("inventory_version")
        val total = data.number("authorized_tool_count") ?: return false
        val offset = data.number("inventory_offset") ?: return false
        val end = offset.toLong() + names.size
        if (!version.matches(Regex("[a-fA-F0-9]{64}")) ||
            data["contract_version"] != JsonPrimitive(1) ||
            data["permissions_changed"] != JsonPrimitive(false) ||
            total < 0 ||
            offset < 0 ||
            end > total ||
            data.number("returned") != names.size ||
            entries.size != inventory.size ||
            names.isEmpty() ||
            names.size > 256 ||
            names.any(String::isBlank) ||
            names.distinct().size != names.size ||
            names.any { it in inventoryNames } ||
            inventoryNames.size + names.size > MAX_INVENTORY_NAMES
        ) {
            return false
        }
        if (inventoryVersion == null) {
            if (offset != 0 || call.arguments.isNotEmpty() && call.arguments != JsonObject(mapOf("catalog_only" to JsonPrimitive(true)))) return false
        } else if (version != inventoryVersion || total != inventoryCount || call.arguments != nextPage || offset != inventoryNames.size) {
            return false
        }
        val continuation = data["continuation_call"] as? JsonObject
        val arguments = continuation?.get("arguments") as? JsonObject
        if (data["has_more"] == JsonPrimitive(true)) {
            if (end >= total ||
                continuation?.text("tool") != ProgressiveToolCatalog.NAME ||
                arguments?.keys != setOf("inventory_cursor", "inventory_version") ||
                arguments.number("inventory_cursor")?.toLong() != end ||
                arguments.text("inventory_version") != version
            ) {
                return false
            }
        } else if (data["has_more"] != JsonPrimitive(false) || end != total.toLong() || continuation != null) {
            return false
        }
        inventoryVersion = version
        inventoryCount = total
        inventoryNames += names
        nextPage = arguments
        return true
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private companion object {
        const val MAX_SCHEMAS = 256
        const val MAX_INVENTORY_NAMES = 4096
    }
}
