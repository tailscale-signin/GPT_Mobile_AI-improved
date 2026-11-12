package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSelfTestReadsTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun tool(name: String, action: String) = AgentToolDefinition(name, action, json("""{"type":"object","properties":{"action":{"const":"$action"}}}"""))

    @Test fun dependencyInventoriesPrecedeOptionalMetadataAndDefinitionPages() {
        val tools = listOf(tool("github_context", "resolve"), tool("github_issue", "list"), tool("github_release__tags", "tags"), tool("github_release__list", "list"), tool("github_ci", "definitions"))
        val sha = "a".repeat(40)
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val available = GitHubSelfTestReads.available(tools, listOf(status))
        assertEquals(listOf("github_issue", "github_release__tags", "github_release__list"), available.take(3).map { it.name })
        assertEquals("github_ci", available.last().name)
        assertEquals(listOf(true, true, true, false, false), available.map(GitHubSelfTestReads::isDependency))
    }

    @Test fun legacyInventoryReacquiresItsExactSourceOnceBeforeSelectingDependencies() {
        val sha = "a".repeat(40)
        val legacy = json("""{"receipt_id":"legacy","call_id":"old","binding":"task","trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs","commit_sha":"$sha","page":1},"result":{"observationTruncated":true,"excerpt":{"workflow_runs":[{"id":7,"head_sha":"$sha","status":"completed","conclusion":"skipped"}]}}}""")
        val tools = listOf(tool("github_ci", "runs"))
        val next = requireNotNull(GitHubSelfTestReads.next(tools, listOf(legacy)))
        assertTrue(next.callId.startsWith("inventory-source-"))
        assertEquals(legacy["arguments"], next.arguments)
        val failed = JsonObject(legacy + mapOf("call_id" to JsonPrimitive(next.callId + "-1"), "success" to JsonPrimitive(false)))
        assertEquals(null, GitHubSelfTestReads.next(tools, listOf(legacy, failed)))
        assertEquals(null, GitHubSelfTestReads.next(emptyList(), listOf(legacy)))
    }

    @Test fun firstLiveRequiredReadBypassesCacheOnlyWhenItsExactSchemaSupportsIt() {
        val sha = "a".repeat(40)
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val live = AgentToolDefinition("github_ci", "Definitions", json("""{"properties":{"action":{"const":"definitions"},"verify_live":{"type":"boolean"}}}"""))
        val first = requireNotNull(GitHubSelfTestReads.next(listOf(live), listOf(status)))
        assertEquals(JsonPrimitive(true), first.arguments["verify_live"])
        assertEquals(JsonPrimitive(sha), first.arguments["commit_sha"])
        val legacy = GitHubSelfTestReads.next(listOf(tool("github_ci", "definitions")), listOf(status))
        assertEquals(null, legacy?.arguments?.get("verify_live"))
        val source = AgentToolDefinition("github_search", "Search", json("""{"properties":{"action":{"const":"search"},"verify_live":{"type":"boolean"}}}"""))
        assertEquals(null, GitHubSelfTestReads.next(listOf(source), listOf(status))?.arguments?.get("verify_live"))
    }

    @Test fun historicalGreenRunCannotSupplyCurrentRevisionAcceptance() {
        val sha = "a".repeat(40)
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val runs = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[{"id":1,"head_sha":"${"b".repeat(40)}","status":"completed","conclusion":"success"},{"id":2,"head_sha":"$sha","status":"completed","conclusion":"failure"}]}}""")
        assertEquals(JsonPrimitive(2), GitHubSelfTestReads.next(listOf(tool("github_ci", "run")), listOf(status, runs))?.arguments?.get("run_id"))
    }

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

    @Test fun runSelectionPrefersExecutedWorkOverTheNewestSkippedWorkflow() {
        val sha = "a".repeat(40)
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val runs = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[{"id":10,"head_sha":"$sha","status":"completed","conclusion":"skipped"},{"id":9,"head_sha":"$sha","status":"completed","conclusion":"success"}]}}""")
        assertEquals(JsonPrimitive(9), GitHubSelfTestReads.next(listOf(tool("github_ci", "jobs")), listOf(status, runs))?.arguments?.get("run_id"))
    }

    @Test fun logSelectionRejectsKnownEmptyStepsAndPrefersRecordedExecution() {
        val receipt = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"jobs"},"result":{"jobs":[{"id":1,"status":"completed","conclusion":"success","steps":[]},{"id":2,"status":"completed","conclusion":"success"},{"id":3,"status":"completed","conclusion":"success","steps":[{"status":"completed","conclusion":"success"}]}]}}""")
        assertEquals(JsonPrimitive(3), GitHubSelfTestReads.next(listOf(tool("github_ci", "logs")), listOf(receipt))?.arguments?.get("job_id"))
    }

    @Test fun logReadsStayWithinTheExactRevisionRun() {
        val sha = "a".repeat(40)
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val runs = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[{"id":2,"head_sha":"$sha","status":"completed","conclusion":"failure"}]}}""")
        val jobs = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"jobs"},"result":{"jobs":[{"id":10,"run_id":1,"status":"completed","conclusion":"failure"},{"id":20,"run_id":2,"status":"completed","conclusion":"failure"}]}}""")
        assertEquals(JsonPrimitive(20), GitHubSelfTestReads.next(listOf(tool("github_ci", "logs")), listOf(status, runs, jobs))?.arguments?.get("job_id"))
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

    @Test fun runAndJobReadsCarryTheObservedRevisionConstraint() {
        val sha = "a".repeat(40)
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha","ref":"main"}}""")
        val runs = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[{"id":7,"head_sha":"$sha","status":"completed","conclusion":"success"}]}}""")
        val jobs = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"jobs"},"result":{"jobs":[{"id":9,"run_id":7,"status":"completed","conclusion":"success"}]}}""")
        for (action in listOf("run", "status", "jobs", "artifacts", "job", "logs")) {
            val next = requireNotNull(GitHubSelfTestReads.next(listOf(tool("github_ci", action)), listOf(status, runs, jobs).filterNot { action == "jobs" && it == jobs }))
            assertEquals(action, JsonPrimitive(sha), next.arguments["expected_head_sha"])
            assertEquals(action, JsonPrimitive(7), next.arguments["run_id"])
        }
        assertEquals(JsonPrimitive("main"), GitHubSelfTestReads.next(listOf(tool("github_context", "branch")), listOf(status))?.arguments?.get("branch"))
    }

    @Test fun successfulRunDoesNotHideAnObservedFailureAtTheSameRevision() {
        val sha = "a".repeat(40)
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val runs = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[{"id":1,"head_sha":"$sha","status":"completed","conclusion":"success"},{"id":2,"head_sha":"$sha","status":"completed","conclusion":"failure"}]}}""")
        assertEquals(json("""{"action":"failure","run_id":2,"expected_head_sha":"$sha"}"""), GitHubSelfTestReads.next(listOf(tool("github_ci", "failure")), listOf(status, runs))?.arguments)
    }

    @Test fun supersededFailureIsNotUsedToAnalyzeAnActiveRerun() {
        val sha = "a".repeat(40)
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val runs = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[{"id":7,"run_attempt":1,"head_sha":"$sha","status":"completed","conclusion":"failure"}]}}""")
        val newer = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"run","run_id":7},"result":{"id":7,"run_attempt":2,"head_sha":"$sha","status":"in_progress","conclusion":null}}""")
        assertEquals(null, GitHubSelfTestReads.next(listOf(tool("github_ci", "failure")), listOf(status, runs, newer)))
    }

    @Test fun historyPermissionDenialIsNotRetriedWithAnotherIssue() {
        val issues = json("""{"trusted":true,"success":true,"tool":"github_issue","arguments":{"action":"list"},"result":{"items":[{"number":2}]}}""")
        for (code in listOf("AUTH_REQUIRED", "PERMISSION_DENIED")) {
            val rejected = json("""{"trusted":true,"success":false,"dispatched":false,"tool":"github_issue","arguments":{"action":"history","issue_number":1},"error_code":"$code"}""")
            assertEquals(null, GitHubSelfTestReads.next(listOf(tool("github_issue", "history")), listOf(rejected, issues)))
        }
    }
}
