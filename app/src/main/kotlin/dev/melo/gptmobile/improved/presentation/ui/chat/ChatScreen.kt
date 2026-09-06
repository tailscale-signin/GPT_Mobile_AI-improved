package dev.melo.gptmobile.improved.presentation.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.domain.model.Message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var showDeleteSessionDialog by remember { mutableStateOf(false) }
    var messageToDelete by remember { mutableStateOf<Message?>(null) }
    var messageToEdit by remember { mutableStateOf<Message?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showToolSelectionSheet by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris?.forEach { uri ->
            viewModel.addAttachment(uri)
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.sessionTitle.ifBlank { stringResource(R.string.new_chat) },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showToolSelectionSheet = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_build),
                            contentDescription = stringResource(R.string.select_tools)
                        )
                    }
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_edit),
                            contentDescription = stringResource(R.string.rename_session_title)
                        )
                    }
                    IconButton(onClick = { showDeleteSessionDialog = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.delete_session_title)
                        )
                    }
                }
            )
        },
        modifier = modifier.imePadding()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        onCopyClick = { text ->
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                        },
                        onDeleteClick = { msg ->
                            messageToDelete = msg
                        },
                        onRegenerateClick = if (message == uiState.messages.lastOrNull { !it.isUser }) {
                            { viewModel.regenerateLastResponse() }
                        } else null,
                        onEditClick = { msg ->
                            messageToEdit = msg
                        }
                    )
                }
            }

            ChatAttachmentDraftRow(
                drafts = uiState.attachmentDrafts,
                onRemoveDraft = viewModel::removeAttachmentDraft,
                onDraftClick = {}
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_attach_file),
                        contentDescription = stringResource(R.string.attach_file)
                    )
                }

                OutlinedTextField(
                    value = uiState.inputMessage,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(text = stringResource(R.string.type_message)) },
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { viewModel.sendMessage() },
                    enabled = (uiState.inputMessage.isNotBlank() || uiState.attachmentDrafts.isNotEmpty()) && !uiState.isLoading
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_send),
                            contentDescription = stringResource(R.string.send)
                        )
                    }
                }
            }
        }
    }

    if (showDeleteSessionDialog) {
        DeleteSessionDialog(
            onConfirm = {
                showDeleteSessionDialog = false
                viewModel.deleteCurrentSession()
                onNavigateBack()
            },
            onDismiss = { showDeleteSessionDialog = false }
        )
    }

    messageToDelete?.let { msg ->
        DeleteMessageDialog(
            message = msg,
            onConfirm = {
                viewModel.deleteMessage(it)
                messageToDelete = null
            },
            onDismiss = { messageToDelete = null }
        )
    }

    messageToEdit?.let { msg ->
        EditMessageDialog(
            message = msg,
            onConfirm = { message, newContent ->
                viewModel.editMessage(message, newContent)
                messageToEdit = null
            },
            onDismiss = { messageToEdit = null }
        )
    }

    if (showRenameDialog) {
        RenameSessionDialog(
            currentTitle = uiState.sessionTitle,
            onConfirm = { newTitle ->
                viewModel.renameSession(newTitle)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    if (showToolSelectionSheet) {
        ChatToolSelectionBottomSheet(
            availableTools = uiState.availableTools,
            selectedToolIds = uiState.selectedToolIds,
            onToolToggled = viewModel::toggleToolSelection,
            onDismiss = { showToolSelectionSheet = false }
        )
    }
}
