package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Response

/** The internal header is stripped by an application interceptor before bytes reach any network. */
internal class HttpAttemptTrace(private val record: (RunDiagnosticKind, Long) -> Unit) {
    private val started = System.nanoTime()
    private val callStarted = AtomicBoolean()
    private val connectionOrRequestStarted = AtomicBoolean()
    private val unsentDnsFailure = AtomicBoolean()
    val provenUnsent: Boolean get() = callStarted.get() && !connectionOrRequestStarted.get() && unsentDnsFailure.get()

    fun connectionOrRequestStarted() {
        connectionOrRequestStarted.set(true)
    }

    fun failed(error: IOException) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var count = 0
        while (current != null && count++ < 16 && seen.add(current)) {
            if (current is UnknownHostException) {
                unsentDnsFailure.set(true)
                return
            }
            current = current.cause
        }
    }

    fun mark(kind: RunDiagnosticKind) {
        if (kind == RunDiagnosticKind.HTTP_CALL_START) callStarted.set(true)
        if (kind == RunDiagnosticKind.REQUEST_BODY_END || kind == RunDiagnosticKind.RESPONSE_HEADERS) connectionOrRequestStarted.set(true)
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
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                trace?.connectionOrRequestStarted()
            }
            override fun connectionAcquired(call: Call, connection: Connection) {
                trace?.connectionOrRequestStarted()
            }
            override fun requestHeadersStart(call: Call) {
                trace?.connectionOrRequestStarted()
            }
            override fun requestBodyStart(call: Call) {
                trace?.connectionOrRequestStarted()
            }
            override fun callFailed(call: Call, ioe: IOException) {
                trace?.failed(ioe)
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
