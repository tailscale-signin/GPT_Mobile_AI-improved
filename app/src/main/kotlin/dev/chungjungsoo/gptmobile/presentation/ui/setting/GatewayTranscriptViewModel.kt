package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayMessageRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayWorkStore
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GatewayTranscriptState(
    val conversation: GatewayConversationRecord? = null,
    val rows: List<GatewayMessageRecord> = emptyList(),
    val partsRetained: Map<String, Int> = emptyMap(),
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class GatewayTranscriptViewModel @Inject constructor(private val store: GatewayWorkStore, private val repository: GatewayConversationRepository) : ViewModel() {
    private val mutable = MutableStateFlow(GatewayTranscriptState())
    val state = mutable.asStateFlow()
    private var key: String? = null
    private val pages = mutableListOf(0L)
    private var page = 0

    fun open(conversationKey: String) {
        if (key == conversationKey && mutable.value.conversation != null) return
        key = conversationKey
        pages.clear()
        pages += 0L
        page = 0
        load()
    }

    fun next() {
        if (!mutable.value.hasNext || mutable.value.loading) return
        val after = mutable.value.rows.last().sequence
        page++
        if (pages.size <= page) pages += after else pages[page] = after
        load()
    }

    fun previous() {
        if (page == 0 || mutable.value.loading) return
        page--
        load()
    }

    fun reconnect() = load(reconnect = true)

    private fun load(reconnect: Boolean = false) = viewModelScope.launch {
        val id = key ?: return@launch
        mutable.value = mutable.value.copy(loading = true, error = null)
        var failure: String? = null
        try {
            val retained = requireNotNull(store.dao.conversation(id)) { "This retained conversation is unavailable." }
            if (reconnect) {
                try {
                    repository.reconnectSaved(retained)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failure = error.message
                }
            }
            val rows = store.dao.transcriptPage(id, pages[page])
            val counts = rows.map { it.logicalMessageId }.distinct().associateWith { store.dao.partCount(id, it) }
            val cursor = store.dao.cursor(id)
            store.dao.markSeen(id, rows.lastOrNull()?.sequence ?: 0, retained.revision)
            if (key == id) mutable.value = GatewayTranscriptState(store.dao.conversation(id), rows, counts, rows.lastOrNull()?.sequence?.let { it < (cursor?.sequence ?: 0) } == true, page > 0, error = failure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (key == id) mutable.value = mutable.value.copy(error = error.message)
        } finally {
            if (key == id) mutable.value = mutable.value.copy(loading = false)
        }
    }
}
