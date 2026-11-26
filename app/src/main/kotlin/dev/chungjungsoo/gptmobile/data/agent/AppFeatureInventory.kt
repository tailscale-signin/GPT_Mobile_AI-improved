package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.AmazonLocalTool
import dev.chungjungsoo.gptmobile.data.agent.tool.AmazonNativeTool
import dev.chungjungsoo.gptmobile.data.agent.tool.AmazonSearchTool
import dev.chungjungsoo.gptmobile.data.agent.tool.DeepResearchTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubDecisionTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryWorkspaceTool
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.MarketplaceRuntime
import dev.chungjungsoo.gptmobile.data.health.HealthConnectTools
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.model.AppFeature as FeatureToggle
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchEvidenceTool
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceContract
import dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingsSearchRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Product documentation. Live entitlement, installation and execution are owned by each tool resolver. */
internal data class AppFeature(
    val id: String,
    val title: String,
    val group: String,
    val admission: String,
    val terms: List<String>,
    val tools: List<String> = emptyList(),
    val plugins: List<String> = emptyList(),
    val kind: String = "feature"
)

internal object AppFeatureInventory {
    val pluginIds: Set<String> by lazy { reviewedAppFeatures.flatMap { it.plugins }.toSortedSet() }

    val auditedToolSources: List<String> get() = auditedToolProducerSources

    /** Packaged adapter contracts, including still-supported legacy registrations. Live consent is checked by the resolver. */
    val packagedNativeTools: Map<String, AgentToolDefinition> by lazy {
        (GitHubMarketplaceCatalog.packages + GitHubMarketplaceCatalog.legacyPackages)
            .filter { it.runtime == MarketplaceRuntime.NATIVE && !it.preset.documentationOnly && NativeMarketplaceCatalog.supports(it) }
            .flatMap(NativeMarketplaceCatalog::definitions)
            .associateBy { it.name }
    }

    /** Stable names from shipped producers, including bounded helpers. Connection-discovered aliases are live only. */
    val toolProducers: Map<String, List<String>> by lazy {
        val sources = fixedToolProducerSources.mapValues { it.value.toMutableSet() }.toMutableMap()
        fun register(file: String, names: Collection<String>) {
            val source = "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/$file.kt"
            names.forEach { sources.getOrPut(it) { mutableSetOf() }.add(source) }
        }
        register("agent/tool/AmazonLocalTool", listOf(AmazonLocalTool.HISTORY))
        register("agent/tool/AmazonNativeTool", AmazonNativeTool.names)
        register("agent/tool/AmazonSearchTool", listOf(AmazonSearchTool.SEARCH, AmazonSearchTool.GET_PRODUCTS))
        register("agent/tool/AmazonCombinedTool", listOf(AmazonSearchTool.SEARCH, AmazonSearchTool.GET_PRODUCTS))
        register("agent/tool/DeepResearchTool", DeepResearchTool.OPERATIONS.map { "deep_research_$it" })
        register("research/feature/ResearchEvidenceTool", ResearchEvidenceTool.OPERATIONS.map { "research_$it" })
        register("marketplace/NativeMarketplaceCatalog", packagedNativeTools.keys)
        register("agent/tool/HealthConnectTool", HealthConnectTools.operations.keys.map { HealthConnectTools.PREFIX + it })
        register("agent/tool/UniversalMarketplaceTool", listOf(MarketplaceContract.TOOL))
        register("agent/tool/GitHubOperationTool", GitHubOperationTool.names)
        register("agent/tool/RepositoryGraphTool", listOf("repository_graph"))
        register("agent/tool/RepositoryWorkspaceTool", listOf("repository_workspace"))
        sources.mapValues { it.value.sorted() }.toSortedMap()
    }

    val features: List<AppFeature> by lazy {
        val toggles = FeatureToggle.entries.map { toggle ->
            AppFeature(
                id = "feature.${toggle.name}",
                title = toggle.title,
                group = "Feature settings",
                admission = toggle.description,
                terms = listOf(SettingsSearchRegistry.group(toggle)),
                kind = "toggle"
            )
        }
        val settings = SettingsSearchRegistry.entries.filterNot { it.key.startsWith("feature.") }.map { setting ->
            AppFeature(
                id = "setting.${setting.key}",
                title = setting.title,
                group = "Settings",
                admission = setting.breadcrumb,
                terms = listOf(setting.keywords).filter(String::isNotBlank),
                kind = "setting"
            )
        }
        val services = ToolServiceCatalog.integrated.map { service ->
            AppFeature(
                id = "plugin.${service.id}",
                title = service.name,
                group = "Built-in plugins",
                admission = service.description,
                terms = listOf(service.id),
                plugins = listOf(service.id),
                kind = "plugin"
            )
        }
        val presets = GitHubMarketplaceCatalog.allPresets.filterNot { it.documentationOnly }.map { preset ->
            AppFeature(
                id = "preset.${preset.id}",
                title = preset.name,
                group = "Integration presets",
                admission = preset.description,
                terms = listOf(preset.category.displayName, preset.alias) + preset.toolCapabilities,
                kind = "preset"
            )
        }
        reviewedAppFeatures + toggles + settings + services + presets
    }

    private data class Operation(val description: String, val actions: List<String>)

    private val nativeOperations by lazy {
        GitHubOperationTool.names.associateWith { name ->
            val definition = GitHubOperationTool.definition(name)
            Operation(definition.description, actions(definition.inputSchema))
        }
    }

    val gitHubDescriptions: Map<String, String> by lazy { nativeOperations.mapValues { it.value.description } }

    /** Compiled native product contracts, independent of account, installation and workspace readiness. */
    val gitHubCatalog: Map<String, List<String>> by lazy {
        val operations = nativeOperations.mapValues { it.value.actions }
        val services = mapOf(
            "repository_graph" to actions(RepositoryGraphTool.schema),
            "repository_workspace" to actions(RepositoryWorkspaceTool.schema),
            GitHubDecisionTool.NAME to emptyList()
        )
        (operations + services).toSortedMap()
    }

    /** A dynamically discovered tool can be indexed once its owner has established its source. */
    fun actionsByTool(definitions: Collection<AgentToolDefinition>): Map<String, List<String>> =
        definitions.filter { it.modelVisible }.groupBy { it.name }.mapNotNull { (name, matches) ->
            // Conflicting definitions for the same dispatch name do not identify a single callable contract.
            matches.map { it.inputSchema }.distinct().singleOrNull()?.let { name to actions(it) }
        }.toMap().toSortedMap()

    private fun actions(schema: JsonObject): List<String> {
        val selector = (schema["properties"] as? JsonObject)?.get("action") as? JsonObject ?: return emptyList()
        val choices = selector["enum"] as? JsonArray
        val fixed = selector["const"]
        val values = if (fixed == null) choices.orEmpty() else listOf(fixed).filter { choices == null || it in choices }
        return values.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.distinct().sorted()
    }
}
