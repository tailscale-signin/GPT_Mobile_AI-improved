package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Authorized candidates and currently executable schemas are different inventories. */
internal object GitHubChecklistSchedule {
    data class Plan(val next: ProviderEvent.ToolCall?, val snapshot: JsonObject)

    fun select(
        authorized: List<AgentToolDefinition>,
        advertised: List<AgentToolDefinition>,
        receipts: Map<String, JsonObject>,
        candidates: List<ProviderEvent.ToolCall>,
        planned: ProviderEvent.ToolCall?,
        catalog: ProviderEvent.ToolCall?,
        acquisitionAllowed: Boolean
    ): Plan {
        val unique = (listOfNotNull(planned) + candidates).distinctBy { it.name to it.arguments }
        val discovery = advertised.firstOrNull { it.name == GitHubAuthorizedCatalog.TOOL && it.modelVisible }
        val attempts = receipts.values.filter {
            it.text("tool") == GitHubAuthorizedCatalog.TOOL &&
                it["trusted"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false)
        }
        fun attempted(name: String) = attempts.lastOrNull { receipt ->
            ((receipt["arguments"] as? JsonObject)?.get("names") as? JsonArray)?.contains(JsonPrimitive(name)) == true
        }
        fun allowed(call: ProviderEvent.ToolCall) = acquisitionAllowed || planned?.let { it.name == call.name && it.arguments == call.arguments } == true
        fun executable(call: ProviderEvent.ToolCall) = advertised.any { it.modelVisible && it.name == call.name && GitHubSelfTestReads.valid(it, call.arguments) }
        val reasons = unique.associateWith { call ->
            when {
                authorized.none { it.modelVisible && it.name == call.name } -> "CURRENT_AUTHORIZATION_REQUIRED"
                authorized.none { it.modelVisible && it.name == call.name && GitHubSelfTestReads.valid(it, call.arguments) } -> "ACTION_ARGUMENTS_REJECTED"
                !allowed(call) -> "ACQUISITION_RESERVE_ACTIVE"
                executable(call) -> "READY"
                !acquisitionAllowed -> "SCHEMA_ACQUISITION_RESERVE_ACTIVE"
                discovery == null -> "DISCOVERY_TOOL_NOT_ADVERTISED"
                attempted(call.name)?.get("success") == JsonPrimitive(true) -> "TOOL_NOT_ADVERTISED_AFTER_DISCOVERY"
                attempted(call.name) != null -> "SCHEMA_DISCOVERY_FAILED"
                else -> "EXACT_SCHEMA_DISCOVERY_REQUIRED"
            }
        }
        val names = unique.filter { reasons[it] == "EXACT_SCHEMA_DISCOVERY_REQUIRED" }.map { it.name }.distinct().take(12)
        val arguments = buildJsonObject { put("names", JsonArray(names.map(::JsonPrimitive))) }
        val discoveryAllowed = discovery != null && names.isNotEmpty() && ToolArgumentValidator.errors(arguments, discovery.inputSchema).isEmpty()
        val discover = if (discoveryAllowed) {
            ProviderEvent.ToolCall("checklist-discovery-" + GitHubEvidenceBook.digest(arguments.toString()).take(20), requireNotNull(discovery).name, arguments)
        } else {
            null
        }
        val ready = unique.filter { reasons[it] == "READY" }
        val cleanup = ready.firstOrNull {
            it.name.githubOperationFamily() == "github_commit" && it.arguments["action"] in setOf(JsonPrimitive("delete_branch"), JsonPrimitive("delete_fixture_branch"))
        }
        val next = cleanup ?: catalog?.takeIf { acquisitionAllowed && executable(it) } ?: ready.firstOrNull() ?: discover
        val snapshot = buildJsonObject {
            put("schema", "github.checklist-schedule.v1")
            put("receipt_set_digest", receiptSetDigest(receipts))
            put("authorized_tool_count", authorized.count { it.modelVisible })
            put("advertised_tool_count", advertised.count { it.modelVisible })
            put("candidate_count", unique.size)
            put("admitted_candidate_count", ready.size)
            put("blocked_candidate_count", unique.size - ready.size)
            put("acquisition_allowed", acquisitionAllowed)
            put("catalog_denominator_unchanged", true)
            next?.let { put("next_call", call(it)) }
            put(
                "candidates",
                JsonArray(
                    unique.map { candidate ->
                        val reason = if (reasons[candidate] == "EXACT_SCHEMA_DISCOVERY_REQUIRED" && !discoveryAllowed) "DISCOVERY_SCHEMA_REJECTED" else requireNotNull(reasons[candidate])
                        buildJsonObject {
                            put("tool", candidate.name)
                            put("arguments", candidate.arguments)
                            put("admission", reason)
                            put("selected", next?.let { it.name == candidate.name && it.arguments == candidate.arguments } == true)
                            put("discovery_selected", discover != null && next == discover && candidate.name in names)
                            attempted(candidate.name)?.let { receipt ->
                                receipt["receipt_id"]?.let { put("discovery_receipt_id", it) }
                                receipt["error_code"]?.let { put("discovery_error_code", it) }
                            }
                        }
                    }
                )
            )
        }
        return Plan(next, snapshot)
    }

    fun receiptSetDigest(receipts: Map<String, JsonObject>): String = GitHubEvidenceBook.digest(JsonObject(receipts.toSortedMap()).toString())

    fun current(plan: JsonObject?, receipts: Map<String, JsonObject>): JsonObject? = plan?.takeIf {
        it["schema"] == JsonPrimitive("github.checklist-schedule.v1") && it["receipt_set_digest"] == JsonPrimitive(receiptSetDigest(receipts))
    }

    private fun call(value: ProviderEvent.ToolCall): JsonObject = buildJsonObject {
        put("tool", value.name)
        put("arguments", value.arguments)
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
