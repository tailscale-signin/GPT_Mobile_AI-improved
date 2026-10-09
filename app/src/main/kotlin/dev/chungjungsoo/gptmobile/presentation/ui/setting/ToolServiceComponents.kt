package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.marketplace.NativePluginInstallation
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.data.model.ToolServiceDefinition
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.ui.mcp.McpBrandAssets
import java.util.Locale

internal data class ToolServiceItem(
    val definition: ToolServiceDefinition,
    val integrated: Boolean,
    val packages: List<GitHubMarketplacePackage>,
    val connections: List<ToolConnection>
) {
    val id get() = definition.id
    val hasPlugin get() = integrated || packages.isNotEmpty() || connections.any { it.type != ToolConnectionType.MCP }
    val hasMcp get() = connections.any { it.type == ToolConnectionType.MCP }

    fun requiredFields(installations: Map<String, NativePluginInstallation>): List<String> = buildList {
        if (id == ToolPluginId.AMAZON_SEARCH && connections.none { it.type == ToolConnectionType.AMAZON_SERPAPI || it.type == ToolConnectionType.MCP }) add("Connect SerpApi with an API key or a remote Amazon MCP provider")
        if (id == ToolPluginId.GOOGLE_PLACES && connections.isEmpty() && packages.isEmpty()) add("Install Google Places from Marketplace with an API key, or connect Google Maps Grounding MCP")
        packages.forEach { entry ->
            val installation = installations[entry.id]
            if (entry.provider == "openstreetmap") {
                listOf("geocode" to "Geocoding", "restrooms" to "Restroom Search").forEach { (operation, label) ->
                    if (operation !in installation?.disabledOperations.orEmpty() && !dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog.validEndpoint(installation?.endpoints?.get(operation).orEmpty())) add("$label endpoint required")
                }
            } else if (installation?.ready(entry) != true) {
                add("${entry.preset.name}: complete required settings")
            }
        }
        connections.forEach { connection ->
            if (connection.endpointUrl.isNullOrBlank()) add("${connection.name}: endpoint required")
            if (connection.authType != ToolConnectionAuthType.NONE && connection.secretRef == null) {
                add(if (connection.authType == ToolConnectionAuthType.OAUTH) "${connection.name}: sign in required" else "${connection.name}: API key required")
            }
        }
    }
}

/** Group by the same identity used by the resolver; connection IDs remain intact. */
internal fun toolServiceItems(connections: List<ToolConnection>, installations: Map<String, NativePluginInstallation>): List<ToolServiceItem> {
    val installed = installations.keys.mapNotNull(GitHubMarketplaceCatalog::find)
    val definitions = ToolServiceCatalog.integrated + installed.map(ToolServiceCatalog::forPackage) + connections.map(ToolServiceCatalog::forConnection)
    return definitions.distinctBy { it.id }.map { definition ->
        ToolServiceItem(
            definition,
            ToolServiceCatalog.integrated.any { it.id == definition.id },
            installed.filter { ToolServiceCatalog.forPackage(it).id == definition.id },
            connections.filter { ToolServiceCatalog.forConnection(it).id == definition.id }
        )
    }
}

internal enum class ToolkitFilter(val label: String) { ALL("All"), PLUGINS("Plugins"), MCP("Remote MCP") }
internal enum class ToolkitSort(val label: String) { NAME("Name A–Z"), ENABLED("Enabled first"), SETUP("Needs setup first") }

internal fun sortedToolServices(
    services: List<ToolServiceItem>,
    query: String,
    filter: ToolkitFilter,
    sort: ToolkitSort,
    enabled: (ToolServiceItem) -> Boolean,
    needsSetup: (ToolServiceItem) -> Boolean
): List<ToolServiceItem> {
    val words = query.trim()
    val matching = services.filter { service ->
        (filter == ToolkitFilter.ALL || (filter == ToolkitFilter.PLUGINS && service.hasPlugin) || (filter == ToolkitFilter.MCP && service.hasMcp)) &&
            (words.isEmpty() || (listOf(service.definition.name, service.definition.description) + service.connections.map { "${it.name} ${it.alias}" } + service.packages.flatMap { it.preset.toolCapabilities }).any { it.contains(words, true) })
    }
    val names = compareBy<ToolServiceItem> { it.definition.name.lowercase(Locale.ROOT) }.thenBy { it.id }
    return when (sort) {
        ToolkitSort.NAME -> matching.sortedWith(names)
        ToolkitSort.ENABLED -> matching.sortedWith(compareByDescending<ToolServiceItem>(enabled).then(names))
        ToolkitSort.SETUP -> matching.sortedWith(compareByDescending<ToolServiceItem>(needsSetup).then(names))
    }
}

@Composable
internal fun ToolkitControls(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: ToolkitFilter,
    onFilterChange: (ToolkitFilter) -> Unit,
    sort: ToolkitSort,
    onSortChange: (ToolkitSort) -> Unit,
    showFilters: Boolean = true
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            query,
            onQueryChange,
            label = { Text("Find a service or tool") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        )
        if (showFilters) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolkitFilter.entries.forEach { option ->
                    FilterChip(selected = option == filter, onClick = { onFilterChange(option) }, label = { Text(option.label) })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp))
                Text(sort.label, Modifier.padding(horizontal = 8.dp))
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ToolkitSort.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        onSortChange(option)
                        menu = false
                    })
                }
            }
        }
    }
}

@Composable
internal fun ToolServiceIcon(definition: ToolServiceDefinition, modifier: Modifier = Modifier) {
    val icon = when (definition.iconName) {
        "delegation" -> Icons.Rounded.Psychology
        "memory" -> Icons.Rounded.Memory
        "schedule" -> Icons.Rounded.Schedule
        "calculator" -> Icons.Rounded.Calculate
        "folder" -> Icons.Rounded.FolderOpen
        "web" -> Icons.Rounded.Language
        "search", "perplexity" -> Icons.Rounded.Search
        "location", "refuge", "toronto", "tomtom", "arcgis", "foursquare", "openrouteservice" -> Icons.Rounded.LocationOn
        "mcp" -> Icons.Rounded.Hub
        "shopping" -> Icons.Rounded.ShoppingCart
        "news" -> Icons.Rounded.Newspaper
        "airbnb" -> Icons.Rounded.Home
        else -> Icons.Rounded.Extension
    }
    Surface(modifier.size(46.dp), shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            val drawable = McpBrandAssets.drawableFor(definition.iconName)
            if (drawable != null) {
                Icon(painterResource(drawable), null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun ToolServiceCard(
    service: ToolServiceItem,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    status: String,
    required: List<String> = emptyList(),
    toggleEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(service.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolServiceIcon(service.definition)
                Column(Modifier.weight(1f).clickable { expanded = !expanded }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(service.definition.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = toggleEnabled)
            }
            if (required.isNotEmpty()) {
                Text(required.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expanded) "Hide details" else service.definition.description,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Collapse ${service.definition.name}" else "Expand ${service.definition.name}")
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(service.definition.description, style = MaterialTheme.typography.bodyMedium)
                    content()
                }
            }
        }
    }
}
