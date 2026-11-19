package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.AnswerDataset

@Composable
internal fun AnswerRelationshipDiagram(data: AnswerDataset) {
    val nodes = remember(data) { data.rows.flatten().distinct() }
    var selected by rememberSaveable(data) { mutableStateOf(nodes.first()) }
    var menu by remember { mutableStateOf(false) }
    val incoming = remember(data, selected) { data.rows.filter { it[1] == selected }.map { it[0] } }
    val outgoing = remember(data, selected) { data.rows.filter { it[0] == selected }.map { it[1] } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (incoming.isNotEmpty()) {
            DiagramNeighbors(incoming, "Incoming to $selected") { selected = it }
            Text("↓", Modifier.clearAndSetSemantics { })
        }
        Box {
            Button(onClick = { menu = true }) {
                Text(selected, Modifier.clearAndSetSemantics { contentDescription = "Selected node $selected. Choose another node." })
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                nodes.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        selected = name
                        menu = false
                    })
                }
            }
        }
        if (outgoing.isNotEmpty()) {
            Text("↓", Modifier.clearAndSetSemantics { })
            DiagramNeighbors(outgoing, "Outgoing from $selected") { selected = it }
        }
    }
}

@Composable
private fun DiagramNeighbors(nodes: List<String>, direction: String, select: (String) -> Unit) {
    Text(direction, style = MaterialTheme.typography.labelSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        nodes.forEach { name ->
            OutlinedButton(onClick = { select(name) }) { Text(name) }
        }
    }
}
