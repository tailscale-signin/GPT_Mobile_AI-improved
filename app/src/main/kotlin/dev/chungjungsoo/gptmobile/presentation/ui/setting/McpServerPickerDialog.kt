package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chungjungsoo.gptmobile.R

/** Rich, server-first MCP picker used by AI model settings. */
@Composable
fun FancyMcpToolsDialog(
    toolBindingState: PlatformSettingViewModel.ToolBindingState,
    settingViewModel: PlatformSettingViewModel
) {
    if (!toolBindingState.isMcpToolsDialogOpen) return

    var expandedServers by remember(toolBindingState.isMcpToolsDialogOpen) {
        mutableStateOf(emptySet<String>())
    }
    val groups = toolBindingState.mcpToolOptions.groupBy { it.connectionUid }

    AlertDialog(
        shape = RoundedCornerShape(28.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "MCP servers",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Choose a server, then fine-tune its tools",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    toolBindingState.mcpConnections.isEmpty() -> EmptyMcpState()
                    toolBindingState.isMcpToolsLoading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp)
                            .semantics { contentDescription = "Discovering MCP tools" },
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    groups.isEmpty() -> EmptyMcpState()
                    else -> groups.forEach { (connectionUid, tools) ->
                        val connectionName = tools.first().connectionName
                        val selectedCount = tools.count { option ->
                            toolBindingState.pendingMcpTools.any {
                                it.connectionUid == connectionUid && it.toolName == option.toolName
                            }
                        }
                        val expanded = connectionUid in expandedServers
                        McpServerCard(
                            name = connectionName,
                            toolCount = tools.size,
                            selectedCount = selectedCount,
                            expanded = expanded,
                            onExpand = {
                                expandedServers = if (expanded) {
                                    expandedServers - connectionUid
                                } else {
                                    expandedServers + connectionUid
                                }
                            },
                            onToggleAll = { enable ->
                                tools.forEach { option ->
                                    val selected = toolBindingState.pendingMcpTools.any {
                                        it.connectionUid == connectionUid && it.toolName == option.toolName
                                    }
                                    if (selected != enable) {
                                        settingViewModel.toggleMcpTool(connectionUid, option.toolName)
                                    }
                                }
                            }
                        ) {
                            tools.forEachIndexed { index, option ->
                                val selected = toolBindingState.pendingMcpTools.any {
                                    it.connectionUid == connectionUid && it.toolName == option.toolName
                                }
                                McpToolRow(
                                    name = option.toolName,
                                    description = option.description ?: option.modelToolName,
                                    selected = selected,
                                    onToggle = {
                                        settingViewModel.toggleMcpTool(connectionUid, option.toolName)
                                    }
                                )
                                if (index != tools.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 52.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        onDismissRequest = settingViewModel::closeMcpToolsDialog,
        confirmButton = {
            TextButton(
                enabled = !toolBindingState.isMcpToolsLoading,
                onClick = settingViewModel::saveMcpTools
            ) {
                Text(stringResource(R.string.save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = settingViewModel::closeMcpToolsDialog) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun McpServerCard(
    name: String,
    toolCount: Int,
    selectedCount: Int,
    expanded: Boolean,
    onExpand: () -> Unit,
    onToggleAll: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    val brandColor = mcpBrandColor(name)
    val allEnabled = toolCount > 0 && selectedCount == toolCount

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selectedCount > 0) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .52f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 4.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onExpand)
                .semantics { contentDescription = "$name MCP server, $selectedCount of $toolCount tools enabled" }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(brandColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = providerMark(name),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "$toolCount tools",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (selectedCount > 0) {
                        Surface(
                            color = brandColor.copy(alpha = .14f),
                            shape = CircleShape
                        ) {
                            Text(
                                text = "$selectedCount enabled",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = brandColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            androidx.compose.material3.Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = allEnabled,
                        role = Role.Switch,
                        onValueChange = onToggleAll
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Enable all tools", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (allEnabled) "Every tool is available to this model" else "Grant access to all $toolCount tools",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = allEnabled, onCheckedChange = null)
            }
            Surface(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .72f)
            ) {
                Column { content() }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun McpToolRow(
    name: String,
    description: String,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name.replace('_', ' '),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyMcpState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Text("MCP", modifier = Modifier.padding(18.dp), fontWeight = FontWeight.Black)
        }
        Text(stringResource(R.string.no_tool_connections), fontWeight = FontWeight.SemiBold)
    }
}

private fun providerMark(name: String): String = when {
    name.contains("github", true) -> "GH"
    name.contains("brave", true) -> "B"
    name.contains("google", true) -> "G"
    name.contains("slack", true) -> "S"
    name.contains("notion", true) -> "N"
    name.contains("filesystem", true) || name.contains("file", true) -> "F"
    else -> name.trim().take(2).uppercase().ifBlank { "M" }
}

private fun mcpBrandColor(name: String): Color = when {
    name.contains("github", true) -> Color(0xFF24292F)
    name.contains("brave", true) -> Color(0xFFFB542B)
    name.contains("google", true) -> Color(0xFF4285F4)
    name.contains("slack", true) -> Color(0xFF611F69)
    name.contains("notion", true) -> Color(0xFF37352F)
    name.contains("filesystem", true) || name.contains("file", true) -> Color(0xFF7C4DFF)
    else -> Color(0xFF006C67)
}
