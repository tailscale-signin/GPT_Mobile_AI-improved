package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChecklistDiscoveryProgressTest {
    @Test fun explicitChecklistTraversesTheIssuedCatalogWithoutRenewingSourceStaleness() = runTest {
        val tools = (1..61).map { AgentToolDefinition("external_$it", "External", JsonObject(emptyMap())) }
        val catalog = ProgressiveToolCatalog(tools, "Inspect tools")
        val checklist = GitHubInvestigationProgress("Test all GitHub tools")
        val engineering = GitHubInvestigationProgress("Repair a repository bug")
        var arguments = JsonObject(emptyMap())
        var last: AgentToolExchange? = null
        repeat(6) { page ->
            val call = ProviderEvent.ToolCall("directory-$page", catalog.definition.name, arguments)
            val result = catalog.execute(call.callId, call.arguments)
            assertFalse(result.isError)
            val exchange = AgentToolExchange(listOf(call), listOf(result))
            assertNull(checklist.observe(exchange))
            val ordinary = engineering.observe(exchange)
            if (page >= 3) assertNotNull("General discovery must retain its no-progress guard", ordinary)
            val data = (result.content as ToolResultContent.Json).value.jsonObject
            arguments = (data["continuation_call"] as? JsonObject)?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
            last = exchange
        }
        assertNotNull("Catalog traversal does not reset source staleness or permit replay", checklist.observe(requireNotNull(last)))
    }

    @Test fun newExactActionSchemasGetOneHandoffButAlternationCannotResetTheGuard() = runTest {
        val definition = GitHubOperationTool.definition("github_context", "github_context__github_api")
        val catalog = ProgressiveToolCatalog(listOf(definition), "Test all GitHub tools", githubMode = true)
        val progress = GitHubInvestigationProgress("Test all GitHub tools")
        repeat(3) { index ->
            val call = ProviderEvent.ToolCall("unchanged-$index", catalog.definition.name, JsonObject(emptyMap()))
            assertNull(progress.observe(AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(JsonObject(emptyMap())), false)))))
        }
        suspend fun acquire(action: String): AgentToolExchange {
            val call = ProviderEvent.ToolCall(
                "schema-$action",
                catalog.definition.name,
                buildJsonObject {
                    put("names", JsonArray(listOf(JsonPrimitive(definition.name))))
                    put("action", action)
                }
            )
            return AgentToolExchange(listOf(call), listOf(catalog.execute(call.callId, call.arguments)))
        }
        val repository = acquire("repository")
        assertNull(progress.observe(repository))
        assertNull(progress.observe(acquire("resolve")))
        assertNotNull(progress.observe(repository))
    }

    @Test fun inventedCursorsChangedVersionsAndUnadmittedNamesAreNotCoverage() = runTest {
        val catalog = ProgressiveToolCatalog((1..30).map { AgentToolDefinition("external_$it", "External", JsonObject(emptyMap())) }, "Inspect tools")
        val progress = GitHubChecklistDiscoveryProgress("Test all GitHub tools")
        val first = ProviderEvent.ToolCall("first", catalog.definition.name, JsonObject(emptyMap()))
        val initial = catalog.execute(first.callId, first.arguments)
        assertTrue(progress.advances(first, initial))
        val body = (initial.content as ToolResultContent.Json).value.jsonObject
        val next = ProviderEvent.ToolCall("next", catalog.definition.name, body.getValue("continuation_call").jsonObject.getValue("arguments").jsonObject)
        val result = catalog.execute(next.callId, next.arguments)
        val changed = (result.content as ToolResultContent.Json).value.jsonObject
        assertFalse(progress.advances(next, result.copy(content = ToolResultContent.Json(JsonObject(changed + ("inventory_version" to JsonPrimitive("f".repeat(64))))))))
        assertFalse(progress.advances(next.copy(arguments = JsonObject(next.arguments + ("inventory_cursor" to JsonPrimitive(1)))), result))
        assertTrue(progress.advances(next, result))
        assertFalse(progress.advances(next, result))
        val unknown = ProviderEvent.ToolCall("unknown", catalog.definition.name, buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive("not_authorized")))) })
        assertFalse(progress.advances(unknown, catalog.execute(unknown.callId, unknown.arguments)))
    }
}
