package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOperationArgumentsTest {
    private fun args(value: String) = Json.parseToJsonElement(value) as JsonObject

    @Test fun lineWindowDiagnosticsDistinguishCorrectableConstraints() {
        fun range(value: String) = kotlinx.serialization.json.Json.parseToJsonElement(value) as kotlinx.serialization.json.JsonObject
        org.junit.Assert.assertEquals(setOf("BAD_START", "CONFLICTING_LIMITS", "BAD_MAX_LINES"), GitHubOperationArguments.readWindowFailures(range("""{"start_line":0,"end_line":-1,"max_lines":2001}""")))
        org.junit.Assert.assertEquals(setOf("BAD_END"), GitHubOperationArguments.readWindowFailures(range("""{"start_line":1,"end_line":2001}""")))
        org.junit.Assert.assertTrue(GitHubOperationArguments.readWindowFailures(range("""{"start_line":2001,"end_line":4000}""")).isEmpty())
        org.junit.Assert.assertTrue(GitHubOperationArguments.readWindowFailures(range("""{"start_line":1,"end_line":-1}""")).isEmpty())
    }

    @Test fun boundedCountAndEofReadsNeedNoPreviouslyDiscoveredLineCount() {
        for (range in listOf("""{"path":"README.md","start_line":20,"max_lines":40}""", """{"path":"README.md","start_line":20,"end_line":-1}""", """{"path":"README.md"}""")) {
            val input = args("""{"ranges":[$range]}""")
            assertTrue(range, GitHubOperationArguments.errors("github_read", input).isEmpty())
            assertTrue(range, dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(input, GitHubOperationTool.definition("github_read").inputSchema).isEmpty())
            assertTrue(GitHubOperationTool.nativeArguments("github_read", input)?.get("ranges") == input["ranges"])
        }
        for (range in listOf("""{"path":"README.md","max_lines":0}""", """{"path":"README.md","max_lines":2001}""", """{"path":"README.md","end_line":0}""", """{"path":"README.md","end_line":-2}""", """{"path":"README.md","end_line":-1,"max_lines":20}""")) {
            assertTrue(range, GitHubOperationArguments.errors("github_read", args("""{"ranges":[$range]}""")).isNotEmpty())
        }
    }

    @Test fun nativeSourceReadRepairKeepsStableRuleAndStopsAnUnchangedInvalidProposal() {
        val definition = GitHubOperationTool.definition("github_read")
        val budget = dev.chungjungsoo.gptmobile.data.agent.ToolArgumentRepairBudget()
        val invalid = args("""{"ranges":[{"path":"private-file.md","end_line":20,"max_lines":4}]}""")
        val rules = linkedSetOf<GitHubOperationArguments.ReadRule>()
        val errors = GitHubOperationArguments.errors("github_read", invalid, rules)
        assertEquals(setOf(GitHubOperationArguments.ReadRule.LINE_WINDOW), rules)
        val initial = budget.rejection("first", definition, invalid, errors, rules)
        val repeated = budget.rejection("second", definition, invalid, errors, rules)
        assertFalse(initial.dispatched)
        assertFalse(repeated.dispatched)
        assertEquals(args("""{"value":["LINE_WINDOW"]}""")["value"], initial.executionReceipt?.get("semantic_read_rules"))
        assertEquals(args("""{"value":true}""")["value"], repeated.executionReceipt?.get("argument_repair_exhausted"))
        assertFalse(initial.executionReceipt.toString().contains("private-file"))
        assertTrue(GitHubOperationArguments.errors("github_read", args("""{"ranges":[{"path":"private-file.md","max_lines":4}]}""")).isEmpty())
    }

    @Test fun boundedCheckWaitIsOptionalAndRejectsUnboundedOrMalformedIntervals() {
        for (timeout in listOf("", ",\"wait_timeout_seconds\":1", ",\"wait_timeout_seconds\":30")) {
            val input = args("""{"run_id":9$timeout}""")
            assertTrue(GitHubOperationArguments.errors("github_watch_checks", input).isEmpty())
            assertTrue(dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(input, GitHubOperationTool.definition("github_watch_checks").inputSchema).isEmpty())
        }
        for (timeout in listOf("0", "31", "60", "-1", "1.5", "\"30\"")) {
            val input = args("""{"run_id":9,"wait_timeout_seconds":$timeout}""")
            assertTrue(dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(input, GitHubOperationTool.definition("github_watch_checks").inputSchema).isNotEmpty())
        }
    }

    @Test fun sourceRangeNumbersAndFieldsCannotBeCoercedIntoDefaultReads() {
        for (range in listOf(
            """{"path":"README.md","start_line":"1"}""",
            """{"path":"README.md","end_line":null}""",
            """{"path":"README.md","max_lines":"20"}""",
            """{"path":"README.md","start_offset":"0"}""",
            """{"path":"README.md","start_offset":null}""",
            """{"path":"README.md","start_offset":1.5}""",
            """{"path":"README.md","start_offset":-1}""",
            """{"path":"README.md","start_offset":2147483648}""",
            """{"path":"README.md","endLine":20}""",
            "null"
        )) {
            val request = args("{\"ranges\":[$range]}")
            assertTrue(range, GitHubOperationArguments.errors("github_read", request).isNotEmpty())
            assertTrue(range, dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(request, GitHubOperationTool.definition("github_read").inputSchema).isNotEmpty())
        }
        assertTrue(GitHubOperationArguments.errors("github_read", args("""{"evidence_id":"saved","ranges":false}""")).isNotEmpty())
    }

    @Test fun placeholderCommitIsRejectedForLookupAndReadsButRealHeadsRemainUsable() {
        for (operation in listOf("github_context", "github_read")) {
            val extra = if (operation == "github_read") ",\"ranges\":[{\"path\":\"README.md\",\"start_line\":1,\"end_line\":30}]" else ""
            val bad = args("{\"commit_sha\":\"${"0".repeat(40)}\"$extra}")
            assertTrue(GitHubOperationArguments.errors(operation, bad).any { "placeholder" in it })
            val good = args("{\"commit_sha\":\"${"a".repeat(40)}\"$extra}")
            assertTrue(GitHubOperationArguments.errors(operation, good).isEmpty())
        }
    }

    @Test fun missingIssueNumberOffersDiscoveryAndOversizedReadsOfferChunks() {
        assertTrue(GitHubOperationArguments.errors("github_issue", args("""{"action":"headers"}""")).any { "action=list" in it })
        assertTrue(GitHubOperationArguments.errors("github_read", args("""{"ranges":[{"path":"README.md","start_line":1,"end_line":4000}]}""")).any { "2001..4000" in it })
        assertTrue(GitHubOperationArguments.errors("github_read", args("""{"ranges":[{"path":"README.md","start_line":1,"end_line":2000},{"path":"README.md","start_line":2001,"end_line":4000}]}""")).isEmpty())
    }

    @Test fun malformedWorkflowSelectorIsRejectedBeforeDispatch() {
        for (selector in listOf("0", "-1", "999999999999999999999999", "../ci.yml", "")) {
            assertTrue(GitHubOperationArguments.errors("github_ci", args("""{"action":"definitions","workflow_id":"$selector"}""")).any { "workflow_id" in it })
        }
        assertTrue(GitHubOperationArguments.errors("github_ci", args("""{"action":"definitions","workflow_id":".github/workflows/ci.yml"}""")).isEmpty())
    }

    @Test fun operationSpecificFieldsCannotBeSilentlyIgnoredOrSendEmptyUpdates() {
        val head = "a".repeat(40)
        for ((operation, value) in listOf(
            "github_pr" to """{"action":"update","pull_number":7,"expected_head_sha":"$head"}""",
            "github_pr" to """{"action":"merge","pull_number":7,"expected_head_sha":"$head","title":"ignored"}""",
            "github_pr" to """{"action":"create","title":"topic","head":"main","base":"main"}""",
            "github_issue" to """{"action":"update","issue_number":7,"expected_updated_at":"now"}""",
            "github_ci" to """{"action":"dispatch","workflow_id":"ci.yml","ref":"main","expected_head_sha":"$head","run_id":7}""",
            "github_ci" to """{"action":"rerun","run_id":7,"inputs":{"publish":"true"}}""",
            "github_release" to """{"action":"create_tag","tag_name":"v1"}"""
        )) {
            assertTrue(value, GitHubOperationArguments.errors(operation, args(value)).isNotEmpty())
        }
        assertTrue(GitHubOperationArguments.errors("github_pr", args("""{"action":"update","pull_number":7,"expected_head_sha":"$head","state":"closed"}""")).isEmpty())
        assertTrue(GitHubOperationArguments.errors("github_issue", args("""{"action":"update","issue_number":7,"expected_updated_at":"now","body":""}""")).isEmpty())
    }
}
