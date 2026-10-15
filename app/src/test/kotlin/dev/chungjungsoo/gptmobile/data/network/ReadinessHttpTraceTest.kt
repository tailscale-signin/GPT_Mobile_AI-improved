package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind
import java.lang.reflect.Proxy
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessHttpTraceTest {
    @Test fun internalCorrelationNeverLeavesTheApplicationInterceptor() {
        val events = mutableListOf<RunDiagnosticKind>()
        val ticket = ReadinessHttpTrace.register(
            HttpAttemptTrace { kind, elapsed ->
                assertTrue(elapsed >= 0)
                events += kind
            }
        )
        val original = Request.Builder().url("https://example.com").header(ReadinessHttpTrace.HEADER, ticket).build()
        val call = Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { _, method, _ ->
            if (method.name == "request") original else null
        } as Call
        val listener = ReadinessHttpTrace.listener.create(call)
        var sent: Request? = null
        val chain = Proxy.newProxyInstance(Interceptor.Chain::class.java.classLoader, arrayOf(Interceptor.Chain::class.java)) { _, method, args ->
            when (method.name) {
                "request" -> original
                "proceed" -> {
                    sent = args[0] as Request
                    Response.Builder().request(requireNotNull(sent)).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
                }
                else -> null
            }
        } as Interceptor.Chain
        listener.callStart(call)
        val response = ReadinessHttpTrace.stripHeader.intercept(chain)
        listener.requestBodyEnd(call, 10)
        listener.responseHeadersEnd(call, response)
        assertNull(sent?.header(ReadinessHttpTrace.HEADER))
        assertEquals(listOf(RunDiagnosticKind.HTTP_CALL_START, RunDiagnosticKind.REQUEST_BODY_END, RunDiagnosticKind.RESPONSE_HEADERS), events)
        // A consumed ticket cannot attach an unrelated later request to this attempt.
        ReadinessHttpTrace.listener.create(call).callStart(call)
        assertEquals(3, events.size)
    }
}
