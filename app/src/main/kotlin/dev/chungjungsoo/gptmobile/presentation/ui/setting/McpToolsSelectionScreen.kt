package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.data.repository.ToolBindingSelection
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

/** Select tools within provider bundles; MCP always executes on the connected server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolsSelectionScreen(
    platformUid: String,
    viewModel: PlatformSettingViewModel,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platform by viewModel.platformState.collectAsStateWithLifecycle()
    val bindings by viewModel.toolBindingState.collectAsStateWithLifecycle()
    val features by viewModel.featureSettings.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ToolkitSort.NAME) }
    LaunchedEffect(platformUid) { viewModel.openMcpToolsDialog() }
    val services = bindings.mcpConnections.groupBy { ToolServiceCatalog.forConnection(it).id }.values.map { connections ->
        ToolServiceItem(ToolServiceCatalog.forConnection(connections.first()), false, emptyList(), connections)
    }
    fun selected(service: ToolServiceItem) = bindings.pendingMcpTools.any { selection -> service.connections.any { it.connectionUid == selection.connectionUid } }
    val filtered = sortedToolServices(services, "", ToolkitFilter.MCP, sort, ::selected) { it.requiredFields(emptyMap()).isNotEmpty() }.filter { service ->
        search.isBlank() ||
            service.definition.name.contains(search, true) ||
            bindings.mcpToolOptions.any { option ->
                service.connections.any { it.connectionUid == option.connectionUid } && "${option.toolName} ${option.description.orEmpty()} ${option.connectionName}".contains(search, true)
            }
    }
    val enabled = platform?.enabled == true && platform?.disableAllTools != true && platform?.disableRemoteTools != true && features.remoteMcpConnections
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = { Text("MCP Tools", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.closeMcpToolsDialog()
                        onNavigationClick()
                    }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                actions = { TextButton(enabled = !bindings.isMcpToolsLoading, onClick = { viewModel.saveMcpTools(onSaved = onNavigationClick) }) { Text("Save", fontWeight = FontWeight.SemiBold) } },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Tools for ${platform?.name.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                    Text("Select a whole service or choose individual tools. Your selections apply only to this AI profile.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ToolkitControls(search, { search = it }, ToolkitFilter.MCP, {}, sort, { sort = it }, showFilters = false)
                    if (!enabled) Text("Enable this profile's online tools and Remote MCP to use these selections.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (bindings.isMcpToolsLoading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("Discovering server tools…")
                    }
                }
            } else if (filtered.isEmpty()) {
                item { Text(if (services.isEmpty()) "Connect an MCP server in Plugins & Tools first." else "No services or tools match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                filtered.forEach { service ->
                    item(key = service.id) {
                        val tools = bindings.mcpToolOptions.filter { option -> service.connections.any { it.connectionUid == option.connectionUid } }
                        val active = tools.count { ToolBindingSelection(it.connectionUid, it.toolName) in bindings.pendingMcpTools }
                        val serviceEnabled = enabled && features.isToolPluginEnabled(service.id)
                        ToolServiceCard(
                            service,
                            checked = tools.isNotEmpty() && active == tools.size,
                            onCheckedChange = { checked -> tools.forEach { viewModel.togglePendingMcpTool(it.connectionUid, it.toolName, checked) } },
                            status = "$active / ${tools.size} selected · Remote MCP",
                            required = service.requiredFields(emptyMap()),
                            toggleEnabled = serviceEnabled && tools.isNotEmpty()
                        ) {
                            service.connections.forEach { connection ->
                                val connectionTools = tools.filter { it.connectionUid == connection.connectionUid }
                                Text(connection.name, style = MaterialTheme.typography.titleSmall)
                                if (connectionTools.isEmpty()) Text("No tools discovered. Check the connection's setup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                connectionTools.filter { search.isBlank() || service.definition.name.contains(search, true) || "${it.toolName} ${it.description.orEmpty()}".contains(search, true) }.forEach { option ->
                                    McpSelectionRow(
                                        option,
                                        ToolBindingSelection(option.connectionUid, option.toolName) in bindings.pendingMcpTools,
                                        serviceEnabled && features.isToolPluginEnabled(ToolPluginId.connection(connection.connectionUid))
                                    ) { viewModel.toggleMcpTool(option.connectionUid, option.toolName) }
                                }
                            }
                        }
                    }
                }
            }
            bindings.errorMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
private fun McpSelectionRow(option: PlatformSettingViewModel.McpToolOption, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    var expanded by rememberSaveable(option.connectionUid, option.toolName) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(option.toolName.replace('_', ' '), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Hide tool details" else "Tool details")
            }
        }
        AnimatedVisibility(expanded) {
            Text(option.description.orEmpty().ifBlank { "No description supplied by the server." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
