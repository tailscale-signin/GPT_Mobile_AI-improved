package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.agent.GatewayOutcomeRecovery
import dev.chungjungsoo.gptmobile.data.agent.sameGatewayEndpoint
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** One read-only reconciliation loop; no timer remains when the durable candidate set is empty. */
internal class GatewayRecoveryPoller(
    scope: CoroutineScope,
    private val hasPending: suspend () -> Boolean,
    private val reconcile: suspend () -> Unit,
    private val intervalMillis: Long = 60_000L,
    private val onFailure: (Exception) -> Unit = {}
) {
    private val routeAvailable = MutableStateFlow(false)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    init {
        require(intervalMillis > 0)
        scope.launch {
            routeAvailable.collectLatest { available ->
                if (!available) return@collectLatest
                wakeups.trySend(Unit)
                for (ignored in wakeups) pollUntilIdle()
            }
        }
    }

    fun routeChanged(available: Boolean) {
        routeAvailable.value = available
        if (available) wake()
    }

    fun wake() {
        if (routeAvailable.value) wakeups.trySend(Unit)
    }

    private suspend fun pollUntilIdle() {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                if (!hasPending()) return
                reconcile()
                failures = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error)
                if (++failures >= 3) return
            }
            delay(intervalMillis)
            // Events arriving during a pass share its next scheduled read, rather
            // than creating another loop or bypassing the coordinator's throttle.
            wakeups.tryReceive()
        }
    }
}

internal fun hasEligibleGatewayRecovery(runs: List<AgentRun>, profiles: List<PlatformV2>, activeRunIds: Set<String>): Boolean {
    val selected = profiles.groupBy { it.uid }
    return runs.any { run ->
        val profile = selected[run.profileUid]?.singleOrNull()
        val baseUrl = run.gatewayBaseUrl
        run.runId !in activeRunIds &&
            GatewayOutcomeRecovery.canReadSavedResult(run) &&
            !run.gatewayJobId.isNullOrBlank() &&
            !baseUrl.isNullOrBlank() &&
            profile?.enabled == true &&
            sameGatewayEndpoint(profile.apiUrl, baseUrl)
    }
}
