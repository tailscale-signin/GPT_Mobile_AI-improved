package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Audits that claim their own live receipt cannot borrow another observer's completed refresh. */
internal class GatewayCapabilityReadContext(val requireOwnedFreshRead: Boolean = true) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GatewayCapabilityReadContext>
}
