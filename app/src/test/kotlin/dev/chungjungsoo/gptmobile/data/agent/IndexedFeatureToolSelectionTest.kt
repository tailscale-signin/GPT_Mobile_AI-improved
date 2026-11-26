package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexedFeatureToolSelectionTest {
    private fun native(id: String) = NativeMarketplaceCatalog.definitions(requireNotNull(GitHubMarketplaceCatalog.find("optional-$id")))
    private fun tool(name: String, preset: String? = null) = AgentToolDefinition(name, "Read matching records", buildJsonObject {}, discoveryPresetId = preset)
    private fun ProgressiveToolCatalog.names(tools: List<AgentToolDefinition>) = selected(tools + definition).map { it.name }

    @Test fun ordinaryRequestsAdmitInstalledProductContractsAndLeaveLookalikesOut() {
        val ticketmaster = native("ticketmaster").single()
        val toronto = native("toronto")
        val lookalike = tool("mcp__places_ticketmaster__events")
        val all = listOf(lookalike) + toronto + ticketmaster
        val task = "Find events on Ticketmaster in Toronto."
        val catalog = ProgressiveToolCatalog(all, task, githubMode = true)
        assertEquals(listOf(ticketmaster.name), catalog.names(all).filterNot { it == ProgressiveToolCatalog.NAME })
        assertFalse(lookalike.name in catalog.names(all - ticketmaster))
        assertTrue(catalog.names(all - ticketmaster).none { it == ticketmaster.name })
        val datasets = ProgressiveToolCatalog(all, "Use Toronto Open Data to find datasets on transit.", githubMode = true)
        assertTrue(datasets.names(all).containsAll(toronto.map { it.name }))
        assertFalse(ticketmaster.name in datasets.names(all))
        val hidden = ticketmaster.copy(modelVisible = false)
        val offline = ProgressiveToolCatalog(listOf(hidden, lookalike), task, githubMode = true)
        assertEquals(listOf(ProgressiveToolCatalog.NAME), offline.names(listOf(hidden, lookalike)))
    }

    @Test fun followupDiscoveryUsesTheInstalledPresetIdAndHonorsOriginalExclusionsAndExactNames() = runTest {
        val mapped = tool("mcp__work_trip__items", "optional-mapbox")
        val pretending = tool("mcp__mapbox__items")
        val original = "Help me with my travel plan without Ticketmaster."
        val tickets = native("ticketmaster").single()
        val all = listOf(mapped, pretending, tickets)
        val catalog = ProgressiveToolCatalog(emptyList(), original, githubMode = true, acquireCold = { _, _ -> all })
        fun available(result: AgentToolResult): List<String> {
            val value = (result.content as ToolResultContent.Json).value as JsonObject
            return (value.getValue("available_next_round") as JsonArray).map { ((it as JsonObject).getValue("name") as JsonPrimitive).content }
        }
        val results = available(catalog.execute("map", buildJsonObject { put("query", "Look up transit with Mapbox") }))
        assertEquals(listOf(mapped.name), results)
        assertFalse(tickets.name in available(catalog.execute("tickets", buildJsonObject { put("query", "Find Ticketmaster Events") })))
        val exact = available(catalog.execute("exact", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(pretending.name)))) }))
        assertEquals(listOf(pretending.name), exact)
        val previouslySelected = ProgressiveToolCatalog(all, "Find events with Ticketmaster", githubMode = true)
        assertTrue(tickets.name in previouslySelected.names(all))
        previouslySelected.execute("exclude", buildJsonObject { put("query", "Use Mapbox without Ticketmaster") })
        assertFalse(tickets.name in previouslySelected.names(all))
    }

    @Test fun localPluginsMatchExplicitRequestsAndSettingsAndSourceLookupsKeepTheirScope() {
        val calculator = tool("calculate_expression")
        val file = tool("read_file_slice")
        val context = tool("github_context")
        val tickets = native("ticketmaster").single()
        val definitions = listOf(calculator, file, context, tickets)
        assertTrue(IndexedFeatureToolSelection("Use Calculator to check my total").selects(calculator))
        assertFalse("Ordinary file references must not select the Files plugin", IndexedFeatureToolSelection("Which files contain this code?").selects(file))
        assertFalse("Settings have no tool owner", IndexedFeatureToolSelection("Change backup and restore settings").hasNamedProduct)
        assertFalse("Offline-only tasks exclude network products", IndexedFeatureToolSelection("Read Ticketmaster Events, offline only").selects(tickets))
        val lookup = ProgressiveToolCatalog(definitions, "Where in this repository is Ticketmaster implemented?", githubMode = true)
        assertFalse("Repository code questions retain source discovery", tickets.name in lookup.names(definitions))
    }
}
