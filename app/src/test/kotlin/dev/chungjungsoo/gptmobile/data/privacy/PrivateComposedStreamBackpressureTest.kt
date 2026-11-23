package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateComposedStreamBackpressureTest {
    @Test
    fun revocationWhileTheCollectorIsPausedSuppressesThePendingRestoredEvent() = runTest {
        for (changePrivacy in listOf(false, true)) {
            val fixture = Fixture()
            val caller = ConversationPrivacyContext.currentOrNull()
            val collectorPaused = CompletableDeferred<Unit>()
            val resumeCollector = CompletableDeferred<Unit>()
            val events = mutableListOf<ProviderEvent>()
            val job = launch(fixture.authority) {
                fixture.session().streamRound(emptyList(), emptyList()).collect { event ->
                    assertSame(caller, ConversationPrivacyContext.currentOrNull())
                    events += event
                    if (events.size == 1) {
                        collectorPaused.complete(Unit)
                        resumeCollector.await()
                    }
                }
            }
            try {
                // Run every ready coroutine while the first collector callback is
                // held. The observation flow adds no channel or buffering policy.
                runCurrent()
                assertTrue("The first collector callback must be paused", collectorPaused.isCompleted)
                assertEquals(listOf(ProviderEvent.TextDelta(SAFE_PREFIX)), events)
                assertTrue("The second restored event must reach the outer wrapper", fixture.pendingDeliveryStarted.isCompleted)
                assertFalse("Forwarding must still be suspended behind the paused collector", fixture.pendingDeliveryReturned.isCompleted)
                assertFalse(job.isCompleted)

                if (changePrivacy) {
                    fixture.state = fixture.state.copy(revision = fixture.state.revision + 1)
                } else {
                    fixture.revoked = true
                }
                resumeCollector.complete(Unit)
                fixture.allowProviderCompletion.complete(Unit)
                runCurrent()

                assertTrue("Collection must terminate after revocation", job.isCompleted)
                assertFalse(job.isCancelled)
                assertEquals(1, fixture.opens)
                assertEquals(1, fixture.dispatches)
                assertTrue(fixture.upstreamStopped)
                assertEquals(JsonPrimitive(false), requireNotNull(fixture.proof?.streamSnapshot())["completed"])
                val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
                assertEquals("DISPATCH_POLICY_CHANGED", failure.errorCode)
                assertEquals("PARTIAL", failure.taskOutcome)
                assertEquals(JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
                assertEquals(JsonPrimitive(false), failure.failureDetails?.get("logical_continuation_allowed"))
                assertFalse(failure.toString().contains(PRIVATE_VALUE) || failure.toString().contains("revoked-backpressure-fixture"))
                assertTrue(events.none { it is ProviderEvent.ThinkingDelta || it is ProviderEvent.ToolCall || it is ProviderEvent.ToolResult || it == ProviderEvent.Completed })
                assertEquals(
                    "Only the event received before revocation may reach the collector; changePrivacy=$changePrivacy",
                    SAFE_PREFIX,
                    events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
                )
                assertSame(caller, ConversationPrivacyContext.currentOrNull())
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    private class Fixture {
        private val store = mockk<PrivacyModeStore>()
        var state = PrivacyModeState(enabled = true, categories = setOf(PrivacyCategory.CUSTOM), revision = 7)
        var revoked = false
        var opens = 0
        var dispatches = 0
        var upstreamStopped = false
        var proof: PrivateDispatchContext? = null
        val pendingDeliveryStarted = CompletableDeferred<Unit>()
        val pendingDeliveryReturned = CompletableDeferred<Unit>()
        val allowProviderCompletion = CompletableDeferred<Unit>()
        val authority = RequestAuthorizationGuard("retained-source-backpressure-r7") {
            check(!revoked) { "revoked-backpressure-fixture" }
        }

        init {
            every { store.effectiveSnapshot(true) } answers { state }
            coEvery { store.privateTerms() } returns listOf(PRIVATE_VALUE)
        }

        fun session(): AgentProviderSession = ConversationPrivacyContext.withCaptured(ConversationPrivacyContext(27, true)) {
            PrivateLocalToolSession(
                store,
                PlatformV2(uid = "private-profile", name = "Private fixture", model = "fixture"),
                listOf(ConversationTurn(MessageV2(content = "value=$PRIVATE_VALUE", platformType = null), null, true)),
                emptyList()
            ) { turns, profile ->
                val masked = maskedProviderSession(store, profile, turns) { wire, _ ->
                    opens++
                    val marker = wire.single().userMessage.content.removePrefix("value=")
                    assertFalse(marker.contains(PRIVATE_VALUE))
                    object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                            assertTrue(tools.isEmpty() && exchanges.isEmpty())
                            requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]).validate()
                            proof = requireNotNull(currentCoroutineContext()[PrivateDispatchContext])
                            dispatches++
                            try {
                                emit(ProviderEvent.TextDelta(SAFE_PREFIX))
                                emit(ProviderEvent.TextDelta(marker))
                                allowProviderCompletion.await()
                                emit(ProviderEvent.Completed)
                            } finally {
                                upstreamStopped = true
                            }
                        }
                    }
                }
                // Observe the actual restored event passing between the two
                // production wrappers. Do not substitute their authorization,
                // restoration, completion or rendezvous implementations.
                object : AgentProviderSession by masked {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        masked.streamRound(tools, exchanges).collect { event ->
                            val pending = event is ProviderEvent.TextDelta && event.text == PRIVATE_VALUE
                            if (pending) pendingDeliveryStarted.complete(Unit)
                            emit(event)
                            if (pending) pendingDeliveryReturned.complete(Unit)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val PRIVATE_VALUE = "fictional-copper-garden"
        private const val SAFE_PREFIX = "safe prefix "
    }
}
