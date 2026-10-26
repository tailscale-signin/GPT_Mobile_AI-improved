package dev.chungjungsoo.gptmobile.data.agent.tool

import android.app.Application
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.mockk
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubEngineeringControllerIntegrationTest {
    @Test fun approvedPatchPublishesOnceAndRestartCompletesOnlyFromExactCiEvidence() = runBlocking {
        val context = RuntimeEnvironment.getApplication<Application>()
        val base = "a".repeat(40)
        val baseTree = "b".repeat(40)
        val tree = "c".repeat(40)
        val commit = "d".repeat(40)
        var remote = base
        val writes = mutableListOf<String>()
        val queued = mutableListOf<String>()
        fun blob(text: String): String {
            val bytes = text.toByteArray()
            return MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes).joinToString("") { "%02x".format(it) }
        }
        val transport = object : GitHubEngineeringApi({ null }) {
            override suspend fun request(path: String, method: String, body: JSONObject?): String {
                if (method != "GET") writes += "$method $path"
                return when {
                    path == "/user" -> """{"id":42,"login":"fixture"}"""
                    path.endsWith("/git/ref/heads/fix%2Fworkflow") -> JSONObject().put("object", JSONObject().put("sha", remote)).toString()
                    path.endsWith("/git/commits/$base") -> JSONObject().put("tree", JSONObject().put("sha", baseTree)).toString()
                    path.endsWith("/git/trees/$baseTree?recursive=1") -> JSONObject().put("truncated", false).put("tree", JSONArray().put(JSONObject().put("path", "A.kt").put("type", "blob").put("mode", "100644").put("sha", blob("before")))).toString()
                    method == "POST" && path.endsWith("/git/blobs") -> JSONObject().put("sha", blob(requireNotNull(body).getString("content"))).toString()
                    method == "POST" && path.endsWith("/git/trees") -> JSONObject().put("sha", tree).toString()
                    method == "POST" && path.endsWith("/git/commits") -> JSONObject().put("sha", commit).toString()
                    path.endsWith("/git/commits/$commit") -> JSONObject().put("sha", commit).put("tree", JSONObject().put("sha", tree)).put("parents", JSONArray().put(JSONObject().put("sha", base))).toString()
                    method == "PATCH" -> {
                        assertFalse(requireNotNull(body).getBoolean("force"))
                        assertEquals(commit, body.getString("sha"))
                        remote = commit
                        "{}"
                    }
                    "/protection/required_status_checks" in path -> """{"contexts":["unit"],"checks":[{"context":"unit","app_id":7}]}"""
                    "/rules/branches/" in path -> "[]"
                    "/check-runs?" in path -> JSONObject().put("total_count", 1).put("check_runs", JSONArray().put(JSONObject().put("id", 1).put("name", "unit").put("head_sha", commit).put("status", "completed").put("conclusion", "success").put("app", JSONObject().put("id", 7)))).toString()
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
        fun createController() = GitHubEngineeringController(context, mockk<ToolConnectionRepository>(), mockk<SecretVault>(), factory, mockk<BenchmarkStore>(), queue)
        val controller = createController()
        val taskId = controller.prepare("connection", "owner", "repo", "fix/workflow", base, listOf(EngineeringFilePatch("A.kt", "before", "after")), "Fix")
        assertTrue(writes.isEmpty())
        assertTrue(runCatching { controller.execute(taskId) }.isFailure)
        assertTrue(writes.isEmpty())
        controller.approve(taskId, requireNotNull(controller.run(taskId)).patchDigest)
        assertEquals(commit, controller.execute(taskId))
        assertEquals(1, writes.count { it.startsWith("PATCH ") })
        assertTrue(taskId in queued)
        val restarted = createController()
        restarted.recover()
        assertEquals(EngineeringWorkerOutcome.VERIFIED, restarted.pollCi(taskId))
        assertEquals("COMPLETED", restarted.recent().single { it.taskId == taskId }.state)
        assertNotNull(restarted.run(taskId)?.verifiedChecksJson)
        assertEquals(1, writes.count { it.startsWith("PATCH ") })
        assertEquals(EngineeringWorkerOutcome.VERIFIED, restarted.pollCi(taskId))
    }
}
