package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.catalog.McpPreset
import dev.chungjungsoo.gptmobile.data.catalog.McpPresetCatalog
import dev.chungjungsoo.gptmobile.data.catalog.McpPresetCategory

/**
 * Dialog allowing users to browse, search, filter by category, and install
 * pre-configured MCP (Model Context Protocol) servers from the catalog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun McpMarketplaceDialog(
    installedAliases: Set<String>,
    onInstallPreset: (McpPreset) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<McpPresetCategory?>(null) }

    val filteredPresets = remember(searchQuery, selectedCategory) {
        val base = if (selectedCategory != null) {
            McpPresetCatalog.getByCategory(selectedCategory!!)
        } else {
            McpPresetCatalog.presets
        }
        if (searchQuery.isNotBlank()) {
            base.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.description.contains(searchQuery, ignoreCase = true) ||
                    it.alias.contains(searchQuery, ignoreCase = true)
            }
        } else {
            base
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier.fillMaxWidth(),
        title = {
            Text(
                text = "MCP Server Catalog",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search servers...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Category chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All") }
                    )
                    McpPresetCategory.entries.forEach { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = {
                                selectedCategory = if (selectedCategory == category) null else category
                            },
                            label = { Text(category.displayName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Presets list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredPresets, key = { it.id }) { preset ->
                        val isInstalled = installedAliases.contains(preset.alias)
                        PresetCard(
                            preset = preset,
                            isInstalled = isInstalled,
                            onInstall = { onInstallPreset(preset) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun PresetCard(
    preset: McpPreset,
    isInstalled: Boolean,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = preset.category.displayName,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (preset.defaultEndpoint.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = preset.defaultEndpoint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isInstalled) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Installed",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                IconButton(onClick = onInstall) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Install ${preset.name}",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
