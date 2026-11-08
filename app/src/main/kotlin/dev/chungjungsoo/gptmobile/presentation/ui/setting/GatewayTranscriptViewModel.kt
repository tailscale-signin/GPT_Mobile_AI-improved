package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayLocalTranscriptPage
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GatewayTranscriptState(
    val conversationKey: String? = null,
    val page: GatewayLocalTranscriptPage? = null,
    val profileName: String? = null,
    val cursor: String? = null,
    val previousCursors: List<String?> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

/** Local paging and explicit server reads cannot create a chat or submit work. */
@HiltViewModel
class GatewayTranscriptViewModel @Inject constructor(
    private val repository: GatewayConversationRepository,
    private val settings: SettingRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(GatewayTranscriptState())
    val state = mutable.asStateFlow()
    private var reading: Job? = null
    private var generation = 0

    fun open(conversationKey: String) {
        generation++
        reading?.cancel()
        mutable.value = GatewayTranscriptState(conversationKey = conversationKey)
        load(null, emptyList())
    }

    fun next() {
        val current = mutable.value
        val cursor = current.page?.nextCursor ?: return
        if (!current.loading) load(cursor, current.previousCursors + current.cursor)
    }

    fun previous() {
        val current = mutable.value
        if (!current.loading && current.previousCursors.isNotEmpty()) load(current.previousCursors.last(), current.previousCursors.dropLast(1))
    }

    fun reconnect() {
        if (!mutable.value.loading) load(null, emptyList(), reconnect = true)
    }

    fun pause() {
        generation++
        reading?.cancel()
        mutable.value = mutable.value.copy(loading = false)
    }

    private fun load(cursor: String?, previous: List<String?>, reconnect: Boolean = false) {
        val key = mutable.value.conversationKey ?: return
        val requestGeneration = generation
        mutable.value = mutable.value.copy(loading = true, error = null)
        reading = viewModelScope.launch {
            var connectionError: String? = null
            try {
                val page = withContext(Dispatchers.IO) {
                    if (reconnect) {
                        try {
                            repository.refreshTranscript(key)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            connectionError = failure.message ?: "The original gateway is unavailable. Retained text remains readable."
                        }
                    }
                    repository.transcriptPage(key, cursor)
                }
                check(page.conversationKey == key && (page.nextCursor == null || page.nextCursor != cursor && page.nextCursor !in previous)) { "The saved transcript cursor did not advance." }
                val name = withContext(Dispatchers.IO) { settings.fetchPlatformV2s().singleOrNull { it.uid == page.profileUid }?.name }
                if (requestGeneration == generation) mutable.value = GatewayTranscriptState(key, page, name, cursor, previous, error = connectionError)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (requestGeneration == generation) mutable.value = mutable.value.copy(error = failure.message ?: "The saved transcript is unavailable.")
            } finally {
                if (requestGeneration == generation) mutable.value = mutable.value.copy(loading = false)
            }
        }
    }
}
