package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.home.HomeToolGroup
import dev.chungjungsoo.gptmobile.data.model.HomeToolSubject
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog

@Composable
internal fun HomeToolSelectionDialog(
    groups: List<HomeToolGroup>,
    selected: List<HomeToolSubject>,
    onDismiss: () -> Unit,
    onApply: (List<HomeToolSubject>) -> Unit
) {
    var draft by remember { mutableStateOf(selected.toSet()) }
    var query by remember { mutableStateOf("") }
    val knownParents = groups.map { it.subject }.toSet()
    val missing = draft.filter { it.copy(toolName = null) !in knownParents }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose plugins and tools") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search plugins and tools") }, singleLine = true)
                TextButton(onClick = { draft = emptySet() }) { Text("Clear") }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(groups.filter { it.name.contains(query, true) || it.tools.any { name -> name.contains(query, true) } }, key = { "${it.subject.kind}:${it.subject.ownerUid}" }) { group ->
                        val parent = group.subject
                        val children = (group.tools.map { parent.copy(toolName = it) } + draft.filter { it.copy(toolName = null) == parent && it.toolName != null }).distinct()
                        val targets = children.ifEmpty { listOf(parent) }
                        val count = targets.count { it in draft }
                        val state = when {
                            count == targets.size -> ToggleableState.On
                            count > 0 || parent in draft -> ToggleableState.Indeterminate
                            else -> ToggleableState.Off
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            TriStateCheckbox(state, onClick = {
                                draft = if (state == ToggleableState.On) draft - targets.toSet() - parent else draft - parent + targets
                            })
                            Column {
                                Text(group.name)
                                Text(group.status.label)
                                group.lastResult?.let { Text(it.label, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        children.forEach { child ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(child in draft, role = Role.Checkbox) {
                                    draft = if (it) draft - parent + child else draft - child
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(child in draft, onCheckedChange = null)
                                Text(requireNotNull(child.toolName) + if (group.catalogKnown && child.toolName !in group.tools) " · unavailable" else "")
                            }
                        }
                    }
                    items(missing, key = { "missing:${it.kind}:${it.ownerUid}:${it.toolName}" }) { subject ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(true, role = Role.Checkbox) { draft = draft - subject }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(true, onCheckedChange = null)
                            Text("${subject.toolName ?: subject.ownerUid} · unavailable")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft.toList()) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
