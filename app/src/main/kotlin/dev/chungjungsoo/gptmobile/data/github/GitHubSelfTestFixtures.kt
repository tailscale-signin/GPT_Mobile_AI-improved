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

/** A disposable, unchanged branch tests authorized writes without merging unrelated work. */
internal object GitHubSelfTestFixtures {
    private fun root(receipt: JsonObject): JsonObject? = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun args(receipt: JsonObject) = receipt["arguments"] as? JsonObject

    fun next(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, allowCreate: Boolean = true): ProviderEvent.ToolCall? {
        if (GitHubChecklistScope.readOnly(task) || !Regex("(?i)\\b(?:all|full|complete|writes|mutations|fixtures)\\b").containsMatchIn(task)) return null
        val observed = receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) }
        val source = observed.firstOrNull { it.text("tool").substringBefore("__") == "github_context" && args(it)?.text("action") == "status" && it["success"] == JsonPrimitive(true) } ?: return null
        val status = root(source) ?: return null
        val branch = status.text("branch")
        val sha = status.text("commit_sha")
        val default = (status["repository"] as? JsonObject)?.text("default_branch")
        // Existing deletion policy requires the fixture to be merged into default.
        if (branch != default || !sha.matches(Regex("[a-fA-F0-9]{40}"))) return null
        val fixture = "codex-self-test/" + GitHubEvidenceBook.digest(source.text("binding") + source.text("receipt_id")).take(20)
        val creation = observed.firstOrNull { it.text("tool").substringBefore("__") == "github_commit" && args(it)?.text("action") == "branch" && args(it)?.text("branch") == fixture }
        val recipes = buildList<Pair<String, JsonObject>> {
            if (creation == null) {
                if (!allowCreate) return null
                // Verify every cleanup operation is authorized before creating
                // a fixture that this session might otherwise be unable to remove.
                val cleanup = tools.firstOrNull {
                    it.name.substringBefore("__") == "github_commit" &&
                        ToolArgumentValidator.errors(
                            buildJsonObject {
                                put("action", "delete_branch")
                                put("branch", fixture)
                                put("expected_head_sha", sha)
                            },
                            it.inputSchema
                        ).isEmpty()
                } ?: return null
                if (!cleanup.modelVisible) return null
                add(
                    "github_commit" to buildJsonObject {
                        put("action", "branch")
                        put("branch", fixture)
                        put("from_branch", branch)
                        put("expected_head_sha", sha)
                        put("select_created_branch", false)
                    }
                )
            } else if (creation["success"] == JsonPrimitive(true) && root(creation)?.get("created") == JsonPrimitive(true) && root(creation)?.get("receipt_verified") == JsonPrimitive(true) && root(creation)?.text("head_sha") == sha) {
                add(
                    "github_context" to buildJsonObject {
                        put("action", "resolve")
                        put("ref", branch)
                        put("refresh", true)
                    }
                )
                add(
                    "github_context" to buildJsonObject {
                        put("action", "branch_dependencies")
                        put("branch", fixture)
                    }
                )
                add(
                    "github_commit" to buildJsonObject {
                        put("action", "delete_branch")
                        put("branch", fixture)
                        put("expected_head_sha", sha)
                    }
                )
            }
        }
        for ((name, arguments) in recipes) {
            val tool = tools.firstOrNull { it.name.substringBefore("__") == name && it.modelVisible } ?: return null
            // A rejected or ambiguous mutation is a blocker, never permission to retry it.
            val prior = observed.lastOrNull { it.text("tool") == tool.name && args(it) == arguments }
            if (prior != null) {
                if (prior["success"] != JsonPrimitive(true)) return null
                continue
            }
            if (ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty()) return null
            return ProviderEvent.ToolCall("fixture-" + GitHubEvidenceBook.digest("${tool.name}:$arguments").take(24), tool.name, arguments)
        }
        return null
    }

    private fun eligible(receipt: JsonObject): Boolean = receipt["trusted"] == JsonPrimitive(true) &&
        receipt["verification_eligible"] != JsonPrimitive(false) &&
        receipt["success"] == JsonPrimitive(true)

    /** Both receipts must prove the same owned, unchanged branch in the same task/repository. */
    fun checkpoint(receipts: Map<String, JsonObject>): JsonArray = JsonArray(
        receipts.values.filter {
            it["trusted"] == JsonPrimitive(true) && it.text("tool").substringBefore("__") == "github_commit" && args(it)?.text("action") == "branch" && args(it)?.text("branch")?.startsWith("codex-self-test/") == true
        }.map { creation ->
            val branch = args(creation)?.text("branch").orEmpty()
            val sha = args(creation)?.text("expected_head_sha").orEmpty()
            val source = receipts.values.firstOrNull { receipt ->
                eligible(receipt) &&
                    receipt.text("tool").substringBefore("__") == "github_context" &&
                    args(receipt)?.text("action") == "status" &&
                    receipt["binding"] == creation["binding"] &&
                    receipt["repository"] == creation["repository"] &&
                    root(receipt)?.text("commit_sha") == sha &&
                    root(receipt)?.text("branch") == args(creation)?.text("from_branch") &&
                    (root(receipt)?.get("repository") as? JsonObject)?.text("default_branch") == args(creation)?.text("from_branch") &&
                    branch == "codex-self-test/" + GitHubEvidenceBook.digest(receipt.text("binding") + receipt.text("receipt_id")).take(20)
            }
            val owned = eligible(creation) &&
                source != null &&
                sha.matches(Regex("[a-fA-F0-9]{40}")) &&
                root(creation)?.get("created") == JsonPrimitive(true) &&
                root(creation)?.get("receipt_verified") == JsonPrimitive(true) &&
                root(creation)?.text("head_sha") == sha &&
                root(creation)?.text("branch") == branch
            val observations = receipts.values.toList()
            val cleanup = observations.drop(observations.indexOf(creation) + 1).lastOrNull {
                it["trusted"] == JsonPrimitive(true) &&
                    it.text("tool").substringBefore("__") == "github_commit" &&
                    args(it)?.text("action") == "delete_branch" &&
                    args(it)?.text("branch") == branch &&
                    it["binding"] == creation["binding"] &&
                    it["repository"] == creation["repository"]
            }
            val cleaned = owned &&
                cleanup != null &&
                eligible(cleanup) &&
                args(cleanup)?.text("expected_head_sha") == sha &&
                root(cleanup)?.get("deleted") == JsonPrimitive(true) &&
                root(cleanup)?.get("absence_verified") == JsonPrimitive(true) &&
                root(cleanup)?.text("branch") == branch &&
                root(cleanup)?.text("observed_head_sha") == sha
            buildJsonObject {
                put("branch", branch)
                put("ownership_verified", owned)
                put("final_state_verified", cleaned)
                put("response_accepted", cleanup?.let { root(it)?.get("deleted") == JsonPrimitive(true) } ?: false)
                put("creation_receipt", creation["receipt_id"] ?: JsonPrimitive("unknown"))
                put(
                    "cleanup_status",
                    if (!owned) {
                        "OWNERSHIP_NOT_ESTABLISHED"
                    } else if (cleaned) {
                        "CLEANED"
                    } else {
                        "PENDING_OR_BLOCKED"
                    }
                )
                cleanup?.get("receipt_id")?.let { put("cleanup_receipt", it) }
                put("mutation_scope", "Unchanged disposable branch only. Content commits, releases, merges and workflow mutations require a separately authorized fixture plan.")
            }
        }
    )

    fun complete(receipts: Map<String, JsonObject>): Boolean {
        val fixtures = checkpoint(receipts).filterIsInstance<JsonObject>()
        if (fixtures.isEmpty() || fixtures.any { it.text("cleanup_status") != "CLEANED" }) return false
        val proved = fixtures.flatMap { listOfNotNull(it["creation_receipt"], it["cleanup_receipt"]) }.toSet()
        return receipts.values.filter {
            it["trusted"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false) &&
                it.text("tool").substringBefore("__") == "github_commit" &&
                args(it)?.text("action") in setOf("branch", "delete_branch")
        }.all { eligible(it) && it["receipt_id"] in proved }
    }
}
