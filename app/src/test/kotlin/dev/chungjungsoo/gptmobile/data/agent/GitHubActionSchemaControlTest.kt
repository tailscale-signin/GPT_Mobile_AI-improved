package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.github.GitHubContentFixture
import dev.chungjungsoo.gptmobile.data.github.GitHubOwnedFixtures
import dev.chungjungsoo.gptmobile.data.github.GitHubReviewComparison
import dev.chungjungsoo.gptmobile.data.github.GitHubSelfTestReads
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubActionSchemaControlTest {
    private val head = "a".repeat(40)
    private val ci = definition("github_ci")
    private val context = definition("github_context")
    private fun definition(name: String) = GitHubOperationTool.definition(name, name + "__selected")
    private fun projected(tool: AgentToolDefinition, action: String) = requireNotNull(GitHubActionSchema.project(tool, action))
    private fun arguments(action: String) = buildJsonObject { put("action", action) }
    private fun row(id: Long) = buildJsonObject {
        put("id", id)
        put("run_id", 71)
        put("run_attempt", 2)
        put("head_sha", head)
        put("status", "completed")
        put("conclusion", "failure")
    }
    private fun receipt(call: ProviderEvent.ToolCall, result: JsonObject, success: Boolean = true) = buildJsonObject {
        put("receipt_id", call.callId)
        put("call_id", call.callId)
        put("binding", "task")
        put("repository", "owner/repo")
        put("trusted", true)
        put("success", success)
        put("dispatched", true)
        put("evidence_origin", "LIVE")
        put("tool", call.name)
        put("arguments", call.arguments)
        put("result", result)
    }
    private fun source() = receipt(
        ProviderEvent.ToolCall("source", context.name, arguments("status")),
        buildJsonObject {
            put("commit_sha", head)
            put("branch", "main")
        }
    )

    @Test fun selectedCiActionsPreserveTheActualPinnedRecoverySequence() {
        val plan = GitHubCiInvestigationRecovery("Diagnose CI failures")
        val scope = buildJsonObject {
            put("owner", "owner")
            put("repo", "repo")
            put("selected_ref", "main")
        }
        fun observe(call: ProviderEvent.ToolCall, data: JsonObject) {
            plan.observe(
                AgentToolExchange(
                    listOf(call),
                    listOf(AgentToolResult(call.callId, ToolResultContent.Json(data), false, executionReceipt = buildJsonObject { put("resource_context", scope) }))
                )
            )
        }
        fun next(action: String): ProviderEvent.ToolCall {
            val selected = projected(ci, action)
            val call = plan.nextCall(listOf(context, selected))
            assertNotNull("The selected $action contract rejected its existing revision-pinned producer", call)
            return requireNotNull(call).also {
                assertEquals(ci.name, it.name)
                assertEquals(JsonPrimitive(action), it.arguments["action"])
                assertEquals(JsonPrimitive("owner"), it.arguments["owner"])
                assertEquals(JsonPrimitive("repo"), it.arguments["repo"])
                assertTrue(ToolArgumentValidator.errors(it.arguments, ci.inputSchema).isEmpty())
                assertTrue(ToolArgumentValidator.errors(it.arguments, selected.inputSchema).isEmpty())
            }
        }
        observe(ProviderEvent.ToolCall("workflows", ci.name, arguments("workflows")), buildJsonObject { put("total_count", 1) })
        assertTrue(plan.activate(listOf(context, ci)))
        observe(requireNotNull(plan.nextCall(listOf(context, ci))), buildJsonObject { put("commit_sha", head) })
        val runs = next("runs")
        assertEquals(JsonPrimitive(head), runs.arguments["commit_sha"])
        observe(runs, buildJsonObject { put("workflow_runs", JsonArray(listOf(row(71)))) })
        val jobs = next("jobs")
        assertEquals(JsonPrimitive(head), jobs.arguments["expected_head_sha"])
        assertEquals(JsonPrimitive(2), jobs.arguments["run_attempt"])
        observe(
            jobs,
            buildJsonObject {
                put("run_id", 71)
                put("run_attempt", 2)
                put("head_sha", head)
                put("jobs", JsonArray(listOf(row(83))))
            }
        )
        val logs = next("logs")
        assertEquals(JsonPrimitive(head), logs.arguments["expected_head_sha"])
        assertEquals(JsonPrimitive(71), logs.arguments["run_id"])
        assertEquals(JsonPrimitive(83), logs.arguments["job_id"])
        assertEquals(JsonPrimitive(2), logs.arguments["run_attempt"])
        assertNull(plan.nextCall(listOf(context, projected(ci, "logs").copy(name = "github_ci__different"))))
        assertNull(plan.nextCall(listOf(context, projected(ci, "logs").copy(modelVisible = false))))
        observe(logs, buildJsonObject { put("total_lines", 400) })
        val tail = next("logs")
        assertEquals(JsonPrimitive(201), tail.arguments["start_line"])
        assertEquals(JsonPrimitive(400), tail.arguments["end_line"])
        assertEquals(logs.arguments - setOf("start_line", "end_line"), tail.arguments - setOf("start_line", "end_line"))
        observe(tail, buildJsonObject { put("total_lines", 400) })
        assertNull(plan.nextCall(listOf(context, projected(ci, "logs"))))
    }

    @Test fun selectedCiActionsAdmitTheExistingSelfTestRevisionAndAttemptControls() {
        val source = source()
        val runs = receipt(
            ProviderEvent.ToolCall(
                "runs",
                ci.name,
                buildJsonObject {
                    put("action", "runs")
                    put("commit_sha", head)
                }
            ),
            buildJsonObject { put("workflow_runs", JsonArray(listOf(row(71)))) }
        )
        val jobs = receipt(
            ProviderEvent.ToolCall(
                "jobs",
                ci.name,
                buildJsonObject {
                    put("action", "jobs")
                    put("run_id", 71)
                    put("run_attempt", 2)
                    put("expected_head_sha", head)
                }
            ),
            buildJsonObject {
                put("run_id", 71)
                put("run_attempt", 2)
                put("head_sha", head)
                put("jobs", JsonArray(listOf(row(83))))
            }
        )
        for (action in listOf("run", "status", "artifacts", "jobs", "failure", "job", "logs")) {
            val observations = if (action in setOf("job", "logs")) listOf(source, runs, jobs) else listOf(source, runs)
            val expected = GitHubSelfTestReads.available(listOf(ci), observations).single { it.arguments["action"] == JsonPrimitive(action) }
            val selected = projected(ci, action)
            val actual = GitHubSelfTestReads.available(listOf(selected), observations).singleOrNull { it.arguments["action"] == JsonPrimitive(action) }
            assertNotNull("The selected $action contract lost the actual self-test recipe", actual)
            assertEquals(expected.name, actual?.name)
            assertEquals(expected.arguments, actual?.arguments)
            assertEquals(JsonPrimitive(head), actual?.arguments?.get("expected_head_sha"))
            assertTrue(ToolArgumentValidator.errors(expected.arguments, selected.inputSchema).isEmpty())
            assertTrue(ToolArgumentValidator.errors(JsonObject(expected.arguments + ("action" to JsonPrimitive("dispatch"))), selected.inputSchema).isNotEmpty())
        }
    }

    @Test fun projectedFixtureCallsRetainOwnershipWorkspaceSelectionAndUnknownWriteRecovery() {
        val tools = listOf("github_context", "github_commit", "github_write", "github_stage_patch", "github_read", "github_search").map(::definition)
        val source = source()
        val receipts = linkedMapOf("source" to source)
        val create = requireNotNull(GitHubContentFixture.next(source, null, tools, receipts, allowCreate = true, cleanupOnly = false, abortIncomplete = false))
        assertEquals(JsonPrimitive(false), create.arguments["select_created_branch"])
        assertEquals(JsonPrimitive(GitHubOwnedFixtures.PLAN), create.arguments["fixture_plan"])
        val branchContract = projected(definition("github_commit"), "branch")
        assertTrue(ToolArgumentValidator.errors(create.arguments, branchContract.inputSchema).isEmpty())
        assertTrue(ToolArgumentValidator.errors(JsonObject(create.arguments + ("fixture_plan" to JsonPrimitive("unowned"))), branchContract.inputSchema).isNotEmpty())
        val created = receipt(
            create,
            buildJsonObject {
                put("branch", create.arguments.getValue("branch"))
                put("head_sha", head)
                put("created", true)
                put("receipt_verified", true)
                put("ownership_verified", true)
            }
        )
        receipts[create.callId] = created
        val cleanup = requireNotNull(GitHubContentFixture.next(source, created, tools, receipts, allowCreate = false, cleanupOnly = true, abortIncomplete = true))
        assertEquals(JsonPrimitive("delete_fixture_branch"), cleanup.arguments["action"])
        receipts[cleanup.callId] = JsonObject(receipt(cleanup, JsonObject(emptyMap()), success = false) + ("error_code" to JsonPrimitive("DISPATCH_OUTCOME_UNKNOWN")))
        val recoveryTools = tools.map { if (it.name == context.name) projected(it, "recovery") else it }
        val recovery = GitHubContentFixture.next(source, created, recoveryTools, receipts, allowCreate = false, cleanupOnly = true, abortIncomplete = true)
        assertNotNull("The selected recovery contract must reconcile the unknown cleanup without repeating it", recovery)
        assertEquals(context.name, recovery?.name)
        assertEquals(JsonPrimitive("recovery"), recovery?.arguments?.get("action"))
        assertEquals(create.arguments["branch"], recovery?.arguments?.get("branch"))
        assertEquals(JsonPrimitive(GitHubOwnedFixtures.PLAN), recovery?.arguments?.get("fixture_plan"))
        assertEquals(JsonPrimitive(head), recovery?.arguments?.get("base_sha"))
        assertEquals(JsonPrimitive(head), recovery?.arguments?.get("commit_sha"))
        assertEquals(JsonPrimitive(true), recovery?.arguments?.get("verify_live"))
        assertFalse(recovery?.arguments?.get("action") == cleanup.arguments["action"])
    }

    @Test fun projectedComparisonKeepsTheActualPinnedPatchEvidenceRequest() {
        val base = "b".repeat(40)
        val task = "Review the selected branch\nBaseline: $base"
        val receipts = mapOf("source" to source())
        val expected = requireNotNull(GitHubReviewComparison.plan(task, listOf(context), receipts)?.nextCall)
        val selected = projected(context, "changes")
        val actual = GitHubReviewComparison.plan(task, listOf(selected), receipts)?.nextCall
        assertNotNull("The selected comparison contract must retain the producer's patch evidence request", actual)
        assertEquals(expected, actual)
        assertEquals(JsonPrimitive(base), actual?.arguments?.get("base_sha"))
        assertEquals(JsonPrimitive(head), actual?.arguments?.get("commit_sha"))
        assertEquals(JsonPrimitive(true), actual?.arguments?.get("include_patches"))
        assertEquals(context.name, actual?.name)
        assertTrue(ToolArgumentValidator.errors(requireNotNull(actual).arguments, selected.inputSchema).isEmpty())
    }
}
