package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedAppendRecoverySessionTest {
    private fun passage(sequence: Int, text: String = "Distinct paragraph $sequence.") = """{"type":"passage","seq":$sequence,"id":"P$sequence","text":"$text","claims":[],"contributors":[],"evidence":[]}"""
    private fun end(sequence: Int) = """{"type":"end","seq":$sequence,"id":"END","lastSeq":${sequence - 1}}"""
    private fun protocol(state: CombinedRecordCheckpoint = CombinedRecordCheckpoint(snapshotDigest = "snapshot", revisionId = "revision")) = CombinedRecordProtocol(state, emptySet(), emptySet(), emptySet(), 10_000)
    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            assertTrue(tools.isEmpty() && exchanges.isEmpty())
            events.forEach { emit(it) }
        }
    }
    private val limit = ProviderLimitDecision("GROQ", "p", "model", 1000L, "shared-quota", "PROVIDER_RATE_LIMITED", true)

    @Test fun appendCommitsTheAttemptBoundaryBeforeDispatchAndNeverRewritesThePrefix() = runTest {
        var saved: CombinedRecordCheckpoint? = null
        var physical = 0
        fun provider(text: String, finished: Boolean) = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                physical++
                assertEquals("IN_FLIGHT", saved?.lastAttemptOutcome)
                assertFalse(saved!!.appendReady)
                assertTrue(tools.isEmpty() && exchanges.isEmpty())
                emit(ProviderEvent.TextDelta(text))
                emit(ProviderEvent.Usage(finishReason = if (finished) "stop" else "length"))
                emit(ProviderEvent.Completed)
            }
        }
        val first = passage(1)
        val wrapper = CombinedRecordRecoverySession(provider(first, false), protocol(), Int.MAX_VALUE, acceptedAppend = true, validateAccess = {}, save = { saved = it }) { cursor, context ->
            assertEquals("OUTPUT_LIMIT", cursor.lastAttemptOutcome)
            assertTrue(cursor.appendReady)
            assertEquals(listOf("P1"), cursor.records.map { it.id })
            assertTrue(context.contains("\"next_sequence\":2"))
            assertFalse(context.startsWith("WRITER_RECOVERY_DIRECT:"))
            provider(first + passage(2) + end(3), true)
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, physical)
        assertEquals(3, events.filterIsInstance<ProviderEvent.TextDelta>().size)
        assertEquals(ProviderEvent.Completed, events.last())
        assertEquals(listOf("P1", "P2", "END"), saved!!.records.map { it.id })
        assertEquals("COMPLETED", saved!!.lastAttemptOutcome)
    }

    @Test fun crashOrUncertainTransportNeverGrantsPermissionToRepeat() = runTest {
        for (outcome in listOf("IN_FLIGHT", "UNKNOWN")) {
            val state = protocol().also { it.accept(passage(1)) }.checkpoint().copy(lastAttemptOutcome = outcome)
            val forbidden = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> { error("Original request must not be repeated") }
            }
            val wrapper = CombinedRecordRecoverySession(forbidden, protocol(state), Int.MAX_VALUE, restored = true, acceptedAppend = true, validateAccess = {}, save = {}) { _, _ -> error("Unknown completion must not append") }
            val events = wrapper.streamRound(emptyList(), emptyList()).toList()
            assertEquals(1, events.filterIsInstance<ProviderEvent.TextDelta>().size)
            assertEquals("COMBINED_APPEND_REMOTE_COMPLETION_UNKNOWN", (events.last() as ProviderEvent.Failed).errorCode)
        }
    }

    @Test fun cancellationPersistsUnknownCompletionAndUnfinishedBytes() = runTest {
        var saved: CombinedRecordCheckpoint? = null
        val partial = passage(2).take(55)
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta(passage(1) + partial))
                throw CancellationException("Stopped")
            }
        }
        val wrapper = CombinedRecordRecoverySession(provider, protocol(), Int.MAX_VALUE, acceptedAppend = true, validateAccess = {}, save = { saved = it }) { _, _ -> error("Cancellation cannot append") }
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull() is CancellationException)
        assertEquals("UNKNOWN", saved!!.lastAttemptOutcome)
        assertFalse(saved!!.appendReady)
        assertEquals(partial, saved!!.pending)
        assertEquals(listOf("P1"), saved!!.records.map { it.id })
    }

    @Test fun knownQuotaRejectionPersistsAnExactCursorAndWaitsWithoutAnotherRequest() = runTest {
        var saved: CombinedRecordCheckpoint? = null
        val failure = ProviderEvent.Failed("Try after reset", providerLimit = limit)
        val wrapper = CombinedRecordRecoverySession(session(failure), protocol(), Int.MAX_VALUE, acceptedAppend = true, validateAccess = {}, save = { saved = it }) { _, _ -> error("The durable scheduler owns the next attempt") }
        assertEquals(failure, wrapper.streamRound(emptyList(), emptyList()).toList().last())
        assertEquals("KNOWN_PROVIDER_REJECTION", saved!!.lastAttemptOutcome)
        assertTrue(saved!!.appendReady)
        assertEquals(limit.retryAt, saved!!.retryAtMillis)
        assertEquals(0, saved!!.noProgressAttempts)
        val restored = CombinedRecordRecoverySession(session(ProviderEvent.Failed("Must not send original")), protocol(saved!!), Int.MAX_VALUE, restored = true, acceptedAppend = true, validateAccess = {}, save = { saved = it }) { state, cursor ->
            assertTrue(state.records.isEmpty())
            assertTrue(cursor.contains("\"next_sequence\":1"))
            session(ProviderEvent.TextDelta(passage(1) + end(2)), ProviderEvent.Completed)
        }
        assertEquals(ProviderEvent.Completed, restored.streamRound(emptyList(), emptyList()).toList().last())
        assertEquals(listOf("P1", "END"), saved!!.records.map { it.id })
    }

    @Test fun incompleteResponseCannotBeRelabeledAsAKnownQuotaRejection() = runTest {
        var saved: CombinedRecordCheckpoint? = null
        val provider = session(ProviderEvent.TextDelta(passage(1)), ProviderEvent.Failed("Limited", providerLimit = limit))
        val wrapper = CombinedRecordRecoverySession(provider, protocol(), Int.MAX_VALUE, acceptedAppend = true, validateAccess = {}, save = { saved = it }) { _, _ -> error("Partial remote outcome must not be retried") }
        val failure = wrapper.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
        assertFalse(failure.providerLimit!!.knownNotAccepted)
        assertEquals("UNKNOWN", saved!!.lastAttemptOutcome)
        assertFalse(saved!!.appendReady)
    }

    @Test fun futureQuotaDeadlineAndRevokedAuthorityStopBeforeDispatch() = runTest {
        val state = CombinedRecordCheckpoint(snapshotDigest = "snapshot", revisionId = "revision", lastAttemptOutcome = "KNOWN_PROVIDER_REJECTION", appendReady = true, retryAtMillis = Long.MAX_VALUE)
        val waiting = CombinedRecordRecoverySession(session(), protocol(state), Int.MAX_VALUE, restored = true, acceptedAppend = true, validateAccess = {}, save = {}) { _, _ -> error("Do not send before reset") }
        assertEquals("COMBINED_APPEND_PROVIDER_WAIT", (waiting.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed).errorCode)
        var checks = 0
        val revoked = CombinedRecordRecoverySession(session(ProviderEvent.TextDelta(passage(1)), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed), protocol(), Int.MAX_VALUE, acceptedAppend = true, validateAccess = { if (++checks > 1) error("Permission revoked") }, save = {}) { _, _ -> error("Do not append after revocation") }
        assertEquals("Permission revoked", runCatching { revoked.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull()?.message)
    }

    @Test fun duplicateTextWithNewIdsDoesNotResetTheNoProgressBound() = runTest {
        var saved: CombinedRecordCheckpoint? = null
        var continuations = 0
        fun repeated(sequence: Int) = session(ProviderEvent.TextDelta(passage(sequence, "The same paragraph again.")), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed)
        val wrapper = CombinedRecordRecoverySession(repeated(1), protocol(), Int.MAX_VALUE, acceptedAppend = true, validateAccess = {}, save = { saved = it }) { cursor, _ ->
            continuations++
            repeated(cursor.lastSequence + 1)
        }
        assertTrue(wrapper.streamRound(emptyList(), emptyList()).toList().last() is ProviderEvent.Failed)
        assertEquals(2, continuations)
        assertEquals(2, saved!!.noProgressAttempts)
        val restored = CombinedRecordRecoverySession(session(), protocol(saved!!), Int.MAX_VALUE, restored = true, acceptedAppend = true, validateAccess = {}, save = {}) { _, _ -> error("Restart cannot reset no-progress bound") }
        assertTrue(restored.streamRound(emptyList(), emptyList()).toList().last() is ProviderEvent.Failed)
    }

    @Test fun malformedRecordCanAppendOnlyAfterTheProviderCompletesAndRawBytesAreSaved() = runTest {
        var saved: CombinedRecordCheckpoint? = null
        var finished = false
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta(passage(1) + "malformed record"))
                emit(ProviderEvent.TextDelta(" remaining source bytes"))
                finished = true
                emit(ProviderEvent.Completed)
            }
        }
        val wrapper = CombinedRecordRecoverySession(provider, protocol(), Int.MAX_VALUE, acceptedAppend = true, validateAccess = {}, save = { saved = it }) { _, _ ->
            assertTrue(finished)
            assertTrue(saved!!.recoveredFragments.single().raw.contains("malformed record remaining source bytes"))
            session(ProviderEvent.TextDelta(passage(2) + end(3)), ProviderEvent.Completed)
        }
        val published = mutableListOf<ProviderEvent>()
        wrapper.streamRound(emptyList(), emptyList()).collect { published += it }
        assertEquals(ProviderEvent.Completed, published.last())
        assertEquals(3, published.filterIsInstance<ProviderEvent.TextDelta>().size)
    }

    @Test fun quotaWaitDoesNotPublishSourceFallbackOrSettleTheFinalDelivery() = runTest {
        val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(continueLongAnswers = true), listOf("p"), mapOf("p" to "model"), "Explain history", singlePass = true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", CombinedRunRole.SYNTHESIS, "p")
        val failure = ProviderEvent.Failed("Provider quota reset pending", providerLimit = limit)
        val delivery = CombinedDeliverySession(
            session(ProviderEvent.TextDelta(passage(1)), failure),
            listOf(dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse("p", "Primary", content = "Every original source paragraph remains available.")),
            10000, structured = true, recordProtocol = true, preserveSourceContent = true, combined = context, validateAccess = {}, originalRequest = "Explain history"
        ) { _, _ -> error("A waiting answer cannot start editorial repair") }
        val events = delivery.streamRound(emptyList(), emptyList()).toList()
        assertEquals(failure, events.last())
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isEmpty())
        assertTrue(events.filterIsInstance<ProviderEvent.CombinedQuality>().isEmpty())
        assertFalse(events.contains(ProviderEvent.Completed))
    }
}
