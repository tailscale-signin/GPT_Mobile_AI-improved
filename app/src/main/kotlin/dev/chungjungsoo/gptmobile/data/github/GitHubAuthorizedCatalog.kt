package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Discovery pages establish the denominator. Explicit-name schema acquisition is a separate operation. */
internal object GitHubAuthorizedCatalog {
    const val TOOL = "discover_available_tools"

    fun snapshot(receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>): JsonObject {
        val observations = receipts.values.filter {
            it.text("tool") == TOOL &&
                it["trusted"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false)
        }
        val pages = observations.filter { it["success"] == JsonPrimitive(true) && data(it)?.get("authorized_inventory") is JsonArray }
        val first = pages.lastOrNull { data(it)?.number("inventory_offset") == 0 }
        val version = first?.let(::data)?.text("inventory_version").orEmpty()
        val expected = first?.let(::data)?.number("authorized_tool_count")
        val names = linkedSetOf<String>()
        val ids = mutableListOf<kotlinx.serialization.json.JsonElement>()
        var offset = 0
        var next: JsonObject? = null
        var complete = false
        var invalid: String? = null
        if (first != null && version.isNotBlank() && expected != null && expected >= 0) {
            var page: JsonObject? = first
            while (page != null) {
                val value = data(page) ?: break
                val entries = (value["authorized_inventory"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                val pageNames = entries.map { it.text("name") }
                val changedContract = entries.any { entry ->
                    val definition = tools.firstOrNull { it.name == entry.text("name") }
                    definition != null && entry.text("schema_fingerprint") != GitHubEvidenceBook.digest(definition.inputSchema.toString())
                }
                if (value.text("inventory_version") != version ||
                    value.number("authorized_tool_count") != expected ||
                    value.number("inventory_offset") != offset ||
                    value.number("returned") != entries.size ||
                    pageNames.any(String::isBlank) ||
                    pageNames.distinct().size != pageNames.size ||
                    pageNames.any { it in names } ||
                    offset + entries.size > expected ||
                    changedContract
                ) {
                    invalid = if (changedContract) "CATALOG_CONTRACT_CHANGED" else "CATALOG_PAGE_INCONSISTENT"
                    break
                }
                names += pageNames
                page["receipt_id"]?.let(ids::add)
                offset += entries.size
                next = value["continuation_call"] as? JsonObject
                if (value["has_more"] == JsonPrimitive(false)) {
                    complete = offset == expected && next == null
                    if (!complete) invalid = "CATALOG_TERMINAL_COUNT_MISMATCH"
                    break
                }
                if (offset >= expected) {
                    invalid = "CATALOG_TERMINAL_COUNT_MISMATCH"
                    next = null
                    break
                }
                val arguments = next?.get("arguments") as? JsonObject
                if (entries.isEmpty() ||
                    next?.text("tool") != TOOL ||
                    arguments?.number("inventory_cursor") != offset ||
                    arguments.text("inventory_version") != version ||
                    arguments.keys != setOf("inventory_cursor", "inventory_version")
                ) {
                    invalid = "CATALOG_CONTINUATION_INVALID"
                    next = null
                    break
                }
                page = pages.drop(pages.indexOf(page) + 1).firstOrNull { it["arguments"] == arguments }
            }
        } else if (first != null) {
            invalid = "CATALOG_IDENTITY_MISSING"
        }
        val failedNext = next?.let { recipe -> observations.lastOrNull { it["arguments"] == recipe["arguments"] && it["success"] != JsonPrimitive(true) } }
        val authorizedNames = tools.filter { it.modelVisible && github(it.name) }.map { it.name }.toSet()
        if (complete && authorizedNames.isNotEmpty() && names.filter(::github).toSet() != authorizedNames) {
            invalid = "CATALOG_AUTHORIZATION_CHANGED"
        }
        val known = tools.filter { it.modelVisible && github(it.name) }.map { it.name }.distinct()
        return buildJsonObject {
            put("schema", "github.authorized-catalog.v1")
            put(
                "status",
                if (invalid != null || failedNext != null) {
                    "BLOCKED"
                } else if (complete) {
                    "COMPLETE"
                } else if (first == null) {
                    "NOT_OBSERVED"
                } else {
                    "PARTIAL"
                }
            )
            put("complete", complete && invalid == null)
            put("denominator_final", complete && invalid == null)
            put("inventory_version", version)
            expected?.let { put("authorized_tool_count", it) }
            put("observed_tool_count", names.size)
            put("observed_github_tool_count", names.count(::github))
            put("known_github_tool_count", known.size)
            put("observed_names", JsonArray(names.map(::JsonPrimitive)))
            put("receipt_ids", JsonArray(ids))
            if (!complete && invalid == null) next?.let { put("continuation_call", it) }
            (invalid ?: failedNext?.text("error_code")?.ifBlank { "CATALOG_CONTINUATION_FAILED" })?.let { put("blocker", it) }
            put("scope", "Authorized tool names; capability actions and repository inventory pages have independent denominators")
        }
    }

    /** Reuse only the version-bound recipe actually returned by discovery. */
    fun next(tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, catalogTools: List<AgentToolDefinition> = tools): ProviderEvent.ToolCall? {
        val tool = tools.firstOrNull { it.name == TOOL && it.modelVisible } ?: return null
        val state = snapshot(receipts, catalogTools)
        if (state["complete"] == JsonPrimitive(true) || state.text("status") == "BLOCKED") return null
        val arguments = (state["continuation_call"] as? JsonObject)?.get("arguments") as? JsonObject
            ?: JsonObject(emptyMap()).takeIf { state.text("status") == "NOT_OBSERVED" } ?: return null
        if (ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty() ||
            receipts.values.any {
                it.text("tool") == TOOL && it["verification_eligible"] != JsonPrimitive(false) && it["arguments"] == arguments
            }
        ) {
            return null
        }
        return ProviderEvent.ToolCall("catalog-" + GitHubEvidenceBook.digest(arguments.toString()).take(20), tool.name, arguments)
    }

    fun required(tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>): Boolean =
        tools.any { it.name == TOOL } || receipts.values.any { it.text("tool") == TOOL }

    private fun github(name: String) = name.substringBefore("__").let { it.startsWith("github_") || it == "repository_graph" }
    private fun data(receipt: JsonObject) = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
    private fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
}
