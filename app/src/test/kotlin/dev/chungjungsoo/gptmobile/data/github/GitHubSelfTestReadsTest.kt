package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSelfTestReadsTest {
    @Test fun completedRunSupportsOneBoundedInlineWatchWithoutInventingASubscriptionOrResource() {
        val sha = "a".repeat(40)
        val watch = AgentToolDefinition("github_watch_checks", "Watch", json("""{"type":"object","properties":{"run_id":{"type":"integer"},"commit_sha":{"type":"string"},"wait_timeout_seconds":{"type":"integer","minimum":1,"maximum":30}},"required":["run_id","commit_sha","wait_timeout_seconds"],"additionalProperties":false}"""))
        val source = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val runs = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[{"id":72,"head_sha":"$sha","status":"completed","conclusion":"success"}]}}""")
        assertEquals(null, GitHubSelfTestReads.next(listOf(watch), listOf(source)))
        val call = requireNotNull(GitHubSelfTestReads.next(listOf(watch), listOf(source, runs)))
        assertEquals(json("""{"run_id":72,"commit_sha":"$sha","wait_timeout_seconds":1}"""), call.arguments)
        val observed = json("""{"trusted":true,"success":true,"tool":"github_watch_checks","arguments":${call.arguments},"result":{"status":"completed"}}""")
        assertEquals(null, GitHubSelfTestReads.next(listOf(watch), listOf(source, runs, observed)))
    }

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun tool(name: String, action: String) = AgentToolDefinition(name, action, json("""{"type":"object","properties":{"action":{"const":"$action"}}}"""))

    @Test fun repositoryCapabilitiesNeedNoResourceAndQualifyOnlyTheirLocalResponseScope() {
        val definition = tool("github_repository", "capabilities")
        val call = requireNotNull(GitHubSelfTestReads.next(listOf(definition), emptyList()))
        assertEquals(json("""{"action":"capabilities"}"""), call.arguments)
        val receipt = json("""{"trusted":true,"success":true,"evidence_origin":"LOCAL","tool":"github_repository","arguments":${call.arguments},"result":{"schema":"github.repository-capabilities.v1","create":true}}""")
        val row = GitHubTestChecklist.rows(mapOf("capabilities" to receipt), listOf(definition), "test all tools").single() as JsonObject
        assertEquals(JsonPrimitive("PASSED"), row["status"])
        assertEquals(JsonPrimitive("RETAINED_RESPONSE"), row["acceptance_basis"])
        assertEquals(JsonPrimitive(false), row["requires_live_transport"])
        assertEquals(JsonPrimitive(false), row["live_verified"])
        assertEquals(null, GitHubSelfTestReads.next(listOf(definition), listOf(receipt)))
    }

    @Test fun repositoryStatusDoesNotInventALifecycleFromWorkspaceOrCiEvidence() {
        val definition = tool("github_repository", "status")
        val workspace = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"${"a".repeat(40)}","repository":{"id":7}}}""")
        val job = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"jobs"},"result":{"jobs":[{"id":7,"status":"completed"}]}}""")
        val receipts = mapOf("workspace" to workspace, "ci" to job)
        assertEquals(null, GitHubSelfTestReads.next(listOf(definition), receipts.values.toList()))
        val preset = "Test the authorized GitHub integration using the supported fixture test matrix. [github-workflow-preset:test_integration]"
        val row = GitHubTestChecklist.rows(receipts, listOf(definition), preset).filterIsInstance<JsonObject>().single { it["id"] == JsonPrimitive("github_repository:status") }
        assertEquals(JsonPrimitive("CONDITIONAL"), row["status"])
        assertEquals(JsonPrimitive(false), row["required"])
        assertEquals(JsonPrimitive("REPOSITORY_LIFECYCLE_REQUIRED"), row["blocker_code"])
        assertTrue(row["blocker"].toString().contains("operation_id"))
        assertTrue(row["blocker"].toString().contains("expected_repository_id"))
        val broad = GitHubTestChecklist.rows(receipts, listOf(definition), "test all tools").filterIsInstance<JsonObject>().single { it["id"] == JsonPrimitive("github_repository:status") }
        assertEquals(JsonPrimitive(true), broad["required"])
        assertEquals(JsonPrimitive("BLOCKED"), broad["status"])
    }

    @Test fun repositoryStatusUsesTheExactNativeCreationIdentityAndDestination() {
        val definition = tool("github_repository", "status")
        val creation = json("""{"trusted":true,"success":true,"tool":"github_repository","arguments":{"action":"create","destination_owner":"owner","destination_repo":"new","owner_type":"user","visibility":"private"},"result":{"schema":"github.repository-lifecycle.v1","operation":"create_repository","repository_id":123,"destination_repository":"owner/new","visibility":"private"}}""")
        val call = requireNotNull(GitHubSelfTestReads.next(listOf(definition), listOf(creation)))
        assertEquals(json("""{"action":"status","operation":"create_repository","expected_repository_id":123,"destination_owner":"owner","destination_repo":"new","owner_type":"user","visibility":"private"}"""), call.arguments)
        val result = creation["result"] as JsonObject
        val invalid = listOf(
            JsonObject(creation + ("trusted" to JsonPrimitive(false))),
            JsonObject(creation + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(creation + ("result" to JsonObject(result + ("destination_repository" to JsonPrimitive("other/new"))))),
            JsonObject(creation + ("result" to JsonObject(result + ("repository_id" to JsonPrimitive(0)))))
        )
        invalid.forEach { assertEquals(null, GitHubSelfTestReads.next(listOf(definition), listOf(it))) }
    }

    @Test fun repositoryGatewayStatusUsesTheRetainedTransferIdWithoutChangingItsOperation() {
        val definition = tool("github_repository", "status")
        val id = "repoop_" + "a".repeat(32)
        val transfer = json("""{"trusted":true,"success":true,"tool":"github_repository","arguments":{"action":"clone"},"result":{"schema":"github.repository-lifecycle.v1","operation":"clone_repository","operation_id":"$id"}}""")
        assertEquals(json("""{"action":"status","operation":"clone_repository","operation_id":"$id"}"""), GitHubSelfTestReads.next(listOf(definition), listOf(transfer))?.arguments)
        val changed = JsonObject(transfer + ("result" to JsonObject((transfer["result"] as JsonObject) + ("operation" to JsonPrimitive("import_repository")))))
        assertEquals(null, GitHubSelfTestReads.next(listOf(definition), listOf(changed)))
    }

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
