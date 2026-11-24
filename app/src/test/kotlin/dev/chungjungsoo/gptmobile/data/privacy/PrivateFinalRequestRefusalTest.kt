package dev.chungjungsoo.gptmobile.data.privacy

import android.content.ContextWrapper
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.provider.AnthropicMessagesAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.GeminiAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.OpenAICompatibleAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.OpenAIResponsesAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderAttachmentEncoder
import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary
import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptObserver
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.PrivacyFinalRequestFailureEvidence
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPIImpl
import dev.chungjungsoo.gptmobile.data.network.GoogleAPIImpl
import dev.chungjungsoo.gptmobile.data.network.GroqAPIImpl
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ModelRequestPreparation
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPIImpl
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateFinalRequestRefusalTest {
    @Test
    fun realProviderSerializersPropagateFinalWireRefusalThroughAdaptersAndMaskedReceiver() = runBlocking {
        for (route in Route.entries) {
            withFixture(route) { fixture ->
                val failure = assertRefused(fixture.collect(), dispatched = false)
                assertEquals(JsonPrimitive("NO"), failure.failureDetails?.get("remote_acceptance"))
                assertEquals(0, fixture.physicalAttempts.get())
                assertEquals(0, fixture.observedAttempts.get())
                assertEquals(0, fixture.httpRequests.get())
                assertTrue(fixture.dispatch.receipts.isEmpty())
                assertTrue(fixture.dispatch.serializedRequestSettings.isEmpty())
                assertNull(fixture.dispatch.latestHttpAttemptEvidence())
                assertTrue(fixture.dispatch.provenEntirelyUnsent())
                val localReceipt = fixture.dispatch.privacyDispatchReceipts.single()
                assertEquals(JsonPrimitive("KNOWN_VALUE_REINTRODUCED"), localReceipt["outcome"])
                assertTrue(localReceipt.containsKey("private_request_id"))
                assertTrue(localReceipt.containsKey("payload_hmac"))
                assertClosed(fixture)
            }
        }
    }

    @Test
    fun realOpenAiRefusalWithoutAccountingContextStillProvesFreshRequestUnsent() = runBlocking {
        for (route in OPENAI_ROUTES) {
            withFixture(route, retainDispatch = false) { fixture ->
                val failure = assertRefused(fixture.collect(), dispatched = false)
                assertEquals(JsonPrimitive("NO"), failure.failureDetails?.get("remote_acceptance"))
                assertEquals(0, fixture.physicalAttempts.get())
                assertEquals(0, fixture.httpRequests.get())
                assertClosed(fixture)
            }
        }
    }

    @Test
    fun genericRealOpenAiTransportFailureKeepsItsExistingClassification() = runBlocking {
        for (route in OPENAI_ROUTES) {
            withFixture(route) { fixture ->
                fixture.reintroduce = false
                val events = fixture.collect()
                val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
                assertEquals("PROVIDER_INVOCATION_FAILED", failure.errorCode)
                assertFalse(failure.failureDetails.orEmpty().containsKey(PrivacyFinalRequestFailureEvidence.FIELD))
                assertFalse(failure.failureDetails.orEmpty().containsKey("privacy_receipt"))
                assertFalse(events.contains(ProviderEvent.Completed))
                assertEquals(1, fixture.physicalAttempts.get())
                assertEquals(1, fixture.observedAttempts.get())
                assertEquals(1, fixture.httpRequests.get())
                assertEquals(JsonPrimitive("KNOWN_VALUES_ABSENT"), fixture.dispatch.privacyDispatchReceipts.single()["outcome"])
                assertClosed(fixture)
            }
        }
    }

    @Test
    fun earlierPhysicalAttemptIsNotErasedByALaterFinalWireRefusal() = runBlocking {
        for (route in OPENAI_ROUTES) {
            withFixture(route) { fixture ->
                fixture.reintroduce = false
                assertEquals("PROVIDER_INVOCATION_FAILED", fixture.collect().filterIsInstance<ProviderEvent.Failed>().single().errorCode)
                assertFalse(fixture.dispatch.provenEntirelyUnsent())
                val previousAttempt = fixture.dispatch.receipts.single()
                fixture.reintroduce = true
                val failure = assertRefused(fixture.collect(), dispatched = true)
                assertEquals(JsonPrimitive("UNKNOWN"), failure.failureDetails?.get("remote_acceptance"))
                assertFalse(fixture.dispatch.provenEntirelyUnsent())
                assertEquals(listOf(previousAttempt), fixture.dispatch.receipts.toList())
                assertEquals(1, fixture.physicalAttempts.get())
                assertEquals(1, fixture.observedAttempts.get())
                assertEquals(1, fixture.httpRequests.get())
                assertEquals(
                    listOf(JsonPrimitive("KNOWN_VALUES_ABSENT"), JsonPrimitive("KNOWN_VALUE_REINTRODUCED")),
                    fixture.dispatch.privacyDispatchReceipts.map { it["outcome"] }
                )
                assertClosed(fixture, streams = 2)
            }
        }
    }

    @Test
    fun inheritedPhysicalObserverRetainsEarlierWorkWithoutAccountingContext() = runBlocking {
        withFixture(Route.OPENAI_CHAT, retainDispatch = false) { fixture ->
            // The fixture admits a prior sanitized wire inside this same producer scope.
            // No transport evidence follows it, so the later refusal cannot establish NO.
            fixture.prepareEarlierWire = true
            val failure = assertRefused(fixture.collect(), dispatched = true)
            assertEquals(JsonPrimitive("UNKNOWN"), failure.failureDetails?.get("remote_acceptance"))
            assertEquals(1, fixture.physicalAttempts.get())
            assertEquals(1, fixture.observedAttempts.get())
            assertEquals(0, fixture.httpRequests.get())
            assertClosed(fixture)
        }
    }

    @Test
    fun cancellingRealPreparationPreservesOriginalCancellationAndClosesProducer() = runBlocking {
        for (route in OPENAI_ROUTES) assertCancellation(route, receivingRefusal = false)
    }

    @Test
    fun cancellingTypedRefusalPublicationDoesNotInventPolicyFailureOrCompletion() = runBlocking {
        for (route in OPENAI_ROUTES) assertCancellation(route, receivingRefusal = true)
    }

    @Test
    fun typedRefusalStillRevalidatesReceivingAuthorityAndPrivacyRevision() = runBlocking {
        for (route in OPENAI_ROUTES) {
            for (changePrivacy in listOf(false, true)) {
                withFixture(route) { fixture ->
                    fixture.beforeValidation = {
                        if (currentCoroutineContext()[PrivateDispatchContext] == null && fixture.dispatch.privacyDispatchReceipts.isNotEmpty()) {
                            if (changePrivacy) {
                                fixture.state = fixture.state.copy(revision = fixture.state.revision + 1)
                            } else {
                                fixture.revoked = true
                            }
                        }
                    }
                    val events = fixture.collect()
                    val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
                    assertEquals("DISPATCH_POLICY_CHANGED", failure.errorCode)
                    assertEquals("PARTIAL", failure.taskOutcome)
                    assertEquals(JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
                    assertEquals(JsonPrimitive(false), failure.failureDetails?.get("logical_continuation_allowed"))
                    assertFalse(events.contains(ProviderEvent.Completed))
                    assertTrue(events.none { it is ProviderEvent.TextDelta || it is ProviderEvent.ThinkingDelta })
                    assertEquals(JsonPrimitive("KNOWN_VALUE_REINTRODUCED"), fixture.dispatch.privacyDispatchReceipts.single()["outcome"])
                    assertEquals(0, fixture.physicalAttempts.get())
                    assertEquals(0, fixture.httpRequests.get())
                    assertFalse(failure.toString().contains(PRIVATE_VALUE) || failure.toString().contains("revoked-fixture"))
                    assertClosed(fixture)
                }
            }
        }
    }

    @Test
    fun sanitizedWireStillCompletesThroughTheRealOpenAiClientAndMaskedReceiver() = runBlocking {
        withFixture(Route.OPENAI_CHAT) { fixture ->
            fixture.reintroduce = false
            fixture.completeNormally = true
            val events = fixture.collect()
            assertEquals("Safe fixture answer.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
            assertEquals(1, events.count { it == ProviderEvent.Completed })
            assertTrue(events.none { it is ProviderEvent.Failed })
            assertEquals(1, fixture.physicalAttempts.get())
            assertEquals(1, fixture.observedAttempts.get())
            assertEquals(1, fixture.httpRequests.get())
            assertEquals(JsonPrimitive("KNOWN_VALUES_ABSENT"), fixture.dispatch.privacyDispatchReceipts.single()["outcome"])
            assertEquals(1, fixture.streamCloses.get())
            assertEquals(JsonPrimitive(true), requireNotNull(fixture.proof?.streamSnapshot())["completed"])
        }
    }

    private suspend fun assertCancellation(route: Route, receivingRefusal: Boolean) = withFixture(route) { fixture ->
        val validating = CompletableDeferred<Unit>()
        val guardClosed = CompletableDeferred<Unit>()
        val caughtByGuard = CompletableDeferred<CancellationException>()
        val caughtByCollector = CompletableDeferred<CancellationException>()
        val original = RefusalCancellation(route.name, receivingRefusal)
        fixture.beforeValidation = {
            val inProducer = currentCoroutineContext()[PrivateDispatchContext] != null
            if ((!receivingRefusal && inProducer) ||
                (receivingRefusal && !inProducer && fixture.dispatch.privacyDispatchReceipts.isNotEmpty())
            ) {
                validating.complete(Unit)
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    caughtByGuard.complete(cancelled)
                    throw cancelled
                } finally {
                    guardClosed.complete(Unit)
                }
            }
        }
        val events = mutableListOf<ProviderEvent>()
        kotlinx.coroutines.coroutineScope {
            val job = launch {
                try {
                    fixture.collect { events += it }
                } catch (cancelled: CancellationException) {
                    caughtByCollector.complete(cancelled)
                    throw cancelled
                }
            }
            try {
                withTimeout(5_000) { validating.await() }
                job.cancel(original)
                withTimeout(5_000) {
                    job.join()
                    guardClosed.await()
                }
                assertSame(original, caughtByGuard.await())
                assertSame(original, caughtByCollector.await())
                assertTrue(job.isCancelled)
                assertTrue(events.none { it is ProviderEvent.Failed || it == ProviderEvent.Completed || it is ProviderEvent.TextDelta || it is ProviderEvent.ThinkingDelta })
                assertEquals(0, fixture.physicalAttempts.get())
                assertEquals(0, fixture.httpRequests.get())
                assertEquals(if (receivingRefusal) 1 else 0, fixture.dispatch.privacyDispatchReceipts.size)
                assertClosed(fixture)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    private fun assertRefused(events: List<ProviderEvent>, dispatched: Boolean): ProviderEvent.Failed {
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals(PrivacyFinalRequestFailureEvidence.CODE, failure.errorCode)
        assertEquals(if (dispatched) "PARTIAL" else "BLOCKED", failure.taskOutcome)
        assertFalse(failure.transportComplete)
        assertNull(failure.providerLimit)
        assertTrue(events.none { it == ProviderEvent.Completed || it is ProviderEvent.TextDelta || it is ProviderEvent.ThinkingDelta })
        val details = requireNotNull(failure.failureDetails)
        assertEquals(JsonPrimitive("request_validation"), details["failed_stage"])
        assertEquals(JsonPrimitive("KNOWN_VALUE_REINTRODUCED"), details[PrivacyFinalRequestFailureEvidence.FIELD])
        assertEquals(JsonPrimitive(dispatched), details["dispatched"])
        assertEquals(JsonPrimitive(false), details["request_replay_allowed"])
        assertEquals(JsonPrimitive(false), details["logical_continuation_allowed"])
        assertEquals(JsonPrimitive(false), details["retryable"])
        assertEquals(
            setOf("failed_stage", PrivacyFinalRequestFailureEvidence.FIELD, "dispatched", "remote_acceptance", "request_replay_allowed", "logical_continuation_allowed", "retryable"),
            details.keys
        )
        assertFalse(failure.toString().contains(PRIVATE_VALUE))
        return failure
    }

    private fun assertClosed(fixture: Fixture, streams: Int = 1) {
        assertEquals(streams, fixture.streamStarts.get())
        assertEquals(streams, fixture.streamCloses.get())
        assertEquals(JsonPrimitive(false), requireNotNull(fixture.proof?.streamSnapshot())["completed"])
    }

    private suspend fun withFixture(route: Route, retainDispatch: Boolean = true, action: suspend (Fixture) -> Unit) {
        LocalServiceHealth.networkChanged()
        val fixture = Fixture(route, retainDispatch)
        try {
            withTimeout(10_000) { action(fixture) }
        } finally {
            fixture.close()
            LocalServiceHealth.networkChanged()
        }
    }

    private class Fixture(private val route: Route, retainDispatch: Boolean) {
        val dispatch = RequestDispatchContext(emptySet())
        val physicalAttempts = AtomicInteger()
        val observedAttempts = AtomicInteger()
        val httpRequests = AtomicInteger()
        val streamStarts = AtomicInteger()
        val streamCloses = AtomicInteger()
        var reintroduce = true
        var prepareEarlierWire = false
        var completeNormally = false

        @Volatile var state = PrivacyModeState(true, setOf(PrivacyCategory.CUSTOM), 7)

        @Volatile var revoked = false

        @Volatile var proof: PrivateDispatchContext? = null
        var beforeValidation: suspend () -> Unit = {}
        private val store = mockk<PrivacyModeStore>()
        private val wrapper = mockk<NetworkClient>()
        private val profile = PlatformV2(
            uid = "private-refusal-${UUID.randomUUID()}",
            name = "Fictional refusal fixture",
            compatibleType = route.type,
            apiUrl = "https://private-refusal-${UUID.randomUUID()}.fixture.invalid/v1",
            token = "fictional-fixture-key",
            model = "fixture-model",
            maxTokens = 64
        )
        private val constraints = RequestConstraints(contextCeiling = 8_192, maxOutputTokens = 64, allowTools = false)
        private val client = HttpClient(
            MockEngine { request ->
                httpRequests.incrementAndGet()
                assertFalse((request.body as TextContent).text.contains(PRIVATE_VALUE))
                if (!completeNormally) throw IllegalStateException("Generic fixture transport failure")
                respond(
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Safe fixture answer.\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }
        ) { install(HttpTimeout) }
        private val authority = RequestAuthorizationGuard("fixture-authority") {
            beforeValidation()
            check(!revoked) { "revoked-fixture" }
        }
        private val context = ConversationPrivacyContext(91, true) + authority +
            PhysicalAttemptBoundary { physicalAttempts.incrementAndGet() } +
            PhysicalAttemptObserver { observedAttempts.incrementAndGet() } +
            (if (retainDispatch) dispatch else EmptyCoroutineContext)

        init {
            every { store.effectiveSnapshot(true) } answers { state }
            coEvery { store.privateTerms() } returns listOf(PRIVATE_VALUE)
            every { wrapper() } returns client
            every { wrapper.gatewayReadClient() } returns client
            every { wrapper.maskedInferenceClient() } returns client
        }

        suspend fun collect(action: suspend (ProviderEvent) -> Unit = {}): List<ProviderEvent> = withContext(context) {
            val configuredDelivered = CompletableDeferred<Unit>()
            val turns = listOf(ConversationTurn(MessageV2(content = "Please draft a reply about $PRIVATE_VALUE.", platformType = null), null, true))
            val encoder = ProviderAttachmentEncoder(ContextWrapper(null))
            val openAi = OpenAIAPIImpl(wrapper)
            val groq = GroqAPIImpl(wrapper)
            val session = maskedProviderSession(store, profile, turns) { maskedTurns, maskedProfile ->
                assertFalse(maskedTurns.toString().contains(PRIVATE_VALUE))
                // Deliberately reintroduce a fictional known value after masking. Everything
                // after this seam is the actual adapter, serializer, preparation and client.
                val transformed = if (reintroduce) {
                    maskedTurns.map { it.copy(userMessage = it.userMessage.copy(content = it.userMessage.content + "\nFixture reintroduction: $PRIVATE_VALUE")) }
                } else {
                    maskedTurns
                }
                val actual = when (route) {
                    Route.OPENAI_CHAT, Route.GROQ -> OpenAICompatibleAdapter(openAi, groq, encoder).openSession(transformed, maskedProfile, constraints)
                    Route.OPENAI_RESPONSES -> OpenAIResponsesAdapter(openAi, encoder).openSession(transformed, maskedProfile, constraints)
                    Route.ANTHROPIC -> AnthropicMessagesAdapter(AnthropicAPIImpl(wrapper), encoder).openSession(transformed, maskedProfile, constraints)
                    Route.GEMINI -> GeminiAdapter(GoogleAPIImpl(wrapper), encoder).openSession(transformed, maskedProfile, constraints)
                }
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) =
                        actual.streamRound(tools, exchanges).onStart {
                            streamStarts.incrementAndGet()
                            proof = requireNotNull(currentCoroutineContext()[PrivateDispatchContext])
                            if (prepareEarlierWire) {
                                ModelRequestPreparation(wrapper).prepareQueued(
                                    """{"model":"fixture-model","messages":[{"role":"user","content":"Prior sanitized fixture request."}],"max_tokens":64}""",
                                    ProviderRequestConfig(profile.apiUrl, null, providerType = "OPENAI", contextCeiling = 8_192),
                                    profile.model,
                                    64
                                )
                            }
                        }.transform { event ->
                            emit(event)
                            // Keep the later receiving-validation suspension bound to the refusal,
                            // not an earlier configuration event still buffered before preparation.
                            if (event is ProviderEvent.RequestConfigured) configuredDelivered.await()
                        }.onCompletion { streamCloses.incrementAndGet() }
                }
            }
            val events = mutableListOf<ProviderEvent>()
            session.streamRound(emptyList(), emptyList()).collect {
                events += it
                if (it is ProviderEvent.RequestConfigured) configuredDelivered.complete(Unit)
                action(it)
            }
            events
        }

        fun close() = client.close()
    }

    private class RefusalCancellation(route: String, receiving: Boolean) : CancellationException("refusal-fixture-$route-$receiving")

    private enum class Route(val type: ClientType) {
        OPENAI_CHAT(ClientType.OPENAI),
        OPENAI_RESPONSES(ClientType.OPENAI),
        GROQ(ClientType.GROQ),
        ANTHROPIC(ClientType.ANTHROPIC),
        GEMINI(ClientType.GOOGLE)
    }

    companion object {
        private const val PRIVATE_VALUE = "fictional-orchid-vault-6842"
        private val OPENAI_ROUTES = listOf(Route.OPENAI_CHAT, Route.OPENAI_RESPONSES)
    }
}
