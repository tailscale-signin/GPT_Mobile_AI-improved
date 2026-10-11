package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.AvailableChatTool
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatToolSelectionBottomSheet(
    tools: List<AvailableChatTool>,
    config: ChatMcpToolConfig,
    onToolToggled: (String) -> Unit,
    onEnableAll: () -> Unit,
    onDisableAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val visibleTools = tools.filter { it.name.contains(query, true) || it.description.contains(query, true) || it.source.contains(query, true) }
    dev.chungjungsoo.gptmobile.presentation.designsystem.AppChoiceSheet(
        onDismissRequest = onDismiss,
        title = { Text("Conversation tools") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${tools.count { config.isToolEnabled(it.id) }} of ${tools.size} selected · Changes apply immediately", style = MaterialTheme.typography.bodyMedium)
                Text("Selection does not grant permission to run a tool.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                dev.chungjungsoo.gptmobile.presentation.designsystem.AppSearchField(query, { query = it }, "Find tools")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEnableAll) { Text("Enable all") }
                    TextButton(onClick = onDisableAll) { Text("Disable all") }
                }
                if (visibleTools.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No tools currently registered",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleTools, key = { it.id }) { tool ->
                            val isEnabled = config.isToolEnabled(tool.id)
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToolToggled(tool.id) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isEnabled) {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.outlineVariant
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Build,
                                            contentDescription = null,
                                            tint = if (isEnabled) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Column {
                                            Text(
                                                text = tool.name,
                                                style = MaterialTheme.typography.titleSmall
                                            )

                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Text(
                                                    text = tool.source,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        if (tool.description.isNotEmpty()) {
                                            Text(
                                                text = tool.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2
                                            )
                                        }
                                    }

                                    Switch(
                                        checked = isEnabled,
                                        onCheckedChange = { onToolToggled(tool.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}
