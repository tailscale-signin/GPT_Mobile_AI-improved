package dev.chungjungsoo.gptmobile.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Network availability alone cannot resume a cloud request. Service evidence belongs to one network epoch. */
internal class ProviderContinuationConnectivity(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private data class State(val epoch: String = "", val validation: String = "UNKNOWN", val responses: Map<String, Long> = emptyMap())
    private val state = MutableStateFlow(State())

    @Synchronized fun networkChanged(epoch: String, validation: String) {
        val previous = state.value
        state.value = State(epoch, validation, previous.responses.takeIf { previous.epoch == epoch }.orEmpty())
    }

    @Synchronized fun responseReceived(host: String) {
        val previous = state.value
        val responses = (previous.responses.filterValues { now() - it in 0..30_000L } + (host.lowercase() to now())).entries.toList().takeLast(128).associate { it.toPair() }
        state.value = previous.copy(responses = responses)
    }

    suspend fun awaitUsable(host: String, maximumWaitMillis: Long): Boolean {
        fun usable(value: State): Boolean = value.validation == "VALIDATED" ||
            value.validation != "DISCONNECTED" &&
            value.responses[host.lowercase()]?.let { now() - it in 0..30_000L } == true
        if (usable(state.value)) return true
        if (maximumWaitMillis <= 0L) return false
        return withTimeoutOrNull(maximumWaitMillis.coerceAtMost(30_000L)) {
            state.first(::usable)
            true
        } ?: false
    }
}
