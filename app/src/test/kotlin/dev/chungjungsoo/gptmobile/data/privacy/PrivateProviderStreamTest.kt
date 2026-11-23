package dev.chungjungsoo.gptmobile.data.privacy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateProviderStreamTest {
    @Test
    fun compatibleTextArrivesBeforeTheServerFinishesAndUsageSurvives() = runBlocking {
        val gate = CountDownLatch(1)
        val calls = AtomicInteger()
        server({ exchange ->
            calls.incrementAndGet()
            exchange.startStream()
            exchange.responseBody.use { out ->
                out.write(sse("""{"choices":[{"delta":{"content":"Early answer"}}]}""").toByteArray())
                out.flush()
                check(gate.await(5, TimeUnit.SECONDS))
                out.write((sse("""{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}""") + sse("[DONE]")).toByteArray())
            }
        }) { url ->
            val frames = mutableListOf<PrivateProviderFrame>()
            withTimeout(5_000) {
                PrivateProviderStream.read(client(), request(url), ClientType.OPENAI).collect { frame ->
                    frames += frame
                    if (frame is PrivateProviderFrame.Answer) gate.countDown()
                }
            }
            assertEquals(0L, gate.count)
            assertEquals(listOf(PrivateProviderFrame.Answer("Early answer")), frames.filterIsInstance<PrivateProviderFrame.Answer>())
            assertEquals(PrivateProviderFrame.Usage(11, 3, 14, "stop", final = true), frames.filterIsInstance<PrivateProviderFrame.Usage>().last())
            assertEquals(PrivateProviderFrame.Completed, frames.last())
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun anIgnoredStreamFlagUsesTheSameResponseAndLabelsItsBufferedDelivery() = runBlocking {
        val calls = AtomicInteger()
        server({ exchange ->
            calls.incrementAndGet()
            exchange.json(200, """{"choices":[{"message":{"content":"answer"},"finish_reason":"stop"}]}""")
        }) { url ->
            val frames = PrivateProviderStream.read(client(), request(url), ClientType.OPENROUTER).toList()
            assertEquals(PrivateProviderFrame.Delivery(PrivateDeliveryMode.COMPLETION_BUFFERED_PROVIDER), frames.first())
            assertEquals(1, calls.get())
            assertEquals(PrivateProviderFrame.Completed, frames.last())
        }
    }

    @Test
    fun anHttpRejectionRetainsOnlyItsStatusAndDoesNotReplayOrEchoTheBody() = runBlocking {
        val calls = AtomicInteger()
        server({ exchange ->
            calls.incrementAndGet()
            exchange.json(429, "confidential-provider-error")
        }) { url ->
            val error = runCatching { PrivateProviderStream.read(client(), request(url), ClientType.OPENAI).toList() }.exceptionOrNull()
            assertTrue(error is PrivateProviderResponseException)
            assertEquals(429, (error as PrivateProviderResponseException).httpStatus)
            assertFalse(error.toString().contains("confidential-provider-error"))
            assertEquals(null, error.cause)
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun cancellingCollectionCancelsTheSamePhysicalCallWithoutWaitingForCompletion() = runBlocking {
        val gate = CountDownLatch(1)
        val seen = CompletableDeferred<Unit>()
        val physical = AtomicReference<Call>()
        val client = client().newBuilder().eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                physical.set(call)
            }
        }).build()
        server({ exchange ->
            exchange.startStream()
            exchange.responseBody.use { out ->
                out.write(sse("""{"choices":[{"delta":{"content":"partial"}}]}""").toByteArray())
                out.flush()
                gate.await(5, TimeUnit.SECONDS)
            }
        }) { url ->
            try {
                val job = launch {
                    PrivateProviderStream.read(client, request(url), ClientType.OPENAI).collect { if (it is PrivateProviderFrame.Answer) seen.complete(Unit) }
                }
                withTimeout(5_000) { seen.await() }
                job.cancelAndJoin()
                assertTrue(requireNotNull(physical.get()).isCanceled())
            } finally {
                gate.countDown()
            }
        }
    }

    @Test
    fun anthropicReasoningTextAndSeparatedUsageAreParsedWithoutExposingSignatures() {
        val frames = parse(
            ClientType.ANTHROPIC,
            """{"type":"message_start","message":{"usage":{"input_tokens":9,"output_tokens":0}}}""",
            """{"type":"content_block_start","content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"plan"}}""",
            """{"type":"content_block_delta","delta":{"type":"signature_delta","signature":"private-signature"}}""",
            """{"type":"content_block_start","content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"answer"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}""",
            """{"type":"message_stop"}"""
        )
        assertEquals(listOf(PrivateProviderFrame.Reasoning("plan")), frames.filterIsInstance<PrivateProviderFrame.Reasoning>())
        assertEquals(listOf(PrivateProviderFrame.Answer("answer")), frames.filterIsInstance<PrivateProviderFrame.Answer>())
        assertEquals(PrivateProviderFrame.Usage(9, 4, null, "end_turn", final = true), frames.filterIsInstance<PrivateProviderFrame.Usage>().last())
        assertFalse(frames.toString().contains("private-signature"))
    }

    @Test
    fun nativeToolsFailClosedInBothTextProtocols() {
        listOf(
            ClientType.OPENAI to """{"choices":[{"delta":{"tool_calls":[{"function":{"name":"exfiltrate"}}]}}]}""",
            ClientType.OPENAI to """{"choices":[{"delta":{"role":"tool","content":"exfiltrate"}}]}""",
            ClientType.OPENAI to """{"choices":[{"delta":{"role":"function","content":"exfiltrate"}}]}""",
            ClientType.ANTHROPIC to """{"type":"content_block_start","content_block":{"type":"tool_use","name":"exfiltrate"}}"""
        ).forEach { (kind, event) ->
            val frames = mutableListOf<PrivateProviderFrame>()
            val error = runCatching { PrivateProviderResponse(kind, frames::add).event(event) }.exceptionOrNull()
            assertEquals(PrivateResponseFailure.UNEXPECTED_CONTROL, (error as PrivateProviderResponseException).reason)
            assertTrue(frames.isEmpty())
            assertFalse(error.toString().contains("exfiltrate"))
        }
    }

    @Test
    fun compatibleContentWithAForbiddenFinishPublishesNothingFromThatFrame() {
        listOf("tool_calls", "function_call", "error").forEach { finish ->
            listOf(false, true).forEach { buffered ->
                val field = if (buffered) "message" else "delta"
                val body = """{"choices":[{"$field":{"content":"not publishable","reasoning_content":"nor this"},"finish_reason":"$finish"}],"usage":{"completion_tokens":2}}"""
                val frames = mutableListOf<PrivateProviderFrame>()
                val decoder = PrivateProviderResponse(ClientType.OPENAI, frames::add)
                val error = runCatching { if (buffered) decoder.completedJson(body) else decoder.event(body) }.exceptionOrNull()
                assertEquals(if (finish == "error") PrivateResponseFailure.PROVIDER_ERROR else PrivateResponseFailure.UNEXPECTED_CONTROL, (error as PrivateProviderResponseException).reason)
                assertTrue("Invalid $finish frame must publish nothing (buffered=$buffered)", frames.isEmpty())
            }
        }
    }

    @Test
    fun anthropicBufferedContentWithAForbiddenStopPublishesNothing() {
        listOf("tool_use", "error").forEach { stop ->
            val body = """{"content":[{"type":"text","text":"not publishable"}],"stop_reason":"$stop","usage":{"output_tokens":2}}"""
            val frames = mutableListOf<PrivateProviderFrame>()
            val error = runCatching { PrivateProviderResponse(ClientType.ANTHROPIC, frames::add).completedJson(body) }.exceptionOrNull()
            assertEquals(if (stop == "error") PrivateResponseFailure.PROVIDER_ERROR else PrivateResponseFailure.UNEXPECTED_CONTROL, (error as PrivateProviderResponseException).reason)
            assertTrue(frames.isEmpty())
        }
    }

    @Test
    fun anthropicTextBeforeANativeControlInTheSameContentArrayPublishesNothing() {
        listOf("tool_use", "server_tool_use", "tool_result").forEach { type ->
            listOf(false, true).forEach { buffered ->
                val content = """[{"type":"text","text":"not publishable"},{"type":"$type","name":"untrusted"}]"""
                val body = if (buffered) """{"content":$content,"stop_reason":"end_turn"}""" else """{"type":"message_start","message":{"content":$content,"usage":{"output_tokens":0}}}"""
                val frames = mutableListOf<PrivateProviderFrame>()
                val decoder = PrivateProviderResponse(ClientType.ANTHROPIC, frames::add)
                val error = runCatching { if (buffered) decoder.completedJson(body) else decoder.event(body) }.exceptionOrNull()
                assertEquals(PrivateResponseFailure.UNEXPECTED_CONTROL, (error as PrivateProviderResponseException).reason)
                assertTrue("Native $type block must prevent publication (buffered=$buffered)", frames.isEmpty())
            }
        }
    }

    @Test
    fun missingCompletionAndMalformedFramesNeverBecomeSuccess() {
        listOf(
            sse("""{"choices":[{"delta":{"content":"partial"}}]}""") to PrivateResponseFailure.MISSING_COMPLETION,
            sse("{confidential-invalid-json}") to PrivateResponseFailure.MALFORMED_EVENT,
            sse("""{"error":{"message":"secret"}}""") to PrivateResponseFailure.PROVIDER_ERROR
        ).forEach { (body, reason) ->
            val frames = mutableListOf<PrivateProviderFrame>()
            val error = runCatching { PrivateProviderStream.readEvents(body.byteInputStream(), PrivateProviderResponse(ClientType.OPENAI, frames::add)) }.exceptionOrNull()
            assertEquals(reason, (error as PrivateProviderResponseException).reason)
            assertTrue(PrivateProviderFrame.Completed !in frames)
            assertFalse(error.toString().contains("confidential"))
        }
    }

    @Test
    fun explicitCompatibleFinishCanCompleteWithoutSentinelAndKeepsLimitReason() {
        val frames = parse(ClientType.OPENAI, """{"choices":[{"delta":{"content":"answer"},"finish_reason":"length"}]}""")
        assertEquals("length", frames.filterIsInstance<PrivateProviderFrame.Usage>().last().finishReason)
        assertEquals(PrivateProviderFrame.Completed, frames.last())
    }

    @Test
    fun unknownFinishDoesNotProveCompletionAndErrorFinishIsAlwaysAFailure() {
        listOf(
            "provider-specific-unrecognized" to PrivateResponseFailure.MISSING_COMPLETION,
            "error" to PrivateResponseFailure.PROVIDER_ERROR
        ).forEach { (finish, reason) ->
            val frames = mutableListOf<PrivateProviderFrame>()
            val body = sse("""{"choices":[{"delta":{"content":"partial"},"finish_reason":"$finish"}]}""")
            val error = runCatching { PrivateProviderStream.readEvents(body.byteInputStream(), PrivateProviderResponse(ClientType.OPENAI, frames::add)) }.exceptionOrNull()
            assertEquals(reason, (error as PrivateProviderResponseException).reason)
            assertTrue(PrivateProviderFrame.Completed !in frames)
        }
    }

    @Test
    fun eventAndAggregateLimitsCoverUntrustedDataAndHeartbeats() {
        listOf(
            "data: " + "x".repeat(PrivateProviderStream.MAX_EVENT_BYTES),
            (":" + "x".repeat(255) + "\n").repeat(PrivateProviderStream.MAX_RESPONSE_BYTES / 256 + 1)
        ).forEach { body ->
            val error = runCatching { PrivateProviderStream.readEvents(body.byteInputStream(), PrivateProviderResponse(ClientType.OPENAI) {}) }.exceptionOrNull()
            assertEquals(PrivateResponseFailure.RESPONSE_LIMIT, (error as PrivateProviderResponseException).reason)
        }
    }

    @Test
    fun multilineEventsAndCrLfKeepJsonTextIntact() {
        val frames = mutableListOf<PrivateProviderFrame>()
        val body = ": comment\r\nevent: message\r\ndata: {\r\ndata: \"choices\":[{\"delta\":{\"content\":\"Unicode: café\"}}]}\r\n\r\ndata: [DONE]\r\n\r\n"
        PrivateProviderStream.readEvents(body.byteInputStream(), PrivateProviderResponse(ClientType.OPENAI, frames::add))
        assertEquals("Unicode: café", frames.filterIsInstance<PrivateProviderFrame.Answer>().single().text)
    }

    private fun parse(kind: ClientType, vararg events: String): List<PrivateProviderFrame> {
        val frames = mutableListOf<PrivateProviderFrame>()
        PrivateProviderStream.readEvents(events.joinToString("") { sse(it) }.byteInputStream(), PrivateProviderResponse(kind, frames::add))
        return frames
    }

    private fun sse(data: String) = "data: $data\n\n"
    private fun client() = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build()
    private fun request(url: String) = Request.Builder().url(url).build()

    private fun HttpExchange.startStream() {
        requestBody.close()
        responseHeaders.add("Content-Type", "text/event-stream")
        sendResponseHeaders(200, 0)
    }

    private fun HttpExchange.json(status: Int, body: String) {
        requestBody.close()
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private suspend fun server(handler: (HttpExchange) -> Unit, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { handler(it) }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/")
        } finally {
            server.stop(0)
        }
    }
}
