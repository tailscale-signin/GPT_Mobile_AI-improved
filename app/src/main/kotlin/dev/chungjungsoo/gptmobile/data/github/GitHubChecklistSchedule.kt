package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.GitHubActionSchema
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
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
        fun authorizedCall(call: ProviderEvent.ToolCall) = authorized.any { tool ->
            tool.modelVisible && tool.name == call.name &&
                (
                    GitHubSelfTestReads.valid(tool, call.arguments) ||
                        (
                            // An authorized read action can be schema-discovered before its
                            // required selectors are known. Discovery is read-only and does not
                            // dispatch the incomplete candidate.
                            GitHubAuthorizationBoundary.isReplayableRead(call.name, call.arguments) &&
                                call.arguments.text("action").isNotBlank() &&
                                GitHubActionSchema.project(tool, call.arguments.text("action")) != null
                            )
                    )
        }
        fun executable(call: ProviderEvent.ToolCall) = advertised.any { it.modelVisible && it.name == call.name && GitHubSelfTestReads.valid(it, call.arguments) }
        fun advertisedRead(call: ProviderEvent.ToolCall): ProviderEvent.ToolCall {
            if (executable(call) || !authorizedCall(call) || !GitHubAuthorizationBoundary.isReplayableRead(call.name, call.arguments)) return call
            val facade = advertised.firstOrNull { tool ->
                tool.modelVisible &&
                    call.name.githubReadFacadeMatches(tool.name) &&
                    GitHubSelfTestReads.valid(tool, call.arguments) &&
                    authorizedCall(call.copy(name = tool.name))
            } ?: return call
            return call.copy(name = facade.name)
        }
        val admittedPlanned = planned?.let(::advertisedRead)
        val unique = (listOfNotNull(planned) + candidates).map(::advertisedRead).distinctBy { it.name to it.arguments }
        val discovery = advertised.firstOrNull { it.name == GitHubAuthorizedCatalog.TOOL && it.modelVisible }
        val attempts = receipts.filterValues {
            it.text("tool") == GitHubAuthorizedCatalog.TOOL &&
                it["trusted"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false)
        }
        fun attemptsFor(name: String) = attempts.entries.filter { (_, receipt) ->
            ((receipt["arguments"] as? JsonObject)?.get("names") as? JsonArray)?.contains(JsonPrimitive(name)) == true
        }
        fun attempted(call: ProviderEvent.ToolCall) = attemptsFor(call.name).lastOrNull { (_, receipt) ->
            val action = (receipt["arguments"] as? JsonObject)?.text("action").orEmpty()
            action.isBlank() || action == call.arguments.text("action")
        }?.value
        val supportsAction = (discovery?.inputSchema?.get("properties") as? JsonObject)?.containsKey("action") == true
        val scopedArguments = unique.associateWith { call ->
            val action = call.arguments.text("action")
            val source = authorized.firstOrNull { it.name == call.name && it.modelVisible }
            buildJsonObject {
                put("names", JsonArray(listOf(JsonPrimitive(call.name))))
                if (supportsAction && action.isNotBlank() && source != null && GitHubActionSchema.project(source, action) != null) put("action", action)
            }
        }
        val lastScoped = unique.associateWith { call -> attemptsFor(call.name).lastOrNull { it.value["arguments"] == scopedArguments.getValue(call) } }
        // A different completed read can displace the schema before another action, page or PR needs it.
        // Bind that reacquisition to the observed progress so the evidence store records a new call exactly once.
        val consumedAt = unique.associateWith { call ->
            val previous = lastScoped[call] ?: return@associateWith null
            if (previous.value["success"] != JsonPrimitive(true) || !GitHubAuthorizationBoundary.isReplayableRead(call.name, call.arguments)) return@associateWith null
            receipts.entries.asSequence().dropWhile { it.key != previous.key }.drop(1).lastOrNull { (_, receipt) ->
                val args = receipt["arguments"] as? JsonObject
                receipt["trusted"] == JsonPrimitive(true) &&
                    receipt["success"] == JsonPrimitive(true) &&
                    receipt["verification_eligible"] != JsonPrimitive(false) &&
                    (receipt.text("tool") == call.name || call.name.githubReadFacadeMatches(receipt.text("tool"))) &&
                    args != null &&
                    args != call.arguments &&
                    GitHubAuthorizationBoundary.isReplayableRead(receipt.text("tool"), args) &&
                    !GitHubActionIdentity.matches(receipt.text("tool"), call.arguments, receipt)
            }?.key
        }
        fun scopedAttempted(call: ProviderEvent.ToolCall) = lastScoped[call] != null && consumedAt[call] == null
        fun budgetExcluded(receipt: JsonObject?, name: String): Boolean {
            if (receipt == null || receipt["success"] != JsonPrimitive(true)) return false
            val result = GitHubInventoryObservation.planningRoot(receipt) ?: return false
            return listOf("schema_budget_excluded", "context_budget_excluded").any { key ->
                (result[key] as? JsonArray)?.contains(JsonPrimitive(name)) == true
            }
        }
        fun allowed(call: ProviderEvent.ToolCall) = acquisitionAllowed || admittedPlanned?.let { it.name == call.name && it.arguments == call.arguments } == true
        val reasons = unique.associateWith { call ->
            val prior = attempted(call)
            val priorNames = (prior?.get("arguments") as? JsonObject)?.get("names") as? JsonArray
            val targetedRecovery = (priorNames?.size ?: 0) > 1 || consumedAt[call] != null
            val projectedElsewhere = attemptsFor(call.name).lastOrNull()?.value?.let { receipt ->
                val action = (receipt["arguments"] as? JsonObject)?.text("action").orEmpty()
                receipt["success"] == JsonPrimitive(true) && action.isNotBlank() && action != call.arguments.text("action")
            } == true
            when {
                authorized.none { it.modelVisible && it.name == call.name } -> "CURRENT_AUTHORIZATION_REQUIRED"
                !authorizedCall(call) -> "ACTION_ARGUMENTS_REJECTED"
                !allowed(call) -> "ACQUISITION_RESERVE_ACTIVE"
                executable(call) -> "READY"
                !acquisitionAllowed -> "SCHEMA_ACQUISITION_RESERVE_ACTIVE"
                discovery == null -> "DISCOVERY_TOOL_NOT_ADVERTISED"
                budgetExcluded(prior, call.name) && !scopedAttempted(call) -> "SCHEMA_BUDGET_RECOVERY_REQUIRED"
                prior?.get("success") == JsonPrimitive(true) && targetedRecovery && !scopedAttempted(call) -> "ACTION_SCHEMA_DISCOVERY_REQUIRED"
                projectedElsewhere && supportsAction && !scopedAttempted(call) -> "ACTION_SCHEMA_DISCOVERY_REQUIRED"
                budgetExcluded(prior, call.name) -> "SCHEMA_BUDGET_RECOVERY_EXHAUSTED"
                prior?.get("success") == JsonPrimitive(true) -> "TOOL_NOT_ADVERTISED_AFTER_DISCOVERY"
                prior != null -> "SCHEMA_DISCOVERY_FAILED"
                else -> "EXACT_SCHEMA_DISCOVERY_REQUIRED"
            }
        }
        // A soft-window exclusion is not a permission denial. Retry only this
        // exact action once, so a large family cannot starve every ready read.
        val scoped = unique.firstOrNull { reasons[it] in setOf("SCHEMA_BUDGET_RECOVERY_REQUIRED", "ACTION_SCHEMA_DISCOVERY_REQUIRED") }
        val names = scoped?.let { listOf(it.name) } ?: unique.filter { reasons[it] == "EXACT_SCHEMA_DISCOVERY_REQUIRED" }.map { it.name }.distinct().take(12)
        val arguments = scoped?.let(scopedArguments::getValue) ?: buildJsonObject { put("names", JsonArray(names.map(::JsonPrimitive))) }
        val discoveryAllowed = discovery != null && names.isNotEmpty() && ToolArgumentValidator.errors(arguments, discovery.inputSchema).isEmpty()
        val discover = if (discoveryAllowed) {
            val checkpoint = scoped?.let { consumedAt[it] }
            val identity = arguments.toString() + checkpoint?.let { "|after:$it" }.orEmpty()
            ProviderEvent.ToolCall("checklist-discovery-" + GitHubEvidenceBook.digest(identity).take(20), requireNotNull(discovery).name, arguments)
        } else {
            null
        }
        val ready = unique.filter { reasons[it] == "READY" }
        val cleanup = ready.firstOrNull {
            it.name.githubOperationFamily() == "github_commit" && it.arguments["action"] in setOf(JsonPrimitive("delete_branch"), JsonPrimitive("delete_fixture_branch"))
        }
        val cleanupPrerequisite = admittedPlanned?.takeIf { it in ready && it.callId.startsWith("fixture-") && GitHubChecklistBudget.cleanupReserve(receipts) > 0 }
        val next = cleanup ?: cleanupPrerequisite ?: ready.firstOrNull() ?: discover ?: catalog?.takeIf { acquisitionAllowed && executable(it) }
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
                        val reason = if (reasons[candidate] in setOf("EXACT_SCHEMA_DISCOVERY_REQUIRED", "SCHEMA_BUDGET_RECOVERY_REQUIRED", "ACTION_SCHEMA_DISCOVERY_REQUIRED") && !discoveryAllowed) "DISCOVERY_SCHEMA_REJECTED" else requireNotNull(reasons[candidate])
                        buildJsonObject {
                            put("tool", candidate.name)
                            put("arguments", candidate.arguments)
                            put("admission", reason)
                            put("selected", next?.let { it.name == candidate.name && it.arguments == candidate.arguments } == true)
                            put("discovery_selected", discover != null && next == discover && candidate.name in names && (scoped == null || candidate.arguments["action"] == scoped.arguments["action"]))
                            attempted(candidate)?.let { receipt ->
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
