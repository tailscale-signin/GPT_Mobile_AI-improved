package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChecklistPaginationTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private val tools = listOf(AgentToolDefinition("github_context", "Branches", json("""{"properties":{"action":{"const":"branches"},"view_cursor":{"type":"string"}}}""")))
    private val page = json("""{"tool":"github_context","arguments":{"view_cursor":"saved:1"}}""")
    private val recovery = json("""{"tool":"github_context","arguments":{"action":"branches","per_page":5}}""")
    private fun source() = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"branches","per_page":5},"result":{"local_page_complete":false,"continuation_call":$page}}""")
    private fun failed(recipe: JsonObject = recovery) = json("""{"trusted":true,"success":false,"tool":"github_context","arguments":{"view_cursor":"saved:1"},"error_code":"SAVED_VIEW_UNAVAILABLE","result":{"reason":"CAPACITY_EVICTED","recovery_call":$recipe}}""")

    @Test fun unreadLocalPageRunsBeforeAnotherInventoryCanEvictIt() {
        val next = requireNotNull(GitHubTestChecklist.nextCall("test all tools", tools, mapOf("source" to source())))
        assertEquals(page["arguments"], next.arguments)
    }

    @Test fun failedPageRemainsPendingAndReopensExactIssuedSourceOnce() {
        val records = linkedMapOf("source" to source(), "failure" to failed())
        assertEquals(listOf(page), GitHubTestChecklist.pendingPages(records))
        val next = requireNotNull(GitHubTestChecklist.nextCall("test all tools", tools, records))
        assertEquals(recovery["arguments"], next.arguments)
        // Restoration after process death must keep the recovery budget spent.
        records["reopened"] = json(source().toString().replace("saved:1", "fresh:1"))
        assertEquals(json("""{"view_cursor":"fresh:1"}"""), GitHubTestChecklist.nextCall("test all tools", tools, records.toMap())?.arguments)
        records["lostAgain"] = json(failed().toString().replace("saved:1", "fresh:1"))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", tools, records.toMap()))
        assertFalse(GitHubTestChecklist.pendingPages(records).isEmpty())
    }

    @Test fun sourceRecoveryCannotReplayMutationOrUnauthorizedTool() {
        val mutation = json("""{"tool":"github_commit","arguments":{"action":"branch","branch":"unsafe"}}""")
        val commitTool = AgentToolDefinition("github_commit", "Branch", json("""{"properties":{"action":{"const":"branch"}}}"""))
        for (recipe in listOf(mutation, json("""{"tool":"github_read__other","arguments":{"view_cursor":"foreign:1"}}"""))) {
            val records = mapOf("source" to source(), "failure" to failed(recipe))
            assertEquals(null, GitHubTestChecklist.nextCall("test all tools read only", tools + commitTool, records))
        }
    }

    @Test fun permissionFailureCannotTriggerSavedPageRecovery() {
        val failure = JsonObject(failed() + ("error_code" to JsonPrimitive("PERMISSION_DENIED")))
        val records = mapOf("source" to source(), "failure" to failure)
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", tools, records))
        assertEquals(listOf(page), GitHubTestChecklist.pendingPages(records))
    }

    @Test fun requiredLocalFragmentsContinueAfterUnrelatedSamplingBudget() {
        val records = linkedMapOf("source" to source())
        for (i in 1..2) records["page$i"] = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"view_cursor":"previous:$i"},"result":{}}""")
        assertEquals(page["arguments"], GitHubTestChecklist.nextCall("test all tools", tools, records)?.arguments)
        assertEquals(page["arguments"], GitHubTestChecklist.nextCall("test all tools and all branches", tools, records)?.arguments)
        assertTrue(GitHubTestChecklist.pendingPages(records).isNotEmpty())
    }

    @Test fun sampleBudgetIsBoundedAndExplicitInventoryCanContinue() {
        val remote = json("""{"tool":"github_context","arguments":{"action":"branches","page":2}}""")
        val records = linkedMapOf("source" to json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"branches"},"result":{"local_page_complete":true,"continuation_call":$remote}}"""))
        for (i in 3..4) records["page$i"] = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"branches","page":$i},"result":{}}""")
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", tools, records))
        assertEquals(remote["arguments"], GitHubTestChecklist.nextCall("test all tools and all branches", tools, records)?.arguments)
        assertEquals(listOf(remote), GitHubTestChecklist.pendingPages(records))
    }

    @Test fun missingIssueDependencyDoesNotForceUnboundedRemotePagination() {
        val issueTools = listOf(AgentToolDefinition("github_issue", "Issue inventory", json("""{"properties":{"action":{"const":"list"},"page":{"type":"integer"}}}""")))
        val remote = json("""{"tool":"github_issue","arguments":{"action":"list","page":2}}""")
        val records = linkedMapOf<String, JsonObject>()
        records["source"] = json("""{"trusted":true,"success":true,"tool":"github_issue","arguments":{"action":"list","page":1},"result":{"continuation_call":$remote}}""")
        for (i in 3..10) {
            records["sample$i"] = json("""{"trusted":true,"success":true,"tool":"github_issue","arguments":{"action":"list","page":$i},"result":{}}""")
        }
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", issueTools, records))
        assertEquals(remote["arguments"], GitHubTestChecklist.nextCall("test all tools and all history", issueTools, records)?.arguments)
        assertEquals(remote["arguments"], GitHubTestChecklist.nextCall("test all tools and all issues", issueTools, records)?.arguments)
    }

    @Test fun missingExecutedJobCanReadAnIssuedExactHeadRunPageAfterOrdinarySamples() {
        val (available, records, remote) = workflowDependency()
        val original = records.toString()
        assertEquals(remote, GitHubTestChecklist.paginationResume("test all tools", records, available)["next_call"])
        assertEquals(remote["arguments"], GitHubTestChecklist.nextCall("test all tools", available, records)?.arguments)
        assertEquals(original, records.toString())
        repeat(6) { index ->
            records["extra$index"] = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"branches","page":${index + 5}},"result":{}}""")
        }
        assertEquals(remote, GitHubTestChecklist.paginationResume("test all tools", records, available)["next_call"])
        repeat(8) { index ->
            records["runPage$index"] = json("""{"trusted":true,"success":true,"binding":"task","repository":"owner/repo","tool":"github_ci","arguments":{"action":"runs","commit_sha":"${"a".repeat(40)}","page":${index + 3}},"result":{}}""")
        }
        assertFalse("next_call" in GitHubTestChecklist.paginationResume("test all tools", records, available))
        assertEquals(remote, GitHubTestChecklist.paginationResume("test all tools and all history", records, available)["next_call"])
    }

    @Test fun workflowDependencyPagesNeedCurrentAuthorityRevisionAndAnUnresolvedExecutableJob() {
        val (available, records, _) = workflowDependency()
        val ciReadOnly = listOf(AgentToolDefinition("github_ci", "Run inventory", json("""{"properties":{"action":{"const":"runs"}}}""")))
        assertFalse("next_call" in GitHubTestChecklist.paginationResume("test all tools", records, ciReadOnly))
        val changes = listOf(
            "runs" to JsonObject(records.getValue("runs") + ("binding" to JsonPrimitive("another-task"))),
            "runs" to JsonObject(records.getValue("runs") + ("tool" to JsonPrimitive("github_ci__other"))),
            "runs" to json(records.getValue("runs").toString().replace("\"action\":\"runs\",\"commit_sha\":\"${"a".repeat(40)}\",\"page\":1", "\"action\":\"runs\",\"commit_sha\":\"${"b".repeat(40)}\",\"page\":1")),
            "status" to json(records.getValue("status").toString().replace("a".repeat(40), "b".repeat(40))),
            "jobs" to JsonObject(records.getValue("jobs") + ("success" to JsonPrimitive(false))),
            "jobs" to json(records.getValue("jobs").toString().replace("skipped", "success")),
            "status" to JsonObject(records.getValue("status") + ("repository_role" to JsonPrimitive("READ_SOURCE")))
        )
        for ((id, changed) in changes) {
            assertFalse("$id: $changed", "next_call" in GitHubTestChecklist.paginationResume("test all tools", records + (id to changed), available))
        }
    }

    private fun workflowDependency(): Triple<List<AgentToolDefinition>, LinkedHashMap<String, JsonObject>, JsonObject> {
        val head = "a".repeat(40)
        val remote = json("""{"tool":"github_ci","arguments":{"action":"runs","commit_sha":"$head","per_page":30,"page":2}}""")
        fun bound(value: JsonObject) = JsonObject(value + mapOf("binding" to JsonPrimitive("task"), "repository" to JsonPrimitive("owner/repo")))
        val records = linkedMapOf(
            "status" to bound(json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$head"}}""")),
            "runs" to bound(json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs","commit_sha":"$head","page":1},"result":{"workflow_runs":[{"id":7,"head_sha":"$head","run_attempt":1,"status":"completed","conclusion":"success"}],"continuation_call":$remote}}""")),
            "jobs" to bound(json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"jobs","run_id":7,"run_attempt":1,"expected_head_sha":"$head"},"result":{"jobs":[{"id":8,"run_id":7,"run_attempt":1,"head_sha":"$head","status":"completed","conclusion":"skipped"}]}}"""))
        )
        repeat(2) { index -> records["sample$index"] = bound(json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"branches","page":${index + 3}},"result":{}}""")) }
        val available = listOf(AgentToolDefinition("github_ci", "Workflow reads", json("""{"properties":{"action":{"enum":["runs","jobs","job","logs"]}}}""")))
        return Triple(available, records, remote)
    }

    @Test fun consumedLocalFragmentsDoNotSpendTheRemoteSamplingAllowance() {
        val remote = json("""{"tool":"github_context","arguments":{"action":"branches","page":2}}""")
        val records = linkedMapOf("source" to json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"branches"},"result":{"local_page_complete":true,"continuation_call":$remote}}"""))
        repeat(20) { index ->
            records["fragment$index"] = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"view_cursor":"previous:$index"},"result":{}}""")
        }
        assertEquals(remote["arguments"], GitHubTestChecklist.nextCall("test all tools", tools, records)?.arguments)
    }

    @Test fun successfulPageClosesPendingContinuation() {
        val success = JsonObject(failed() + ("success" to JsonPrimitive(true)))
        assertTrue(GitHubTestChecklist.pendingPages(mapOf("source" to source(), "read" to success)).isEmpty())
    }

    @Test fun duplicatePageRecipesKeepTheStrongestFreshnessAndExactRevision() {
        val head = "a".repeat(40)
        fun receipt(id: String, args: String, revision: String = head, binding: String = "task") = json("""{"receipt_id":"$id","trusted":true,"success":true,"binding":"$binding","repository":"owner/repo","source_head_sha":"$revision","tool":"github_ci","arguments":{"action":"definitions"},"result":{"continuation_call":{"tool":"github_ci","arguments":$args}}}""")
        val weak = """{"action":"definitions","index_cursor":4,"commit_sha":"$head"}"""
        val strong = """{"action":"definitions","index_cursor":4,"commit_sha":"$head","verify_live":true}"""
        val records = linkedMapOf("old" to receipt("old", weak), "live" to receipt("live", strong))
        assertEquals(listOf(json("""{"tool":"github_ci","arguments":$strong}""")), GitHubTestChecklist.pendingPages(records))
        records["different-revision"] = receipt("different-revision", weak.replace(head, "b".repeat(40)), "b".repeat(40))
        records["other-task"] = receipt("other-task", strong, binding = "other")
        assertEquals(3, GitHubTestChecklist.pendingPages(records).size)
        val completed = json("""{"trusted":true,"success":true,"binding":"task","repository":"owner/repo","tool":"github_ci","arguments":$strong,"result":{}}""")
        assertEquals(2, GitHubTestChecklist.pendingPages(records + ("completed" to completed)).size)
    }

    @Test fun sourceReadResumeProofRequiresTheBoundIssuerAndAnUnspentAuthorizedRecipe() {
        fun bound(value: JsonObject, id: String) = JsonObject(value + mapOf("receipt_id" to JsonPrimitive(id), "call_id" to JsonPrimitive(id), "binding" to JsonPrimitive("task"), "repository" to JsonPrimitive("owner/repo")))
        val original = bound(source(), "source")
        val rejected = JsonObject(bound(failed(), "failed-cursor") + ("dispatched" to JsonPrimitive(false)))
        val records = linkedMapOf("source" to original, "failure" to rejected)
        val proof = requireNotNull(GitHubTestChecklist.sourceReadRecovery(tools, records))
        assertEquals(JsonPrimitive("failed-cursor"), proof["failed_call_id"])
        assertEquals(JsonPrimitive("source"), proof["source_receipt_id"])
        assertEquals(recovery["arguments"], proof["arguments"])
        assertEquals(null, GitHubTestChecklist.sourceReadRecovery(emptyList(), records))
        assertEquals(null, GitHubTestChecklist.sourceReadRecovery(tools, mapOf("failure" to rejected)))
        assertEquals(null, GitHubTestChecklist.sourceReadRecovery(tools, records + ("reopened" to bound(source(), "reopened"))))
        val foreign = JsonObject(original + ("binding" to JsonPrimitive("foreign")))
        assertEquals(null, GitHubTestChecklist.sourceReadRecovery(tools, records + ("source" to foreign)))
    }

    @Test fun repositoryBindingEnrichmentClosesExactPagesAndSpendsLogSamplingBudget() {
        fun bound(row: JsonObject, binding: String = "task"): JsonObject {
            val arguments = row["arguments"] as JsonObject
            return JsonObject(row + mapOf("binding" to JsonPrimitive(binding), "repository" to JsonPrimitive("owner/repo"), "arguments" to JsonObject(arguments + mapOf("owner" to JsonPrimitive("owner"), "repo" to JsonPrimitive("repo")))))
        }
        fun log(offset: Int, next: Int) = bound(json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"logs","job_id":7,"start_offset":$offset},"result":{"continuation_call":{"tool":"github_ci","arguments":{"action":"logs","job_id":7,"start_offset":$next}}}}"""))
        val records = linkedMapOf("first" to log(0, 40), "second" to log(40, 80), "third" to log(80, 120))
        val pending = GitHubTestChecklist.pendingPages(records)
        assertEquals(1, pending.size)
        assertEquals(JsonPrimitive(120), (pending.single()["arguments"] as JsonObject)["start_offset"])
        val definition = AgentToolDefinition("github_ci", "Logs", json("""{"properties":{"action":{"const":"logs"}}}"""))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(definition), records))
        assertEquals(JsonPrimitive(2), GitHubTestChecklist.paginationResume("test all tools", records, listOf(definition))["remote_samples_spent"])
        val foreign = JsonObject(records.getValue("second") + ("binding" to JsonPrimitive("another-account")))
        assertEquals(2, GitHubTestChecklist.pendingPages(linkedMapOf("first" to records.getValue("first"), "foreign" to foreign)).size)
    }

    @Test fun targetedDependencyPagesPrecedeBroadHistoryWithoutChangingIssuedSelectors() {
        val head = "a".repeat(40)
        val broad = json("""{"tool":"github_context","arguments":{"action":"commits","page":2}}""")
        val targeted = json("""{"tool":"github_ci","arguments":{"action":"runs","page":2,"commit_sha":"$head","per_page":30}}""")
        val definitions = listOf(
            AgentToolDefinition("github_context", "Commits", json("""{"properties":{"action":{"const":"commits"}}}""")),
            AgentToolDefinition("github_ci", "Runs", json("""{"properties":{"action":{"const":"runs"}}}"""))
        )
        val records = linkedMapOf(
            "status" to json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$head"}}"""),
            "history" to json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"commits"},"result":{"continuation_call":$broad}}"""),
            "runs" to json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs","commit_sha":"$head"},"result":{"continuation_call":$targeted}}""")
        )
        val next = requireNotNull(GitHubTestChecklist.nextCall("test all tools", definitions, records))
        assertEquals("github_ci", next.name)
        assertEquals(targeted["arguments"], next.arguments)
        assertEquals(2, GitHubTestChecklist.pendingPages(records).size)
        assertEquals(JsonPrimitive(false), GitHubTestChecklist.paginationResume("test all tools", records, definitions)["complete"])
    }

    @Test fun defaultRunInventoryIsPinnedAndWorkflowInventoryUsesFullPage() {
        val sha = "a".repeat(40)
        val records = mapOf("status" to json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}"""))
        val ci = AgentToolDefinition("github_ci", "Runs", json("""{"properties":{"action":{"const":"runs"}}}"""))
        val selected = requireNotNull(GitHubTestChecklist.nextCall("test all tools", listOf(ci), records))
        assertEquals(JsonPrimitive(sha), selected.arguments["commit_sha"])
        assertEquals(JsonPrimitive(30), selected.arguments["per_page"])
        val history = requireNotNull(GitHubTestChecklist.nextCall("test all tools and all runs", listOf(ci), records))
        assertFalse(history.arguments.containsKey("commit_sha"))
        assertEquals(JsonPrimitive(100), history.arguments["per_page"])
        val workflowTool = AgentToolDefinition("github_ci", "Workflows", json("""{"properties":{"action":{"const":"workflows"}}}"""))
        assertEquals(JsonPrimitive(100), GitHubTestChecklist.nextCall("test all tools", listOf(workflowTool), records)?.arguments?.get("per_page"))
    }

    @Test fun issuedDefinitionContinuationSurvivesUnrelatedHistoryBudget() {
        val sha = "a".repeat(40)
        val remote = json("""{"tool":"github_ci","arguments":{"action":"definitions","commit_sha":"$sha","index_cursor":4}}""")
        val records = linkedMapOf("source" to json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"definitions","commit_sha":"$sha"},"result":{"local_page_complete":true,"continuation_call":$remote}}"""))
        repeat(8) { i -> records["runs$i"] = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs","page":${i + 2}},"result":{}}""") }
        val ci = AgentToolDefinition("github_ci", "Definitions", json("""{"properties":{"action":{"const":"definitions"}}}"""))
        assertEquals(remote["arguments"], GitHubTestChecklist.nextCall("test all tools", listOf(ci), records)?.arguments)
        // Finite source-catalog traversal still leaves a truthful pending checkpoint.
        repeat(31) { i -> records["definition$i"] = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"definitions","commit_sha":"$sha","index_cursor":${(i + 2) * 4}},"result":{}}""") }
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(ci), records))
        assertEquals(listOf(remote), GitHubTestChecklist.pendingPages(records))
    }

    @Test fun skippedWorkflowInventoryFollowsItsExactRevisionDependencyAfterOrdinarySamplesAreSpent() {
        val sha = "a".repeat(40)
        val remote = json("""{"tool":"github_ci","arguments":{"action":"runs","commit_sha":"$sha","page":2}}""")
        fun receipt(tool: String, args: String, result: String) = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"$tool","arguments":$args,"result":$result}""")
        val skipped = (1..30).joinToString(",") { """{"id":$it,"run_attempt":1,"head_sha":"$sha","status":"completed","conclusion":"skipped"}""" }
        val records = linkedMapOf(
            "status" to receipt("github_context", """{"action":"status"}""", """{"commit_sha":"$sha"}"""),
            "runs" to receipt("github_ci", """{"action":"runs","commit_sha":"$sha"}""", """{"workflow_runs":[$skipped],"continuation_call":$remote}"""),
            "jobs" to receipt("github_ci", """{"action":"jobs","run_id":1,"run_attempt":1,"expected_head_sha":"$sha"}""", """{"jobs":[]}""")
        )
        repeat(2) { i -> records["history$i"] = receipt("github_context", """{"action":"commits","page":${i + 2}}""", "{}") }
        val ci = AgentToolDefinition("github_ci", "CI", json("""{"properties":{"action":{"enum":["runs","jobs","job","logs"]}}}"""))
        val next = GitHubTestChecklist.nextCall("test all tools", listOf(ci), records)
        assertEquals(remote["arguments"], next?.arguments)
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(ci), records, remainingToolCalls = 2))
        val foreignIssuer = JsonObject(records.getValue("runs") + ("binding" to JsonPrimitive("other-task")))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(ci), records + ("runs" to foreignIssuer)))
        val job = """{"id":31,"run_attempt":1,"head_sha":"$sha","status":"completed","conclusion":"success"}"""
        records["page2"] = receipt("github_ci", remote["arguments"].toString(), """{"workflow_runs":[$job]}""")
        assertEquals(json("""{"action":"jobs","run_id":31,"run_attempt":1,"expected_head_sha":"$sha"}"""), GitHubTestChecklist.nextCall("test all tools", listOf(ci), records)?.arguments)
        records["jobs31"] = receipt("github_ci", """{"action":"jobs","run_id":31,"run_attempt":1,"expected_head_sha":"$sha"}""", """{"jobs":[{"id":310,"run_id":31,"run_attempt":1,"head_sha":"$sha","status":"completed","conclusion":"success","steps":[{"status":"completed","conclusion":"success"}]}]}""")
        val log = GitHubSelfTestReads.available(listOf(ci), records.values.toList()).single { it.arguments["action"] == JsonPrimitive("logs") }
        assertEquals(JsonPrimitive(310), log.arguments["job_id"])
        assertEquals(JsonPrimitive(sha), log.arguments["expected_head_sha"])
    }

    @Test fun localFragmentsFinishBeforePendingCheckWatch() {
        val sha = "a".repeat(40)
        val records = mapOf(
            "source" to source(),
            "status" to json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}"""),
            "runs" to json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[{"id":7,"head_sha":"$sha","status":"in_progress"}]}}""")
        )
        val watch = AgentToolDefinition("github_watch_checks", "Watch", json("""{"type":"object"}"""))
        assertEquals(page["arguments"], GitHubTestChecklist.nextCall("test all tools", tools + watch, records)?.arguments)
    }

    @Test fun issueHistoryAndTagContinuationsAreNotSilentlyStranded() {
        for ((name, action) in listOf("github_issue" to "history", "github_release" to "tags")) {
            val args = json("""{"action":"$action","page":2,"issue_number":7}""")
            val continuation = json("""{"tool":"$name","arguments":$args}""")
            val source = json("""{"trusted":true,"success":true,"dispatched":true,"tool":"$name","arguments":{"action":"$action","issue_number":7},"result":{"continuation_call":$continuation}}""")
            val definition = AgentToolDefinition(name, action, json("""{"properties":{"action":{"const":"$action"}}}"""))
            assertEquals(args, GitHubTestChecklist.nextCall("test all tools", listOf(definition), mapOf("source" to source))?.arguments)
        }
    }

    @Test fun logOffsetContinuationConsumesTheSameBoundedRemoteAllowance() {
        val definition = AgentToolDefinition("github_ci", "Logs", json("""{"properties":{"action":{"const":"logs"}}}"""))
        fun args(offset: Int) = json("""{"action":"logs","job_id":7,"start_offset":$offset}""")
        fun source(offset: Int, next: Int) = json("""{"trusted":true,"success":true,"dispatched":true,"tool":"github_ci","arguments":${args(offset)},"result":{"continuation_call":{"tool":"github_ci","arguments":${args(next)}}}}""")
        val receipts = linkedMapOf("first" to source(0, 40))
        assertEquals(args(40), GitHubTestChecklist.nextCall("test all tools", listOf(definition), receipts)?.arguments)
        receipts["second"] = source(40, 80)
        assertEquals(args(80), GitHubTestChecklist.nextCall("test all tools", listOf(definition), receipts)?.arguments)
        receipts["third"] = source(80, 120)
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(definition), receipts))
        assertEquals(args(120), GitHubTestChecklist.nextCall("test all tools and complete pending pagination", listOf(definition), receipts)?.arguments)
        val checkpoint = GitHubTestChecklist.paginationResume("test all tools", receipts, listOf(definition))
        assertEquals(JsonPrimitive(2), checkpoint["remote_samples_spent"])
        assertEquals(JsonPrimitive(false), checkpoint["complete"])
        assertEquals(JsonPrimitive("BOUNDED_SMOKE_SAMPLE"), checkpoint["mode"])
    }

    @Test fun resumedPaginationPreservesBudgetAndDoesNotAuthorizeWrites() {
        val remote = json("""{"tool":"github_context","arguments":{"action":"branches","page":2}}""")
        val receipts = mapOf("source" to json("""{"trusted":true,"success":true,"dispatched":true,"tool":"github_context","arguments":{"action":"branches"},"result":{"continuation_call":$remote}}"""))
        val task = "test all tools read only and complete remaining pages"
        assertTrue(GitHubChecklistScope.exhaustive(task))
        assertTrue(GitHubChecklistScope.readOnly(task))
        val resume = GitHubTestChecklist.paginationResume(task, receipts, tools, remainingToolCalls = 2)
        assertEquals(JsonPrimitive(1), resume["pending_count"])
        assertFalse(resume.containsKey("next_call"))
        assertFalse(GitHubTestChecklist.apiComplete(task, receipts, tools))
    }

    @Test fun terminalReadbackAllowsOnlyOneBoundedWatchQualificationInsteadOfHistoricalPendingWait() {
        val sha = "a".repeat(40)
        val receipts = mapOf(
            "status" to json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}"""),
            "runs" to json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[{"id":7,"run_attempt":1,"head_sha":"$sha","status":"in_progress"}]}}"""),
            "run" to json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"run","run_id":7},"result":{"id":7,"run_attempt":1,"head_sha":"$sha","status":"completed","conclusion":"success"}}""")
        )
        val watch = AgentToolDefinition("github_watch_checks", "Watch", json("""{"type":"object"}"""))
        val probe = requireNotNull(GitHubTestChecklist.nextCall("test all tools", listOf(watch), receipts))
        assertEquals(json("""{"run_id":7,"commit_sha":"$sha","wait_timeout_seconds":1}"""), probe.arguments)
        val observed = json("""{"trusted":true,"success":true,"tool":"github_watch_checks","arguments":${probe.arguments},"result":{"status":"completed","inline":true}}""")
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(watch), receipts + ("watch" to observed)))
    }

    @Test fun failedPageAdviceDoesNotClaimThatPaginationWasCompleted() {
        val remote = json("""{"tool":"github_context","arguments":{"action":"branches","page":2}}""")
        val receipts = mapOf(
            "source" to json("""{"trusted":true,"success":true,"dispatched":true,"tool":"github_context","arguments":{"action":"branches"},"result":{"continuation_call":$remote}}"""),
            "denied" to json("""{"trusted":true,"success":false,"dispatched":false,"tool":"github_context","arguments":{"action":"branches","page":2},"error_code":"AUTH_REQUIRED"}""")
        )
        val resume = GitHubTestChecklist.paginationResume("test all tools and complete pending pagination", receipts, tools)
        assertEquals(JsonPrimitive(1), resume["failed_page_count"])
        assertFalse(resume.containsKey("next_call"))
        assertEquals(JsonPrimitive(false), resume["complete"])
    }

    @Test fun negatedPaginationRequestDoesNotExpandSampling() {
        for (task in listOf("test all tools; do not complete pending pagination", "test all tools; never resume unread pages", "test all tools; don't finish remaining reads")) {
            assertFalse(task, GitHubChecklistScope.exhaustive(task))
        }
        assertTrue(GitHubChecklistScope.exhaustive("test all tools and finish pending reads"))
        assertTrue(GitHubChecklistScope.exhaustive("test all tools and complete remaining pagination"))
    }

    @Test fun issuedContinuationCannotSwitchBranchDispatchOrIndexTheGraph() {
        for ((name, action, extra) in listOf(
            Triple("github_context", "resolve", "\"ref\":\"other-branch\""),
            Triple("github_ci", "dispatch", "\"ref\":\"main\""),
            Triple("repository_graph", "index", "\"query\":\"ignored\"")
        )) {
            val recipe = json("""{"tool":"$name","arguments":{"action":"$action",$extra}}""")
            val source = json("""{"trusted":true,"success":true,"tool":"$name","arguments":{"action":"status"},"result":{"continuation_call":$recipe}}""")
            val tool = AgentToolDefinition(name, action, json("""{"properties":{"action":{"const":"$action"}}}"""))
            val resume = GitHubTestChecklist.paginationResume("test all tools read only and complete pending pages", mapOf("source" to source), listOf(tool))
            assertFalse(name, "next_call" in resume)
            assertEquals(JsonPrimitive(false), resume["complete"])
        }
    }

    @Test fun hiddenToolCannotBeSelectedByContinuationOrAutomaticProbe() {
        val hidden = tools.map { it.copy(modelVisible = false) }
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools and complete pending pages", hidden, mapOf("source" to source())))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", hidden, emptyMap()))
    }
}
