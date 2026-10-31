package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@HiltViewModel
class GitHubEvidenceViewModel @Inject constructor(private val workspace: WorkspaceRepository) : ViewModel() {
    private val mutable = MutableStateFlow<List<WorkspaceRecord>>(emptyList())
    val rows = mutable.asStateFlow()
    private val content = MutableStateFlow<Pair<String, String>?>(null)
    val selected = content.asStateFlow()
    private var job: Job? = null
    private var chatId: Int? = null
    fun bind(id: Int) {
        if (chatId == id) return
        chatId = id
        job?.cancel()
        content.value = null
        job = viewModelScope.launch { workspace.dao.observeGitHubEvidence(id).collectLatest { mutable.value = it } }
    }
    fun inspect(row: WorkspaceRecord) {
        if (row.chatId != chatId) return
        val currentChat = chatId
        viewModelScope.launch {
            val text = try {
                val payload = workspace.protectedSynthesisCheckpoint(row.id)
                if (payload == null) {
                    "Receipt unavailable. Refresh the source; this is not evidence of success."
                } else {
                    Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload).let {
                        if (it.length > 16_000) it.take(16_000) + "\n[Preview limited to 16,000 characters; full encrypted receipt remains retained.]" else it
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "Protected receipt could not be opened. Its verification status is unknown."
            }
            if (chatId == currentChat) content.value = row.id to text
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitHubEvidenceButton(chatId: Int) {
    val model: GitHubEvidenceViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    LaunchedEffect(chatId) { model.bind(chatId) }
    val rows by model.rows.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    var open by remember(chatId) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Verified Engineering · Evidence") }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
                item { Text("Verified fields are exact receipt observations. Inferred statements are interpretations. Unknown, stale and contradicted claims are not proof of completion.") }
                if (rows.isEmpty()) item { Text("No saved receipts yet. Temporary chats keep evidence only for the active request.") }
                item { Text("Latest ${rows.size} retained records. Older receipts remain saved with the conversation.") }
                items(rows, key = { it.id }) { row ->
                    Column(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { model.inspect(row) }) { Text("${row.title} · ${java.time.Instant.ofEpochMilli(row.updatedAt)}") }
                        if (selected?.first == row.id) Text(selected?.second.orEmpty())
                    }
                }
            }
        }
    }
}
