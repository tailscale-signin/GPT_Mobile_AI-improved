package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeProject
import dev.chungjungsoo.gptmobile.data.knowledge.ProjectWorkspaceRepository

@Composable
internal fun ProjectWorkspacePanel(viewModel: FactVaultViewModel, open: (Int, Int?) -> Unit) {
    val projects by viewModel.projects.collectAsStateWithLifecycle(emptyList())
    val chats by viewModel.projectChats.collectAsStateWithLifecycle()
    val links by viewModel.projectLinks.collectAsStateWithLifecycle(emptyList())
    val profiles by viewModel.projectProfiles.collectAsStateWithLifecycle(emptyList())
    val documents by viewModel.documents.collectAsStateWithLifecycle(emptyList())
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<KnowledgeProject?>(null) }
    var organizing by remember { mutableStateOf<KnowledgeProject?>(null) }
    var deleting by remember { mutableStateOf<KnowledgeProject?>(null) }
    var sharing by remember { mutableStateOf<KnowledgeProject?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsHero("WORKSPACES", "Keep projects together", "Separate instructions, memories and shared documents.")
        TextButton(onClick = { editing = ProjectWorkspaceRepository.draft() }, enabled = !busy) { Text("Create project") }
        projects.forEach { project ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.name, style = MaterialTheme.typography.titleLarge)
                    Text("${links.count { it.projectId == project.id }} conversations · ${documents.count { it.projectId == project.id }} documents", style = MaterialTheme.typography.labelMedium)
                    Text(if (project.includePersonalMemory) "Project + personal memory" else "Project memory only", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { viewModel.createProjectChat(project) { open(it, null) } }, enabled = !busy) { Text("New chat") }
                        TextButton(onClick = { editing = project }, enabled = !busy) { Text("Settings") }
                        TextButton(onClick = { organizing = project }, enabled = !busy) { Text("Conversations") }
                        TextButton(onClick = { sharing = project }, enabled = !busy) { Text("Documents") }
                        TextButton(onClick = { deleting = project }, enabled = !busy) { Text("Delete") }
                    }
                }
            }
        }
    }
    deleting?.let { project ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${project.name}?") }, text = { Text("Deletes project memories, instructions and shared document copies. Conversations and their own attachments remain and return to personal scope. New messages may create personal memories.") }, confirmButton = {
            TextButton(onClick = {
                viewModel.deleteProject(project.id)
                deleting = null
            }) { Text("Delete project") }
        }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    editing?.let { original ->
        var draft by remember(original) { mutableStateOf(original) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (original.name.isEmpty()) "New project" else "Project settings") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(80)) }, label = { Text("Name") }, singleLine = true) }
                    item { OutlinedTextField(draft.instructions, { draft = draft.copy(instructions = it.take(12000)) }, label = { Text("Instructions") }, minLines = 3, maxLines = 8) }
                    item {
                        Row {
                            Text("Include personal memory", Modifier.weight(1f))
                            Switch(draft.includePersonalMemory, { draft = draft.copy(includePersonalMemory = it) })
                        }
                        Text("Default model & tool profile", style = MaterialTheme.typography.labelLarge)
                        Text("New chats use this profile’s model and plugin/MCP bindings.", style = MaterialTheme.typography.bodySmall)
                    }
                    items(profiles.filter { it.enabled }, key = { it.uid }) { profile ->
                        FilterChip(draft.defaultProfileUid == profile.uid, { draft = draft.copy(defaultProfileUid = profile.uid) }, label = { Text(profile.name.ifBlank { profile.model }) })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveProject(draft)
                    editing = null
                }, enabled = draft.name.isNotBlank() && !busy) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
    organizing?.let { project ->
        AlertDialog(
            onDismissRequest = { organizing = null },
            title = { Text(project.name) },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    if (chats.isEmpty()) item { Text("Create a conversation to add it here.") }
                    items(chats, key = { it.id }) { chat ->
                        val selected = links.any { it.chatId == chat.id && it.projectId == project.id }
                        Row {
                            TextButton(onClick = { open(chat.id, null) }, modifier = Modifier.weight(1f)) { Text(chat.title) }
                            Switch(selected, { viewModel.attachProject(chat.id, if (it) project.id else null) }, enabled = !busy)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { organizing = null }) { Text("Done") } }
        )
    }
    sharing?.let { project ->
        AlertDialog(
            onDismissRequest = { sharing = null },
            title = { Text("Shared documents") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    if (documents.isEmpty()) item { Text("Index a conversation attachment in Documents first.") }
                    items(documents.filter { it.chatId != null || it.projectId == project.id }, key = { it.id }) { document ->
                        Column {
                            Text(document.title)
                            if (document.projectId == project.id) {
                                TextButton(onClick = { viewModel.removeDocument(document.id) }, enabled = !busy) { Text("Remove from project") }
                            } else {
                                TextButton(onClick = { viewModel.shareDocument(document.id, project.id) }, enabled = !busy) { Text("Copy to project") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sharing = null }) { Text("Done") } }
        )
    }
}
