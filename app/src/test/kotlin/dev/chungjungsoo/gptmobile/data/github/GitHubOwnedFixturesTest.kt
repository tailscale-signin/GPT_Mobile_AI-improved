package dev.chungjungsoo.gptmobile.data.github

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOwnedFixturesTest {
    private val root = "/repos/owner/repo"
    private val branch = "codex-self-test/" + "d".repeat(20)
    private val origin = "a".repeat(40)
    private val first = "b".repeat(40)
    private val second = "c".repeat(40)
    private fun args(head: String, revised: Boolean) = buildJsonObject {
        put("branch", branch)
        put("expected_head_sha", head)
        put("message", GitHubOwnedFixtures.message(branch, revised))
        put(
            "files",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", GitHubOwnedFixtures.path(branch))
                        put("content", GitHubOwnedFixtures.content(branch, revised))
                        if (revised) put("expected_blob_sha", GitHubCommitReconciliation.blobSha(GitHubOwnedFixtures.content(branch, false)))
                    }
                )
            )
        )
    }
    private fun cleanup(head: String) = buildJsonObject {
        put("branch", branch)
        put("origin_head_sha", origin)
        put("expected_head_sha", head)
    }
    private fun creation() = buildJsonObject {
        put("fixture_plan", GitHubOwnedFixtures.PLAN)
        put("branch", branch)
        put("base", "main")
        put("expected_head_sha", origin)
        put("select_created_branch", false)
    }
    private fun created() = buildJsonObject {
        put("branch", branch)
        put("head_sha", origin)
        put("created", true)
        put("receipt_verified", true)
        put("remote_state_verified", true)
    }

    @Test fun creationIntentSurvivesRestartWithoutGrantingOwnershipOrCleanup() = runBlocking {
        val directory = Files.createTempDirectory("github-fixture-intent").toFile()
        try {
            val firstStore = GitHubRepositoryStateStore(directory)
            GitHubOwnedFixtures(firstStore, "task").prepareCreation(root, creation())
            val store = GitHubRepositoryStateStore(directory)
            val restored = GitHubOwnedFixtures(store, "task")
            val key = "fixture:" + GitHubRepositoryStateStore.digest("$root|$branch")
            val intent = requireNotNull(store.read("task", key))
            assertEquals(JsonPrimitive("CREATION_PENDING"), intent["status"])
            assertEquals(JsonPrimitive("CLEANUP_PENDING"), intent["lifecycle_state"])
            assertEquals(JsonPrimitive(false), intent["ownership_verified"])
            assertTrue(runCatching { restored.prepareCommit(root, args(origin, false)) }.isFailure)
            val remote = Remote()
            assertTrue(runCatching { remote.clean(restored, origin) }.isFailure)
            assertEquals(0, remote.deletes)
            restored.register(root, creation(), created())
            assertEquals(JsonPrimitive(true), store.read("task", key)?.get("ownership_verified"))
            remote.clean(restored, origin)
            assertEquals(1, remote.deletes)
            assertEquals(JsonPrimitive("REMOVED_VERIFIED"), store.read("task", key)?.get("lifecycle_state"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private inner class Remote {
        var head: String? = origin
        var deletes = 0
        var publications = 0
        var protected = false
        var hasRules = false
        var openPr = false
        var activeRun = false
        var unexpectedTreeChange = false
        var failAfterDelete = false
        var recreateAfterDelete = false
        val commits = linkedMapOf<String, JsonObject>()
        val trees = linkedMapOf<String, JsonObject>()
        val preserved = buildJsonObject {
            put("path", "README.md")
            put("sha", "4".repeat(40))
            put("type", "blob")
            put("mode", "100644")
        }
        init {
            commits[origin] = buildJsonObject {
                put("sha", origin)
                put("tree", buildJsonObject { put("sha", "1".repeat(40)) })
                put("parents", JsonArray(emptyList()))
                put("message", "base")
            }
            trees["1".repeat(40)] = tree("1".repeat(40), listOf(preserved))
        }
        private fun tree(sha: String, rows: List<JsonObject>) = buildJsonObject {
            put("sha", sha)
            put("truncated", false)
            put("tree", JsonArray(rows))
        }
        fun publish(arguments: JsonObject, sha: String): JsonObject {
            publications++
            val parent = arguments.getValue("expected_head_sha").jsonPrimitive.content
            val treeSha = if (sha == first) "2".repeat(40) else "3".repeat(40)
            val file = (arguments.getValue("files") as JsonArray).single().jsonObject
            val synthetic = buildJsonObject {
                put("path", file.getValue("path"))
                put("sha", GitHubCommitReconciliation.blobSha(file.getValue("content").jsonPrimitive.content))
                put("type", "blob")
                put("mode", "100644")
            }
            commits[sha] = buildJsonObject {
                put("sha", sha)
                put("tree", buildJsonObject { put("sha", treeSha) })
                put("parents", JsonArray(listOf(buildJsonObject { put("sha", parent) })))
                put("message", arguments.getValue("message"))
            }
            trees[treeSha] = tree(treeSha, listOf(preserved, synthetic))
            head = sha
            return buildJsonObject {
                put("sha", sha)
                put("parent_sha", parent)
                put("branch", branch)
                put("tree_sha", treeSha)
                put("files", GitHubCommitReconciliation.filesReceipt(arguments.getValue("files") as JsonArray))
            }
        }
        suspend fun read(path: String): JsonElement = when {
            path == root -> buildJsonObject { put("default_branch", "main") }
            path.startsWith("$root/branches/") -> buildJsonObject { put("protected", protected) }
            path.startsWith("$root/rules/branches/") -> JsonArray(if (hasRules) listOf(buildJsonObject { put("type", "deletion") }) else emptyList())
            path.startsWith("$root/pulls?") -> JsonArray(if (openPr) listOf(buildJsonObject { put("number", 12) }) else emptyList())
            path.startsWith("$root/actions/runs?") -> buildJsonObject { put("total_count", if (activeRun) 1 else 0) }
            path.startsWith("$root/git/matching-refs/heads/") -> JsonArray(head?.let { listOf(ref(it)) }.orEmpty())
            path.startsWith("$root/git/ref/heads/") -> ref(requireNotNull(head))
            path.startsWith("$root/git/commits/") -> commits.getValue(path.substringAfterLast('/'))
            path.startsWith("$root/git/trees/") -> {
                val sha = path.substringAfterLast('/').substringBefore('?')
                val value = trees.getValue(sha)
                if (unexpectedTreeChange && sha == "3".repeat(40)) {
                    JsonObject(
                        value + (
                            "tree" to JsonArray(
                                (value.getValue("tree") as JsonArray) + buildJsonObject {
                                    put("path", "unrelated.txt")
                                    put("sha", "7".repeat(40))
                                    put("type", "blob")
                                    put("mode", "100644")
                                }
                            )
                            )
                    )
                } else {
                    value
                }
            }
            else -> error("Unexpected request $path")
        }
        private fun ref(sha: String) = buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("object", buildJsonObject { put("sha", sha) })
        }
        suspend fun delete(path: String) {
            assertEquals("$root/git/refs/heads/${GitHubWorkspaceClient.segment(branch)}", path)
            deletes++
            head = if (recreateAfterDelete) second else null
            if (failAfterDelete) throw IOException("lost deletion response")
        }
        suspend fun clean(ledger: GitHubOwnedFixtures, expected: String, selected: String = "main") = ledger.cleanup(root, cleanup(expected), selected, ::read, ::delete) { pending -> GitHubCommitReconciliation.recover(root, pending, request = ::read) }
    }

    @Test fun ownedContentAndStagedRevisionSurviveRestartAndCleanWithoutMerging() = runBlocking {
        val directory = Files.createTempDirectory("github-fixture").toFile()
        try {
            var ledger = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "account:connection:conversation:task")
            val remote = Remote()
            ledger.register(root, creation(), created())
            for ((revised, parent, sha) in listOf(Triple(false, origin, first), Triple(true, first, second))) {
                val intent = args(parent, revised)
                ledger.prepareCommit(root, intent)
                ledger.acceptCommit(root, intent, remote.publish(intent, sha))
                ledger = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "account:connection:conversation:task")
            }
            val result = remote.clean(ledger, second)
            assertEquals(JsonPrimitive(true), result["ownership_verified"])
            assertEquals(JsonPrimitive(true), result["absence_verified"])
            assertEquals(JsonPrimitive(false), result["atomic_head_precondition"])
            assertEquals(JsonPrimitive(origin), result["origin_head_sha"])
            assertEquals(2, remote.publications)
            assertEquals(1, remote.deletes)
            assertEquals(null, remote.head)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun circuitRejectionBeforeDeleteReleasesOnlyTheUnsentIntent() = runBlocking {
        val ledger = GitHubOwnedFixtures(GitHubRepositoryStateStore(), "account:connection:conversation:task")
        val remote = Remote()
        ledger.register(root, creation(), created())
        val failure = runCatching {
            ledger.cleanup(root, cleanup(origin), "main", remote::read, { throw GitHubRequestCircuit.Open(30_000) }) { null }
        }.exceptionOrNull()
        assertTrue(failure is GitHubRequestCircuit.Open)
        assertEquals(origin, remote.head)
        assertEquals(0, remote.deletes)
        val result = remote.clean(ledger, origin)
        assertEquals(JsonPrimitive(true), result["absence_verified"])
        assertEquals(1, remote.deletes)
    }

    private fun awaiting(intent: JsonObject) = buildJsonObject {
        put("engineering_task_id", "saved-approval")
        put("status", "AWAITING_APPROVAL")
        put("committed", false)
        put("branch", branch)
        put("expected_head_sha", intent.getValue("expected_head_sha"))
    }

    private fun approvalStatus(intent: JsonObject, state: String = "AWAITING_APPROVAL", cancelled: Boolean = false) = buildJsonObject {
        put("engineering_task_id", "saved-approval")
        put("state", state)
        put("branch", branch)
        put("publication_intent", intent)
        put("cancelled_before_publication", cancelled)
    }

    @Test fun approvalAndFixtureSurviveRestartWithoutPublicationOrCleanup() = runBlocking {
        val directory = Files.createTempDirectory("github-fixture-approval").toFile()
        try {
            val original = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
            val remote = Remote()
            val intent = args(origin, false)
            original.register(root, creation(), created())
            original.prepareCommit(root, intent)
            original.awaitApproval(root, intent, awaiting(intent))
            val restored = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
            val status = restored.recoverApproval(root, approvalStatus(intent), remote::read, { remote.head == null }) { error("An active approval cannot reconcile or publish") }
            assertEquals(JsonPrimitive("WAITING_FOR_APPROVAL"), status?.get("cleanup_status"))
            assertEquals(JsonPrimitive("DELIBERATELY_RETAINED"), status?.get("lifecycle_state"))
            assertEquals(JsonPrimitive("APPROVE_OR_CANCEL_EXISTING_PATCH"), status?.get("next_action"))
            assertEquals(JsonPrimitive("saved-approval"), status?.get("engineering_task_id"))
            assertTrue(runCatching { remote.clean(restored, origin) }.exceptionOrNull()?.message.orEmpty().contains("WAITING_FOR_APPROVAL"))
            assertEquals(0, remote.publications)
            assertEquals(0, remote.deletes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun approvedPublicationIsReadBackAfterRestartAndNeverPublishedAgain() = runBlocking {
        val directory = Files.createTempDirectory("github-fixture-approved").toFile()
        try {
            val original = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
            val remote = Remote()
            val intent = args(origin, false)
            original.register(root, creation(), created())
            original.prepareCommit(root, intent)
            original.awaitApproval(root, intent, awaiting(intent))
            remote.publish(intent, first)
            val restored = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
            val proof = restored.recoverApproval(root, approvalStatus(intent, "MONITORING_CI"), remote::read, { remote.head == null }) { saved -> GitHubCommitReconciliation.recover(root, saved, request = remote::read) }
            assertEquals(JsonPrimitive("PUBLISHED"), proof?.get("approval_state"))
            assertEquals(JsonPrimitive(true), proof?.get("remote_state_verified"))
            assertEquals(JsonPrimitive(first), proof?.get("sha"))
            assertEquals(proof, restored.recoverApproval(root, approvalStatus(intent, "MONITORING_CI"), remote::read, { remote.head == null }) { error("Completed publication must not be reconciled twice") })
            assertEquals(JsonPrimitive(true), remote.clean(restored, first)["absence_verified"])
            assertEquals(1, remote.publications)
            assertEquals(1, remote.deletes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun cancelledApprovalRequiresUnchangedHeadThenRetainsCleanupUntilVerified() = runBlocking {
        for (moved in listOf(false, true)) {
            val directory = Files.createTempDirectory("github-fixture-cancelled").toFile()
            try {
                val original = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
                val remote = Remote()
                val intent = args(origin, false)
                original.register(root, creation(), created())
                original.prepareCommit(root, intent)
                original.awaitApproval(root, intent, awaiting(intent))
                if (moved) remote.publish(intent, first)
                val restored = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
                val status = approvalStatus(intent, "CANCELLED", cancelled = true)
                val result = runCatching { restored.recoverApproval(root, status, remote::read, { remote.head == null }) { error("Prepublication cancellation cannot retry publication") } }
                if (moved) {
                    assertTrue(result.isFailure)
                    assertEquals(0, remote.deletes)
                } else {
                    val cancelled = result.getOrThrow()
                    assertEquals(JsonPrimitive("CANCELLED_BEFORE_PUBLICATION"), cancelled?.get("approval_state"))
                    assertEquals(JsonPrimitive("PENDING"), cancelled?.get("cleanup_status"))
                    assertEquals(cancelled, restored.recoverApproval(root, status, remote::read, { remote.head == null }) { error("Cancellation cannot restore a pending publication") })
                    assertEquals(JsonPrimitive(true), remote.clean(restored, origin)["absence_verified"])
                    assertEquals(0, remote.publications)
                    assertEquals(1, remote.deletes)
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun legacyApprovalAdoptsOnlyTheExistingOwnedSyntheticIntent() = runBlocking {
        val store = GitHubRepositoryStateStore()
        val original = GitHubOwnedFixtures(store, "task")
        val remote = Remote()
        val intent = args(origin, false)
        original.register(root, creation(), created())
        val restored = GitHubOwnedFixtures(store, "task")
        val status = restored.recoverApproval(root, approvalStatus(intent), remote::read, { remote.head == null }) { error("Waiting for native approval") }
        assertEquals(JsonPrimitive("WAITING_FOR_APPROVAL"), status?.get("cleanup_status"))
        assertEquals(0, remote.publications)
        assertEquals(0, remote.deletes)
        val foreign = JsonObject(approvalStatus(intent) + ("engineering_task_id" to JsonPrimitive("other-task")))
        assertTrue(runCatching { restored.recoverApproval(root, foreign, remote::read, { false }) { null } }.isFailure)
    }

    @Test fun lostContentReceiptUsesCompleteRemoteProofBeforeCleanupWithoutRepeatingPublication() = runBlocking {
        val directory = Files.createTempDirectory("github-fixture-pending").toFile()
        try {
            val remote = Remote()
            val original = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
            original.register(root, creation(), created())
            original.prepareCommit(root, args(origin, false))
            remote.publish(args(origin, false), first)
            val restored = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
            assertEquals(JsonPrimitive(true), remote.clean(restored, first)["absence_verified"])
            assertEquals(1, remote.publications)
            assertEquals(1, remote.deletes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun creationOnlyRecoveryReturnsPendingButCannotAuthorizeDeletionReconciliationAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("github-fixture-before-delete").toFile()
        try {
            GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task").register(root, creation(), created())
            val restored = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
            val recovery = buildJsonObject {
                put("fixture_plan", GitHubOwnedFixtures.PLAN)
                put("branch", branch)
                put("base_sha", origin)
                put("commit_sha", origin)
            }
            var reads = 0
            var absenceProbes = 0
            val status = restored.reconcile(
                root,
                recovery,
                read = {
                    reads++
                    JsonObject(emptyMap())
                },
                expectedAbsence = {
                    absenceProbes++
                    true
                },
                approvalStatus = { error("Creation has no approval to recover") },
                reconcile = { error("Creation has no publication to reconcile") }
            )
            assertEquals(JsonPrimitive("PENDING"), status["cleanup_status"])
            assertFalse(status["deleted"] == JsonPrimitive(true))
            assertFalse(status["absence_verified"] == JsonPrimitive(true))
            val failure = runCatching {
                restored.reconcileDeletion(root, recovery, read = {
                    reads++
                    JsonObject(emptyMap())
                }, expectedAbsence = {
                    absenceProbes++
                    true
                })
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().startsWith("FIXTURE_RECONCILE_REQUIRED:"))
            assertEquals(0, reads)
            assertEquals(0, absenceProbes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun lostDeletionResponseIsReconciledByAbsenceAfterRestartAndNeverReplayed() = runBlocking {
        for (recreated in listOf(false, true)) {
            val directory = Files.createTempDirectory("github-fixture-delete").toFile()
            try {
                val ledger = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
                val remote = Remote().apply {
                    failAfterDelete = true
                    recreateAfterDelete = recreated
                }
                ledger.register(root, creation(), created())
                assertTrue(runCatching { remote.clean(ledger, origin) }.isFailure)
                val restored = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
                val recovered = runCatching { remote.clean(restored, origin) }
                if (recreated) {
                    assertTrue(recovered.isFailure)
                } else {
                    assertEquals(JsonPrimitive(true), recovered.getOrThrow()["absence_verified"])
                    assertEquals(JsonPrimitive(true), recovered.getOrThrow()["reconciled"])
                }
                assertEquals(1, remote.deletes)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun restoredDeletionRequiresOriginalAccountAndFreshContentAccessBeforeAnExpected404() = runBlocking {
        val store = GitHubRepositoryStateStore()
        val remote = Remote().apply { failAfterDelete = true }
        val owned = GitHubOwnedFixtures(store, "account:task")
        owned.register(root, creation(), created())
        assertTrue(runCatching { remote.clean(owned, origin) }.isFailure)
        var absenceProbes = 0
        var replayedDeletes = 0
        for (problem in listOf("account", "access", "wrong-sha", "read-budget")) {
            val restored = GitHubOwnedFixtures(store, if (problem == "account") "replacement-account:task" else "account:task")
            val result = runCatching {
                restored.cleanup(root, cleanup(origin), "main", read = { path ->
                    assertEquals("$root/git/commits/$origin", path)
                    when (problem) {
                        "access" -> throw GitHubRequestRejected(404, "Original content is inaccessible")
                        "wrong-sha" -> buildJsonObject { put("sha", "e".repeat(40)) }
                        "read-budget" -> throw IllegalStateException("Synthetic read admission budget exhausted")
                        else -> remote.read(path)
                    }
                }, delete = { replayedDeletes++ }, expectedAbsence = {
                    absenceProbes++
                    true
                }, reconcile = { null })
            }
            assertTrue(problem, result.isFailure)
            assertEquals(0, absenceProbes)
            assertEquals(0, replayedDeletes)
        }
        val restored = GitHubOwnedFixtures(store, "account:task")
        val result = restored.cleanup(root, cleanup(origin), "main", read = remote::read, delete = { replayedDeletes++ }, expectedAbsence = { path ->
            assertEquals("$root/git/ref/heads/${GitHubWorkspaceClient.segment(branch)}", path)
            absenceProbes++
            true
        }, reconcile = { null })
        assertEquals(JsonPrimitive(true), result["absence_verified"])
        assertEquals(JsonPrimitive("COMPLETED"), result["deletion_state"])
        assertEquals(1, absenceProbes)
        assertEquals(0, replayedDeletes)
        assertEquals(1, remote.deletes)
    }

    @Test fun unrelatedChangesForeignScopeAndEveryDependentBlockBeforeDeletion() = runBlocking {
        for (blocker in listOf("foreign-task", "protected", "rules", "pr", "run", "selected", "changed-head", "unrelated-tree")) {
            val store = GitHubRepositoryStateStore()
            val ledger = GitHubOwnedFixtures(store, "task")
            val remote = Remote()
            ledger.register(root, creation(), created())
            for ((revised, parent, sha) in listOf(Triple(false, origin, first), Triple(true, first, second))) {
                val intent = args(parent, revised)
                ledger.prepareCommit(root, intent)
                ledger.acceptCommit(root, intent, remote.publish(intent, sha))
            }
            remote.protected = blocker == "protected"
            remote.hasRules = blocker == "rules"
            remote.openPr = blocker == "pr"
            remote.activeRun = blocker == "run"
            remote.unexpectedTreeChange = blocker == "unrelated-tree"
            if (blocker == "changed-head") remote.head = "e".repeat(40)
            val execution = if (blocker == "foreign-task") GitHubOwnedFixtures(store, "another-task") else ledger
            assertTrue(blocker, runCatching { remote.clean(execution, second, if (blocker == "selected") branch else "main") }.isFailure)
            assertEquals(blocker, 0, remote.deletes)
        }
    }

    @Test fun missingOwnershipOrInterruptedUnprovedContentCannotAuthorizeCleanup() = runBlocking {
        val ledger = GitHubOwnedFixtures(GitHubRepositoryStateStore(), "task")
        val remote = Remote()
        assertTrue(runCatching { ledger.register(root, creation(), JsonObject(created() + ("created" to JsonPrimitive(false)))) }.isFailure)
        assertTrue(runCatching { remote.clean(ledger, origin) }.isFailure)
        ledger.register(root, creation(), created())
        ledger.prepareCommit(root, args(origin, false))
        assertTrue(runCatching { remote.clean(ledger, origin) }.isFailure)
        assertEquals(0, remote.deletes)
    }

    @Test fun acceptedDeletionWithoutObservedAbsenceDoesNotClaimDeletedAndIsNeverRepeated() = runBlocking {
        val ledger = GitHubOwnedFixtures(GitHubRepositoryStateStore(), "task")
        val remote = Remote().apply { recreateAfterDelete = true }
        ledger.register(root, creation(), created())
        val result = remote.clean(ledger, origin)
        assertEquals(JsonPrimitive(false), result["deleted"])
        assertEquals(JsonPrimitive(false), result["absence_verified"])
        assertEquals(JsonPrimitive("REF_STILL_PRESENT"), result["verification_status"])
        assertTrue(runCatching { remote.clean(ledger, origin) }.isFailure)
        assertEquals(1, remote.deletes)
    }

    @Test fun onlyTheExactSyntheticPatchAndBlobLeaseCanUseOwnedCleanup() = runBlocking {
        val ledger = GitHubOwnedFixtures(GitHubRepositoryStateStore(), "task")
        ledger.register(root, creation(), created())
        for (key in listOf("branch", "expected_head_sha", "message", "files")) {
            val replacement = when (key) {
                "branch" -> JsonPrimitive("codex-self-test/" + "e".repeat(20))
                "files" -> JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", "README.md")
                            put("content", "unrelated")
                        }
                    )
                )
                else -> JsonPrimitive("invalid")
            }
            val changed = JsonObject(args(origin, false) + (key to replacement))
            // An unregistered branch has no cleanup authority at all; ordinary
            // user writes to it remain governed by the normal commit safeguards.
            if (key == "branch") continue
            assertTrue(key, runCatching { ledger.prepareCommit(root, changed) }.isFailure)
        }
    }

    @Test fun ownershipIsProtectedFromReadCachePruningAndCannotBeRemovedAsCache() = runBlocking {
        val directory = Files.createTempDirectory("github-fixture-retained").toFile()
        try {
            val store = GitHubRepositoryStateStore(directory)
            GitHubOwnedFixtures(store, "task").register(root, creation(), created())
            repeat(300) { store.write("cache", "source-$it", buildJsonObject { put("value", it) }) }
            val key = "fixture:" + GitHubRepositoryStateStore.digest("$root|$branch")
            assertTrue(runCatching { store.remove("task", key) }.isFailure)
            val restored = GitHubOwnedFixtures(GitHubRepositoryStateStore(directory), "task")
            val result = Remote().clean(restored, origin)
            assertEquals(JsonPrimitive(true), result["absence_verified"])
            assertFalse(result["atomic_head_precondition"] == JsonPrimitive(true))
        } finally {
            directory.deleteRecursively()
        }
    }
}
