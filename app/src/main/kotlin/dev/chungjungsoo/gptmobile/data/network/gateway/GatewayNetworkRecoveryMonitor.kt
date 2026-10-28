package dev.chungjungsoo.gptmobile.data.network.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val agentRunCoordinator: AgentRunCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

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
                    scope.launch { agentRunCoordinator.recoverInterruptedGatewayRuns() }
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (validated && validatedNetwork != network) {
                validatedNetwork = network
                dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints.networkChanged()
                if (dev.chungjungsoo.gptmobile.BuildConfig.MCP_ROUTE_RECOVERY) dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkChanged()
                resumeResearchOutbox()
                scope.launch {
                    agentRunCoordinator.recoverInterruptedGatewayRuns()
                }
            } else if (!validated && validatedNetwork == network) {
                validatedNetwork = null
            }
        }

        override fun onLost(network: Network) {
            routeIdentity = null
            dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints.networkChanged()
            if (dev.chungjungsoo.gptmobile.BuildConfig.MCP_ROUTE_RECOVERY) dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkChanged()
            if (validatedNetwork == network) {
                validatedNetwork = null
            }
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return

        val active = connectivityManager.activeNetwork
        val capabilities = active?.let(connectivityManager::getNetworkCapabilities)
        validatedNetwork = active?.takeIf {
            capabilities != null
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        if (validatedNetwork != null) {
            scope.launch {
                agentRunCoordinator.recoverInterruptedGatewayRuns()
            }
        }
    }

    private fun resumeResearchOutbox() {
        scope.launch {
            runCatching {
                val manager = androidx.work.WorkManager.getInstance(context)
                val running = manager.getWorkInfosForUniqueWork("research-examination-outbox").get()
                    .any { it.state == androidx.work.WorkInfo.State.RUNNING }
                if (!running) dev.chungjungsoo.gptmobile.data.research.work.ResearchExaminationWorker.enqueue(context, reconsider = true)
            }.onFailure { error ->
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                    "ResearchExamine",
                    "NETWORK_RECOVERY_ENQUEUE_FAILED · reason=${error.javaClass.simpleName} · action=durable_outbox_retained",
                    "W"
                )
            }
        }
    }
}
