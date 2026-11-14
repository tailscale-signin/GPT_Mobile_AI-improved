package dev.chungjungsoo.gptmobile.data.voice

/** A generation identity is independent of the revision currently selected for reading. */
data class VoiceMessageIdentity(val messageId: Int, val runId: String, val revision: Int = -1) {
    val key: String get() = "$messageId:$runId:$revision"
}

data class VoiceMessageReply(
    val identity: VoiceMessageIdentity,
    val text: String,
    val complete: Boolean,
    val failed: Boolean = false,
    val readAloud: Boolean = true
)

/**
 * Session-local admission ledger. Initial history and subsequently paged older messages never
 * autoplay. A regenerated response is new only when a previously observed message changes run.
 * A consumed generation cannot re-enter the queue after recomposition, final persistence or Stop.
 */
class VoiceMessagePlayback {
    private val known = mutableMapOf<Int, String>()
    private val consumed = mutableSetOf<String>()
    private val pending = linkedMapOf<String, VoiceMessageReply>()
    private var highWaterMark = 0
    var initialized = false
        private set
    var active: VoiceMessageReply? = null
        private set
    val hasPending: Boolean get() = pending.isNotEmpty()
    private var delivered: VoiceMessageReply? = null

    fun observe(replies: List<VoiceMessageReply>, autoplay: Boolean) {
        if (!initialized) {
            initialized = true
            replies.forEach { known[it.identity.messageId] = it.identity.runId }
            highWaterMark = replies.maxOfOrNull { it.identity.messageId } ?: 0
            return
        }
        if (!autoplay) {
            // Keep captions and turn completion even when audio is disabled. Re-enabling auto
            // read must not narrate a response first received while muted or in the background.
            pending.replaceAll { _, reply -> reply.copy(readAloud = false) }
        }
        replies.forEach { reply ->
            val identity = reply.identity
            val previousRun = known.put(identity.messageId, identity.runId)
            val newlyGenerated = identity.messageId > highWaterMark || previousRun != null && previousRun != identity.runId
            if (previousRun != null && previousRun != identity.runId) {
                val superseded = pending.values.filter { it.identity.messageId == identity.messageId && it.identity.runId != identity.runId }
                superseded.forEach {
                    pending.remove(it.identity.key)
                    consumed += it.identity.key
                }
                active?.takeIf { it.identity.messageId == identity.messageId && it.identity.runId != identity.runId }?.let {
                    active = it.copy(complete = true, failed = true)
                }
            }
            active?.takeIf { it.identity == identity }?.let { active = reply.copy(readAloud = it.readAloud) }
            pending[identity.key]?.let { pending[identity.key] = reply.copy(readAloud = it.readAloud) }
            if (newlyGenerated && identity.key !in consumed) {
                pending[identity.key] = reply.copy(readAloud = autoplay)
            }
        }
        highWaterMark = maxOf(highWaterMark, replies.maxOfOrNull { it.identity.messageId } ?: 0)
    }

    fun next(): VoiceMessageReply? {
        if (active != null) return null
        val next = pending.values.firstOrNull { it.text.isNotBlank() || it.complete } ?: return null
        pending.remove(next.identity.key)
        consumed += next.identity.key
        active = next
        delivered = null
        return next
    }

    /** Only changed text/terminal state is delivered to the audio stream. */
    fun update(): VoiceMessageReply? = active?.takeUnless { it == delivered }?.also { delivered = it }

    fun finish() {
        active = null
        delivered = null
    }

    /** Explicit replay supersedes the active generation, including its later stream/final events. */
    fun manual(identity: VoiceMessageIdentity) {
        active?.let { consumed += it.identity.key }
        consumed += identity.key
        pending.remove(identity.key)
        finish()
    }

    fun discardPending() {
        consumed.addAll(pending.keys)
        pending.clear()
    }
}
