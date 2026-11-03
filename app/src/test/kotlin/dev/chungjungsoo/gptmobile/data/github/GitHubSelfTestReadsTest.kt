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

    @Test fun definitionsUsePinnedTreeInsteadOfDeletedOrGeneratedActionsWorkflowIds() {
        val sha = "a".repeat(40)
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val workflows = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"workflows"},"result":{"workflows":[{"id":1,"path":"dynamic/old","state":"deleted"}]}}""")
        val next = requireNotNull(GitHubSelfTestReads.next(listOf(tool("github_ci", "definitions")), listOf(status, workflows)))
        assertEquals(json("""{"action":"definitions","commit_sha":"$sha"}"""), next.arguments)
        assertEquals(null, GitHubSelfTestReads.next(listOf(tool("github_ci", "definitions")), listOf(workflows)))
    }

    @Test fun excludedOrFailedInventoryCannotSupplyAReleaseTag() {
        for (flags in listOf("\"trusted\":false,\"success\":true", "\"trusted\":true,\"success\":false", "\"trusted\":true,\"success\":true,\"verification_eligible\":false")) {
            val receipt = json("""{$flags,"tool":"github_release","arguments":{"action":"tags"},"result":{"items":[{"name":"v1"}]}}""")
            assertEquals(null, GitHubSelfTestReads.next(listOf(tool("github_release", "status")), listOf(receipt)))
        }
    }

    @Test fun persistedMalformedReleaseRequestGetsOneCorrectedRead() {
        val tag = json("""{"trusted":true,"success":true,"tool":"github_release","arguments":{"action":"tags"},"result":{"items":[{"name":"v1"}]}}""")
        val failed = json("""{"trusted":true,"success":false,"tool":"github_release","arguments":{"action":"status"},"error_code":"INVALID_ARGUMENTS"}""")
        val tools = listOf(tool("github_release", "status"))
        val next = requireNotNull(GitHubSelfTestReads.next(tools, listOf(failed, tag)))
        assertEquals(JsonPrimitive("v1"), next.arguments["tag_name"])
        val rejected = JsonObject(failed + ("arguments" to next.arguments))
        assertEquals(null, GitHubSelfTestReads.next(tools, listOf(failed, tag, rejected)))
        assertEquals(null, GitHubSelfTestReads.next(tools, listOf(JsonObject(failed + ("error_code" to JsonPrimitive("PERMISSION_DENIED"))), tag)))
    }

    @Test fun unavailableLogsAllowOnlyOneDifferentObservedJob() {
        val jobs = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"jobs"},"result":{"jobs":[{"id":1,"status":"completed","conclusion":"success"},{"id":2,"status":"completed","conclusion":"success"},{"id":3,"status":"completed","conclusion":"success"}]}}""")
        fun failed(id: Int) = json("""{"trusted":true,"success":false,"tool":"github_ci","arguments":{"action":"logs","job_id":$id,"start_line":1,"end_line":40},"error_code":"GITHUB_NOT_FOUND_OR_HIDDEN"}""")
        val tools = listOf(tool("github_ci", "logs"))
        assertEquals(JsonPrimitive(2), GitHubSelfTestReads.next(tools, listOf(jobs, failed(1)))?.arguments?.get("job_id"))
        assertEquals(null, GitHubSelfTestReads.next(tools, listOf(jobs, failed(1), failed(2))))
    }
}
