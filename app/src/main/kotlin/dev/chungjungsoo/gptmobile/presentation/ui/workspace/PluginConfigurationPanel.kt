package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.workspace.PluginConfiguration
import dev.chungjungsoo.gptmobile.data.workspace.PortablePluginConfiguration
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PluginConfigurationViewModel @Inject constructor(private val settings: SettingRepository, private val connections: ToolConnectionRepository, private val trust: dev.chungjungsoo.gptmobile.data.permissions.ToolTrustStore) : ViewModel() {
    val grants = MutableStateFlow(trust.grants())
    fun revoke(key: String) = launch {
        trust.revokeGrant(key)
        grants.value = trust.grants()
    }
    val text = MutableStateFlow("")
    val notice = MutableStateFlow("")
    val preview = MutableStateFlow<PortablePluginConfiguration?>(null)
    fun export() = launch {
        text.value = PluginConfiguration.export(settings.getFeatureSettings().pluginExecution, connections.listConnections())
        notice.value = "Schema v1. Credentials, URL paths/queries, grants and active bindings are excluded."
    }
    fun validate() = launch {
        preview.value = PluginConfiguration.decode(text.value)
        notice.value = "Validated for schema v1. Review before applying."
    }
    fun apply() = launch {
        val config = requireNotNull(preview.value)
        val current = settings.getFeatureSettings()
        settings.updateFeatureSettings(current.copy(pluginExecution = current.pluginExecution + config.plugins))
        // Connection metadata is a setup checklist, never an automatically trusted active connection.
        notice.value = "${config.plugins.size} plugin configurations applied. Set up ${config.connections.size} listed connections in Plugins & remote MCP; re-enter endpoints and credentials there."
        preview.value = null
    }
    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            notice.value = error.message.orEmpty()
            preview.value = null
        }
    }
}

@Composable
fun PluginConfigurationPanel(model: PluginConfigurationViewModel = hiltViewModel()) {
    val text by model.text.collectAsState()
    val notice by model.notice.collectAsState()
    val preview by model.preview.collectAsState()
    val grants by model.grants.collectAsState()
    var grantQuery by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            WorkspaceCard("Portable plugin settings") {
                Text("Versioned, validated configuration. Each plugin keeps its existing controls in Plugins & remote MCP.")
                Row {
                    Button(onClick = { model.export() }) { Text("Export configuration") }
                    TextButton(onClick = { model.validate() }) { Text("Validate import") }
                }
                SelectionContainer {
                    OutlinedTextField(text, {
                        model.text.value = it
                        model.preview.value = null
                    }, label = { Text("Configuration JSON") }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp), maxLines = 14)
                }
                Text(notice)
            }
        }
        item {
            WorkspaceCard("Remembered tool permissions") {
                OutlinedTextField(grantQuery, { grantQuery = it }, label = { Text("Search by conversation, repository or tool") })
                grants.filter { it.second.contains(grantQuery, true) }.forEach { (key, label) ->
                    Row {
                        Text(label, Modifier.weight(1f))
                        TextButton(onClick = { model.revoke(key) }) { Text("Revoke") }
                    }
                }
                if (grants.isEmpty()) Text("No active remembered grants.")
                Text("Tool catalog changes revoke remembered grants. One-hour grants are tied to a conversation, schema and supplied repository/action.", style = MaterialTheme.typography.bodySmall)
            }
        }
        preview?.let { config ->
            item {
                WorkspaceCard("Import review · schema ${config.schemaVersion}") {
                    config.plugins.forEach { (id, options) -> Text("$id · ${options.timeoutSeconds}s · ${options.maxOutputCharacters} characters · ${options.searchResults} search results") }
                    config.connections.forEach { Text("${it.name} · ${it.type} · ${it.origin}\nEndpoint required${if (it.requiresCredential) "; credential required" else ""}") }
                    Button(onClick = { model.apply() }) { Text("Apply validated plugin options") }
                }
            }
        }
    }
}
