package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolObservationRetention
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPaginationBoundaryTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun terminalPagePreventsRemoteOverrunButAllowsRefreshAndDifferentScope() {
        var time = 0L
        val boundary = GitHubPaginationBoundary { time }
        val first = json("""{"action":"runs","owner":"o","repo":"r","workflow_id":"build.yml","page":1}""")
        val second = JsonObject(first + ("page" to JsonPrimitive(2)))
        boundary.observe("github_ci", first, json("""{"has_more":false}"""))
        assertEquals(1, boundary.completedPage("github_ci", second))
        assertEquals(null, boundary.completedPage("github_ci", JsonObject(second + ("workflow_id" to JsonPrimitive("release.yml")))))
        assertEquals(null, boundary.completedPage("github_ci", JsonObject(second + ("repo" to JsonPrimitive("other")))))
        assertEquals(null, boundary.completedPage("github_ci", JsonObject(second + ("view_cursor" to JsonPrimitive("local")))))
        time = 30_000_000_001L
        assertEquals(null, boundary.completedPage("github_ci", second))
        boundary.observe("github_ci", first, json("""{"has_more":false}"""))
        assertEquals(null, boundary.completedPage("github_ci", first))
        assertEquals(null, boundary.completedPage("github_ci", second))
    }

    @Test fun operationStopsAfterLastRemotePageAndRetainsMeasuredTrace() = runBlocking {
        var requests = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "test", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                requests++
                return AgentToolResult(callId, ToolResultContent.Json(json("""{"has_more":false,"workflow_runs":[]}""")), false)
            }
        }
        val tool = GitHubOperationTool("github_ci", backend, "scope")
        val first = tool.execute("one", json("""{"action":"runs","page":1}"""))
        assertTrue(first.executionReceipt!!.containsKey("tool_trace"))
        val next = tool.execute("two", json("""{"action":"runs","page":2}"""))
        assertFalse(next.dispatched)
        assertFalse(next.isError)
        assertEquals(1, requests)
        assertEquals(JsonPrimitive(true), (next.content as ToolResultContent.Json).value.jsonObject["pagination_complete"])
        tool.execute("refresh", json("""{"action":"runs","page":1}"""))
        assertEquals(2, requests)
        val invalid = tool.execute("ambiguous", json("""{"action":"status","workflow_id":"build.yml"}"""))
        assertEquals("INVALID_ARGUMENTS", invalid.errorCode)
        assertFalse(invalid.dispatched)
        assertEquals(2, requests)
    }

    @Test fun retainedJsonTextRemainsStructuredAndBounded() {
        val original = ToolResultContent.Text("""{"items":[${List(3000) { "\"${"😀".repeat(20)}\"" }.joinToString(",")}]}""")
        val bounded = ToolObservationRetention.content(original, 65536)
        assertTrue(bounded is ToolResultContent.Json)
        val text = (bounded as ToolResultContent.Json).value.toString()
        assertTrue(text.toByteArray().size <= 65536)
        assertEquals(JsonPrimitive(true), Json.parseToJsonElement(text).jsonObject["observationTruncated"])
    }

    @Test fun savedReadsAcceptMatchingSelectorsAndRejectScopeChanges() {
        val views = GitHubResultViews()
        val request = json("""{"action":"runs","owner":"o","repo":"r","workflow_id":"build.yml","page":1,"per_page":20}""")
        val original = AgentToolResult("first", ToolResultContent.Json(json("""{"workflow_runs":[],"has_more":false}""")), false)
        val page = (views.present("scope", "github_ci", "github_ci", original, request).content as ToolResultContent.Json).value.jsonObject
        val cursor = (page.getValue("recovery_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor") as JsonPrimitive).content
        val carried = JsonObject(request + ("response_detail" to JsonPrimitive("compact")))
        val resumed = views.read("scope", "github_ci", "github_ci", cursor, "next", carried)
        assertFalse(resumed.isError)
        assertFalse(resumed.dispatched)
        for ((key, value) in mapOf("repo" to JsonPrimitive("other"), "workflow_id" to JsonPrimitive("release.yml"), "page" to JsonPrimitive(2))) {
            assertTrue(views.read("scope", "github_ci", "github_ci", cursor, "bad", JsonObject(carried + (key to value))).isError)
        }
        assertTrue(views.read("other-scope", "github_ci", "github_ci", cursor, "foreign", carried).isError)
    }
}
