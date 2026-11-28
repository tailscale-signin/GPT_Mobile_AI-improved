package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChecklistFairnessTest {
    private val context = GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__github_api")
    private val ci = GitHubOperationTool.definition("github_ci", "github_ci__github_api")
    private val issue = GitHubOperationTool.definition("github_issue", "github_issue__github_api")
    private fun read(tool: AgentToolDefinition, action: String, page: Int? = null) = ProviderEvent.ToolCall(
        "${tool.name}-$action-$page",
        tool.name,
        buildJsonObject {
            put("action", action)
            page?.let { put("page", it) }
        }
    )
    private fun receipt(call: ProviderEvent.ToolCall, result: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("trusted", true)
        put("success", true)
        put("verification_eligible", true)
        put("receipt_id", call.callId)
        put("binding", "selected-connection")
        put("repository", "owner/repo")
        put("tool", call.name)
        put("arguments", call.arguments)
        put("result", result)
    }

    @Test fun loggedTwentySevenContractEightHandleProfileReachesHiddenReadsBeforeCiPagination() = runTest {
        val native = listOf("github_ci", "github_commit", "github_context", "github_context_inspect", "github_issue", "github_pr", "github_pr_inspect", "github_read", "github_release", "github_repository", "github_search", "github_stage_patch", "github_watch_checks", "github_write")
            .map { GitHubOperationTool.definition(it, "${it}__github_api") }
        // These unexecuted auxiliary contracts reproduce the logged inventory identities.
        val auxiliary = listOf("calculate_expression", "current_date", "memory", "places_refuge__restrooms", "places_toronto__datasets", "places_toronto__records", "read_file_slice", "read_url", "web_search", "repository_graph__github_api", "github_decide")
            .map { AgentToolDefinition(it, "Auxiliary catalog entry", JsonObject(emptyMap())) }
        val backend = AgentToolDefinition("github__github_api", "Private backend", JsonObject(emptyMap()), modelVisible = false)
        val catalog = ProgressiveToolCatalog(native + auxiliary + backend, "Test all GitHub tools", githubMode = true)
        val authorized = native + auxiliary + backend + catalog.definition
        val admittedNames = setOf("discover_available_tools", "github_ci__github_api", "github_context_inspect__github_api", "github_decide", "github_pr_inspect__github_api", "github_read__github_api", "github_search__github_api", "repository_graph__github_api")
        var advertised = authorized.filter { it.name in admittedNames }
        val receipts = linkedMapOf<String, JsonObject>()
        val pending = read(issue, "list")
        fun plan(visible: ProviderEvent.ToolCall) = GitHubChecklistSchedule.select(
            authorized,
            advertised,
            receipts,
            listOf(visible, pending),
            visible,
            GitHubAuthorizedCatalog.next(advertised, receipts, authorized),
            true
        )
        assertEquals(27, authorized.size)
        assertEquals(8, advertised.size)
        val status = read(context, "status")
        assertEquals(status, plan(status).next)
        receipts[status.callId] = receipt(status)
        val page = requireNotNull(plan(read(ci, "runs", 1)).next)
        assertEquals(catalog.definition.name, page.name)
        assertTrue(page.arguments.isEmpty())
        val directory = catalog.execute(page.callId, page.arguments)
        assertFalse(directory.isError)
        receipts[page.callId] = receipt(page, (directory.content as ToolResultContent.Json).value.jsonObject)
        assertEquals(JsonPrimitive(true), GitHubAuthorizedCatalog.snapshot(receipts, authorized)["complete"])
        val runs = read(ci, "runs", 1)
        assertEquals(runs, plan(runs).next)
        receipts[runs.callId] = receipt(runs)
        val discovery = requireNotNull(plan(read(ci, "runs", 2)).next)
        assertEquals(catalog.definition.name, discovery.name)
        assertEquals(JsonArray(listOf(JsonPrimitive(issue.name))), discovery.arguments["names"])
        val schemas = catalog.execute(discovery.callId, discovery.arguments)
        assertFalse(schemas.isError)
        receipts[discovery.callId] = receipt(discovery, (schemas.content as ToolResultContent.Json).value.jsonObject)
        advertised = catalog.selected(authorized)
        val execute = plan(read(ci, "runs", 2))
        assertEquals(pending, execute.next)
        assertEquals(JsonPrimitive("ACQUIRED_SCHEMA_READ"), execute.snapshot["selection_reason"])
        assertEquals(JsonPrimitive(26), execute.snapshot["authorized_tool_count"])
        assertEquals(JsonPrimitive(true), execute.snapshot["catalog_denominator_unchanged"])
    }

    @Test fun catalogTraversalYieldsAfterTwoPagesAndThenResumesTheExactReturnedRecipe() = runTest {
        val catalog = ProgressiveToolCatalog((1..60).map { AgentToolDefinition("external_$it", "External", JsonObject(emptyMap())) } + context, "Inspect tools")
        val tools = listOf(context, catalog.definition)
        val status = read(context, "status")
        val pending = read(context, "repository")
        val receipts = linkedMapOf(status.callId to receipt(status))
        fun plan() = GitHubChecklistSchedule.select(tools, tools, receipts, listOf(pending), pending, GitHubAuthorizedCatalog.next(tools, receipts, tools), true)
        repeat(2) {
            val call = requireNotNull(plan().next)
            assertEquals(catalog.definition.name, call.name)
            val result = catalog.execute(call.callId, call.arguments)
            receipts[call.callId] = receipt(call, (result.content as ToolResultContent.Json).value.jsonObject)
        }
        assertEquals(pending, plan().next)
        receipts[pending.callId] = receipt(pending)
        val next = requireNotNull(plan().next)
        val exact = requireNotNull(GitHubAuthorizedCatalog.next(tools, receipts, tools))
        assertEquals(exact, next)
        assertEquals(JsonPrimitive(24), next.arguments["inventory_cursor"])
        assertTrue("inventory_version" in next.arguments)
    }

    @Test fun reservesAndCurrentAuthorizationStillGateFairDiscovery() {
        val catalog = ProgressiveToolCatalog(listOf(context, issue), "Test all GitHub tools")
        val visible = read(context, "repository")
        val receipts = listOf("status", "branches").associateWith { receipt(read(context, it)) }
        val catalogCall = ProviderEvent.ToolCall("catalog", catalog.definition.name, JsonObject(emptyMap()))
        val tools = listOf(context, issue, catalog.definition)
        val reserved = GitHubChecklistSchedule.select(tools, listOf(context, catalog.definition), receipts, listOf(visible, read(issue, "list")), visible, catalogCall, false)
        assertEquals(visible, reserved.next)
        val unauthorized = GitHubChecklistSchedule.select(listOf(context), listOf(catalog.definition), receipts, listOf(read(issue, "list")), null, catalogCall, true)
        assertNull(unauthorized.next)
        val untrusted = receipts.mapValues { (_, value) -> JsonObject(value + ("trusted" to JsonPrimitive(false))) }
        assertEquals(visible, GitHubChecklistSchedule.select(tools, listOf(context, catalog.definition), untrusted, listOf(visible, read(issue, "list")), visible, catalogCall, true).next)
    }
}
