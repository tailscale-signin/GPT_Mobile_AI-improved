package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Per-run evidence, consumed only on this device; never part of a provider request. */
internal class PrivateProtectionContext(
    private val saveInput: suspend (MessageV2, PrivacyProtectionReceipt) -> Unit
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PrivateProtectionContext>

    private val inputs = mutableListOf<Pair<MessageV2, PrivacyProtectionReceipt>>()
    private val responses = ArrayDeque<PrivateProtectedText>()
    private var responseOffset = 0

    @Synchronized
    fun input(message: MessageV2, receipt: PrivacyProtectionReceipt) {
        if (message.id > 0) inputs += message to receipt
    }

    suspend fun flushInputs() {
        val pending = synchronized(this) { inputs.toList().also { inputs.clear() } }
        pending.forEach { (message, receipt) -> saveInput(message, receipt) }
    }

    @Synchronized
    fun restored(result: PrivateProtectedText) {
        responses.addLast(result)
    }

    @Synchronized
    fun displayReceipt(text: String): PrivacyProtectionReceipt {
        if (text.isEmpty()) return PrivacyProtectionReceipt.create(text, emptyList())
        while (responses.isNotEmpty()) {
            val response = responses.first()
            val offset = response.text.indexOf(text, responseOffset)
            if (offset >= 0 && response.text.indexOf(text, offset + 1) >= 0) {
                // A parser may have withheld an identical progress fragment. Without an
                // unambiguous source position, neither occurrence may claim local evidence.
                responses.removeFirst()
                responseOffset = 0
                return PrivacyProtectionReceipt.create(text, emptyList())
            }
            if (offset >= 0 && response.receipt.confirmed && response.receipt.validFor(response.text)) {
                responseOffset = offset + text.length
                return PrivacyProtectionReceipt.create(
                    text,
                    response.receipt.ranges.mapNotNull { range ->
                        val start = maxOf(range.start, offset)
                        val end = minOf(range.endExclusive, responseOffset)
                        PrivacyProtectedRange(start - offset, end - offset).takeIf { start < end }
                    }
                )
            }
            // No exact remaining source slice means no display authority for this response.
            responses.removeFirst()
            responseOffset = 0
        }
        return PrivacyProtectionReceipt.create(text, emptyList())
    }

    @Synchronized
    fun clear() {
        inputs.clear()
        responses.clear()
        responseOffset = 0
    }
}
