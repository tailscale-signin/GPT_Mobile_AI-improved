package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Creation is accepted from its exact destination proof, never from an unrelated workspace read. */
internal object GitHubRepositoryAcceptance {
    /** Additional requested work keeps its own implementation/test obligation. */
    fun requiresAdditionalEvidence(requirement: String): Boolean {
        val text = GitHubTaskContinuity.request(requirement).replace(Regex("`[^`]*`|\"[^\"]*\"|'[^']*'"), " ")
        return Regex("(?i)\\b(?:implement|fix|repair|configure|set up|setup|build|test|deploy|publish|install|rewrite|refactor|document|enable|disable|rename|delete|remove|update|edit|add)\\b").containsMatchIn(text) ||
            Regex("(?i)\\b(?:and|then|also|plus)\\s+(?:create|fork|import|clone|open|close|merge|push|commit|make|ensure)\\b").containsMatchIn(text) ||
            Regex("(?i)\\b(?:with|containing|including)\\s+(?:(?:a|an|the)\\s+)?(?:readme|license|topics?|branch protection|application|app|service|feature|code|tests?)\\b").containsMatchIn(text)
    }
    fun evaluate(requirement: String, acceptance: JsonObject, receipts: Map<String, JsonObject>, now: Long): GitHubOperationAcceptance.Assessment? {
        val operation = GitHubRepositoryLifecycle.intent(requirement) ?: return null
        val linked = (acceptance["operation_receipts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val selected = linked.mapNotNull(receipts::get).filter { receipt ->
            receipt["trusted"] == JsonPrimitive(true) &&
                receipt["verification_eligible"] != JsonPrimitive(false) &&
                receipt["success"] == JsonPrimitive(true) &&
                GitHubEvidenceOrigin.of(receipt) == "LIVE" &&
                (receipt["observed_at_ms"] as? JsonPrimitive)?.longOrNull?.let { it in (now - GitHubEvidencePolicy.MAX_AGE_MS)..now } == true
        }
        for (receipt in selected) {
            val arguments = receipt["arguments"] as? JsonObject ?: continue
            val native = GitHubOperationTool.nativeArguments(receipt.text("tool"), arguments) ?: arguments.takeIf { receipt.text("tool").substringBefore("__") == "github" } ?: continue
            if (native.text("action") != operation && !(native.text("action") == "repository_lifecycle_status" && native.text("operation") == operation)) continue
            val raw = receipt["result"] as? JsonObject ?: continue
            val result = raw["data"] as? JsonObject ?: raw
            if (result.text("operation") != operation || result.text("status") != "VERIFIED" || result["remote_state_verified"] != JsonPrimitive(true)) continue
            val destination = "${native.text("destination_owner")}/${native.text("destination_repo")}"
            val retainedGatewayStatus = native.text("action") == "repository_lifecycle_status" && native.text("operation_id").matches(Regex("repoop_[a-f0-9]{32}")) && result.text("operation_id") == native.text("operation_id")
            if (operation != "clone_repository" && !retainedGatewayStatus && (!result.text("destination_repository").equals(destination, true) || result["visibility"] != native["visibility"])) continue
            val source = "${native.text("source_owner")}/${native.text("source_repo")}"
            if (operation != "create_repository" && native.text("action") != "repository_lifecycle_status" && !result.text("source_repository").equals(source, true)) continue
            if (operation != "clone_repository" && (result["repository_id"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } != true) continue
            val explicit = GitHubTargetPolicy.readSources(requirement)
            if (explicit.isNotEmpty() && operation != "create_repository" && result.text("source_repository").lowercase(java.util.Locale.ROOT) !in explicit) continue
            if (operation == "create_repository" && explicit.isNotEmpty() && destination.lowercase(java.util.Locale.ROOT) !in explicit) continue
            val requestedDestination = Regex("(?i)\\b(?:to|into|as)\\s+(?:(?:a|the|new|independent|repository|repo)\\s+)*(?:https://github\\.com/)?([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(?![/A-Za-z0-9_.-])").find(requirement)?.groupValues?.get(1)?.removeSuffix(".git")
            if (requestedDestination != null && !result.text("destination_repository").equals(requestedDestination, true)) continue
            val requestedOwner = Regex("(?i)\\b(?:under|owned by|in (?:the )?(?:account|organization|org))\\s+[`\"']?([A-Za-z0-9][A-Za-z0-9-]{0,38})(?![/A-Za-z0-9_.-])").find(requirement)?.groupValues?.get(1)
            if (requestedOwner != null && !result.text("destination_repository").substringBefore('/').equals(requestedOwner, true)) continue
            val named = Regex("(?i)\\b(?:named|called)\\s+[`\"']?([A-Za-z0-9_.-]+)").find(requirement)?.groupValues?.get(1)
            if (named != null && !result.text("destination_repository").substringAfter('/').equals(named, true)) continue
            if (Regex("(?i)\\bprivate\\b").containsMatchIn(requirement) && result["visibility"] != JsonPrimitive("private")) continue
            if (Regex("(?i)\\bpublic\\b").containsMatchIn(requirement) && result["visibility"] != JsonPrimitive("public")) continue
            if (operation == "import_repository") {
                val history = native.text("copy_mode") == "history" || Regex("(?i)\\b(?:history|all branches|all tags|mirror)\\b").containsMatchIn(requirement)
                if (history && (result["history_preserved"] != JsonPrimitive(true) || (result["ref_count"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } != true || !result.text("refs_sha256").matches(Regex("[a-f0-9]{64}")))) continue
                if (!history && (result.text("source_tree_sha").isBlank() || result["source_tree_sha"] != result["destination_tree_sha"])) continue
            }
            if (operation == "clone_repository" && (result.text("workspace_id").isBlank() || !result.text("checked_out_sha").matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) || result["checked_out_sha"] != result["source_commit_sha"])) continue
            return GitHubOperationAcceptance.Assessment(GitHubAcceptanceLedger.State.PASS, "The exact repository operation and requested copy semantics were verified from its retained identity and fresh destination proof", result.text("destination_repository").takeIf(String::isNotBlank))
        }
        return GitHubOperationAcceptance.Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, "Link a fresh verified repository-lifecycle receipt for the exact source, destination, visibility and copy mode; an accepted create/fork request alone is not completion")
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
