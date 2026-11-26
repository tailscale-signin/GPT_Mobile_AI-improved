package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import java.util.Locale

/** Matches an explicitly named product against the owner of an already authorized tool contract. */
internal class IndexedFeatureToolSelection(query: String, original: String = query) {
    private val selected = mutableSetOf<String>()
    private val excluded = mutableSetOf<String>()
    val hasNamedProduct: Boolean get() = selected.isNotEmpty()

    init {
        val request = normalize(query)
        val task = if (query == original) request else normalize(original)
        for ((id, labels) in productLabels) {
            if (labels.any { label -> declinedPhrases.any { prefix -> "$prefix$label " in task || "$prefix$label " in request } }) {
                excluded += id
            } else if (labels.any { label ->
                    " $label " in request && (label !in ambiguousNames || invocationPhrases.any { "$it$label " in request } || " $label plugin " in request)
                }
            ) {
                selected += id
            }
        }
        if (offlinePhrases.any { it in task || it in request }) {
            excluded += networkProducts
        }
    }

    fun selectsPreset(id: String?): Boolean = id != null && id in selected && id !in excluded
    fun excludesPreset(id: String?): Boolean = id != null && id in excluded
    fun selects(tool: AgentToolDefinition): Boolean = selectsPreset(owner(tool))
    fun excludes(tool: AgentToolDefinition): Boolean = excludesPreset(owner(tool))

    private fun owner(tool: AgentToolDefinition): String? = tool.discoveryPresetId ?: localPlugins[tool.name]

    companion object {
        private val separators = Regex("[^\\p{L}\\p{N}]+")
        private fun normalize(value: String): String = " " + separators.replace(value.take(8_000).lowercase(Locale.ROOT), " ").trim() + " "
        private val declinedPhrases = listOf("without ", "without the ", "without using ", "without using the ", "avoid ", "skip ", "exclude ", "no ", "do not use ", "don t use ", "do not use the ", "don t use the ")
        private val invocationPhrases = listOf("use ", "using ", "via ", "with the ", "ask ", "search with ")
        private val offlinePhrases = listOf(" offline only ", " without the web ", " without network ", " use only the attached ", " using only the attached ")
        private val ambiguousNames = setOf("github", "files", "file", "news", "time", "memory", "filesystem", "search", "fetch", "thinking", "maps", "toronto", "refuge", "weather")

        // These fixed, unnamespaced names are only emitted by the local plugins. MCP tools are namespaced.
        private val localPlugins = mapOf(
            BuiltInAgentTool.CALCULATE_EXPRESSION to ToolPluginId.CALCULATOR,
            BuiltInAgentTool.CURRENT_DATE to ToolPluginId.CURRENT_DATE,
            BuiltInAgentTool.READ_FILE_SLICE to ToolPluginId.READ_FILES,
            BuiltInAgentTool.DEVICE_LOCATION to ToolPluginId.DEVICE_LOCATION,
            BuiltInAgentTool.READ_URL to ToolPluginId.READ_URL
        )
        private val executablePresets by lazy { GitHubMarketplaceCatalog.allPresets.filterNot { it.documentationOnly } }
        private val productLabels by lazy {
            val byId = GitHubMarketplaceCatalog.packages.associateBy { it.id }
            buildMap {
                for (preset in executablePresets) {
                    val names = listOfNotNull(preset.name, preset.id, preset.alias, byId[preset.id]?.provider)
                    put(preset.id, names.map { normalize(it).trim() }.filter { it.length >= 3 }.toSet())
                }
                for (plugin in ToolServiceCatalog.integrated.filter { it.id in localPlugins.values }) {
                    put(plugin.id, setOf(normalize(plugin.name).trim(), normalize(plugin.id).trim()))
                }
            }
        }
        private val networkProducts by lazy {
            executablePresets.filter { it.integratedTool == null && it.id != "optional-universal-marketplace" }.mapTo(mutableSetOf()) { it.id } +
                ToolServiceCatalog.integrated.filter { it.usesNetwork }.map { it.id }
        }
    }
}
