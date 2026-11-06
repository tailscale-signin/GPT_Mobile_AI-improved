package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubValidationRecoveryTest {
    private val head = "a".repeat(40)
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun prStateUpdateActuallySendsTheRequestedStateAndHonorsTheExpectedBase() = runTest {
        val sent = mutableListOf<JsonObject>()
        HttpClient(
            MockEngine { request ->
                if (request.method == HttpMethod.Patch) {
                    sent += json((request.body as TextContent).text)
                    respond("""{"number":7,"state":"closed","head":{"sha":"$head"}}""")
                } else {
                    respond("""{"number":7,"state":"open","head":{"sha":"$head"},"base":{"sha":"${"b".repeat(40)}"}}""")
                }
            }
        ).use { http ->
            val client = GitHubWorkspaceClient("token", http)
            val args = json("""{"owner":"o","repo":"r","pull_number":7,"expected_head_sha":"$head","expected_base_sha":"${"b".repeat(40)}","state":"closed"}""")
            val result = client.execute("update_pull_request", args).jsonObject
            assertEquals(JsonPrimitive("closed"), result["state"])
            assertEquals(listOf(json("""{"state":"closed"}""")), sent)
            val changedBase = JsonObject(args + ("expected_base_sha" to JsonPrimitive("c".repeat(40))))
            val failure = runCatching { client.execute("update_pull_request", changedBase) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("base changed"))
            assertEquals(1, sent.size)
        }
    }

    @Test fun deterministicReadFailuresKeepTheirCategoryAndActionableSafeDetailsWithoutRetry() = runTest {
        for ((status, code) in listOf(401 to "AUTH_REQUIRED", 404 to "GITHUB_NOT_FOUND_OR_INACCESSIBLE", 422 to "GITHUB_VALIDATION_FAILED")) {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond("""{"message":"Validation Failed","errors":[{"resource":"Issue","field":"number","code":"invalid","value":"private-rejected-value"}]}""", HttpStatusCode.fromValue(status))
                }
            ).use { http ->
                val result = GitHubTool("token", http).execute("failed", json("""{"action":"get_issue","owner":"o","repo":"r","issue_number":7}"""))
                assertTrue(result.isError)
                assertEquals(code, result.errorCode)
                val text = (result.content as ToolResultContent.Text).text
                assertFalse(text.contains("private-rejected-value"))
                if (status == 422) {
                    assertTrue(text.contains("field=number"))
                    assertTrue(text.contains("not retried unchanged"))
                }
                if (status == 404) assertTrue(text.contains("does not exist") && text.contains("cannot see"))
                assertEquals(1, requests)
            }
        }
    }

    @Test fun lostBranchAcknowledgementCannotReplayWhenTheRefIsAbsentOrHasADifferentHead() = runTest {
        val store = GitHubRepositoryStateStore()
        var writes = 0
        var observed: String? = null
        HttpClient(
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Post -> {
                        writes++
                        throw IOException("Acknowledgement lost")
                    }
                    request.url.encodedPath.endsWith("/main") -> respond("""{"ref":"refs/heads/main","object":{"sha":"$head"}}""")
                    observed != null -> respond("""{"ref":"refs/heads/topic","object":{"sha":"$observed"}}""")
                    else -> respond("{}", HttpStatusCode.NotFound)
                }
            }
        ).use { http ->
            val args = json("""{"action":"create_branch","owner":"o","repo":"r","base":"main","branch":"topic","expected_head_sha":"$head"}""")
            fun tool() = GitHubTool("token", http, stateStore = store)
            assertEquals("RECONCILIATION_REQUIRED", tool().execute("first", args).errorCode)
            assertEquals("RECONCILIATION_REQUIRED", tool().execute("new-call-id", args).errorCode)
            observed = "b".repeat(40)
            assertEquals("RECONCILIATION_REQUIRED", tool().execute("wrong-head", args).errorCode)
            observed = head
            val recovered = tool().execute("exact-readback", args)
            assertFalse(recovered.isError)
            assertFalse(recovered.sharedResult)
            val proof = ToolResultEnvelope.element(recovered.content).jsonObject
            assertEquals(JsonPrimitive("REMOTE_REF_AND_EXPECTED_HEAD"), proof["verification"])
            assertEquals(JsonPrimitive(true), proof["remote_state_verified"])
            assertEquals("LIVE", GitHubEvidenceOrigin.classify(recovered.dispatched, recovered.sharedResult, recovered.executionReceipt))
            assertEquals(1, writes)
        }
    }

    @Test fun uncertainNativeCommentIsNotSentAgainWithAnotherCallId() = runTest {
        val store = GitHubRepositoryStateStore()
        var writes = 0
        var observedHead = head
        HttpClient(
            MockEngine { request ->
                if (request.method == HttpMethod.Post) {
                    writes++
                    respond("{}", HttpStatusCode.RequestTimeout)
                } else {
                    respond("""{"number":7,"state":"open","head":{"sha":"$observedHead"}}""")
                }
            }
        ).use { http ->
            val args = json("""{"action":"create_issue_comment","owner":"o","repo":"r","pull_number":7,"expected_head_sha":"$head","body":"one comment"}""")
            for ((index, guard) in listOf(head, null, "b".repeat(40), head).withIndex()) {
                val id = "call-$index"
                observedHead = guard ?: head
                val contextual = JsonObject(
                    (args - "expected_head_sha") + mapOf("ref" to JsonPrimitive("context-$index"), "response_detail" to JsonPrimitive(id)) +
                        if (guard != null) mapOf("expected_head_sha" to JsonPrimitive(guard)) else emptyMap()
                )
                assertEquals("RECONCILIATION_REQUIRED", GitHubTool("token", http, stateStore = store).execute(id, contextual).errorCode)
            }
            assertEquals(1, writes)
        }
    }

    @Test fun acceptedBranchCreationRefreshesItsMissingRefWithoutClearingOtherRefFailures() = runTest {
        var created = false
        val requests = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                requests += "${request.method.value} ${request.url.encodedPath}"
                when (request.url.encodedPath) {
                    "/repos/o/r/git/ref/heads/main" -> respond("""{"ref":"refs/heads/main","object":{"sha":"$head"}}""")
                    "/repos/o/r/git/ref/heads/fixture" -> if (created) respond("""{"ref":"refs/heads/fixture","object":{"sha":"$head"}}""") else respond("{}", HttpStatusCode.NotFound)
                    "/repos/o/r/git/ref/heads/other" -> respond("{}", HttpStatusCode.NotFound)
                    "/repos/o/r/git/refs" -> {
                        created = true
                        respond("""{"ref":"refs/heads/fixture","object":{"sha":"$head"}}""", HttpStatusCode.Created)
                    }
                    else -> error("Unexpected HTTP request: ${request.url}")
                }
            }
        ).use { http ->
            val tool = GitHubTool("token", http)
            val read = json("""{"action":"get_branch_head","owner":"o","repo":"r","branch":"fixture"}""")
            val other = JsonObject(read + ("branch" to JsonPrimitive("other")))
            assertEquals("GITHUB_NOT_FOUND_OR_INACCESSIBLE", tool.execute("missing-fixture", read).errorCode)
            assertEquals("GITHUB_NOT_FOUND_OR_INACCESSIBLE", tool.execute("missing-other", other).errorCode)
            val result = tool.execute("create", json("""{"action":"create_branch","owner":"o","repo":"r","base":"main","branch":"fixture","expected_head_sha":"$head","select_created_branch":false}"""))
            assertFalse(result.isError)
            val receipt = ToolResultEnvelope.element(result.content).jsonObject
            assertEquals(JsonPrimitive(true), receipt["remote_state_verified"])
            assertEquals(JsonPrimitive(true), receipt["selection_preserved"])
            assertEquals("GITHUB_REF_UNAVAILABLE", tool.execute("still-missing-other", other).errorCode)
            assertEquals(1, requests.count { it == "POST /repos/o/r/git/refs" })
            assertEquals(3, requests.count { it == "GET /repos/o/r/git/ref/heads/fixture" })
            assertEquals(1, requests.count { it == "GET /repos/o/r/git/ref/heads/other" })
        }
    }
}
