package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.knowledge.AttachmentLibraryRepository
import dev.chungjungsoo.gptmobile.data.knowledge.MemoryDocumentRepository
import dev.chungjungsoo.gptmobile.data.memory.LocalSemanticMemory
import dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository
import dev.chungjungsoo.gptmobile.data.rag.FactVaultSettings
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class FactVaultViewModel @Inject constructor(
    private val repository: FactVaultRepository,
    private val documentsRepository: MemoryDocumentRepository,
    private val library: AttachmentLibraryRepository,
    private val toolConnections: ToolConnectionRepository,
    semanticMemory: LocalSemanticMemory,
    private val workspaces: dev.chungjungsoo.gptmobile.data.knowledge.ProjectWorkspaceRepository
) : ViewModel() {
    private val _connections = MutableStateFlow<List<ToolConnection>>(emptyList())
    val connections = _connections.asStateFlow()
    val vault = repository.state
    val semanticStatus = semanticMemory.status
    val documents = documentsRepository.documents
    val attachments = library.attachments
    val projects = workspaces.projects
    val projectLinks = workspaces.links
    val projectProfiles = workspaces.profiles
    private val _projectChats = MutableStateFlow<List<dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2>>(emptyList())
    val projectChats = _projectChats.asStateFlow()
    fun deleteProject(id: String) = perform {
        repository.forgetScope("project:$id")
        documentsRepository.dao.projectDocuments(id).forEach { documentsRepository.delete(it.id) }
        workspaces.delete(id)
    }
    fun saveProject(project: dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeProject) = perform { workspaces.save(project) }
    fun attachProject(chatId: Int, projectId: String?) = perform { workspaces.attach(chatId, projectId) }
    fun createProjectChat(project: dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeProject, open: (Int) -> Unit) = perform {
        val id = workspaces.createChat(project)
        _projectChats.value = workspaces.chats()
        open(id)
    }
    fun shareDocument(id: String, projectId: String) = perform { documentsRepository.shareWithProject(id, projectId) }
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    init {
        refresh()
    }
    fun refresh() = perform {
        repository.load()
        _projectChats.value = workspaces.chats()
        _connections.value = toolConnections.listConnections().filter { it.type == ToolConnectionType.MCP }
    }
    fun updateSettings(settings: FactVaultSettings) = perform { repository.updateSettings(settings) }
    fun setEnabled(enabled: Boolean) = perform { repository.setEnabled(enabled) }
    fun applyRecommendedControls() = perform {
        val current = repository.state.value.settings
        repository.updateSettings(
            current.copy(
                learningEnabled = true,
                recallEnabled = true,
                learnPreferences = true,
                learnRelationships = true,
                localModelLearning = true,
                rotateAutomaticFacts = true,
                captureSensitivity = 75,
                maxCapturePerMessage = 12,
                maxRecall = 12,
                maxFacts = 4096,
                semanticRecall = true,
                learnRecurringTopics = true,
                allowCloudRecall = true,
                recallTokens = 1536,
                alwaysRecallPinned = true
            )
        )
    }
    fun setFactEnabled(id: String, enabled: Boolean) = perform { repository.setFactEnabled(id, enabled) }
    fun pin(id: String, pinned: Boolean) = perform { repository.pin(id, pinned) }
    fun restructure(ids: Set<String>, replacements: List<String>) = perform { repository.restructure(ids, replacements) }
    fun reviewFacts(ids: Set<String>, enabled: Boolean) = perform { repository.reviewFacts(ids, enabled) }
    fun delete(id: String) = perform { repository.deleteFact(id) }
    fun saveFact(text: String, id: String? = null) = perform { repository.saveManual(text, id) }
    fun clear() = perform { repository.clear() }
    fun rebuildSemanticIndex() = perform { repository.rebuildSemanticIndex() }
    fun removeDocument(id: String) = perform { documentsRepository.delete(id) }
    fun indexDocuments() = perform {
        require(repository.state.value.enabled) { "Enable memory before indexing documents." }
        val result = library.indexAll()
        _status.value = "${result.indexed} documents indexed locally · ${result.skipped} missing, unsupported or empty documents skipped."
    }

    private fun perform(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                action()
                _error.value = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _error.value = if (error is IllegalArgumentException) error.message else "Memory could not be updated. Try again; saved content has been kept."
            } finally {
                _busy.value = false
            }
        }
    }
}
