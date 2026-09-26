package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeProject
import dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeProjectChat
import dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeWorkspaceRepository
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class KnowledgeWorkspaceViewModel @Inject constructor(
    private val repository: KnowledgeWorkspaceRepository,
    private val chats: ChatRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    val projects = repository.projects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val documents = repository.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val links = repository.chatLinks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val chatRooms = MutableStateFlow<List<dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    init {
        action { chatRooms.value = chats.fetchChatListV2() }
    }
    fun save(project: KnowledgeProject?, name: String, instructions: String) = action {
        require(name.isNotBlank() && instructions.length <= 8000)
        if (project == null) repository.createProject(name, instructions) else repository.dao.saveProject(project.copy(name = name.take(100), instructions = instructions))
    }
    fun deleteProject(id: String) = action { repository.dao.deleteProject(id) }
    fun deleteDocument(id: String) = action { repository.dao.deleteDocument(id) }
    fun attach(chatId: Int, projectId: String?) = action {
        if (projectId == null) repository.dao.detachChat(chatId) else repository.dao.attachChat(KnowledgeProjectChat(chatId, projectId))
    }
    fun importDocument(projectId: String, uri: Uri) = action {
        val resolver = context.contentResolver
        val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Document"
        val file = File.createTempFile("knowledge-", ".input", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 20 * 1024 * 1024) { "Choose a document smaller than 20 MiB." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Could not open document.")
            val extracted = dev.chungjungsoo.gptmobile.util.DocumentTextExtractor.extract(context, file, resolver.getType(uri) ?: "text/plain")
            repository.index(title, extracted.text, projectId = projectId, explicitlyRestore = true)
        } finally {
            file.delete()
        }
    }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = "Could not update the workspace. Check the document format or available storage."
            } finally {
                busy.value = false
            }
        }
    }
}
