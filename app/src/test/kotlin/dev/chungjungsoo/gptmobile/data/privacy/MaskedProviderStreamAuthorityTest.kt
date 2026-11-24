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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskedProviderStreamAuthorityTest {
    @Test
    fun revokingInheritedAuthorityAfterFirstTextStopsLaterAnswerReasoningAndCompletion() = runBlocking {
        for (next in NextOutput.entries) {
            val fixture = Fixture()
            val firstPublished = CompletableDeferred<Unit>()
            fixture.response = { marker ->
                flow {
                    emit(ProviderEvent.TextDelta("safe prefix "))
                    firstPublished.await()
                    when (next) {
                        NextOutput.ANSWER -> emit(ProviderEvent.TextDelta(marker))
                        NextOutput.REASONING -> emit(ProviderEvent.ThinkingDelta(marker))
                        NextOutput.COMPLETION -> Unit
                    }
                    emit(ProviderEvent.Completed)
                }
            }
            val events = mutableListOf<ProviderEvent>()
            withTimeout(5_000) {
                fixture.collect { event ->
                    events += event
                    if (event is ProviderEvent.TextDelta) {
                        fixture.revoked = true
                        firstPublished.complete(Unit)
                    }
                }
            }
            assertStopped(fixture, events)
        }
    }

    @Test
    fun suspendedPublicationValidationRechecksBothInheritedPermissionAndPrivacyRevision() = runBlocking {
        for (changePrivacy in listOf(false, true)) {
            val fixture = Fixture()
            val validating = CompletableDeferred<Unit>()
            val resumeValidation = CompletableDeferred<Unit>()
            var firstPublished = false
            fixture.beforeValidation = {
                if (firstPublished) {
                    validating.complete(Unit)
                    resumeValidation.await()
                }
            }
            val events = mutableListOf<ProviderEvent>()
            val job = launch {
                fixture.collect { event ->
                    events += event
                    if (event is ProviderEvent.TextDelta) firstPublished = true
                }
            }
            try {
                withTimeout(5_000) { validating.await() }
                assertEquals("safe prefix ", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
                if (changePrivacy) {
                    fixture.state = fixture.state.copy(revision = fixture.state.revision + 1)
                } else {
                    fixture.revoked = true
                }
                resumeValidation.complete(Unit)
                withTimeout(5_000) { job.join() }
                assertStopped(fixture, events)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellationDuringPublicationValidationStaysCancellationAndDoesNotPublishAReplyOrFailure() = runBlocking {
        val fixture = Fixture()
        val validating = CompletableDeferred<Unit>()
        var firstPublished = false
        fixture.beforeValidation = {
            if (firstPublished) {
                validating.complete(Unit)
                awaitCancellation()
            }
        }
        val events = mutableListOf<ProviderEvent>()
        val job = launch {
            fixture.collect { event ->
                events += event
                if (event is ProviderEvent.TextDelta) firstPublished = true
            }
        }
        try {
            withTimeout(5_000) { validating.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals("safe prefix ", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
            assertTrue(events.none { it is ProviderEvent.Failed || it is ProviderEvent.ThinkingDelta || it == ProviderEvent.Completed })
            assertEquals(1, fixture.dispatches)
            assertTrue(fixture.upstreamStopped)
            assertEquals(JsonPrimitive(false), requireNotNull(fixture.proof?.streamSnapshot())["completed"])
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun assertStopped(fixture: Fixture, events: List<ProviderEvent>) {
        assertEquals("safe prefix ", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it is ProviderEvent.ThinkingDelta || it == ProviderEvent.Completed })
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("DISPATCH_POLICY_CHANGED", failure.errorCode)
        assertEquals("PARTIAL", failure.taskOutcome)
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("logical_continuation_allowed"))
        assertFalse(failure.toString().contains(PRIVATE_VALUE) || failure.toString().contains("revoked-source-fixture"))
        assertEquals(1, fixture.opens)
        assertEquals(1, fixture.dispatches)
        assertTrue(fixture.upstreamStopped)
        assertEquals(JsonPrimitive(false), requireNotNull(fixture.proof?.streamSnapshot())["completed"])
    }

    private enum class NextOutput { ANSWER, REASONING, COMPLETION }

    private class Fixture {
        private val store = mockk<PrivacyModeStore>()
        var state = PrivacyModeState(true, setOf(PrivacyCategory.CUSTOM), 7)
        var revoked = false
        var beforeValidation: suspend () -> Unit = {}
        var opens = 0
        var dispatches = 0
        var upstreamStopped = false
        var proof: PrivateDispatchContext? = null
        var response: (String) -> Flow<ProviderEvent> = { marker ->
            flow {
                emit(ProviderEvent.TextDelta("safe prefix "))
                emit(ProviderEvent.TextDelta(marker))
                emit(ProviderEvent.Completed)
            }
        }
        private val authority = RequestAuthorizationGuard("retained-source-r7") {
            beforeValidation()
            check(!revoked) { "revoked-source-fixture" }
        }
        private val context = ConversationPrivacyContext(27, true) + authority

        init {
            every { store.effectiveSnapshot(true) } answers { state }
            coEvery { store.privateTerms() } returns listOf(PRIVATE_VALUE)
        }

        suspend fun collect(action: suspend (ProviderEvent) -> Unit) = withContext(context) {
            val profile = PlatformV2(uid = "private-profile", name = "Private fixture", model = "fixture")
            val turns = listOf(ConversationTurn(MessageV2(content = "value=$PRIVATE_VALUE", platformType = null), null, true))
            val session = maskedProviderSession(store, profile, turns) { wire, _ ->
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
                            response(marker).collect { emit(it) }
                        } finally {
                            upstreamStopped = true
                        }
                    }
                }
            }
            session.streamRound(emptyList(), emptyList()).collect(action)
        }
    }

    companion object {
        private const val PRIVATE_VALUE = "fictional-copper-garden"
    }
}
