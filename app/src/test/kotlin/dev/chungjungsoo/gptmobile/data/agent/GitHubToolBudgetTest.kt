package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubToolBudgetTest {
    @Test fun anAcceptedLargePlanProtectsItsProofReserveAcrossParallelReadsAndRestart() = runTest {
        val task = (1..33).joinToString("\n") { "Requirement $it must remain covered" }
        var retained: JsonObject? = null
        var dispatches = 0
        val checkpoint = ToolCallBudgetCheckpoint("large-proof-reserve", { retained }, { retained = it })
        checkpoint.initialize(32)
        repeat(23) { checkpoint.acquire(32) }
        val limits = AgentRunLimits(githubTask = true, githubTaskRequest = task, maxToolCalls = 32)
        val raw = object : AgentTool {
            override val definition = AgentToolDefinition(
                "github_read",
                "Read source",
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { put("verify_live", buildJsonObject { put("type", "boolean") }) })
                }
            )
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Text("source proof"), false)
            }
        }
        suspend fun restored() = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(32), durableCalls = checkpoint)
        val ordinary = restored().bind(raw)
        val results = (1..3).map { index -> async { ordinary.execute("read-$index", buildJsonObject {}) } }.awaitAll()
        assertEquals(1, results.count { !it.isError })
        assertEquals(2, results.count { it.errorCode == "VERIFICATION_RESERVE_REACHED" && !it.dispatched })
        val afterRestart = restored()
        assertEquals(8, afterRestart.remainingCalls())
        assertEquals("VERIFICATION_RESERVE_REACHED", afterRestart.bind(raw).execute("ordinary-after-restart", buildJsonObject {}).errorCode)
        // A later verified ledger may reduce the pending count; the same budget
        // releases the matching source allowance without resetting its counter.
        afterRestart.updateVerificationBudget(1, 0)
        val proof = afterRestart.bind(raw)
        repeat(6) { index -> assertFalse(proof.execute("remaining-source-$index", buildJsonObject {}).isError) }
        assertEquals("VERIFICATION_RESERVE_REACHED", proof.execute("source-at-final-reserve", buildJsonObject {}).errorCode)
        repeat(2) { index -> assertFalse(proof.execute("proof-$index", buildJsonObject { put("verify_live", true) }).isError) }
        assertEquals("BUDGET_EXHAUSTED", restored().bind(raw).execute("extra", buildJsonObject { put("verify_live", true) }).errorCode)
        assertEquals(9, dispatches)
        assertEquals(32, checkpoint.initialize(200))
    }

    @Test fun parallelProofReadsPreserveTheOwnedFixtureCleanupAllowance() = runTest {
        var retained: JsonObject? = null
        val checkpoint = ToolCallBudgetCheckpoint("proof-cleanup-boundary", { retained }, { retained = it })
        checkpoint.initialize(12)
        repeat(7) { checkpoint.acquire(12) }
        val limits = AgentRunLimits(githubTask = true, maxToolCalls = 12)
        var reads = 0
        val read = object : AgentTool {
            override val definition = AgentToolDefinition(
                "github_read",
                "Proof",
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("verify_live", buildJsonObject { put("type", "boolean") })
                            put("view_cursor", buildJsonObject { put("type", "string") })
                        }
                    )
                }
            )
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                reads++
                return AgentToolResult(callId, ToolResultContent.Text("proof"), false)
            }
        }
        val budget = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(12), durableCalls = checkpoint)
        budget.updateVerificationBudget(33, 4)
        val proof = budget.bind(read)
        val results = (1..3).map { index -> async { proof.execute("proof-$index", buildJsonObject { put("verify_live", true) }) } }.awaitAll()
        assertEquals(1, reads)
        assertEquals(2, results.count { it.errorCode == "VERIFICATION_RESERVE_REACHED" && !it.dispatched })
        assertEquals(4, budget.remainingCalls())
        assertEquals("VERIFICATION_RESERVE_REACHED", proof.execute("saved-proof", buildJsonObject { put("view_cursor", "saved:0:1024") }).errorCode)
        val reopened = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(12), durableCalls = checkpoint)
        reopened.updateVerificationBudget(1, 4)
        assertEquals("VERIFICATION_RESERVE_REACHED", reopened.bind(read).execute("proof-after-restart", buildJsonObject { put("verify_live", true) }).errorCode)
        val cleanup = reopened.bind(object : AgentTool {
            override val definition = AgentToolDefinition("github_commit", "Cleanup", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("cleanup receipt"), false)
        })
        repeat(4) { index -> assertFalse(cleanup.execute("cleanup-$index", buildJsonObject { put("action", "delete_fixture_branch") }).isError) }
        assertEquals("BUDGET_EXHAUSTED", cleanup.execute("extra", buildJsonObject {}).errorCode)
        assertEquals(12, checkpoint.initialize(200))
    }

    @Test fun retainedSearchPagesCanUseVerificationReserveWithoutRenewingTheDurableCeiling() = runTest {
        var row: JsonObject? = null
        var dispatches = 0
        val checkpoint = ToolCallBudgetCheckpoint("retained-verification", { row }, { row = it })
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(
                "github_search",
                "read",
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("view_cursor", buildJsonObject { put("type", "string") })
                            put("ref", buildJsonObject { put("type", "string") })
                        }
                    )
                    put("additionalProperties", false)
                }
            )
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Text("retained bytes"), false)
            }
        }
        checkpoint.initialize(4)
        checkpoint.acquire(4)
        checkpoint.acquire(4)
        val limits = AgentRunLimits(githubTask = true, maxToolCalls = 4)
        suspend fun restored() = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(4), durableCalls = checkpoint)
        val reserve = restored().bind(tool)
        assertEquals("VERIFICATION_RESERVE_REACHED", reserve.execute("source", buildJsonObject { put("ref", "main") }).errorCode)
        assertEquals(
            "VERIFICATION_RESERVE_REACHED",
            reserve.execute(
                "mixed",
                buildJsonObject {
                    put("view_cursor", "saved:0:1024")
                    put("ref", "main")
                }
            ).errorCode
        )
        assertEquals("VERIFICATION_RESERVE_REACHED", reserve.execute("malformed", buildJsonObject { put("view_cursor", "not-an-issued-cursor") }).errorCode)
        assertEquals(0, dispatches)
        assertFalse(reserve.execute("local-first", buildJsonObject { put("view_cursor", "saved:0:1024") }).isError)
        val continued = restored().bind(tool)
        assertFalse(continued.execute("local-next", buildJsonObject { put("view_cursor", "saved:1:1024") }).isError)
        val exhausted = restored().bind(tool).execute("local-extra", buildJsonObject { put("view_cursor", "saved:2:1024") })
        assertEquals("BUDGET_EXHAUSTED", exhausted.errorCode)
        assertFalse(exhausted.dispatched)
        assertEquals(2, dispatches)
        assertEquals(4, checkpoint.initialize(200))
    }

    @Test fun retainedSearchPageReserveStillRequiresCurrentAuthorizationBeforeCharging() = runTest {
        var dispatched = false
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(
                "github_search",
                "read",
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { put("view_cursor", buildJsonObject { put("type", "string") }) })
                }
            )
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched = true
                return AgentToolResult(callId, ToolResultContent.Text("retained bytes"), false)
            }
        }
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 4), initialCalls = 2)
        val denied = budget.bind(tool, authorize = { _, _ -> false }).execute("revoked", buildJsonObject { put("view_cursor", "saved:0:1024") })
        assertEquals("AUTH_REQUIRED", denied.errorCode)
        assertFalse(dispatched)
        assertEquals(2, budget.usedCalls())
    }

    @Test fun privacyRejectionIsUnsentTerminalAndNeverAnUnknownWrite() = runTest {
        var attempts = 0
        val tool = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 10)).bind(object : AgentTool {
            override val definition = AgentToolDefinition("github_write", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                attempts++
                throw dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected("PERSONAL_DATA_LOCAL_ONLY", "PERSONAL_DATA_LOCAL_ONLY: sharing blocked")
            }
        })
        for (id in listOf("first", "changed")) {
            val result = tool.execute(id, buildJsonObject { put("request", id) })
            assertFalse(result.dispatched)
            assertEquals("PERSONAL_DATA_LOCAL_ONLY", result.errorCode)
            assertFalse(result.errorCode in ToolResultEnvelope.unknownOutcomeCodes)
        }
        assertEquals(1, attempts)
    }

    @Test fun privateReviewReceivesTheOriginalReceiptBeforeSharedBudgetTruncation() = runTest {
        val original = "source line\n".repeat(10000) + "Important finding at the end"
        val evidence = dev.chungjungsoo.gptmobile.data.github.GitHubReviewEvidence()
        val tool = ToolExecutionBudget(AgentRunLimits(githubTask = true)).bind(object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text(original), false)
        })
        val args = buildJsonObject { put("path", "source.txt") }
        val delivered = withContext(ToolReceiptObserver(evidence::observe)) { tool.execute("read", args) }
        assertFalse(delivered.content.toString().contains("Important finding at the end"))
        val receipt = Json.parseToJsonElement(evidence.snapshot().single()).jsonObject
        assertEquals(JsonPrimitive(original), receipt["result"])
        assertEquals(args, receipt["arguments"])
        assertEquals(JsonPrimitive("github_read"), receipt["tool"])
        // Later delivery/replay excerpts must never overwrite the checked execution receipt.
        evidence.observe(ProviderEvent.ToolCall("read", "github_read", args), delivered)
        assertEquals(JsonPrimitive(original), Json.parseToJsonElement(evidence.snapshot().single()).jsonObject["result"])
    }

    @Test fun missingWritePathsGetSpecificRepairWithoutDispatchAndValidCorrectionCanProceed() = runTest {
        var dispatched = 0
        val tool = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 1)).bind(object : AgentTool {
            override val definition = GitHubOperationTool.definition("github_write", "github_write__github_api")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("staged"), false)
            }
        })
        fun arguments(path: String?) = buildJsonObject {
            put("branch", "work")
            put("expected_head_sha", "a".repeat(40))
            put("message", "Repair")
            put(
                "files",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            path?.let { put("path", it) }
                            put("content", "replacement")
                        }
                    )
                )
            )
        }
        val rejected = tool.execute("missing", arguments(null))
        assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
        assertFalse(rejected.dispatched)
        assertTrue(rejected.content.toString().contains("Each files item must contain its own non-empty repository-relative path"))
        assertTrue(rejected.content.toString().contains("do not guess"))
        assertEquals("INVALID_ARGUMENTS", tool.execute("empty", arguments("")).errorCode)
        assertEquals(0, dispatched)
        assertFalse(tool.execute("corrected", arguments("app/File.kt")).isError)
        assertEquals(1, dispatched)
    }

    @Test fun mismatchedWriteReceiptRequiresReconciliationBeforeAnotherCall() = runTest {
        var dispatched = 0
        val tool = ToolExecutionBudget(AgentRunLimits()).bind(object : AgentTool {
            override val definition = AgentToolDefinition("github_write__github_api", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult("another-call", ToolResultContent.Text("unrelated"), false, dispatched = false)
            }
        })
        val args = buildJsonObject { put("path", "file.txt") }
        val first = tool.execute("first", args)
        assertEquals("TOOL_RESULT_ID_MISMATCH", first.errorCode)
        assertTrue(first.dispatched)
        assertEquals(JsonPrimitive("unknown"), Json.parseToJsonElement(ToolResultEnvelope.encode("github_write", first)).jsonObject["execution"]!!.jsonObject["outcome"])
        val retry = tool.execute("second", args)
        assertEquals("RECONCILIATION_REQUIRED", retry.errorCode)
        assertFalse(retry.dispatched)
        assertEquals(JsonPrimitive(dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("call", "first")), retry.executionReceipt?.get("reconciliation_original_call_hash"))
        assertEquals(JsonPrimitive("TOOL_RESULT_ID_MISMATCH"), retry.executionReceipt?.get("reconciliation_original_code"))
        assertEquals(JsonPrimitive(true), retry.executionReceipt?.get("reconciliation_original_dispatched"))
        assertEquals(JsonPrimitive(false), retry.executionReceipt?.get("request_replay_allowed"))
        assertEquals(JsonPrimitive("unknown"), Json.parseToJsonElement(ToolResultEnvelope.encode("github_write", retry)).jsonObject["execution"]!!.jsonObject["outcome"])
        assertEquals(1, dispatched)
    }

    @Test fun aZeroGitHubOutputAllowanceDoesNotDispatch() = runTest {
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolOutputBytes = 0))
        val tool = budget.bind(object : AgentTool {
            override val definition = AgentToolDefinition("github_release", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Zero output allowance must stop before dispatch")
        })
        assertFalse(budget.canExecute())
        val result = tool.execute("blocked", JsonObject(emptyMap()))
        assertTrue(result.outputBudgetExhausted)
        assertFalse(result.dispatched)
    }

    @Test fun parallelGitHubPagesDoNotConsumeACumulative24KiBTurnBuffer() = runTest {
        var reads = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                reads++
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("body", "Release notes ".repeat(3000)) }), false)
            }
        }
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 12, maxToolOutputBytes = 24 * 1024))
        val tool = budget.bind(GitHubOperationTool("github_release", backend, "parallel-releases"))
        val first = (1..6).map { page ->
            async {
                tool.execute(
                    "release-$page",
                    buildJsonObject {
                        // Release inventories now preview long notes. Explicit
                        // details still deliver bounded full-content pages.
                        put("action", "detail")
                        put("tag_name", "v$page")
                    }
                )
            }
        }.awaitAll()
        assertEquals(6, reads)
        assertTrue(first.sumOf { it.content.toString().encodeToByteArray().size } > 24 * 1024)
        for (result in first) {
            assertFalse(result.isError)
            assertFalse(result.outputBudgetExhausted)
            assertEquals(null, result.toolResultBudgetLimitBytes)
            val next = (result.content as ToolResultContent.Json).value.jsonObject["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject
            val saved = tool.execute("saved-${result.callId}", next)
            assertFalse(saved.isError)
            assertFalse(saved.outputBudgetExhausted)
            val envelope = (saved.content as ToolResultContent.Json).value.jsonObject
            assertTrue(envelope.containsKey("fragments"))
            assertFalse(envelope.containsKey("replay_compacted"))
        }
        assertEquals(6, reads)
        val blocked = tool.execute("over-call-limit", buildJsonObject { put("action", "list") })
        assertTrue(blocked.toolCallBudgetExhausted)
        assertEquals(6, reads)
    }

    @Test fun issueDiscoveryIsAdmittedAndInventoryPagesCoverEveryAuthorizedTool() = runTest {
        val github = GitHubOperationTool.names.map { GitHubOperationTool.definition(it, it + "__github_api") }
        val extra = (1..30).map { AgentToolDefinition("other_$it", "Other authorized tool", JsonObject(emptyMap())) }
        val definitions = github + extra
        val catalog = ProgressiveToolCatalog(definitions, "List open issues")
        assertTrue(catalog.selected(definitions).any { it.name == "github_issue__github_api" })
        val seen = mutableSetOf<String>()
        var request = JsonObject(emptyMap())
        while (true) {
            val result = catalog.execute("inventory-${seen.size}", request)
            val page = (result.content as ToolResultContent.Json).value.jsonObject
            val inventory = page["authorized_inventory"] as JsonArray
            assertTrue(inventory.size <= 12)
            inventory.forEach { item ->
                assertEquals(JsonPrimitive("not tested"), item.jsonObject["operational_status"])
                assertTrue(seen.add(item.jsonObject["name"]!!.let { (it as JsonPrimitive).content }))
            }
            val next = page["continuation_call"] as? JsonObject ?: break
            request = next["arguments"]!!.jsonObject
        }
        assertEquals(definitions.map { it.name }.toSet(), seen)
        assertFalse(GitHubOperationTool.taskTools("My issue is that I cannot sleep").contains("github_issue"))
    }
}
