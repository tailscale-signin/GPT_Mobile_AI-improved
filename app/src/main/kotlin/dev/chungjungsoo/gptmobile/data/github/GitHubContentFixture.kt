package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Receipt-driven fixture recipes; transport ownership is independently enforced by GitHubOwnedFixtures. */
internal object GitHubContentFixture {
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
    private fun arguments(receipt: JsonObject) = receipt["arguments"] as? JsonObject
    private fun root(receipt: JsonObject): JsonObject? = (receipt["result"] as? JsonObject)?.let { it["data"] as? JsonObject ?: it }
    private fun eligible(receipt: JsonObject) = receipt["trusted"] == JsonPrimitive(true) && receipt["verification_eligible"] != JsonPrimitive(false)
    private fun successful(receipt: JsonObject) = eligible(receipt) && receipt["success"] == JsonPrimitive(true)
    private fun String.sha() = matches(Regex("[a-f0-9]{40}"))
    private data class Recipe(val tool: String, val arguments: JsonObject)

    fun isCreation(receipt: JsonObject): Boolean = receipt.text("tool").substringBefore("__") == "github_commit" && arguments(receipt)?.text("action") == "branch" && arguments(receipt)?.text("fixture_plan") == GitHubOwnedFixtures.PLAN

    private fun cleanup(branch: String, origin: String, head: String) = Recipe(
        "github_commit",
        buildJsonObject {
            put("action", "delete_fixture_branch")
            put("branch", branch)
            put("origin_head_sha", origin)
            put("expected_head_sha", head)
        }
    )

    private fun creation(branch: String, source: String, sha: String) = Recipe(
        "github_commit",
        buildJsonObject {
            put("action", "branch")
            put("branch", branch)
            put("from_branch", source)
            put("expected_head_sha", sha)
            put("select_created_branch", false)
            put("fixture_plan", GitHubOwnedFixtures.PLAN)
        }
    )

    private fun write(branch: String, origin: String) = Recipe(
        "github_write",
        buildJsonObject {
            put("action", "commit")
            put("branch", branch)
            put("expected_head_sha", origin)
            put("message", GitHubOwnedFixtures.message(branch, false))
            put(
                "files",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", GitHubOwnedFixtures.path(branch))
                            put("content", GitHubOwnedFixtures.content(branch, false))
                        }
                    )
                )
            )
        }
    )

    private fun stage(branch: String, head: String) = Recipe(
        "github_stage_patch",
        buildJsonObject {
            put("branch", branch)
            put("expected_head_sha", head)
            put("commit_sha", head)
            put(
                "edits",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", GitHubOwnedFixtures.path(branch))
                            put("expected_blob_sha", GitHubCommitReconciliation.blobSha(GitHubOwnedFixtures.content(branch, false)))
                            put("old_text", "phase: created")
                            put("new_text", "phase: staged")
                        }
                    )
                )
            )
        }
    )

    private fun commit(branch: String, head: String, stageId: String) = Recipe(
        "github_commit",
        buildJsonObject {
            put("action", "commit_staged_patch")
            put("branch", branch)
            put("expected_head_sha", head)
            put("stage_id", stageId)
            put("message", GitHubOwnedFixtures.message(branch, true))
        }
    )

    private fun evidence(branch: String, origin: String, head: String, revised: Boolean) = listOf(
        Recipe(
            "github_read",
            buildJsonObject {
                put("action", "ranges")
                put("commit_sha", head)
                put(
                    "ranges",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("path", GitHubOwnedFixtures.path(branch))
                                put("start_line", 1)
                                put("end_line", 3)
                            }
                        )
                    )
                )
            }
        ),
        Recipe(
            "github_search",
            buildJsonObject {
                put("action", "search")
                put("commit_sha", head)
                put("query", "phase: ${if (revised) "staged" else "created"}")
                put("mode", "literal")
                put("path_prefix", GitHubOwnedFixtures.path(branch))
            }
        ),
        Recipe(
            "github_context",
            buildJsonObject {
                put("action", "changes")
                put("base_sha", origin)
                put("commit_sha", head)
                put("include_patches", true)
            }
        )
    )

    private fun tool(recipe: Recipe, tools: List<AgentToolDefinition>) = tools.firstOrNull {
        it.modelVisible && it.name.substringBefore("__") == recipe.tool && ToolArgumentValidator.errors(recipe.arguments, it.inputSchema).isEmpty()
    }

    fun available(tools: List<AgentToolDefinition>, branch: String, source: String, sha: String): Boolean =
        (listOf(creation(branch, source, sha), write(branch, sha), stage(branch, sha), commit(branch, sha, "fixture-stage"), cleanup(branch, sha, sha)) + evidence(branch, sha, sha, false)).all { tool(it, tools) != null }

    private fun prior(recipe: Recipe, observations: List<JsonObject>) = observations.lastOrNull { it.text("tool").substringBefore("__") == recipe.tool && arguments(it) == recipe.arguments }
    private fun call(recipe: Recipe, tools: List<AgentToolDefinition>, observations: List<JsonObject>): ProviderEvent.ToolCall? {
        if (prior(recipe, observations) != null) return null
        val tool = tool(recipe, tools) ?: return null
        return ProviderEvent.ToolCall("fixture-" + GitHubEvidenceBook.digest("${tool.name}:${recipe.arguments}").take(24), tool.name, recipe.arguments)
    }

    private fun contentReceipt(receipt: JsonObject?, branch: String, parent: String, revised: Boolean): String? {
        if (receipt == null || !successful(receipt)) return null
        val result = root(receipt) ?: return null
        val head = result.text("sha")
        val file = (result["files"] as? JsonArray)?.singleOrNull() as? JsonObject
        return head.takeIf { it.sha() && it != parent && result.text("parent_sha") == parent && result.text("branch") == branch && file?.text("path") == GitHubOwnedFixtures.path(branch) && file.text("blob_sha") == GitHubCommitReconciliation.blobSha(GitHubOwnedFixtures.content(branch, revised)) }
    }

    private fun evidenceVerified(recipe: Recipe, receipt: JsonObject?, branch: String, origin: String, head: String, revised: Boolean): Boolean {
        if (receipt == null || !successful(receipt)) return false
        val result = root(receipt) ?: return false
        val path = GitHubOwnedFixtures.path(branch)
        val blob = GitHubCommitReconciliation.blobSha(GitHubOwnedFixtures.content(branch, revised))
        return when (recipe.tool) {
            "github_read" -> result.text("commit_sha") == head && (result["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any { it.text("path") == path && it.text("sha") == blob && it.text("content").trimEnd() == GitHubOwnedFixtures.content(branch, revised).trimEnd() }
            "github_search" -> result.text("commit_sha") == head && result["matches_complete"] == JsonPrimitive(true) && (result["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any { it.text("path") == path && it.text("sha") == blob && (it["matches"] as? JsonArray).orEmpty().isNotEmpty() }
            "github_context" -> result.text("base_sha") == origin &&
                result.text("head_sha") == head &&
                result["files_total"] == JsonPrimitive(1) &&
                (result["files"] as? JsonArray)?.singleOrNull()?.let { raw ->
                    val file = raw as? JsonObject
                    file?.text("filename") == path && file.text("sha") == blob && file["patch_available"] == JsonPrimitive(true) && file.text("patch").lineSequence().any { it == "+phase: ${if (revised) "staged" else "created"}" }
                } == true
            else -> false
        }
    }

    fun next(
        source: JsonObject,
        creationReceipt: JsonObject?,
        tools: List<AgentToolDefinition>,
        receipts: Map<String, JsonObject>,
        allowCreate: Boolean,
        cleanupOnly: Boolean,
        abortIncomplete: Boolean
    ): ProviderEvent.ToolCall? {
        val status = root(source) ?: return null
        val origin = status.text("commit_sha")
        val branch = "codex-self-test/" + GitHubEvidenceBook.digest(source.text("binding") + source.text("receipt_id")).take(20)
        val sourceBranch = status.text("branch")
        val observations = receipts.values.filter { eligible(it) && it["binding"] == source["binding"] && it["repository"] == source["repository"] }
        if (creationReceipt == null) return if (allowCreate && !cleanupOnly && available(tools, branch, sourceBranch, origin)) call(creation(branch, sourceBranch, origin), tools, observations) else null
        if (!owned(source, creationReceipt)) return null
        var head = origin
        val write = write(branch, origin)
        val written = prior(write, observations)
        val firstHead = contentReceipt(written, branch, origin, false)
        if (written != null && firstHead == null) {
            // A queued exact review may still publish later. It cannot be turned
            // into permission to remove its destination or to repeat the write.
            if (successful(written)) return null
            return recoveryCleanup(branch, origin, head, tools, observations)
        }
        if (firstHead == null) {
            if (abortIncomplete) return call(cleanup(branch, origin, head), tools, observations)
            return if (cleanupOnly) null else call(write, tools, observations)
        }
        head = firstHead
        for (recipe in evidence(branch, origin, head, false)) {
            val receipt = prior(recipe, observations)
            if (!evidenceVerified(recipe, receipt, branch, origin, head, false)) {
                if (receipt != null || abortIncomplete) return call(cleanup(branch, origin, head), tools, observations)
                return if (cleanupOnly) null else call(recipe, tools, observations)
            }
        }
        val stage = stage(branch, head)
        val staged = prior(stage, observations)
        if (staged == null) {
            if (abortIncomplete) return call(cleanup(branch, origin, head), tools, observations)
            return if (cleanupOnly) null else call(stage, tools, observations)
        }
        val stageResult = root(staged)
        if (!successful(staged) || stageResult?.text("stage_id").isNullOrBlank() || stageResult.text("expected_head_sha") != head || stageResult.text("branch") != branch) return call(cleanup(branch, origin, head), tools, observations)
        val commit = commit(branch, head, stageResult.text("stage_id"))
        val committed = prior(commit, observations)
        val finalHead = contentReceipt(committed, branch, head, true)
        if (committed != null && finalHead == null) {
            if (successful(committed)) return null
            return recoveryCleanup(branch, origin, head, tools, observations)
        }
        if (finalHead == null) {
            if (abortIncomplete) return call(cleanup(branch, origin, head), tools, observations)
            return if (cleanupOnly) null else call(commit, tools, observations)
        }
        head = finalHead
        for (recipe in evidence(branch, origin, head, true)) {
            val receipt = prior(recipe, observations)
            if (!evidenceVerified(recipe, receipt, branch, origin, head, true)) {
                if (receipt != null || abortIncomplete) return call(cleanup(branch, origin, head), tools, observations)
                return if (cleanupOnly) null else call(recipe, tools, observations)
            }
        }
        return call(cleanup(branch, origin, head), tools, observations)
    }

    private fun recoveryCleanup(branch: String, origin: String, knownHead: String, tools: List<AgentToolDefinition>, observations: List<JsonObject>): ProviderEvent.ToolCall? {
        val inspect = Recipe(
            "github_context",
            buildJsonObject {
                put("action", "branch")
                put("branch", branch)
                put("verify_live", true)
            }
        )
        val observed = prior(inspect, observations)
        if (observed == null) return call(inspect, tools, observations)
        val value = root(observed) ?: return null
        val details = value["branch"] as? JsonObject
        if (details != null && details.text("name") != branch) return null
        val head = value.text("head_sha").ifBlank { (details?.get("commit") as? JsonObject)?.text("sha").orEmpty() }.ifBlank { (value["commit"] as? JsonObject)?.text("sha").orEmpty() }.ifBlank { value.text("commit_sha") }
        if (!successful(observed) || !head.sha()) return null
        // The cleanup transport must reconcile a pending exact publication and
        // prove its whole tree. An arbitrary observed head never grants ownership.
        return call(cleanup(branch, origin, head.ifBlank { knownHead }), tools, observations)
    }

    private fun owned(source: JsonObject?, creation: JsonObject): Boolean {
        if (source == null || !successful(source) || !successful(creation) || source["binding"] != creation["binding"] || source["repository"] != creation["repository"]) return false
        val args = arguments(creation) ?: return false
        val origin = args.text("expected_head_sha")
        val branch = args.text("branch")
        val result = root(creation) ?: return false
        return isCreation(creation) &&
            origin.sha() &&
            root(source)?.text("commit_sha") == origin &&
            root(source)?.text("branch") == args.text("from_branch") &&
            branch == "codex-self-test/" + GitHubEvidenceBook.digest(source.text("binding") + source.text("receipt_id")).take(20) &&
            result.text("branch") == branch &&
            result.text("head_sha") == origin &&
            result["created"] == JsonPrimitive(true) &&
            result["receipt_verified"] == JsonPrimitive(true) &&
            result["ownership_verified"] == JsonPrimitive(true)
    }

    fun checkpoint(creation: JsonObject, receipts: Map<String, JsonObject>): JsonObject {
        val args = arguments(creation).orEmpty()
        val branch = (args["branch"] as? JsonPrimitive)?.content.orEmpty()
        val origin = (args["expected_head_sha"] as? JsonPrimitive)?.content.orEmpty()
        val observations = receipts.values.dropWhile { it !== creation && it != creation }.filter { eligible(it) && it["binding"] == creation["binding"] && it["repository"] == creation["repository"] }
        val source = receipts.values.firstOrNull { it.text("tool").substringBefore("__") == "github_context" && arguments(it)?.text("action") == "status" && owned(it, creation) }
        val ownership = owned(source, creation)
        val written = prior(write(branch, origin), observations)
        val first = contentReceipt(written, branch, origin, false)
        val staged = first?.let { prior(stage(branch, it), observations) }
        val stageId = staged?.let(::root)?.text("stage_id").orEmpty()
        val committed = first?.takeIf { stageId.isNotBlank() }?.let { prior(commit(branch, it, stageId), observations) }
        val final = first?.let { contentReceipt(committed, branch, it, true) }
        val proved = listOfNotNull(creation, written, staged, committed).mapNotNull { it["receipt_id"] }
        val stageVerified = staged != null && successful(staged) && root(staged)?.text("expected_head_sha") == first && root(staged)?.text("branch") == branch
        val complete = ownership && stageVerified && final != null && listOf(requireNotNull(first) to false, final to true).all { (head, revised) -> evidence(branch, origin, head, revised).all { recipe -> evidenceVerified(recipe, prior(recipe, observations), branch, origin, head, revised) } }
        val cleanup = observations.lastOrNull { it.text("tool").substringBefore("__") == "github_commit" && arguments(it)?.text("action") == "delete_fixture_branch" && arguments(it)?.text("branch") == branch }
        val cleaned = ownership && cleanup != null && successful(cleanup) && (!complete || arguments(cleanup)?.text("expected_head_sha") == final) && arguments(cleanup)?.text("origin_head_sha") == origin && root(cleanup)?.text("origin_head_sha") == origin && root(cleanup)?.text("branch") == branch && root(cleanup)?.text("observed_head_sha") == arguments(cleanup)?.text("expected_head_sha") && root(cleanup)?.get("ownership_verified") == JsonPrimitive(true) && root(cleanup)?.get("deleted") == JsonPrimitive(true) && root(cleanup)?.get("absence_verified") == JsonPrimitive(true)
        return buildJsonObject {
            put("branch", branch)
            put("fixture_plan", GitHubOwnedFixtures.PLAN)
            put("ownership_verified", ownership)
            put("content_verified", complete)
            put("final_state_verified", cleaned)
            put(
                "cleanup_status",
                if (!ownership) {
                    "OWNERSHIP_NOT_ESTABLISHED"
                } else if (cleaned) {
                    "CLEANED"
                } else {
                    "PENDING_OR_BLOCKED"
                }
            )
            put("creation_receipt", creation["receipt_id"] ?: JsonPrimitive("unknown"))
            cleanup?.get("receipt_id")?.let { put("cleanup_receipt", it) }
            put("mutation_receipts", JsonArray(proved))
            put("mutation_scope", "Owned branch, exact nonempty synthetic content, staged revision, pinned content reads/searches and nonempty diff; absence verified after cleanup.")
        }
    }
}
