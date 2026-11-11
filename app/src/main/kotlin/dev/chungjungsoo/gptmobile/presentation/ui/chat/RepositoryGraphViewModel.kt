package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphOperationException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphResponse
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
    val indexingPaused: Boolean = false,
    val repairAction: String? = null,
    val gatewayName: String? = null,
    val gatewayProfileUid: String? = null,
    val indexOutcomeUnknown: Boolean = false
)

@HiltViewModel
class RepositoryGraphViewModel @Inject constructor(private val graphs: GatewayGraphRepository) : ViewModel() {
    private val mutable = MutableStateFlow(RepositoryGraphUiState())
    val state = mutable.asStateFlow()
    private var context: GitHubConversationContext? = null
    private var gatewayProfileUid: String? = null
    private var gatewayConfiguration: PlatformV2? = null
    private var pending: Job? = null
    private var generation = 0L

    /** UI can hide a retired binding before its effect runs without exposing credential fingerprints. */
    fun isBoundTo(selected: GitHubConversationContext, gatewayProfileUid: String? = null, gatewayProfile: PlatformV2? = null): Boolean =
        context == selected && this.gatewayProfileUid == gatewayProfileUid && gatewayConfiguration == gatewayProfile

    fun bind(selected: GitHubConversationContext, gatewayProfileUid: String? = null, gatewayProfile: PlatformV2? = null) {
        if (isBoundTo(selected, gatewayProfileUid, gatewayProfile)) {
            if (!mutable.value.busy && (mutable.value.result == null || mutable.value.indexOutcomeUnknown || mutable.value.error != null)) request("status")
            return
        }
        stop()
        context = selected
        this.gatewayProfileUid = gatewayProfileUid
        gatewayConfiguration = gatewayProfile
        mutable.value = RepositoryGraphUiState(boundContext = selected, gatewayProfileUid = gatewayProfileUid)
        request("status")
    }

    fun stop() {
        generation++
        pending?.cancel()
        pending = null
        mutable.update {
            val unconfirmed = it.indexOutcomeUnknown || it.busy && it.operation == "index"
            it.copy(busy = false, indexingPaused = it.operation == "index" && it.indexCursor != null, indexOutcomeUnknown = unconfirmed, repairAction = if (unconfirmed) "CHECK_SAVED_INDEX" else it.repairAction)
        }
    }

    fun request(action: String, query: String = "", symbol: String = "", options: JsonObject = JsonObject(emptyMap())) {
        val selected = context ?: return
        if (mutable.value.busy) return
        if (action == "index" && mutable.value.indexOutcomeUnknown) {
            mutable.update { it.copy(error = "Check the saved index before continuing. The previous request may have reached this gateway.", repairAction = "CHECK_SAVED_INDEX") }
            return
        }
        val admittedGeneration = ++generation
        val selectedGateway = gatewayProfileUid
        fun current() = context == selected && admittedGeneration == generation && gatewayProfileUid == selectedGateway
        mutable.update { it.copy(busy = true, error = null, repairAction = null, operation = action, indexingPaused = false) }
        pending = viewModelScope.launch {
            suspend fun readStatus(): GatewayGraphResponse = graphs.executeForMap(
                selected,
                buildJsonObject {
                    put("action", "status")
                    put("budget_bytes", 32000)
                },
                selectedGateway,
                false
            )
            suspend fun loadArchitecture(result: JsonObject) {
                if (result["graph"] !is JsonObject || mutable.value.indexOutcomeUnknown || !current()) return
                val architecture = graphs.executeForMap(
                    selected,
                    buildJsonObject {
                        put("action", "architecture")
                        put("budget_bytes", 32000)
                    },
                    selectedGateway,
                    false
                )
                if (current()) mutable.update { it.copy(architecture = architecture.result.takeIf { value -> value["graph"] is JsonObject }) }
            }
            fun publish(response: GatewayGraphResponse) {
                if (!current()) return
                mutable.update {
                    val status = response.operation == "status"
                    val cursor = (response.result["index_cursor"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    val more = response.result["has_more"] == JsonPrimitive(true)
                    it.copy(
                        result = response.result,
                        resultOperation = response.operation,
                        architecture = when {
                            response.operation == "architecture" -> response.result
                            status && response.result["graph"] !is JsonObject -> null
                            else -> it.architecture
                        },
                        gatewayProfileUid = response.gatewayProfileUid,
                        gatewayName = response.gatewayName,
                        indexCursor = if (status) cursor.takeIf { more } else it.indexCursor,
                        indexingPaused = status && more,
                        indexOutcomeUnknown = response.indexOutcomeUnknown,
                        repairAction = if (response.indexOutcomeUnknown) "CHECK_SAVED_INDEX" else null,
                        error = if (response.indexOutcomeUnknown) "The gateway has not yet confirmed the previous index batch. Check the saved index again before continuing." else null
                    )
                }
            }
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
                        val response = graphs.executeForMap(selected, arguments, selectedGateway, pages == 0)
                        if (!current()) return@withTimeout
                        publish(response)
                        if (response.operation != "index") {
                            if (response.operation == "status") loadArchitecture(response.result)
                            return@withTimeout
                        }
                        pages++
                        val more = response.result["has_more"] == JsonPrimitive(true)
                        val next = (response.result["index_cursor"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                        check(!more || next != null) { "The gateway did not return the next indexing cursor. Check the saved index before continuing." }
                        check(!more || seenCursors.add(checkNotNull(next))) { "Indexing stopped because the gateway repeated a page. Check the saved index before continuing." }
                        cursor = next.takeIf { more }
                        mutable.update { it.copy(indexCursor = cursor) }
                        if (!more) break
                        if (pages >= MAX_INDEX_PAGES) {
                            mutable.update { it.copy(indexingPaused = true) }
                            return@withTimeout
                        }
                    } while (isActive)
                    if (current()) {
                        val response = readStatus()
                        if (current()) {
                            publish(response)
                            mutable.update { it.copy(operation = "status", architecture = null) }
                            loadArchitecture(response.result)
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (current()) mutable.update { it.copy(error = "The repository graph request timed out. Check its saved status when the gateway is available.", indexingPaused = action == "index", indexOutcomeUnknown = it.indexOutcomeUnknown || action == "index", repairAction = if (action == "index") "CHECK_SAVED_INDEX" else it.repairAction) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: GatewayGraphOperationException) {
                if (current()) mutable.update { it.copy(error = failure.message, gatewayProfileUid = failure.gatewayProfileUid ?: it.gatewayProfileUid, gatewayName = failure.gatewayName ?: it.gatewayName, repairAction = failure.repairAction, indexOutcomeUnknown = failure.indexOutcomeUnknown) }
            } catch (failure: Exception) {
                if (current()) mutable.update { it.copy(error = failure.message ?: "Repository graph unavailable.", indexOutcomeUnknown = it.indexOutcomeUnknown || action == "index", repairAction = if (action == "index") "CHECK_SAVED_INDEX" else it.repairAction) }
            } finally {
                if (current()) mutable.update { it.copy(busy = false) }
            }
        }
    }

    internal companion object {
        const val MAX_INDEX_PAGES = 32
    }
}
