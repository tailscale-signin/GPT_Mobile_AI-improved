package dev.chungjungsoo.gptmobile.data.privacy

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/**
 * Policy of one saved conversation. Availability in Options never changes this policy.
 * Coroutine children inherit it; callback and transport boundaries must capture it explicitly.
 */
internal class ConversationPrivacyContext(
    val chatId: Int?,
    val enabled: Boolean
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<ConversationPrivacyContext?> {
    override fun updateThreadContext(context: CoroutineContext): ConversationPrivacyContext? = current.get().also { current.set(this) }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ConversationPrivacyContext?) {
        if (oldState == null) current.remove() else current.set(oldState)
    }

    companion object Key : CoroutineContext.Key<ConversationPrivacyContext> {
        private val current = ThreadLocal<ConversationPrivacyContext?>()

        fun currentOrNull(): ConversationPrivacyContext? = current.get()

        fun capture(): ConversationPrivacyContext? = current.get()

        /** Callback/executor bridge; never mutates app settings or another conversation. */
        fun <T> withCaptured(captured: ConversationPrivacyContext?, block: () -> T): T {
            val previous = current.get()
            if (captured == null) current.remove() else current.set(captured)
            return try {
                block()
            } finally {
                if (previous == null) current.remove() else current.set(previous)
            }
        }
    }
}
