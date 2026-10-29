package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBatchPlannerTest {
    @Test fun separateSourcesDoNotExhaustTheReaderRepeatLimit() {
        fun call(id: String, url: String) = ProviderEvent.ToolCall(id, "mcp__jina__read_url", kotlinx.serialization.json.buildJsonObject { put("url", kotlinx.serialization.json.JsonPrimitive(url)) })
        val first = call("1", "https://example.org/one")
        val second = call("2", "https://example.org/two")
        val plan = planToolBatch(listOf(first, second), 40, setOf(first.name), mapOf(toolRepeatKey(first) to 3), emptySet(), 24) { it.name }
        assertEquals(listOf(second), plan.executable)
        assertEquals(setOf("1"), plan.perToolSuppressedIds)
        assertTrue(plan.disabledNames.isEmpty())
    }

    @Test fun admittedCallsSurviveRemovalOfARepeatedTool() {
        val calls = (0..3).map { ProviderEvent.ToolCall("$it", "search_nodes", JsonObject(emptyMap())) }
        val plan = planToolBatch(calls, 10, setOf("search_nodes"), mapOf("search_nodes" to 1), emptySet(), 16) { it.name }
        assertEquals(listOf("0", "1"), plan.executable.map { it.callId })
        assertEquals(setOf("2", "3"), plan.perToolSuppressedIds)
        assertEquals(setOf("search_nodes"), plan.disabledNames)
        assertEquals(2, plan.acceptedByName["search_nodes"])
    }

    @Test fun hostSuppressionDoesNotBlockIndependentEndpoints() {
        val calls = (0..2).map { ProviderEvent.ToolCall("$it", "read_url", JsonObject(emptyMap())) }
        val plan = planToolBatch(calls, 2, setOf("read_url"), emptyMap(), setOf("0"), 16) { it.callId }
        assertEquals(listOf("1"), plan.executable.map { it.callId })
        assertEquals(listOf("0", "2"), plan.deferred.map { it.callId })
        assertEquals(setOf("0"), plan.hostSuppressedIds)
        assertTrue(plan.disabledNames.isEmpty())
    }

    @Test fun unavailableNamesAreNotReportedAsRepeatLimits() {
        val call = ProviderEvent.ToolCall("first", "github_read_file", JsonObject(emptyMap()))
        val plan = planToolBatch(listOf(call), 10, setOf("github_read__github_api"), emptyMap(), emptySet(), 24) { it.name }
        assertTrue(plan.executable.isEmpty())
        assertTrue(plan.perToolSuppressedIds.isEmpty())
        assertEquals(setOf("first"), plan.unavailableIds)
        assertTrue(plan.acceptedByName.isEmpty())
    }

    @Test fun bareReadAliasResolvesOnlyToOneAuthorizedCapability() {
        val call = ProviderEvent.ToolCall("read", "github_search", JsonObject(emptyMap()))
        assertEquals("github_search__github_api", resolveGitHubReadAlias(call, setOf("github_search__github_api")).name)
        assertEquals(call, resolveGitHubReadAlias(call, emptySet()))
        assertEquals(call, resolveGitHubReadAlias(call, setOf("github_search__one", "github_search__two")))
        assertEquals(call, resolveGitHubReadAlias(call, setOf("github_search", "github_search__one")))
        val foreign = call.copy(name = "github_search__removed")
        assertEquals(foreign, resolveGitHubReadAlias(foreign, setOf("github_search__github_api")))
        val mutation = call.copy(name = "github_write")
        assertEquals(mutation, resolveGitHubReadAlias(mutation, setOf("github_write__github_api")))
    }
}
