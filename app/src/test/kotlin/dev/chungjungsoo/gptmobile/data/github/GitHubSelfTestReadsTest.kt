package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSelfTestReadsTest {
    @Test fun compactPullInventoryKeepsTheOpenPullAndItsObservedRevision() {
        val head = "a".repeat(40)
        val original = json("""{"pull_requests":[{"number":2014,"state":"closed"},{"number":1970,"state":"open","head":{"sha":"$head"}}]}""")
        val receipt = json("""{"trusted":true,"success":true,"tool":"github_pr","arguments":{"action":"list"},"result":{"observationTruncated":true,"excerpt":{}}}""")
        val retained = JsonObject(receipt + ("inventory_observation" to requireNotNull(GitHubInventoryObservation.capture(original))))
        val tool = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.definition("github_pr")
        val next = GitHubSelfTestReads.available(listOf(tool), listOf(retained))
        val headers = next.single { it.arguments["action"] == JsonPrimitive("headers") }
        assertEquals(JsonPrimitive(1970), headers.arguments["pull_number"])
        assertEquals(JsonPrimitive(head), headers.arguments["expected_head_sha"])
        val status = next.single { it.arguments["action"] == JsonPrimitive("agent_merge_status") }
        assertEquals(null, status.arguments["expected_head_sha"])
        next.forEach { call ->
            assertEquals(emptyList<String>(), dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.errors("github_pr", call.arguments))
            assertTrue(GitHubSelfTestReads.valid(tool, call.arguments))
        }
    }

    @Test fun legacyMergeStatusRejectionGetsOneCorrectedReadOnItsExactAuthorizedTool() {
        val head = "a".repeat(40)
        val tool = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.definition("github_pr").copy(name = "github_pr__selected")
        val inventory = json("""{"trusted":true,"success":true,"tool":"github_pr__selected","arguments":{"action":"list"},"result":{"pull_requests":[{"number":2254,"state":"open","head":{"sha":"$head"}}]}}""")
        val rejected = json("""{"trusted":true,"success":false,"dispatched":false,"binding":"task","repository":"owner/repo","tool":"github_pr__selected","arguments":{"action":"agent_merge_status","pull_number":2254,"expected_head_sha":"$head","verify_live":true},"error_code":"INVALID_ARGUMENTS"}""")
        fun status(receipts: List<JsonObject>) = GitHubSelfTestReads.available(listOf(tool), listOf(inventory) + receipts).singleOrNull { it.arguments["action"] == JsonPrimitive("agent_merge_status") }
        val corrected = requireNotNull(status(listOf(rejected)))
        assertEquals("github_pr__selected", corrected.name)
        assertEquals(json("""{"action":"agent_merge_status","pull_number":2254,"verify_live":true}"""), corrected.arguments)
        assertTrue(GitHubSelfTestReads.valid(tool, corrected.arguments))
        val attempted = JsonObject(rejected + ("arguments" to corrected.arguments))
        assertEquals(null, status(listOf(rejected, attempted)))
        assertEquals(null, status(listOf(JsonObject(rejected + ("error_code" to JsonPrimitive("GITHUB_PERMISSION_DENIED"))))))
        assertEquals(null, status(listOf(JsonObject(rejected + ("dispatched" to JsonPrimitive(true))))))
        val anotherPull = JsonObject(rejected + ("arguments" to JsonObject((rejected["arguments"] as JsonObject) + ("pull_number" to JsonPrimitive(2255)))))
        assertEquals(null, status(listOf(anotherPull)))
    }

    @Test fun familySchemaCannotAdmitAnActionSpecificInvalidRecipe() {
        val tool = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.definition("github_pr")
        val invalid = json("""{"action":"agent_merge_status","pull_number":2254,"expected_head_sha":"${"a".repeat(40)}"}""")
        assertEquals(emptyList<String>(), dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(invalid, tool.inputSchema))
        assertTrue(!GitHubSelfTestReads.valid(tool, invalid))
        val read = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.definition("github_read")
        assertTrue(GitHubSelfTestReads.valid(read, json("""{"view_cursor":"saved:1"}""")))
    }

    @Test fun legacyEmptyCopilotListFailureIsRecheckedOnceWithoutAcceptingMalformedLists() {
        val tool = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.definition("github_pr").copy(name = "github_pr__github_api")
        val failed = json("""{"trusted":true,"success":false,"dispatched":true,"tool":"github_pr__github_api","arguments":{"action":"copilot_tasks","verify_live":true},"error_code":"GITHUB_PAGINATION_INCOMPLETE","result":{"schema":"github.copilot-task.v1","tasks":[],"page":1,"per_page":30,"has_more":false,"upstream_has_more":false,"complete":false,"reason":"MISSING_REMOTE_RECORD_COLLECTION"}}""")
        fun next(receipts: List<JsonObject>) = GitHubSelfTestReads.available(listOf(tool), receipts).singleOrNull { it.arguments["action"] == JsonPrimitive("copilot_tasks") }
        val retry = requireNotNull(next(listOf(failed)))
        assertEquals(failed["arguments"], retry.arguments)
        assertTrue(retry.callId.startsWith("copilot-empty-list-recheck-"))
        assertEquals(null, next(listOf(failed, JsonObject(failed + ("call_id" to JsonPrimitive(retry.callId))))))
        assertEquals(null, next(listOf(JsonObject(failed + ("error_code" to JsonPrimitive("GITHUB_PERMISSION_DENIED"))))))
        for (tasks in listOf(JsonObject(emptyMap()), JsonPrimitive("missing"))) {
            val malformed = JsonObject(failed + ("result" to JsonObject((failed.getValue("result") as JsonObject) + ("tasks" to tasks))))
            assertEquals(null, next(listOf(malformed)))
        }
        assertEquals(JsonPrimitive(false), failed["success"])
    }

    @Test fun releaseDetailUsesTheObservedReleaseIdEvenWhenItsTagHasNoPublishedRelease() {
        val definition = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.definition("github_release")
        val listed = json("""{"trusted":true,"success":true,"tool":"github_release","arguments":{"action":"list"},"result":{"items":[{"id":452622158,"draft":true,"tag_name":"v1"}]}}""")
        val tags = json("""{"trusted":true,"success":true,"tool":"github_release","arguments":{"action":"tags"},"result":{"items":[{"name":"v1"}]}}""")
        fun detail(records: List<JsonObject>) = GitHubSelfTestReads.available(listOf(definition), records).singleOrNull { it.arguments["action"] == JsonPrimitive("detail") }
        val args = requireNotNull(detail(listOf(listed, tags))).arguments
        assertEquals(JsonPrimitive(452622158), args["release_id"])
        val focused = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.GitHubActionSchema.project(definition, "detail"))
        assertTrue(GitHubSelfTestReads.valid(focused, args))
        assertEquals(args, GitHubSelfTestReads.available(listOf(focused), listOf(listed, tags)).single { it.arguments["action"] == JsonPrimitive("detail") }.arguments)
        assertTrue("tag_name" !in args)
        assertTrue(dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.errors("github_release", args).isEmpty())
        val native = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.nativeArguments("github_release", args)
        assertEquals(JsonPrimitive("get_release"), native?.get("action"))
        assertEquals(null, detail(listOf(tags)))
        val missedTag = json("""{"trusted":true,"success":false,"tool":"github_release","arguments":{"action":"detail","tag_name":"v1"},"error_code":"GITHUB_NOT_FOUND_OR_HIDDEN"}""")
        assertEquals(args, detail(listOf(listed, tags, missedTag))?.arguments)
        val missingId = JsonObject(missedTag + ("arguments" to args))
        assertEquals(null, detail(listOf(listed, missedTag, missingId)))
        val forbidden = JsonObject(missedTag + ("error_code" to JsonPrimitive("GITHUB_PERMISSION_DENIED")))
        assertEquals(null, detail(listOf(listed, forbidden)))
        val unrelated = JsonObject(missedTag + ("arguments" to json("""{"action":"detail","tag_name":"other"}""")))
        assertEquals(null, detail(listOf(listed, unrelated)))
        val byTag = json("""{"action":"detail","tag_name":"v1"}""")
        assertEquals(JsonPrimitive("get_release_by_tag"), dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.nativeArguments("github_release", byTag)?.get("action"))
        assertTrue(dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.errors("github_release", JsonObject(args + byTag)).isNotEmpty())
    }

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

    @Test fun watchCanBeExercisedWithAnObservedCommitWhenTheRepositoryHasNoRuns() {
        val head = "a".repeat(40)
        val watch = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.definition("github_watch_checks")
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$head"}}""")
        val recipe = requireNotNull(GitHubSelfTestReads.next(listOf(watch), listOf(status)))
        assertEquals(json("""{"all_checks":true,"commit_sha":"$head","wait_timeout_seconds":1}"""), recipe.arguments)
        assertEquals(null, GitHubSelfTestReads.next(listOf(watch), listOf(json("""{"trusted":false,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$head"}}"""))))
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
        // Even broad integration coverage does not authorize creating an
        // unrelated repository merely to satisfy a dependent status operation.
        assertEquals(JsonPrimitive(false), broad["required"])
        assertEquals(JsonPrimitive("CONDITIONAL"), broad["status"])
        assertEquals(JsonPrimitive("REPOSITORY_LIFECYCLE_REQUIRED"), broad["blocker_code"])
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

    @Test fun readSourceStatusAndEarlierReadsCannotDisplaceWorkspaceDependencies() {
        val sha = "a".repeat(40)
        val sourceSha = "b".repeat(40)
        val status = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val runs = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[{"id":10,"head_sha":"$sha","status":"completed","conclusion":"success"}]}}""")
        fun readSource(receipt: JsonObject) = JsonObject(
            receipt + mapOf("repository" to JsonPrimitive("other/repo"), "repository_role" to JsonPrimitive("READ_SOURCE"))
        )
        val foreignStatus = readSource(JsonObject(status + ("result" to json("""{"commit_sha":"$sourceSha"}"""))))
        val foreignRunRead = readSource(json("""{"binding":"task","trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"run","run_id":20},"result":{"id":20,"head_sha":"$sourceSha","status":"completed","conclusion":"success"}}"""))
        val next = requireNotNull(GitHubSelfTestReads.next(listOf(tool("github_ci", "run")), listOf(status, runs, foreignStatus, foreignRunRead)))
        assertEquals(json("""{"action":"run","run_id":10,"expected_head_sha":"$sha"}"""), next.arguments)
        assertEquals(null, GitHubSelfTestReads.next(listOf(tool("github_ci", "definitions")), listOf(foreignStatus)))
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
        // Query the exact revision without reusing the superseded failure's ID.
        assertEquals(json("""{"action":"failure","expected_head_sha":"$sha"}"""), GitHubSelfTestReads.next(listOf(tool("github_ci", "failure")), listOf(status, runs, newer))?.arguments)
    }

    @Test fun noFailedRunStillAllowsOneExactRevisionFailureQuery() {
        val sha = "a".repeat(40)
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val tools = listOf(tool("github_ci", "failure"))
        val call = requireNotNull(GitHubSelfTestReads.next(tools, listOf(status)))
        assertEquals(json("""{"action":"failure","expected_head_sha":"$sha"}"""), call.arguments)
        val observed = json("""{"trusted":true,"success":true,"dispatched":true,"evidence_origin":"LIVE","tool":"github_ci","arguments":${call.arguments},"result":{"status":"no_failed_run","head_sha":"$sha"}}""")
        assertEquals(null, GitHubSelfTestReads.next(tools, listOf(status, observed)))
        assertEquals(null, GitHubSelfTestReads.next(tools, emptyList()))
        assertEquals(null, GitHubSelfTestReads.next(listOf(tool("github_ci", "logs")), listOf(status, observed)))
        val required = tools + tool("github_ci", "logs")
        val receipts = mapOf("source" to status, "failure-query" to observed)
        val rows = GitHubTestChecklist.rows(receipts, required, "test all tools").filterIsInstance<JsonObject>()
        assertEquals(JsonPrimitive("PASSED"), rows.single { it["id"] == JsonPrimitive("github_ci:failure") }["status"])
        assertEquals(JsonPrimitive("BLOCKED"), rows.single { it["id"] == JsonPrimitive("github_ci:logs") }["status"])
        assertEquals(false, GitHubSelfTestFixtures.complete(receipts))
        assertEquals(false, GitHubTestChecklist.apiComplete("test all tools", receipts, required))
    }

    @Test fun successfulEmptyJobsQueryDoesNotSuppressTheNextBoundedDependencyRead() {
        val sha = "a".repeat(40)
        val status = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
        val runs = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[{"id":10,"head_sha":"$sha","status":"completed","conclusion":"success"},{"id":20,"head_sha":"$sha","status":"completed","conclusion":"success"}]}}""")
        val first = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"jobs","run_id":10,"expected_head_sha":"$sha"},"result":{"jobs":[]}}""")
        val tools = listOf(tool("github_ci", "jobs"), tool("github_ci", "logs"))
        val call = requireNotNull(GitHubSelfTestReads.next(tools, listOf(status, runs, first)))
        assertEquals(json("""{"action":"jobs","run_id":20,"expected_head_sha":"$sha"}"""), call.arguments)
        val next = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":${call.arguments},"result":{"jobs":[{"id":21,"run_id":20,"status":"completed","conclusion":"success"}]}}""")
        val logs = requireNotNull(GitHubSelfTestReads.next(tools, listOf(status, runs, first, next)))
        assertEquals(JsonPrimitive("logs"), logs.arguments["action"])
        assertEquals(JsonPrimitive(21), logs.arguments["job_id"])
        assertEquals(JsonPrimitive(20), logs.arguments["run_id"])
        assertEquals(JsonPrimitive(sha), logs.arguments["expected_head_sha"])
        val complete = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":${logs.arguments},"result":{"text":"executed job output"}}""")
        assertEquals(null, GitHubSelfTestReads.next(tools, listOf(status, runs, first, next, complete)))
        // Without a dependent job/log action, a jobs-only smoke test does not expand its sample.
        assertEquals(null, GitHubSelfTestReads.next(listOf(tool("github_ci", "jobs")), listOf(status, runs, first)))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", tools, mapOf("source" to status, "runs" to runs, "jobs" to first), remainingToolCalls = 2))
    }

    @Test fun historyPermissionDenialIsNotRetriedWithAnotherIssue() {
        val issues = json("""{"trusted":true,"success":true,"tool":"github_issue","arguments":{"action":"list"},"result":{"items":[{"number":2}]}}""")
        for (code in listOf("AUTH_REQUIRED", "PERMISSION_DENIED")) {
            val rejected = json("""{"trusted":true,"success":false,"dispatched":false,"tool":"github_issue","arguments":{"action":"history","issue_number":1},"error_code":"$code"}""")
            assertEquals(null, GitHubSelfTestReads.next(listOf(tool("github_issue", "history")), listOf(rejected, issues)))
        }
    }

    @Test fun readyReadMatrixCoversAllFourteenAndUnlocksObservedResources() {
        val head = "a".repeat(40)
        val status = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$head","branch":"main"}}""")
        val pr = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_pr","arguments":{"action":"list"},"result":{"pull_requests":[{"number":1534}]}}""")
        val requested = listOf(
            "github_context" to "index",
            "github_context" to "changes",
            "github_context" to "efficiency",
            "github_context" to "capabilities",
            "github_context" to "branch_dependencies",
            "github_context" to "commit_search",
            "github_context" to "recovery",
            "github_pr" to "comments",
            "github_pr" to "reviews",
            "github_pr" to "threads",
            "github_issue" to "list",
            "github_release" to "list",
            "github_release" to "tags",
            "github_repository" to "capabilities"
        )
        val dependencies = listOf(
            "github_issue" to "headers",
            "github_issue" to "context",
            "github_issue" to "comments",
            "github_issue" to "history",
            "github_release" to "status",
            "github_release" to "tag",
            "github_release" to "detail"
        )
        val definitions = (requested + dependencies).map { (name, action) -> tool(name, action) }
        fun actions(receipts: List<JsonObject>) = GitHubSelfTestReads.available(definitions, receipts).map { call ->
            call.name to (call.arguments["action"] as JsonPrimitive).content
        }.toSet()

        val ready = actions(listOf(status, pr))
        assertEquals(requested.toSet(), ready)
        val changes = GitHubSelfTestReads.available(definitions, listOf(status, pr))
            .single { it.name == "github_context" && it.arguments["action"] == JsonPrimitive("changes") }
        assertEquals(JsonPrimitive(head), changes.arguments["base_sha"])
        assertEquals(JsonPrimitive(head), changes.arguments["commit_sha"])
        val prReads = GitHubSelfTestReads.available(definitions, listOf(status, pr))
            .filter { it.name == "github_pr" }
        assertEquals(3, prReads.size)
        assertTrue(prReads.all { it.arguments["pull_number"] == JsonPrimitive(1534) })

        val issueList = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_issue","arguments":{"action":"list","state":"all"},"result":{"issues":[{"number":1584}]}}""")
        val tags = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_release","arguments":{"action":"tags"},"result":{"items":[{"name":"v1"}]}}""")
        val releases = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_release","arguments":{"action":"list"},"result":{"items":[{"tag_name":"v1"}]}}""")
        val unpublished = json("""{"trusted":true,"success":true,"tool":"github_release","arguments":{"action":"status"},"result":{"tag_name":"v1","published":false,"release":null}}""")
        val drafts = json("""{"trusted":true,"success":true,"tool":"github_release","arguments":{"action":"list"},"result":{"items":[{"tag_name":"v1","draft":true}]}}""")
        val tagOnly = actions(listOf(status, pr, issueList, tags, unpublished, drafts))
        assertTrue(("github_release" to "tag") in tagOnly)
        assertTrue(("github_release" to "detail") !in tagOnly)
        val unlocked = actions(listOf(status, pr, issueList, tags, releases))
        assertTrue(dependencies.all { it in unlocked })
        assertTrue(("github_issue" to "list") !in unlocked)
        assertTrue(("github_release" to "tags") !in unlocked)
        assertTrue(("github_release" to "list") !in unlocked)
    }
}
