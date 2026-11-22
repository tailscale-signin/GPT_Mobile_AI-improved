package dev.chungjungsoo.gptmobile.data.network

import io.ktor.util.AttributeKey
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Same-probe exchange evidence only; no endpoint, credentials, content or historical health. */
internal class MetadataResponseObservation : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MetadataResponseObservation> {
        val requestAttribute = AttributeKey<MetadataResponseObservation>("metadata.response.observation")
    }
    private val lastResponse = AtomicLong(-1)
    private val interruptedRead = AtomicBoolean(false)
    val observedAtEpochMs: Long? get() = lastResponse.get().takeIf { it >= 0 }
    val hasInterruptedRead: Boolean get() = interruptedRead.get()

    fun responded() {
        lastResponse.set(System.currentTimeMillis().coerceAtLeast(0))
    }

    /** A later local rejection cannot erase a read whose response was not established. */
    fun readInterrupted() {
        interruptedRead.set(true)
    }
}
