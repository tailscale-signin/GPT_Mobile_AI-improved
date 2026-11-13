package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolReceiptObserver
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStatusEvidenceRecoveryTest {
    private val sha = "97f10ff7a1f3789fb82d958b2dc6b22c8074dfdf"
    private val now = 1_000_000L
    private val tools = listOf("github_context", "github_pr").map { GitHubOperationTool.definition(it) }

    @Test fun normalizedRepositoryPreservesLargeChecksBeforeReplayAndCompletesStatus() = runTest {
        for (count in listOf(21, 22)) {
            for ((explicitUrl, compact) in listOf(false to false, false to true, true to false, true to true)) {
                val saved = linkedMapOf<String, JsonObject>()
                val book = GitHubEvidenceBook("task", "owner/repo", "main", tools.map { it.name }.toSet(), now = { now }, save = { id, value -> saved[id] = value })
                for ((id, receipt) in baseReceipts()) {
                    book.observe(
                        ProviderEvent.ToolCall(id, receipt.getValue("tool").jsonPrimitive.content, receipt.getValue("arguments").jsonObject),
                        AgentToolResult(id, ToolResultContent.Json(receipt.getValue("result")), false)
                    )
                }
                val original = checks(count, large = true)
                assertTrue(original.toString().encodeToByteArray().size > 64 * 1024)
                var dispatches = 0
                val backend = object : AgentTool {
                    override val definition = AgentToolDefinition("github", "fixture", JsonObject(emptyMap()))
                    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                        dispatches++
                        assertEquals(JsonPrimitive("owner"), arguments["owner"])
                        assertEquals(JsonPrimitive("repo"), arguments["repo"])
                        assertEquals(JsonPrimitive(sha), arguments["commit_sha"])
                        // A child receipt cannot borrow the parent's evidence permission.
                        currentCoroutineContext()[ToolReceiptObserver]?.observe?.invoke(
                            ProviderEvent.ToolCall("private-child", "github_context", arguments),
                            AgentToolResult("private-child", ToolResultContent.Json(buildJsonObject { put("secret", "private child evidence") }), false)
                        )
                        return AgentToolResult(callId, ToolResultContent.Json(original), false)
                    }
                }
                val limits = AgentRunLimits(githubTask = true, maxRounds = 2, maxToolCalls = 4)
                val bound = GitHubBoundRepositoryTool(
                    ToolExecutionBudget(limits).bind(GitHubOperationTool("github_context", backend, "status-$count-$explicitUrl-$compact")),
                    GitHubRepositoryContext("owner", "repo", "main")
                )
                val callable = if (explicitUrl) {
                    object : AgentTool {
                        override val definition = bound.definition
                        override val managesExecutionBudget = bound.managesExecutionBudget
                        override suspend fun execute(callId: String, arguments: JsonObject) = bound.prepareExecution(callId, arguments).invoke()
                    }
                } else {
                    bound
                }
                val arguments = buildJsonObject {
                    put("action", "checks")
                    put("commit_sha", sha)
                    if (explicitUrl) put("repo", "https://github.com/owner/repo")
                    if (compact) put("response_detail", "compact")
                }
                var rounds = 0
                val provider = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        if (rounds++ == 0) emit(ProviderEvent.ToolCall("checks", "github_context", arguments)) else emit(ProviderEvent.TextDelta("Check observations retained."))
                        emit(ProviderEvent.Completed)
                    }
                }
                val events = AgentRunner(limits, onToolResult = book::observe).run(provider, listOf(callable)).toList()
                assertEquals(1, dispatches)
                val finished = events.filterIsInstance<AgentRunEvent.ToolFinished>().single().result
                assertFalse(finished.isError)
                assertTrue(finished.content.toString().encodeToByteArray().size <= 64 * 1024)
                val (receipts, epoch) = book.snapshot()
                assertEquals(4, receipts.size)
                assertFalse(receipts.values.any { it["call_id"] == JsonPrimitive("private-child") })
                val retained = receipts.values.single { it["call_id"] == JsonPrimitive("checks") }
                val id = retained.getValue("receipt_id").jsonPrimitive.content
                assertEquals(original, saved.getValue(id)["result"])
                assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(original.toString())), retained["result_sha256"])
                assertEquals(JsonPrimitive("owner"), retained.getValue("arguments").jsonObject["owner"])
                assertEquals(JsonPrimitive("repo"), retained.getValue("arguments").jsonObject["repo"])
                assertTrue(retained.toString().encodeToByteArray().size < 64 * 1024)
                val projectedRows = retained.getValue("result").jsonObject.getValue("check_details").jsonObject.getValue("check_runs").jsonArray
                assertEquals(count, projectedRows.size)
                for (row in projectedRows.map { it.jsonObject }) {
                    assertEquals(JsonPrimitive(sha), row["head_sha"])
                    assertFalse(row.containsKey("html_url"))
                    assertFalse(row.containsKey("failure_summary"))
                    assertEquals(setOf("id"), row.getValue("app").jsonObject.keys)
                }
                val (text, report) = requireNotNull(GitHubEvidencePolicy.status(receipts, now, epoch))
                assertEquals(JsonPrimitive(true), report["completion_verified"])
                assertTrue(text.contains("15 success"))
                assertTrue(text.contains("${count - 15} skipped"))
                assertFalse(text.contains("Unavailable observations"))
                assertEquals(null, GitHubStatusRequest.nextCall(tools, receipts, now, epoch))
            }
        }
    }

    @Test fun savedTruncatedChecksGetOnePinnedRecoveryAndCannotLoop() {
        val saved = baseReceipts().toMutableMap()
        saved["truncated"] = receipt("checks", truncated())
        val recovery = requireNotNull(GitHubStatusRequest.nextCall(tools, saved, now, 0))
        assertEquals("github_context", recovery.name)
        assertEquals(JsonPrimitive("checks"), recovery.arguments["action"])
        assertEquals(JsonPrimitive(sha), recovery.arguments["commit_sha"])
        assertEquals(JsonPrimitive("compact"), recovery.arguments["response_detail"])
        assertTrue(recovery.callId.endsWith("-recovery"))
        assertTrue(ToolArgumentValidator.errors(recovery.arguments, tools.first().inputSchema).isEmpty())
        saved["still-truncated"] = receipt("checks", truncated())
        assertEquals(null, GitHubStatusRequest.nextCall(tools, saved, now, 0))
        assertEquals(JsonPrimitive(false), requireNotNull(GitHubEvidencePolicy.status(saved, now, 0)).second["completion_verified"])
    }

    @Test fun recoveredChecksStillRequireTheCurrentHeadAndFreshAuthorizedEvidence() {
        val good = receipt("checks", checks())
        assertEquals(JsonPrimitive(true), requireNotNull(GitHubEvidencePolicy.status(baseReceipts() + ("checks" to good), now, 0)).second["completion_verified"])
        val variants = listOf(
            JsonObject(good + ("trusted" to JsonPrimitive(false))),
            JsonObject(good + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(good + ("observed_at_ms" to JsonPrimitive(0))),
            JsonObject(good + ("epoch" to JsonPrimitive(1))),
            JsonObject(good + ("result" to JsonObject(checks() + ("commit_sha" to JsonPrimitive("b".repeat(40))))))
        )
        for (invalid in variants) {
            val report = requireNotNull(GitHubEvidencePolicy.status(baseReceipts() + ("checks" to invalid), now, 0)).second
            assertEquals(JsonPrimitive(false), report["completion_verified"])
            assertEquals(JsonPrimitive("PARTIAL"), report["task_outcome"])
        }
    }

    @Test fun successfulSourceRecoveryCompletesTheSavedStatusWithoutRepeatingOtherReads() {
        val saved = baseReceipts() + ("truncated" to receipt("checks", truncated()))
        assertEquals(JsonPrimitive(false), requireNotNull(GitHubEvidencePolicy.status(saved, now, 0)).second["completion_verified"])
        val recovery = requireNotNull(GitHubStatusRequest.nextCall(tools, saved, now, 0))
        val recovered = saved + (recovery.callId to JsonObject(receipt("checks", checks()) + ("arguments" to recovery.arguments)))
        assertEquals(5, recovered.size)
        assertEquals(saved.getValue("truncated"), recovered["truncated"])
        assertEquals(JsonPrimitive(true), requireNotNull(GitHubEvidencePolicy.status(recovered, now, 0)).second["completion_verified"])
        assertEquals(null, GitHubStatusRequest.nextCall(tools, recovered, now, 0))
    }

    @Test fun incompleteDetailProjectionDoesNotPresentItsSurvivingRowsAsAllCheckOutcomes() {
        val original = checks()
        val details = original.getValue("check_details").jsonObject
        val rows = details.getValue("check_runs").jsonArray
        val incompleteRows = listOf(
            JsonArray(rows.take(2)),
            JsonArray(rows.mapIndexed { index, row -> if (index == 0) JsonObject(row.jsonObject - "conclusion") else row })
        )
        for (incomplete in incompleteRows) {
            val partial = JsonObject(original + ("check_details" to JsonObject(details + ("check_runs" to incomplete))))
            val text = requireNotNull(GitHubEvidencePolicy.status(baseReceipts() + ("checks" to receipt("checks", partial)), now, 0)).first
            assertFalse(text.contains("Observed check outcomes"))
        }
    }

    private fun baseReceipts(): Map<String, JsonObject> = linkedMapOf(
        "status" to receipt(
            "status",
            buildJsonObject {
                put(
                    "data",
                    buildJsonObject {
                        put(
                            "repository",
                            buildJsonObject {
                                put("full_name", "owner/repo")
                                put("default_branch", "main")
                            }
                        )
                        put("branch", "main")
                        put("commit_sha", sha)
                        put(
                            "latest_workflow",
                            buildJsonObject {
                                put("name", "CI")
                                put("status", "completed")
                                put("conclusion", "success")
                                put("head_branch", "main")
                                put("head_sha", sha)
                            }
                        )
                    }
                )
            }
        ),
        "commits" to receipt(
            "commits",
            buildJsonObject {
                put("commit_sha", sha)
                put("items", JsonArray(emptyList()))
            }
        ),
        "prs" to receipt("list", buildJsonObject { put("items", JsonArray(emptyList())) }, "github_pr")
    )

    private fun receipt(action: String, result: JsonObject, tool: String = "github_context") = buildJsonObject {
        put("tool", tool)
        put("success", true)
        put("trusted", true)
        put("verification_eligible", true)
        put("observed_at_ms", now)
        put("epoch", 0)
        put(
            "arguments",
            buildJsonObject {
                put("action", action)
                if (action in setOf("checks", "commits")) put("commit_sha", sha)
            }
        )
        put("result", result)
    }

    private fun truncated() = buildJsonObject {
        put("observationTruncated", true)
        put(
            "excerpt",
            buildJsonObject {
                put("checks", 21)
                put("commit_sha", sha)
            }
        )
    }

    private fun checks(count: Int = 21, large: Boolean = false) = buildJsonObject {
        put("commit_sha", sha)
        put("state", "success")
        put("checks", count)
        put("statuses", 0)
        put(
            "check_details",
            buildJsonObject {
                put("total_count", count)
                put(
                    "check_runs",
                    JsonArray(
                        List(count) { index ->
                            buildJsonObject {
                                put("id", index + 1)
                                put("name", "Check $index")
                                put("head_sha", sha)
                                put("status", "completed")
                                put("conclusion", if (index < 15) "success" else "skipped")
                                put("details_url", "https://github.com/owner/repo/actions/runs/${index + 1}")
                                put(
                                    "app",
                                    buildJsonObject {
                                        put("id", 10)
                                        if (large) put("description", "Repeated producer metadata. ".repeat(200))
                                    }
                                )
                            }
                        }
                    )
                )
            }
        )
        put(
            "status_details",
            buildJsonObject {
                put("total_count", 0)
                put("statuses", JsonArray(emptyList()))
            }
        )
    }
}
