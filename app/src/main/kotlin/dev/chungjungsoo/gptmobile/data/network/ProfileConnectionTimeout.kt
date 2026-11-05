package dev.chungjungsoo.gptmobile.data.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Per-profile override carried from the progress watchdog to each physical request. */
internal class ProfileConnectionTimeout(val millis: Long?) : AbstractCoroutineContextElement(Key) {
    init {
        require(millis == null || millis > 0)
    }
    companion object Key : CoroutineContext.Key<ProfileConnectionTimeout>
}
