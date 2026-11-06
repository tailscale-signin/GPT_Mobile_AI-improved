from pathlib import Path
root = Path.cwd()
t = 'app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/'
def append(path, body):
    p = root / path
    s = p.read_text()
    head, tail = s.rsplit('\n}', 1)
    p.write_text(head + '\n' + body + '\n}' + tail)
p = root / (t + 'github/GitHubRefAvailabilityTest.kt')
if p.exists():
    raise RuntimeError('New regression file already exists')
p.write_text('''package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRefAvailabilityTest {
    @Test fun unavailableRefBlocksDependentReadsWithoutChangingRepositoryOrRefKind() {
        val gate = GitHubRefAvailability()
        gate.failed("/repos/Owner/Repo/commits/missing%2Fbranch", 422, "No commit found for SHA")
        val failure = runCatching { gate.requireAvailable("/repos/owner/repo/commits/missing%2Fbranch/check-runs") }.exceptionOrNull() as GitHubReadValidation
        assertEquals("GITHUB_REF_UNAVAILABLE", failure.code)
        assertEquals(JsonPrimitive(false), failure.details["dispatched"])
        assertEquals(JsonPrimitive(false), failure.details["automatic_branch_substitution"])
        assertEquals(JsonPrimitive("missing/branch"), failure.details["ref"])
        gate.requireAvailable("/repos/owner/other/commits/missing%2Fbranch")
        gate.requireAvailable("/repos/owner/repo/branches/missing%2Fbranch")
        gate.requireAvailable("/repos/owner/repo/commits/main")
    }

    @Test fun permissionsServerErrorsAndMissingProtectionDoNotPoisonRefResolution() {
        val gate = GitHubRefAvailability()
        for (status in listOf(401, 403, 429, 500, 503)) gate.failed("/repos/o/r/commits/main", status, "unavailable")
        gate.failed("/repos/o/r/commits/main", 422, "An unrelated field is invalid")
        gate.failed("/repos/o/r/branches/main/protection", 404, "Not found")
        gate.requireAvailable("/repos/o/r/commits/main")
        gate.requireAvailable("/repos/o/r/branches/main")
    }

    @Test fun explicitRevalidationExpiryAndCredentialReplacementClearOnlyTheirScope() {
        var time = 0L
        val gate = GitHubRefAvailability(now = { time }, cooldownMillis = 100)
        gate.failed("/repos/o/r/branches/feature", 404, "Not found")
        gate.failed("/repos/o/other/branches/feature", 404, "Not found")
        gate.revalidate("/repos/o/r", "feature")
        gate.requireAvailable("/repos/o/r/branches/feature")
        assertTrue(runCatching { gate.requireAvailable("/repos/o/other/branches/feature") }.isFailure)
        time = 100
        gate.requireAvailable("/repos/o/other/branches/feature")
        gate.failed("/repos/o/r/branches/feature", 404, "Not found")
        gate.clear()
        gate.requireAvailable("/repos/o/r/branches/feature")
    }

    @Test fun negativeObservationsAreBoundedAndBranchAndTagNamespacesStayDistinct() {
        val gate = GitHubRefAvailability()
        repeat(129) { gate.failed("/repos/o/r/branches/ref$it", 404, "Not found") }
        gate.requireAvailable("/repos/o/r/branches/ref0")
        assertTrue(runCatching { gate.requireAvailable("/repos/o/r/branches/ref128") }.isFailure)
        gate.requireAvailable("/repos/o/r/commits/ref128")
        gate.requireAvailable("/repos/o/r/git/ref/tags/ref128")
    }

    @Test fun staleCommitStopsCascadingHttpAndExplicitRefreshCanRecover() = runTest {
        var requests = 0
        var restored = false
        val sha = "a".repeat(40)
        val tree = "b".repeat(40)
        val http = HttpClient(MockEngine { request ->
            requests++
            assertEquals("/repos/o/r/commits/gone", request.url.encodedPath)
            if (restored) respond("""{"sha":"$sha","commit":{"tree":{"sha":"$tree"}},"files":[]}""")
            else respond("""{"message":"No commit found for SHA: gone"}""", HttpStatusCode.UnprocessableEntity)
        })
        try {
            val client = GitHubWorkspaceClient("token", http)
            val args = buildJsonObject { put("owner", "o"); put("repo", "r"); put("ref", "gone") }
            assertTrue(runCatching { client.execute("get_commit_detail", args) }.exceptionOrNull() is GitHubRequestRejected)
            assertTrue(runCatching { client.execute("get_commit_status_summary", args) }.exceptionOrNull() is GitHubReadValidation)
            assertEquals(1, requests)
            assertNull(client.taskContext())
            restored = true
            client.execute("resolve_context", JsonObject(args + ("refresh" to JsonPrimitive(true))))
            assertEquals(2, requests)
            assertEquals("gone", client.taskContext()?.ref)
        } finally {
            http.close()
        }
    }

    @Test fun missingBranchDoesNotFanOutIntoPullRequestsAndWorkflowQueries() = runTest {
        val paths = mutableListOf<String>()
        val client = GitHubInspection { path ->
            paths += path
            throw GitHubRequestRejected(404, "Branch not found or inaccessible")
        }
        assertTrue(runCatching { client.execute("branch_dependencies", "/repos/o/r", Json.parseToJsonElement("""{"branch":"gone"}""").jsonObject, 1) }.isFailure)
        assertEquals(listOf("/repos/o/r/branches/gone"), paths)
        assertFalse(paths.any { "/pulls" in it || "/actions" in it })
    }
}
''')
append(t+'agent/tool/RepositoryGraphFallbackTest.kt', '''    @Test fun typedReadinessOutageAllowsOneAuthorizedReadButNeverReplaysIndexing() = runTest {
        var nativeReads = 0
        val tool = RepositoryGraphTool("repository_graph__active", nativeStatus = { call ->
            nativeReads++
            AgentToolResult(call, ToolResultContent.Text("Selected repository status"), false)
        }) { throw dev.chungjungsoo.gptmobile.data.github.GatewayGraphUnavailableException("Graph is unavailable") }
        val status = tool.execute("status", JsonObject(mapOf("action" to JsonPrimitive("status"))))
        assertTrue(status.isError)
        assertEquals(1, nativeReads)
        assertTrue(((status.content as ToolResultContent.Json).value as JsonObject).containsKey("native_fallback"))
        tool.execute("index", JsonObject(mapOf("action" to JsonPrimitive("index"))))
        assertEquals(1, nativeReads)
    }
''')
append(t+'github/GitHubRequestCircuitTest.kt', '''    @Test fun observedValidationResponseBreaksAConsecutiveTransportFailureStreak() {
        val circuit = GitHubRequestCircuit(threshold = 2)
        circuit.failed(circuit.admit(), IOException("network"))
        circuit.failed(circuit.admit(), GitHubRequestRejected(422, "Invalid ref"))
        circuit.failed(circuit.admit(), IOException("network"))
        assertFalse(circuit.admit().probe)
    }

    @Test fun localValidationAndTlsCannotHealAnExpiredOpenCircuit() {
        var time = 0L
        val circuit = GitHubRequestCircuit(1, 100, now = { time })
        circuit.failed(circuit.admit(), IOException("network"))
        time = 100
        circuit.failed(circuit.admit(), IllegalArgumentException("local schema"))
        val afterValidation = circuit.admit()
        assertTrue(afterValidation.probe)
        circuit.failed(afterValidation, SSLException("untrusted"))
        assertTrue(circuit.admit().probe)
    }
''')
append(t+'network/LocalServiceHealthTest.kt', '''    @Test fun closedBeforeResponseIsATransportFailureWithoutOverridingAuthenticationOrCancellation() {
        val interrupted = IllegalStateException("Connection closed before response arrived")
        assertEquals(ConnectionHealth.STREAM_INTERRUPTED, LocalServiceHealth.classify(interrupted))
        assertEquals(ConnectionHealth.STREAM_INTERRUPTED, LocalServiceHealth.classify(IllegalStateException("gateway", interrupted)))
        assertEquals(ConnectionHealth.AUTHENTICATION_FAILED, LocalServiceHealth.classify(IllegalStateException("HTTP 403 forbidden: connection closed before response arrived")))
        assertEquals(ConnectionHealth.UNKNOWN, LocalServiceHealth.classify(kotlinx.coroutines.CancellationException("Connection closed before response arrived")))
    }
''')
append(t+'github/GitHubBranchTransitionTest.kt', '''    @Test fun disposableFixtureCannotChangeTheTaskDestinationEvenWithAContextPayload() {
        val fixture = JsonObject(arguments + ("select_created_branch" to JsonPrimitive(false)))
        assertNull(GitHubBranchTransition.verified("owner/repo", "github_commit", fixture, result(), true))
    }
''')
append(t+'agent/tool/GitHubResultViewsStressTest.kt', '''    @Test fun longJsonPointersAreIncludedInFragmentByteAccounting() {
        val views = GitHubResultViews(pageBytes = 2048)
        val key = "k".repeat(320)
        val value = "v".repeat(380)
        val source = buildJsonObject { put(key, value) }
        val first = views.present("scope", "github_read", "github_read", AgentToolResult("first", ToolResultContent.Json(source), false))
        assertFalse(first.isError)
        assertEquals(source, data(first).getValue("data"))
        val recovery = data(first).getValue("recovery_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
        var page = data(views.read("scope", "github_read", "github_read", recovery, "recover"))
        val rebuilt = StringBuilder()
        var pages = 0
        while (true) {
            assertTrue(page.toString().encodeToByteArray().size <= 1536)
            for (fragment in page.getValue("fragments").jsonArray) {
                val row = fragment.jsonObject
                assertEquals("/$key", row.getValue("pointer").jsonPrimitive.content)
                assertEquals(rebuilt.length, row.getValue("offset").jsonPrimitive.int)
                rebuilt.append(row.getValue("value").jsonPrimitive.content)
            }
            if (page.getValue("local_page_complete").jsonPrimitive.boolean) break
            page = data(views.read("scope", "github_read", "github_read", cursor(page), "next"))
            assertTrue(++pages < 100)
        }
        assertEquals(value, rebuilt.toString())
    }

    @Test fun tagCommitContinuationUsesPinnedContextContractAndPreservesConnection() {
        val sha = "a".repeat(40)
        val source = Json.parseToJsonElement("""{"commit":{"sha":"$sha","continuation":{"action":"get_commit_detail","commit_sha":"$sha","ref":"$sha","page":2,"per_page":30,"include_patches":false}}}""").jsonObject
        val request = Json.parseToJsonElement("""{"action":"tag","tag_name":"v1","owner":"o","repo":"r"}""").jsonObject
        val first = data(GitHubResultViews().present("scope", "github_release", "github_release__active", AgentToolResult("tag", ToolResultContent.Json(source), false), request))
        val call = first.getValue("remote_continuations").jsonArray.single().jsonObject.getValue("call").jsonObject
        assertEquals(JsonPrimitive("github_context__active"), call["tool"])
        val args = call.getValue("arguments").jsonObject
        assertEquals(JsonPrimitive("commit"), args["action"])
        assertEquals(JsonPrimitive(sha), args["commit_sha"])
        assertEquals(JsonPrimitive(2), args["page"])
        assertFalse(args.containsKey("tag_name"))
        assertTrue(ToolArgumentValidator.errors(args, GitHubOperationTool.definition("github_context").inputSchema).isEmpty())
    }

    @Test fun presentationCapacityRetainsBoundedOriginalAndSeparateUpstreamOutcome() {
        val source = ToolResultContent.Json(buildJsonObject { put("sha", "a".repeat(40)); put("receipt", "confirmed") })
        val result = GitHubResultViews(capacityBytes = 100).present("scope", "github_commit", "github_commit", AgentToolResult("write", source, false))
        assertTrue(result.isError)
        assertEquals("RETAINED_RESULT_CAPACITY", result.errorCode)
        assertEquals(source, result.retainedContent)
        assertEquals(JsonPrimitive(true), result.executionReceipt?.get("upstream_completed"))
        assertEquals(JsonPrimitive("COMPLETED"), result.executionReceipt?.get("upstream_execution_state"))
        assertEquals(JsonPrimitive("CAPACITY_REJECTED"), result.executionReceipt?.get("presentation_state"))
    }
''')
append(t+'github/GitHubToolCompletenessTest.kt', '''    @Test fun tagInspectionBoundsCommitPatchesAndRetainsAnExactFileContinuation() = runTest {
        val sha = "a".repeat(40)
        val commit = buildJsonObject {
            put("sha", sha)
            put("files", kotlinx.serialization.json.JsonArray((1..30).map { index ->
                buildJsonObject { put("filename", "file$index.kt"); put("status", "modified"); put("patch", "x".repeat(100_000)) }
            }))
        }
        val client = GitHubInspection { path ->
            when (path) {
                "/repos/o/r/git/ref/tags/v1" -> Json.parseToJsonElement("""{"ref":"refs/tags/v1","object":{"type":"commit","sha":"$sha"}}""")
                "/repos/o/r/commits/$sha?per_page=30&page=1" -> commit
                "/repos/o/r/releases/tags/v1" -> Json.parseToJsonElement("""{"tag_name":"v1"}""")
                else -> error("Unexpected request: $path")
            }
        }
        val result = client.execute("tag_context", "/repos/o/r", args("tag_name" to "v1"), 1)
        assertTrue(result.toString().encodeToByteArray().size < 10_000)
        val detail = result.getValue("commit").jsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), detail["patches_included"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(sha), detail.getValue("continuation").jsonObject["commit_sha"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(2), detail.getValue("continuation").jsonObject["page"])
        assertEquals(30, detail.getValue("files").jsonArray.size)
    }
''')
append(t+'agent/tool/GitHubToolTest.kt', '''    @Test fun disposableBranchReadbackNeverSelectsTheFixtureAsWorkspaceContext() = runTest {
        val sha = "a".repeat(40)
        val tree = "b".repeat(40)
        var created = false
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/repos/o/r/git/ref/heads/main" -> respond("""{"ref":"refs/heads/main","object":{"sha":"$sha"}}""")
                "/repos/o/r/git/ref/heads/fixture" -> if (created) respond("""{"ref":"refs/heads/fixture","object":{"sha":"$sha"}}""") else respond("{}", HttpStatusCode.NotFound)
                "/repos/o/r/git/refs" -> { created = true; respond("""{"ref":"refs/heads/fixture","object":{"sha":"$sha"}}""", HttpStatusCode.Created) }
                "/repos/o/r/commits/main" -> respond("""{"sha":"$sha","commit":{"tree":{"sha":"$tree"}},"files":[]}""")
                else -> error("A fixture must not become the selected context: ${request.url}")
            }
        })
        try {
            val tool = GitHubTool("token", http, repositoryContext = GitHubRepositoryContext("o", "r", "main"))
            val result = tool.execute("fixture", buildJsonObject {
                put("action", "create_branch"); put("owner", "o"); put("repo", "r"); put("branch", "fixture")
                put("base", "main"); put("expected_head_sha", sha); put("select_created_branch", false)
            })
            assertFalse(result.isError)
            val body = (result.content as ToolResultContent.Json).value.jsonObject
            assertEquals("true", body["selection_preserved"].toString())
            assertEquals("true", body["remote_state_verified"].toString())
            assertEquals("false", body["context_selected"].toString())
            assertFalse(body.containsKey("context"))
            assertFalse(tool.execute("read", buildJsonObject { put("action", "get_commit_detail") }).isError)
            assertFalse(paths.any { it == "/repos/o/r/commits/fixture" })
            assertEquals(1, paths.count { it == "/repos/o/r/commits/main" })
        } finally {
            http.close()
        }
    }
''')
