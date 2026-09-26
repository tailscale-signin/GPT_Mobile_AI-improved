package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.McpBrowserData
import dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@HiltViewModel
class McpBrowserViewModel @Inject constructor(private val resolver: AgentToolResolver, private val client: McpClientManager) : ViewModel() {
    val catalog = MutableStateFlow(McpBrowserData())
    val text = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    fun browse(connection: ToolConnection) = action {
        catalog.value = client.browse(resolver.mcpConfig(connection))
        text.value = "Choose a resource or prompt to preview. Nothing is inserted into a chat automatically."
    }
    fun read(connection: ToolConnection, uri: String) = action { text.value = client.readResource(resolver.mcpConfig(connection), uri) }
    fun prompt(connection: ToolConnection, name: String, arguments: String) = action {
        val values = Json.parseToJsonElement(arguments).jsonObject.mapValues { it.value.jsonPrimitive.content }
        require(values.size <= 32 && arguments.length <= 16000)
        text.value = client.getPrompt(resolver.mcpConfig(connection), name, values)
    }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                withTimeout(20_000) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                text.value = "Could not load this MCP content. Check connection diagnostics and required prompt arguments."
            } finally {
                busy.value = false
            }
        }
    }
}

@Composable
fun McpBrowserDialog(connection: ToolConnection, onDismiss: () -> Unit, model: McpBrowserViewModel = hiltViewModel()) {
    val catalog by model.catalog.collectAsStateWithLifecycle()
    val text by model.text.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var arguments by remember(connection) { mutableStateOf("{}") }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(connection.connectionUid) { model.browse(connection) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.mcp_browser_dialog_label_1)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("${connection.name} · ${catalog.serverName}")
            if (catalog.verifiedAt > 0) Text("Last connected: ${java.time.Instant.ofEpochMilli(catalog.verifiedAt)}")
            if (busy) LinearProgressIndicator()
            catalog.resources.forEach { resource -> TextButton(enabled = !busy, onClick = { model.read(connection, resource.uri) }) { Text(resource.name) } }
            if (catalog.prompts.isNotEmpty()) OutlinedTextField(arguments, { arguments = it.take(16000) }, label = { Text(stringResource(R.string.mcp_browser_dialog_label_2)) })
            catalog.prompts.forEach { prompt ->
                Text("${prompt.name}: ${prompt.arguments?.joinToString { it.name }.orEmpty()}")
                TextButton(enabled = !busy, onClick = { model.prompt(connection, prompt.name, arguments) }) { Text(stringResource(R.string.mcp_browser_dialog_label_3)) }
            }
            androidx.compose.foundation.text.selection.SelectionContainer { Text(text) }
            TextButton(enabled = text.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(stringResource(R.string.mcp_browser_dialog_label_4)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mcp_browser_dialog_label_5)) } })
}
