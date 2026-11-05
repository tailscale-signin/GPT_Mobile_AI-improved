package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSchemaRepairTest {
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun json(result: AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject
    private val sha = "a".repeat(40)

    private class Backend : AgentTool {
        val calls = mutableListOf<JsonObject>()
        var unknown = false
        override val definition = AgentToolDefinition("github", "fixture", buildJsonObject { put("type", "object") })
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            calls += arguments
            return if (unknown) {
                AgentToolResult(callId, ToolResultContent.Text("Receipt lost"), true, errorCode = "DISPATCH_OUTCOME_UNKNOWN", dispatched = true)
            } else {
                AgentToolResult(callId, ToolResultContent.Json(argsBody), false)
            }
        }
        private val argsBody = Json.parseToJsonElement("""{"items":[{"number":321,"head_sha":"${"a".repeat(40)}"}],"has_more":false}""")
    }

    @Test fun failedReadReportsTheMatchingActionBranchAndOffersAnExactDiscoveryCall() = runBlocking {
        val backend = Backend()
        val native = GitHubOperationTool("github_pr", backend, "schema-repair", modelName = "github_pr__selected")
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true))
        val tool = budget.bind(native)
        val request = args("""{"action":"headers","owner":"owner","repo":"repo"}""")
        val rejected = tool.execute("invalid", request)
        val text = json(rejected)["validation_errors"].toString()
        assertTrue(text.contains("pull_number"))
        assertTrue(text.contains("anyOf["))
        assertFalse(text.contains("view_cursor"))
        assertEquals("PRECHECK_REJECTED", ToolResultEnvelope.executionState(rejected))
        assertFalse(rejected.dispatched)
        assertEquals(0, budget.usedCalls())
        val repeated = tool.execute("same-invalid", request)
        assertTrue(repeated.sharedResult)
        assertEquals(rejected.content, repeated.content)
        assertTrue(backend.calls.isEmpty())
        val proposal = json(rejected).getValue("correction_call").jsonObject
        assertEquals(JsonPrimitive("github_pr__selected"), proposal["tool"])
        val correction = proposal.getValue("arguments").jsonObject
        assertEquals(args("""{"action":"list","owner":"owner","repo":"repo"}"""), correction)
        assertTrue(ToolArgumentValidator.errors(correction, tool.definition.inputSchema).isEmpty())
        assertFalse(tool.execute("discover", correction).isError)
        assertFalse(tool.execute("inspect", args("""{"action":"headers","pull_number":321,"owner":"owner","repo":"repo"}""")).isError)
        assertEquals(2, backend.calls.size)
        assertEquals(JsonPrimitive(321), backend.calls.last()["pull_number"])
    }

    @Test fun omittedActionReportsDefaultContextPrerequisite() {
        val schema = GitHubOperationTool("github_pr", Backend(), "defaults").definition.inputSchema
        val errors = ToolArgumentValidator.errors(args("{}"), schema)
        assertTrue(errors.any { "pull_number" in it })
        assertFalse(errors.any { "view_cursor" in it })
    }

    @Test fun createReportsItsMissingBaseAndNeverInventsAWrite() = runBlocking {
        val tool = GitHubOperationTool("github_pr", Backend(), "create-contract")
        val result = tool.execute("create", args("""{"action":"create","title":"Fix","head":"work"}"""))
        assertFalse(result.dispatched)
        val errors = json(result).getValue("validation_errors").toString()
        assertTrue(errors.contains("$.base"))
        assertFalse(errors.contains("pull_number"))
        assertFalse("correction_call" in json(result))
    }

    @Test fun aReadAliasCorrectionPreservesTheObservedNumberAndRepository() = runBlocking {
        val tool = GitHubOperationTool("github_pr", Backend(), "alias-contract")
        val result = tool.execute("alias", args("""{"action":"context","pr_number":321,"owner":"owner","repo":"repo"}"""))
        val proposal = json(result).getValue("correction_call").jsonObject.getValue("arguments").jsonObject
        assertEquals(args("""{"action":"context","pull_number":321,"owner":"owner","repo":"repo"}"""), proposal)
        assertTrue(ToolArgumentValidator.errors(proposal, tool.definition.inputSchema).isEmpty())
        assertFalse(result.dispatched)
    }

    @Test fun unknownMergeOutcomeIsStillFencedAndNeverTreatedAsAnArgumentCorrection() = runBlocking {
        val backend = Backend().apply { unknown = true }
        val tool = ToolExecutionBudget(AgentRunLimits(githubTask = true)).bind(GitHubOperationTool("github_pr", backend, "unknown-contract"))
        val request = args("""{"action":"merge","pull_number":321,"expected_head_sha":"$sha"}""")
        val first = tool.execute("merge", request)
        assertEquals("DISPATCH_OUTCOME_UNKNOWN", first.errorCode)
        val repeated = tool.execute("merge-again", request)
        assertEquals("RECONCILIATION_REQUIRED", repeated.errorCode)
        assertFalse(repeated.dispatched)
        assertEquals(1, backend.calls.size)
    }

    @Test fun nestedAlternativesExposeRequiredFieldsWithoutRepeatingTheLargeCatalog() {
        val schema = args("""{"type":"object","properties":{"files":{"type":"array","items":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"delete":{"type":"boolean"}},"required":["path"],"oneOf":[{"required":["content"]},{"required":["delete"]}]}}}}""")
        val errors = ToolArgumentValidator.errors(args("""{"files":[{"path":"src/Target.kt"}]}"""), schema)
        assertTrue(errors.any { "$.files[0].content" in it })
        assertTrue(errors.any { "$.files[0].delete" in it })
        val message = ToolArgumentValidator.repairMessage("github_write", errors, schema)
        assertFalse(message.contains("Tool schema:"))
        assertTrue(message.length < 2000)
    }
}
