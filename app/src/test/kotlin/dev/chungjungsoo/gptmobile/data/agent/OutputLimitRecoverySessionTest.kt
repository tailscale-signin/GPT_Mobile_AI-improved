package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputLimitRecoverySessionTest {
    @Test fun transientRecoveryDoesNotReplayAnInitialToolBearingRequest() = runTest {
        val wrapper = OutputLimitRecoverySession(session { emit(ProviderEvent.Failed("Service overloaded")) }, recoverTransientFailures = true) { _, _ -> error("No tool request replay") }
        val events = wrapper.streamRound(listOf(AgentToolDefinition("write", "", buildJsonObject {})), emptyList()).toList()
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
    }

    @Test fun interruptedEditorialAnswerKeepsPartialOutputAndRecoversWithoutTools() = runTest {
        var attempts = 0
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta("Saved paragraph. "))
                emit(ProviderEvent.Failed("Read error: SSL protocol error"))
            },
            recoverTransientFailures = true
        ) { draft, _ ->
            attempts++
            assertEquals("Saved paragraph. ", draft)
            session {
                emit(ProviderEvent.TextDelta("Remaining evidence."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, attempts)
        assertEquals("Saved paragraph. Remaining evidence.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it is ProviderEvent.Failed || it is ProviderEvent.ToolCall })
    }

    @Test fun editorialTransientRetriesAreBoundedAndNeverRetryQuota() = runTest {
        var attempts = 0
        val wrapper = OutputLimitRecoverySession(session { emit(ProviderEvent.Failed("Service temporarily overloaded")) }, recoverTransientFailures = true) { _, _ ->
            attempts++
            session { emit(ProviderEvent.Failed("Service temporarily overloaded")) }
        }
        assertEquals(1, wrapper.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().size)
        assertEquals(2, attempts)
        val quota = OutputLimitRecoverySession(session { emit(ProviderEvent.Failed("LLM7 has reached its free allowance")) }, recoverTransientFailures = true) { _, _ -> error("No quota retry") }
        assertEquals(1, quota.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().size)
    }

    @Test fun truncatedAnswerContinuesOnceWithEvidenceAndWithoutRepeatingTools() = runTest {
        var continuations = 0
        val call = ProviderEvent.ToolCall("done", "write", buildJsonObject {})
        val evidence = AgentToolExchange(listOf(call), listOf(AgentToolResult("done", ToolResultContent.Text("Saved change"), false)))
        val initial = session {
            emit(ProviderEvent.TextDelta("First part. "))
            emit(ProviderEvent.ToolCall("incomplete", "write", buildJsonObject {}))
            emit(ProviderEvent.Failed("The response reached its output limit."))
            emit(ProviderEvent.Usage(100, 200, 300))
        }
        val recovered = OutputLimitRecoverySession(initial) { draft, exchanges ->
            continuations++
            assertEquals("First part. ", draft)
            assertEquals("Saved change", (exchanges.single().results.single().content as ToolResultContent.Text).text)
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                    assertTrue(tools.isEmpty())
                    assertTrue(exchanges.isEmpty())
                    emit(ProviderEvent.TextDelta("Remaining part."))
                    emit(ProviderEvent.Usage(20, 10, 30))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val events = recovered.streamRound(listOf(AgentToolDefinition("write", "", buildJsonObject {})), listOf(evidence)).toList()
        assertEquals(1, continuations)
        assertEquals("First part. Remaining part.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it is ProviderEvent.ToolCall || it is ProviderEvent.Failed })
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertEquals(210, events.filterIsInstance<ProviderEvent.Usage>().sumOf { it.outputTokens ?: 0 })
        assertTrue(events.filterIsInstance<ProviderEvent.Usage>().none { it.cumulative })
    }

    @Test fun secondOutputLimitAndNonLimitFailuresRemainFailures() = runTest {
        var attempts = 0
        val recovery = OutputLimitRecoverySession(session { emit(ProviderEvent.Failed("The response reached its output limit.")) }) { _, _ ->
            attempts++
            session { emit(ProviderEvent.Failed("The response reached its output limit.")) }
        }
        assertEquals(1, recovery.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().size)
        assertEquals(1, attempts)
        val failure = OutputLimitRecoverySession(session { emit(ProviderEvent.Failed("Authentication failed")) }) { _, _ -> error("No retry") }
        assertEquals("Authentication failed", failure.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single().message)
    }

    @Test fun internallyManagedToolsAreNeverAutomaticallyReplayed() = runTest {
        val initial = object : AgentProviderSession {
            override val handlesToolsInternally = true
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.Failed("The response reached its output limit."))
            }
        }
        val wrapper = OutputLimitRecoverySession(initial) { _, _ -> error("Native tool session must not be retried") }
        assertTrue(wrapper.streamRound(emptyList(), emptyList()).toList().single() is ProviderEvent.Failed)
    }

    @Test fun cancellationIsNotConvertedIntoRecoveryOrFailure() = runTest {
        val wrapper = OutputLimitRecoverySession(session { throw CancellationException("Canceled") }) { _, _ -> error("No retry") }
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull() is CancellationException)
    }

    @Test fun multipleCumulativeUsageSnapshotsAreNotDoubleCounted() = runTest {
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.Usage(10, 20, 30))
                emit(ProviderEvent.Usage(10, 40, 50))
                emit(ProviderEvent.Completed)
            }
        ) { _, _ -> error("No retry") }
        val usage = wrapper.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(40, usage.outputTokens)
        assertFalse(usage.cumulative)
    }

    @Test fun threeThousandWordsCanSpanManySmallRequestsWithoutRestartingHistory() = runTest {
        fun part(number: Int) = "Era$number " + "detail$number ".repeat(299)
        var attempts = 0
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta(part(0)))
                emit(ProviderEvent.Failed("The response reached its output limit."))
            },
            maxContinuations = LongResponsePolicy.continuationLimit(3000, 512),
            targetWords = 3000
        ) { draft, _ ->
            attempts++
            assertTrue(draft.startsWith("Era0 "))
            assertEquals(attempts * 300, LongResponsePolicy.countWords(draft))
            session {
                emit(ProviderEvent.TextDelta(part(attempts)))
                if (attempts < 9) emit(ProviderEvent.Failed("finish_reason=length")) else emit(ProviderEvent.Completed)
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        assertEquals(9, attempts)
        assertEquals(3000, LongResponsePolicy.countWords(text))
        assertTrue(text.indexOf("Era0 ") < text.indexOf("Era9 "))
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun earlyStopBelowWordGoalContinuesButRepeatedOutputStopsWithoutDuplicatingIt() = runTest {
        val text = "A useful but incomplete historical overview."
        var attempts = 0
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta(text))
                emit(ProviderEvent.Completed)
            },
            maxContinuations = 8,
            targetWords = 3000
        ) { _, _ ->
            attempts++
            session {
                emit(ProviderEvent.TextDelta(text))
                emit(ProviderEvent.Completed)
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, attempts)
        assertEquals(text, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun noFurtherContinuationAfterCancellationOrUnrelatedFailure() = runTest {
        var attempts = 0
        val wrapper = OutputLimitRecoverySession(session { emit(ProviderEvent.Failed("finish_reason=length")) }, maxContinuations = 8) { _, _ ->
            attempts++
            session { throw CancellationException("User stopped") }
        }
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull() is CancellationException)
        assertEquals(1, attempts)
    }

    @Test fun literalOverlapIsRemovedWhileDistinctDatesArePreserved() {
        val overlap = "The Revolution began in 1789. "
        assertEquals("Napoleon took power in 1799.", continuationSuffix("Earlier eras. $overlap", overlap + "Napoleon took power in 1799."))
        assertEquals("A conflicting date is 1788.", continuationSuffix(overlap, "A conflicting date is 1788."))
    }

    private fun session(events: suspend kotlinx.coroutines.flow.FlowCollector<ProviderEvent>.() -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow(events)
    }
}
