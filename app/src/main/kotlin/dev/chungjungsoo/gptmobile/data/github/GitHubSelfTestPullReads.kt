package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A PR read follows its own observed head, independently of the selected workspace commit. */
internal object GitHubSelfTestPullReads {
    private val reads = setOf("headers", "context", "comments", "reviews", "threads")
    private val revision = Regex("[a-fA-F0-9]{40}")
    data class Selection(val row: JsonObject, val receipt: JsonObject)

    fun select(receipts: List<JsonObject>): Selection? {
        val selected = GitHubSelfTestPrerequisites.selected(receipts)
        val eligible = (selected?.receipts ?: receipts).withIndex().filter { (_, receipt) ->
            receipt["trusted"] == JsonPrimitive(true) &&
                receipt["success"] == JsonPrimitive(true) &&
                receipt["verification_eligible"] != JsonPrimitive(false) &&
                receipt["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                receipt.text("tool").githubOperationFamily() == "github_pr"
        }.asReversed()
        val listed = eligible.flatMap { (index, receipt) ->
            if ((receipt["arguments"] as? JsonObject)?.text("action") != "list") return@flatMap emptyList()
            val root = GitHubInventoryObservation.planningRoot(receipt) ?: return@flatMap emptyList()
            ((root["pull_requests"] ?: root["items"]) as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { row ->
                number(row["number"])?.let { Triple(index, receipt, JsonObject(row + ("number" to it))) }
            }
        }
        val initial = (listed.filter { it.third.text("state") == "open" } + listed).firstOrNull()
            ?: eligible.firstNotNullOfOrNull { (index, receipt) -> detail(receipt)?.let { Triple(index, receipt, it) } }
            ?: return null
        // Preserve the selected PR and connection. Only a newer observation of that
        // same resource can replace its head; an old list is not a revision lease.
        val latest = eligible.firstNotNullOfOrNull { (index, receipt) ->
            if (index < initial.first || !sameScope(receipt, initial.second) || receipt.text("tool") != initial.second.text("tool")) return@firstNotNullOfOrNull null
            val row = if ((receipt["arguments"] as? JsonObject)?.text("action") == "list") {
                listed.firstOrNull { it.first == index && it.third["number"] == initial.third["number"] }?.third
            } else {
                detail(receipt)?.takeIf { it["number"] == initial.third["number"] }
            }
            row?.takeIf { head(it) != null }?.let { Selection(it, receipt) }
        }
        return latest ?: Selection(initial.third, initial.second)
    }

    /** At most one fresh-header read per PR and one corrected read per failed action. */
    fun recover(tools: List<AgentToolDefinition>, receipts: List<JsonObject>, selected: Selection?): List<ProviderEvent.ToolCall> {
        if (selected == null) return emptyList()
        val source = selected.receipt
        if (source.text("binding").isBlank() || source.text("repository").isBlank()) return emptyList()
        val number = selected.row["number"] ?: return emptyList()
        val tool = tools.singleOrNull { it.modelVisible && it.name == source.text("tool") } ?: return emptyList()
        val observations = receipts.filter { receipt ->
            receipt["trusted"] == JsonPrimitive(true) &&
                receipt["verification_eligible"] != JsonPrimitive(false) &&
                receipt["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                sameScope(receipt, source) &&
                receipt.text("tool") == tool.name
        }
        return reads.mapNotNull { action ->
            val attempts = observations.filter {
                val args = it["arguments"] as? JsonObject
                args?.text("action") == action && args["pull_number"] == number && !it.text("call_id").startsWith("pr-head-refresh-")
            }
            val failed = attempts.lastOrNull() ?: return@mapNotNull null
            if (failed["success"] != JsonPrimitive(false) ||
                failed.text("error_code") != "GITHUB_PR_HEAD_CHANGED" ||
                failed.text("call_id").startsWith("pr-head-retry-") ||
                GitHubActionIdentity.digest(failed) == null
            ) {
                return@mapNotNull null
            }
            val previous = failed["arguments"] as? JsonObject ?: return@mapNotNull null
            val expected = (previous["expected_head_sha"] as? JsonPrimitive)?.takeIf { it.isString && revision.matches(it.content) } ?: return@mapNotNull null
            val key = GitHubEvidenceBook.digest("${source.text("binding")}|${source.text("repository")}|${tool.name}|$number|$action").take(24)
            if (observations.any { it.text("call_id") == "pr-head-retry-$key" || it.text("call_id").startsWith("pr-head-retry-$key-") }) return@mapNotNull null
            val current = head(selected.row)
            val call = if (current != null && !current.content.equals(expected.content, true)) {
                ProviderEvent.ToolCall("pr-head-retry-$key", tool.name, JsonObject(previous + ("expected_head_sha" to current)))
            } else {
                val id = "pr-head-refresh-" + GitHubEvidenceBook.digest("${source.text("binding")}|${source.text("repository")}|${tool.name}|$number").take(24)
                if (observations.any { it.text("call_id") == id || it.text("call_id").startsWith("$id-") }) return@mapNotNull null
                val arguments = previous.filterKeys { it in setOf("owner", "repo", "pull_number") } +
                    mapOf("action" to JsonPrimitive("headers"), "verify_live" to JsonPrimitive(true))
                ProviderEvent.ToolCall(id, tool.name, JsonObject(arguments))
            }
            call.takeIf { GitHubAuthorizationBoundary.isReplayableRead(it.name, it.arguments) && GitHubSelfTestReads.valid(tool, it.arguments) }
        }.distinctBy { it.callId }
    }

    /** An earlier successful lease does not complete a read of the newly observed head. */
    fun observedAt(receipt: JsonObject, revision: JsonPrimitive): Boolean {
        val observed = detail(receipt)?.let(::head)
            ?: ((receipt["arguments"] as? JsonObject)?.get("expected_head_sha") as? JsonPrimitive)?.takeIf { it.isString && this.revision.matches(it.content) }
        return observed?.content?.equals(revision.content, true) == true
    }

    private fun detail(receipt: JsonObject): JsonObject? {
        val args = receipt["arguments"] as? JsonObject ?: return null
        if (args.text("action") !in reads + "create") return null
        val root = GitHubInventoryObservation.planningRoot(receipt) ?: return null
        val pull = root["pull_request"] as? JsonObject
        val returned = number(pull?.get("number") ?: root["pull_number"] ?: root["number"])
        val requested = number(args["pull_number"])
        if (returned != null && requested != null && returned != requested) return null
        val id = returned ?: requested ?: return null
        return JsonObject((pull ?: root).filterKeys { it in setOf("head", "head_sha", "state") } + ("number" to id))
    }

    private fun number(value: JsonElement?): JsonPrimitive? = (value as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }?.toIntOrNull()?.let(::JsonPrimitive)

    private fun head(row: JsonObject): JsonPrimitive? = (((row["head"] as? JsonObject)?.get("sha") ?: row["head_sha"]) as? JsonPrimitive)
        ?.takeIf { it.isString && revision.matches(it.content) }

    private fun sameScope(left: JsonObject, right: JsonObject): Boolean = left["binding"] == right["binding"] && left["repository"] == right["repository"]
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
