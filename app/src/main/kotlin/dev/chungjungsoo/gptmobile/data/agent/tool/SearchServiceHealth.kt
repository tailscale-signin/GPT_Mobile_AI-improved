package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SearchServiceObservation(
    val engine: String,
    val route: String,
    val outcome: String,
    val httpStatus: Int? = null,
    val requestSent: Boolean?,
    val bodyReceived: Boolean,
    val observedAt: Long,
    val retryAt: Long = 0,
    val message: String
)

/** Contains no credential or query. A cached failure retains the original observation. */
object SearchServiceHealth {
    private val mutable = MutableStateFlow<Map<String, SearchServiceObservation>>(emptyMap())
    val observations = mutable.asStateFlow()
    fun record(id: String, observation: SearchServiceObservation) {
        mutable.update { it + (id to observation) }
    }
}

data class EffectiveSearchEngine(val id: String, val name: String, val authorized: Boolean, val reason: String)
