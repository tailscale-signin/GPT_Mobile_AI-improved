package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AppLogPanel() {
    val enabled by AppLogRecorder.enabled.collectAsStateWithLifecycle()
    val entries by AppLogRecorder.entries.collectAsStateWithLifecycle()
    val error by AppLogRecorder.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Track app logs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Switch(enabled, AppLogRecorder::setEnabled)
                }
                Text("Capture app events, errors, model requests, tools, network headers and Android logs for this app.", style = MaterialTheme.typography.bodyMedium)
                Text("Logs stay on this device until you share them. Credentials are redacted and HTTP bodies are excluded. Other app logs may include content; review before sharing. The latest 2 MB is retained.", style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(query, { query = it }, label = { Text("Filter logs") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!errorsOnly, { errorsOnly = false }, label = { Text("All levels") })
            FilterChip(errorsOnly, { errorsOnly = true }, label = { Text("Warnings & errors") })
        }
        Row {
            TextButton(onClick = {
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) { AppLogRecorder.export() } ?: return@launch
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "Share diagnostic logs"
                            )
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(context, "Unable to export logs", Toast.LENGTH_SHORT).show()
                    }
                }
            }) { Text("Export logs") }
            TextButton(onClick = AppLogRecorder::clear) { Text("Clear logs") }
        }
        val visible = entries.filter { (!errorsOnly || it.level in setOf("W", "E", "F")) && (it.tag + it.message).contains(query, ignoreCase = true) }.takeLast(100)
        Text("${visible.size} recent matching events · newest first", style = MaterialTheme.typography.labelMedium)
        if (visible.isEmpty()) Text(if (enabled) "Waiting for matching app events…" else "Enable tracking to record a test session.")
        visible.asReversed().forEach { entry ->
            Card(Modifier.fillMaxWidth()) {
                Text(
                    entry.line(),
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (entry.level) {
                        "E", "F" -> MaterialTheme.colorScheme.error
                        "W" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}
