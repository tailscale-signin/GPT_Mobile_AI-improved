package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.knowledge.AttachmentLibraryRepository
import dev.chungjungsoo.gptmobile.data.knowledge.MemoryDocumentRepository
import dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository
import dev.chungjungsoo.gptmobile.data.rag.FactVaultSettings
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class FactVaultViewModel @Inject constructor(
    private val repository: FactVaultRepository,
    private val documentsRepository: MemoryDocumentRepository,
    private val library: AttachmentLibraryRepository
) : ViewModel() {
    val vault = repository.state
    val documents = documentsRepository.documents
    val attachments = library.attachments
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    init {
        refresh()
    }
    fun refresh() = perform { repository.load() }
    fun updateSettings(settings: FactVaultSettings) = perform { repository.updateSettings(settings) }
    fun setEnabled(enabled: Boolean) = perform { repository.setEnabled(enabled) }
    fun setFactEnabled(id: String, enabled: Boolean) = perform { repository.setFactEnabled(id, enabled) }
    fun pin(id: String, pinned: Boolean) = perform { repository.pin(id, pinned) }
    fun delete(id: String) = perform { repository.deleteFact(id) }
    fun saveFact(text: String, id: String? = null) = perform { repository.saveManual(text, id) }
    fun clear() = perform { repository.clear() }
    fun removeDocument(id: String) = perform { documentsRepository.dao.deleteDocument(id) }
    fun indexDocuments() = perform { _status.value = "${library.indexAll()} documents indexed locally." }

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
