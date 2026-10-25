package dev.chungjungsoo.gptmobile.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.UUID

internal data class NetworkFailureSnapshot(val changeId: String, val validation: String, val episodeId: String? = null, val distinctHosts: Int = 0)

internal data class NetworkRecoveryReceipt(val episodeId: String, val changeId: String, val distinctHosts: Int, val firstFailureAt: Long, val lastFailureAt: Long, val responseAt: Long)

/** Bounded device-network evidence; never attributes simultaneous DNS failures to model quality. */
internal class NetworkFailureEpisodes(private val clock: () -> Long = System::currentTimeMillis) {
    private var networkIdentity: String? = null
    private var snapshot = NetworkFailureSnapshot(UUID.randomUUID().toString(), "UNKNOWN")
    private var lastDnsAt = Long.MIN_VALUE
    private var firstDnsAt = Long.MIN_VALUE
    private val hosts = linkedSetOf<String>()
    private val recovered = ArrayDeque<NetworkRecoveryReceipt>()

    @Synchronized fun networkChanged(identity: String?, validation: String): Boolean {
        if (identity == networkIdentity && validation == snapshot.validation) return false
        networkIdentity = identity
        snapshot = NetworkFailureSnapshot(UUID.randomUUID().toString(), validation)
        hosts.clear()
        lastDnsAt = Long.MIN_VALUE
        firstDnsAt = Long.MIN_VALUE
        return true
    }

    @Synchronized fun current() = snapshot.copy(episodeId = null, distinctHosts = 0)

    @Synchronized fun dnsFailure(host: String, dispatched: NetworkFailureSnapshot = snapshot): NetworkFailureSnapshot {
        if (dispatched.changeId != snapshot.changeId) return dispatched.copy(episodeId = UUID.randomUUID().toString(), distinctHosts = 1)
        val now = clock()
        if (lastDnsAt == Long.MIN_VALUE || now - lastDnsAt !in 0..2_000) {
            hosts.clear()
            snapshot = snapshot.copy(episodeId = UUID.randomUUID().toString(), distinctHosts = 0)
            firstDnsAt = now
        }
        lastDnsAt = now
        if (hosts.size < 32) hosts += host
        snapshot = snapshot.copy(distinctHosts = hosts.size)
        return snapshot
    }

    /** A real response ends the shared connectivity episode; it does not prove every host healthy. */
    @Synchronized fun responseReceived(dispatched: NetworkFailureSnapshot): NetworkRecoveryReceipt? {
        if (dispatched.changeId != snapshot.changeId) return null
        val episodeId = snapshot.episodeId ?: return null
        val receipt = NetworkRecoveryReceipt(episodeId, snapshot.changeId, hosts.size, firstDnsAt, lastDnsAt, clock())
        recovered.addLast(receipt)
        while (recovered.size > 16) recovered.removeFirst()
        snapshot = snapshot.copy(episodeId = null, distinctHosts = 0)
        hosts.clear()
        lastDnsAt = Long.MIN_VALUE
        firstDnsAt = Long.MIN_VALUE
        return receipt
    }

    @Synchronized fun recoveries(): List<NetworkRecoveryReceipt> = recovered.toList()
}

internal object DeviceNetworkDiagnostics {
    val episodes = NetworkFailureEpisodes()
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized fun initialize(context: Context) {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        fun update(network: Network?, capabilities: NetworkCapabilities?) {
            val validation = when {
                network == null -> "DISCONNECTED"
                capabilities == null -> "UNKNOWN"
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "VALIDATED"
                else -> "UNVALIDATED"
            }
            if (episodes.networkChanged(network?.toString(), validation)) dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.networkChanged()
        }
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network, capabilities)
            override fun onLost(network: Network) {
                val active = manager.activeNetwork
                update(active, active?.let(manager::getNetworkCapabilities))
            }
        }
        runCatching {
            val active = manager.activeNetwork
            update(active, active?.let(manager::getNetworkCapabilities))
            manager.registerDefaultNetworkCallback(listener)
            callback = listener
        }
    }
}
