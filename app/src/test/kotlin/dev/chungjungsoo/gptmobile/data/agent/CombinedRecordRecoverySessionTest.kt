package dev.chungjungsoo.gptmobile.data.agent

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

class CombinedRecordRecoverySessionTest {
    private val first = """{"type":"passage","seq":1,"id":"P1","text":"Original paragraph.","claims":[],"contributors":[],"evidence":[]}"""
    private val second = """{"type":"passage","seq":2,"id":"P2","text":"Interrupted section, now complete.","claims":[],"contributors":[],"evidence":[]}"""
    private val end = """{"type":"end","seq":3,"id":"END","lastSeq":2}"""
    private fun protocol(state: CombinedRecordCheckpoint = CombinedRecordCheckpoint(snapshotDigest = "snapshot", revisionId = "revision")) = CombinedRecordProtocol(state, emptySet(), emptySet(), emptySet(), 10_000)
    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            assertTrue(tools.isEmpty() && exchanges.isEmpty())
            events.forEach { emit(it) }
        }
    }

    @Test fun cleanEofWithoutEndRecordIsAProtocolFailureButLengthIsNot() = runTest {
        suspend fun outcome(finishReason: String): ProviderEvent.Failed {
            val recovery = CombinedRecordRecoverySession(
                session(ProviderEvent.TextDelta(first), ProviderEvent.Usage(finishReason = finishReason), ProviderEvent.Completed),
                protocol(),
                0,
                validateAccess = {},
                save = {}
            ) { _, _ -> error("Single-pass writer must not continue automatically") }
            return recovery.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
        }
        assertEquals("OUTPUT_PROTOCOL_INVALID", outcome("stop").errorCode)
        val length = outcome("length")
        assertTrue(length.message.startsWith("COMBINED_PARTIAL:"))
        assertFalse(length.errorCode == "OUTPUT_PROTOCOL_INVALID")
    }

    @Test fun limitedStreamCommitsBeforePublicationAndRegeneratesWholeRecordWithoutOverlap() = runTest {
        var saved: CombinedRecordCheckpoint? = null
        var continuations = 0
        val partial = second.take(second.indexOf("now"))
        val recovery = CombinedRecordRecoverySession(
            session(ProviderEvent.TextDelta(first + partial), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed),
            protocol(),
            2,
            validateAccess = {},
            save = { saved = it }
        ) { state, context ->
            continuations++
            assertEquals(1, state.lastSequence)
            assertTrue(context.contains("next_sequence\":2"))
            assertEquals(partial, state.recoveredFragments.single().raw)
            session(ProviderEvent.TextDelta(first + second + end), ProviderEvent.Completed)
        }
        val published = mutableListOf<String>()
        var completed = false
        recovery.streamRound(emptyList(), emptyList()).collect {
            if (it is ProviderEvent.TextDelta) {
                assertTrue(saved!!.records.any { row -> row.wire + "\n" == it.text })
                published += it.text
            }
            if (it == ProviderEvent.Completed) completed = true
        }
        assertEquals(1, continuations)
        assertEquals(3, published.size)
        assertTrue(completed)
        assertEquals(listOf("P1", "P2", "END"), saved!!.records.map { it.id })
    }

    @Test fun cancellationRetainsTheRawFragmentAndNeverInventsCompletion() = runTest {
        val cut = second.take(70)
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta(first + cut))
                throw CancellationException("cancel")
            }
        }
        var saved: CombinedRecordCheckpoint? = null
        val recovery = CombinedRecordRecoverySession(provider, protocol(), 3, validateAccess = {}, save = { saved = it }) { _, _ -> error("No continuation after cancellation") }
        assertTrue(runCatching { recovery.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull() is CancellationException)
        assertEquals(cut, saved!!.pending)
        assertEquals(1, saved!!.lastSequence)
        assertFalse(saved!!.ended)
    }

    @Test fun anOwnershipRejectionPublishesNoUnsavedRecords() = runTest {
        val recovery = CombinedRecordRecoverySession(session(ProviderEvent.TextDelta(first)), protocol(), 1, validateAccess = {}, save = { error("Owner changed") }) { _, _ -> error("No continuation") }
        val published = mutableListOf<ProviderEvent>()
        assertTrue(runCatching { recovery.streamRound(emptyList(), emptyList()).collect { published += it } }.isFailure)
        assertTrue(published.isEmpty())
    }

    @Test fun restartReplaysCommittedRecordsAndContinuesOnlyTheUncommittedSequence() = runTest {
        val saved = protocol().also { it.accept(first + second.take(60)) }.checkpoint()
        var originalCalls = 0
        val original = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                originalCalls++
                error("Do not restart original generation")
            }
        }
        val recovery = CombinedRecordRecoverySession(original, protocol(saved), 2, restored = true, validateAccess = {}, save = {}) { _, context ->
            assertTrue(context.contains("next_sequence\":2"))
            session(ProviderEvent.TextDelta(second + end), ProviderEvent.Completed)
        }
        val events = recovery.streamRound(emptyList(), emptyList()).toList()
        assertEquals(0, originalCalls)
        assertEquals(3, events.filterIsInstance<ProviderEvent.TextDelta>().size)
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun tinyUnfinishedDeltasAreCoalescedAndTerminalFragmentIsSaved() = runTest {
        var writes = 0
        var saved: CombinedRecordCheckpoint? = null
        val fragment = first.take(60)
        val provider = session(*fragment.map { ProviderEvent.TextDelta(it.toString()) }.toTypedArray(), ProviderEvent.Failed("interrupted"))
        val recovery = CombinedRecordRecoverySession(provider, protocol(), 0, validateAccess = {}, save = {
            writes++
            saved = it
        }) { _, _ -> error("No continuation") }
        recovery.streamRound(emptyList(), emptyList()).toList()
        assertEquals(fragment, saved!!.pending)
        assertTrue(writes < fragment.length / 2)
    }

    @Test fun reasoningOnlyChangesStrategyThenStopsForAuthorizedWriterFallback() = runTest {
        var attempts = 0
        val observed = mutableListOf<Pair<Boolean, Int>>()
        val stalled = session(ProviderEvent.ThinkingDelta("Plan the entire answer again"), ProviderEvent.Usage(outputTokens = 2048, reasoningTokens = 2048, finishReason = "length"), ProviderEvent.Completed)
        val recovery = CombinedRecordRecoverySession(stalled, protocol(), 5, validateAccess = {}, save = {}, observeAttempt = { reasoning, passages -> observed += reasoning to passages }) { _, context ->
            attempts++
            assertTrue(context.startsWith("WRITER_RECOVERY_DIRECT:"))
            session(ProviderEvent.ThinkingDelta("More reasoning"), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed)
        }
        val events = recovery.streamRound(emptyList(), emptyList()).toList()
        val failure = events.last() as ProviderEvent.Failed
        assertTrue(failure.message.startsWith("COMBINED_WRITER_STALLED:"))
        assertTrue(permitsCombinedWriterFallback("FAILED", failure.message))
        assertFalse(permitsCombinedWriterFallback("CANCELED", failure.message))
        assertEquals(1, attempts)
        assertEquals(listOf(true to 0, true to 0), observed)
    }

    @Test fun directWritingRecoveryCanFinishAfterReasoningOnlyAttempt() = runTest {
        val stalled = session(ProviderEvent.ThinkingDelta("No answer yet"), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed)
        val recovery = CombinedRecordRecoverySession(stalled, protocol(), 2, validateAccess = {}, save = {}) { _, context ->
            assertTrue(context.contains("Write the next complete"))
            session(ProviderEvent.TextDelta(first + second + end), ProviderEvent.Completed)
        }
        val events = recovery.streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, events.last())
        assertEquals(3, events.filterIsInstance<ProviderEvent.TextDelta>().size)
    }
}
