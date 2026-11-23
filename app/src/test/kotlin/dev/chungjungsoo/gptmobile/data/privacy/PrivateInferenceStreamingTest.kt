package dev.chungjungsoo.gptmobile.data.privacy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase
import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ModelRequestPreparation
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateInferenceStreamingTest {
    @Test
    fun verifiedCloudRoutePublishesOnlyRestoredTextBeforeUpstreamCompletion() = runBlocking {
        val gate = CountDownLatch(1)
        val fixture = Fixture()
        fixture.serve({ exchange, token ->
            exchange.startStream()
            exchange.responseBody.use { output ->
                output.write(frame("Answer: " + token.dropLast(1)).toByteArray())
                output.flush()
                check(gate.await(5, TimeUnit.SECONDS))
                output.write((frame("]") + "data: [DONE]\n\n").toByteArray())
            }
        }) {
            val events = mutableListOf<ProviderEvent>()
            withTimeout(5_000) {
                fixture.collect { event ->
                    events += event
                    if (event is ProviderEvent.TextDelta) {
                        assertFalse(event.text.contains("[PRIVATE_"))
                        gate.countDown()
                    }
                    if (event == ProviderEvent.Completed) {
                        val evidence = requireNotNull(fixture.proof?.streamSnapshot())
                        assertEquals(JsonPrimitive(true), evidence["completed"])
                        assertEquals(JsonPrimitive("INCREMENTAL"), evidence["delivery_mode"])
                        assertTrue(evidence.containsKey("first_restored_answer_ms"))
                    }
                }
            }
            assertEquals("Answer: $SECRET", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
            assertEquals(1, fixture.calls.get())
            assertTrue(events.last() == ProviderEvent.Completed)
            assertEquals(ProviderUsagePhase.FINAL, events.filterIsInstance<ProviderEvent.Usage>().last().phase)
            assertTrue(fixture.configRevision.endsWith(":privacy-7"))
            assertEquals(JsonPrimitive(true), fixture.wire?.get("stream"))
            assertFalse(requireNotNull(fixture.wire).toString().contains(SECRET))
        }
    }

    @Test
    fun permissionRevisionChangedAfterFirstTextStopsTheVerifiedStreamWithoutReplay() = runBlocking {
        val gate = CountDownLatch(1)
        val fixture = Fixture()
        fixture.serve({ exchange, token ->
            exchange.startStream()
            exchange.responseBody.use { output ->
                output.write(frame("safe prefix ").toByteArray())
                output.flush()
                check(gate.await(5, TimeUnit.SECONDS))
                output.write((frame(token) + "data: [DONE]\n\n").toByteArray())
            }
        }) {
            val events = mutableListOf<ProviderEvent>()
            fixture.collect { event ->
                events += event
                if (event is ProviderEvent.TextDelta) {
                    fixture.state = fixture.state.copy(revision = 8)
                    gate.countDown()
                }
            }
            assertEquals("safe prefix ", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
            assertEquals("DISPATCH_POLICY_CHANGED", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
            assertFalse(ProviderEvent.Completed in events)
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test
    fun malformedOwnedMarkerFailsWithoutPublishingItOrRequestingAnUnmaskedRetry() = runBlocking {
        val fixture = Fixture()
        fixture.serve({ exchange, token ->
            exchange.startStream()
            exchange.responseBody.use { it.write((frame(token.replace("_CUSTOM_", "_PERSON_")) + "data: [DONE]\n\n").toByteArray()) }
        }) {
            val events = fixture.events()
            assertTrue(events.none { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
            assertEquals("PRIVACY_OUTPUT_PROTOCOL_INVALID", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
            assertEquals(1, fixture.calls.get())
            assertFalse(requireNotNull(fixture.proof?.streamSnapshot()).toString().contains(SECRET))
        }
    }

    @Test
    fun gatewayCompletionContractIsExplicitlyBufferedAndRestoresWithTheSameProof() = runBlocking {
        val fixture = Fixture(ClientType.LLAMA)
        fixture.serve({ exchange, token ->
            val bytes = JsonObject(mapOf("text" to JsonPrimitive("Answer: $token"))).toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }) {
            val events = fixture.events()
            assertEquals("Answer: $SECRET", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
            assertEquals(JsonPrimitive("COMPLETION_BUFFERED_ENDPOINT"), requireNotNull(fixture.proof?.streamSnapshot())["delivery_mode"])
            assertEquals(null, fixture.wire?.get("stream"))
            assertEquals(ProviderEvent.Completed, events.last())
        }
    }

    @Test
    fun changedApprovalAtThePhysicalBoundarySendsNoInferenceBody() = runBlocking {
        val fixture = Fixture()
        fixture.routingAllowed = false
        fixture.serve({ _, _ -> error("No request should have reached this endpoint") }) {
            val events = fixture.events()
            assertEquals(0, fixture.calls.get())
            val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
            assertEquals("DISPATCH_POLICY_CHANGED", failure.errorCode)
            assertEquals("BLOCKED", failure.taskOutcome)
            assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
            assertEquals(JsonPrimitive("provider_admission"), failure.failureDetails?.get("failed_stage"))
            assertTrue(events.none { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
        }
    }

    @Test
    fun failedAnthropicStreamNeverPromotesInitialZeroOutputUsageToFinal() = runBlocking {
        val fixture = Fixture(ClientType.ANTHROPIC)
        val seen = CountDownLatch(1)
        fixture.serve({ exchange, _ ->
            exchange.startStream()
            exchange.responseBody.use { output ->
                val initial = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":11,\"output_tokens\":0}}}\n\n"
                val answer = "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"partial answer\"}}\n\n"
                output.write((initial + answer).toByteArray())
                output.flush()
                check(seen.await(5, TimeUnit.SECONDS))
            }
        }) {
            val events = mutableListOf<ProviderEvent>()
            fixture.collect { event ->
                events += event
                if (event is ProviderEvent.TextDelta) seen.countDown()
            }
            val usage = events.filterIsInstance<ProviderEvent.Usage>()
            assertTrue(usage.isNotEmpty())
            assertTrue(usage.all { it.phase == ProviderUsagePhase.INTERIM && it.cumulative })
            assertTrue(events.any { it is ProviderEvent.TextDelta })
            assertTrue(events.any { it is ProviderEvent.Failed })
            assertFalse(ProviderEvent.Completed in events)
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test
    fun cloudWireRequestsStreamingWhileRetainingOpenRouterRoutingAndCacheGates() {
        val route = VerifiedPrivateRoute("https://example.invalid/chat/completions", ClientType.OPENROUTER, upstream = "approved/only")
        val wire = PrivateInferenceSession.wireBody(route, "model", JsonArray(emptyList()), 123)
        assertEquals(JsonPrimitive(true), wire["stream"])
        assertEquals(JsonPrimitive(true), wire["stream_options"]?.jsonObject?.get("include_usage"))
        assertEquals(JsonPrimitive(false), wire["provider"]?.jsonObject?.get("allow_fallbacks"))
        assertEquals(JsonArray(listOf(JsonPrimitive("approved/only"))), wire["provider"]?.jsonObject?.get("only"))
        assertEquals(JsonPrimitive(true), wire["provider"]?.jsonObject?.get("zdr"))
        assertEquals(JsonPrimitive("deny"), wire["provider"]?.jsonObject?.get("data_collection"))
    }

    private class Fixture(private val kind: ClientType = ClientType.OPENAI) {
        private val store = mockk<PrivacyModeStore>()
        private val verifier = mockk<PrivacyProviderVerifier>()
        private val preparation = mockk<ModelRequestPreparation>()
        private lateinit var session: PrivateInferenceSession
        val calls = AtomicInteger()

        @Volatile var state = PrivacyModeState(true, setOf(PrivacyCategory.CUSTOM), 7)
        var proof: PrivateDispatchContext? = null
        var wire: JsonObject? = null
        var configRevision = ""
        var routingAllowed = true

        init {
            every { store.effectiveSnapshot(true) } answers { state }
            coEvery { store.privateTerms() } returns listOf(SECRET)
            every { verifier.preparation } returns preparation
            every { verifier.http } returns OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build()
            coEvery { verifier.routingUnchanged(any(), any()) } answers { routingAllowed }
            coEvery { verifier.accountUnchanged(any(), any()) } returns true
            coEvery { preparation.prepareQueued(any(), any(), any(), any()) } coAnswers {
                proof = requireNotNull(currentCoroutineContext()[PrivateDispatchContext])
                val config = secondArg<ProviderRequestConfig>()
                configRevision = config.permissionRevision
                requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]).validate()
                requireNotNull(proof).validate(firstArg(), config.permissionRevision)
                firstArg<String>()
            }
        }

        suspend fun serve(handler: (HttpExchange, String) -> Unit, block: suspend () -> Unit) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                calls.incrementAndGet()
                val payload = exchange.requestBody.use { it.readBytes().decodeToString() }
                wire = Json.parseToJsonElement(payload).jsonObject
                val messages = requireNotNull(wire)["messages"] as JsonArray
                val input = messages.last().jsonObject["content"]!!.jsonPrimitive.content
                val token = Regex("\\[PRIVATE_[a-f0-9]{32}_CUSTOM_[0-9]+]").find(input)?.value ?: error("Expected protected fixture token")
                handler(exchange, token)
            }
            server.start()
            val url = "http://127.0.0.1:${server.address.port}/"
            val profile = PlatformV2(uid = "verified", name = "Private fixture", compatibleType = kind, model = "fixture", apiUrl = url)
            coEvery { verifier.verify(profile) } returns PrivacyProviderVerifier.Result(PrivacyProfileEligibility.Decision(true, "Verified fixture"), VerifiedPrivateRoute(url, kind))
            session = PrivateInferenceSession(verifier, store, profile, listOf(ConversationTurn(MessageV2(content = "value=$SECRET", platformType = null), null, true)), RequestConstraints()) { "" }
            try {
                block()
            } finally {
                server.stop(0)
            }
        }

        suspend fun collect(action: suspend (ProviderEvent) -> Unit) = withContext(ConversationPrivacyContext(71, true) + RequestAuthorizationGuard("fixture-authority") {}) {
            session.streamRound(emptyList(), emptyList()).collect(action)
        }

        suspend fun events() = withContext(ConversationPrivacyContext(71, true) + RequestAuthorizationGuard("fixture-authority") {}) {
            session.streamRound(emptyList(), emptyList()).toList()
        }
    }

    companion object {
        private const val SECRET = "violet-copper-private-fixture"
        private fun frame(text: String) = "data: " + JsonObject(mapOf("choices" to JsonArray(listOf(JsonObject(mapOf("delta" to JsonObject(mapOf("content" to JsonPrimitive(text))))))))).toString() + "\n\n"

        private fun HttpExchange.startStream() {
            responseHeaders.add("Content-Type", "text/event-stream")
            sendResponseHeaders(200, 0)
        }
    }
}
