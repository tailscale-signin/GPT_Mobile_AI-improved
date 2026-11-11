package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlinx.coroutines.CancellationException

/** Cancels a phone observer only. Remote cancellation must use its explicit job command. */
internal class GatewayObserverCancellation(val origin: String) : CancellationException("Gateway observer detached") {
    init {
        require(origin in setOf("user", "navigation", "superseded", "observer_detached"))
    }
}
