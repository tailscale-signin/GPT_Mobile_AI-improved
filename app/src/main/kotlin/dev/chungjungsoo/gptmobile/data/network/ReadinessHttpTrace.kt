package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind
import java.util.UUID
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Response

/** The internal header is stripped by an application interceptor before bytes reach any network. */
internal class HttpAttemptTrace(private val record: (RunDiagnosticKind, Long) -> Unit) {
    private val started = System.nanoTime()
    fun mark(kind: RunDiagnosticKind) {
        runCatching { record(kind, ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)) }
    }
}

internal object ReadinessHttpTrace {
    const val HEADER = "X-GPTMobile-Internal-Trace"
    private data class Ticket(val trace: HttpAttemptTrace, val at: Long)
    private val tickets = linkedMapOf<String, Ticket>()

    @Synchronized fun register(trace: HttpAttemptTrace): String {
        val now = System.nanoTime()
        tickets.entries.removeAll { now - it.value.at > 3_600_000_000_000L }
        while (tickets.size >= 128) tickets.remove(tickets.keys.first())
        return UUID.randomUUID().toString().also { tickets[it] = Ticket(trace, now) }
    }

    @Synchronized private fun take(id: String?): HttpAttemptTrace? = id?.let { tickets.remove(it)?.trace }
    val stripHeader = Interceptor { chain -> chain.proceed(chain.request().newBuilder().removeHeader(HEADER).build()) }
    val listener = EventListener.Factory { call ->
        val trace = take(call.request().header(HEADER))
        object : EventListener() {
            override fun callStart(call: Call) {
                trace?.mark(RunDiagnosticKind.HTTP_CALL_START)
            }
            override fun requestBodyEnd(call: Call, byteCount: Long) {
                trace?.mark(RunDiagnosticKind.REQUEST_BODY_END)
            }
            override fun responseHeadersEnd(call: Call, response: Response) {
                trace?.mark(RunDiagnosticKind.RESPONSE_HEADERS)
            }
        }
    }
}
