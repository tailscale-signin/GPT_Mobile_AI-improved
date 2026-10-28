package dev.chungjungsoo.gptmobile.data.diagnostics

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/** Structural identity only; source text and prompts never enter performance records. */
internal data class CombinedOperationOwner(
    val screen: String = "Chat",
    val messageId: Int? = null,
    val runId: String? = null,
    val revisionId: String? = null,
    val blockId: String? = null,
    val digest: String? = null,
    val profileUid: String? = null
)

/** Async slices remain valid when a suspend operation resumes on another dispatcher. */
internal object CombinedOperationTrace {
    private val nextCookie = AtomicInteger()
    private val platformAvailable by lazy {
        runCatching {
            Trace.isEnabled()
            true
        }.getOrDefault(false)
    }
    private val highFrequency = setOf("protocol_decode", "text_measure", "text_layout", "document_block_read", "source_block_read")

    internal data class Token(
        val operation: String,
        val owner: CombinedOperationOwner,
        val cookie: Int,
        val startedNanos: Long,
        val startThread: String,
        val platformTrace: Boolean
    )

    fun begin(operation: String, owner: CombinedOperationOwner = CombinedOperationOwner()): Token? {
        val trace = platformAvailable && runCatching { Trace.isEnabled() }.getOrDefault(false)
        if (!trace && !AppLogRecorder.enabled.value) return null
        val token = Token(operation, owner, nextCookie.incrementAndGet(), System.nanoTime(), Thread.currentThread().name, trace)
        if (trace) runCatching { Trace.beginAsyncSection("Combined.$operation", token.cookie) }
        return token
    }

    fun end(token: Token?) {
        if (token == null) return
        val duration = (System.nanoTime() - token.startedNanos).coerceAtLeast(0)
        if (token.platformTrace) runCatching { Trace.endAsyncSection("Combined.${token.operation}", token.cookie) }
        // Perfetto retains every enabled slice. Text diagnostics sample frequent
        // operations at 16 ms and other operations at 100 ms; these records are
        // not a complete latency population.
        val threshold = if (token.operation in highFrequency) 16_000_000L else 100_000_000L
        if (!AppLogRecorder.enabled.value || duration < threshold) return
        fun value(text: String?) = text?.replace('\n', ' ')?.replace('\r', ' ')?.take(160) ?: "unknown"
        val owner = token.owner
        runCatching {
            AppLogRecorder.record(
                "CombinedTiming",
                "OPERATION_TIMING · operation=${token.operation} · traceId=${token.cookie} · durationNs=$duration · logThresholdNs=$threshold · screen=${value(owner.screen)} · message=${owner.messageId ?: "unknown"} · run=${value(owner.runId)} · revision=${value(owner.revisionId)} · block=${value(owner.blockId)} · digest=${value(owner.digest?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } })} · profile=${value(owner.profileUid)} · startThread=${value(token.startThread)} · endThread=${value(Thread.currentThread().name)}"
            )
        }
    }

    inline fun <T> measure(operation: String, owner: CombinedOperationOwner = CombinedOperationOwner(), block: () -> T): T {
        val token = begin(operation, owner)
        try {
            return block()
        } finally {
            end(token)
        }
    }
}
