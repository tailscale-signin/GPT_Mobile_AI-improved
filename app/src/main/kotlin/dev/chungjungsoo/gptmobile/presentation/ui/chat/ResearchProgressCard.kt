package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.research.ResearchSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResearchProgressCard(sessions: List<ResearchSession>, activeRunIds: Set<String>, stop: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val activeSessions = sessions.filter { it.runId in activeRunIds && !it.snapshot.complete }
    val latest = (activeSessions.ifEmpty { sessions }).maxByOrNull { it.snapshot.updatedAt } ?: return
    val snapshot = latest.snapshot
    val active = latest.runId in activeRunIds && !snapshot.complete
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (active) "Research · ${snapshot.phase}" else "Research evidence", style = MaterialTheme.typography.titleSmall)
            Text("${snapshot.searches} searches · ${snapshot.engineResponses} engine reports · ${snapshot.engineFailures} unavailable", style = MaterialTheme.typography.bodySmall)
            Text("${snapshot.sources.count { it.readable }} read · ${snapshot.sources.count { it.status == "Blocked" }} blocked · ${snapshot.claims.count { it.verdict == "Supported" }} supported claims", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { expanded = true }) { Text("Evidence") }
                if (active) TextButton(onClick = { activeSessions.forEach { stop(it.runId) } }) { Text("Stop and summarize") }
            }
        }
    }
    if (expanded) {
        val uri = LocalUriHandler.current
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Research history", style = MaterialTheme.typography.titleLarge) }
                sessions.sortedByDescending { it.snapshot.updatedAt }.forEach { session ->
                    item(key = session.runId) {
                        Text(session.snapshot.task.take(200), style = MaterialTheme.typography.titleMedium)
                        Text("${session.snapshot.phase} · ${session.snapshot.searches} searches · ${session.snapshot.attempts} page attempts", style = MaterialTheme.typography.bodySmall)
                        session.snapshot.notes.filterNot { it.startsWith("Policy:") }.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    items(session.snapshot.sources, key = { session.runId + it.id }) { source ->
                        OutlinedCard {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(source.title, style = MaterialTheme.typography.titleSmall)
                                Text(source.status + if (source.originId.isNotBlank()) " · Related content: ${source.originId}" else "", style = MaterialTheme.typography.labelSmall)
                                if (source.publishedAt.isNotBlank()) Text("Published: ${source.publishedAt}", style = MaterialTheme.typography.bodySmall)
                                if (source.retrievedAt > 0) Text("Read: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(source.retrievedAt))}", style = MaterialTheme.typography.bodySmall)
                                session.snapshot.claims.filter { it.sourceId == source.id }.forEach { claim ->
                                    Text("${claim.verdict}: ${claim.text}", style = MaterialTheme.typography.bodyMedium)
                                    Text(claim.quote, style = MaterialTheme.typography.bodySmall)
                                }
                                if (source.passage.isNotBlank()) Text(source.passage.take(1800), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { runCatching { if (java.net.URI(source.url).scheme?.lowercase() in setOf("http", "https")) uri.openUri(source.url) } }) { Text("Open source") }
                            }
                        }
                    }
                }
            }
        }
    }
}
