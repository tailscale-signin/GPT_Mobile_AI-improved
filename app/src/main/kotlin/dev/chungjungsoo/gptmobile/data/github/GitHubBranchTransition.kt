package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Only a successful, repository-bound selection receipt can move a saved task. */
internal object GitHubBranchTransition {
    data class Destination(val branch: String, val commitSha: String)

    fun verified(repository: String, tool: String, arguments: JsonObject, result: JsonElement?, success: Boolean): Destination? {
        if (!success) return null
        val operation = tool.substringBefore("__")
        val action = arguments.text("action")
        if (!(operation == "github_commit" && action == "branch" || operation == "github_context" && action == "resolve")) return null
        if (operation == "github_commit" && arguments["select_created_branch"] == JsonPrimitive(false)) return null
        val envelope = result as? JsonObject ?: return null
        val data = envelope["data"] as? JsonObject ?: envelope
        val context = data["context"] as? JsonObject ?: data.takeIf { operation == "github_context" } ?: return null
        if ("${context.text("owner")}/${context.text("repo")}" != repository) return null
        val branch = context.text("ref")
        val sha = context.text("commit_sha")
        val requested = arguments.text(if (operation == "github_commit") "branch" else "ref")
        if (branch.isBlank() || branch != requested || !sha.matches(Regex("[a-fA-F0-9]{40}"))) return null
        if (operation == "github_commit" && (data["receipt_verified"] != JsonPrimitive(true) || data.text("head_sha") != sha)) return null
        return Destination(branch, sha)
    }

    fun fromEvidence(repository: String, receipt: JsonObject): Destination? {
        val arguments = receipt["arguments"] as? JsonObject ?: return null
        return verified(
            repository,
            receipt.text("tool"),
            arguments,
            receipt["result"],
            receipt["trusted"] == JsonPrimitive(true) && receipt["success"] == JsonPrimitive(true) && receipt["verification_eligible"] != JsonPrimitive(false)
        )
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
