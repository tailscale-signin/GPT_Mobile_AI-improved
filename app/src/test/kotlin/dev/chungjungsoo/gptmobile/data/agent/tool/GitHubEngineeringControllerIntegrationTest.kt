package dev.chungjungsoo.gptmobile.data.agent.tool

import android.app.Application
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.coEvery
import io.mockk.mockk
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubEngineeringControllerIntegrationTest {
    // Robolectric replaces the application data directory between methods while
    // application singletons can survive in the reused sandbox class loader.
    @Before fun isolateControllerDatabase() {
        closeControllerDatabase()
    }

    @After fun releaseControllerDatabase() {
        closeControllerDatabase()
    }

    @Test fun approvedPatchPublishesOnceAndRestartCompletesOnlyFromExactCiEvidence() = runBlocking {
        verifyApprovedWorkflow(delete = false)
    }

    @Test fun approvedDeletionPublishesNullTreeEntryWithoutCreatingAnEmptyBlob() = runBlocking {
        verifyApprovedWorkflow(delete = true)
    }

    @Test fun durableRestartsResumeObjectCheckpointsAndReconcileAnAcceptedPushWithoutRepeatingWrites() = runBlocking {
        verifyApprovedWorkflow(delete = false, interruptAtBoundaries = true)
    }

    @Test fun replacingAPendingReviewRetainsTheBindingBeforeCancellationAndSurvivesBindingFailure() = runBlocking {
        val controller = reviewController()
        val original = prepareReview(controller, "original", requiresHardware = true)
        val replacement = prepareReview(controller, "edited", requiresHardware = true)
        val digest = requireNotNull(controller.run(original)).patchDigest
        val failure = runCatching {
            controller.replacePendingReview(original, digest, replacement) {
                assertEquals("AWAITING_APPROVAL", controller.task(original)?.state)
                error("Binding storage unavailable")
            }
        }.exceptionOrNull()
        assertEquals("Binding storage unavailable", failure?.message)
        assertEquals("AWAITING_APPROVAL", controller.task(original)?.state)
        assertFalse(requireNotNull(controller.run(original)).approvalRevoked)
        assertEquals("AWAITING_APPROVAL", controller.task(replacement)?.state)

        var bindingRetained = false
        controller.replacePendingReview(original, digest, replacement) {
            assertEquals("AWAITING_APPROVAL", controller.task(original)?.state)
            bindingRetained = true
        }
        assertTrue(bindingRetained)
        closeControllerDatabase()
        val restored = reviewController()
        assertEquals("CANCELLED", restored.task(original)?.state)
        assertTrue(requireNotNull(restored.run(original)).approvalRevoked)
        assertEquals("AWAITING_APPROVAL", restored.task(replacement)?.state)
        assertTrue(requireNotNull(restored.task(replacement)).requiresHardwareVerification)
        assertTrue(GitHubEngineeringDatabaseFactory.open(RuntimeEnvironment.getApplication()).ledgerDao().operationsForTask(original).isEmpty())
    }

    @Test fun approvalThatWinsTheRepositoryLockCannotBeCancelledByAnEdit() = runBlocking {
        val controller = reviewController()
        val original = prepareReview(controller, "original")
        val replacement = prepareReview(controller, "edited")
        val digest = requireNotNull(controller.run(original)).patchDigest
        controller.approve(original, digest)
        var bindingAttempted = false

        assertTrue(runCatching { controller.replacePendingReview(original, digest, replacement) { bindingAttempted = true } }.isFailure)

        assertFalse(bindingAttempted)
        assertEquals("COMMITTING", controller.task(original)?.state)
        assertFalse(requireNotNull(controller.run(original)).approvalRevoked)
        assertEquals("AWAITING_APPROVAL", controller.task(replacement)?.state)
    }

    @Test fun replacementCannotRemoveHardwareVerificationOrRetireItself() = runBlocking {
        val controller = reviewController()
        val original = prepareReview(controller, "original", requiresHardware = true)
        val replacement = prepareReview(controller, "edited", requiresHardware = false)
        val digest = requireNotNull(controller.run(original)).patchDigest
        var bindingAttempted = false

        assertTrue(runCatching { controller.replacePendingReview(original, digest, replacement) { bindingAttempted = true } }.isFailure)
        assertTrue(runCatching { controller.replacePendingReview(original, digest, original) { bindingAttempted = true } }.isFailure)

        assertFalse(bindingAttempted)
        assertEquals("AWAITING_APPROVAL", controller.task(original)?.state)
        assertFalse(requireNotNull(controller.run(original)).approvalRevoked)
    }

    private suspend fun prepareReview(controller: GitHubEngineeringController, content: String, requiresHardware: Boolean = false): String =
        controller.prepare("connection", "owner", "repo", "fix/workflow", "a".repeat(40), listOf(EngineeringFilePatch("A.kt", "before", content)), "Fix", requiresHardware)

    private fun reviewController(): GitHubEngineeringController {
        val context = RuntimeEnvironment.getApplication()
        val transport = object : GitHubEngineeringApi({ null }) {
            override suspend fun request(path: String, method: String, body: JSONObject?): String {
                assertEquals("GET", method)
                return when (path) {
                    "/user" -> """{"id":42,"login":"fixture"}"""
                    "/repos/owner/repo" -> """{"default_branch":"main"}"""
                    "/repos/owner/repo/git/ref/heads/fix%2Fworkflow" -> JSONObject().put("object", JSONObject().put("sha", "a".repeat(40))).toString()
                    else -> error("Unexpected review request: $path")
                }
            }
        }
        val factory = object : GitHubEngineeringApiFactory() {
            override fun create(token: suspend () -> String?) = transport
        }
        val queue = object : GitHubEngineeringWorkQueue(context) {
            override fun cancel(taskId: String) = Unit
        }
        val connection = ToolConnection(
            connectionUid = "connection",
            name = "GitHub fixture",
            alias = "github-fixture",
            type = ToolConnectionType.GITHUB,
            endpointUrl = "https://api.github.com",
            authType = ToolConnectionAuthType.BEARER,
            secretRef = null,
            oauthClientId = null
        )
        val connections = mockk<ToolConnectionRepository> { coEvery { getConnection("connection") } returns connection }
        return GitHubEngineeringController(context, connections, mockk<SecretVault>(), factory, mockk<BenchmarkStore>(), queue)
    }

    // The production factory has process lifetime. Closing its real Room database
    // and clearing only that singleton simulates a process restart without changing
    // rows, task state, operation receipts, or the remote repository fixture.
    private fun closeControllerDatabase() {
        val field = GitHubEngineeringDatabaseFactory::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? GitHubEngineeringDatabase)?.close()
        field.set(null, null)
    }

    private suspend fun verifyApprovedWorkflow(delete: Boolean, interruptAtBoundaries: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        val base = "a".repeat(40)
        val baseTree = "b".repeat(40)
        val tree = "c".repeat(40)
        val commit = "d".repeat(40)
        var remote = base
        val writes = mutableListOf<String>()
        val queued = mutableListOf<String>()
        var interruptBefore: String? = null
        var loseRefResponse = false
        var checkHead = commit
        var checkApp = 7
        var checkStatus = "completed"
        var checkPublished = true
        fun blob(text: String): String {
            val bytes = text.toByteArray()
            return MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes).joinToString("") { "%02x".format(it) }
        }
        val transport = object : GitHubEngineeringApi({ null }) {
            override suspend fun request(path: String, method: String, body: JSONObject?): String {
                if (interruptBefore == "$method $path") {
                    interruptBefore = null
                    throw CancellationException("Interrupted before $method $path")
                }
                if (method != "GET") writes += "$method $path"
                return when {
                    path == "/user" -> """{"id":42,"login":"fixture"}"""
                    path.endsWith("/git/ref/heads/fix%2Fworkflow") -> JSONObject().put("object", JSONObject().put("sha", remote)).toString()
                    path.endsWith("/git/commits/$base") -> JSONObject().put("tree", JSONObject().put("sha", baseTree)).toString()
                    path.endsWith("/git/trees/$baseTree?recursive=1") -> JSONObject().put("truncated", false).put("tree", JSONArray().put(JSONObject().put("path", "A.kt").put("type", "blob").put("mode", "100644").put("sha", blob("before")))).toString()
                    method == "POST" && path.endsWith("/git/blobs") -> {
                        assertFalse(delete)
                        JSONObject().put("sha", blob(requireNotNull(body).getString("content"))).toString()
                    }
                    method == "POST" && path.endsWith("/git/trees") -> {
                        val entry = requireNotNull(body).getJSONArray("tree").getJSONObject(0)
                        assertEquals("A.kt", entry.getString("path"))
                        if (delete) assertTrue(entry.isNull("sha")) else assertEquals(blob("after"), entry.getString("sha"))
                        JSONObject().put("sha", tree).toString()
                    }
                    method == "POST" && path.endsWith("/git/commits") -> JSONObject().put("sha", commit).toString()
                    path.endsWith("/git/commits/$commit") -> JSONObject().put("sha", commit).put("tree", JSONObject().put("sha", tree)).put("parents", JSONArray().put(JSONObject().put("sha", base))).toString()
                    method == "PATCH" -> {
                        assertFalse(requireNotNull(body).getBoolean("force"))
                        assertEquals(commit, body.getString("sha"))
                        remote = commit
                        if (loseRefResponse) {
                            loseRefResponse = false
                            throw CancellationException("Remote ref accepted; response lost before local confirmation")
                        }
                        "{}"
                    }
                    "/protection/required_status_checks" in path -> """{"contexts":["unit"],"checks":[{"context":"unit","app_id":7}]}"""
                    "/rules/branches/" in path -> "[]"
                    "/check-runs?" in path -> JSONObject().put("total_count", if (checkPublished) 1 else 0).put(
                        "check_runs",
                        JSONArray().apply {
                            if (checkPublished) put(JSONObject().put("id", 1).put("name", "unit").put("head_sha", checkHead).put("status", checkStatus).put("conclusion", "success").put("app", JSONObject().put("id", checkApp)))
                        }
                    ).toString()
                    "/statuses?" in path -> "[]"
                    path == "/repos/owner/repo" -> """{"default_branch":"main"}"""
                    else -> error("Unexpected fake GitHub request: $method $path")
                }
            }
        }
        val factory = object : GitHubEngineeringApiFactory() {
            override fun create(token: suspend () -> String?) = transport
        }
        val queue = object : GitHubEngineeringWorkQueue(context) {
            override fun enqueue(taskId: String) {
                queued += taskId
            }
            override fun cancel(taskId: String) = Unit
        }
        val connection = ToolConnection(
            connectionUid = "connection",
            name = "GitHub fixture",
            alias = "github-fixture",
            type = ToolConnectionType.GITHUB,
            endpointUrl = "https://api.github.com",
            authType = ToolConnectionAuthType.BEARER,
            secretRef = null,
            oauthClientId = null
        )
        val connections = mockk<ToolConnectionRepository> {
            coEvery { getConnection(connection.connectionUid) } returns connection
        }
        fun createController() = GitHubEngineeringController(context, connections, mockk<SecretVault>(), factory, mockk<BenchmarkStore>(), queue)
        var controller = createController()
        val taskId = controller.prepare("connection", "owner", "repo", "fix/workflow", base, listOf(EngineeringFilePatch("A.kt", "before", if (delete) "" else "after", delete)), "Fix")
        suspend fun state() = controller.recent().single { it.taskId == taskId }.state
        suspend fun operation() = GitHubEngineeringDatabaseFactory.open(context).ledgerDao().operationsForTask(taskId).single()
        suspend fun restart() {
            val beforeWrites = writes.toList()
            val beforeRun = requireNotNull(controller.run(taskId))
            closeControllerDatabase()
            controller = createController()
            controller.recover()
            val retained = requireNotNull(controller.run(taskId))
            assertEquals(beforeWrites, writes)
            assertEquals(beforeRun.patchJson, retained.patchJson)
            assertEquals(beforeRun.patchDigest, retained.patchDigest)
            assertEquals(beforeRun.approvedActor, retained.approvedActor)
        }
        suspend fun interruptedExecute() {
            val failure = runCatching { controller.execute(taskId) }.exceptionOrNull()
            assertTrue("Injected interruption must escape the controller", failure is CancellationException)
            assertEquals("COMMITTING", state())
            assertTrue(controller.events(taskId).none { it.evidence == EngineeringEvidence.REQUIRED_CHECKS_PASSED.name })
        }
        assertTrue(writes.isEmpty())
        if (interruptAtBoundaries) {
            restart()
            assertEquals("AWAITING_APPROVAL", state())
            assertEquals(taskId, controller.prepare("connection", "owner", "repo", "fix/workflow", base, listOf(EngineeringFilePatch("A.kt", "before", "after")), "Fix"))
        }
        assertTrue(runCatching { controller.execute(taskId) }.isFailure)
        assertTrue(writes.isEmpty())
        controller.approve(taskId, requireNotNull(controller.run(taskId)).patchDigest)
        if (interruptAtBoundaries) {
            restart()
            assertEquals("COMMITTING", state())
            assertTrue(writes.isEmpty())
            interruptBefore = "POST /repos/owner/repo/git/trees"
            interruptedExecute()
            val original = operation()
            assertEquals("BLOBS_STAGED", original.stage)
            assertEquals(listOf("POST /repos/owner/repo/git/blobs"), writes)
            restart()

            interruptBefore = "POST /repos/owner/repo/git/commits"
            interruptedExecute()
            assertEquals("TREE_CREATED", operation().stage)
            assertEquals(tree, operation().treeSha)
            assertEquals(1, writes.count { it.endsWith("/git/blobs") })
            assertEquals(1, writes.count { it.endsWith("/git/trees") })
            restart()

            interruptBefore = "GET /repos/owner/repo/git/commits/$commit"
            interruptedExecute()
            assertEquals("COMMIT_CREATED", operation().stage)
            assertEquals(commit, operation().commitSha)
            assertEquals(1, writes.count { it.endsWith("/git/commits") })
            restart()
            assertEquals("COMMITTING", state())
            assertEquals(base, remote)
            assertTrue(writes.none { it.startsWith("PATCH ") })

            loseRefResponse = true
            interruptedExecute()
            assertEquals(commit, remote)
            assertEquals("COMMIT_CREATED", operation().stage)
            restart()
            assertEquals("REF_UPDATED", operation().stage)
            assertEquals(commit, operation().remoteRefSha)
            assertEquals(original.operationId, operation().operationId)
            assertEquals(original.idempotencyToken, operation().idempotencyToken)
            assertEquals("CI_PENDING", state())
        } else {
            assertEquals(commit, controller.execute(taskId))
        }
        assertEquals(1, writes.count { it.startsWith("PATCH ") })
        assertTrue(taskId in queued)
        if (interruptAtBoundaries) {
            interruptBefore = "GET /repos/owner/repo/commits/$commit/check-runs?filter=latest&per_page=100&page=1"
            assertTrue(runCatching { controller.pollCi(taskId) }.exceptionOrNull() is CancellationException)
            restart()
            suspend fun remainsPending() {
                assertEquals(EngineeringWorkerOutcome.RETRY, controller.pollCi(taskId))
                assertEquals("CI_PENDING", state())
                assertTrue(controller.events(taskId).none { it.evidence == EngineeringEvidence.REQUIRED_CHECKS_PASSED.name })
                restart()
                assertEquals("CI_PENDING", state())
            }
            checkHead = base
            remainsPending()
            checkHead = commit
            checkApp = 8
            remainsPending()
            checkApp = 7
            checkPublished = false
            remainsPending()
            checkPublished = true
            checkStatus = "in_progress"
            remainsPending()
            checkStatus = "completed"
        }
        controller = createController()
        controller.recover()
        assertEquals(EngineeringWorkerOutcome.VERIFIED, controller.pollCi(taskId))
        assertEquals("COMPLETED", state())
        val verified = requireNotNull(controller.run(taskId)?.verifiedChecksJson)
        assertNotNull(verified)
        val receipt = JSONObject(verified)
        assertEquals(commit, receipt.getString("sha"))
        val check = receipt.getJSONArray("checks").getJSONObject(0)
        assertEquals(commit, check.getString("headSha"))
        assertEquals(7, check.getInt("appId"))
        assertEquals("SUCCESS", check.getString("conclusion"))
        assertEquals(1, writes.count { it.startsWith("PATCH ") })
        assertEquals(EngineeringWorkerOutcome.VERIFIED, controller.pollCi(taskId))
        if (interruptAtBoundaries) {
            restart()
            assertEquals(EngineeringWorkerOutcome.VERIFIED, controller.pollCi(taskId))
            assertEquals(
                listOf("POST /repos/owner/repo/git/blobs", "POST /repos/owner/repo/git/trees", "POST /repos/owner/repo/git/commits", "PATCH /repos/owner/repo/git/refs/heads/fix%2Fworkflow"),
                writes
            )
            assertEquals(1, controller.events(taskId).count { it.evidence == EngineeringEvidence.REQUIRED_CHECKS_PASSED.name })
        }
    }
}
