package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import org.junit.runner.RunWith

/** Real Android binding/client/receipt code with a closed in-memory HTTP stub; no live account effects. */
@RunWith(AndroidJUnit4::class)
class GitHubBoundTransportInstrumentedTest {
    private val head = "a".repeat(40)
    private val branch = "codex-self-test/" + "b".repeat(20)
    private val root = "/repos/test-owner/synthetic-repo"

    private fun bound(operation: String, client: GitHubWorkspaceClient): AgentTool = GitHubBoundRepositoryTool(
        GitHubOperationTool(
            operation,
            object : AgentTool {
                override val definition = AgentToolDefinition("github", "Synthetic authorized test boundary", buildJsonObject { put("type", "object") })
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    val accounting = GitHubDispatchAccounting(forceLiveReads = true)
                    val result = withContext(accounting) {
                        try {
                            AgentToolResult(callId, ToolResultContent.Json(client.execute(arguments.getValue("action").jsonPrimitive.content, arguments)), false)
                        } catch (pending: GitHubExecutionBlocked) {
                            AgentToolResult(callId, ToolResultContent.Json(pending.checkpoint), true, errorCode = "RECONCILIATION_REQUIRED")
                        } catch (failure: GitHubRequestRejected) {
                            AgentToolResult(callId, ToolResultContent.Text("Synthetic HTTP rejection"), true, errorCode = if (failure.status == 404) "GITHUB_NOT_FOUND_OR_INACCESSIBLE" else "GITHUB_ENDPOINT_PERMISSION_REQUIRED")
                        }
                    }
                    return accounting.finish(result, operation == "github_commit")
                }
            },
            "instrumented-fixture"
        ),
        GitHubRepositoryContext("test-owner", "synthetic-repo", "main")
    )

    private fun evidence(name: String, started: Long, requests: Int, assertions: JsonObject) {
        assertTrue("Runtime revision must be measured from the installed build", BuildConfig.APP_COMMIT.matches(Regex("[a-fA-F0-9]{40}")))
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "github-$name-evidence.json").writeText(
            buildJsonObject {
                put("schema", "github.android-stub-acceptance.v1")
                put("test", name)
                put("result", "PASS")
                put("runtime", GitHubRuntimeIdentity.current())
                put("repository_commit_sha", head)
                put("transport", "KTOR_MOCK_ENGINE")
                put("evidence_class", "CURRENT_DEVICE_STUB_EXECUTION")
                put("live_account_execution", false)
                put("live_side_effects", 0)
                put("stub_http_requests", requests)
                put("elapsed_realtime_ms", SystemClock.elapsedRealtime() - started)
                put("assertions", assertions)
            }.toString()
        )
    }

    @Test fun boundReadUsesExactRepositoryAndRetainsRuntimeRevision() = runBlocking {
        val started = SystemClock.elapsedRealtime()
        val requests = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                requests += request.url.encodedPath
                val body = when (request.url.encodedPath) {
                    "$root/branches/main" -> """{"name":"main","protected":false,"commit":{"sha":"$head"}}"""
                    "$root/branches/main/protection" -> "{}"
                    "$root/rules/branches/main" -> "[]"
                    else -> error("Unexpected synthetic branch endpoint")
                }
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        ).use { http ->
            val tool = bound("github_context", GitHubWorkspaceClient("synthetic-token", http, stateStore = GitHubRepositoryStateStore()))
            val result = tool.execute(
                "read",
                buildJsonObject {
                    put("action", "branch")
                    put("branch", "main")
                    put("verify_live", true)
                }
            )
            assertFalse(result.isError)
            assertTrue(result.content.toString().contains(head))
            assertEquals(JsonPrimitive("test-owner"), result.executionReceipt?.get("corrected_arguments")?.jsonObject?.get("owner"))
            assertEquals(JsonPrimitive(3), result.executionReceipt?.get("read_requests"))
            val denied = tool.execute(
                "foreign",
                buildJsonObject {
                    put("action", "branch")
                    put("branch", "main")
                    put("owner", "another-owner")
                    put("repo", "synthetic-repo")
                }
            )
            assertTrue(denied.isError)
            assertFalse(denied.dispatched)
            assertEquals(3, requests.size)
            evidence(
                "bound-read",
                started,
                requests.size,
                buildJsonObject {
                    put("repository_bound", true)
                    put("foreign_target_dispatched", false)
                }
            )
        }
    }

    @Test fun ownedDeletionReconcilesExactRefAbsenceWithoutRepeatingMutation() = runBlocking {
        val started = SystemClock.elapsedRealtime()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "github-stub-${UUID.randomUUID()}")
        val store = GitHubRepositoryStateStore(directory)
        var deleted = false
        var interruptAbsence = true
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val encoded = GitHubWorkspaceClient.segment(branch)
        HttpClient(
            MockEngine { request ->
                requests += request.method to request.url.encodedPath
                val path = request.url.encodedPath
                val value = when {
                    request.method == HttpMethod.Delete -> {
                        assertEquals("$root/git/refs/heads/$encoded", path)
                        assertFalse("A completed deletion must never be sent twice", deleted)
                        deleted = true
                        return@MockEngine respond("", HttpStatusCode.NoContent)
                    }
                    path == "$root/git/ref/heads/$encoded" && deleted && interruptAbsence -> {
                        interruptAbsence = false
                        return@MockEngine respond("""{"message":"Synthetic access interruption"}""", HttpStatusCode.Forbidden)
                    }
                    path in setOf("$root/git/ref/heads/$encoded", "$root/branches/$encoded") && deleted -> return@MockEngine respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
                    path == root -> """{"default_branch":"main"}"""
                    path == "$root/branches/$encoded" -> """{"protected":false}"""
                    path.startsWith("$root/rules/") || path == "$root/pulls" -> "[]"
                    path == "$root/actions/runs" -> """{"total_count":0}"""
                    path == "$root/git/commits/$head" -> """{"sha":"$head"}"""
                    path == "$root/git/ref/heads/$encoded" -> """{"ref":"refs/heads/$branch","object":{"sha":"$head"}}"""
                    else -> error("Unexpected synthetic endpoint $path")
                }
                respond(value, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        ).use { http ->
            try {
                val ownership = GitHubOwnedFixtures(store, "account:connection:conversation:task")
                ownership.register(
                    root,
                    buildJsonObject {
                        put("fixture_plan", GitHubOwnedFixtures.PLAN)
                        put("branch", branch)
                        put("base", "main")
                        put("expected_head_sha", head)
                        put("select_created_branch", false)
                    },
                    buildJsonObject {
                        put("branch", branch)
                        put("head_sha", head)
                        put("created", true)
                        put("receipt_verified", true)
                        put("remote_state_verified", true)
                    }
                )
                fun client() = GitHubWorkspaceClient("synthetic-token", http, connectionUid = "connection", conversationId = "conversation", taskId = "task", stateStore = GitHubRepositoryStateStore(directory), durableIdentity = "account")
                val args = buildJsonObject {
                    put("action", "delete_fixture_branch")
                    put("branch", branch)
                    put("origin_head_sha", head)
                    put("expected_head_sha", head)
                }
                val recovery = buildJsonObject {
                    put("action", "recovery")
                    put("fixture_plan", GitHubOwnedFixtures.PLAN)
                    put("branch", branch)
                    put("base_sha", head)
                    put("commit_sha", head)
                }
                assertTrue("Recovery is an ordinary authorized read, never a mutation replay", GitHubAuthorizationBoundary.isReplayableRead("github_context", recovery))
                val beforeDeletion = bound("github_context", client()).execute("before-deletion", recovery)
                assertFalse("General recovery may return the retained local fixture status", beforeDeletion.isError)
                val pending = (beforeDeletion.content as ToolResultContent.Json).value.jsonObject.getValue("data").jsonObject
                assertEquals(JsonPrimitive("PENDING"), pending["cleanup_status"])
                assertEquals(JsonPrimitive(branch), pending["branch"])
                assertEquals(JsonPrimitive(head), pending["head_sha"])
                assertFalse("Retained ownership cannot claim deletion", pending["deleted"] == JsonPrimitive(true))
                assertFalse("Creation alone cannot prove remote absence", pending["absence_verified"] == JsonPrimitive(true))
                assertEquals(JsonPrimitive(0), beforeDeletion.executionReceipt?.get("read_requests"))
                assertEquals(JsonPrimitive(0), beforeDeletion.executionReceipt?.get("mutation_requests"))
                assertEquals(JsonPrimitive(0), beforeDeletion.executionReceipt?.get("expected_absence_responses"))
                assertEquals(JsonPrimitive(false), beforeDeletion.executionReceipt?.get("live_read_performed"))
                val rejectedReconciliation = runCatching {
                    ownership.reconcileDeletion(root, recovery, read = { error("No content request is authorized before deletion intent") }, expectedAbsence = { error("No absence request is authorized before deletion intent") })
                }.exceptionOrNull()
                assertTrue("Creation alone cannot authorize the deletion reconciliation entry point", rejectedReconciliation is IllegalArgumentException)
                assertTrue(rejectedReconciliation?.message.orEmpty().startsWith("FIXTURE_RECONCILE_REQUIRED:"))
                assertEquals(0, requests.size)
                val first = bound("github_commit", client()).execute("delete", args)
                assertTrue("Deletion accepted but absence verification interrupted", first.isError)
                assertEquals("RECONCILIATION_REQUIRED", first.errorCode)
                assertEquals(JsonPrimitive(true), first.executionReceipt?.get("requires_reconciliation"))
                assertEquals(JsonPrimitive(0), first.executionReceipt?.get("expected_absence_responses"))
                assertEquals(JsonPrimitive(1), first.executionReceipt?.get("read_http_failures"))
                assertEquals(JsonPrimitive(1), first.executionReceipt?.get("accepted_mutations"))
                val before = requests.size
                val restored = bound("github_context", client()).execute(
                    "reconcile",
                    JsonObject(recovery + ("verify_live" to JsonPrimitive(true)))
                )
                assertFalse("Unexpected synthetic reconciliation rejection: ${restored.content}", restored.isError)
                assertEquals(before + 2, requests.size)
                assertEquals(HttpMethod.Get to "$root/git/commits/$head", requests[before])
                assertEquals(HttpMethod.Get to "$root/git/ref/heads/$encoded", requests.last())
                assertEquals(1, requests.count { it.first == HttpMethod.Delete })
                assertEquals(JsonPrimitive(0), restored.executionReceipt?.get("mutation_requests"))
                assertEquals(JsonPrimitive(2), restored.executionReceipt?.get("read_requests"))
                assertEquals(JsonPrimitive(404), restored.executionReceipt?.get("http_status"))
                assertEquals(JsonPrimitive(1), restored.executionReceipt?.get("expected_absence_responses"))
                assertEquals(JsonPrimitive(0), restored.executionReceipt?.get("read_http_failures"))
                val ordinary = bound("github_context", client()).execute(
                    "missing",
                    buildJsonObject {
                        put("action", "branch")
                        put("branch", branch)
                        put("verify_live", true)
                    }
                )
                assertTrue("Unowned missing-ref reads remain failures", ordinary.isError)
                assertEquals(JsonPrimitive(1), ordinary.executionReceipt?.get("read_http_failures"))
                evidence(
                    "owned-deletion",
                    started,
                    requests.size,
                    buildJsonObject {
                        put("delete_requests", 1)
                        put("exact_ref_absence_observed", true)
                        put("restored_reconciliation_read_only", true)
                        put("generic_missing_ref_rejected", true)
                    }
                )
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
