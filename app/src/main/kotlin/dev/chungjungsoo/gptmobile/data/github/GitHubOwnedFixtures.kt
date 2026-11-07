package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Task-scoped authority for two known synthetic changes, never arbitrary unmerged branches. */
internal class GitHubOwnedFixtures(private val store: GitHubRepositoryStateStore, private val scope: String) {
    companion object {
        const val PLAN = "content_v1"
        private val branchPattern = Regex("codex-self-test/[a-f0-9]{20}")
        fun path(branch: String): String = "docs/codex-self-test/${branch.substringAfterLast('/')}/fixture.txt"
        fun content(branch: String, revised: Boolean): String =
            "GPT Mobile GitHub integration fixture\nfixture: ${branch.substringAfterLast('/')}\nphase: ${if (revised) "staged" else "created"}\n"
        fun message(branch: String, revised: Boolean): String = "test: ${if (revised) "revise" else "create"} self-test fixture ${branch.substringAfterLast('/')}"
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
        private fun String.sha() = matches(Regex("[a-f0-9]{40}"))
    }

    private fun key(root: String, branch: String) = "fixture:" + GitHubRepositoryStateStore.digest("${root.lowercase()}|$branch")
    private suspend fun saved(root: String, branch: String): JsonObject? = store.read(scope, key(root, branch))?.also {
        require(it.text("repository") == root.lowercase() && it.text("branch") == branch && it.text("fixture_plan") == PLAN) {
            "FIXTURE_OWNERSHIP_UNAVAILABLE: The durable task-scoped ownership record is missing or incomplete."
        }
    }

    suspend fun register(root: String, arguments: JsonObject, receipt: JsonObject): JsonObject {
        if (arguments.text("fixture_plan") != PLAN) return receipt
        val branch = arguments.text("branch")
        val origin = arguments.text("expected_head_sha")
        val source = arguments.text("base").ifBlank { arguments.text("from_branch") }
        require(branchPattern.matches(branch) && source.isNotBlank() && source != branch && origin.sha() && arguments["select_created_branch"] == JsonPrimitive(false)) {
            "A content fixture needs its dedicated branch, source head and preserved workspace selection."
        }
        require(receipt["created"] == JsonPrimitive(true) && receipt["receipt_verified"] == JsonPrimitive(true) && receipt["remote_state_verified"] == JsonPrimitive(true) && receipt.text("head_sha") == origin && receipt.text("branch") == branch) {
            "FIXTURE_OWNERSHIP_UNAVAILABLE: Only an acknowledged creation and fresh matching ref establish ownership; a collision or uncertain creation does not."
        }
        store.update(scope, key(root, branch)) { previous ->
            if (previous != null) {
                require(previous.text("repository") == root.lowercase() && previous.text("branch") == branch && previous.text("origin_head_sha") == origin && previous.text("source_branch") == source) {
                    "Fixture ownership changed; inspect the existing durable record."
                }
                previous
            } else {
                buildJsonObject {
                    put("schema_version", 1)
                    put("fixture_plan", PLAN)
                    put("repository", root.lowercase())
                    put("branch", branch)
                    put("source_branch", source)
                    put("origin_head_sha", origin)
                    put("head_sha", origin)
                    put("ownership_verified", true)
                    put("status", "RUNNING")
                    put("commits", JsonArray(emptyList()))
                }
            }
        }
        return JsonObject(receipt + mapOf("fixture_plan" to JsonPrimitive(PLAN), "ownership_verified" to JsonPrimitive(true), "fixture_path" to JsonPrimitive(path(branch))))
    }

    /** Retain exact bounded intent before any tree, commit or ref publication can escape. */
    suspend fun prepareCommit(root: String, arguments: JsonObject) {
        val branch = arguments.text("branch")
        if (!branchPattern.matches(branch)) return
        val retained = saved(root, branch) ?: return
        val commits = (retained["commits"] as? JsonArray).orEmpty()
        val revised = commits.isNotEmpty()
        require(retained["cleanup_started"] != JsonPrimitive(true) && retained.text("status") != "COMPLETED" && commits.size < 2) { "The owned fixture is already complete or being cleaned." }
        require(arguments.text("expected_head_sha") == retained.text("head_sha")) { "Fixture head changed; reconcile the retained publication before another write." }
        val files = arguments["files"] as? JsonArray
        val file = files?.singleOrNull() as? JsonObject
        require(file != null && file.text("path") == path(branch) && file.text("content") == content(branch, revised) && file["delete"] != JsonPrimitive(true) && arguments.text("message") == message(branch, revised)) {
            "Owned fixtures permit only their exact synthetic file creation and one staged revision. Other writes need an independent lifecycle."
        }
        require(if (revised) file.text("expected_blob_sha") == GitHubCommitReconciliation.blobSha(content(branch, false)) else file["expected_blob_sha"] == null) { "The fixture blob lease differs from its recorded content." }
        val intent = buildJsonObject {
            put("branch", branch)
            put("message", arguments.getValue("message"))
            put("expected_head_sha", arguments.getValue("expected_head_sha"))
            put("files", requireNotNull(files))
        }
        store.update(scope, key(root, branch)) { current ->
            require(current?.text("head_sha") == retained.text("head_sha") && current["commits"] == retained["commits"] && current["cleanup_started"] != JsonPrimitive(true)) { "Fixture changed while preparing its mutation." }
            require(current["pending_commit"] == null || current["pending_commit"] == intent) { "An unresolved fixture mutation already owns this head; reconcile it first." }
            JsonObject(current + ("pending_commit" to intent))
        }
    }

    suspend fun acceptCommit(root: String, arguments: JsonObject, receipt: JsonObject) {
        val branch = arguments.text("branch")
        if (!branchPattern.matches(branch)) return
        val retained = saved(root, branch) ?: return
        val pending = retained["pending_commit"] as? JsonObject ?: return
        require(receipt.text("sha").sha() && receipt.text("parent_sha") == pending.text("expected_head_sha") && receipt.text("branch") == branch) { "Fixture publication receipt does not match its retained intent." }
        store.update(scope, key(root, branch)) { current ->
            require(current?.get("pending_commit") == pending && current.text("head_sha") == pending.text("expected_head_sha")) { "Fixture changed while recording its publication." }
            val committed = JsonObject(pending + mapOf("sha" to receipt.getValue("sha"), "tree_sha" to receipt.getValue("tree_sha")))
            JsonObject((current - "pending_commit") + mapOf("head_sha" to receipt.getValue("sha"), "commits" to JsonArray((current["commits"] as? JsonArray).orEmpty() + committed)))
        }
    }

    /** Every remote read here must bypass caches; the caller supplies that transport boundary. */
    suspend fun cleanup(
        root: String,
        arguments: JsonObject,
        selectedBranch: String?,
        read: suspend (String) -> JsonElement,
        delete: suspend (String) -> Unit,
        reconcile: suspend (JsonObject) -> JsonObject?
    ): JsonObject {
        val branch = arguments.text("branch")
        val origin = arguments.text("origin_head_sha")
        val expected = arguments.text("expected_head_sha")
        require(branchPattern.matches(branch) && origin.sha() && expected.sha()) { "Owned fixture cleanup requires its branch, origin_head_sha and current expected_head_sha." }
        var retained = requireNotNull(saved(root, branch)) { "FIXTURE_OWNERSHIP_UNAVAILABLE: No fixture was created by this task/account." }
        require(retained["ownership_verified"] == JsonPrimitive(true) && retained.text("origin_head_sha") == origin) { "Fixture ownership or origin lease does not match." }
        val matchingPath = "$root/git/matching-refs/heads/${GitHubWorkspaceClient.segment(branch)}"
        suspend fun absent(): Boolean = (read(matchingPath) as? JsonArray)?.none { (it as? JsonObject)?.text("ref") == "refs/heads/$branch" }
            ?: error("GitHub did not return an authoritative matching-ref list.")
        if (retained["cleanup_started"] == JsonPrimitive(true)) {
            require(retained.text("head_sha") == expected) { "The cleanup head lease differs from the durable deletion intent." }
            require(absent()) { "FIXTURE_RECONCILE_REQUIRED: A deletion was already attempted and the ref is present. No deletion will be repeated, including after restart." }
            return finishCleanup(root, branch, retained, reconciled = true)
        }
        (retained["pending_commit"] as? JsonObject)?.let { pending ->
            val proof = requireNotNull(reconcile(pending)) { "FIXTURE_RECONCILE_REQUIRED: An interrupted content publication has not been proved; no branch will be deleted." }
            acceptCommit(root, pending, proof)
            retained = requireNotNull(saved(root, branch))
        }
        require(retained.text("head_sha") == expected) { "Fixture current head differs from the supplied lease; inspect its branch before cleanup." }
        val metadata = read(root).jsonObject
        require(branch != metadata.text("default_branch") && branch != selectedBranch) { "Cannot remove the default or selected branch." }
        val encoded = GitHubWorkspaceClient.segment(branch)
        val details = read("$root/branches/$encoded").jsonObject
        require(details["protected"] == JsonPrimitive(false)) { "Fixture protection must be known and disabled." }
        require((read("$root/rules/branches/$encoded") as? JsonArray)?.isEmpty() == true) { "Branch rules prevent fixture cleanup." }
        val owner = root.removePrefix("/repos/").substringBefore('/')
        for (filter in listOf("head=${GitHubWorkspaceClient.segment("$owner:$branch")}", "base=$encoded")) {
            require((read("$root/pulls?state=open&$filter&per_page=1") as? JsonArray)?.isEmpty() == true) { "An open pull request depends on the fixture." }
        }
        for (status in listOf("queued", "in_progress", "waiting", "pending", "requested")) {
            require(read("$root/actions/runs?branch=$encoded&status=$status&per_page=1").jsonObject["total_count"] == JsonPrimitive(0)) { "Active workflow runs prevent fixture cleanup." }
        }
        val refPath = "$root/git/ref/heads/$encoded"
        suspend fun currentHead(): String {
            val ref = read(refPath).jsonObject
            require(ref.text("ref") == "refs/heads/$branch") { "GitHub returned another ref." }
            return (ref["object"] as? JsonObject)?.text("sha").orEmpty()
        }
        require(currentHead() == expected) { "Fixture branch moved; cleanup cannot discard another writer's work." }
        var parent = origin
        for (raw in (retained["commits"] as? JsonArray).orEmpty()) {
            val commit = raw.jsonObject
            require(commit.text("expected_head_sha") == parent) { "The durable fixture commit chain is incomplete." }
            // The immutable commit is checked independently of the mutable ref.
            // Both real ref observations surrounding the complete chain must match.
            val pinned = buildJsonObject {
                put("ref", "refs/heads/$branch")
                put("object", buildJsonObject { put("sha", commit.getValue("sha")) })
            }
            val proof = GitHubCommitReconciliation.recover(root, commit, pinned) { path -> if (path == refPath) pinned else read(path) }
            require(proof?.text("sha") == commit.text("sha")) { "Fixture commit parent, message or complete tree differs from its recorded synthetic patch." }
            parent = commit.text("sha")
        }
        require(parent == expected && currentHead() == expected) { "Fixture head changed or contains an unowned commit." }
        store.update(scope, key(root, branch)) { current ->
            require(current == retained) { "Fixture state changed before cleanup; inspect the durable receipt." }
            JsonObject(current + ("cleanup_started" to JsonPrimitive(true)))
        }
        // GitHub REST deletion has no atomic SHA lease. Recheck above, retain
        // the intent before dispatch, and never retry an uncertain deletion.
        delete("$root/git/refs/heads/$encoded")
        val missing = absent()
        if (missing) return finishCleanup(root, branch, retained, reconciled = false)
        return cleanupReceipt(retained, absent = false, reconciled = false)
    }

    private suspend fun finishCleanup(root: String, branch: String, retained: JsonObject, reconciled: Boolean): JsonObject {
        val receipt = cleanupReceipt(retained, absent = true, reconciled = reconciled)
        store.update(scope, key(root, branch)) { current ->
            require(current?.text("origin_head_sha") == retained.text("origin_head_sha") && current.text("head_sha") == retained.text("head_sha") && current["cleanup_started"] == JsonPrimitive(true))
            JsonObject(current + mapOf("status" to JsonPrimitive("COMPLETED"), "cleanup_receipt" to receipt))
        }
        return receipt
    }

    private fun cleanupReceipt(retained: JsonObject, absent: Boolean, reconciled: Boolean) = buildJsonObject {
        put("fixture_plan", PLAN)
        put("branch", retained.getValue("branch"))
        put("origin_head_sha", retained.getValue("origin_head_sha"))
        put("observed_head_sha", retained.getValue("head_sha"))
        put("ownership_verified", true)
        put("deleted", absent)
        put("delete_request_attempted", true)
        put("absence_verified", absent)
        put("reconciled", reconciled)
        put("verification_status", if (absent) "VERIFIED_ABSENT" else "REF_STILL_PRESENT")
        put("atomic_head_precondition", false)
        put("mutation_scope", "Only the recorded synthetic file and its owned disposable branch; no merge, force push or unrelated deletion.")
    }
}
