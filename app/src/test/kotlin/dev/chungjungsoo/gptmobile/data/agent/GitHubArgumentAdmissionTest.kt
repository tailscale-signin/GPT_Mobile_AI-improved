package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubArgumentAdmissionTest {
    @Test fun fullAndProjectedRangeContractsRejectConflictingLimitsBeforeSpendingACall() = runBlocking {
        for (projected in listOf(false, true)) {
            val backend = RecordingBackend()
            val native = GitHubOperationTool("github_read", backend, "range-contract", "github_read__selected")
            val definition = if (projected) requireNotNull(GitHubActionSchema.project(native.definition, "ranges")) else native.definition
            val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 1))
            val tool = budget.bindPresented(native, definition)
            for (end in listOf(-2, 20)) {
                val invalid = args("""{"action":"ranges","ranges":[{"path":"README.md","start_line":1,"end_line":$end,"max_lines":20}]}""")
                assertTrue(ToolArgumentValidator.errors(invalid, definition.inputSchema).isNotEmpty())
                val result = tool.execute("invalid-$end", invalid)
                assertEquals("INVALID_ARGUMENTS", result.errorCode)
                assertFalse(result.dispatched)
                assertFalse(result.toolCallBudgetExhausted)
                assertEquals(0, budget.usedCalls())
                assertTrue(backend.calls.isEmpty())
            }
            val corrected = args("""{"action":"ranges","ranges":[{"path":"README.md","start_line":1,"max_lines":20}]}""")
            val result = tool.execute("corrected", corrected)
            assertFalse(result.isError)
            assertTrue(result.dispatched)
            assertEquals(1, budget.usedCalls())
            assertEquals(corrected["ranges"], backend.calls.single()["ranges"])
        }
    }

    @Test fun fullAndProjectedRangesPreserveDefaultsEofAndInclusiveLimits() = runBlocking {
        val ranges = listOf(
            """{"path":"README.md"}""",
            """{"path":"README.md","start_line":20,"end_line":-1}""",
            """{"path":"README.md","start_line":2001,"end_line":4000}""",
            """{"path":"README.md","start_line":20,"max_lines":2000,"start_offset":3}"""
        )
        for (projected in listOf(false, true)) {
            for (range in ranges) {
                val backend = RecordingBackend()
                val native = GitHubOperationTool("github_read", backend, "range-defaults", "github_read__selected")
                val definition = if (projected) requireNotNull(GitHubActionSchema.project(native.definition, "ranges")) else native.definition
                val input = args("""{"action":"ranges","ranges":[$range]}""")
                assertTrue(ToolArgumentValidator.errors(input, definition.inputSchema).isEmpty())
                assertTrue(GitHubOperationArguments.errors("github_read", input).isEmpty())
                val result = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 1)).bindPresented(native, definition).execute("read", input)
                assertFalse(result.isError)
                assertEquals(input["ranges"], backend.calls.single()["ranges"])
            }
            val definition = GitHubOperationTool.definition("github_read")
            val presented = if (projected) requireNotNull(GitHubActionSchema.project(definition, "ranges")) else definition
            for (range in listOf("""{"path":"README.md","max_lines":0}""", """{"path":"README.md","max_lines":2001}""", """{"path":"README.md","end_line":-2}""", """{"path":"README.md","start_offset":-1}""")) {
                assertTrue(range, ToolArgumentValidator.errors(args("""{"action":"ranges","ranges":[$range]}"""), presented.inputSchema).isNotEmpty())
            }
        }
    }

    @Test fun ciSemanticRulesIdentifyOnlyTheActualFailedFieldsWithoutValues() {
        data class Case(val input: String, val rule: GitHubOperationArguments.CiRule, val fields: Set<String>)
        val cases = listOf(
            Case("""{"action":"runs","branch":"refs/tags/private-release"}""", GitHubOperationArguments.CiRule.BRANCH_NAME, setOf("branch")),
            Case("""{"action":"runs","head_sha":"private-head"}""", GitHubOperationArguments.CiRule.COMMIT_SHA, setOf("head_sha")),
            Case("""{"action":"logs","job_id":0}""", GitHubOperationArguments.CiRule.POSITIVE_ID, setOf("job_id")),
            Case("""{"action":"definitions","workflow_id":"../private.yml"}""", GitHubOperationArguments.CiRule.WORKFLOW_SELECTOR, setOf("workflow_id")),
            Case("""{"action":"logs"}""", GitHubOperationArguments.CiRule.MISSING_SELECTOR, setOf("job_id")),
            Case("""{"action":"runs","inputs":{"private-key":"private-value"}}""", GitHubOperationArguments.CiRule.UNUSED_FIELD, setOf("inputs")),
            Case("""{"action":"logs","job_id":7,"start_line":0}""", GitHubOperationArguments.CiRule.LOG_START, setOf("start_line")),
            Case("""{"action":"logs","job_id":7,"start_line":20,"end_line":10}""", GitHubOperationArguments.CiRule.LOG_ORDER, setOf("start_line", "end_line")),
            Case("""{"action":"logs","job_id":7,"start_line":20,"end_line":2020}""", GitHubOperationArguments.CiRule.LOG_SPAN, setOf("start_line", "end_line"))
        )
        for (case in cases) {
            val input = args(case.input)
            val ci = linkedSetOf<GitHubOperationArguments.CiRejection>()
            val errors = GitHubOperationArguments.errors("github_ci", input, ciRejections = ci)
            assertEquals(1, errors.size)
            assertEquals(setOf(GitHubOperationArguments.CiRejection(case.rule, case.fields)), ci)
            val definition = GitHubOperationTool.definition("github_ci", "github_ci__selected")
            val result = ToolArgumentValidator.rejection("ci-rule", definition.name, input, definition.inputSchema, errors, ciRejections = ci)
            assertEquals(JsonArray(listOf(JsonPrimitive(case.rule.name))), result.executionReceipt?.get("semantic_ci_rules"))
            assertEquals(JsonArray(case.fields.sorted().map(::JsonPrimitive)), result.executionReceipt?.get("validation_field_names"))
            assertFalse(result.executionReceipt.toString().contains("private"))
            assertFalse(result.dispatched)
        }
        val ci = linkedSetOf<GitHubOperationArguments.CiRejection>()
        assertTrue(GitHubOperationArguments.errors("github_ci", args("""{"action":"logs","job_id":7,"start_line":20,"end_line":2019}"""), ciRejections = ci).isEmpty())
        assertTrue(ci.isEmpty())
        GitHubOperationArguments.errors("github_context", args("""{"branch":"refs/tags/private-release"}"""), ciRejections = ci)
        assertTrue(ci.isEmpty())
    }

    @Test fun actualCiSemanticRepairKeepsItsTypedRuleAndWaitsForANewValidProposal() = runBlocking {
        val backend = RecordingBackend()
        val native = GitHubOperationTool("github_ci", backend, "ci-repair", "github_ci__selected")
        val invalid = args("""{"action":"logs","job_id":7,"start_line":20,"end_line":10}""")
        val scope = ToolArgumentRepairScope()
        withContext(scope) {
            repeat(2) { index ->
                val result = native.execute("invalid-$index", invalid)
                assertFalse(result.dispatched)
                assertEquals("INVALID_ARGUMENTS", result.errorCode)
                assertEquals(JsonArray(listOf(JsonPrimitive("LOG_ORDER"))), result.executionReceipt?.get("semantic_ci_rules"))
                assertEquals(JsonPrimitive(index + 1), result.executionReceipt?.get("argument_repair_attempt"))
                assertEquals(JsonPrimitive(index == 1), result.executionReceipt?.get("argument_repair_exhausted"))
                assertTrue(backend.calls.isEmpty())
            }
            val corrected = args("""{"action":"logs","job_id":7,"start_line":20,"end_line":30}""")
            assertFalse(native.execute("corrected", corrected).isError)
            assertEquals(1, backend.calls.size)
            assertEquals(corrected["job_id"], backend.calls.single()["job_id"])
            assertEquals(corrected["start_line"], backend.calls.single()["start_line"])
            assertEquals(corrected["end_line"], backend.calls.single()["end_line"])
        }
    }

    @Test fun unknownConclusionValuesStayRejectedWithoutSemanticOrAutomaticCorrectionClaims() = runBlocking {
        val backend = RecordingBackend()
        val native = GitHubOperationTool("github_ci", backend, "ci-schema", "github_ci__selected")
        val input = args("""{"action":"runs","branch":"main","conclusion":"private-result","status":"private-state","per_page":30}""")
        val result = native.execute("unknown-field", input)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(JsonPrimitive(1), result.executionReceipt?.get("unknown_field_count"))
        assertNull(result.executionReceipt?.get("semantic_ci_rules"))
        assertNull(((result.content as ToolResultContent.Json).value as JsonObject)["correction_call"])
        assertTrue(backend.calls.isEmpty())
        for (definition in listOf(native.definition, requireNotNull(GitHubActionSchema.project(native.definition, "runs")))) {
            assertTrue(definition.description.contains("status"))
            assertFalse("conclusion" in (definition.inputSchema["properties"] as JsonObject))
        }
    }

    @Test fun lastAdmittedSemanticFailureRetainsBudgetSnapshotAndTheNextCallStillStops() = runBlocking {
        val backend = RecordingBackend()
        val native = GitHubOperationTool("github_ci", backend, "ci-final-admission", "github_ci__selected")
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 32), initialCalls = 31)
        val tool = budget.bind(native)
        val result = tool.execute("last-admitted", args("""{"action":"logs","job_id":7,"start_line":20,"end_line":10}"""))
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertFalse(result.dispatched)
        assertTrue(result.toolCallBudgetExhausted)
        assertEquals(32, result.toolCallBudgetUsed)
        assertEquals(32, result.toolCallBudgetLimit)
        assertEquals(JsonArray(listOf(JsonPrimitive("LOG_ORDER"))), result.executionReceipt?.get("semantic_ci_rules"))
        val denied = tool.execute("denied", args("""{"action":"logs","job_id":7,"start_line":20,"end_line":30}"""))
        assertEquals("BUDGET_EXHAUSTED", denied.errorCode)
        assertFalse(denied.dispatched)
        assertTrue(denied.toolCallBudgetExhausted)
        assertNull(denied.executionReceipt?.get("semantic_ci_rules"))
        assertEquals(32, budget.usedCalls())
        assertTrue(backend.calls.isEmpty())

        val separate = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 1)).bind(native)
        val success = separate.execute("successful-last-call", args("""{"action":"logs","job_id":7,"start_line":20,"end_line":30}"""))
        assertFalse(success.isError)
        assertTrue(success.dispatched)
        assertTrue(success.toolCallBudgetExhausted)
        assertEquals(1, backend.calls.size)
    }

    private fun args(value: String) = Json.parseToJsonElement(value) as JsonObject

    private class RecordingBackend : AgentTool {
        val calls = mutableListOf<JsonObject>()
        override val definition = AgentToolDefinition("github", "Fixture backend", buildJsonObject {})
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            calls += arguments
            return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("ok", true) }), false)
        }
    }
}
