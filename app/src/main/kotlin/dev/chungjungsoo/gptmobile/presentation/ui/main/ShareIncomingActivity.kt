package dev.chungjungsoo.gptmobile.presentation.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.sharing.IncomingShare
import dev.chungjungsoo.gptmobile.data.sharing.ShareInbox
import dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** Keep edits through rotation without putting the user's text in the Activity state bundle. */
class ShareReviewViewModel : androidx.lifecycle.ViewModel() {
    var draft by mutableStateOf<String?>(null)
}

/** Import and review only. A share never starts an AI request or remote upload. */
@AndroidEntryPoint
class ShareIncomingActivity : dev.chungjungsoo.gptmobile.presentation.ui.main.ProtectedActivity() {
    @Inject lateinit var inbox: ShareInbox

    @Inject lateinit var database: ChatDatabaseV2

    @Inject lateinit var attachments: dev.chungjungsoo.gptmobile.data.repository.AttachmentUploadCoordinator

    @Inject lateinit var documents: dev.chungjungsoo.gptmobile.data.knowledge.MemoryDocumentRepository
    private val editor: ShareReviewViewModel by viewModels()
    private var stagedToken: String? = null
    override fun onSaveInstanceState(outState: Bundle) {
        stagedToken?.let { outState.putString("shareToken", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        stagedToken = savedInstanceState?.getString("shareToken")
        super.onCreate(savedInstanceState)
        setContent {
            GPTMobileTheme {
                Surface(Modifier.fillMaxSize()) {
                    var share by remember { mutableStateOf<IncomingShare?>(null) }
                    var chats by remember { mutableStateOf<List<ChatRoomV2>>(emptyList()) }
                    var profiles by remember { mutableStateOf<List<PlatformV2>>(emptyList()) }
                    var action by rememberSaveable { mutableStateOf("Ask about") }
                    var selectedChat by rememberSaveable { mutableStateOf<Int?>(null) }
                    var selectedProfile by rememberSaveable { mutableStateOf<String?>(null) }
                    val text = editor.draft.orEmpty()
                    var error by remember { mutableStateOf<String?>(null) }
                    var busy by remember { mutableStateOf(true) }
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(Unit) {
                        try {
                            StartupRecoveryGate.await()
                            val token = stagedToken
                            share = if (token == null) {
                                inbox.import(intent).also { stagedToken = inbox.stage(it) }
                            } else {
                                val delivered = database.chatRoomDao().findDeliveredShare(token)
                                if (delivered != null) {
                                    inbox.acknowledge(token)
                                    openChat(delivered)
                                    return@LaunchedEffect
                                }
                                requireNotNull(inbox.consume(token)) { "This share expired. Share the content again to start a new draft." }
                            }
                            if (editor.draft == null) editor.draft = share?.text.orEmpty()
                            chats = database.chatRoomDao().getChatRoomsWithFavorites().filterNot { it.isTemporary }
                            profiles = database.platformDao().getPlatforms().filter { it.enabled }
                            if (selectedProfile == null) selectedProfile = profiles.firstOrNull()?.uid
                            if (token == null) selectedChat = intent.getIntExtra("research_origin_chat", 0).takeIf { id -> id > 0 && chats.any { it.id == id } }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Cannot read the shared content."
                        }
                        busy = false
                    }
                    BackHandler {
                        scope.launch {
                            share?.let { inbox.discard(it) }
                            stagedToken?.let { inbox.acknowledge(it) }
                            finish()
                        }
                    }
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Add to a conversation", style = MaterialTheme.typography.headlineSmall)
                        Text("Review here, then press Send in the conversation.", style = MaterialTheme.typography.bodySmall)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        OutlinedTextField(text, { editor.draft = it.take(16000) }, label = { Text("Message or link") }, maxLines = 5)
                        share?.let { Text("${it.files.size} files imported locally") }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Ask about", "Summarize", "Save to knowledge").forEach { label -> FilterChip(action == label, { action = label }, label = { Text(label) }) }
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            item { FilterChip(selectedChat == null, { selectedChat = null }, label = { Text("New conversation") }) }
                            if (selectedChat == null) items(profiles, key = { it.uid }) { profile -> FilterChip(selectedProfile == profile.uid, { selectedProfile = profile.uid }, label = { Text(profile.name) }) }
                            items(chats, key = { it.id }) { chat -> FilterChip(selectedChat == chat.id, { selectedChat = chat.id }, label = { Text(chat.title) }) }
                        }
                        Button(enabled = !busy && share != null && (selectedChat != null || selectedProfile != null), onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val message = if (action == "Summarize") "Summarize this shared content, citing any attached documents.\n\n$text" else text
                                    val incoming = requireNotNull(share).copy(text = message)
                                    val prepared = incoming.files.map { requireNotNull(attachments.prepareLocalAttachment(this@ShareIncomingActivity, it)) { "Cannot prepare a shared file." } }
                                    val token = requireNotNull(stagedToken)
                                    val chatId = database.withTransaction {
                                        database.chatRoomDao().findDeliveredShare(token)?.let { return@withTransaction it }
                                        val chatId = selectedChat ?: database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Shared conversation", enabledPlatform = listOf(requireNotNull(selectedProfile)))).toInt()
                                        if (action == "Save to knowledge") {
                                            val texts = prepared.mapNotNull { attachment -> attachment.extractedText?.takeIf(String::isNotBlank)?.let { attachment.resolvedDisplayName to it } } + listOfNotNull(text.takeIf(String::isNotBlank)?.let { "Shared text" to it })
                                            require(texts.isNotEmpty()) { "No extractable text. Open the attachments as a draft instead." }
                                            texts.forEach { (title, content) ->
                                                documents.index(title, content, chatId = chatId, projectId = null, sourceKey = stagedToken, explicitlyRestore = true)
                                            }
                                        }
                                        val room = database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).single()
                                        if (room.lastShareToken != token) {
                                            val old = kotlinx.serialization.json.Json.decodeFromString<List<dev.chungjungsoo.gptmobile.data.model.ChatAttachment>>(room.draftAttachments)
                                            database.chatRoomDao().saveComposerDraft(chatId, listOfNotNull(room.draftText?.takeIf(String::isNotBlank), incoming.text.takeIf(String::isNotBlank)).joinToString("\n\n"), kotlinx.serialization.json.Json.encodeToString(old + prepared), System.currentTimeMillis() / 1000)
                                            database.chatRoomDao().markShareDelivered(chatId, token)
                                        }
                                        chatId
                                    }
                                    inbox.acknowledge(token)
                                    openChat(chatId)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Could not open the draft. Try again."
                                    busy = false
                                }
                            }
                        }) { Text(if (action == "Save to knowledge") "Save locally & open draft" else "Open draft") }
                        TextButton(onClick = {
                            scope.launch {
                                share?.let { inbox.discard(it) }
                                stagedToken?.let { inbox.acknowledge(it) }
                                finish()
                            }
                        }) { Text("Cancel") }
                    }
                }
            }
        }
    }

    private fun openChat(chatId: Int) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CHAT_ROOM_ID, chatId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
