package dev.chungjungsoo.gptmobile.data.diagnostics

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Submission receipt only; durable run IDs own subsequent execution and completion. */
internal class SendAttemptTrace(
    val requestId: String = UUID.randomUUID().toString(),
    private val record: (String) -> Unit = { AppLogRecorder.record("Send", it) }
) {
    private val closed = AtomicBoolean()

    init {
        record("SEND_TAPPED · requestId=$requestId")
    }

    fun finish(reason: String) {
        if (closed.compareAndSet(false, true)) record("SEND_SUBMISSION_OUTCOME · requestId=$requestId · reason=$reason")
    }

    fun accepted(runIds: List<String>, chatId: Int, messageId: Int) {
        if (!closed.compareAndSet(false, true)) return
        runIds.forEach { runId -> record("SEND_DURABLY_ACCEPTED · requestId=$requestId · runId=$runId · chatId=$chatId · messageId=$messageId · dispatched=false") }
        record("SEND_SUBMISSION_OUTCOME · requestId=$requestId · reason=DURABLY_ACCEPTED")
    }
}
