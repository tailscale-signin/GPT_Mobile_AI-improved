package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolRoundAdmission
import dev.chungjungsoo.gptmobile.data.agent.captureProviderInvocation
import dev.chungjungsoo.gptmobile.data.agent.planToolBatch
import dev.chungjungsoo.gptmobile.data.agent.providerInvocation
import dev.chungjungsoo.gptmobile.data.agent.toolRepeatKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOperationToolTest {
    @Test fun selectorlessWatchOffersScopedPinnedRunDiscoveryWithoutSubscribing() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("An unselected watch must not dispatch")
        }
        val tool = GitHubOperationTool("github_watch_checks", backend, "watch-discovery", "github_watch_checks__selected")
        val arguments = buildJsonObject {
            put("owner", "owner")
            put("repo", "repo")
            put("commit_sha", "a".repeat(40))
            put("wait_timeout_seconds", 10)
            put("all_checks", false)
        }
        // Production validates the selected schema in the budget wrapper first.
        val result = dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget(
            dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits(maxToolCalls = 4)
        ).bind(tool).execute("watch", arguments)
        assertTrue(result.isError)
        assertFalse(result.dispatched)
        val recipe = (result.content as ToolResultContent.Json).value.jsonObject.getValue("recovery_call").jsonObject
        assertEquals(JsonPrimitive("github_ci__selected"), recipe["tool"])
        val discovery = recipe.getValue("arguments").jsonObject
        assertEquals(JsonPrimitive("runs"), discovery["action"])
        assertEquals(arguments["commit_sha"], discovery["commit_sha"])
        assertEquals(arguments["owner"], discovery["owner"])
        assertEquals(arguments["repo"], discovery["repo"])
        assertEquals(JsonPrimitive(100), discovery["per_page"])
        assertTrue(ToolArgumentValidator.errors(discovery, GitHubOperationTool.definition("github_ci").inputSchema).isEmpty())
        assertFalse("run_id" in discovery || "job_id" in discovery || "wait_timeout_seconds" in discovery)
        val ambiguous = tool.execute("ambiguous-watch", JsonObject(arguments + ("run_id" to JsonPrimitive(-1))))
        assertFalse((ambiguous.content as ToolResultContent.Json).value.jsonObject.containsKey("recovery_call"))
    }

    @Test fun successfulPathSearchOffersTheObservedFilesAtTheSameCommitAndConnection() = runBlocking {
        val head = "a".repeat(40)
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("commit_sha", head)
                        put(
                            "items",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("path", "AGENTS.md")
                                        put("sha", "b".repeat(40))
                                    }
                                )
                            )
                        )
                    }
                ),
                false
            )
        }
        val tool = GitHubOperationTool("github_search", backend, "source-progression", "github_search__selected")
        val result = tool.execute("paths", args("""{"owner":"owner","repo":"repo","mode":"paths","query":"*.md"}"""))
        val body = dev.chungjungsoo.gptmobile.data.github.GitHubSemanticSummary.data((result.content as ToolResultContent.Json).value.jsonObject)
        val recipe = body.getValue("source_read_call").jsonObject
        assertEquals(JsonPrimitive("github_read__selected"), recipe["tool"])
        val read = recipe.getValue("arguments").jsonObject
        assertEquals(JsonPrimitive(head), read["commit_sha"])
        assertEquals(JsonPrimitive("AGENTS.md"), read.getValue("ranges").jsonArray.single().jsonObject["path"])
        assertTrue(ToolArgumentValidator.errors(read, GitHubOperationTool.definition("github_read").inputSchema).isEmpty())
    }

    @Test fun fuzzyPhraseRejectionOffersAnExactLiteralCorrectionWithoutRewritingTheQuery() = runBlocking {
        val observed = mutableListOf<JsonObject>()
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                observed += arguments
                return if (arguments["fuzzy_match"] == JsonPrimitive(true)) {
                    AgentToolResult(callId, ToolResultContent.Text("Fuzzy search accepts one identifier."), true, dispatched = false, errorCode = "INVALID_ARGUMENTS")
                } else {
                    AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("matches_complete", true) }), false)
                }
            }
        }
        val tool = GitHubOperationTool("github_search", backend, "fuzzy-query", "github_search__selected")
        val original = args("""{"query":"main menu","mode":"literal","path_prefix":"app/","fuzzy_match":true,"fuzzy_threshold":0.8}""")
        val rejected = tool.execute("invalid", original)
        assertEquals(JsonPrimitive("GITHUB_SEARCH_FUZZY_QUERY_INVALID"), rejected.executionReceipt?.get("reason_code"))
        assertFalse(rejected.dispatched)
        val correction = (rejected.content as ToolResultContent.Json).value.jsonObject.getValue("correction_call").jsonObject
        assertEquals(JsonPrimitive("github_search__selected"), correction["tool"])
        val corrected = correction.getValue("arguments").jsonObject
        assertEquals(JsonObject((original - "fuzzy_threshold") + ("fuzzy_match" to JsonPrimitive(false))), corrected)
        assertFalse(tool.execute("corrected", corrected).isError)
        assertEquals(JsonPrimitive("main menu"), observed.last()["query"])
        val advanced = tool.execute("advanced", JsonObject(original + ("index_cursor" to JsonPrimitive(10))))
        assertFalse((advanced.content as ToolResultContent.Json).value.jsonObject.containsKey("correction_call"))
    }

    @Test fun noncanonicalContinuationHandlesReturnSchemaAndFieldDiagnosticsBeforeDispatch() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Noncanonical handles must not dispatch")
        }
        for (handle in listOf("cursor", "next_cursor", "tracking_id", "evidence_id")) {
            val tool = GitHubOperationTool("github_issue", backend, "handle-diagnostics-$handle")
            val result = tool.execute("bad-handle", buildJsonObject { put(handle, "private-handle") })
            assertTrue(result.isError)
            assertFalse(result.dispatched)
            val receipt = requireNotNull(result.executionReceipt)
            assertEquals(JsonPrimitive("SAVED_VIEW_CURSOR_MALFORMED"), receipt["reason_code"])
            assertEquals(JsonArray(listOf(JsonPrimitive(handle))), receipt["validation_field_names"])
            assertEquals(JsonPrimitive(1), receipt["invalid_field_count"])
            assertTrue(receipt.containsKey("tool_schema_hash"))
        }
    }

    @Test fun incompleteRepositorySelectorProducesTypedPreDispatchEvidence() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult =
                error("Invalid pre-dispatch GitHub arguments must never reach the backend")
        }
        val tool = GitHubOperationTool("github_search", backend, "precheck-evidence")
        val failure = tool.execute(
            "missing-repository-selector",
            args("""{"action":"search","query":"checkpoint","owner":"example"}""")
        )
        assertTrue(failure.isError)
        assertFalse(failure.dispatched)
        assertEquals("INVALID_ARGUMENTS", failure.errorCode)
        assertEquals(
            JsonPrimitive("GITHUB_REPOSITORY_SELECTOR_INCOMPLETE"),
            failure.executionReceipt?.get("reason_code")
        )
        assertEquals(JsonPrimitive("PREFLIGHT_REJECTED"), failure.executionReceipt?.get("phase"))
        assertEquals(
            JsonPrimitive("github.argument-rejection.v1"),
            failure.executionReceipt?.get("diagnostic_contract")
        )
    }

    @Test fun implicitPrListDoesNotReplaceExplicitSinglePrReads() = runBlocking {
        val observed = mutableListOf<JsonObject>()
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                observed += arguments
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject {}), false)
            }
        }
        val tool = GitHubOperationTool("github_pr", backend, "diagnostic-contract", "github_pr__github_api")
        valid("github_pr", args("{}"))
        valid("github_pr", args("""{"state":"open","page":2}"""))
        assertFalse(tool.execute("list", args("{}")).isError)
        assertFalse(tool.execute("context", args("""{"pull_number":17}""")).isError)
        assertTrue(tool.execute("headers", args("""{"action":"headers"}""")).isError)
        assertTrue(tool.execute("mutation", args("""{"action":"merge"}""")).isError)
        assertEquals(listOf(JsonPrimitive("list_pull_requests"), JsonPrimitive("pr_context")), observed.map { it["action"] })
    }

    @Test fun copilotAgentMergeActionsAreAdvertisedAndValidatedWithoutAutoMerge() = runBlocking {
        val actions = mapOf(
            "agent_merge_status" to "agent_merge_status",
            "agent_merge_enable" to "agent_merge_enable",
            "agent_merge_update" to "agent_merge_update",
            "agent_merge_disable" to "agent_merge_disable"
        )
        val checked = "a".repeat(40)
        for ((name, native) in actions) {
            val request = buildJsonObject {
                put("action", name)
                put("pull_number", 1534)
                if (name != "agent_merge_status") put("expected_head_sha", checked)
                if (name == "agent_merge_update") put("allowed_actions", JsonArray(listOf(JsonPrimitive("fix_ci"))))
            }
            valid("github_pr", request)
            assertEquals(JsonPrimitive(native), GitHubOperationTool.nativeArguments("github_pr", request)?.get("action"))
        }
        val tool = GitHubOperationTool(
            "github_pr",
            object : AgentTool {
                override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult =
                    AgentToolResult(callId, ToolResultContent.Json(buildJsonObject {}), false)
            },
            "copilot-validation"
        )
        val unsafe = listOf(
            """{"action":"agent_merge_enable","pull_number":1534}""",
            """{"action":"agent_merge_update","pull_number":1534,"expected_head_sha":"$checked"}""",
            """{"action":"agent_merge_disable","pull_number":1534,"expected_head_sha":"$checked","allowed_actions":["fix_ci"]}""",
            """{"action":"agent_merge_enable","pull_number":1534,"expected_head_sha":"$checked","allowed_actions":["auto_merge"]}""",
            """{"action":"agent_merge_status","pull_number":1534,"allowed_actions":["fix_ci"]}"""
        )
        for (input in unsafe) {
            val denied = tool.execute("invalid-copilot", args(input))
            assertTrue(input, denied.isError)
            assertFalse(input, denied.dispatched)
        }
    }

    @Test fun prReviewsDispatchesThroughAnAdvertisedNativeReadWithZeroRows() = runBlocking {
        assertTrue("pull_request_reviews" in dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient.readActions)
        val observed = mutableListOf<JsonObject>()
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                observed += arguments
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(Json.parseToJsonElement("""{"reviews":[],"has_more":false,"returned":0}""").jsonObject),
                    false
                )
            }
        }
        val pr = GitHubOperationTool("github_pr", backend, "review-read")
        val request = args("""{"action":"reviews","pull_number":1534}""")
        valid("github_pr", request)
        val result = pr.execute("no-reviews", request)
        assertFalse(result.toString(), result.isError)
        assertEquals(JsonPrimitive("pull_request_reviews"), observed.single()["action"])
        val progress = result.executionReceipt!!.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(0), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(true), progress["complete"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), progress["acceptance_status"])
    }

    @Test fun prPageSizeAndReadOnlySearchAliasesReachTheirAdvertisedBackend() = runBlocking {
        val observed = mutableListOf<JsonObject>()
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                observed += arguments
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("ok", true) }), false)
            }
        }
        val pr = GitHubOperationTool("github_pr", backend, "diagnostic-contract")
        val input = args("""{"action":"list","per_page":50,"page":2}""")
        valid("github_pr", input)
        assertFalse(pr.execute("list", input).isError)
        assertEquals(JsonPrimitive(50), observed.last()["per_page"])
        assertEquals(JsonPrimitive("list_pull_requests"), observed.last()["action"])
        val search = GitHubOperationTool("github_search", backend, "diagnostic-contract")
        for (action in listOf("search", "search_repository", "search_code")) {
            val query = args("""{"action":"$action","query":"checkpoint","commit_sha":"$sha"}""")
            valid("github_search", query)
            assertFalse(search.execute(action, query).isError)
            assertEquals(JsonPrimitive("search_repository"), observed.last()["action"])
        }
        val before = observed.size
        assertTrue(search.execute("unsafe", args("""{"action":"delete","query":"checkpoint"}""")).isError)
        assertEquals(before, observed.size)
        // Direct native dispatch also performs the bounded first-page search repair;
        // agent-level correction alone did not cover the build-142 precheck reports.
        val repaired = search.execute("page-size", args("""{"query":"checkpoint","commit_sha":"$sha","per_page":50}"""))
        assertFalse(repaired.isError)
        assertEquals(before + 1, observed.size)
        assertEquals(JsonPrimitive("search_repository"), observed.last()["action"])
        assertFalse("per_page" in observed.last())
        for (input in listOf(
            """{"query":"checkpoint","per_page":50,"page":2}""",
            """{"query":"checkpoint","per_page":50,"view_cursor":"unissued"}""",
            """{"query":"checkpoint","per_page":50,"index_cursor":2}""",
            """{"query":"checkpoint","per_page":50,"unknown":"unsafe"}"""
        )) {
            val rejected = search.execute("unsafe-page-size", args(input))
            assertTrue(rejected.isError)
            assertFalse(rejected.dispatched)
            assertEquals(before + 1, observed.size)
        }
    }

    @Test fun exactBuild142ZeroBlobSearchReachesNativeBackendWithSafeBound() = runBlocking {
        val observed = mutableListOf<JsonObject>()
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                observed += arguments
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("ok", true) }), false)
            }
        }
        val search = GitHubOperationTool("github_search", backend, "build142-zero-blobs")
        val original = listOf(
            """{"action":"search","mode":"paths","query":"*","index_cursor":0,"batch_size":1000,"max_new_blobs":0}""",
            """{"action":"search","mode":"paths","query":"*","batch_size":1000,"max_new_blobs":0}"""
        )
        original.forEachIndexed { index, json ->
            val result = search.execute("original-call-$index", args(json))
            assertFalse(result.toString(), result.isError)
            assertEquals(JsonPrimitive(1), observed.last()["max_new_blobs"])
            assertEquals(JsonPrimitive(1000), observed.last()["batch_size"])
            assertEquals(JsonPrimitive("search_repository"), observed.last()["action"])
        }
        assertEquals(JsonPrimitive(0), observed.first()["index_cursor"])
        assertFalse("index_cursor" in observed.last())

        val before = observed.size
        for (json in listOf(
            """{"action":"search","mode":"paths","query":"*","index_cursor":2000,"max_new_blobs":0}""",
            """{"action":"search","mode":"paths","query":"*","view_cursor":"unissued","max_new_blobs":0}""",
            """{"action":"search","mode":"paths","query":"*","max_new_blobs":0,"unknown":"unsafe"}"""
        )) {
            val rejected = search.execute("no-unsafe-fix", args(json))
            assertTrue(rejected.isError)
            assertFalse(rejected.dispatched)
            assertEquals(before, observed.size)
        }
    }

    @Test fun comparisonGenerationComesFromLocalAssessmentAndNotBackendPayloadOrReceipt() = runBlocking {
        val forged = dev.chungjungsoo.gptmobile.data.github.GitHubComparisonPage.newGeneration(false)
        var numbered = false
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("base_sha", "a".repeat(40))
                        put("head_sha", "b".repeat(40))
                        put("pagination_generation", forged.evidence())
                        if (numbered) {
                            put("page", 1)
                            put("effective_per_page", 100)
                            put("files_total", 1)
                            put("has_more", false)
                            put("files", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("filename", "source.kt") })))
                        }
                    }
                ),
                false,
                executionReceipt = buildJsonObject { put("pagination_generation", forged.evidence()) }
            )
        }
        val tool = GitHubOperationTool("github_context", backend, "comparison-generation-origin")
        val request = buildJsonObject {
            put("action", "changes")
            put("base_sha", "a".repeat(40))
            put("commit_sha", "b".repeat(40))
            put("verify_live", true)
        }
        val unassessed = tool.execute("unassessed", request)
        assertFalse(unassessed.toString(), unassessed.isError)
        assertEquals(null, dev.chungjungsoo.gptmobile.data.github.GitHubComparisonPage.generation(unassessed.executionReceipt))
        numbered = true
        val assessed = tool.execute("assessed", request)
        assertFalse(assessed.toString(), assessed.isError)
        val generation = requireNotNull(dev.chungjungsoo.gptmobile.data.github.GitHubComparisonPage.generation(assessed.executionReceipt))
        assertTrue(generation.startsLiveRefresh)
        assertTrue(generation != forged)
    }

    @Test fun selectedIssueListAcceptsAndForwardsTheIssuedCursor() = runBlocking {
        var forwarded: JsonObject? = null
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                forwarded = arguments
                val page = (arguments["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("has_more", page == 1)
                            put("total_count", 2)
                            put("items", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("id", page) })))
                            if (page == 1) {
                                put(
                                    "continuation",
                                    buildJsonObject {
                                        put("action", "list_issues")
                                        put("state", "open")
                                        put("page", 2)
                                        put("per_page", 20)
                                        put("sort", "created")
                                        put("direction", "asc")
                                        put("after", "issued-opaque-cursor")
                                    }
                                )
                            }
                        }
                    ),
                    false
                )
            }
        }
        val tool = GitHubOperationTool("github_issue", backend, "continued-issues", "github_issue__selected")
        val selected = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.GitHubActionSchema.project(tool.definition, "list"))
        val first = tool.execute("first", args("""{"action":"list","state":"open","per_page":20}"""))
        assertFalse(first.toString(), first.isError)
        val request = (first.content as ToolResultContent.Json).value.jsonObject.getValue("continuation_call")
            .jsonObject.getValue("arguments").jsonObject
        assertEquals(JsonPrimitive("issued-opaque-cursor"), request["after"])
        val budget = dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget(dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits(maxToolCalls = 1))
        val second = budget.bindPresented(tool, selected).execute("continued", request)
        assertFalse(second.toString(), second.isError)
        assertEquals(JsonObject(request + ("action" to JsonPrimitive("list_issues"))), forwarded)
        assertEquals(JsonPrimitive(true), second.executionReceipt?.get("pagination_progress")?.jsonObject?.get("complete"))
    }

    @Test fun rejectedHandWrittenIssuePageExposesItsMissingContinuationContract() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId,
                ToolResultContent.Text("Follow the exact issue continuation"),
                true,
                errorCode = "INVALID_ARGUMENTS",
                dispatched = false,
                executionReceipt = buildJsonObject {
                    put("issue_continuation_rejected", true)
                    put("missing_field_count", 1)
                }
            )
        }
        val tool = GitHubOperationTool("github_issue", backend, "build142")
        val result = tool.execute("page-two", args("""{"action":"list","page":2,"per_page":20,"state":"open"}"""))
        assertFalse(result.dispatched)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        val body = (result.content as ToolResultContent.Json).value.jsonObject
        assertTrue(body.getValue("data").jsonObject.getValue("message").jsonPrimitive.content.contains("exact continuation_call"))
        assertEquals(JsonPrimitive(1), result.executionReceipt?.get("missing_field_count"))
        assertEquals(JsonPrimitive("TOOL_ARGUMENT_CONSTRAINT_REJECTED"), result.executionReceipt?.get("reason_code"))
    }

    @Test fun argumentRepairCannotShrinkAnOversizedNativeEnvelopePastAdmission() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Oversized original arguments must never dispatch")
        }
        val search = GitHubOperationTool("github_search", backend, "oversized")
        val budget = dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget(dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits(maxToolCalls = 4))
        val result = budget.bind(search).execute(
            "oversized",
            buildJsonObject {
                put("query", "checkpoint")
                put("per_page", "x".repeat(20_000))
            }
        )
        assertEquals("TOOL_BATCH_OVERSIZE", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(0, budget.usedCalls())
    }

    @Test fun nativeSourceBatchCannotSpendTheVerificationReserve() = runBlocking {
        var dispatches = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("ok", true) }), false)
            }
        }
        val read = GitHubOperationTool("github_read", backend, "reserve")
        val budget = dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget(dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits(maxToolCalls = 4, githubTask = true))
        val bound = budget.bind(read)
        val results = coroutineScope {
            (0..7).map { index ->
                async { bound.execute("read-$index", args("""{"path":"AGENTS.md","start_line":1,"end_line":250,"response_detail":"full"}""")) }
            }.awaitAll()
        }
        assertEquals(2, results.count { !it.isError })
        assertEquals(6, results.count { it.errorCode == "VERIFICATION_RESERVE_REACHED" && !it.dispatched })
        assertEquals(2, dispatches)
        assertEquals(2, budget.usedCalls())
    }

    @Test fun nativeAndRestoredBatchReadsNormalizeBeforeDispatchAndKeepCallId() = runBlocking {
        val observed = mutableListOf<Pair<String, JsonObject>>()
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                observed += callId to arguments
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("ok", true) }), false)
            }
        }
        val read = GitHubOperationTool("github_read", backend, "build142", "github_read__selected")
        val raw = args("""{"path":"AGENTS.md","start_line":1,"end_line":250,"response_detail":"full"}""")
        val checkpoint = dev.chungjungsoo.gptmobile.data.agent.ToolBatchCheckpoint(null, "build142")
        val original = ProviderEvent.ToolCall("restored-read", read.definition.name, raw).captureProviderInvocation()
        checkpoint.enqueue(listOf(original))
        val restored = checkpoint.recoverySnapshot().pending.single()
        var authorized: JsonObject? = null
        val budget = dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget(dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits(maxToolCalls = 4))
        val bound = budget.bind(read, authorize = { _, arguments ->
            authorized = arguments
            true
        })
        val result = bound.execute(restored.callId, restored.arguments)
        assertFalse(result.isError)
        assertEquals("restored-read", result.callId)
        assertEquals(JsonPrimitive("ranges"), authorized?.get("action"))
        assertEquals(1, budget.usedCalls())
        assertEquals(authorized, result.executionReceipt?.get("canonical_arguments"))
        assertEquals(original.providerInvocation(), restored.providerInvocation())
        assertEquals(JsonPrimitive("read_ranges"), observed.single().second["action"])
        assertEquals(raw["response_detail"], authorized?.get("response_detail"))
        assertEquals(args("""{"path":"AGENTS.md","start_line":1,"end_line":250}"""), observed.single().second["ranges"]!!.jsonArray.single())
        for (invalid in listOf(
            """{"path":"../secret","response_detail":"full"}""",
            """{"path":"AGENTS.md","response_detail":"invalid"}""",
            """{"path":"AGENTS.md","view_cursor":"unissued:1"}""",
            """{"path":"AGENTS.md","ranges":[{"path":"other"}]}"""
        )) {
            val rejected = read.execute("bad", args(invalid))
            assertTrue(rejected.isError)
            assertFalse(rejected.dispatched)
        }
        val search = GitHubOperationTool("github_search", backend, "build142")
        assertFalse(search.execute("search", args("""{"query":"checkpoint","per_page":50,"response_detail":"full"}""")).isError)
        assertEquals(2, observed.size)
    }

    @Test fun structuredReadValidationPreservesPinnedCandidatesAndUsesTheActiveToolRoute() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                val entries = mapOf("README.md" to args("""{"path":"README.md","sha":"$sha","type":"blob","mode":"100644"}"""))
                val failure = runCatching {
                    dev.chungjungsoo.gptmobile.data.github.GitHubPinnedPathResolver.resolve("docs/README.md", entries, sha, true)
                }.exceptionOrNull() as dev.chungjungsoo.gptmobile.data.github.GitHubReadValidation
                return AgentToolResult(callId, ToolResultContent.Json(failure.details), true, errorCode = failure.code, dispatched = false)
            }
        }
        val tool = GitHubOperationTool("github_read", backend, "pinned-recovery", modelName = "github_read__api")
        val observed = tool.execute("read", args("""{"commit_sha":"$sha","ranges":[{"path":"docs/README.md","start_line":1,"end_line":20}]}"""))
        assertTrue(observed.isError)
        assertFalse(observed.dispatched)
        assertEquals("GITHUB_PATH_NOT_FOUND", observed.errorCode)
        val details = result(observed).getValue("data").jsonObject
        assertEquals(JsonPrimitive("README.md"), details.getValue("candidates").jsonArray.single().jsonObject["path"])
        val correction = details.getValue("corrective_actions").jsonArray.first().jsonObject
        assertEquals(JsonPrimitive("github_search__api"), correction["tool"])
        valid("github_search", correction.getValue("arguments").jsonObject)
    }

    @Test fun unavailableSourceHandleOffersOnlyTheExplicitlyPinnedRanges() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) =
                AgentToolResult(callId, ToolResultContent.Text("unknown handle"), true, errorCode = "EVIDENCE_UNAVAILABLE", dispatched = false)
        }
        val tool = GitHubOperationTool("github_read", backend, "source-handles", modelName = "github_read__api")
        val ranges = args("""{"action":"ranges","owner":"owner","repo":"repo","commit_sha":"$sha","ranges":[{"path":"src/Chat.kt","start_line":5,"end_line":9}]}""")
        val saved = args("""{"evidence_id":"unavailable"}""")
        val failed = tool.execute("pinned", JsonObject(ranges + saved))
        val next = result(failed).getValue("data").jsonObject.getValue("correction_call").jsonObject
        assertEquals(JsonPrimitive("github_read__api"), next["tool"])
        assertEquals(ranges, next["arguments"])
        assertEquals(next, result(failed)["correction_call"])
        val handleOnly = tool.execute("handle-only", saved)
        val recovery = result(handleOnly).getValue("correction_call").jsonObject
        assertFalse("correction_call" in result(handleOnly).getValue("data").jsonObject)
        assertEquals(JsonPrimitive("github_context__api"), recovery["tool"])
        assertEquals(args("""{"action":"recovery"}"""), recovery["arguments"])
        val compact = dev.chungjungsoo.gptmobile.data.agent.GitHubReplayRecovery.compact(handleOnly.content, 2048)
        val replay = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.GitHubReplayRecovery.envelope(requireNotNull(compact)))
        assertEquals(recovery, replay["correction_call"])
        assertFalse("continuation_call" in replay)
    }

    @Test fun missingPinnedPathProvidesDiscoveryInsteadOfAnotherGuessedRead() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId,
                ToolResultContent.Text("Path is not in the pinned tree: .github/workflows/run-tests.yml"),
                true,
                errorCode = "INVALID_ARGUMENTS",
                dispatched = false
            )
        }
        val tool = GitHubOperationTool("github_read", backend, "paths", modelName = "github_read__api")
        val observed = tool.execute("read", args("""{"commit_sha":"$sha","ranges":[{"path":".github/workflows/run-tests.yml","start_line":1,"end_line":20}]}"""))
        assertTrue(observed.isError)
        assertFalse(observed.dispatched)
        val correction = result(observed)["data"]!!.jsonObject["correction_call"]!!.jsonObject
        assertEquals(JsonPrimitive("github_search__api"), correction["tool"])
        val arguments = correction["arguments"]!!.jsonObject
        assertEquals(JsonPrimitive(".github/workflows"), arguments["path_prefix"])
        assertEquals(JsonPrimitive(sha), arguments["commit_sha"])
        valid("github_search", arguments)
        assertTrue(observed.retainedContent.toString().contains("run-tests.yml"))
    }

    @Test fun missingPinnedPathCorrectionTargetsTheRejectedRangeInABatch() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId,
                ToolResultContent.Text("Path is not in the pinned tree: .github/workflows/run-tests.yml"),
                true,
                errorCode = "INVALID_ARGUMENTS",
                dispatched = false
            )
        }
        val tool = GitHubOperationTool("github_read", backend, "paths", modelName = "github_read__api")
        val observed = tool.execute(
            "read",
            args("""{"commit_sha":"$sha","ranges":[{"path":"README.md","start_line":1,"end_line":20},{"path":".github/workflows/run-tests.yml","start_line":1,"end_line":20}]}""")
        )
        val arguments = result(observed)["data"]!!.jsonObject["correction_call"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals(JsonPrimitive(".github/workflows"), arguments["path_prefix"])
        assertEquals(JsonPrimitive(sha), arguments["commit_sha"])
    }

    @Test fun compactResultsRetainOriginalEvidenceAndOfferFullDetail() = runBlocking {
        val backend = Backend().apply {
            body = args("""{"state":"PASSED","checks":1,"check_details":{"check_runs":[{"name":"unit","conclusion":"success","app":{"permissions":{"contents":"write"}}}]}}""")
        }
        val tool = GitHubOperationTool("github_context", backend, "projection")
        val compact = tool.execute("compact", args("""{"action":"checks","commit_sha":"$sha"}"""))
        assertFalse(compact.content.toString().contains("permissions"))
        assertTrue(compact.retainedContent.toString().contains("permissions"))
        val full = tool.execute("full", args("""{"action":"checks","commit_sha":"$sha","response_detail":"full"}"""))
        assertTrue(full.content.toString().contains("permissions"))
        assertFalse(backend.calls.last().containsKey("response_detail"))
    }

    @Test fun searchSavedPagesAcceptOnlyUnchangedSelectorsWithoutDispatch() = runBlocking {
        val backend = Backend().also { it.body = JsonPrimitive("search evidence ".repeat(2000)) }
        val tool = GitHubOperationTool("github_search", backend, "search-context", views = GitHubResultViews(pageBytes = 2048))
        val request = args("""{"owner":"owner","repo":"repo","commit_sha":"$sha","query":"Target","mode":"definitions","path_prefix":"src/","index_cursor":0,"batch_size":100,"context_lines":4,"fuzzy_match":false}""")
        var cursor = continuation(result(tool.execute("first", request)))
        var pages = 0
        while ("view_cursor" in cursor) {
            val saved = tool.execute("page-${pages++}", JsonObject(cursor + request))
            assertFalse(saved.isError)
            assertFalse(saved.dispatched)
            assertTrue(saved.sharedResult)
            assertTrue(pages < 200)
            val page = result(saved)
            if (page["local_page_complete"] == JsonPrimitive(true)) break
            cursor = continuation(page)
        }
        assertTrue(pages > 1)
        for ((field, value) in listOf("query" to JsonPrimitive("Other"), "index_cursor" to JsonPrimitive(1), "context_lines" to JsonPrimitive(3), "mode" to JsonPrimitive("paths"), "unexpected" to JsonPrimitive(true))) {
            val rejected = tool.execute("changed-$field", JsonObject(cursor + request + (field to value)))
            assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
            assertFalse(rejected.dispatched)
        }
        assertEquals(1, backend.calls.size)
    }

    @Test fun savedReadRangesCannotBeChangedOrUsedToRepeatAWrite() = runBlocking {
        val backend = Backend().also { it.body = JsonPrimitive("evidence ".repeat(2000)) }
        val tool = GitHubOperationTool("github_read", backend, "range-context", views = GitHubResultViews(pageBytes = 2048))
        val request = args("""{"ranges":[{"path":"A.kt","start_line":1,"end_line":3}]}""")
        val cursor = continuation(result(tool.execute("first", request)))
        assertFalse(tool.execute("same", JsonObject(cursor + request)).isError)
        val changed = args("""{"ranges":[{"path":"B.kt","start_line":1,"end_line":3}]}""")
        assertEquals("INVALID_ARGUMENTS", tool.execute("changed", JsonObject(cursor + changed)).errorCode)
        assertEquals("INVALID_ARGUMENTS", tool.execute("write", JsonObject(cursor + ("files" to JsonArray(emptyList())))).errorCode)
        assertEquals(1, backend.calls.size)
    }

    @Test fun budgetedWatchAcceptsExactCommitAndRecoversAnUnboundTarget() = runBlocking {
        val backend = Backend()
        val name = "github_watch_checks__selected"
        val bound = ToolExecutionBudget(AgentRunLimits()).bind(GitHubOperationTool("github_watch_checks", backend, "watch-by-commit", name))
        val ownerRepo = args("""{"owner":"owner","repo":"repo"}""")
        val rejected = bound.execute("no-target", ownerRepo)
        assertFalse(rejected.dispatched)
        val recipe = (rejected.content as ToolResultContent.Json).value.jsonObject.getValue("recovery_call").jsonObject
        assertEquals(JsonPrimitive("github_context__selected"), recipe["tool"])
        assertEquals(JsonObject(ownerRepo + ("action" to JsonPrimitive("status"))), recipe["arguments"])
        assertTrue(backend.calls.isEmpty())

        val head = "a".repeat(40)
        val accepted = bound.execute("commit-watch", args("""{"owner":"owner","repo":"repo","all_checks":true,"commit_sha":"$head"}"""))
        assertFalse(accepted.isError)
        assertEquals(JsonPrimitive("watch_checks"), backend.calls.single()["action"])
        assertEquals(JsonPrimitive(head), backend.calls.single()["commit_sha"])
        assertEquals(null, backend.calls.single()["run_id"])
    }

    @Test fun invalidWatchIdentifiersAreRejectedBeforeBackendDispatch() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_watch_checks", backend, "watch")
        for (invalid in listOf("{}", "{\"run_id\":0}", "{\"pr_number\":-1}", "{\"run_id\":\"not-a-run\"}")) {
            val rejected = tool.execute("invalid", args(invalid))
            assertTrue(rejected.isError)
            assertFalse(rejected.dispatched)
            assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
        }
        assertTrue(backend.calls.isEmpty())
        tool.execute("valid", args("{\"run_id\":42}"))
        assertEquals("watch_checks", backend.calls.single()["action"]!!.jsonPrimitive.content)
    }

    @Test fun commitDetailContinuationKeepsItsPageSizeAndPatchPreference() = runBlocking {
        val backend = Backend().apply {
            body = args("""{"continuation":{"action":"get_commit_detail","commit_sha":"$sha","ref":"$sha","page":2,"per_page":5,"include_patches":false}}""")
        }
        val tool = GitHubOperationTool("github_context", backend, "commit-pages")
        val next = continuation(result(tool.execute("first", args("""{"action":"commit","ref":"main","per_page":5,"include_patches":false}"""))))
        valid("github_context", next)
        assertEquals(JsonPrimitive(5), next["per_page"])
        assertEquals(JsonPrimitive(false), next["include_patches"])
        assertEquals(JsonPrimitive(sha), next["commit_sha"])
        tool.execute("next", next)
        assertEquals(JsonPrimitive(2), backend.calls.last()["page"])
        assertEquals(JsonPrimitive(5), backend.calls.last()["per_page"])
    }

    @Test fun pullRequestListIsAdvertisedAndContextRequiresObservedNumber() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_pr", backend, "pr-list")
        val list = args("""{"action":"list","state":"open","page":2}""")
        valid("github_pr", list)
        tool.execute("list", list)
        assertEquals("list_pull_requests", backend.calls.single().getValue("action").jsonPrimitive.content)
        for (action in listOf("context", "comments", "reviews")) {
            val result = tool.execute(action, args("""{"action":"$action"}"""))
            assertTrue(result.isError)
            assertFalse(result.dispatched)
            assertTrue(result.content.toString().contains("action=list"))
        }
        assertEquals(1, backend.calls.size)
    }

    @Test fun ciLogRejectionReportsMissingJobAndOffersScopedRunOrJobDiscovery() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_ci", backend, "job-selector", modelName = "github_ci__connection")
        val cases = listOf(
            Triple(args("""{"action":"logs","start_offset":0,"end_line":40}"""), "missing", args("""{"action":"runs"}""")),
            Triple(
                args("""{"action":"logs","owner":"a","repo":"r","run_id":45517338096,"run_attempt":2,"job_id":"2"}"""),
                "invalid",
                args("""{"action":"jobs","owner":"a","repo":"r","run_id":45517338096,"run_attempt":2}""")
            )
        )
        for ((request, fieldState, expectedDiscovery) in cases) {
            val rejected = tool.execute(fieldState, request)
            assertFalse(rejected.dispatched)
            val receipt = requireNotNull(rejected.executionReceipt)
            assertEquals(JsonPrimitive(1), receipt["${fieldState}_field_count"])
            assertEquals(JsonArray(listOf(JsonPrimitive("job_id"))), receipt["${fieldState}_fields"])
            val envelope = (rejected.content as ToolResultContent.Json).value.jsonObject
            val selector = envelope.getValue("required_selector").jsonObject
            assertEquals(JsonPrimitive("job_id"), selector["field"])
            assertEquals(JsonPrimitive("jobs"), selector["discovery_action"])
            val correction = envelope.getValue("correction_call").jsonObject
            assertEquals(JsonPrimitive(tool.definition.name), correction["tool"])
            assertEquals(expectedDiscovery, correction["arguments"])
            valid("github_ci", expectedDiscovery)
        }
        assertTrue(backend.calls.isEmpty())
        val largeJob = args("""{"action":"logs","job_id":138880200781}""")
        valid("github_ci", largeJob)
    }

    @Test fun missingOrInvalidPullRequestSelectorReturnsTypedRoutePreservingDiscovery() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_pr", backend, "pr-selector", modelName = "github_pr__connection")
        for ((callId, request, field) in listOf(
            Triple("missing", args("""{"owner":"owner","repo":"repo","action":"context"}"""), "missing_fields"),
            Triple("invalid", args("""{"owner":"owner","repo":"repo","action":"threads","pull_number":0}"""), "invalid_fields")
        )) {
            val rejected = tool.execute(callId, request)
            assertTrue(rejected.isError)
            assertFalse(rejected.dispatched)
            assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
            val envelope = (rejected.content as ToolResultContent.Json).value.jsonObject
            assertEquals(JsonPrimitive("PR_SELECTOR_REQUIRED"), envelope["reason"])
            assertEquals(JsonArray(listOf(JsonPrimitive("pull_number"))), envelope[field])
            val selector = envelope.getValue("required_selector").jsonObject
            assertEquals(JsonPrimitive("pull_number"), selector["field"])
            assertEquals(JsonPrimitive("positive_integer"), selector["type"])
            assertEquals(JsonPrimitive("list"), selector["discovery_action"])
            val correction = envelope.getValue("correction_call").jsonObject
            assertEquals(JsonPrimitive("github_pr__connection"), correction["tool"])
            assertEquals(
                args("""{"action":"list","owner":"owner","repo":"repo"}"""),
                correction.getValue("arguments")
            )
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun tagCreationContinuationInspectsWithoutRepeatingTheMutation() = runBlocking {
        val backend = Backend().apply {
            body = args("""{"upstream_completed":true,"continuation":{"action":"tag_context","tag_name":"v1"}}""")
        }
        val tool = GitHubOperationTool("github_release", backend, "tag-create")
        val first = tool.execute("create", args("""{"action":"create_tag","tag_name":"v1","target_commitish":"$sha"}"""))
        val next = continuation(result(first))
        valid("github_release", next)
        assertEquals("tag", next.getValue("action").jsonPrimitive.content)
        assertTrue("target_commitish" !in next)
        assertTrue(ToolArgumentValidator.errors(next, requireNotNull(dev.chungjungsoo.gptmobile.data.agent.GitHubActionSchema.project(tool.definition, "tag")).inputSchema).isEmpty())
        tool.execute("read", next)
        assertEquals(listOf("create_tag", "tag_context"), backend.calls.map { it.getValue("action").jsonPrimitive.content })
    }

    @Test fun issueCreationContinuationReadsContextWithoutRepeatingTheMutation() = runBlocking {
        val backend = Backend().apply {
            body = args("""{"upstream_completed":true,"continuation":{"action":"issue_context","issue_number":7}}""")
        }
        val tool = GitHubOperationTool("github_issue", backend, "issue-create")
        val first = tool.execute("create", args("""{"action":"create","title":"Bug","body":"Description"}"""))
        val next = continuation(result(first))
        valid("github_issue", next)
        assertEquals("context", next.getValue("action").jsonPrimitive.content)
        assertTrue(setOf("title", "body").none { it in next })
        assertTrue(ToolArgumentValidator.errors(next, requireNotNull(dev.chungjungsoo.gptmobile.data.agent.GitHubActionSchema.project(tool.definition, "context")).inputSchema).isEmpty())
        tool.execute("read", next)
        assertEquals(listOf("create_issue", "issue_context"), backend.calls.map { it.getValue("action").jsonPrimitive.content })
    }

    @Test fun commitHistoryContinuationPreservesPinnedTipAndReadAction() = runBlocking {
        val backend = Backend().apply {
            body = args("""{"items":[{"sha":"$sha"}],"has_more":true,"continuation":{"action":"list_commits","ref":"$sha","commit_sha":"$sha","page":2}}""")
        }
        val tool = GitHubOperationTool("github_context", backend, "commit-pages")
        val first = tool.execute("first", args("""{"action":"commits","ref":"main"}"""))
        val next = continuation(result(first))
        valid("github_context", next)
        assertEquals("commits", next.getValue("action").jsonPrimitive.content)
        assertEquals(sha, next.getValue("commit_sha").jsonPrimitive.content)
        assertEquals(sha, next.getValue("ref").jsonPrimitive.content)
    }

    @Test fun invalidWritePathsAndDuplicatesNeverReachBackend() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_write", backend, "write-paths")
        for (files in listOf(
            """[{"content":"x"}]""",
            """[{"path":"","content":"x"}]""",
            """[{"path":"../x","content":"x"}]""",
            """[{"path":"/app/x","content":"x"}]""",
            """[{"path":"a.kt","content":"x"},{"path":"a.kt","content":"y"}]"""
        )) {
            val result = tool.execute("bad", args("""{"branch":"fix/test","expected_head_sha":"${"a".repeat(40)}","message":"Update","files":$files}"""))
            assertTrue(result.isError)
            assertFalse(result.dispatched)
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun ciDefaultsToDiscoveryAndPreservesPageContinuation() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_ci", backend, "workflow-pages")
        tool.execute("discover", args("{}"))
        assertEquals("list_workflows", backend.calls.single()["action"]?.jsonPrimitive?.content)
    }

    @Test fun workflowDefinitionContinuationRetainsFacadeActionAndPinnedRevision() = runBlocking {
        val backend = Backend().apply {
            body = args("""{"workflows":[{"path":".github/workflows/build.yml"},{"path":".github/workflows/lint.yml"},{"path":".github/workflows/test.yml"},{"path":".github/workflows/release.yml"}],"total_count":5,"has_more":true,"continuation":{"action":"workflow_catalog","commit_sha":"$sha","index_cursor":4}}""")
        }
        val tool = GitHubOperationTool("github_ci", backend, "workflow-definitions")
        val first = tool.execute("first", args("""{"action":"definitions","ref":"main"}"""))
        assertEquals("workflow_catalog", backend.calls.single().getValue("action").jsonPrimitive.content)
        val next = continuation(result(first))
        valid("github_ci", next)
        assertEquals("definitions", next.getValue("action").jsonPrimitive.content)
        assertEquals(sha, next.getValue("commit_sha").jsonPrimitive.content)
        assertEquals("4", next.getValue("index_cursor").jsonPrimitive.content)
        backend.body = args("""{"workflows":[{"path":".github/workflows/acceptance.yml"}],"total_count":5,"has_more":false}""")
        val last = tool.execute("next", next)
        assertFalse(last.isError)
        assertEquals(JsonPrimitive(false), result(last).getValue("data").jsonObject["has_more"])
        assertFalse(result(last).getValue("data").jsonObject.containsKey("pagination_progress"))
        assertEquals("workflow_catalog", backend.calls.last().getValue("action").jsonPrimitive.content)
    }

    @Test fun evidenceHandleWithInventedNumericCursorReturnsActionableCorrection() = runBlocking {
        val backend = Backend()
        val operation = GitHubOperationTool("github_read", backend, "read-validation")
        val invalid = operation.execute("invalid", args("""{"evidence_id":"retained-source","view_cursor":"0"}"""))
        assertTrue(invalid.isError)
        assertFalse(invalid.dispatched)
        assertEquals("INVALID_ARGUMENTS", invalid.errorCode)
        val envelope = (invalid.content as ToolResultContent.Json).value.jsonObject
        assertTrue(envelope.getValue("message").jsonPrimitive.content.contains("evidence_id alone"))
        val correction = envelope.getValue("correction_call").jsonObject
        assertEquals("github_read", correction.getValue("tool").jsonPrimitive.content)
        assertEquals(args("""{"evidence_id":"retained-source"}"""), correction.getValue("arguments"))
        assertTrue(backend.calls.isEmpty())
        operation.execute("corrected", correction.getValue("arguments").jsonObject)
        assertEquals("retained-source", backend.calls.single()["evidence_id"]?.jsonPrimitive?.content)
    }

    @Test fun shortWorkspaceCommandsKeepTheirRepositoryAndWriteCatalog() {
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("connection", "owner", "repo", "feature", "a".repeat(40), "coder")
        val tools = GitHubOperationTool.taskTools(context.promptContext() + "\n\nImplement fixes")
        assertTrue(tools.containsAll(setOf("github_context", "github_read", "github_stage_patch", "github_write")))
        assertTrue(context.promptContext().contains("GitHub workspace: owner/repo\nBranch: feature"))
    }

    @Test fun unchangedPrecheckRejectionReusesFailureHistoryWithoutDispatch() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_read", backend, "task")
        val invalid = args("""{"view_cursor":"not-a-cursor"}""")
        val first = tool.execute("first", invalid)
        val replay = tool.execute("retry", invalid)
        assertTrue(first.isError)
        assertTrue(replay.isError)
        assertFalse(first.sharedResult)
        assertTrue(replay.sharedResult)
        assertFalse(replay.dispatched)
        assertEquals("retry", replay.callId)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun numericViewCursorExplainsRecoveryWithoutDispatch() = runBlocking {
        val backend = Backend()
        val operation = GitHubOperationTool("github_read", backend, "invalid-cursor")
        val result = operation.execute("read", args("""{"view_cursor":"0"}"""))
        assertTrue(result.isError)
        assertFalse(result.dispatched)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertTrue((result.content as ToolResultContent.Text).text.contains("omit view_cursor"))
        assertEquals(JsonPrimitive("SAVED_VIEW_CURSOR_MALFORMED"), result.executionReceipt?.get("reason_code"))
        assertEquals(JsonPrimitive("PREFLIGHT_REJECTED"), result.executionReceipt?.get("phase"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun savedPagesRenewOnlyInsideTheirAuthorizedScopeAndEventuallyExpire() = runBlocking {
        var clock = 0L
        val backend = Backend().also { it.body = JsonPrimitive("captured ".repeat(2000)) }
        val views = GitHubResultViews(now = { clock }, pageBytes = 2048)
        val read = GitHubOperationTool("github_read", backend, "task-A", views = views)
        val cursor = continuation(result(read.execute("first", args("""{"evidence_id":"E1"}"""))))
        clock = 9 * 60_000L
        assertFalse(read.execute("active", cursor).isError)
        clock = 18 * 60_000L
        assertFalse(read.execute("active-again", cursor).isError)
        clock = 27 * 60_000L
        val foreign = GitHubOperationTool("github_read", backend, "task-B", views = views).execute("foreign", cursor)
        assertEquals("SAVED_VIEW_UNAVAILABLE", foreign.errorCode)
        assertFalse(foreign.dispatched)
        val mixed = read.execute("mixed", JsonObject(cursor + ("commit_sha" to JsonPrimitive(sha))))
        assertEquals("INVALID_ARGUMENTS", mixed.errorCode)
        assertFalse(mixed.dispatched)
        val correction = (mixed.content as ToolResultContent.Json).value.jsonObject.getValue("correction_call").jsonObject
        assertEquals(cursor, correction.getValue("arguments"))
        assertEquals("github_read", correction.getValue("tool").jsonPrimitive.content)
        clock = 29 * 60_000L
        val expired = read.execute("expired", cursor)
        assertEquals("SAVED_VIEW_UNAVAILABLE", expired.errorCode)
        assertFalse(expired.dispatched)
        val diagnostic = (expired.content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive("EXPIRED"), diagnostic["reason"])
        // An opaque evidence handle supplies no repository, revision or ranges
        // from which to invent a new immutable source request after expiry.
        assertEquals(JsonPrimitive("START_FRESH_SCOPED_READ"), diagnostic["recovery_action"])
        assertEquals(JsonPrimitive(false), diagnostic["mutation_replay_allowed"])
        assertFalse("source_recovery_call" in diagnostic)
        assertTrue(diagnostic.getValue("message").jsonPrimitive.content.contains("fresh authorized read"))
        assertEquals(1, backend.calls.size)
    }

    @Test fun expiredPinnedSourcePreservesItsExactImmutableRecoveryWithoutDispatch() = runBlocking {
        var clock = 0L
        val request = args("""{"owner":"owner","repo":"repo","commit_sha":"$sha","ranges":[{"path":"README.md","start_line":1,"end_line":1}]}""")
        val backend = Backend().also {
            it.body = buildJsonObject {
                put("commit_sha", sha)
                put("content", "captured ".repeat(2000))
            }
        }
        val views = GitHubResultViews(now = { clock }, pageBytes = 2048)
        val read = GitHubOperationTool("github_read", backend, "pinned-source", views = views)
        val cursor = continuation(result(read.execute("first", request)))
        clock = 11 * 60_000L
        val expired = read.execute("expired", cursor)
        assertEquals("SAVED_VIEW_UNAVAILABLE", expired.errorCode)
        assertFalse(expired.dispatched)
        val diagnostic = result(expired)
        assertEquals(JsonPrimitive("EXPIRED"), diagnostic["reason"])
        assertEquals(JsonPrimitive("REOPEN_ORIGINAL_SOURCE"), diagnostic["recovery_action"])
        assertEquals(JsonPrimitive(false), diagnostic["mutation_replay_allowed"])
        assertTrue(diagnostic.getValue("message").jsonPrimitive.content.contains("same immutable source"))
        val recovery = diagnostic.getValue("source_recovery_call").jsonObject
        assertEquals(JsonPrimitive("github_read"), recovery["tool"])
        assertEquals(request, recovery["arguments"])
        assertEquals(1, backend.calls.size)
    }

    @Test fun savedPageAcceptsMatchingContextDefaultsWithoutRepeatingBackendWork() = runBlocking {
        val backend = Backend().also { it.body = JsonPrimitive("captured ".repeat(2000)) }
        val views = GitHubResultViews(pageBytes = 2048)
        val tool = GitHubOperationTool("github_pr", backend, "task", views = views)
        val request = args("""{"owner":"owner","repo":"repo","action":"context","pull_number":12,"commit_sha":"$sha"}""")
        val cursor = continuation(result(tool.execute("first", request)))
        val matching = JsonObject(cursor + request.filterKeys { it in setOf("owner", "repo", "action", "commit_sha") })
        val saved = tool.execute("page", matching)
        assertFalse(saved.isError)
        assertFalse(saved.dispatched)
        assertTrue(saved.sharedResult)
        for (changed in listOf("owner" to JsonPrimitive("other"), "repo" to JsonPrimitive("other"), "commit_sha" to JsonPrimitive("b".repeat(40)), "action" to JsonPrimitive("merge"))) {
            val refused = tool.execute("wrong-${changed.first}", JsonObject(matching + changed))
            assertEquals("INVALID_ARGUMENTS", refused.errorCode)
            assertFalse(refused.dispatched)
            assertEquals(cursor, (refused.content as ToolResultContent.Json).value.jsonObject.getValue("correction_call").jsonObject.getValue("arguments"))
        }
        val foreign = GitHubOperationTool("github_pr", backend, "other-task", views = views).execute("foreign", matching)
        assertEquals("SAVED_VIEW_UNAVAILABLE", foreign.errorCode)
        assertEquals(1, backend.calls.size)
    }

    @Test fun nextTurnCanReadSavedReceiptWithoutRepeatingTheMutation() = runBlocking {
        val backend = Backend().also { it.body = JsonPrimitive("commit receipt ".repeat(500)) }
        val views = GitHubResultViews(pageBytes = 2048)
        val firstScope = GitHubResultViews.scope("credential", "connection", "chat", "first-turn")
        val first = GitHubOperationTool("github_commit", backend, firstScope, views = views)
        val request = args("""{"stage_id":"stage","branch":"work","expected_head_sha":"$sha","message":"Commit"}""")
        val cursor = continuation(result(first.execute("commit", request)))
        val nextScope = GitHubResultViews.scope("credential", "connection", "chat", "next-turn")
        val next = GitHubOperationTool("github_commit", backend, nextScope, views = views).execute("receipt", cursor)
        assertFalse(next.isError)
        assertFalse(next.dispatched)
        assertEquals(1, backend.calls.size)
        for (scope in listOf(
            GitHubResultViews.scope("other-credential", "connection", "chat", "next-turn"),
            GitHubResultViews.scope("credential", "other-connection", "chat", "next-turn"),
            GitHubResultViews.scope("credential", "connection", "other-chat", "next-turn")
        )) {
            val foreign = GitHubOperationTool("github_commit", backend, scope, views = views).execute("foreign", cursor)
            assertEquals("SAVED_VIEW_UNAVAILABLE", foreign.errorCode)
        }
        assertFalse(GitHubResultViews.scope("credential", "connection", "", "first") == GitHubResultViews.scope("credential", "connection", "", "next"))
        assertEquals(1, backend.calls.size)
    }

    @Test fun malformedSourceRangesAreRejectedBeforeTheBackendRuns() = runBlocking {
        val backend = Backend()
        val read = GitHubOperationTool("github_read", backend, "validation")
        val invalid = listOf(
            """{"ranges":[{"path":"file.kt","start_line":"1","end_line":20}]}""",
            """{"ranges":[{"start_line":1}]}""",
            """{"ranges":false}""",
            """{"ranges":[null]}""",
            """{"ranges":[{"path":"file.kt","end_line":null}]}""",
            """{"ranges":[{"path":"file.kt","endLine":20}]}""",
            """{"ranges":[{"path":"file.kt","start_offset":null}]}""",
            """{"ranges":[{"path":"file.kt","start_offset":"0"}]}""",
            """{"ranges":[{"path":"file.kt","start_offset":2147483648}]}"""
        )
        for ((index, raw) in invalid.withIndex()) {
            val result = read.execute("invalid-$index", args(raw))
            assertEquals(raw, "INVALID_ARGUMENTS", result.errorCode)
            assertFalse(result.dispatched)
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun boundedSourceDefaultsAndValidContinuationOffsetsReachTheBackend() = runBlocking {
        val backend = Backend()
        val read = GitHubOperationTool("github_read", backend, "bounded-source-defaults")
        val ranges = listOf(
            """{"path":"file.kt"}""",
            """{"path":"file.kt","start_line":2,"end_line":-1}""",
            """{"path":"file.kt","start_line":2,"max_lines":20}""",
            """{"path":"file.kt","start_line":2,"end_line":4,"start_offset":300}"""
        )
        for ((index, range) in ranges.withIndex()) {
            val request = args("{\"ranges\":[$range]}")
            val result = read.execute("valid-$index", request)
            assertFalse(result.content.toString(), result.isError)
            assertEquals(request["ranges"], backend.calls.last()["ranges"])
        }
        assertEquals(ranges.size, backend.calls.size)
    }

    @Test fun activeSavedViewsSurviveEntryEvictionBeforeInactiveViews() = runBlocking {
        val backend = Backend().also { it.body = JsonPrimitive("captured ".repeat(500)) }
        val views = GitHubResultViews(pageBytes = 2048)
        val read = GitHubOperationTool("github_read", backend, "task-A", views = views)
        val cursors = (1..32).map { continuation(result(read.execute("source-$it", args("""{"evidence_id":"E$it"}""")))) }
        assertFalse(read.execute("touch-first", cursors.first()).isError)
        read.execute("new-source", args("""{"evidence_id":"E33"}"""))
        assertFalse(read.execute("first-still-present", cursors.first()).isError)
        assertEquals("SAVED_VIEW_UNAVAILABLE", read.execute("second-evicted", cursors[1]).errorCode)
        assertEquals(33, backend.calls.size)
    }

    @Test fun changesRequiresAnExplicitStringBaseBeforeDispatch() = runBlocking {
        val backend = Backend()
        val operation = GitHubOperationTool("github_context", backend, "changes-validation")
        for (input in listOf(
            """{"action":"changes"}""",
            """{"action":"changes","base_sha":"   "}""",
            """{"action":"changes","base_sha":false}""",
            """{"action":"changed_since","base":"main"}"""
        )) {
            val result = operation.execute("invalid", args(input))
            assertTrue(result.isError)
            assertFalse(result.dispatched)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
        }
        assertTrue(backend.calls.isEmpty())
        operation.execute("valid", args("""{"action":"changes","base_sha":"main"}"""))
        assertEquals("changed_since", backend.calls.single()["action"]?.jsonPrimitive?.content)
        assertEquals("main", backend.calls.single()["base"]?.jsonPrimitive?.content)
    }

    @Test fun invalidSearchQueriesAreRejectedBeforeDispatch() = runBlocking {
        val backend = Backend()
        val operation = GitHubOperationTool("github_search", backend, "search-validation")
        for (input in listOf("{}", "{\"query\":\"   \"}", "{\"query\":false}", "{\"query\":\"${"x".repeat(201)}\"}")) {
            val result = operation.execute("invalid", args(input))
            assertTrue(result.isError)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
        }
        assertTrue(backend.calls.isEmpty())
        operation.execute("valid", args("{\"query\":\"${"x".repeat(200)}\"}"))
        assertEquals(1, backend.calls.size)
    }

    @Test fun incompleteRepositoryOverridesAreRejectedWithoutDispatch() = runBlocking {
        val backend = Backend()
        val operation = GitHubOperationTool("github_context", backend, "validation-test")
        val result = operation.execute("invalid", args("""{"action":"status","owner":"team"}"""))
        assertTrue(result.isError)
        assertTrue((result.content as ToolResultContent.Text).text.contains("both owner and repo"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun omittedRepositoryUsesPinnedContext() = runBlocking {
        val backend = Backend()
        val operation = GitHubOperationTool("github_context", backend, "context-test")
        operation.execute("context", args("""{"action":"status"}"""))
        assertEquals(1, backend.calls.size)
        assertEquals("repo_status", backend.calls.single()["action"]?.jsonPrimitive?.content)
    }

    @Test fun compactContextCanRequestRemoteRepositoryStatus() {
        val arguments = GitHubOperationTool.nativeArguments("github_context", buildJsonObject { put("action", "status") })!!
        assertEquals("repo_status", arguments["action"]?.jsonPrimitive?.content)
        assertTrue(GitHubOperationTool.definition("github_context").inputSchema.toString().contains("status"))
    }
    private val sha = "a".repeat(40)
    private class Backend : AgentTool {
        override val definition = AgentToolDefinition("github", "Legacy backend", buildJsonObject {}, modelVisible = false)
        val calls = mutableListOf<JsonObject>()
        var body: JsonElement = buildJsonObject { put("ok", true) }
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            calls += arguments
            delay(1)
            return AgentToolResult(callId, ToolResultContent.Json(body), false)
        }
    }
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun result(value: AgentToolResult) = (value.content as ToolResultContent.Json).value.jsonObject
    private fun continuation(value: JsonObject) = value.getValue("continuation_call").jsonObject.getValue("arguments").jsonObject
    private fun valid(operation: String, value: JsonObject) = assertEquals("Advertised continuation must validate against its own facade", emptyList<String>(), ToolArgumentValidator.errors(value, GitHubOperationTool.definition(operation).inputSchema))

    @Test fun compactInspectionContractsKeepReadsAndRejectWrites() {
        val pr = GitHubOperationTool.definition("github_pr_inspect")
        assertEquals("github_pr", GitHubOperationTool.operationName(pr.name))
        valid(
            "github_pr_inspect",
            buildJsonObject {
                put("action", "context")
                put("pull_number", 7)
            }
        )
        assertTrue(
            ToolArgumentValidator.errors(
                buildJsonObject {
                    put("action", "merge")
                    put("pull_number", 7)
                },
                pr.inputSchema
            ).isNotEmpty()
        )
        val broad = GitHubOperationTool.definition("github_pr")
        assertTrue(ProgressiveToolCatalog.schemaTokens(pr) < ProgressiveToolCatalog.schemaTokens(broad) / 2)
    }

    @Test fun compactPrContinuationsKeepTheirAdvertisedAliasAndExecutePinnedReadArguments() = runBlocking {
        for (suffix in listOf("", "__selected_inspect")) {
            val backend = Backend().also {
                it.body = args("""{"files_continuation":{"action":"pr_context","pull_number":17,"page":2,"expected_head":"$sha","expected_base_sha":"${"b".repeat(40)}","include_patches":false,"include_files":true,"include_checks":false},"checks_continuation":{"action":"pr_context","pull_number":17,"page":1,"check_page":3,"expected_head":"$sha","expected_base_sha":"${"b".repeat(40)}","include_patches":false,"include_files":false,"include_checks":true}}""")
            }
            val name = "github_pr_inspect$suffix"
            val tool = GitHubOperationTool("github_pr", backend, "compact-pr-$suffix", name)
            val initial = result(tool.execute("first", args("""{"action":"context","pull_number":17}""")))
            val continuations = initial.getValue("remote_continuations").jsonArray
            assertEquals(2, continuations.size)
            for ((index, entry) in continuations.withIndex()) {
                val call = entry.jsonObject.getValue("call").jsonObject
                assertEquals(JsonPrimitive(name), call["tool"])
                val arguments = call.getValue("arguments").jsonObject
                assertEquals(emptyList<String>(), ToolArgumentValidator.errors(arguments, tool.definition.inputSchema))
                assertEquals(JsonPrimitive(sha), arguments["expected_head_sha"])
                assertEquals(JsonPrimitive("b".repeat(40)), arguments["expected_base_sha"])
                assertEquals(JsonPrimitive(false), arguments["include_patches"])
                val executed = tool.execute("page-$index", arguments)
                assertFalse(executed.isError)
                assertEquals(JsonPrimitive("pr_context"), backend.calls.last()["action"])
                assertEquals(JsonPrimitive(sha), backend.calls.last()["expected_head"])
                for (field in listOf("include_files", "include_checks", "check_page")) assertEquals(arguments[field], backend.calls.last()[field])
            }
            val count = backend.calls.size
            val write = tool.execute("write", args("""{"action":"merge","pull_number":17,"expected_head_sha":"$sha"}"""))
            assertTrue(write.isError)
            assertFalse(write.dispatched)
            assertEquals(count, backend.calls.size)
        }
    }

    @Test fun compactContextCorrectionsRouteOnlyToRealToolsOnTheSameConnection() = runBlocking {
        for (suffix in listOf("", "__selected_inspect")) {
            val read = args("""{"commit_sha":"$sha","ranges":[{"path":"File.kt","start_line":1,"end_line":5}]}""")
            val search = args("""{"commit_sha":"$sha","query":"*.kt","mode":"paths"}""")
            val context = args("""{"action":"resolve"}""")
            val corrections = listOf("github_read" to read, "github_search" to search, "github_context" to context)
            val observed = mutableListOf<JsonObject>()
            val backend = object : AgentTool {
                override val definition = AgentToolDefinition("github$suffix", "Read correction fixture", buildJsonObject {})
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    observed += arguments
                    if (observed.size > 1) return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("ok", true) }), false)
                    return AgentToolResult(
                        callId,
                        ToolResultContent.Json(
                            buildJsonObject {
                                put("schema", "github.read_validation.v1")
                                put(
                                    "corrective_actions",
                                    JsonArray(
                                        corrections.map { (name, args) ->
                                            buildJsonObject {
                                                put("tool", name)
                                                put("arguments", args)
                                            }
                                        }
                                    )
                                )
                            }
                        ),
                        true,
                        errorCode = "INVALID_ARGUMENTS",
                        dispatched = false
                    )
                }
            }
            val inspect = GitHubOperationTool("github_context", backend, "corrections-$suffix", "github_context_inspect$suffix")
            val targets = listOf(inspect, GitHubOperationTool("github_read", backend, "corrections-$suffix", "github_read$suffix"), GitHubOperationTool("github_search", backend, "corrections-$suffix", "github_search$suffix"))
            val first = result(inspect.execute("initial", args("""{"action":"status"}"""))).getValue("data").jsonObject
            val calls = first.getValue("corrective_actions").jsonArray.map { it.jsonObject }
            assertEquals(setOf("github_read$suffix", "github_search$suffix", "github_context_inspect$suffix"), calls.map { it.getValue("tool").jsonPrimitive.content }.toSet())
            for ((index, call) in calls.withIndex()) {
                val target = targets.single { it.definition.name == call.getValue("tool").jsonPrimitive.content }
                val arguments = call.getValue("arguments").jsonObject
                assertEquals(emptyList<String>(), ToolArgumentValidator.errors(arguments, target.definition.inputSchema))
                val result = target.execute("corrected-$index", arguments)
                assertFalse(result.isError)
            }
            assertEquals(listOf("repo_status", "read_ranges", "search_repository", "resolve_context"), observed.map { it.getValue("action").jsonPrimitive.content })
        }
    }

    @Test fun ordinaryRepositorySchemasRemainBoundedAndHideLegacyMonolith() {
        val definitions = GitHubOperationTool.names.map { GitHubOperationTool.definition(it) }
        val catalog = requireNotNull(ProgressiveToolCatalog.create(listOf(Backend().definition) + definitions, "Inspect this repository"))
        val selected = catalog.selected(listOf(Backend().definition) + definitions).filter { GitHubOperationTool.operationName(it.name) != null }
        assertEquals(setOf("github_context_inspect", "github_search", "github_read"), selected.map { it.name }.toSet())
        val wire = JsonArray(
            selected.map {
                buildJsonObject {
                    put("name", it.name)
                    put("description", it.description)
                    put("parameters", it.inputSchema)
                }
            }
        ).toString()
        val estimate = dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(wire)
        println("GitHub ordinary schema: ${wire.toByteArray().size} UTF-8 bytes, $estimate estimated tokens, ${selected.size} facade definitions")
        assertTrue("Ordinary schema estimate was $estimate; expanded admission contracts must remain under 1600 tokens", estimate < 1600)
        assertFalse(catalog.selected(listOf(Backend().definition) + definitions).any { it.name == "github" })
        assertEquals(setOf("github_context", "github_search", "github_read", "github_stage_patch", "github_commit", "github_write"), GitHubOperationTool.taskTools("Fix code in the repository, create a branch and push the commit"))
    }

    @Test fun savedWriteResultReopensThroughAdvertisedSchemasWithoutAnotherWrite() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_write", backend, "write-cursor")
        val write = args("""{"action":"commit","branch":"fix/example","expected_head_sha":"$sha","message":"Fix code","files":[{"path":"src/Fix.kt","content":"updated","expected_blob_sha":"$sha"}]}""")
        val sent = tool.execute("first", write)
        assertFalse(sent.isError)
        val cursor = result(sent).getValue("recovery_call").jsonObject.getValue("arguments").jsonObject
        val focused = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.GitHubActionSchema.project(tool.definition, "commit"))
        for (definition in listOf(tool.definition, focused)) {
            val schema = definition.inputSchema
            for (exposed in listOf(schema, dev.chungjungsoo.gptmobile.data.agent.provider.modelCompatibleGitHubToolSchema(schema))) {
                assertTrue(ToolArgumentValidator.errors(cursor, exposed).isEmpty())
                assertTrue(ToolArgumentValidator.errors(write, exposed).isEmpty())
                for (required in listOf("branch", "expected_head_sha", "message", "files")) {
                    assertTrue(required, ToolArgumentValidator.errors(JsonObject(write - required), exposed).isNotEmpty())
                }
            }
            assertTrue(ToolArgumentValidator.errors(JsonObject(write + cursor), schema).isNotEmpty())
        }
        val saved = tool.execute("recover", cursor)
        assertFalse(saved.isError)
        assertEquals(listOf(JsonPrimitive("commit_files")), backend.calls.map { it["action"] })
    }

    @Test fun inspectionAliasesAdmitTheirOwnReturnedPaginationControls() {
        val fixtures = listOf(
            Triple("github_pr", args("""{"action":"list"}"""), args("""{"action":"list_pull_requests","state":"open","sort":"updated","direction":"desc","page":2,"per_page":20}""")),
            Triple("github_context", args("""{"action":"commit","commit_sha":"$sha"}"""), args("""{"action":"get_commit_detail","commit_sha":"$sha","ref":"$sha","page":2,"per_page":30,"include_patches":false}"""))
        )
        for ((name, request, native) in fixtures) {
            val alias = "${name}_inspect"
            val shown = GitHubResultViews().present("inspection", name, alias, AgentToolResult("first", ToolResultContent.Json(buildJsonObject { put("continuation", native) }), false), request)
            val call = result(shown).getValue("continuation_call").jsonObject
            val next = call.getValue("arguments").jsonObject
            assertEquals(JsonPrimitive(alias), call["tool"])
            val schema = GitHubOperationTool.definition(alias)
            val focused = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.GitHubActionSchema.project(schema, request.getValue("action").jsonPrimitive.content))
            assertTrue(ToolArgumentValidator.errors(next, schema.inputSchema).isEmpty())
            assertTrue(ToolArgumentValidator.errors(next, focused.inputSchema).isEmpty())
            assertEquals(native, GitHubOperationTool.nativeArguments(alias, next))
        }
    }

    @Test fun selectedWorkspaceRefCannotSplitAnUnfilteredTraversalOrInvalidateItsNextCall() {
        val fixtures = listOf(
            "github_ci" to args("""{"action":"jobs","run_id":12}"""),
            "github_ci" to args("""{"action":"artifacts","run_id":12}"""),
            "github_context" to args("""{"action":"branch_dependencies","branch":"feature"}"""),
            "github_context" to args("""{"action":"commit_search","query":"release"}""")
        )
        for ((name, request) in fixtures) {
            val first = requireNotNull(GitHubOperationTool.nativeArguments(name, request))
            val native = JsonObject(first + mapOf("ref" to JsonPrimitive("main"), "page" to JsonPrimitive(2)))
            val view = GitHubResultViews().present("selected-ref", name, name, AgentToolResult("first", ToolResultContent.Json(buildJsonObject { put("continuation", native) }), false), request)
            val next = continuation(result(view))
            assertEquals(JsonObject(request + ("page" to JsonPrimitive(2))), next)
            val definition = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.GitHubActionSchema.project(GitHubOperationTool.definition(name), request.getValue("action").jsonPrimitive.content))
            assertTrue(ToolArgumentValidator.errors(next, definition.inputSchema).isEmpty())
            assertEquals(
                dev.chungjungsoo.gptmobile.data.github.GitHubInventoryQuery.arguments(name, first),
                dev.chungjungsoo.gptmobile.data.github.GitHubInventoryQuery.arguments(name, native)
            )
        }
    }

    @Test fun commitStatusAndFailureContinuationsRetainPageAttemptAndPinnedHead() {
        val cases = listOf(
            args("""{"action":"status","ref":"main"}""") to
                args("""{"action":"get_commit_status_summary","ref":"$sha","commit_sha":"$sha","page":2}"""),
            args("""{"action":"failure","run_id":24}""") to
                args("""{"action":"ci_failure_digest","run_id":24,"run_attempt":2,"expected_head_sha":"$sha","page":11}""")
        )
        for ((request, native) in cases) {
            val views = GitHubResultViews()
            val first = views.present("ci-pages", "github_ci", "github_ci", AgentToolResult("first", ToolResultContent.Json(buildJsonObject { put("continuation", native) }), false), request)
            val next = continuation(result(first))
            val action = request.getValue("action").jsonPrimitive.content
            val schema = GitHubOperationTool.definition("github_ci")
            val focused = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.GitHubActionSchema.project(schema, action))
            assertTrue(ToolArgumentValidator.errors(next, focused.inputSchema).isEmpty())
            assertTrue(GitHubOperationArguments.errors("github_ci", next).isEmpty())
            assertEquals(native, GitHubOperationTool.nativeArguments("github_ci", next))
        }
    }

    @Test fun focusedThreadSchemaAcceptsTheHeadBoundCursor() {
        val tool = GitHubOperationTool.definition("github_pr")
        val focused = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.GitHubActionSchema.project(tool, "threads"))
        val next = args("""{"action":"threads","owner":"o","repo":"r","pull_number":7,"after":"next","expected_head_sha":"$sha"}""")
        assertTrue(ToolArgumentValidator.errors(next, focused.inputSchema).isEmpty())
    }

    @Test fun directWriteAdvertisesGuardedMultiFileCommits() {
        val schema = GitHubOperationTool.definition("github_write").inputSchema
        val arguments = args(
            """{"action":"commit","branch":"fix/example","expected_head_sha":"$sha","message":"Fix code","files":[{"path":"src/Fix.kt","content":"updated","expected_blob_sha":"$sha"}]}"""
        )
        assertEquals(emptyList<String>(), ToolArgumentValidator.errors(arguments, schema))
        assertEquals("commit_files", GitHubOperationTool.nativeArguments("github_write", arguments)?.get("action")?.jsonPrimitive?.content)
        assertTrue(GitHubOperationTool.names.contains("github_write"))
        assertTrue(GitHubOperationTool.taskTools("Write files and push changes in GitHub").contains("github_write"))
    }

    @Test fun repairsAndExactModelFacingWriteNamesRetainExecutableSchemas() {
        for (task in listOf("Repair the repository failures", "Correct code in this repo", "Apply all these changes to GitHub", "Enable github_write__github_api for the current task")) {
            assertTrue("Missing write contract for: $task", "github_write" in GitHubOperationTool.taskTools(task))
        }
        val args = args("""{"action":"capabilities"}""")
        assertTrue(ToolArgumentValidator.errors(args, GitHubOperationTool.definition("github_context").inputSchema).isEmpty())
        assertEquals("write_capabilities", GitHubOperationTool.nativeArguments("github_context", args)?.get("action")?.jsonPrimitive?.content)
    }

    @Test fun directWriteRejectsMalformedFileListsBeforeDispatch() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_write", backend, "write-validation")
        val invalid = args("""{"branch":"fix/example","files":[{"path":"src/Fix.kt"}]}""")
        val result = tool.execute("invalid-write", invalid)
        assertTrue(result.isError)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertFalse(result.dispatched)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun deletionSchemaRequiresExplicitIntentAndBlobWithoutReplacementContent() {
        val schema = GitHubOperationTool.definition("github_write").inputSchema
        fun arguments(file: String) = args("""{"branch":"fix/example","expected_head_sha":"$sha","message":"Delete file","files":[$file]}""")
        val valid = arguments("""{"path":"old.txt","delete":true,"expected_blob_sha":"$sha"}""")
        assertTrue(ToolArgumentValidator.errors(valid, schema).isEmpty())
        for (file in listOf(
            """{"path":"old.txt"}""",
            """{"path":"old.txt","delete":false}""",
            """{"path":"old.txt","delete":true}""",
            """{"path":"old.txt","delete":true,"expected_blob_sha":"$sha","content":"replacement"}"""
        )) {
            assertTrue(ToolArgumentValidator.errors(arguments(file), schema).isNotEmpty())
        }
        assertTrue("github_write" in GitHubOperationTool.taskTools("Delete old.txt in the GitHub repository"))
    }

    @Test fun directWriteRequiresAllCommitPreconditions() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_write", backend, "missing-fields")
        for (raw in listOf(
            """{"branch":"fix/example","message":"Update","files":[{"path":"new.txt","content":"text"}]}""",
            """{"expected_head_sha":"$sha","message":"Update","files":[{"path":"new.txt","content":"text"}]}""",
            """{"branch":"fix/example","expected_head_sha":"$sha","files":[{"path":"new.txt","content":"text"}]}""",
            """{"branch":"fix/example","expected_head_sha":"$sha","message":"Update"}"""
        )) {
            val result = tool.execute("missing", args(raw))
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertFalse(result.dispatched)
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun directWriteRejectsMalformedHeadShaWithoutDispatch() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_write", backend, "invalid-head")
        val result = tool.execute(
            "invalid-head",
            args("""{"branch":"fix/example","expected_head_sha":"main","message":"Update","files":[{"path":"new.txt","content":"text"}]}""")
        )
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertFalse(result.dispatched)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun directWriteRoutesThroughAuthorizedBackend() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_write", backend, "write-test")
        val arguments = args(
            """{"action":"commit","branch":"fix/example","expected_head_sha":"$sha","message":"Fix code","files":[{"path":"src/Fix.kt","content":"updated"}]}"""
        )
        tool.execute("write", arguments)
        assertEquals(1, backend.calls.size)
        assertEquals("commit_files", backend.calls.single()["action"]?.jsonPrimitive?.content)
        assertEquals("fix/example", backend.calls.single()["branch"]?.jsonPrimitive?.content)
    }

    @Test fun readContinuationPreservesCommitAndLongLineOffsetsUsingOnlyAdvertisedFields() = runBlocking {
        val backend = Backend()
        backend.body = buildJsonObject {
            put("commit_sha", sha)
            put(
                "ranges",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", "src/Long.kt")
                            put("content", "first part")
                            put(
                                "continuation",
                                buildJsonObject {
                                    put("path", "src/Long.kt")
                                    put("start_line", 2)
                                    put("end_line", 4)
                                    put("start_offset", 300)
                                    put("commit_sha", sha)
                                }
                            )
                        }
                    )
                )
            )
        }
        val operation = GitHubOperationTool("github_read", backend, "scope")
        val initial = args("""{"owner":"team","repo":"app","ranges":[{"path":"src/Long.kt","start_line":2,"end_line":4}]}""")
        val next = continuation(result(operation.execute("first", initial)))
        valid("github_read", next)
        assertFalse("path" in next)
        assertFalse("start_offset" in next)
        assertEquals(sha, next["commit_sha"]?.jsonPrimitive?.content)
        operation.execute("next", next)
        val native = backend.calls.last()
        assertEquals("read_ranges", native["action"]?.jsonPrimitive?.content)
        assertEquals(300, native["ranges"]!!.jsonArray.single().jsonObject["start_offset"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, native["ranges"]!!.jsonArray.single().jsonObject["end_line"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun readContinuationPreservesRequestedLineCountOnRedispatch() = runBlocking {
        val backend = Backend().apply {
            body = args("""{"ranges":[{"path":"src/File.kt","continuation":{"path":"src/File.kt","start_line":6,"max_lines":5,"commit_sha":"$sha"}}]}""")
        }
        val tool = GitHubOperationTool("github_read", backend, "line-count")
        val request = args("""{"owner":"team","repo":"app","ranges":[{"path":"src/File.kt","start_line":1,"max_lines":5}]}""")
        val next = continuation(result(tool.execute("first", request)))
        valid("github_read", next)
        assertEquals(JsonPrimitive(sha), next["commit_sha"])
        tool.execute("second", next)
        assertEquals(args("""{"path":"src/File.kt","start_line":6,"max_lines":5}"""), backend.calls.last().getValue("ranges").jsonArray.single())
    }

    @Test fun searchAndChangedFileContinuationsRoundtripSnapshotAndCursor() = runBlocking {
        for ((operation, initial, nativeContinuation, expectedAction) in listOf(
            listOf("github_search", """{"query":"Needle","mode":"references","path_prefix":"src/"}""", """{"action":"search_repository","commit_sha":"$sha","index_cursor":24,"page":2,"query":"Needle","mode":"references","path_prefix":"src/"}""", "search_repository"),
            listOf("github_context", """{"action":"changes","base_sha":"${"b".repeat(40)}"}""", """{"action":"changed_since","base":"${"b".repeat(40)}","commit_sha":"$sha","page":2}""", "changed_since")
        )) {
            val backend = Backend().also { it.body = buildJsonObject { put("continuation", args(nativeContinuation)) } }
            val tool = GitHubOperationTool(operation, backend, "scope")
            val next = continuation(result(tool.execute("first", args(initial))))
            valid(operation, next)
            assertEquals(sha, next["commit_sha"]?.jsonPrimitive?.content)
            tool.execute("next", next)
            assertEquals(expectedAction, backend.calls.last()["action"]?.jsonPrimitive?.content)
            assertEquals("2", backend.calls.last()["page"]?.jsonPrimitive?.content)
            if (operation == "github_search") {
                assertEquals("24", backend.calls.last()["index_cursor"]?.jsonPrimitive?.content)
            } else {
                assertEquals("b".repeat(40), backend.calls.last()["base"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test fun independentPrFilesAndChecksContinuationsStayBoundToTheSameHead() = runBlocking {
        val backend = Backend().also {
            it.body = args("""{"files_continuation":{"action":"pr_context","pull_number":17,"page":2,"expected_head":"$sha","expected_base_sha":"${"b".repeat(40)}","include_patches":false,"include_files":true,"include_checks":false},"checks_continuation":{"action":"pr_context","pull_number":17,"page":1,"check_page":3,"expected_head":"$sha","expected_base_sha":"${"b".repeat(40)}","include_patches":false,"include_files":false,"include_checks":true}}""")
        }
        val tool = GitHubOperationTool("github_pr", backend, "scope")
        val initial = result(tool.execute("first", args("""{"action":"context","pull_number":17}""")))
        val continuations = initial["remote_continuations"]!!.jsonArray
        assertEquals(2, continuations.size)
        for (entry in continuations) {
            val next = entry.jsonObject["call"]!!.jsonObject["arguments"]!!.jsonObject
            valid("github_pr", next)
            assertEquals("context", next["action"]?.jsonPrimitive?.content)
            assertEquals(sha, next["expected_head_sha"]?.jsonPrimitive?.content)
            assertEquals("b".repeat(40), next["expected_base_sha"]?.jsonPrimitive?.content)
            assertEquals(entry.jsonObject["native"]!!.jsonObject["include_files"], next["include_files"])
            assertEquals(entry.jsonObject["native"]!!.jsonObject["include_checks"], next["include_checks"])
            tool.execute("page", next)
            assertEquals(sha, backend.calls.last()["expected_head"]?.jsonPrimitive?.content)
        }
    }

    @Test fun localPagesExposeEveryItemBeforeReturningAnyRemotePageCall() = runBlocking {
        val backend = Backend().also { target ->
            target.body = buildJsonObject {
                put("commit_sha", sha)
                put(
                    "files",
                    JsonArray(
                        (0 until 30).map { index ->
                            buildJsonObject {
                                put("filename", "file-$index.kt")
                                put("text", "x".repeat(240))
                            }
                        }
                    )
                )
                put(
                    "continuation",
                    buildJsonObject {
                        put("action", "search_repository")
                        put("commit_sha", sha)
                        put("index_cursor", 30)
                        put("query", "x")
                    }
                )
            }
        }
        val views = GitHubResultViews(pageBytes = 2048)
        val tool = GitHubOperationTool("github_search", backend, "task-A", views = views)
        var page = result(tool.execute("first", args("""{"query":"x"}""")))
        val seen = StringBuilder()
        var pages = 0
        while (true) {
            pages++
            assertTrue(page.toString().toByteArray().size <= 2048)
            seen.append(page["fragments"])
            if (page["local_page_complete"]!!.jsonPrimitive.boolean) break
            assertFalse("remote_continuations" in page)
            val next = continuation(page)
            assertEquals(setOf("view_cursor"), next.keys)
            valid("github_search", next)
            val local = tool.execute("local-$pages", next)
            assertFalse(local.dispatched)
            assertTrue(local.sharedResult)
            page = result(local)
            assertTrue(pages < 100)
        }
        assertTrue(pages > 1)
        for (index in 0 until 30) assertEquals(1, Regex("\"file-$index.kt\"").findAll(seen).count())
        assertEquals(1, backend.calls.size)
        val remote = continuation(page)
        valid("github_search", remote)
        assertEquals("30", remote["index_cursor"]?.jsonPrimitive?.content)
        tool.execute("remote", remote)
        assertEquals(2, backend.calls.size)
    }

    @Test fun savedCursorsCannotBeReusedAcrossTaskOrOperationScopes() = runBlocking {
        val backend = Backend().also { it.body = JsonPrimitive("captured ".repeat(2000)) }
        val views = GitHubResultViews(pageBytes = 2048)
        val read = GitHubOperationTool("github_read", backend, "task-A", views = views)
        val cursor = continuation(result(read.execute("first", args("""{"evidence_id":"E1"}"""))))
        val foreign = GitHubOperationTool("github_read", backend, "task-B", views = views).execute("foreign", cursor)
        val other = GitHubOperationTool("github_search", backend, "task-A", views = views).execute("other", cursor)
        assertEquals("SAVED_VIEW_UNAVAILABLE", foreign.errorCode)
        assertEquals("SAVED_VIEW_WRONG_OPERATION", other.errorCode)
        assertFalse(other.dispatched)
        assertFalse(foreign.dispatched)
        assertEquals(1, backend.calls.size)
    }

    @Test fun presentationCapacityFailureRetainsMutationReceiptAndCompletedDispatch() {
        val views = GitHubResultViews(pageBytes = 2048, capacityBytes = 100)
        val raw = AgentToolResult(
            "commit",
            ToolResultContent.Json(
                buildJsonObject {
                    put("sha", sha)
                    put("stage_id", "stage-42")
                    put("payload", "x".repeat(10000))
                }
            ),
            false,
            dispatched = true
        )
        val bounded = views.present("scope", "github_commit", "github_commit", raw)
        val envelope = result(bounded)
        assertFalse("Presentation capacity does not turn a completed upstream write into a failed operation", bounded.isError)
        assertEquals(null, bounded.errorCode)
        assertTrue(bounded.dispatched)
        assertEquals(raw.content, bounded.retainedContent)
        assertEquals(JsonPrimitive("RETAINED_RESULT_CAPACITY"), envelope["error"])
        assertEquals(JsonPrimitive("COMPLETED"), bounded.executionReceipt?.get("upstream_execution_state"))
        assertEquals(JsonPrimitive("CAPACITY_REJECTED"), bounded.executionReceipt?.get("presentation_state"))
        assertTrue(envelope["upstream_completed"]!!.jsonPrimitive.boolean)
        assertEquals(sha, envelope["receipt"]!!.jsonObject["sha"]?.jsonPrimitive?.content)
        assertEquals("stage-42", envelope["receipt"]!!.jsonObject["stage_id"]?.jsonPrimitive?.content)
        assertTrue(envelope["next_step"]!!.jsonPrimitive.content.contains("never repeat a mutation"))
    }

    @Test fun onlyExplicitImmutableCommitReadsCanShareAcrossModelContributors() = runBlocking {
        val backend = Backend()
        val broker = SharedToolCallBroker()
        val tool = GitHubOperationTool("github_read", backend, "turn")
        val shared = broker.wrap("turn", "authorized-github", true, tool)
        val immutable = buildJsonObject {
            put("commit_sha", sha)
            put("evidence_id", "E1")
        }
        val results = coroutineScope { (1..5).map { index -> async { shared.execute("read-$index", immutable) } }.awaitAll() }
        assertEquals(1, backend.calls.size)
        assertEquals(4, results.count { it.sharedResult })
        repeat(2) { shared.execute("mutable-$it", buildJsonObject { put("evidence_id", "E1") }) }
        assertEquals(3, backend.calls.size)
        val commit = broker.wrap("turn", "authorized-write", true, GitHubOperationTool("github_commit", backend, "turn"))
        repeat(2) {
            commit.execute(
                "write-$it",
                buildJsonObject {
                    put("stage_id", "S1")
                    put("commit_sha", sha)
                    put("expected_head_sha", sha)
                    put("branch", "work")
                    put("message", "Apply staged changes")
                }
            )
        }
        assertEquals(5, backend.calls.size)
    }

    @Test fun thirtyDifferentReadsAreAllowedWhileAnIdenticalOperationStopsAfterThree() {
        val calls = (0 until 30).map { index ->
            ProviderEvent.ToolCall(
                "read-$index",
                "github_read",
                buildJsonObject {
                    put("commit_sha", sha)
                    put("evidence_id", "E$index")
                }
            )
        }
        val admission = ToolRoundAdmission(maxCalls = 32, maxSameTool = 24)
        assertTrue(calls.all(admission::admit))
        val plan = planToolBatch(calls, 32, setOf("github_read"), emptyMap(), emptySet(), 24, scope = ::toolRepeatKey)
        assertEquals(30, plan.executable.size)
        val repeated = (0..3).map { calls.first().copy(callId = "repeat-$it") }
        val repeatedPlan = planToolBatch(repeated, 32, setOf("github_read"), emptyMap(), emptySet(), 24, scope = ::toolRepeatKey)
        assertEquals(3, repeatedPlan.executable.size)
        assertEquals(setOf("repeat-3"), repeatedPlan.perToolSuppressedIds)
        assertTrue(repeatedPlan.disabledNames.isEmpty())
        assertNotEquals(toolRepeatKey(calls[0]), toolRepeatKey(calls[1]))
    }

    @Test fun ciFailureAllowsLatestFailedRunOrAnExplicitRefWithoutInventingAnId() = runBlocking {
        val backend = Backend()
        val tool = GitHubOperationTool("github_ci", backend, "turn")
        val latest = args("""{"action":"failure"}""")
        valid("github_ci", latest)
        tool.execute("latest", latest)
        assertEquals("ci_failure_digest", backend.calls.last()["action"]?.jsonPrimitive?.content)
        assertFalse("run_id" in backend.calls.last())
        tool.execute("branch", args("""{"action":"failure","ref":"fix/report"}"""))
        assertEquals("fix/report", backend.calls.last()["ref"]?.jsonPrimitive?.content)
    }
}
