package dev.melo.gptmobile.improved.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import dev.melo.gptmobile.improved.data.model.MarketplaceMcpServer

@Composable
fun McpMarketplaceDialog(
    servers: List<MarketplaceMcpServer>,
    installedUids: Set<String>,
    onDismiss: () -> Unit,
    onInstall: (MarketplaceMcpServer, Map<String, String>) -> Unit
) {
    var selectedServer by remember { mutableStateOf<MarketplaceMcpServer?>(null) }
    var configValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    if (selectedServer != null) {
        val server = selectedServer!!
        AlertDialog(
            onDismissRequest = { selectedServer = null },
            title = { Text(server.name) },
            text = {
                Column {
                    Text(server.description)
                    Spacer(modifier = Modifier.height(16.dp))
                    server.requiredEnvVars.forEach { envVar ->
                        OutlinedTextField(
                            value = configValues[envVar] ?: "",
                            onValueChange = { configValues = configValues + (envVar to it) },
                            label = { Text(envVar) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onInstall(server, configValues)
                        selectedServer = null
                    }
                ) {
                    Text("Install")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedServer = null }) {
                    Text("Cancel")
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("MCP Marketplace") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(servers) { server ->
                        val isInstalled = installedUids.contains(server.id)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(enabled = !isInstalled) {
                                    configValues = emptyMap()
                                    selectedServer = server
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = server.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (isInstalled) {
                                        Text(
                                            text = "Installed",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = server.description,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        )
    }
}
