package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAuditEvidenceTest {
    private val head = "a".repeat(40)
    private val base = "b".repeat(40)
    private val task = "Review the selected branch against its base."
    private val tools = listOf("github_context", "github_pr", "github_commit", "github_issue").map { GitHubOperationTool.definition(it) }
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun receipt(id: String, tool: String, arguments: JsonObject, result: JsonObject) = buildJsonObject {
        put("receipt_id", id)
        put("binding", "task")
        put("repository", "owner/repo")
        put("trusted", true)
        put("verification_eligible", true)
        put("success", true)
        put("dispatched", true)
        put("tool", tool)
        put("arguments", arguments)
        put("result", result)
    }
    private fun status(branch: String = "work") = receipt("status", "github_context", json("""{"action":"status"}"""), json("""{"branch":"$branch","commit_sha":"$head","repository":{"default_branch":"main"}}"""))
    private fun noPulls() = receipt("prs", "github_pr", json("""{"action":"list","state":"open","per_page":100}"""), json("""{"items":[],"page":1,"has_more":false}"""))

    private fun checkSource(): JsonObject = buildJsonObject {
        put("commit_sha", head)
        put("state", "RUNNING")
        put("has_more", false)
        put(
            "check_details",
            buildJsonObject {
                put("total_count", 18)
                put(
                    "check_runs",
                    JsonArray(
                        (1..18).map { index ->
                            buildJsonObject {
                                put("id", index)
                                put("name", if (index == 18) "Validate exact release commit" else "Check $index")
                                put("status", if (index == 18) "in_progress" else "completed")
                                put("conclusion", if (index == 18) JsonPrimitive(null as String?) else JsonPrimitive(if (index == 17) "skipped" else "success"))
                                put("html_url", "https://github.com/owner/repo/actions/runs/$index")
                                put("started_at", "2026-11-09T18:35:00Z")
                                put("completed_at", if (index == 18) JsonPrimitive(null as String?) else JsonPrimitive("2026-11-09T18:36:00Z"))
                                put(
                                    "app",
                                    buildJsonObject {
                                        put("id", 7)
                                        put("owner", buildJsonObject { put("avatar_url", "a".repeat(2000)) })
                                        put(
                                            "permissions",
                                            buildJsonObject {
                                                put("contents", "write")
                                                put("metadata", "x".repeat(2000))
                                            }
                                        )
                                    }
                                )
                                put(
                                    "output",
                                    buildJsonObject {
                                        put("title", "Check $index")
                                        put("summary", "log details ".repeat(1000))
                                    }
                                )
                                put("required", true) // Unqualified upstream metadata must not become policy evidence.
                            }
                        }
                    )
                )
            }
        )
        put("status_details", json("""{"total_count":0,"state":"pending","statuses":[]}"""))
    }

    @Test fun eighteenHeavyChecksFitOneFacadeResponseAndRetainFullOriginal() = runBlocking {
        val source = checkSource()
        var dispatches = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "fixture", json("{}"))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Json(source), false)
            }
        }
        val args = json("""{"action":"checks","commit_sha":"$head","response_detail":"compact"}""")
        val result = GitHubOperationTool("github_context", backend, "audit-checks").execute("checks", args)
        val envelope = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive(true), envelope["local_page_complete"])
        assertTrue(envelope.toString().encodeToByteArray().size.toString(), envelope.toString().encodeToByteArray().size <= 8192)
        assertEquals(18, ((envelope["data"] as JsonObject)["check_details"] as JsonObject)["check_runs"].let { it as JsonArray }.size)
        assertEquals(source, (result.retainedContent as ToolResultContent.Json).value)
        assertFalse(envelope.toString().contains("avatar_url"))
        assertFalse(envelope.toString().contains("\"required\":true"))
        assertEquals(JsonPrimitive(true), result.executionReceipt?.get("retained_original_complete"))
        assertEquals(1, dispatches)
        val summary = envelope["semantic_summary"] as JsonObject
        assertEquals(json("""{"successful":16,"skipped":1,"in_progress":1,"failed":0,"other_completed":0}"""), summary["check_counts"])
        val report = GitHubPartialReport.render(listOf(requireNotNull(GitHubPartialReport.observe(ProviderEvent.ToolCall("checks", "github_context", args), result))))
        assertTrue(report.contains("16 successful, 1 skipped, 1 in progress, 0 failed"))
        assertTrue(report.contains(head))
    }

    @Test fun normalReviewHasOnlyRequestedAcceptanceCriteriaAndKeepsCatalogSeparately() {
        val checkpoint = GitHubTestChecklist.checkpoint(task, emptyMap(), tools, 21)
        val capabilities = checkpoint["capabilities"] as JsonArray
        assertTrue(capabilities.isNotEmpty())
        assertTrue(capabilities.all { (it as JsonObject)["required"] == JsonPrimitive(false) })
        val ledger = checkpoint["acceptance_ledger"] as JsonObject
        assertTrue((ledger["capabilities"] as JsonArray).isEmpty())
        assertEquals(capabilities.size, (ledger["capability_inventory"] as JsonArray).size)
        assertTrue((ledger["requirements"] as JsonArray).isNotEmpty())
        assertTrue(((checkpoint["resume_plan"] as JsonObject)["pending_capabilities"] as JsonArray).isEmpty())
        val integration = GitHubTestChecklist.checkpoint("Test all authorized GitHub integration tools", emptyMap(), tools)
        assertTrue((integration["capabilities"] as JsonArray).any { (it as JsonObject)["required"] == JsonPrimitive(true) })
    }

    @Test fun invalidIssueHeadersOffersValidatedListRecoveryWithoutDispatch() = runBlocking {
        var dispatches = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "fixture", json("{}"))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Json(json("{}")), false)
            }
        }
        val result = GitHubOperationTool("github_issue", backend, "issue-recovery", modelName = "github_issue__selected").execute("headers", json("""{"action":"headers","state":"open"}"""))
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(0, dispatches)
        val correction = ((result.content as ToolResultContent.Json).value as JsonObject)["correction_call"] as JsonObject
        assertEquals(JsonPrimitive("github_issue__selected"), correction["tool"])
        assertEquals(json("""{"action":"list","state":"open"}"""), correction["arguments"])
        val precheck = ToolArgumentValidator.rejection("precheck", "github_issue__selected", json("""{"action":"headers","state":"open"}"""), GitHubOperationTool.definition("github_issue").inputSchema)
        assertEquals(correction, ((precheck.content as ToolResultContent.Json).value as JsonObject)["correction_call"])
    }

    @Test fun outOfOrderReceiptCountersCannotRestoreSpentCallsAndLiveLedgerWins() {
        val records = linkedMapOf("new" to json("""{"receipt_id":"new","tool_budget":{"used":43,"limit":64}}"""), "old" to json("""{"receipt_id":"old","tool_budget":{"used":11,"limit":64}}"""))
        assertEquals(21, GitHubChecklistBudget.remaining(records))
        assertEquals(9, GitHubChecklistBudget.remaining(records, 9))
        val checkpoint = GitHubChecklistBudget.checkpoint(records, 9)
        assertEquals(JsonPrimitive("LIVE_EXECUTION_LEDGER"), checkpoint["remaining_source"])
        assertEquals(JsonPrimitive(43), checkpoint["observed_used_calls"])
    }

    @Test fun missingProtectionDoesNotBecomeNoRequiredChecks() = runBlocking {
        val observed = GitHubInspection { path ->
            when {
                path.endsWith("/protection") -> error("Access denied")
                path.contains("/rules/branches/") -> JsonArray(emptyList())
                else -> json("""{"name":"main","protected":false}""")
            }
        }.execute("branch_context", "/repos/owner/repo", json("""{"branch":"main"}"""), 1)
        val policy = observed["policy"] as JsonObject
        assertEquals(JsonPrimitive("UNKNOWN"), policy["required_checks_state"])
        assertEquals(JsonPrimitive(false), policy["required_checks_complete"])
        assertEquals(JsonPrimitive(true), (observed["rules"] as JsonObject)["available"])
    }

    @Test fun nestedProtection404RetainsUnknownPolicyEvenWhenRulesReturnRequiredChecks() = runBlocking {
        val observed = GitHubInspection { path ->
            when {
                path.endsWith("/protection") -> throw GitHubRequestRejected(404, "Not found")
                path.contains("/rules/branches/") -> JsonArray(listOf(json("""{"type":"required_status_checks","parameters":{"required_status_checks":[{"context":"Required verification"}]}}""")))
                else -> json("""{"name":"main","protected":false}""")
            }
        }.execute("branch_context", "/repos/owner/repo", json("""{"branch":"main"}"""), 1)
        val policy = observed["policy"] as JsonObject
        assertEquals(JsonPrimitive(404), (observed["protection"] as JsonObject)["http_status"])
        assertEquals(JsonPrimitive("UNKNOWN"), policy["required_checks_state"])
        assertEquals(JsonPrimitive(false), policy["required_checks_complete"])
        assertEquals(JsonArray(listOf(JsonPrimitive("Required verification"))), policy["observed_required_checks"])
    }

    @Test fun runtimeQualificationIdentifiesExcludedCachedReceiptDespiteMatchingSha() {
        val runtime = json("""{"identity_source":"RUNNING_PROCESS_BUILD_CONFIG","application_id":"app","version_name":"1","version_code":1,"app_source_sha":"$head"}""")
        val cached = JsonObject(status() + mapOf("runtime_identity" to runtime, "evidence_origin" to JsonPrimitive("CACHED")))
        val binding = GitHubIntegrationAcceptance.binding(listOf(cached), runtime)
        assertEquals(JsonPrimitive(false), binding["verified"])
        assertEquals(JsonPrimitive("STATUS_RECEIPTS_EXCLUDED"), binding["reason_code"])
        assertTrue(binding.toString().contains("CURRENT_LIVE_READ_NOT_OBSERVED"))
        assertEquals(JsonPrimitive("NOT_TESTED"), binding["device_execution"])
    }

    @Test fun defaultBranchReviewRequiresBaselineAndDoesNotInventEmptyDiff() {
        val plan = requireNotNull(GitHubReviewComparison.plan(task, tools, linkedMapOf("status" to status("main"), "prs" to noPulls())))
        assertEquals(JsonPrimitive("BASELINE_REQUIRED"), plan.checkpoint["state"])
        assertEquals(JsonPrimitive(true), plan.checkpoint["user_input_required"])
        assertNull(plan.nextCall)
        assertFalse(plan.checkpoint.containsKey("base_sha"))
    }

    @Test fun quotedBranchCommandsDoNotTriggerComparisonAcquisition() {
        assertFalse(GitHubReviewComparison.matches("Review this log: \"then deploy the branch\""))
        assertFalse(GitHubReviewComparison.matches("Explain this command:\n```text\nReview the selected branch against its base.\n```"))
        assertFalse(GitHubReviewComparison.matches("Review this excerpt:\n> Review the selected branch against its base."))
        assertTrue(GitHubReviewComparison.matches(task))
    }

    @Test fun quotedHistoricalBaselinesCannotSelectTheReviewComparison() {
        val records = linkedMapOf("status" to status("main"), "prs" to noPulls())
        val excerpts = listOf(
            "\"Baseline: $base\"",
            "```text\nBaseline: $base\n```",
            "> Baseline: `$base`",
            "`Baseline: $base`"
        )
        excerpts.forEach { excerpt ->
            val plan = requireNotNull(GitHubReviewComparison.plan("$task\nHistorical excerpt:\n$excerpt", tools, records))
            assertEquals(excerpt, JsonPrimitive("BASELINE_REQUIRED"), plan.checkpoint["state"])
            assertFalse(excerpt, plan.checkpoint.containsKey("base_sha"))
            assertNull(excerpt, plan.nextCall)
        }
    }

    @Test fun explicitInlineShaBaselineSurvivesQuotedHistoricalInstructions() {
        val historicalBase = "c".repeat(40)
        val plan = requireNotNull(
            GitHubReviewComparison.plan(
                "$task\nHistorical excerpt: \"Baseline: $historicalBase\"\nBaseline: `$base`",
                tools,
                linkedMapOf("status" to status("main"))
            )
        )
        assertEquals(JsonPrimitive("EXPLICIT_BASE_SHA"), plan.checkpoint["comparison_policy"])
        assertEquals(JsonPrimitive(base), requireNotNull(plan.nextCall).arguments["base_sha"])
        assertEquals(JsonPrimitive(head), requireNotNull(plan.nextCall).arguments["commit_sha"])
    }

    @Test fun matchingPrPinsBothRevisionsAndCompletedComparisonIsReused() {
        val prs = receipt("prs", "github_pr", json("""{"action":"list"}"""), json("""{"items":[{"number":2,"head":{"sha":"$head"},"base":{"sha":"$base"}}],"has_more":false}"""))
        val records = linkedMapOf("status" to status(), "prs" to prs)
        val plan = requireNotNull(GitHubReviewComparison.plan(task, tools, records))
        val call = requireNotNull(plan.nextCall)
        assertEquals(JsonPrimitive(base), call.arguments["base_sha"])
        assertEquals(JsonPrimitive(head), call.arguments["commit_sha"])
        assertEquals(JsonPrimitive("OBSERVED_PR_BASE"), plan.checkpoint["comparison_policy"])
        records["changes"] = receipt("changes", "github_context", call.arguments, json("""{"base_sha":"$base","head_sha":"$head","files":[{"filename":"A.kt"}],"has_more":false}"""))
        val done = requireNotNull(GitHubReviewComparison.plan(task, tools, records))
        assertNull(done.nextCall)
        assertEquals(JsonPrimitive("READ_CHANGED_SOURCE"), done.checkpoint["state"])
        assertEquals(JsonPrimitive(false), done.checkpoint["source_review_verified"])
    }

    @Test fun verifiedSessionRequestsExactComparisonBeforeModelAndResumesAfterReceipt() = runBlocking {
        var modelRounds = 0
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                modelRounds++
                emit(ProviderEvent.Failed("Source inspection remains pending", errorCode = "FIXTURE_MODEL_STOP"))
            }
        }
        val book = GitHubEvidenceBook("task", "owner/repo", "work", tools.map { it.name }.toSet())
        suspend fun observe(id: String, tool: String, arguments: JsonObject, source: JsonObject) = book.observe(ProviderEvent.ToolCall(id, tool, arguments), AgentToolResult(id, ToolResultContent.Json(source), false))
        observe("status", "github_context", json("""{"action":"status"}"""), status()["result"] as JsonObject)
        observe("prs", "github_pr", json("""{"action":"list"}"""), json("""{"items":[{"head":{"sha":"$head"},"base":{"sha":"$base"}}],"has_more":false}"""))
        val session = GitHubVerifiedSession(delegate, book, task, { 8192 }, { error("No answer extraction expected") }, {}, {}, remainingToolCalls = { 21 })
        val before = session.streamRound(tools, emptyList()).toList()
        val compare = before.filterIsInstance<ProviderEvent.ToolCall>().single()
        assertEquals(JsonPrimitive("changes"), compare.arguments["action"])
        assertEquals(JsonPrimitive(base), compare.arguments["base_sha"])
        assertEquals(JsonPrimitive(head), compare.arguments["commit_sha"])
        assertEquals(0, modelRounds)
        observe(compare.callId, compare.name, compare.arguments, json("""{"base_sha":"$base","head_sha":"$head","files":[],"has_more":false}"""))
        val after = session.streamRound(tools, emptyList()).toList()
        assertEquals(1, modelRounds)
        assertTrue(after.none { it is ProviderEvent.ToolCall })
        assertEquals("FIXTURE_MODEL_STOP", after.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
    }

    @Test fun branchReviewCannotPassFromStatusFactsOrComparisonWithoutSource() {
        val request = "$task Baseline: $base"
        val claims = listOf(
            json("""{"status":"VERIFIED","kind":"fact","receipt_id":"source"}"""),
            json("""{"status":"INFERRED","kind":"recommendation","receipt_id":"source"}""")
        )
        val records = linkedMapOf("status" to status())
        fun complete() = GitHubRequestCompletion.evaluate(request, claims, json("{}"), records)["completion_verified"] == JsonPrimitive(true)
        assertFalse(complete())
        records["changes"] = receipt("changes", "github_context", json("""{"action":"changes","base_sha":"$base","commit_sha":"$head"}"""), json("""{"base_sha":"$base","head_sha":"$head","files_total":1,"files":[{"filename":"A.kt"}],"has_more":false}"""))
        assertFalse(complete())
        records["source"] = receipt("source", "github_read", json("""{"commit_sha":"$head","ranges":[{"path":"A.kt","start_line":1,"end_line":10}]}"""), json("""{"commit_sha":"$head","files":[{"path":"A.kt","content":"fun changed() = true"}]}"""))
        assertTrue(complete())
    }

    @Test fun proposalPreparationIsNeitherRemoteMutationNorCommitAcceptance() {
        val proposal = JsonObject(receipt("proposal", "github_commit", json("""{"stage_id":"stage","branch":"work","message":"fix","expected_head_sha":"$head"}"""), json("""{"status":"PROPOSAL_PREPARED","committed":false,"stage_id":"stage"}""")) + mapOf("dispatched" to JsonPrimitive(false), "execution" to json("""{"read_requests":0,"mutation_requests":0,"accepted_mutations":0,"phase":"LOCAL_OR_REPLAYED"}""")))
        val outcome = GitHubOperationOutcome.snapshot(proposal)
        assertEquals(JsonPrimitive(true), outcome["proposal_prepared"])
        assertEquals(JsonPrimitive(false), outcome["remote_dispatched"])
        assertEquals(JsonPrimitive(false), outcome["remote_outcome_verified"])
        assertEquals(GitHubExecutionLedger.ReceiptState.PROPOSAL_PREPARED, GitHubExecutionLedger.receiptState(proposal))
        assertEquals(GitHubExecutionLedger.ObservationKind.PROPOSAL_PREPARED, GitHubExecutionLedger.observationKind(proposal))
        val rows = GitHubTestChecklist.rows(mapOf("proposal" to proposal), tools, "Test all GitHub tools")
        val commit = rows.filterIsInstance<JsonObject>().first { it["id"] == JsonPrimitive("github_commit:") }
        assertNotEquals(JsonPrimitive("PASS"), commit["acceptance_state"])
    }

    @Test fun receiptHashesIdentifyOriginalAndProjectionSeparately() = runBlocking {
        val saved = linkedMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("task", "owner/repo", "main", setOf("github_context"), save = { id, value -> saved[id] = value })
        val source = json("""{"commit_sha":"$head","metadata":"original"}""")
        val view = json("""{"semantic_summary":{"commit_sha":"$head"}}""")
        val call = ProviderEvent.ToolCall("hash", "github_context", json("""{"action":"status"}"""), "original-fingerprint")
        book.observe(call, AgentToolResult("hash", ToolResultContent.Json(view), false, retainedContent = ToolResultContent.Json(source)))
        val receipt = book.snapshot().first.values.single()
        assertEquals(JsonPrimitive("original-fingerprint"), receipt["original_invocation_fingerprint"])
        assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(source.toString())), receipt["source_result_sha256"])
        assertNotEquals(receipt["source_result_sha256"], receipt["projection_sha256"])
        assertEquals(JsonPrimitive("IMMUTABLE_SOURCE_RESULT"), receipt["hash_scope"])
        assertNotNull(saved.values.singleOrNull { it["call_id"] == JsonPrimitive("hash") })
    }
}
