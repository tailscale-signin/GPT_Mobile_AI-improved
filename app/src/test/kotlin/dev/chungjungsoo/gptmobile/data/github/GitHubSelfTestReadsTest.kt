package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubSelfTestReadsTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun tool(name: String, action: String) = AgentToolDefinition(name, action, json("""{"type":"object","properties":{"action":{"const":"$action"}}}"""))

    @Test fun releaseStatusWaitsForAnObservedTag() {
        val definition = tool("github_release", "status")
        assertEquals(null, GitHubSelfTestReads.next(listOf(definition), emptyList()))
        val receipt = json("""{"trusted":true,"success":true,"tool":"github_release","arguments":{"action":"tags"},"result":{"items":[{"name":"v1"}]}}""")
        assertEquals(JsonPrimitive("v1"), GitHubSelfTestReads.next(listOf(definition), listOf(receipt))?.arguments?.get("tag_name"))
    }

    @Test fun logReadsSkipJobsThatDidNotExecute() {
        for (conclusion in listOf("skipped", "neutral", "cancelled")) {
            val receipt = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"jobs"},"result":{"jobs":[{"id":1,"status":"completed","conclusion":"$conclusion"},{"id":2,"status":"completed","conclusion":"failure"}]}}""")
            assertEquals(JsonPrimitive(2), GitHubSelfTestReads.next(listOf(tool("github_ci", "logs")), listOf(receipt))?.arguments?.get("job_id"))
        }
    }

    @Test fun scopedTestsDoNotAttemptAccountWideRepositoryEnumeration() {
        val receipt = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"repository":{"full_name":"owner/repo"}}}""")
        assertEquals(null, GitHubSelfTestReads.next(listOf(tool("github_context", "repositories")), listOf(receipt)))
    }
}
