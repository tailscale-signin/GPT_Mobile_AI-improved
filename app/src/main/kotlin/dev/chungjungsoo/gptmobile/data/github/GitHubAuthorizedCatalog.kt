package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolContractFingerprint
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
        val contractChanges = mutableListOf<JsonObject>()
        val receivedNames = linkedSetOf<String>()
        val verifiedGithubNames = linkedSetOf<String>()
        var offset = 0
        var next: JsonObject? = null
        var complete = false
        var invalid: String? = null
        if (first != null && first.text("binding").isNotBlank() && first.text("repository").isNotBlank() && version.isNotBlank() && expected != null && expected >= 0) {
            var page: JsonObject? = first
            while (page != null) {
                val value = data(page) ?: break
                val entries = (value["authorized_inventory"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                val pageNames = entries.map { it.text("name") }
                receivedNames += pageNames.filter(String::isNotBlank)
                contractChanges += entries.mapNotNull { entry ->
                    tools.firstOrNull { it.name == entry.text("name") }?.let { contractChange(entry, it) }
                }
                // The inventory envelope remains global, but a search/MCP schema
                // change cannot invalidate independent native GitHub contracts.
                val changedNames = contractChanges.map { it.text("name") }.toSet()
                val changedContract = changedNames.any(::github)
                if (value.text("inventory_version") != version ||
                    page["binding"] != first["binding"] ||
                    page["repository"] != first["repository"] ||
                    value.number("authorized_tool_count") != expected ||
                    value.number("inventory_offset") != offset ||
                    value.number("returned") != entries.size ||
                    pageNames.any(String::isBlank) ||
                    pageNames.distinct().size != pageNames.size ||
                    pageNames.any { it in names } ||
                    offset + entries.size > expected ||
                    changedContract
                ) {
                    invalid = if (changedContract) {
                        "CATALOG_CONTRACT_CHANGED"
                    } else if (page["binding"] != first["binding"] || page["repository"] != first["repository"]) {
                        "CATALOG_AUTHORIZATION_CHANGED"
                    } else {
                        "CATALOG_PAGE_INCONSISTENT"
                    }
                    break
                }
                names += pageNames
                verifiedGithubNames += pageNames.filter { github(it) && it !in changedNames }
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
        if (failedNext != null) complete = false
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
            put("discovery_calls_observed", observations.size)
            put("discovery_calls_succeeded", observations.count { it["success"] == JsonPrimitive(true) })
            put("discovery_calls_failed", observations.count { it["success"] != JsonPrimitive(true) })
            put("inventory_pages_received", pages.size)
            put("inventory_pages_verified", ids.size)
            put("call_count_scope", "CURRENT_TASK_RETAINED_DISCOVERY_RECEIPTS")
            expected?.let { put("authorized_tool_count", it) }
            put("observed_tool_count", names.size)
            put("observed_github_tool_count", names.count(::github))
            put("known_github_tool_count", known.size)
            put("observed_names", JsonArray(names.map(::JsonPrimitive)))
            put("received_names", JsonArray(receivedNames.map(::JsonPrimitive)))
            put("received_tool_count", receivedNames.size)
            put("received_github_tool_count", receivedNames.count(::github))
            put("verified_github_names", JsonArray(verifiedGithubNames.map(::JsonPrimitive)))
            put("contract_scope", "REQUESTED_GITHUB_TOOLS")
            put("receipt_ids", JsonArray(ids))
            put("retained_page_receipt_ids", JsonArray(pages.mapNotNull { it["receipt_id"] }.takeLast(64)))
            if (contractChanges.isNotEmpty()) {
                put("contract_change_count", contractChanges.size)
                put("contract_changes", JsonArray(contractChanges.take(32)))
                put("requested_contract_changes", JsonArray(contractChanges.filter { github(it.text("name")) }.take(32)))
                put("unrelated_contract_changes", JsonArray(contractChanges.filterNot { github(it.text("name")) }.take(32)))
            }
            if (!complete && invalid == null) next?.let { put("continuation_call", it) }
            (invalid ?: failedNext?.text("error_code")?.ifBlank { "CATALOG_CONTINUATION_FAILED" })?.let { put("blocker", it) }
            put("scope", "Authorized tool names; capability actions and repository inventory pages have independent denominators")
        }
    }

    /** Reuse only the version-bound recipe actually returned by discovery. */
    fun next(tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, catalogTools: List<AgentToolDefinition> = tools): ProviderEvent.ToolCall? {
        val tool = tools.firstOrNull { it.name == TOOL && it.modelVisible } ?: return null
        val state = snapshot(receipts, catalogTools)
        if (state["complete"] == JsonPrimitive(true)) return null
        val observations = receipts.values.filter { it.text("tool") == TOOL && it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) }
        val stale = observations.lastOrNull { it["success"] != JsonPrimitive(true) && it.text("error_code") == "STALE_INVENTORY" }
        if (stale != null && observations.lastOrNull() == stale) {
            val error = data(stale)
            val correction = error?.get("correction_call") as? JsonObject
            val arguments = correction?.get("arguments") as? JsonObject
            val offeredRestarts = observations.count { data(it)?.get("restart_offered") == JsonPrimitive(true) }
            if (error?.text("schema") == "tools.inventory-restart.v1" &&
                error["permissions_changed"] == JsonPrimitive(false) &&
                error["restart_offered"] == JsonPrimitive(true) &&
                offeredRestarts == 1 &&
                correction?.text("tool") == TOOL &&
                arguments?.isEmpty() == true &&
                ToolArgumentValidator.errors(arguments, tool.inputSchema).isEmpty()
            ) {
                return ProviderEvent.ToolCall("catalog-restart-" + GitHubEvidenceBook.digest(stale.text("receipt_id")).take(20), tool.name, arguments)
            }
            return null
        }
        if (state.text("blocker") == "CATALOG_CONTRACT_CHANGED") {
            // One read-only refresh per current semantic contract. Existing operation
            // receipts remain independently revalidated against their own arguments.
            val signature = GitHubEvidenceBook.digest(
                catalogTools.filter { it.modelVisible && github(it.name) }.sortedBy { it.name }.joinToString("\n") {
                    "${it.name}:${it.contractVersion}:${ToolContractFingerprint.definition(it)}"
                }
            ).take(20)
            val callId = "catalog-contract-refresh-$signature"
            val arguments = JsonObject(emptyMap())
            if (observations.any { it.text("call_id") == callId || it.text("call_id").startsWith("$callId-") } || ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty()) return null
            return ProviderEvent.ToolCall(callId, tool.name, arguments)
        }
        if (state.text("status") == "BLOCKED") return null
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

    private fun contractChange(entry: JsonObject, definition: AgentToolDefinition): JsonObject? {
        val semantic = entry.text("semantic_schema_fingerprint").isNotBlank()
        val observed = entry.text(if (semantic) "semantic_schema_fingerprint" else "schema_fingerprint")
        val expected = if (semantic) ToolContractFingerprint.definition(definition) else GitHubEvidenceBook.digest(definition.contractSchema.toString())
        val observedVersion = entry.text("invocation_contract_version")
        val versionChanged = observedVersion.isNotBlank() && observedVersion != definition.contractVersion.toString()
        val schemaMatches = observed == expected || !semantic && observed == GitHubEvidenceBook.digest(definition.inputSchema.toString())
        if (schemaMatches && !versionChanged) return null
        val fingerprintValid = observed.matches(Regex("[a-fA-F0-9]{64}"))
        val versionValid = observedVersion.matches(Regex("[1-9][0-9]{0,9}\\.[0-9]{1,10}"))
        return buildJsonObject {
            put("name", definition.name)
            put("fingerprint_kind", if (semantic) "SEMANTIC_SCHEMA_V1" else "RAW_SCHEMA_V1")
            put("observed_schema_fingerprint", if (fingerprintValid) observed else "INVALID")
            put("schema_fingerprint_invalid", !fingerprintValid)
            put("expected_schema_fingerprint", expected)
            put("schema_changed", !schemaMatches)
            put(
                "observed_contract_version",
                if (versionValid) {
                    observedVersion
                } else if (observedVersion.isBlank()) {
                    "NOT_PROVIDED"
                } else {
                    "INVALID"
                }
            )
            put("contract_version_invalid", observedVersion.isNotBlank() && !versionValid)
            put("expected_contract_version", definition.contractVersion.toString())
            put("contract_version_changed", versionChanged)
        }
    }

    private fun github(name: String) = name.substringBefore("__").let { it.startsWith("github_") || it == "repository_graph" }
    private fun data(receipt: JsonObject) = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
    private fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
}
