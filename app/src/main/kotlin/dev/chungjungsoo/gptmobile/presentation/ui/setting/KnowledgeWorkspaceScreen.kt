package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeProject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeWorkspaceScreen(viewModel: KnowledgeWorkspaceViewModel, onBack: () -> Unit) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val chats by viewModel.chatRooms.collectAsStateWithLifecycle()
    val links by viewModel.links.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editedProject by remember { mutableStateOf<KnowledgeProject?>(null) }
    var name by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var deleteProject by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selected?.let { viewModel.importDocument(it, uri) }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.knowledge_workspaces)) }, navigationIcon = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.queue_cancel)) }
        })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.knowledge_description))
                TextButton(enabled = !busy, onClick = {
                    editing = true
                    editedProject = null
                    name = ""
                    instructions = ""
                }) { Text(stringResource(R.string.knowledge_create)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it) }
            }
            items(projects, key = { it.id }) { project ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        TextButton(onClick = { selected = if (selected == project.id) null else project.id }) { Text(project.name) }
                        if (selected == project.id) {
                            Text(project.instructions)
                            TextButton(enabled = !busy, onClick = {
                                editedProject = project
                                name = project.name
                                instructions = project.instructions
                                editing = true
                            }) { Text(stringResource(R.string.queue_edit)) }
                            TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("text/*", "application/pdf", "application/json")) }) { Text(stringResource(R.string.knowledge_import)) }
                            documents.filter { it.projectId == project.id }.forEach { document ->
                                Text(document.title)
                                TextButton(enabled = !busy, onClick = { viewModel.deleteDocument(document.id) }) { Text(stringResource(R.string.queue_remove)) }
                            }
                            Text(stringResource(R.string.knowledge_chats))
                            chats.forEach { chat ->
                                val assigned = links.firstOrNull { it.chatId == chat.id }?.projectId == project.id
                                TextButton(enabled = !busy, onClick = { viewModel.attach(chat.id, if (assigned) null else project.id) }) {
                                    Text((if (assigned) "✓ " else "+ ") + chat.title)
                                }
                            }
                            TextButton(enabled = !busy, onClick = { deleteProject = project.id }) { Text(stringResource(R.string.knowledge_delete)) }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.knowledge_chat_documents)) }
            items(documents.filter { it.chatId != null }, key = { it.id }) { document ->
                Text(document.title)
                TextButton(enabled = !busy, onClick = { viewModel.deleteDocument(document.id) }) { Text(stringResource(R.string.queue_remove)) }
            }
        }
    }
    if (editing) {
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.knowledge_workspaces)) }, text = {
            Column {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.knowledge_name)) })
                OutlinedTextField(instructions, { instructions = it.take(8000) }, label = { Text(stringResource(R.string.knowledge_instructions)) })
            }
        }, confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                viewModel.save(editedProject, name, instructions)
                editing = false
            }) { Text(stringResource(R.string.queue_save)) }
        }, dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.queue_cancel)) } })
    }
    deleteProject?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteProject = null },
            title = { Text(stringResource(R.string.knowledge_delete)) },
            text = { Text(stringResource(R.string.knowledge_delete_note)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProject(id)
                    deleteProject = null
                }) { Text(stringResource(R.string.queue_remove)) }
            },
            dismissButton = { TextButton(onClick = { deleteProject = null }) { Text(stringResource(R.string.queue_cancel)) } }
        )
    }
}
