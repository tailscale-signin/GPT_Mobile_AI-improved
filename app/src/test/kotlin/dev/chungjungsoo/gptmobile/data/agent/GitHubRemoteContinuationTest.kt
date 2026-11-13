package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRemoteContinuationTest {
    @Test fun aSharedPayloadPreservesProvenCurrentPhysicalReads() {
        val shared = AgentToolResult(
            "same",
            ToolResultContent.Text("saved"),
            false,
            sharedResult = true,
            executionReceipt = buildJsonObject {
                put("read_requests", 3)
                put("read_response_observations", 3)
                put("read_source", "MIXED")
                put(
                    "tool_trace",
                    buildJsonObject {
                        put("call_id", "same")
                        put("dispatched", true)
                        put("remote_read_requests", 3)
                    }
                )
            }
        )
        assertEquals(shared, currentGitHubInvocationReceipt(shared))
    }

    @Test fun aNewWrapperTracePreservesCurrentMixedReadAccounting() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "fixture", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId,
                ToolResultContent.Json(buildJsonObject { put("workflows", JsonArray(emptyList())) }),
                false,
                dispatched = true,
                sharedResult = true,
                executionReceipt = buildJsonObject {
                    put("read_requests", 1)
                    put("read_response_observations", 1)
                    put("cached_read_observations", 1)
                    put("read_source", "MIXED")
                }
            )
        }
        val result = GitHubOperationTool("github_ci", backend, "mixed").execute("current", JsonObject(emptyMap()))
        val normalized = currentGitHubInvocationReceipt(result)
        assertTrue(normalized.dispatched)
        assertEquals(JsonPrimitive(1), normalized.executionReceipt!!["read_requests"])
        assertEquals(JsonPrimitive(1), normalized.executionReceipt!!["tool_trace"]!!.jsonObject["remote_read_requests"])
    }

    @Test fun fiftyFiveWorkflowsUseTwoIssuedPagesWithoutRepeatingTheFirst() = runBlocking {
        val pages = mutableListOf<Int>()
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "fixture", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                val page = arguments["page"]?.jsonPrimitive?.int ?: 1
                pages += page
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("has_more", page == 1)
                            put("total_count", 55)
                            put("workflows", JsonArray((if (page == 1) 1..30 else 31..55).map { buildJsonObject { put("id", it) } }))
                            if (page == 1) {
                                put(
                                    "continuation",
                                    buildJsonObject {
                                        put("action", "list_workflows")
                                        put("page", 2)
                                        put("per_page", 30)
                                    }
                                )
                            }
                        }
                    ),
                    false
                )
            }
        }
        val tool = GitHubOperationTool("github_ci", backend, "fifty-five", "github_ci__selected")
        val firstCall = ProviderEvent.ToolCall("first", tool.definition.name, JsonObject(emptyMap()))
        val first = tool.execute(firstCall.callId, firstCall.arguments)
        val exchanges = mutableListOf(AgentToolExchange(listOf(firstCall), listOf(first)))
        val state = GitHubReplayRecovery.State(listOf(firstCall))
        val next = requireNotNull(GitHubReplayRecovery.nextRemoteRead(exchanges, listOf(tool.definition), state))
        assertEquals(JsonPrimitive("workflows"), next.arguments["action"])
        assertEquals(JsonPrimitive(2), next.arguments["page"])
        val last = tool.execute(next.callId, next.arguments)
        state.record(listOf(next))
        exchanges += AgentToolExchange(listOf(next), listOf(last))
        assertEquals(listOf(1, 2), pages)
        val progress = requireNotNull(last.executionReceipt)["pagination_progress"]!!.jsonObject
        assertEquals(JsonPrimitive(55), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(true), progress["complete"])
        assertNull(GitHubReplayRecovery.nextRemoteRead(exchanges, listOf(tool.definition), state))
        assertEquals(JsonPrimitive("workflows"), first.executionReceipt!!["tool_trace"]!!.jsonObject["action"])
    }

    @Test fun reusedReceiptsHaveAFreshCallIdentityAndRetainOriginalObservationProvenance() {
        val original = AgentToolResult(
            "old",
            ToolResultContent.Text("saved"),
            false,
            executionReceipt = buildJsonObject {
                put("read_requests", 1)
                put(
                    "tool_trace",
                    buildJsonObject {
                        put("call_id", "old")
                        put("tool", "github_ci")
                        put("dispatched", true)
                    }
                )
            }
        )
        val reused = AgentContractValidation.receipt("new", original.copy(callId = "new", sharedResult = true))
        val receipt = requireNotNull(reused.executionReceipt)
        assertFalse(reused.dispatched)
        assertEquals(JsonPrimitive("new"), receipt["tool_trace"]!!.jsonObject["call_id"])
        assertEquals(JsonPrimitive("old"), receipt["source_observation_call_id"])
        assertEquals(JsonPrimitive(0), receipt["read_requests"])
        assertEquals(JsonPrimitive("old"), receipt["source_observation_trace"]!!.jsonObject["call_id"])
        assertEquals(JsonPrimitive(1), receipt["source_observation_read_requests"])
        assertEquals(reused, currentGitHubInvocationReceipt(reused))
    }

    @Test fun remoteContinuationsRejectChangedScopesMutationActionsAndUnissuedPages() {
        val original = workflowArguments()
        val invalid = listOf(
            workflowArguments(page = 3),
            JsonObject(workflowArguments(page = 2) + ("owner" to JsonPrimitive("different"))),
            workflowArguments(repo = "different", page = 2),
            JsonObject(workflowArguments(page = 2) + ("action" to JsonPrimitive("dispatch"))),
            JsonObject(workflowArguments(page = 2) + ("per_page" to JsonPrimitive(100))),
            JsonObject(workflowArguments(page = 2) + ("view_cursor" to JsonPrimitive("invented:1:1024")))
        )
        for (arguments in invalid) {
            assertNull(arguments.toString(), GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(original, arguments)), listOf(ciDefinition)))
        }
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(original, workflowArguments(page = 2), recipeTool = "github_ci__other")), listOf(ciDefinition)))
        val failed = issuedPage(original, workflowArguments(page = 2)).let { it.copy(results = it.results.map { result -> result.copy(isError = true) }) }
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(failed), listOf(ciDefinition)))
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(original, workflowArguments(page = 2))), listOf(ciDefinition.copy(modelVisible = false))))
    }

    @Test fun repositoryDefaultsMustComeFromTheOwningWorkflowReceipt() {
        val original = buildJsonObject { put("action", "workflows") }
        val next = JsonObject(workflowArguments(page = 2) + ("ref" to JsonPrimitive("main")))
        val receipt = buildJsonObject {
            put("schema", "github.dispatch.v1")
            put(
                "resolved_arguments",
                buildJsonObject {
                    put("action", "list_workflows")
                    put("owner", "Owner")
                    put("repo", "Repo")
                }
            )
            put(
                "resource_context",
                buildJsonObject {
                    put("owner", "owner")
                    put("repo", "repo")
                    put("selected_ref", "main")
                }
            )
        }
        fun exchange(arguments: JsonObject) = issuedPage(original, arguments).let { batch ->
            batch.copy(results = batch.results.map { it.copy(executionReceipt = receipt) })
        }
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(original, next)), listOf(ciDefinition)))
        assertEquals(next, GitHubReplayRecovery.nextRemoteRead(listOf(exchange(next)), listOf(ciDefinition))?.arguments)
        for (key in listOf("owner", "repo", "ref")) {
            val changed = JsonObject(next + (key to JsonPrimitive("different")))
            assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(exchange(changed)), listOf(ciDefinition)))
        }
    }

    @Test fun consumedDefaultPageSizeRemainsConsumedAfterReplayEviction() {
        val consumed = ProviderEvent.ToolCall("already-read", ciDefinition.name, JsonObject(workflowArguments(page = 2) - "per_page"))
        val state = GitHubReplayRecovery.State(listOf(consumed))
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(workflowArguments(), workflowArguments(page = 2))), listOf(ciDefinition), state))
    }

    @Test fun differentScopesHaveDistinctAutomaticIdsAndShareTheDurableThirtyTwoReadBudget() {
        val state = GitHubReplayRecovery.State()
        val ids = mutableSetOf<String>()
        repeat(32) { index ->
            // Only the latest issuer remains in replay; earlier calls remain in State.
            val exchange = issuedPage(workflowArguments(repo = "repo-$index"), workflowArguments(repo = "repo-$index", page = 2))
            val call = requireNotNull(GitHubReplayRecovery.nextRemoteRead(listOf(exchange), listOf(ciDefinition), state))
            ids += call.callId
            state.record(listOf(call))
        }
        assertEquals(32, ids.size)
        assertTrue(state.exhausted())
        assertNull(GitHubReplayRecovery.nextRemoteRead(listOf(issuedPage(workflowArguments(repo = "next"), workflowArguments(repo = "next", page = 2))), listOf(ciDefinition), state))
    }

    private val ciDefinition = GitHubOperationTool.definition("github_ci", "github_ci__selected")

    private fun workflowArguments(repo: String = "repo", page: Int = 1) = buildJsonObject {
        put("action", "workflows")
        put("owner", "owner")
        put("repo", repo)
        put("page", page)
        put("per_page", 30)
    }

    private fun issuedPage(original: JsonObject, next: JsonObject, recipeTool: String = ciDefinition.name): AgentToolExchange {
        val call = ProviderEvent.ToolCall("issuer", ciDefinition.name, original)
        val result = AgentToolResult(
            call.callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.operation.v1")
                    put(
                        "continuation_call",
                        buildJsonObject {
                            put("tool", recipeTool)
                            put("arguments", next)
                        }
                    )
                }
            ),
            false
        )
        return AgentToolExchange(listOf(call), listOf(result))
    }
}
