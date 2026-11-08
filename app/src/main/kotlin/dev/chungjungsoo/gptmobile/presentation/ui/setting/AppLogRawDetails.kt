package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppLogRawDetails(entries: List<AppLogEntry>) {
    // Keep cursors stable if new events join this incident while it is open.
    // Closing and reopening details takes a fresh snapshot.
    val snapshot = remember { entries }
    var cursor by remember { mutableStateOf(AppLogDetailCursor()) }
    var history by remember { mutableStateOf(emptyList<AppLogDetailCursor>()) }
    var page by remember { mutableStateOf<AppLogDetailPage?>(null) }
    LaunchedEffect(cursor) {
        page = null
        page = withContext(Dispatchers.Default) { pageAppLogDetails(snapshot, cursor) }
    }
    Column(Modifier.padding(12.dp)) {
        val current = page
        if (current == null) {
            Text("Loading details…", style = MaterialTheme.typography.labelMedium)
        } else {
            Text("${current.totalRecords} retained events · detail page ${history.size + 1}", style = MaterialTheme.typography.labelMedium)
            current.rows.forEach { row ->
                Text("Event ${row.recordNumber}${if (row.continued) " · continued" else ""}", style = MaterialTheme.typography.labelSmall)
                Text(row.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Row {
                TextButton(onClick = {
                    cursor = history.last()
                    history = history.dropLast(1)
                }, enabled = history.isNotEmpty()) { Text("Previous details") }
                TextButton(onClick = {
                    current.next?.let { next ->
                        history = history + cursor
                        cursor = next
                    }
                }, enabled = current.next != null) { Text("Next details") }
            }
        }
    }
}
