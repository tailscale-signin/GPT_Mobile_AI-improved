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
    private class Response(val id: Long, var finished: Boolean = false, var ambiguous: Boolean = false) {
        val text = StringBuilder()
        val ranges = mutableListOf<PrivacyProtectedRange>()
        var valid = true
    }

    private val responses = ArrayDeque<Response>()
    private var responseOffset = 0
    private var nextResponse = 0L

    @Synchronized
    fun input(message: MessageV2, receipt: PrivacyProtectionReceipt) {
        require(receipt.validFor(message.content)) { "Private input evidence does not match the message revision." }
        if (message.id > 0) inputs += message to receipt
    }

    suspend fun flushInputs() {
        val pending = synchronized(this) { inputs.toList().also { inputs.clear() } }
        pending.forEach { (message, receipt) -> saveInput(message, receipt) }
    }

    @Synchronized
    fun restored(result: PrivateProtectedText) {
        val response = beginResponse()
        restored(response, result)
        finishResponse(response)
    }

    @Synchronized
    fun beginResponse(): Long {
        val overlapping = responses.filter { !it.finished }
        overlapping.forEach { it.ambiguous = true }
        val response = Response(++nextResponse, ambiguous = overlapping.isNotEmpty())
        responses.addLast(response)
        return response.id
    }

    @Synchronized
    fun restored(responseId: Long, result: PrivateProtectedText) {
        val response = responses.firstOrNull { it.id == responseId } ?: return
        if (response.finished || !response.valid) return
        if (!result.receipt.confirmed || !result.receipt.validFor(result.text) || result.text.length > 1_048_576 - response.text.length) {
            response.valid = false
            response.text.clear()
            response.ranges.clear()
            return
        }
        val base = response.text.length
        response.text.append(result.text)
        response.ranges += result.receipt.ranges.map { PrivacyProtectedRange(base + it.start, base + it.endExclusive) }
    }

    @Synchronized
    fun finishResponse(responseId: Long) {
        responses.firstOrNull { it.id == responseId }?.finished = true
    }

    @Synchronized
    fun discardResponse(responseId: Long) {
        if (responses.firstOrNull()?.id == responseId) responseOffset = 0
        responses.removeAll { it.id == responseId }
    }

    @Synchronized
    fun displayReceipt(text: String): PrivacyProtectionReceipt {
        if (text.isEmpty()) return PrivacyProtectionReceipt.create(text, emptyList())
        while (responses.isNotEmpty()) {
            val response = responses.first()
            if (!response.valid || response.ambiguous) {
                responses.removeFirst()
                responseOffset = 0
                continue
            }
            val offset = response.text.indexOf(text, responseOffset)
            if (offset >= 0 && response.text.indexOf(text, offset + 1) >= 0) {
                // A parser may have withheld an identical progress fragment. Without an
                // unambiguous source position, neither occurrence may claim local evidence.
                responses.removeFirst()
                responseOffset = 0
                return PrivacyProtectionReceipt.create(text, emptyList())
            }
            if (offset >= 0) {
                responseOffset = offset + text.length
                return PrivacyProtectionReceipt.create(
                    text,
                    response.ranges.mapNotNull { range ->
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
