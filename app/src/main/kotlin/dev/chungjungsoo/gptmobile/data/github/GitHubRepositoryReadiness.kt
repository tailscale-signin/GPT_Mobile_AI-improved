package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Lifecycle status follows a retained creation/transfer, not the selected repository's source tree. */
internal object GitHubRepositoryReadiness {
    data class Prerequisites(val arguments: JsonObject?, val conditional: Boolean = false, val requiredInputs: List<String> = emptyList())

    fun read(action: String, tools: List<AgentToolDefinition>, receipts: List<JsonObject>): Prerequisites? {
        if (action !in setOf("capabilities", "status")) return null
        val arguments = if (action == "capabilities") {
            buildJsonObject { put("action", action) }
        } else {
            val receipt = receipts.lastOrNull {
                it["trusted"] == JsonPrimitive(true) &&
                    it["verification_eligible"] != JsonPrimitive(false) &&
                    it["dispatched"] != JsonPrimitive(false) &&
                    it.text("tool").substringBefore("__") == "github_repository" &&
                    (it["arguments"] as? JsonObject)?.text("action") in setOf("create", "fork", "import", "clone") &&
                    GitHubInventoryObservation.planningRoot(it)?.get("dispatched") != JsonPrimitive(false)
            } ?: return Prerequisites(null, conditional = true)
            val original = receipt.getValue("arguments") as JsonObject
            val result = GitHubInventoryObservation.planningRoot(receipt)
            val operation = original.text("action") + "_repository"
            if (result?.text("schema")?.takeIf(String::isNotBlank)?.let { it != "github.repository-lifecycle.v1" } == true) {
                return Prerequisites(null, requiredInputs = listOf("A retained repository lifecycle receipt for operation=$operation"))
            }
            if (result?.text("operation")?.takeIf(String::isNotBlank)?.let { it != operation } == true) {
                return Prerequisites(null, requiredInputs = listOf("A retained lifecycle receipt matching operation=$operation"))
            }
            val destination = "${original.text("destination_owner")}/${original.text("destination_repo")}"
            val observedDestination = result?.text("destination_repository")?.takeIf(String::isNotBlank)
            if (original.text("action") != "clone" &&
                (observedDestination?.equals(destination, ignoreCase = true) == false || result?.get("visibility")?.let { it != original["visibility"] } == true)
            ) {
                return Prerequisites(null, requiredInputs = listOf("A lifecycle receipt matching the original destination_owner, destination_repo and visibility"))
            }
            val gateway = original.text("action") == "clone" || original.text("action") == "import" && original.text("copy_mode") == "history"
            buildJsonObject {
                put("action", action)
                put("operation", operation)
                if (gateway) {
                    result?.get("operation_id")?.let { put("operation_id", it) }
                } else {
                    listOf("destination_owner", "destination_repo", "owner_type", "visibility").forEach { key -> original[key]?.let { put(key, it) } }
                    (result?.get("repository_id") as? JsonPrimitive)?.takeIf { (it.longOrNull ?: 0) > 0 }?.let { put("expected_repository_id", it) }
                }
            }.also { candidate ->
                if (gateway && candidate.text("operation_id").isBlank()) return Prerequisites(null, requiredInputs = listOf("operation_id from the retained gateway lifecycle receipt"))
            }
        }
        val nativeErrors = GitHubOperationArguments.errors("github_repository", arguments)
        val schemas = tools.filter { it.modelVisible && it.name.substringBefore("__") == "github_repository" }
        val schemaErrors = schemas.map { ToolArgumentValidator.errors(arguments, it.inputSchema) }.minByOrNull { it.size }
            ?: listOf("Current authorization for github_repository action=$action")
        // Conditional schema alternatives can mention the other transport's ID.
        // Report this retained operation's exact native prerequisite first.
        val errors = nativeErrors.ifEmpty { schemaErrors }.distinct()
        return Prerequisites(arguments.takeIf { errors.isEmpty() }, requiredInputs = errors)
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
