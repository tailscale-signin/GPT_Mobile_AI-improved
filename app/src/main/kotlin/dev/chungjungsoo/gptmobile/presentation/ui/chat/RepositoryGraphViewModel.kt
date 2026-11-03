package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphRepository
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class RepositoryGraphUiState(
    val result: JsonObject? = null,
    val busy: Boolean = false,
    val operation: String = "status",
    val error: String? = null
)

@HiltViewModel
class RepositoryGraphViewModel @Inject constructor(private val graphs: GatewayGraphRepository) : ViewModel() {
    private val mutable = MutableStateFlow(RepositoryGraphUiState())
    val state = mutable.asStateFlow()
    private var context: GitHubConversationContext? = null
    private var pending: Job? = null
    private var generation = 0L

    fun bind(selected: GitHubConversationContext) {
        if (context == selected) return
        stop()
        context = selected
        mutable.value = RepositoryGraphUiState()
        request("status")
    }

    fun stop() {
        generation++
        pending?.cancel()
        pending = null
        mutable.update { it.copy(busy = false) }
    }

    fun request(action: String, query: String = "", symbol: String = "", options: JsonObject = JsonObject(emptyMap())) {
        val selected = context ?: return
        if (mutable.value.busy) return
        val admittedGeneration = ++generation
        pending = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, operation = action) }
            try {
                var cursor: String? = null
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
                    if (context != selected || admittedGeneration != generation) return@launch
                    mutable.update { it.copy(result = result) }
                    cursor = (result["index_cursor"] as? JsonPrimitive)?.contentOrNull
                    val more = result["has_more"] == JsonPrimitive(true)
                    check(action != "index" || !more || cursor != null) { "The gateway did not return the next indexing cursor. Progress is retained." }
                } while (isActive && action == "index" && mutable.value.result?.get("has_more") == JsonPrimitive(true))
                if (action == "index" && context == selected && admittedGeneration == generation) {
                    val result = graphs.execute(selected, buildJsonObject { put("action", "status") })
                    if (admittedGeneration == generation) mutable.update { it.copy(result = result, operation = "status") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (context == selected && admittedGeneration == generation) mutable.update { it.copy(error = failure.message ?: "Repository graph unavailable.") }
            } finally {
                if (context == selected && admittedGeneration == generation) mutable.update { it.copy(busy = false) }
            }
        }
    }
}
