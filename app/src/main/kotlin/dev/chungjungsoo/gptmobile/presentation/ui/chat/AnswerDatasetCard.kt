package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.AnswerDataset
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AnswerDatasetCard(data: AnswerDataset) {
    var column by rememberSaveable(data) { mutableIntStateOf(-1) }
    var descending by rememberSaveable(data) { mutableStateOf(false) }
    var limit by rememberSaveable(data) { mutableIntStateOf(8) }
    var visual by rememberSaveable(data) { mutableStateOf(data.type == "bars" || data.type == "diagram") }
    val ordered = remember(data, column, descending) { data.ordered(column, descending) }
    if (data.type == "places") {
        val map = remember(data) {
            val places = data.rows.mapIndexed { index, row -> dev.chungjungsoo.gptmobile.data.agent.tool.NearbyPlace(index.toString(), row[0], row[1].toDouble(), row[2].toDouble(), 0.0) }
            val first = places.first()
            LocationMapData(dev.chungjungsoo.gptmobile.data.agent.tool.MapCoordinate(first.latitude, first.longitude), places, null, showOrigin = false, title = data.title)
        }
        EmbeddedLocationMap(map)
        return
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(data.title, style = MaterialTheme.typography.titleMedium)
            if (data.type == "sources") {
                val handler = androidx.compose.ui.platform.LocalUriHandler.current
                var openFailed by remember(data) { mutableStateOf(false) }
                ordered.take(limit).forEach { row ->
                    TextButton(onClick = { openFailed = runCatching { handler.openUri(row[1]) }.isFailure }) {
                        Text("${row[0]} · ${AnswerDataset.sourceHost(row[1])}")
                    }
                }
                if (openFailed) Text("Could not open this source.", color = MaterialTheme.colorScheme.error)
            } else if (visual && data.type == "diagram") {
                AnswerRelationshipDiagram(data)
            } else if (visual) {
                val maximum = remember(data) { data.rows.maxOf { it[1].toDouble() }.takeIf { it > 0 } ?: 1.0 }
                ordered.take(limit).forEach { row ->
                    Text("${row[0]} · ${row[1]} ${data.columns[1]}")
                    Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth((row[1].toDouble() / maximum).toFloat()).height(6.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            } else {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row {
                        data.columns.forEachIndexed { index, label ->
                            val state = when {
                                column != index -> "Unsorted"
                                descending -> "Descending"
                                else -> "Ascending"
                            }
                            TextButton(
                                onClick = {
                                    descending = column == index && !descending
                                    column = index
                                },
                                modifier = Modifier.width(136.dp).semantics { stateDescription = state }
                            ) { Text(label + if (column == index) (if (descending) " ↓" else " ↑") else "") }
                        }
                    }
                    ordered.take(limit).forEach { row ->
                        HorizontalDivider()
                        Row { row.forEach { value -> Text(value, Modifier.width(136.dp).padding(6.dp)) } }
                    }
                }
            }
            if (limit < ordered.size && !(visual && data.type == "diagram")) TextButton(onClick = { limit += 8 }) { Text("Show more rows (${ordered.size - limit} remaining)") }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (data.type == "bars" || data.type == "diagram") {
                    TextButton(onClick = { visual = !visual }) {
                        Text(
                            if (visual) {
                                "View table"
                            } else if (data.type == "diagram") {
                                "View diagram"
                            } else {
                                "View chart"
                            }
                        )
                    }
                }
                val csv = remember(data, ordered) { data.csv(ordered) }
                SaveAnswerText("Save CSV", "answer.csv", "text/csv", csv)
            }
        }
    }
}

/** Bind a picker result to the exact content visible when the user requested the export. */
@Composable
internal fun SaveAnswerText(label: String, filename: String, mime: String, content: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        val snapshot = pending
        pending = null
        if (uri != null && snapshot != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(snapshot) } != null
                    } catch (_: IOException) {
                        false
                    } catch (_: SecurityException) {
                        false
                    }
                }
                Toast.makeText(context, if (saved) "File saved" else "Could not write the file", Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (content.length <= 65536) {
        TextButton(onClick = {
            pending = content
            launcher.launch(filename)
        }) { Text(label) }
    }
}
