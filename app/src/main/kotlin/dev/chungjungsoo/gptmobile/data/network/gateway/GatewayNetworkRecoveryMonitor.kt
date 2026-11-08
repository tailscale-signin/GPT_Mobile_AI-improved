package dev.chungjungsoo.gptmobile.data.network.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Reconciles durable Gateway jobs when Android regains internet or a local-network route.
 *
 * The Gateway already owns the durable remote job. This monitor only asks the
 * coordinator to reconcile local Room state with that remote job; it never
 * replays the user's prompt or starts a second agent runtime.
 */
@Singleton
class GatewayNetworkRecoveryMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val agentRunCoordinator: AgentRunCoordinator,
    private val chatRepository: ChatRepository,
    private val settings: SettingRepository,
    private val researchArchive: dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val routeRevision = AtomicLong()
    private val routeRevisionState = MutableStateFlow(0L)
    internal val routeChanges = routeRevisionState.asStateFlow()
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val recoveryPolling by lazy {
        GatewayRecoveryPoller(
            scope,
            hasPending = {
                hasEligibleGatewayRecovery(chatRepository.getRecoverableGatewayRuns(), settings.fetchPlatformV2s(), agentRunCoordinator.activeRuns.value.keys)
            },
            reconcile = { agentRunCoordinator.recoverInterruptedGatewayRuns() },
            onFailure = { error ->
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Gateway", "RECOVERY_POLL_FAILED · reason=${error.javaClass.simpleName} · action=saved_jobs_retained", "W")
            }
        )
    }
    internal val routeAvailable get() = recoveryPolling.routeAvailable

    @Volatile
    private var validatedNetwork: Network? = null

    @Volatile
    private var routeIdentity: Int? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // Route and DNS changes can occur without replacing the default Network.
            // Keep only an in-memory identity, never private addresses in diagnostics.
            val identity = 31 * network.hashCode() + linkProperties.hashCode()
            if (routeIdentity != identity) {
                routeIdentity = identity
                dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints.networkChanged()
                if (dev.chungjungsoo.gptmobile.BuildConfig.MCP_ROUTE_RECOVERY) dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkChanged()
                if (validatedNetwork == network) {
                    resumeResearchOutbox()
                    recoveryPolling.wake()
                }
                routeRevisionState.value = routeRevision.incrementAndGet()
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val validated = networkCapabilities.hasGatewayRoute()
            if (validated && validatedNetwork != network) {
                validatedNetwork = network
                dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints.networkChanged()
                if (dev.chungjungsoo.gptmobile.BuildConfig.MCP_ROUTE_RECOVERY) dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkChanged()
                resumeResearchOutbox()
                recoveryPolling.routeChanged(true)
                routeRevisionState.value = routeRevision.incrementAndGet()
            } else if (!validated && validatedNetwork == network) {
                validatedNetwork = null
                recoveryPolling.routeChanged(false)
                routeRevisionState.value = routeRevision.incrementAndGet()
            }
        }

        override fun onLost(network: Network) {
            routeIdentity = null
            dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints.networkChanged()
            if (dev.chungjungsoo.gptmobile.BuildConfig.MCP_ROUTE_RECOVERY) dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkChanged()
            if (validatedNetwork == network) {
                validatedNetwork = null
                recoveryPolling.routeChanged(false)
            }
            routeRevisionState.value = routeRevision.incrementAndGet()
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return

        val active = connectivityManager.activeNetwork
        val capabilities = active?.let(connectivityManager::getNetworkCapabilities)
        validatedNetwork = active?.takeIf {
            capabilities?.hasGatewayRoute() == true
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        recoveryPolling.routeChanged(validatedNetwork != null)
        routeRevisionState.value = routeRevision.incrementAndGet()
        scope.launch {
            // A run becoming interrupted on an unchanged network must also wake
            // recovery. Ignore token/progress updates while its live owner exists.
            agentRunCoordinator.activeRuns.map { it.keys.toSet() }.distinctUntilChanged().collect { recoveryPolling.wake() }
        }
    }

    private fun NetworkCapabilities.hasGatewayRoute() = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
        hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun resumeResearchOutbox() {
        scope.launch {
            try {
                dev.chungjungsoo.gptmobile.data.research.work.ResearchExaminationWorker.reconcile(context, researchArchive, reconsider = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                    "ResearchExamine",
                    "NETWORK_RECOVERY_ENQUEUE_FAILED · reason=${error.javaClass.simpleName} · action=durable_outbox_retained",
                    "W"
                )
            }
        }
    }
}
