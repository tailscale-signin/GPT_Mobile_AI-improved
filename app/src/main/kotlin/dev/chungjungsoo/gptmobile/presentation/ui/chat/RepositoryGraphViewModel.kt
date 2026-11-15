package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphOperationException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphResponse
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphResultException
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    val indexOutcomeUnknown: Boolean = false,
    val searchResult: JsonObject? = null,
    val mapQuery: String = "",
    val mapFocusPath: String? = null,
    val mapPage: Int = 0,
    val mapNextCursor: String? = null,
    val indexStatus: JsonObject? = null,
    val indexJob: JsonObject? = null,
    val durableIndexJobs: Boolean = false,
    val modelAccess: String = "NOT_CHECKED",
    val errorCode: String? = null
) {
    val visibleMap: JsonObject? get() = searchResult ?: architecture
    val indexingOnGateway: Boolean get() = indexJob?.graphText("state") in setOf("QUEUED", "RUNNING")
}

@HiltViewModel
class RepositoryGraphViewModel @Inject constructor(private val graphs: GatewayGraphRepository) : ViewModel() {
    private val mutable = MutableStateFlow(RepositoryGraphUiState())
    val state = mutable.asStateFlow()
    private var context: GitHubConversationContext? = null
    private var gatewayProfileUid: String? = null
    private var gatewayConfiguration: PlatformV2? = null
    private var pending: Job? = null
    private var polling: Job? = null
    private var generation = 0L
    private var observationGeneration = 0L
    private val mapRequests = mutableListOf<JsonObject>()

    /** UI can hide a retired binding before its effect runs without exposing credential fingerprints. */
    fun isBoundTo(selected: GitHubConversationContext, gatewayProfileUid: String? = null, gatewayProfile: PlatformV2? = null): Boolean =
        context == selected && this.gatewayProfileUid == gatewayProfileUid && gatewayConfiguration == gatewayProfile

    fun bind(selected: GitHubConversationContext, gatewayProfileUid: String? = null, gatewayProfile: PlatformV2? = null) {
        if (isBoundTo(selected, gatewayProfileUid, gatewayProfile)) {
            if (!mutable.value.busy && (mutable.value.result == null || mutable.value.indexOutcomeUnknown || mutable.value.error != null || mutable.value.indexingOnGateway)) request("status")
            return
        }
        stop()
        context = selected
        this.gatewayProfileUid = gatewayProfileUid
        gatewayConfiguration = gatewayProfile
        mapRequests.clear()
        mutable.value = RepositoryGraphUiState(boundContext = selected, gatewayProfileUid = gatewayProfileUid)
        request("status")
    }

    /** Closing the view detaches observers; a saved gateway job continues independently. */
    fun stop() {
        generation++
        observationGeneration++
        pending?.cancel()
        polling?.cancel()
        pending = null
        polling = null
        mutable.update {
            val unconfirmed = it.indexOutcomeUnknown || it.busy && it.operation == "index" && it.indexJob == null
            it.copy(busy = false, indexingPaused = !it.indexingOnGateway && it.operation == "index" && it.indexCursor != null, indexOutcomeUnknown = unconfirmed, repairAction = if (unconfirmed) "CHECK_SAVED_INDEX" else it.repairAction)
        }
    }

    fun stopIndexing() {
        val job = mutable.value.indexJob?.takeIf { mutable.value.indexingOnGateway }
        stop()
        if (job != null) {
            val jobGeneration = (job["generation"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 1 }
            if (jobGeneration == null) {
                mutable.update { it.copy(error = "Refresh the saved job before stopping it; its current generation was not returned.", errorCode = "GRAPH_JOB_IDENTITY_REQUIRED", repairAction = "CHECK_SAVED_INDEX") }
                return
            }
            request(
                "index",
                options = buildJsonObject {
                    put("job_action", "cancel")
                    put("job_id", job.graphText("id"))
                    put("job_generation", jobGeneration)
                }
            )
        }
    }

    fun retryFailed() = request("index", options = buildJsonObject { put("retry_failed", true) })

    fun searchMap(query: String) {
        val term = query.trim().take(2000)
        request(if (term.isEmpty()) "architecture" else "query", query = term)
    }

    fun focusMap(node: JsonObject) {
        val path = node.graphText("path")
        if (path.isNotBlank()) request("architecture", options = buildJsonObject { put("paths", JsonArray(listOf(JsonPrimitive(path)))) })
    }

    fun nextMapPage() {
        val cursor = mutable.value.mapNextCursor ?: return
        val previous = mapRequests.getOrNull(mutable.value.mapPage) ?: return
        if (mapRequests.take(mutable.value.mapPage + 1).any { it.graphText("result_cursor") == cursor }) {
            mutable.update { it.copy(error = "The gateway repeated a result page. Refresh the map to start a new search.", errorCode = "GRAPH_RESULT_CURSOR_REPEATED", repairAction = "RESTART_MAP_SEARCH") }
            return
        }
        requestMapPage(JsonObject(previous + ("result_cursor" to JsonPrimitive(cursor))), mutable.value.mapPage + 1)
    }

    fun previousMapPage() {
        val page = mutable.value.mapPage - 1
        mapRequests.getOrNull(page)?.let { requestMapPage(it, page) }
    }

    private fun requestMapPage(arguments: JsonObject, page: Int) = request(arguments.graphText("action"), query = arguments.graphText("query"), options = JsonObject(arguments - setOf("action", "query")), mapPage = page)

    private fun publish(response: GatewayGraphResponse, inspection: Boolean = true) {
        val isStatus = response.operation in setOf("status", "index")
        val cursor = response.result.graphText("index_cursor").takeIf(String::isNotBlank)
        val more = response.result["has_more"] == JsonPrimitive(true)
        val job = response.result["index_job"] as? JsonObject
        mutable.update {
            it.copy(
                result = if (inspection) response.result else it.result,
                resultOperation = if (inspection) response.operation else it.resultOperation,
                indexStatus = if (isStatus) response.result else it.indexStatus,
                gatewayProfileUid = response.gatewayProfileUid,
                gatewayName = response.gatewayName,
                indexCursor = if (isStatus) cursor.takeIf { more } else it.indexCursor,
                indexingPaused = isStatus && more && job?.graphText("state") !in setOf("QUEUED", "RUNNING"),
                indexJob = if (isStatus) job else it.indexJob,
                durableIndexJobs = response.durableIndexJobs,
                modelAccess = response.modelAccess,
                indexOutcomeUnknown = response.indexOutcomeUnknown,
                repairAction = if (response.indexOutcomeUnknown) "CHECK_SAVED_INDEX" else null,
                error = if (response.indexOutcomeUnknown) "The gateway has not yet confirmed the previous index request. Check the saved index before continuing." else null,
                errorCode = if (response.indexOutcomeUnknown) "GRAPH_INDEX_OUTCOME_UNKNOWN" else null
            )
        }
    }

    private fun clearMap() {
        mapRequests.clear()
        mutable.update { it.copy(architecture = null, searchResult = null, mapQuery = "", mapFocusPath = null, mapPage = 0, mapNextCursor = null) }
    }

    private fun publishMap(response: GatewayGraphResponse, arguments: JsonObject, requestedPage: Int) {
        if (response.result.graphText("status") == "NOT_INDEXED") {
            // A snapshot can disappear between its status and source reads.
            clearMap()
            mutable.update { it.copy(result = response.result, resultOperation = response.operation, indexStatus = response.result, indexCursor = null, indexingPaused = false) }
            return
        }
        val next = response.result.graphText("next_cursor").ifBlank { response.result.graphText("result_cursor") }.takeIf(String::isNotBlank)
        if (response.result["has_more"] == JsonPrimitive(true) && next == null) throw GatewayGraphResultException("GRAPH_RESULT_CURSOR_MISSING", "The gateway omitted the next result cursor. Refresh this search before continuing.", "RESTART_MAP_SEARCH")
        if (next != null && next == arguments.graphText("result_cursor")) throw GatewayGraphResultException("GRAPH_RESULT_CURSOR_REPEATED", "The gateway repeated a result page. Refresh this search before continuing.", "RESTART_MAP_SEARCH")
        if (requestedPage == 0) mapRequests.clear()
        while (mapRequests.size > requestedPage) mapRequests.removeAt(mapRequests.lastIndex)
        mapRequests.add(arguments)
        mutable.update {
            it.copy(
                architecture = if (response.operation == "architecture") response.result else it.architecture,
                searchResult = if (response.operation == "query") response.result else null,
                mapQuery = arguments.graphText("query"),
                mapFocusPath = ((arguments["paths"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull,
                mapPage = requestedPage,
                mapNextCursor = next
            )
        }
    }

    private fun watchIndex() {
        if (polling?.isActive == true || !mutable.value.indexingOnGateway) return
        val selected = context ?: return
        val selectedGateway = gatewayProfileUid
        val observer = observationGeneration
        fun current() = observer == observationGeneration && context == selected && gatewayProfileUid == selectedGateway
        polling = viewModelScope.launch {
            while (isActive && current() && mutable.value.indexingOnGateway) {
                delay(INDEX_POLL_MILLIS)
                if (mutable.value.busy) continue
                try {
                    val response = withTimeout(30_000L) {
                        graphs.executeForMap(
                            selected,
                            buildJsonObject {
                                put("action", "status")
                                put("budget_bytes", 32000)
                            },
                            selectedGateway,
                            false
                        )
                    }
                    if (!current()) return@launch
                    publish(response, inspection = false)
                    if (!mutable.value.indexingOnGateway && !mutable.value.busy) request("status")
                } catch (_: TimeoutCancellationException) {
                    if (current()) mutable.update { it.copy(error = "The job status check timed out. The saved job may still be running; refresh to reconnect.", errorCode = "GRAPH_REQUEST_TIMEOUT", repairAction = "CHECK_SAVED_INDEX") }
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (current()) showFailure(failure, false)
                    return@launch
                }
            }
        }
    }

    private fun showFailure(failure: Exception, indexing: Boolean) {
        mutable.update {
            if (failure is GatewayGraphOperationException) {
                it.copy(error = failure.message, errorCode = failure.failureCode, gatewayProfileUid = failure.gatewayProfileUid ?: it.gatewayProfileUid, gatewayName = failure.gatewayName ?: it.gatewayName, repairAction = failure.repairAction, indexOutcomeUnknown = failure.indexOutcomeUnknown)
            } else if (failure is GatewayGraphResultException) {
                it.copy(error = failure.message, errorCode = failure.code, repairAction = failure.repairAction)
            } else {
                it.copy(error = failure.message ?: "Repository graph unavailable.", errorCode = "GRAPH_OPERATION_FAILED", indexOutcomeUnknown = it.indexOutcomeUnknown || indexing && it.indexJob == null, repairAction = if (indexing) "CHECK_SAVED_INDEX" else it.repairAction)
            }
        }
    }

    fun request(action: String, query: String = "", symbol: String = "", options: JsonObject = JsonObject(emptyMap()), mapPage: Int = 0) {
        val selected = context ?: return
        if (mutable.value.busy) return
        val cancel = options.graphText("job_action") == "cancel"
        if (action == "index" && !cancel && mutable.value.indexOutcomeUnknown) {
            mutable.update { it.copy(error = "Check the saved index before continuing. The previous request may have reached this gateway.", errorCode = "GRAPH_INDEX_OUTCOME_UNKNOWN", repairAction = "CHECK_SAVED_INDEX") }
            return
        }
        val admittedGeneration = ++generation
        val selectedGateway = gatewayProfileUid
        fun current() = context == selected && admittedGeneration == generation && gatewayProfileUid == selectedGateway
        mutable.update { it.copy(busy = true, error = null, errorCode = null, repairAction = null, operation = action, indexingPaused = false) }
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
                if (!current()) return
                // An unindexed revision carries scope metadata too; it has no
                // saved source map or result cursors to inspect.
                if (result.graphText("status") == "NOT_INDEXED" || result["graph"] !is JsonObject) {
                    clearMap()
                    return
                }
                if (mutable.value.indexOutcomeUnknown) return
                val arguments = buildJsonObject {
                    put("action", "architecture")
                    put("limit", MAP_PAGE_SIZE)
                    put("budget_bytes", 32000)
                }
                val response = graphs.executeForMap(selected, arguments, selectedGateway, false)
                if (current()) publishMap(response, arguments, 0)
            }
            try {
                withTimeout(90_000L) {
                    var cursor = mutable.value.indexCursor.takeIf { action == "index" && options["retry_failed"] != JsonPrimitive(true) && !cancel && !mutable.value.durableIndexJobs }
                    val seenCursors = mutableSetOf<String>().apply { cursor?.let(::add) }
                    var pages = 0
                    do {
                        val arguments = buildJsonObject {
                            options.forEach { (key, value) -> put(key, value) }
                            put("action", action)
                            if (query.isNotBlank()) put("query", query)
                            if (symbol.isNotBlank()) put("symbol", symbol)
                            if (action in setOf("architecture", "query")) put("limit", MAP_PAGE_SIZE)
                            if (action == "index" && !cancel && mutable.value.durableIndexJobs) put("background", true)
                            cursor?.let { put("index_cursor", it) }
                            put("budget_bytes", 32000)
                        }
                        val response = graphs.executeForMap(selected, arguments, selectedGateway, pages == 0)
                        if (!current()) return@withTimeout
                        if (response.operation in setOf("architecture", "query")) publishMap(response, arguments, mapPage)
                        publish(response)
                        if (response.operation == "status") {
                            loadArchitecture(response.result)
                            return@withTimeout
                        }
                        if (response.operation != "index") return@withTimeout
                        if (response.result["index_job"] is JsonObject) return@withTimeout
                        pages++
                        val more = response.result["has_more"] == JsonPrimitive(true)
                        val next = response.result.graphText("index_cursor").takeIf(String::isNotBlank)
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
                            mutable.update { it.copy(operation = "status") }
                            loadArchitecture(response.result)
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (current()) mutable.update { it.copy(error = "The repository graph request timed out. Check its saved status when the gateway is available.", errorCode = "GRAPH_REQUEST_TIMEOUT", indexingPaused = action == "index" && !it.indexingOnGateway, indexOutcomeUnknown = it.indexOutcomeUnknown || action == "index" && it.indexJob == null, repairAction = if (action == "index") "CHECK_SAVED_INDEX" else it.repairAction) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current()) showFailure(failure, action == "index")
            } finally {
                if (current()) {
                    mutable.update { it.copy(busy = false) }
                    watchIndex()
                }
            }
        }
    }

    internal companion object {
        const val MAX_INDEX_PAGES = 32
        const val MAP_PAGE_SIZE = 12
        const val INDEX_POLL_MILLIS = 2_000L
    }
}
