package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@Composable
internal fun ProfileToolsPanel(
    viewModel: PlatformSettingViewModel,
    platform: PlatformV2,
    supportsTools: Boolean,
    onLocationChange: (Boolean) -> Unit,
    onServiceChange: (String, Boolean, Set<String>) -> Unit,
    onMcpClick: () -> Unit
) {
    val features by viewModel.featureSettings.collectAsStateWithLifecycle()
    val bindings by viewModel.toolBindingState.collectAsStateWithLifecycle()
    val installations by viewModel.pluginInstallations.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ToolkitFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(ToolkitSort.NAME) }
    LaunchedEffect(viewModel) { viewModel.loadToolBindings() }
    val services = toolServiceItems(bindings.connections, installations)
    val toolsAllowed = supportsTools && platform.enabled && !platform.disableAllTools

    fun selected(service: ToolServiceItem): Boolean {
        val allowed = features.isToolPluginSelected(platform.uid, service.id)
        return when {
            service.id == ToolPluginId.DEVICE_LOCATION -> allowed && bindings.deviceLocationEnabled
            !service.hasPlugin && service.hasMcp -> allowed && bindings.selectedMcpTools.any { selected -> service.connections.any { it.connectionUid == selected.connectionUid } }
            else -> allowed
        }
    }
    fun available(service: ToolServiceItem): Boolean = features.isToolPluginEnabled(service.id) &&
        (
            (service.integrated && service.id != ToolPluginId.AMAZON_SEARCH) ||
                service.packages.any { installations[it.id]?.enabled == true && installations[it.id]?.ready(it) == true } ||
                service.connections.any { features.isToolPluginEnabled(ToolPluginId.connection(it.connectionUid)) && !it.endpointUrl.isNullOrBlank() && (it.authType == ToolConnectionAuthType.NONE || it.secretRef != null) }
            )

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProfileToolSwitch("Tools", "Allow this AI profile to use tools", checked = !platform.disableAllTools, enabled = supportsTools && platform.enabled) { viewModel.toggleDisableAllTools() }
                ProfileToolSwitch("On-device tools", "Memory, files, calculator and device features", checked = !platform.disableLocalTools, enabled = toolsAllowed) { viewModel.toggleDisableLocalTools() }
                ProfileToolSwitch("Online tools", "Network plugins and connected MCP servers", checked = !platform.disableRemoteTools, enabled = toolsAllowed) { viewModel.toggleDisableRemoteTools() }
            }
        }
        Text("Choose services for ${platform.name}", style = MaterialTheme.typography.titleMedium)
        Text("Enable a service in Plugins & Tools first, then choose what this AI profile can use. Your choices are saved independently for each profile.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToolkitControls(search, { search = it }, filter, { filter = it }, sort, { sort = it })
        val visible = sortedToolServices(services, search, filter, sort, ::selected) { it.requiredFields(installations).isNotEmpty() }
        if (visible.isEmpty()) Text("No matching services.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        visible.forEach { service ->
            key(service.id) {
                val active = available(service)
                val remoteOnly = !service.hasPlugin && service.hasMcp
                val localAllowed = !platform.disableLocalTools && !service.definition.usesNetwork
                val onlineAllowed = !platform.disableRemoteTools && service.definition.usesNetwork
                val mcpAllowed = !remoteOnly || features.remoteMcpConnections
                ToolServiceCard(
                    service = service,
                    checked = selected(service),
                    onCheckedChange = { enabled ->
                        when (service.id) {
                            ToolPluginId.DEVICE_LOCATION -> onLocationChange(enabled)
                            else -> onServiceChange(service.id, enabled, service.connections.filter { it.type == ToolConnectionType.MCP }.map { it.connectionUid }.toSet())
                        }
                    },
                    status = when {
                        !active -> "Enable this service in Plugins & Tools"
                        !mcpAllowed -> "Remote MCP is disabled in Plugins & Tools"
                        remoteOnly -> "${bindings.selectedMcpTools.count { selection -> service.connections.any { it.connectionUid == selection.connectionUid } }} tools assigned · Remote MCP"
                        service.id == ToolPluginId.AMAZON_SEARCH -> if (selected(service)) "Amazon tools allowed for this profile" else "Optional · Off until selected for this profile"
                        else -> if (service.definition.usesNetwork) "In-app plugin · Uses the network" else "In-app plugin · On device"
                    },
                    required = service.requiredFields(installations),
                    toggleEnabled = toolsAllowed && active && mcpAllowed && (localAllowed || onlineAllowed)
                ) {
                    if (service.id == ToolPluginId.WEB_SEARCH) {
                        OutlinedButton(onClick = viewModel::openSearchBackendDialog, enabled = toolsAllowed) {
                            Icon(Icons.Rounded.Language, null, Modifier.size(18.dp))
                            Text("Search engines & crawlers", Modifier.padding(start = 8.dp))
                        }
                    }
                    if (service.hasMcp) {
                        OutlinedButton(onClick = onMcpClick, enabled = toolsAllowed && !platform.disableRemoteTools && features.remoteMcpConnections) {
                            Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
                            Text("Choose individual MCP tools", Modifier.padding(start = 8.dp))
                        }
                    }
                    service.packages.forEach { entry ->
                        dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog.definitions(entry).forEach { definition ->
                            val operation = definition.name.substringAfterLast("__")
                            val toolId = ToolPluginId.nativeOperation(entry.id, operation)
                            ProfileToolSwitch(operation.replace('_', ' '), entry.preset.name, features.isToolPluginSelected(platform.uid, toolId), toolsAllowed && active) {
                                onServiceChange(toolId, it, emptySet())
                            }
                        }
                    }
                    service.connections.forEach { connection ->
                        ProfileToolSwitch(
                            connection.name,
                            if (connection.type == ToolConnectionType.MCP) "Remote MCP · ${connection.alias}" else "In-app provider · ${connection.alias}",
                            features.isToolPluginSelected(platform.uid, ToolPluginId.connection(connection.connectionUid)),
                            toolsAllowed && active && features.isToolPluginEnabled(ToolPluginId.connection(connection.connectionUid))
                        ) {
                            onServiceChange(ToolPluginId.connection(connection.connectionUid), it, setOf(connection.connectionUid).takeIf { connection.type == ToolConnectionType.MCP }.orEmpty())
                        }
                    }
                    if (!service.hasMcp && service.id != ToolPluginId.WEB_SEARCH && service.packages.isEmpty() && service.connections.isEmpty()) {
                        Icon(Icons.Rounded.Memory, null, Modifier.size(18.dp))
                        Text("Service permission applies only to this AI profile. Configure the service in Plugins & Tools.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileToolSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun ProfileIdentityCard(platform: PlatformV2) {
    Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SmartToy, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(platform.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(platform.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                val type = when (platform.compatibleType) {
                    ClientType.LITERT_LM -> "On device"
                    ClientType.FREE -> "Free provider"
                    else -> "${platform.compatibleType.name.lowercase().replace('_', ' ')} provider"
                }
                Text("$type · ${if (platform.enabled) "Enabled" else "Disabled"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
