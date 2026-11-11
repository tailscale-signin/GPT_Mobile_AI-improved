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

    /** Save a cleanup obligation before creation escapes; intent alone never proves ownership. */
    suspend fun prepareCreation(root: String, arguments: JsonObject) {
        if (arguments.text("fixture_plan") != PLAN) return
        val branch = arguments.text("branch")
        val origin = arguments.text("expected_head_sha")
        val source = arguments.text("base").ifBlank { arguments.text("from_branch") }
        require(branchPattern.matches(branch) && source.isNotBlank() && source != branch && origin.sha() && arguments["select_created_branch"] == JsonPrimitive(false)) {
            "The fixture lifecycle must identify its dedicated branch, source head and cleanup scope before creation."
        }
        store.update(scope, key(root, branch)) { previous ->
            if (previous != null && previous.text("status") != "COMPLETED") {
                require(previous.text("origin_head_sha") == origin && previous.text("source_branch") == source && previous.text("repository") == root.lowercase()) { "Another fixture intent owns this name; reconcile it before creating resources." }
                require(previous["ownership_verified"] != JsonPrimitive(true)) { "An owned fixture already has a lifecycle. Reconcile its missing ref or cleanup; do not recreate it." }
                previous
            } else {
                buildJsonObject {
                    put("schema_version", 2)
                    put("fixture_plan", PLAN)
                    put("repository", root.lowercase())
                    put("branch", branch)
                    put("source_branch", source)
                    put("origin_head_sha", origin)
                    put("head_sha", origin)
                    put("owner_scope_sha256", GitHubRepositoryStateStore.digest(scope))
                    put("purpose", "BOUNDED_SYNTHETIC_CONTENT_INTEGRATION_TEST")
                    put("ownership_verified", false)
                    put("status", "CREATION_PENDING")
                    put("lifecycle_state", "CLEANUP_PENDING")
                    put("lifecycle_reason", "CREATION_OUTCOME_NOT_YET_RECONCILED")
                    put("cleanup_obligation", "RECONCILE_OWNERSHIP_THEN_VERIFY_ABSENCE")
                    put("approval_policy", "EXACT_PATCH_APPROVAL_RECHECKED_BEFORE_PUBLICATION")
                    put("commits", JsonArray(emptyList()))
                }
            }
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
            if (previous != null && previous.text("status") != "COMPLETED") {
                require(previous.text("repository") == root.lowercase() && previous.text("branch") == branch && previous.text("origin_head_sha") == origin && previous.text("source_branch") == source) {
                    "Fixture ownership changed; inspect the existing durable record."
                }
                if (previous["ownership_verified"] == JsonPrimitive(true)) {
                    previous
                } else {
                    JsonObject(
                        previous + mapOf(
                            "ownership_verified" to JsonPrimitive(true),
                            "status" to JsonPrimitive("RUNNING"),
                            "lifecycle_state" to JsonPrimitive("CLEANUP_PENDING"),
                            "lifecycle_reason" to JsonPrimitive("LIFECYCLE_IN_PROGRESS"),
                            "cleanup_obligation" to JsonPrimitive("DURABLE_UNTIL_VERIFIED_ABSENT")
                        )
                    )
                }
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
                    put("owner_scope_sha256", GitHubRepositoryStateStore.digest(scope))
                    put("purpose", "BOUNDED_SYNTHETIC_CONTENT_INTEGRATION_TEST")
                    put("lifecycle_state", "CLEANUP_PENDING")
                    put("lifecycle_reason", "LIFECYCLE_IN_PROGRESS")
                    put("cleanup_obligation", "DURABLE_UNTIL_VERIFIED_ABSENT")
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
        require(retained["ownership_verified"] == JsonPrimitive(true)) { "FIXTURE_OWNERSHIP_UNAVAILABLE: A creation intent cannot authorize content publication." }
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
            JsonObject((current - "pending_commit" - "pending_approval") + mapOf("head_sha" to receipt.getValue("sha"), "commits" to JsonArray((current["commits"] as? JsonArray).orEmpty() + committed), "last_publication" to receipt, "lifecycle_state" to JsonPrimitive("CLEANUP_PENDING"), "lifecycle_reason" to JsonPrimitive("PUBLICATION_VERIFIED_CONTINUE_LIFECYCLE")))
        }
    }

    suspend fun awaitApproval(root: String, arguments: JsonObject, receipt: JsonObject) {
        val branch = arguments.text("branch")
        if (!branchPattern.matches(branch) || saved(root, branch) == null) return
        val taskId = receipt.text("engineering_task_id")
        require(taskId.isNotBlank() && receipt.text("status") == "AWAITING_APPROVAL" && receipt["committed"] == JsonPrimitive(false)) { "The native approval receipt is incomplete; preserve the pending fixture for reconciliation." }
        store.update(scope, key(root, branch)) { current ->
            val pending = current?.get("pending_commit") as? JsonObject
            require(pending != null && pending.text("expected_head_sha") == arguments.text("expected_head_sha"))
            JsonObject(current + mapOf("pending_approval" to receipt, "lifecycle_state" to JsonPrimitive("DELIBERATELY_RETAINED"), "lifecycle_reason" to JsonPrimitive("EXACT_PATCH_APPROVAL_PENDING")))
        }
    }

    suspend fun recoverApproval(
        root: String,
        status: JsonObject,
        read: suspend (String) -> JsonElement,
        expectedAbsence: suspend (String) -> Boolean,
        reconcile: suspend (JsonObject) -> JsonObject?
    ): JsonObject? {
        val branch = status.text("branch")
        if (!branchPattern.matches(branch)) return null
        var retained = saved(root, branch) ?: return null
        val intent = status["publication_intent"] as? JsonObject ?: return null
        val id = status.text("engineering_task_id")
        val published = retained["last_publication"] as? JsonObject
        if (published?.text("engineering_task_id") == id) return published
        val cancelled = retained["cancelled_approval"] as? JsonObject
        if (cancelled?.text("engineering_task_id") == id) return cancelled
        // Older builds saved native approval before fixture intent. Upgrade only
        // the exact task-owned synthetic change; the original branch must exist.
        if (retained["pending_commit"] == null) {
            prepareCommit(root, intent)
            awaitApproval(
                root,
                intent,
                buildJsonObject {
                    put("engineering_task_id", id)
                    put("status", "AWAITING_APPROVAL")
                    put("committed", false)
                    put("branch", branch)
                    put("expected_head_sha", intent.getValue("expected_head_sha"))
                }
            )
            retained = requireNotNull(saved(root, branch))
        }
        require((retained["pending_approval"] as? JsonObject)?.text("engineering_task_id") == id) { "The fixture belongs to another pending approval; retain both records for review." }
        return this.reconcile(
            root,
            buildJsonObject {
                put("fixture_plan", PLAN)
                put("branch", branch)
                put("base_sha", retained.getValue("origin_head_sha"))
                put("commit_sha", intent.getValue("expected_head_sha"))
            },
            read,
            expectedAbsence,
            { status },
            reconcile
        )
    }

    /** Observe approval/publication or deletion using the retained intent, never dispatch a write. */
    suspend fun reconcile(
        root: String,
        arguments: JsonObject,
        read: suspend (String) -> JsonElement,
        expectedAbsence: suspend (String) -> Boolean,
        approvalStatus: suspend (String) -> JsonObject?,
        reconcile: suspend (JsonObject) -> JsonObject?
    ): JsonObject {
        val branch = arguments.text("branch")
        val retained = requireNotNull(saved(root, branch)) { "FIXTURE_OWNERSHIP_UNAVAILABLE: No owned fixture is recorded for this task." }
        require(arguments.text("fixture_plan") == PLAN && branchPattern.matches(branch) && arguments.text("base_sha") == retained.text("origin_head_sha")) { "Fixture recovery must use its exact recorded origin and branch." }
        if (retained["ownership_verified"] != JsonPrimitive(true)) {
            return JsonObject(retained + mapOf("cleanup_status" to JsonPrimitive("OWNERSHIP_NOT_ESTABLISHED"), "next_action" to JsonPrimitive("RECONCILE_CREATION_RECEIPT")))
        }
        if (retained["cleanup_started"] == JsonPrimitive(true)) return reconcileDeletion(root, arguments, read, expectedAbsence)
        val pending = retained["pending_commit"] as? JsonObject
        val approval = retained["pending_approval"] as? JsonObject
        if (pending == null) {
            return (retained["last_publication"] as? JsonObject) ?: buildJsonObject {
                put("branch", branch)
                put("ownership_verified", true)
                put("origin_head_sha", retained.getValue("origin_head_sha"))
                put("head_sha", retained.getValue("head_sha"))
                put("cleanup_status", "PENDING")
            }
        }
        require(arguments.text("commit_sha") in setOf(pending.text("expected_head_sha"), retained.text("head_sha"))) { "Fixture recovery head differs from the retained publication intent." }
        val id = approval?.text("engineering_task_id")
        val status = id?.let { approvalStatus(it) }
        fun unresolved(state: String, next: String): JsonObject = JsonObject(
            approval.orEmpty() + mapOf(
                "branch" to JsonPrimitive(branch),
                "head_sha" to pending.getValue("expected_head_sha"),
                "native_state" to JsonPrimitive(status?.text("state")?.ifBlank { "UNKNOWN" } ?: "UNKNOWN"),
                "approval_state" to JsonPrimitive(state),
                "cleanup_status" to JsonPrimitive("WAITING_FOR_APPROVAL"),
                "lifecycle_state" to JsonPrimitive("DELIBERATELY_RETAINED"),
                "lifecycle_reason" to JsonPrimitive(state),
                "next_action" to JsonPrimitive(next)
            )
        )
        if (approval != null && status == null) return unresolved("NATIVE_APPROVAL_STATE_UNAVAILABLE", "OPEN_SAVED_APPROVAL")
        if (status?.text("state") == "AWAITING_APPROVAL") return unresolved("EXACT_PATCH_APPROVAL_PENDING", "APPROVE_OR_CANCEL_EXISTING_PATCH")
        if (status?.text("state") == "COMMITTING" && status["publication_may_have_started"] == JsonPrimitive(false)) {
            return unresolved("APPROVED_AWAITING_DISPATCH", "RESUME_NATIVE_PUBLICATION")
        }
        if (status?.get("cancelled_before_publication") == JsonPrimitive(true)) {
            val ref = read("$root/git/ref/heads/${GitHubWorkspaceClient.segment(branch)}") as? JsonObject
            require((ref?.get("object") as? JsonObject)?.text("sha") == pending.text("expected_head_sha")) { "Fixture changed after cancellation; its publication must be reconciled before cleanup." }
            val cancelled = buildJsonObject {
                put("engineering_task_id", requireNotNull(id))
                put("approval_state", "CANCELLED_BEFORE_PUBLICATION")
                put("native_state", status.text("state"))
                put("remote_mutation_performed", false)
                put("branch", branch)
                put("head_sha", pending.getValue("expected_head_sha"))
                put("ownership_verified", true)
                put("cleanup_status", "PENDING")
            }
            store.update(scope, key(root, branch)) { current ->
                require(current?.get("pending_commit") == pending && current["pending_approval"] == approval)
                JsonObject((current - "pending_commit" - "pending_approval") + mapOf("cancelled_approval" to cancelled, "lifecycle_state" to JsonPrimitive("CLEANUP_PENDING"), "lifecycle_reason" to JsonPrimitive("CANCELLED_BEFORE_PUBLICATION")))
            }
            return cancelled
        }
        val recordedCommit = status?.get("published_commit_sha")
        val proofArguments = if (recordedCommit != null) JsonObject(pending + ("published_commit_sha" to recordedCommit)) else pending
        val proof = reconcile(proofArguments) ?: if (approval != null) {
            return unresolved("PUBLICATION_AWAITING_REMOTE_VERIFICATION", "RECONCILE_EXISTING_PUBLICATION")
        } else {
            error("FIXTURE_RECONCILE_REQUIRED · The live ref and commit do not verify the saved publication. The existing fixture and approval are retained.")
        }
        // The transport's reconciliation callback may already have accepted this exact receipt.
        if (saved(root, branch)?.get("pending_commit") != null) acceptCommit(root, pending, proof)
        val publication = JsonObject(
            proof + buildJsonObject {
                id?.let { put("engineering_task_id", it) }
                put("approval_state", "PUBLISHED")
                put("remote_state_verified", true)
            }
        )
        store.update(scope, key(root, branch)) { current ->
            require(current?.text("head_sha") == proof.text("sha"))
            JsonObject(current + ("last_publication" to publication))
        }
        return publication
    }

    /** A separate read-only entry point cannot repeat the original deletion. */
    suspend fun reconcileDeletion(
        root: String,
        arguments: JsonObject,
        read: suspend (String) -> JsonElement,
        expectedAbsence: suspend (String) -> Boolean
    ): JsonObject {
        val branch = arguments.text("branch")
        val origin = arguments.text("base_sha")
        val expected = arguments.text("commit_sha")
        require(arguments.text("fixture_plan") == PLAN && branchPattern.matches(branch) && origin.sha() && expected.sha()) { "Fixture recovery requires its exact plan, branch, origin and recorded deletion head." }
        val retained = requireNotNull(saved(root, branch)) { "FIXTURE_OWNERSHIP_UNAVAILABLE: No fixture was created by this task/account." }
        require(retained["ownership_verified"] == JsonPrimitive(true) && retained.text("origin_head_sha") == origin && retained.text("head_sha") == expected && retained["cleanup_started"] == JsonPrimitive(true)) {
            "FIXTURE_RECONCILE_REQUIRED: No matching durable deletion intent exists. Read-only recovery cannot authorize deletion."
        }
        return verifyDeletion(root, branch, origin, retained, read) {
            expectedAbsence("$root/git/ref/heads/${GitHubWorkspaceClient.segment(branch)}")
        }
    }

    private suspend fun verifyDeletion(
        root: String,
        branch: String,
        origin: String,
        retained: JsonObject,
        read: suspend (String) -> JsonElement,
        absent: suspend () -> Boolean
    ): JsonObject {
        // Private refs can return404 after access is revoked. Prove current
        // content access in the original account/repository before using404.
        val accessibleOrigin = read("$root/git/commits/$origin") as? JsonObject
        require(accessibleOrigin?.text("sha") == origin) { "FIXTURE_RECONCILE_REQUIRED: The original immutable commit is not currently accessible; ref absence cannot establish cleanup." }
        require(absent()) { "FIXTURE_RECONCILE_REQUIRED: A deletion was already attempted and the ref is present. No deletion will be repeated, including after restart." }
        return finishCleanup(root, branch, retained, reconciled = true)
    }

    /** Every remote read here must bypass caches; the caller supplies that transport boundary. */
    suspend fun cleanup(
        root: String,
        arguments: JsonObject,
        selectedBranch: String?,
        read: suspend (String) -> JsonElement,
        delete: suspend (String) -> Unit,
        expectedAbsence: (suspend (String) -> Boolean)? = null,
        reconcile: suspend (JsonObject) -> JsonObject?
    ): JsonObject {
        val branch = arguments.text("branch")
        val origin = arguments.text("origin_head_sha")
        val expected = arguments.text("expected_head_sha")
        require(branchPattern.matches(branch) && origin.sha() && expected.sha()) { "Owned fixture cleanup requires its branch, origin_head_sha and current expected_head_sha." }
        var retained = requireNotNull(saved(root, branch)) { "FIXTURE_OWNERSHIP_UNAVAILABLE: No fixture was created by this task/account." }
        require(retained["ownership_verified"] == JsonPrimitive(true) && retained.text("origin_head_sha") == origin) { "Fixture ownership or origin lease does not match." }
        val refPath = "$root/git/ref/heads/${GitHubWorkspaceClient.segment(branch)}"

        // The production transport accepts a fresh exact-ref 404 only inside this
        // task-owned teardown. Generic missing refs retain their normal failure policy.
        suspend fun absent(): Boolean = expectedAbsence?.invoke(refPath) ?: run {
            val matchingPath = "$root/git/matching-refs/heads/${GitHubWorkspaceClient.segment(branch)}"
            (read(matchingPath) as? JsonArray)?.none { (it as? JsonObject)?.text("ref") == "refs/heads/$branch" }
                ?: error("GitHub did not return an authoritative matching-ref list.")
        }
        if (retained["cleanup_started"] == JsonPrimitive(true)) {
            require(retained.text("head_sha") == expected) { "The cleanup head lease differs from the durable deletion intent." }
            return verifyDeletion(root, branch, origin, retained, read, ::absent)
        }
        require(retained["pending_approval"] == null) { "WAITING_FOR_APPROVAL: Resolve the existing saved approval and reconcile its status before cleaning this fixture." }
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
        val deletionIntent = store.update(scope, key(root, branch)) { current ->
            require(current == retained) { "Fixture state changed before cleanup; inspect the durable receipt." }
            JsonObject(current + mapOf("cleanup_started" to JsonPrimitive(true), "status" to JsonPrimitive("DELETE_PENDING"), "lifecycle_state" to JsonPrimitive("CLEANUP_PENDING"), "lifecycle_reason" to JsonPrimitive("DELETE_OUTCOME_REQUIRES_ABSENCE_VERIFICATION")))
        }
        // GitHub REST deletion has no atomic SHA lease. Recheck above, retain
        // the intent before dispatch, and never retry an uncertain deletion.
        try {
            delete("$root/git/refs/heads/$encoded")
        } catch (failure: Exception) {
            // These scheduler rejections prove this DELETE never entered its
            // dispatch block. Everything else keeps the durable uncertainty fence.
            if (failure is GitHubRequestCircuit.Open || failure is GitHubRequestScheduler.Capacity) {
                store.update(scope, key(root, branch)) { current ->
                    require(current == deletionIntent) { "Fixture deletion intent changed before recovery." }
                    retained
                }
            }
            throw failure
        }
        val missing = absent()
        if (missing) return finishCleanup(root, branch, retained, reconciled = false)
        return cleanupReceipt(retained, absent = false, reconciled = false)
    }

    private suspend fun finishCleanup(root: String, branch: String, retained: JsonObject, reconciled: Boolean): JsonObject {
        val receipt = cleanupReceipt(retained, absent = true, reconciled = reconciled)
        store.update(scope, key(root, branch)) { current ->
            require(current?.text("origin_head_sha") == retained.text("origin_head_sha") && current.text("head_sha") == retained.text("head_sha") && current["cleanup_started"] == JsonPrimitive(true))
            JsonObject(current + mapOf("status" to JsonPrimitive("COMPLETED"), "cleanup_receipt" to receipt, "lifecycle_state" to JsonPrimitive("REMOVED_VERIFIED"), "lifecycle_reason" to JsonPrimitive("REMOTE_REF_ABSENCE_VERIFIED"), "cleanup_obligation" to JsonPrimitive("VERIFIED_ABSENT")))
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
        put("deletion_state", if (absent) "COMPLETED" else "PENDING_VERIFICATION")
        put("lifecycle_state", if (absent) "REMOVED_VERIFIED" else "CLEANUP_PENDING")
        put("lifecycle_reason", if (absent) "REMOTE_REF_ABSENCE_VERIFIED" else "REMOTE_REF_STILL_PRESENT")
        put("atomic_head_precondition", false)
        put("mutation_scope", "Only the recorded synthetic file and its owned disposable branch; no merge, force push or unrelated deletion.")
    }
}
