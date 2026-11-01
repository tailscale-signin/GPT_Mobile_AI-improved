package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCiGroundingTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun data(result: AgentToolResult): JsonObject = when (val value = result.content) {
        is ToolResultContent.Json -> value.value.jsonObject
        is ToolResultContent.Text -> json(value.text)
        else -> error("No source")
    }

    @Test fun definiteHttpRejectionAndEarlierAcceptedWritesKeepTheirEvidence() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/git/ref/heads/topic")) {
                    return@MockEngine respond("{}", HttpStatusCode.NotFound)
                } else if (request.method.value == "POST") {
                    respond("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden)
                } else {
                    respond("""{"object":{"sha":"${"a".repeat(40)}"},"permissions":{"push":true}}""")
                }
            }
        )
        try {
            val result = GitHubTool("token", http).execute("rejected", json("""{"action":"create_branch","owner":"o","repo":"r","branch":"topic"}"""))
            assertTrue(result.dispatched)
            assertEquals("REJECTED", result.executionReceipt!!.getValue("phase").jsonPrimitive.content)
            assertEquals("403", result.executionReceipt!!.getValue("http_status").jsonPrimitive.content)
            assertEquals("false", result.executionReceipt!!.getValue("requires_reconciliation").jsonPrimitive.content)
        } finally {
            http.close()
        }
        val accounting = GitHubDispatchAccounting()
        kotlinx.coroutines.withContext(accounting) {
            GitHubDispatchAccounting.started(true)
            GitHubDispatchAccounting.responded(true, 201)
        }
        val partial = accounting.finish(AgentToolResult("partial", ToolResultContent.Text("Later preflight failed"), true, errorCode = "INVALID_ARGUMENTS"), true)
        assertTrue(partial.dispatched)
        assertEquals("PARTIAL_ACCEPTED", partial.executionReceipt!!.getValue("phase").jsonPrimitive.content)
        assertEquals("true", partial.executionReceipt!!.getValue("requires_reconciliation").jsonPrimitive.content)
    }

    @Test fun unsentValidationAndLostMutationResponseHaveDifferentRecoveryReceipts() = runTest {
        for (lost in listOf(false, true)) {
            var writes = 0
            val http = HttpClient(
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/git/ref/heads/topic")) {
                        return@MockEngine respond("{}", HttpStatusCode.NotFound)
                    } else if (request.method.value == "POST") {
                        writes++
                        throw java.io.IOException("Connection closed before the response")
                    }
                    respond("""{"object":{"sha":"${"a".repeat(40)}"}}""")
                }
            )
            try {
                val result = GitHubTool("token", http).execute("call", json("""{"action":"create_branch","owner":"o","repo":"r","branch":"topic","expected_head_sha":"${(if (lost) "a" else "b").repeat(40)}"}"""))
                assertTrue(result.isError)
                assertEquals(lost, result.dispatched)
                assertEquals(if (lost) 1 else 0, writes)
                assertEquals(if (lost) "OUTCOME_UNKNOWN" else "PREFLIGHT_REJECTED", result.executionReceipt!!.getValue("phase").jsonPrimitive.content)
                assertEquals(lost.toString(), result.executionReceipt!!.getValue("requires_reconciliation").jsonPrimitive.content)
            } finally {
                http.close()
            }
        }
    }

    @Test fun workflowTriggerAndInputsAreCheckedBeforePostAndUnchangedFailureSurvivesRestart() = runTest {
        val state = GitHubRepositoryStateStore()
        var reads = 0
        var writes = 0
        val definition = "on:\n  push:\n  workflow_dispatch:\n    inputs:\n      target:\n        type: string\n"
        val encoded = java.util.Base64.getEncoder().encodeToString(definition.toByteArray())
        val http = HttpClient(
            MockEngine { request ->
                if (request.method.value == "POST") {
                    writes++
                    respond("", HttpStatusCode.NoContent)
                } else {
                    reads++
                    when {
                        "/commits/" in request.url.encodedPath -> respond("""{"sha":"${"a".repeat(40)}"}""")
                        "/actions/workflows/" in request.url.encodedPath -> respond("""{"id":10,"state":"active","path":".github/workflows/build.yml"}""")
                        "/contents/" in request.url.encodedPath -> respond("""{"encoding":"base64","size":${definition.length},"content":"$encoded"}""")
                        else -> respond("""{"default_branch":"main"}""")
                    }
                }
            }
        )
        val bad = json("""{"action":"dispatch_workflow","owner":"o","repo":"r","workflow_id":"build.yml","ref":"topic","expected_head_sha":"${"a".repeat(40)}","inputs":{"ref":"topic"}}""")
        try {
            val first = GitHubTool("token", http, stateStore = state).execute("first", bad)
            assertEquals("WORKFLOW_INPUTS_INVALID", first.errorCode)
            assertFalse(first.dispatched)
            assertEquals(0, writes)
            val observed = reads
            val second = GitHubTool("token", http, stateStore = state).execute("different-call-id", bad)
            assertEquals("GITHUB_OPERATION_BLOCKED", second.errorCode)
            assertEquals(observed, reads)
            val alias = JsonObject(bad + ("workflow_id" to kotlinx.serialization.json.JsonPrimitive(".github/workflows/build.yml")))
            assertEquals("GITHUB_OPERATION_BLOCKED", GitHubTool("token", http, stateStore = state).execute("alias", alias).errorCode)
            assertEquals(observed, reads)
            val fixed = JsonObject(bad + ("inputs" to json("""{"target":"topic"}""")))
            val accepted = GitHubTool("token", http, stateStore = state).execute("fixed", fixed)
            assertFalse(accepted.isError)
            assertEquals(1, writes)
            assertEquals("ACCEPTED", accepted.executionReceipt!!.getValue("phase").jsonPrimitive.content)
        } finally {
            http.close()
        }
    }

    @Test fun freshWorkflowInventoryCanResolveAResourceBlockWithoutBroadeningPermissions() = runTest {
        val store = GitHubRepositoryStateStore()
        var visible = false
        var reads = 0
        var writes = 0
        val definition = "on: [workflow_dispatch]"
        val encoded = java.util.Base64.getEncoder().encodeToString(definition.toByteArray())
        val http = HttpClient(
            MockEngine { request ->
                if (request.method.value == "POST") {
                    writes++
                    respond("", HttpStatusCode.NoContent)
                } else {
                    reads++
                    when {
                        request.url.encodedPath.endsWith("/actions/workflows") -> respond("""{"total_count":1,"workflows":[{"id":10,"state":"active","path":".github/workflows/build.yml"}]}""")
                        "/commits/" in request.url.encodedPath -> respond("""{"sha":"${"a".repeat(40)}"}""")
                        "/actions/workflows/" in request.url.encodedPath -> if (visible) respond("""{"id":10,"state":"active","path":".github/workflows/build.yml"}""") else respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
                        "/contents/" in request.url.encodedPath -> respond("""{"encoding":"base64","size":${definition.length},"content":"$encoded"}""")
                        else -> respond("""{"default_branch":"main","permissions":{"push":true}}""")
                    }
                }
            }
        )
        val input = json("""{"action":"dispatch_workflow","owner":"o","repo":"r","workflow_id":"build.yml","ref":"topic","expected_head_sha":"${"a".repeat(40)}"}""")
        try {
            val tool = GitHubTool("same-token", http, stateStore = store)
            assertEquals("GITHUB_NOT_FOUND_OR_HIDDEN", tool.execute("hidden", input).errorCode)
            val observed = reads
            assertEquals("GITHUB_OPERATION_BLOCKED", tool.execute("same", input).errorCode)
            assertEquals(observed, reads)
            visible = true
            assertFalse(tool.execute("inspect", json("""{"action":"list_workflows","owner":"o","repo":"r"}""")).isError)
            assertFalse(tool.execute("corrected-state", input).isError)
            assertEquals(1, writes)
        } finally {
            http.close()
        }
    }

    @Test fun invalidRunNeverRequestsJobsAndOffersRunDiscovery() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
            }
        )
        try {
            val result = GitHubTool("token", http).execute("read", json("""{"action":"list_workflow_jobs","owner":"o","repo":"r","run_id":1}"""))
            assertTrue(result.isError)
            assertEquals(listOf("/repos/o/r/actions/runs/1"), paths)
            assertEquals("runs", data(result).getValue("recovery_call").jsonObject.getValue("arguments").jsonObject.getValue("action").jsonPrimitive.content)
        } finally {
            http.close()
        }
    }

    @Test fun jobsFailureRetainsActualRunAndExplicitErrorInsteadOfReportingSuccess() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/jobs")) {
                    respond("""{"message":"Unavailable"}""", HttpStatusCode.NotFound)
                } else {
                    respond("""{"id":41,"head_sha":"${"a".repeat(40)}","status":"completed","conclusion":"failure"}""")
                }
            }
        )
        try {
            val result = GitHubTool("token", http).execute("read", json("""{"action":"list_workflow_jobs","owner":"o","repo":"r","run_id":41}"""))
            assertTrue(result.isError)
            assertEquals("failure", data(result).getValue("run").jsonObject.getValue("conclusion").jsonPrimitive.content)
            assertEquals("run", data(result).getValue("recovery_call").jsonObject.getValue("arguments").jsonObject.getValue("action").jsonPrimitive.content)
        } finally {
            http.close()
        }
    }

    @Test fun movedRefAndMismatchedRunStopBeforeDispatchOrJobRequest() = runTest {
        for (action in listOf("dispatch_workflow", "list_workflow_jobs", "get_workflow_run")) {
            val paths = mutableListOf<String>()
            val http = HttpClient(
                MockEngine { request ->
                    paths += "${request.method.value} ${request.url.encodedPath}"
                    respond("""{"id":41,"sha":"${"b".repeat(40)}","head_sha":"${"b".repeat(40)}"}""")
                }
            )
            try {
                val result = GitHubTool("token", http).execute("read", json("""{"action":"$action","owner":"o","repo":"r","run_id":41,"workflow_id":"build.yml","ref":"main","expected_head_sha":"${"a".repeat(40)}"}"""))
                assertTrue(result.isError)
                assertFalse(paths.any { it.startsWith("POST") || it.endsWith("/jobs") })
            } finally {
                http.close()
            }
        }
    }
}
