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
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Receiving authority through the actual private wrappers and synthetic provider events. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateReceiverGuardContractTest {
    @Test
    fun pendingReasoningIsSuppressedWhenInheritedAuthorityIsRevoked() = runTest {
        assertPendingRevocation(PendingKind.REASONING, changePrivacy = false)
    }

    @Test
    fun pendingReasoningIsSuppressedWhenPrivacyRevisionChanges() = runTest {
        assertPendingRevocation(PendingKind.REASONING, changePrivacy = true)
    }

    @Test
    fun pendingCompletionIsSuppressedWhenInheritedAuthorityIsRevoked() = runTest {
        assertPendingRevocation(PendingKind.COMPLETION, changePrivacy = false)
    }

    @Test
    fun pendingCompletionIsSuppressedWhenPrivacyRevisionChanges() = runTest {
        assertPendingRevocation(PendingKind.COMPLETION, changePrivacy = true)
    }

    @Test
    fun privacyIsRecheckedAfterTheReceivingAuthorizationSuspends() = runTest {
        // Without PrivateTurn.authority supplying its own post-suspension check,
        // this case exercises the receiving boundary's captured policy directly.
        val fixture = Fixture(PendingKind.TEXT, admitTurn = false)
        withPending(fixture) { pending ->
            val gate = ValidationGate()
            fixture.validationGate = gate
            pending.resume.complete(Unit)
            runCurrent()
            assertTrue("The receiving validation must suspend before delivery", gate.entered.isCompleted)
            assertEquals(listOf(ProviderEvent.TextDelta(SAFE_PREFIX)), pending.events)
            assertFalse(pending.job.isCompleted)

            fixture.changePrivacy()
            gate.release.complete(Unit)
            fixture.allowProviderCompletion.complete(Unit)
            runCurrent()
            assertPolicyStopped(fixture, pending)
        }
    }

    @Test
    fun cancellationDuringReceivingValidationDoesNotPublishFailureOrCompletion() = runTest {
        val fixture = Fixture(PendingKind.TEXT)
        val outside = ConversationPrivacyContext.currentOrNull()
        withPending(fixture) { pending ->
            val gate = ValidationGate()
            fixture.validationGate = gate
            pending.resume.complete(Unit)
            runCurrent()
            assertTrue("The receiving validation must be suspended", gate.entered.isCompleted)
            assertEquals(listOf(ProviderEvent.TextDelta(SAFE_PREFIX)), pending.events)

            pending.job.cancelAndJoin()
            assertTrue(pending.job.isCancelled)
            assertFalse(gate.release.isCompleted)
            assertEquals(listOf(ProviderEvent.TextDelta(SAFE_PREFIX)), pending.events)
            assertTrue(fixture.upstreamStopped)
            assertEquals(1, fixture.opens)
            assertEquals(1, fixture.dispatches)
            assertSame(outside, ConversationPrivacyContext.currentOrNull())
        }
    }

    @Test
    fun anUpstreamPolicyFailureCannotBypassTheReceivingGuardWithPrivateContent() = runTest {
        val fixture = Fixture(PendingKind.FORGED_FAILURE)
        withPending(fixture) { pending ->
            fixture.revoked = true
            pending.resume.complete(Unit)
            runCurrent()
            assertPolicyStopped(fixture, pending)
            assertTrue(pending.events.none { it === fixture.forgedFailure })
            assertFalse(pending.events.toString().contains("synthetic-upstream-origin"))
        }
    }

    @Test
    fun unchangedAdmittedTurnDeliversEachEventOnceAndRestoresTheDistinctCallerContext() = runTest {
        val fixture = Fixture(PendingKind.REASONING)
        val outside = ConversationPrivacyContext.currentOrNull()
        withPending(fixture) { pending ->
            assertTrue(requireNotNull(fixture.admittedTurn).policy.enabled)
            assertSame(fixture.capturedChat, requireNotNull(fixture.admittedTurn).conversation)
            pending.resume.complete(Unit)
            fixture.allowProviderCompletion.complete(Unit)
            runCurrent()

            assertTrue(pending.job.isCompleted)
            assertFalse(pending.job.isCancelled)
            assertEquals(
                listOf(ProviderEvent.TextDelta(SAFE_PREFIX), ProviderEvent.ThinkingDelta(PRIVATE_VALUE), ProviderEvent.Completed),
                pending.events
            )
            assertEquals(1, fixture.opens)
            assertEquals(1, fixture.dispatches)
            assertTrue(fixture.upstreamStopped)
            assertTrue(fixture.innerStopped)
            assertEquals(JsonPrimitive(true), requireNotNull(fixture.proof?.streamSnapshot())["completed"])
            assertSame(outside, ConversationPrivacyContext.currentOrNull())
        }
    }

    @Test
    fun downstreamCollectorExceptionPropagatesUnchangedAndStopsTheProvider() = runTest {
        val fixture = Fixture(PendingKind.TEXT)
        val outside = ConversationPrivacyContext.currentOrNull()
        val expected = CollectorFailure(Any())
        val events = mutableListOf<ProviderEvent>()
        var observed: Throwable? = null
        val job = launch {
            try {
                fixture.collect { event ->
                    events += event
                    throw expected
                }
            } catch (failure: Throwable) {
                observed = failure
            }
        }
        try {
            runCurrent()
            assertTrue(job.isCompleted)
            assertSame(expected, observed)
            assertEquals(listOf(ProviderEvent.TextDelta(SAFE_PREFIX)), events)
            assertTrue(fixture.upstreamStopped)
            assertEquals(1, fixture.opens)
            assertEquals(1, fixture.dispatches)
            assertSame(outside, ConversationPrivacyContext.currentOrNull())
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun TestScope.assertPendingRevocation(kind: PendingKind, changePrivacy: Boolean) {
        val fixture = Fixture(kind)
        withPending(fixture) { pending ->
            if (changePrivacy) fixture.changePrivacy() else fixture.revoked = true
            pending.resume.complete(Unit)
            fixture.allowProviderCompletion.complete(Unit)
            runCurrent()
            assertPolicyStopped(fixture, pending)
        }
    }

    private suspend fun TestScope.withPending(fixture: Fixture, block: suspend TestScope.(PendingCollection) -> Unit) {
        val events = mutableListOf<ProviderEvent>()
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val job = launch {
            fixture.collect { event ->
                events += event
                if (events.size == 1) {
                    paused.complete(Unit)
                    resume.await()
                }
            }
        }
        try {
            runCurrent()
            assertTrue("The first collector callback must be held", paused.isCompleted)
            assertEquals(listOf(ProviderEvent.TextDelta(SAFE_PREFIX)), events)
            assertTrue("The second event must enter the actual outer wrapper", fixture.forwardingStarted.isCompleted)
            if (fixture.kind == PendingKind.COMPLETION) {
                // The outer wrapper consumes the inner Completed before sending
                // its own terminal event. Require the entire inner stream to have
                // unwound while the collector is still held, not a pending inner send.
                assertTrue(fixture.forwardingReturned.isCompleted)
                assertTrue(fixture.innerStopped)
                assertTrue(fixture.upstreamStopped)
                assertEquals(JsonPrimitive(true), requireNotNull(fixture.proof?.streamSnapshot())["completed"])
            } else {
                assertFalse("Forwarding must remain suspended at the delivery boundary", fixture.forwardingReturned.isCompleted)
            }
            assertFalse(job.isCompleted)
            block(PendingCollection(events, resume, job))
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun assertPolicyStopped(fixture: Fixture, pending: PendingCollection) {
        assertTrue("Receiving denial must finish collection", pending.job.isCompleted)
        assertFalse(pending.job.isCancelled)
        assertEquals(listOf(ProviderEvent.TextDelta(SAFE_PREFIX)), pending.events.filterIsInstance<ProviderEvent.TextDelta>())
        assertTrue(pending.events.none { it is ProviderEvent.ThinkingDelta || it is ProviderEvent.ToolCall || it is ProviderEvent.ToolResult || it == ProviderEvent.Completed })
        val failure = pending.events.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals(2, pending.events.size)
        assertEquals("DISPATCH_POLICY_CHANGED", failure.errorCode)
        assertEquals("PARTIAL", failure.taskOutcome)
        assertEquals(JsonPrimitive("generation_permission"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("logical_continuation_allowed"))
        assertFalse(failure.toString().contains(PRIVATE_VALUE) || failure.toString().contains("synthetic-revoked-owner"))
        assertEquals(1, fixture.opens)
        assertEquals(1, fixture.dispatches)
        assertTrue(fixture.upstreamStopped)
        // Do not relabel a completed provider stream as incomplete merely because
        // its terminal event was rejected by the receiving boundary.
        if (fixture.kind == PendingKind.COMPLETION) {
            assertEquals(JsonPrimitive(true), requireNotNull(fixture.proof?.streamSnapshot())["completed"])
        }
    }

    private data class PendingCollection(val events: List<ProviderEvent>, val resume: CompletableDeferred<Unit>, val job: Job)
    private class ValidationGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }
    private class CollectorFailure(val identity: Any) : RuntimeException("synthetic-downstream-failure")
    private enum class PendingKind { TEXT, REASONING, COMPLETION, FORGED_FAILURE }

    private class Fixture(val kind: PendingKind, private val admitTurn: Boolean = true) {
        private val store = mockk<PrivacyModeStore>()
        val capturedChat = ConversationPrivacyContext(127, true)
        private val callerChat = ConversationPrivacyContext(128, false)
        var state = PrivacyModeState(enabled = true, categories = setOf(PrivacyCategory.CUSTOM), revision = 7)
        var revoked = false
        var validationGate: ValidationGate? = null
        var admittedTurn: PrivateTurn? = null
        var proof: PrivateDispatchContext? = null
        var opens = 0
        var dispatches = 0
        var upstreamStopped = false
        var innerStopped = false
        val forwardingStarted = CompletableDeferred<Unit>()
        val forwardingReturned = CompletableDeferred<Unit>()
        val allowProviderCompletion = CompletableDeferred<Unit>()
        val forgedFailure = ProviderEvent.Failed(
            PRIVATE_VALUE,
            errorCode = "DISPATCH_POLICY_CHANGED",
            taskOutcome = "PARTIAL",
            failureDetails = buildJsonObject {
                put("failed_stage", "generation_permission")
                put("request_replay_allowed", false)
                put("logical_continuation_allowed", false)
                put("claimed_origin", "synthetic-upstream-origin")
                put("private_value", PRIVATE_VALUE)
            }
        )
        private val inherited = RequestAuthorizationGuard("receiver-contract-owner-r7") {
            assertSame(capturedChat, currentCoroutineContext()[ConversationPrivacyContext])
            assertSame(capturedChat, ConversationPrivacyContext.currentOrNull())
            assertSame(admittedTurn, currentCoroutineContext()[PrivateTurn])
            validationGate?.let { gate ->
                gate.entered.complete(Unit)
                gate.release.await()
            }
            check(!revoked) { "synthetic-revoked-owner" }
        }

        init {
            every { store.effectiveSnapshot(any()) } answers { state.copy(enabled = firstArg<Boolean>()) }
            coEvery { store.privateTerms() } returns listOf(PRIVATE_VALUE)
        }

        fun changePrivacy() {
            state = state.copy(revision = state.revision + 1, protectionRevision = state.protectionRevision + 1)
        }

        suspend fun collect(action: suspend (ProviderEvent) -> Unit) = withContext(capturedChat) {
            // Keep the admitted owner alive until collection finishes. Capturing
            // in a completed preparation coroutine would test a different failure.
            val turn = if (admitTurn) PrivateTurn.capture(store) else null
            admittedTurn = turn
            val session = PrivateLocalToolSession(
                store,
                PlatformV2(uid = "receiver-private-profile", name = "Receiver fixture", model = "fixture"),
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
                            assertSame(capturedChat, ConversationPrivacyContext.currentOrNull())
                            assertSame(turn, currentCoroutineContext()[PrivateTurn])
                            requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]).validate()
                            proof = requireNotNull(currentCoroutineContext()[PrivateDispatchContext])
                            dispatches++
                            try {
                                emit(ProviderEvent.TextDelta(SAFE_PREFIX))
                                when (kind) {
                                    PendingKind.TEXT -> emit(ProviderEvent.TextDelta(marker))
                                    PendingKind.REASONING -> emit(ProviderEvent.ThinkingDelta(marker))
                                    PendingKind.COMPLETION -> {
                                        emit(ProviderEvent.Completed)
                                        return@flow
                                    }
                                    PendingKind.FORGED_FAILURE -> {
                                        emit(forgedFailure)
                                        return@flow
                                    }
                                }
                                allowProviderCompletion.await()
                                emit(ProviderEvent.Completed)
                            } finally {
                                upstreamStopped = true
                            }
                        }
                    }
                }
                object : AgentProviderSession by masked {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        try {
                            masked.streamRound(tools, exchanges).collect { event ->
                                val pending = when (kind) {
                                    PendingKind.TEXT -> event is ProviderEvent.TextDelta && event.text == PRIVATE_VALUE
                                    PendingKind.REASONING -> event is ProviderEvent.ThinkingDelta && event.text == PRIVATE_VALUE
                                    PendingKind.COMPLETION -> event == ProviderEvent.Completed
                                    PendingKind.FORGED_FAILURE -> event === forgedFailure
                                }
                                if (pending) forwardingStarted.complete(Unit)
                                emit(event)
                                if (pending) forwardingReturned.complete(Unit)
                            }
                        } finally {
                            innerStopped = true
                        }
                    }
                }
            }
            val authority = turn?.authority(inherited) ?: inherited
            withContext(callerChat + (turn ?: EmptyCoroutineContext) + authority) {
                session.streamRound(emptyList(), emptyList()).collect { event ->
                    assertSame(callerChat, currentCoroutineContext()[ConversationPrivacyContext])
                    assertSame(callerChat, ConversationPrivacyContext.currentOrNull())
                    assertSame(turn, currentCoroutineContext()[PrivateTurn])
                    action(event)
                }
            }
        }
    }

    companion object {
        private const val PRIVATE_VALUE = "fictional-receiver-value"
        private const val SAFE_PREFIX = "safe prefix "
    }
}
