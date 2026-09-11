package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolsSelectionScreen(
    platformUid: String,
    viewModel: PlatformSettingViewModel,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platform by viewModel.platformState.collectAsStateWithLifecycle()
    val toolBindingState by viewModel.toolBindingState.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedProviderFilter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (toolBindingState.mcpToolOptions.isEmpty() && !toolBindingState.isMcpToolsLoading) {
            viewModel.openMcpToolsDialog()
        }
    }

    val platformData = platform

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.mcp_server),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = !toolBindingState.isMcpToolsLoading,
                        onClick = {
                            viewModel.saveMcpTools()
                            onNavigationClick()
                        }
                    ) {
                        Text(stringResource(R.string.save), fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Master & Granular Disable Tool Cards
            if (platformData != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (platformData.disableAllTools) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.disable_all_tools),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (platformData.disableAllTools) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.disable_all_tools_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = platformData.disableAllTools,
                                onCheckedChange = { viewModel.toggleDisableAllTools() }
                            )
                        }

                        if (!platformData.disableAllTools) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.disable_remote_tools),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(R.string.disable_remote_tools_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = platformData.disableRemoteTools,
                                    onCheckedChange = { viewModel.toggleDisableRemoteTools() }
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Calculate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.disable_local_tools),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(R.string.disable_local_tools_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = platformData.disableLocalTools,
                                    onCheckedChange = { viewModel.toggleDisableLocalTools() }
                                )
                            }
                        }
                    }
                }
            }

            // Search Bar for Tools
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Filter MCP Tools") },
                placeholder = { Text("Search by tool name, server, description...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Provider filter chips
            val allServerNames = remember(toolBindingState.mcpConnections, toolBindingState.mcpToolOptions) {
                val fromConnections = toolBindingState.mcpConnections.map { it.name }
                val fromTools = toolBindingState.mcpToolOptions.map { it.connectionName }
                (fromConnections + fromTools).distinct().sorted()
            }

            if (allServerNames.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedProviderFilter == null,
                        onClick = { selectedProviderFilter = null },
                        label = { Text("All Providers (${allServerNames.size})") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )

                    allServerNames.forEach { serverName ->
                        val isSelected = selectedProviderFilter == serverName
                        val brandColor = mcpProviderBrandColor(serverName)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedProviderFilter = if (isSelected) null else serverName
                            },
                            label = { Text(serverName) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(brandColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = mcpProviderMark(serverName),
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = brandColor.copy(alpha = 0.25f),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }

            if (toolBindingState.isMcpToolsLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Discovering MCP tools from servers...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (toolBindingState.mcpConnections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Text("MCP", modifier = Modifier.padding(20.dp), fontWeight = FontWeight.Black, fontSize = 20.sp)
                        }
                        Text(
                            text = stringResource(R.string.no_tool_connections),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Add tool connections in Settings > Tool Connections to use them here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val filteredOptions = toolBindingState.mcpToolOptions.filter { option ->
                    val matchesQuery = searchQuery.isBlank() ||
                        option.toolName.contains(searchQuery, ignoreCase = true) ||
                        option.connectionName.contains(searchQuery, ignoreCase = true) ||
                        option.description?.contains(searchQuery, ignoreCase = true) == true
                    val matchesProvider = selectedProviderFilter == null ||
                        option.connectionName.equals(selectedProviderFilter, ignoreCase = true)
                    matchesQuery && matchesProvider
                }

                val connectionsToShow = toolBindingState.mcpConnections.filter { connection ->
                    selectedProviderFilter == null || connection.name.equals(selectedProviderFilter, ignoreCase = true)
                }

                val groupedByUid = filteredOptions.groupBy { it.connectionUid }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    connectionsToShow.forEach { connection ->
                        val toolsForConnection = groupedByUid[connection.connectionUid].orEmpty()
                        val allToolsForConnection = toolBindingState.mcpToolOptions.filter { it.connectionUid == connection.connectionUid }
                        val activeInServer = allToolsForConnection.count { opt ->
                            toolBindingState.pendingMcpTools.any {
                                it.connectionUid == connection.connectionUid && it.toolName == opt.toolName
                            }
                        }

                        item(key = "server-card-${connection.connectionUid}") {
                            McpProviderSectionCard(
                                connection = connection,
                                tools = toolsForConnection,
                                totalToolCount = allToolsForConnection.size,
                                activeToolCount = activeInServer,
                                isPlatformDisabled = platformData?.disableAllTools == true || platformData?.disableRemoteTools == true,
                                pendingMcpTools = toolBindingState.pendingMcpTools,
                                onToggleTool = { toolName ->
                                    viewModel.toggleMcpTool(connection.connectionUid, toolName)
                                },
                                onToggleAllInServer = { enable ->
                                    allToolsForConnection.forEach { option ->
                                        val selected = toolBindingState.pendingMcpTools.any {
                                            it.connectionUid == connection.connectionUid && it.toolName == option.toolName
                                        }
                                        if (selected != enable) {
                                            viewModel.toggleMcpTool(connection.connectionUid, option.toolName)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpProviderSectionCard(
    connection: dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection,
    tools: List<PlatformSettingViewModel.McpToolOption>,
    totalToolCount: Int,
    activeToolCount: Int,
    isPlatformDisabled: Boolean,
    pendingMcpTools: Set<dev.chungjungsoo.gptmobile.data.repository.ToolBindingSelection>,
    onToggleTool: (String) -> Unit,
    onToggleAllInServer: (Boolean) -> Unit
) {
    val brandColor = mcpProviderBrandColor(connection.name)
    var isExpanded by remember { mutableStateOf(true) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "providerArrow"
    )
    val allEnabled = totalToolCount > 0 && activeToolCount == totalToolCount

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (activeToolCount > 0) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(brandColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mcpProviderMark(connection.name),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connection.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${connection.alias} • $totalToolCount tool${if (totalToolCount != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (activeToolCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = brandColor.copy(alpha = 0.18f)
                            ) {
                                Text(
                                    text = "$activeToolCount active",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = brandColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                if (totalToolCount > 0 && !isPlatformDisabled) {
                    Switch(
                        checked = allEnabled,
                        onCheckedChange = { onToggleAllInServer(it) },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(arrowRotation)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (tools.isEmpty()) {
                        Text(
                            text = if (totalToolCount == 0) "No tools discovered for this server." else "No tools match search filter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    } else {
                        tools.forEach { option ->
                            val isSelected = pendingMcpTools.any {
                                it.connectionUid == option.connectionUid && it.toolName == option.toolName
                            }
                            McpToolCard(
                                option = option,
                                isSelected = isSelected,
                                isPlatformDisabled = isPlatformDisabled,
                                onToggle = { onToggleTool(option.toolName) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToolCard(
    option: PlatformSettingViewModel.McpToolOption,
    isSelected: Boolean,
    isPlatformDisabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = !isPlatformDisabled
    val icon = mcpToolIconForName(option.toolName)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.Check else icon,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.toolName.replace('_', ' '),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                option.description?.takeIf(String::isNotBlank)?.let { desc ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = option.modelToolName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Switch(
                checked = isSelected,
                enabled = enabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

private fun mcpProviderMark(name: String): String = when {
    name.contains("github", true) -> "GH"
    name.contains("brave", true) -> "B"
    name.contains("google", true) -> "G"
    name.contains("slack", true) -> "S"
    name.contains("notion", true) -> "N"
    name.contains("filesystem", true) || name.contains("file", true) -> "FS"
    name.contains("search", true) -> "SE"
    name.contains("terminal", true) || name.contains("shell", true) -> "SH"
    name.contains("memory", true) -> "ME"
    name.contains("postgres", true) || name.contains("sql", true) -> "DB"
    else -> name.trim().take(2).uppercase().ifBlank { "MC" }
}

private fun mcpProviderBrandColor(name: String): Color = when {
    name.contains("github", true) -> Color(0xFF24292F)
    name.contains("brave", true) -> Color(0xFFFB542B)
    name.contains("google", true) -> Color(0xFF4285F4)
    name.contains("slack", true) -> Color(0xFF611F69)
    name.contains("notion", true) -> Color(0xFF37352F)
    name.contains("filesystem", true) || name.contains("file", true) -> Color(0xFF7C4DFF)
    name.contains("search", true) -> Color(0xFF00796B)
    name.contains("postgres", true) || name.contains("sql", true) -> Color(0xFF336791)
    name.contains("terminal", true) -> Color(0xFF212121)
    else -> Color(0xFF006C67)
}

private fun mcpToolIconForName(toolName: String): ImageVector {
    val lower = toolName.lowercase()
    return when {
        lower.contains("search") || lower.contains("find") || lower.contains("query") -> Icons.Default.Search
        lower.contains("build") || lower.contains("compile") || lower.contains("run") -> Icons.Default.Build
        lower.contains("code") || lower.contains("script") || lower.contains("git") -> Icons.Default.Code
        lower.contains("file") || lower.contains("read") || lower.contains("write") || lower.contains("dir") -> Icons.Default.Folder
        lower.contains("terminal") || lower.contains("exec") || lower.contains("cmd") || lower.contains("bash") -> Icons.Default.Terminal
        lower.contains("calc") || lower.contains("math") -> Icons.Default.Calculate
        lower.contains("web") || lower.contains("http") || lower.contains("fetch") || lower.contains("url") -> Icons.Default.Language
        else -> Icons.Default.Extension
    }
}
