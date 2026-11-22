package dev.chungjungsoo.gptmobile.data.network

import io.ktor.util.AttributeKey
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Same-probe response evidence only; no endpoint, credentials, content or historical health. */
internal class MetadataResponseObservation : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MetadataResponseObservation> {
        val requestAttribute = AttributeKey<MetadataResponseObservation>("metadata.response.observation")
    }
    private val lastResponse = AtomicLong(-1)
    val observedAtEpochMs: Long? get() = lastResponse.get().takeIf { it >= 0 }
    fun responded() {
        lastResponse.set(System.currentTimeMillis().coerceAtLeast(0))
    }
}
