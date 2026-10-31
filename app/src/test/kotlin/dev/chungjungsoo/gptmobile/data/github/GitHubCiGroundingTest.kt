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
