package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphRepository
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class RepositoryGraphUiState(
    val boundContext: GitHubConversationContext? = null,
    val result: JsonObject? = null,
    val resultOperation: String? = null,
    val architecture: JsonObject? = null,
    val busy: Boolean = false,
    val operation: String = "status",
    val error: String? = null,
    val indexCursor: String? = null,
    val indexingPaused: Boolean = false
)

@HiltViewModel
class RepositoryGraphViewModel @Inject constructor(private val graphs: GatewayGraphRepository) : ViewModel() {
    private val mutable = MutableStateFlow(RepositoryGraphUiState())
    val state = mutable.asStateFlow()
    private var context: GitHubConversationContext? = null
    private var pending: Job? = null
    private var generation = 0L

    fun bind(selected: GitHubConversationContext) {
        if (context == selected) {
            if (!mutable.value.busy && mutable.value.result == null) request("status")
            return
        }
        stop()
        context = selected
        mutable.value = RepositoryGraphUiState(boundContext = selected)
        request("status")
    }

    fun stop() {
        generation++
        pending?.cancel()
        pending = null
        mutable.update { it.copy(busy = false, indexingPaused = it.operation == "index" && it.indexCursor != null) }
    }

    fun request(action: String, query: String = "", symbol: String = "", options: JsonObject = JsonObject(emptyMap())) {
        val selected = context ?: return
        if (mutable.value.busy) return
        val admittedGeneration = ++generation
        mutable.update { it.copy(busy = true, error = null, operation = action, indexingPaused = false) }
        pending = viewModelScope.launch {
            try {
                withTimeout(90_000L) {
                    var cursor = mutable.value.indexCursor.takeIf { action == "index" }
                    val seenCursors = mutableSetOf<String>().apply { cursor?.let(::add) }
                    var pages = 0
                    do {
                        val arguments = buildJsonObject {
                            options.forEach { (key, value) -> put(key, value) }
                            put("action", action)
                            if (query.isNotBlank()) put("query", query)
                            if (symbol.isNotBlank()) put("symbol", symbol)
                            cursor?.let { put("index_cursor", it) }
                            put("budget_bytes", 32000)
                        }
                        val result = graphs.execute(selected, arguments)
                        if (context != selected || admittedGeneration != generation) return@withTimeout
                        mutable.update { it.copy(result = result, resultOperation = action, architecture = if (action == "architecture") result else it.architecture) }
                        if (action != "index") return@withTimeout
                        pages++
                        val more = result["has_more"] == JsonPrimitive(true)
                        val next = (result["index_cursor"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                        check(!more || next != null) { "The gateway did not return the next indexing cursor. Progress is retained; retry indexing." }
                        check(!more || seenCursors.add(checkNotNull(next))) { "Indexing stopped because the gateway repeated a page. Progress is retained; retry indexing." }
                        cursor = next.takeIf { more }
                        mutable.update { it.copy(indexCursor = cursor) }
                        if (!more) break
                        if (pages >= MAX_INDEX_PAGES) {
                            mutable.update { it.copy(indexingPaused = true) }
                            return@withTimeout
                        }
                    } while (isActive)
                    if (context == selected && admittedGeneration == generation) {
                        val result = graphs.execute(
                            selected,
                            buildJsonObject {
                                put("action", "status")
                                put("budget_bytes", 32000)
                            }
                        )
                        if (context == selected && admittedGeneration == generation) mutable.update { it.copy(result = result, resultOperation = "status", operation = "status", architecture = null) }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (context == selected && admittedGeneration == generation) mutable.update { it.copy(error = "The repository graph request timed out. Progress is retained; retry when the gateway is available.", indexingPaused = action == "index") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (context == selected && admittedGeneration == generation) mutable.update { it.copy(error = failure.message ?: "Repository graph unavailable.") }
            } finally {
                if (context == selected && admittedGeneration == generation) mutable.update { it.copy(busy = false) }
            }
        }
    }

    internal companion object {
        const val MAX_INDEX_PAGES = 32
    }
}
