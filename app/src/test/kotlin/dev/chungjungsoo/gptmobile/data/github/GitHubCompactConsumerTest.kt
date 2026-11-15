package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCompactConsumerTest {
    private val head = "a".repeat(40)
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private val runtime = json("""{"application_id":"app.test","version_name":"1","version_code":1,"app_source_sha":"${"b".repeat(40)}","identity_source":"RUNNING_PROCESS_BUILD_CONFIG"}""")
    private fun status(name: String = "github_context_inspect__selected") = json("""{"receipt_id":"source","call_id":"source-call","trusted":true,"success":true,"dispatched":true,"evidence_origin":"LIVE","binding":"task","repository":"owner/repo","tool":"$name","arguments":{"action":"status","verify_live":true},"runtime_identity":$runtime,"result":{"branch":"main","commit_sha":"$head","open_issue_count":2,"open_pull_request_count":1}}""")
    private fun definition(name: String, connection: String = "selected") = GitHubOperationTool.definition(name, "${name}__$connection")
    private val graphTool = AgentToolDefinition("repository_graph__selected", "Graph", json("""{"type":"object","properties":{"action":{"enum":["status","index"]}},"required":["action"],"additionalProperties":false}"""))
    private fun graph(success: Boolean = true, action: String = "status") = json("""{"receipt_id":"graph","trusted":true,"success":$success,"binding":"task","repository":"owner/repo","tool":"${graphTool.name}","arguments":{"action":"$action"},"result":{"status":"NOT_INDEXED","graph":{"repository":"owner/repo","branch":"main","commit_sha":"$head"}}}""")

    @Test fun compactStatusRetainsSelectedSourceAndExistingGraphScopeGuards() {
        for (name in listOf("github_context__selected", "github_context_inspect__selected")) {
            val source = status(name)
            assertEquals(source, GitHubSelfTestPrerequisites.selected(listOf(source))?.source)
            assertEquals("NOT_INDEXED", GitHubSelfTestPrerequisites.graphObservation(listOf(source, graph())))
            for (change in listOf("trusted" to JsonPrimitive(false), "verification_eligible" to JsonPrimitive(false), "repository_role" to JsonPrimitive("READ_SOURCE"))) {
                assertNull(GitHubSelfTestPrerequisites.selected(listOf(JsonObject(source + change))))
            }
            assertEquals("UNOBSERVED", GitHubSelfTestPrerequisites.graphObservation(listOf(source, JsonObject(graph() + ("binding" to JsonPrimitive("other"))))))
        }
    }

    @Test fun compactStatusCanBindTheActualBuildWithoutChangingItsReceipt() {
        val source = status()
        val original = source.toString()
        assertEquals(JsonPrimitive(true), GitHubIntegrationAcceptance.binding(listOf(source), runtime)["verified"])
        assertEquals(JsonPrimitive(false), GitHubIntegrationAcceptance.binding(listOf(JsonObject(source + ("trusted" to JsonPrimitive(false)))), runtime)["verified"])
        assertEquals(JsonPrimitive(false), GitHubIntegrationAcceptance.binding(listOf(source), JsonObject(runtime + ("version_code" to JsonPrimitive(2))))["verified"])
        assertEquals(original, source.toString())
        assertEquals(JsonPrimitive("github_context_inspect__selected"), source["tool"])
    }

    @Test fun compactSourceKeepsUnknownGraphWritesInReconciliation() {
        val source = status()
        val unknown = graph(success = false, action = "index")
        val next = GitHubSelfTestGraph.available(listOf(graphTool), listOf(source, unknown)).single()
        assertEquals(graphTool.name, next.name)
        assertEquals(json("""{"action":"status"}"""), next.arguments)
        assertTrue(GitHubSelfTestGraph.available(listOf(graphTool), listOf(source, unknown, graph(success = false))).isEmpty())
        assertTrue(GitHubSelfTestGraph.available(listOf(graphTool.copy(name = "repository_graph__other")), listOf(source, unknown)).isEmpty())
    }

    @Test fun checklistUnifiesCapabilityFamiliesButRetainsExactSchemaAuthorization() {
        val source = status()
        val compact = definition("github_context_inspect")
        val full = definition("github_context")
        val rows = GitHubTestChecklist.rows(mapOf("source" to source), listOf(compact, full), "test all tools").map { it.jsonObject }
        val row = rows.single { it["id"] == JsonPrimitive("github_context:status") }
        assertEquals(JsonPrimitive("PASS"), row["acceptance_state"])
        assertFalse(rows.any { (it["id"] as? JsonPrimitive)?.content?.contains("_inspect:") == true })
        val foreign = JsonObject(source + ("tool" to JsonPrimitive("github_context_inspect__other")))
        val excluded = GitHubTestChecklist.rows(mapOf("source" to foreign), listOf(compact), "test all tools").map { it.jsonObject }.single { it["id"] == JsonPrimitive("github_context:status") }
        assertEquals(JsonPrimitive("NOT_TESTED"), excluded["acceptance_state"])
        assertEquals(JsonPrimitive("AUTHORIZED"), excluded.getValue("readiness").jsonObject["authorization"])
    }

    @Test fun approvalReadSelectsASchemaThatSupportsRecoveryOnTheSameConnection() {
        val pending = json("""{"receipt_id":"pending","call_id":"prepare","trusted":true,"success":true,"dispatched":false,"binding":"task","repository":"owner/repo","branch":"work","tool":"github_write__selected","arguments":{"action":"commit","branch":"work","expected_head_sha":"$head","stage_id":"stage"},"result":{"engineering_task_id":"approval-42","status":"AWAITING_APPROVAL","committed":false}}""")
        val receipts = mapOf("pending" to pending)
        val compact = definition("github_context_inspect")
        val full = definition("github_context")
        val next = requireNotNull(GitHubApprovalContinuation.next("resume", listOf(compact, full), receipts, receipts.keys))
        assertEquals(full.name, next.name)
        assertEquals(JsonPrimitive("recovery"), next.arguments["action"])
        assertTrue(ToolArgumentValidator.errors(next.arguments, full.inputSchema).isEmpty())
        assertNull(GitHubApprovalContinuation.next("resume", listOf(compact), receipts, receipts.keys))
        assertNull(GitHubApprovalContinuation.next("resume", listOf(compact, definition("github_context", "other")), receipts, receipts.keys))
    }

    @Test fun committedTaskCanStartItsBoundedReadsWithOnlyTheCompactFacade() {
        val compact = definition("github_context_inspect")
        val first = requireNotNull(GitHubCommitContinuation.next(head, "resume", listOf(compact), emptyMap()))
        assertEquals(compact.name, first.name)
        assertEquals(JsonPrimitive("status"), first.arguments["action"])
        val receipt = JsonObject(status() + mapOf("call_id" to JsonPrimitive(first.callId), "arguments" to first.arguments))
        val second = requireNotNull(GitHubCommitContinuation.next(head, "resume", listOf(compact), mapOf("first" to receipt)))
        assertEquals(JsonPrimitive("commit"), second.arguments["action"])
        assertEquals(JsonPrimitive(head), second.arguments["commit_sha"])
    }

    @Test fun compactStatusFactsReachProgressWithoutAcceptingAnotherCallOrFamily() {
        val progress = GitHubChatProgress { 0L }
        val call = ProviderEvent.ToolCall("source-call", "github_context_inspect__selected", json("""{"action":"status"}"""))
        val facts = GitHubSemanticSummary.from("github_context", call.arguments, status().getValue("result").jsonObject)
        val result = AgentToolResult(call.callId, ToolResultContent.Json(JsonObject(mapOf("semantic_summary" to facts))), false)
        assertEquals("Repository status: 2 open issues and 1 open pull request", progress.observeResult(call, result))
        assertNull(progress.observeResult(call.copy(name = "github_pr_inspect__selected"), result))
        assertNull(progress.observeResult(call, result.copy(callId = "other")))
    }

    @Test fun familyClassificationDoesNotRewriteExactActionIdentityOrUnknownNames() {
        val source = status()
        val full = JsonObject(source + ("tool" to JsonPrimitive("github_context__selected")))
        val other = JsonObject(source + ("tool" to JsonPrimitive("github_context_inspect__other")))
        assertNotNull(GitHubActionIdentity.digest(source))
        assertNotEquals(GitHubActionIdentity.digest(source), GitHubActionIdentity.digest(full))
        assertNotEquals(GitHubActionIdentity.digest(source), GitHubActionIdentity.digest(other))
        assertEquals("github_context_inspect_extra", "github_context_inspect_extra__selected".githubOperationFamily())
        assertEquals("unrelated_inspect", "unrelated_inspect__selected".githubOperationFamily())
    }

    @Test fun compactPullRequestRejectionsRetainTypedSelectorRecovery() {
        val tool = definition("github_pr_inspect")
        val arguments = json("""{"action":"context"}""")
        val result = ToolArgumentValidator.rejection("missing", tool.name, arguments, tool.inputSchema, listOf("missing pull_number"))
        val body = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive("PR_SELECTOR_REQUIRED"), body["reason"])
        assertEquals(JsonPrimitive("list"), body.getValue("correction_call").jsonObject.getValue("arguments").jsonObject["action"])
        assertFalse(result.dispatched)
    }
}
