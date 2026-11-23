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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivatePlainConversationSessionTest {
    @Test
    fun noToolConversationStreamsVerifiedTextBeforeCompletionThroughBothPrivateWrappers() = runBlocking {
        val fixture = Fixture()
        val seen = CompletableDeferred<Unit>()
        fixture.response = { token ->
            flow {
                emit(ProviderEvent.TextDelta("Answer: " + token.dropLast(1)))
                withTimeout(2_000) { seen.await() }
                emit(ProviderEvent.TextDelta("]"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = mutableListOf<ProviderEvent>()
        fixture.session().streamRound(emptyList(), emptyList()).collect { event ->
            events += event
            if (event is ProviderEvent.TextDelta) {
                assertFalse(event.text.contains("[PRIVATE_"))
                seen.complete(Unit)
            }
        }
        assertEquals("Answer: $SECRET", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.ToolCall || it is ProviderEvent.ToolResult || it is ProviderEvent.Failed })
    }

    @Test
    fun noToolFastPathStillRejectsNativeControlInsteadOfAuthorizingIt() = runBlocking {
        val fixture = Fixture()
        fixture.response = {
            flow {
                emit(ProviderEvent.ToolCall("untrusted", "remote_service", buildJsonObject {}))
                emit(ProviderEvent.Completed)
            }
        }
        val events = fixture.session().streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.none { it is ProviderEvent.ToolCall || it is ProviderEvent.ToolResult || it is ProviderEvent.Completed })
        assertTrue(events.any { it is ProviderEvent.Failed })
        assertEquals(1, fixture.dispatches)
    }

    @Test
    fun cancellationWhileAMarkerIsIncompleteNeverFlushesItsRawSuffix() = runBlocking {
        val fixture = Fixture()
        val waiting = CompletableDeferred<Unit>()
        fixture.response = { token ->
            flow {
                emit(ProviderEvent.TextDelta(token.dropLast(1)))
                waiting.complete(Unit)
                awaitCancellation()
            }
        }
        val events = mutableListOf<ProviderEvent>()
        val job = launch { fixture.session().streamRound(emptyList(), emptyList()).collect { events += it } }
        withTimeout(2_000) { waiting.await() }
        job.cancelAndJoin()
        assertTrue(events.none { it is ProviderEvent.TextDelta || it is ProviderEvent.Completed })
    }

    @Test
    fun aChangedPolicyDuringGenerationStopsPublicationAndDoesNotRetry() = runBlocking {
        val fixture = Fixture()
        fixture.response = { token ->
            flow {
                fixture.state = fixture.state.copy(revision = fixture.state.revision + 1)
                emit(ProviderEvent.TextDelta(token))
                emit(ProviderEvent.Completed)
            }
        }
        val events = fixture.session().streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.none { it is ProviderEvent.TextDelta || it is ProviderEvent.Completed })
        assertEquals("DISPATCH_POLICY_CHANGED", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(1, fixture.dispatches)
    }

    private class Fixture {
        private val store = mockk<PrivacyModeStore>()
        var state = PrivacyModeState(enabled = true, categories = setOf(PrivacyCategory.CUSTOM), revision = 3)
        var dispatches = 0
        var response: (String) -> Flow<ProviderEvent> = { flow { emit(ProviderEvent.Completed) } }

        init {
            every { store.effectiveSnapshot(true) } answers { state }
            coEvery { store.privateTerms() } returns listOf(SECRET)
        }

        fun session(): AgentProviderSession = ConversationPrivacyContext.withCaptured(ConversationPrivacyContext(27, true)) {
            PrivateLocalToolSession(
                store,
                PlatformV2(uid = "private-profile", name = "Private test", model = "fixture"),
                listOf(ConversationTurn(MessageV2(content = "value=$SECRET", platformType = null), null, true)),
                emptyList()
            ) { turns, profile ->
                maskedProviderSession(store, profile, turns) { wire, _ ->
                    object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                            assertTrue(tools.isEmpty() && exchanges.isEmpty())
                            requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]).validate()
                            requireNotNull(currentCoroutineContext()[PrivateDispatchContext])
                            dispatches++
                            response(wire.first().userMessage.content.substringAfter("value=")).collect { emit(it) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val SECRET = "local-value-31415"
    }
}
