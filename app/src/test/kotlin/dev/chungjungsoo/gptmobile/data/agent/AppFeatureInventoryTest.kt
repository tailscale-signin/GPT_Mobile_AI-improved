package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubDecisionTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.MarketplaceRuntime
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.model.AppFeature as FeatureToggle
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingsSearchRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFeatureInventoryTest {
    @Test fun everyToggleSettingAndBuiltInPluginHasAUniqueDocumentedIdentity() {
        val rows = AppFeatureInventory.features
        assertEquals(rows.size, rows.distinctBy { it.id }.size)
        assertEquals(reviewedAppFeatures, rows.filter { it.kind == "feature" })
        assertEquals(FeatureToggle.entries.map { "feature.${it.name}" }.toSet(), rows.filter { it.kind == "toggle" }.map { it.id }.toSet())
        val searchable = SettingsSearchRegistry.entries.map { if (it.key.startsWith("feature.")) it.key else "setting.${it.key}" }
        assertTrue(rows.map { it.id }.containsAll(searchable))
        assertEquals(ToolServiceCatalog.integrated.map { it.id }.toSet(), AppFeatureInventory.pluginIds)
        assertEquals(AppFeatureInventory.pluginIds, rows.filter { it.kind == "plugin" }.flatMap { it.plugins }.toSet())
        val presets = GitHubMarketplaceCatalog.allPresets
        assertEquals(presets.filterNot { it.documentationOnly }.map { "preset.${it.id}" }.toSet(), rows.filter { it.kind == "preset" }.map { it.id }.toSet())
        assertTrue(rows.filter { it.kind != "feature" }.all { it.tools.isEmpty() })
        assertTrue(rows.all { it.title.isNotBlank() && it.admission.isNotBlank() })
    }

    @Test fun fixedAppToolsRetainVerifiedProducerPathsWithoutInventingDynamicConnectionNames() {
        val tools = AppFeatureInventory.toolProducers
        val builtinNames = BuiltInAgentTool::class.java.declaredFields.filter { it.type == String::class.java }.map { it.get(null) as String }
        assertTrue(tools.keys.containsAll(builtinNames - BuiltInAgentTool.GITHUB))
        assertFalse(BuiltInAgentTool.GITHUB in tools)
        assertFalse("amazon_list_price_watches__free_native" in tools)
        assertTrue(tools.keys.containsAll(listOf("discover_available_tools", "read_file_slice", "local_assist_plan", "research_examine_article", "health_connect_summary", "deep_research_get_paper", "memory", "crawler")))
        assertTrue(tools.getValue("read_file_slice").single().endsWith("/ReadFileSliceTool.kt"))
        assertTrue(tools.getValue("research_examine_article").single().endsWith("/ResearchEvidenceTool.kt"))
        assertTrue(AppFeatureInventory.auditedToolSources.any { it.endsWith("/McpToolMapper.kt") })
        assertFalse(tools.values.flatten().any { it.endsWith("/McpToolMapper.kt") })
        assertTrue(tools.values.all { sources -> sources.isNotEmpty() && sources.all { it.endsWith(".kt") } })
    }

    @Test fun packagedNativeContractsMatchEverySupportedDefinitionAndPreserveTheAdvertisedArguments() {
        val packaged = AppFeatureInventory.packagedNativeTools
        val supported = (GitHubMarketplaceCatalog.packages + GitHubMarketplaceCatalog.legacyPackages).filter { it.runtime == MarketplaceRuntime.NATIVE && NativeMarketplaceCatalog.supports(it) }
        val definitions = supported.flatMap(NativeMarketplaceCatalog::definitions)
        assertEquals(definitions.size, packaged.size)
        assertEquals(definitions.associateBy { it.name }, packaged)
        assertTrue(AppFeatureInventory.toolProducers.keys.containsAll(packaged.keys))
        assertTrue(packaged.keys.all { name -> AppFeatureInventory.toolProducers.getValue(name).any { it.endsWith("/NativeMarketplaceCatalog.kt") } })
        assertEquals(setOf("query", "location"), (packaged.getValue("places_ticketmaster__events").inputSchema["properties"] as JsonObject).keys)
        assertTrue("places_toronto__datasets" in packaged)
        assertTrue("places_openstreetmap__geocode" in packaged)
        assertTrue("places_nominatim__geocode" in packaged)
        assertFalse("places_ticketmaster__search_events" in packaged)
        assertFalse(packaged.keys.any { it.startsWith("places_mapbox__") })
    }

    @Test fun documentedGitHubFamiliesIncludeTheNativeRegistryAndConditionalRepositoryServices() {
        val catalog = AppFeatureInventory.gitHubCatalog
        assertEquals(GitHubOperationTool.names + setOf("repository_workspace", "repository_graph", GitHubDecisionTool.NAME), catalog.keys)
        assertEquals(GitHubOperationTool.names, AppFeatureInventory.gitHubDescriptions.keys)
        assertTrue(AppFeatureInventory.gitHubDescriptions.values.all(String::isNotBlank))
        assertTrue("callers" in catalog.getValue("repository_graph"))
        assertTrue("run" in catalog.getValue("repository_workspace"))
        assertTrue("merge" in catalog.getValue("github_pr"))
        assertFalse("merge" in catalog.getValue("github_pr_inspect"))
        assertTrue(catalog.getValue(GitHubDecisionTool.NAME).isEmpty())
        assertTrue(AppFeatureInventory.actionsByTool(emptyList()).isEmpty())
    }

    @Test fun liveIndexKeepsOnlyAdvertisedSchemasAndNeverBlendsConnectionsOrHiddenTools() {
        val read = tool("github_pr__work", """{"enum":["headers","context"]}""")
        val merge = tool("github_pr__personal", """{"const":"merge"}""")
        val unknown = tool("remote__query", """{"enum":["find"]}""")
        val hidden = read.copy(name = "github_pr__revoked", modelVisible = false)
        val inputs = listOf(read, merge, unknown, hidden)
        val actual = AppFeatureInventory.actionsByTool(inputs)
        assertEquals(mapOf(read.name to listOf("context", "headers"), merge.name to listOf("merge"), unknown.name to listOf("find")), actual)
        assertFalse(AppFeatureInventory.actionsByTool(inputs + merge.copy(name = read.name)).containsKey(read.name))
        assertEquals(emptyList<String>(), AppFeatureInventory.actionsByTool(listOf(tool("test", """{"const":"outside","enum":["allowed"]}""")))["test"])
    }

    private fun tool(name: String, selector: String) =
        AgentToolDefinition(name, "Selected schema", Json.parseToJsonElement("""{"properties":{"action":$selector}}""") as JsonObject)
}
