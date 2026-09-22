package dev.chungjungsoo.gptmobile.data.network.gateway

import android.content.Context
import android.net.ConnectivityManager
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
 * Reconciles durable Gateway jobs when Android regains validated internet access.
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
    private var networkValidated = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val shouldRecover = validated && !networkValidated
            networkValidated = validated
            if (shouldRecover) {
                scope.launch {
                    agentRunCoordinator.recoverInterruptedGatewayRuns()
                }
            }
        }

        override fun onLost(network: Network) {
            networkValidated = false
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return

        val active = connectivityManager.activeNetwork
        val capabilities = active?.let(connectivityManager::getNetworkCapabilities)
        networkValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        connectivityManager.registerDefaultNetworkCallback(callback)

        if (networkValidated) {
            scope.launch {
                agentRunCoordinator.recoverInterruptedGatewayRuns()
            }
        }
    }
}
