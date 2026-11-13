package dev.chungjungsoo.gptmobile.data.diagnostics

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Counts already owned by the subtree: turns, timeline entries or log rows, not viewport visibility.
 * Never derive these by walking chat history or inspecting rendered text.
 */
internal data class UiOperationContext(
    val component: String,
    val itemCount: Int? = null,
    val retainedItemCount: Int? = null,
    val eventCount: Int? = null,
    val blockCount: Int? = null,
    val pageIndex: Int? = null,
    val frozen: Boolean? = null
) {
    fun fields(): JsonObject = buildJsonObject {
        put("component", component.take(40))
        itemCount?.takeIf { it >= 0 }?.let { put("itemCount", it) }
        retainedItemCount?.takeIf { it >= 0 }?.let { put("retainedItemCount", it) }
        eventCount?.takeIf { it >= 0 }?.let { put("eventCount", it) }
        blockCount?.takeIf { it >= 0 }?.let { put("blockCount", it) }
        pageIndex?.takeIf { it >= 0 }?.let { put("pageIndex", it) }
        frozen?.let { put("frozen", it) }
    }
}

/** Layout inputs distinguish legitimate remeasurement from repeated identical work. */
internal data class TextLayoutDiagnosticContext(
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val density: Float,
    val fontScale: Float,
    val rightToLeft: Boolean
) {
    fun fields(): JsonObject = buildJsonObject {
        put("minWidth", minWidth)
        put("maxWidth", maxWidth)
        put("minHeight", minHeight)
        put("maxHeight", maxHeight)
        density.takeIf { it.isFinite() && it > 0 }?.let { put("density", it) }
        fontScale.takeIf { it.isFinite() && it > 0 }?.let { put("fontScale", it) }
        put("rightToLeft", rightToLeft)
    }
}

/** Structural identity only; source text and prompts never enter performance records. */
internal data class CombinedOperationOwner(
    val screen: String = "Chat",
    val messageId: Int? = null,
    val runId: String? = null,
    val revisionId: String? = null,
    val blockId: String? = null,
    val digest: String? = null,
    val profileUid: String? = null,
    val uiContext: UiOperationContext? = null,
    val textLayoutContext: TextLayoutDiagnosticContext? = null
) {
    /** Formats only for an aggregate window report, not for each sampled operation. */
    fun frameContext(): JsonObject = buildJsonObject {
        fun identity(key: String, value: String?) {
            value?.takeIf(String::isNotBlank)?.let { put(key, it.take(160)) }
        }
        identity("screen", screen)
        messageId?.takeIf { it > 0 }?.let { put("message", it) }
        identity("run", runId)
        identity("revision", revisionId)
        identity("block", blockId)
        identity("profile", profileUid)
        uiContext?.let { put("ui", it.fields()) }
        textLayoutContext?.let { put("textLayout", it.fields()) }
    }
}

/** Async slices remain valid when a suspend operation resumes on another dispatcher. */
internal object CombinedOperationTrace {
    internal const val FRAME_SAMPLE_THRESHOLD_NANOS = 4_000_000L
    private val nextCookie = AtomicInteger()
    private val frameCoverage = FrameOperationCoverage()
    internal fun frameWindow(startedNanos: Long, endedNanos: Long): FrameOperationCoverage.Snapshot = frameCoverage.snapshot(startedNanos, endedNanos)
    internal fun clearFrameHistory() = frameCoverage.clear()
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
        val platformTrace: Boolean,
        val diagnosticCaptureEpoch: Long?,
        val frameGeneration: Long
    )

    fun begin(operation: String, owner: CombinedOperationOwner = CombinedOperationOwner()): Token? {
        val trace = platformAvailable && runCatching { Trace.isEnabled() }.getOrDefault(false)
        // Capture before reading privacy. A privacy/clear transition during begin
        // invalidates this epoch even if the operation ends after logging resumes.
        val diagnosticCaptureEpoch = AppLogRecorder.operationCaptureEpoch()
        if (!trace && diagnosticCaptureEpoch == null) return null
        val token = Token(operation, owner, nextCookie.incrementAndGet(), System.nanoTime(), Thread.currentThread().name, trace, diagnosticCaptureEpoch, frameCoverage.generation())
        if (trace) runCatching { Trace.beginAsyncSection("Combined.$operation", token.cookie) }
        return token
    }

    fun end(token: Token?) {
        if (token == null) return
        val endedNanos = System.nanoTime()
        val duration = (endedNanos - token.startedNanos).coerceAtLeast(0)
        if (token.platformTrace) runCatching { Trace.endAsyncSection("Combined.${token.operation}", token.cookie) }
        // Perfetto retains every enabled slice. Text diagnostics sample frequent
        // operations at 16 ms and other operations at 100 ms; these records are
        // not a complete latency population.
        val threshold = if (token.operation in highFrequency || token.operation.startsWith("ui_")) 16_000_000L else 100_000_000L
        val diagnosticCaptureEpoch = token.diagnosticCaptureEpoch ?: return
        if (!AppLogRecorder.operationCaptureCurrent(diagnosticCaptureEpoch)) return
        // Frame correlation must not inherit the much higher text-log threshold:
        // repeated 4–15 ms layout/DAO work can miss a high-refresh display deadline
        // without ever producing a slow-operation log. Keep those samples bounded
        // in memory and emit only the normal per-window coverage report.
        if (duration >= FRAME_SAMPLE_THRESHOLD_NANOS && !frameCoverage.observe(FrameOperationCoverage.Sample(token.operation, token.cookie, token.startedNanos, endedNanos, token.owner.runId, token.owner), token.frameGeneration)) return
        if (duration < threshold) return
        fun value(text: String?) = text?.replace('\n', ' ')?.replace('\r', ' ')?.take(160) ?: "unknown"
        val owner = token.owner
        runCatching {
            AppLogRecorder.recordCaptured(
                diagnosticCaptureEpoch,
                "CombinedTiming",
                "OPERATION_TIMING · operation=${token.operation} · traceId=${token.cookie} · durationNs=$duration · logThresholdNs=$threshold · screen=${value(owner.screen)} · message=${owner.messageId ?: "unknown"} · run=${value(owner.runId)} · revision=${value(owner.revisionId)} · block=${value(owner.blockId)} · digest=${value(owner.digest?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } })} · profile=${value(owner.profileUid)} · startThread=${value(token.startThread)} · endThread=${value(Thread.currentThread().name)}" +
                    owner.uiContext?.let { " · uiContext=${it.fields()}" }.orEmpty() +
                    owner.textLayoutContext?.let { " · textLayoutContext=${it.fields()}" }.orEmpty()
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
