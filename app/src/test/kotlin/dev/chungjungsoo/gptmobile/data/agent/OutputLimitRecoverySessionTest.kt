package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputLimitRecoverySessionTest {
    @Test fun unknownGatewayOutcomeStopsEvenWhenLengthAndStallRecoveryWouldOtherwiseApply() = runTest {
        for (finishReason in listOf<String?>(null, "length")) {
            var requests = 0
            var continuations = 0
            val saved = mutableListOf<String>()
            val wrapper = OutputLimitRecoverySession(
                session {
                    requests++
                    emit(ProviderEvent.TextDelta("Saved answer fragment."))
                    emit(ProviderEvent.Usage(finishReason = finishReason))
                    emit(ProviderEvent.Failed("Connection reset before completion", progressDeadline = ProgressDeadlineOutcome("GENERATION_STALLED", ObservedProgressStage.ANSWER, 180_000, 90_000, true, 0), errorCode = "GATEWAY_OUTCOME_UNKNOWN"))
                    error("No later stream failure may replace the unknown gateway outcome")
                },
                maxContinuations = 6,
                targetWords = 1000,
                recoverTransientFailures = true,
                saveProgress = { text, _, _, _ -> saved += text }
            ) { _, _ ->
                continuations++
                session { emit(ProviderEvent.Completed) }
            }
            val events = wrapper.streamRound(emptyList(), emptyList()).toList()
            assertEquals(1, requests)
            assertEquals(0, continuations)
            assertEquals("GATEWAY_OUTCOME_UNKNOWN", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
            assertEquals("Saved answer fragment.", saved.last())
            assertFalse(events.any { it is ProviderEvent.Completed || it is ProviderEvent.ToolCall })
        }
    }

    @Test fun restoredDraftStopsAfterFirstEmptyContinuationWithoutSpendingAnotherRequest() = runTest {
        var continued = 0
        val saved = mutableListOf<String>()
        fun limited() = session {
            emit(ProviderEvent.ThinkingDelta("Reasoning without visible progress"))
            emit(ProviderEvent.Failed("finish_reason=length"))
        }
        val wrapper = OutputLimitRecoverySession(
            limited(),
            maxContinuations = 12,
            initialDraft = "Saved visible answer.",
            effectiveOutputCap = 2048,
            saveProgress = { draft, _, _, _ -> saved += draft }
        ) { _, _ ->
            continued++
            limited()
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        // The existing no-progress guard stops the first empty restored continuation.
        assertEquals(1, continued)
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("OUTPUT_CONTINUATION_NO_PROGRESS"))
        assertTrue(saved.all { it == "Saved visible answer." })
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun missingUsageStillStopsTwoLowYieldOutputLimitedRequests() = runTest {
        var continued = 0
        val saved = mutableListOf<String>()
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.ThinkingDelta("Reasoning without visible progress"))
                emit(ProviderEvent.Failed("finish_reason=length"))
            },
            maxContinuations = 12,
            effectiveOutputCap = 2048,
            saveProgress = { draft, _, _, _ -> saved += draft }
        ) { _, _ ->
            continued++
            session {
                emit(ProviderEvent.ThinkingDelta("Still no answer"))
                emit(ProviderEvent.Failed("finish_reason=length"))
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, continued)
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("OUTPUT_CONTINUATION_NO_VISIBLE_PROGRESS"))
        assertTrue(saved.all { it.isEmpty() })
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun emptyWatchdogTimeoutDoesNotStartAnotherIdenticalWait() = runTest {
        val deadline = ProgressDeadlineOutcome("MODEL_PROGRESS_TIMEOUT", ObservedProgressStage.WAITING_FOR_MODEL, 180_000, 180_000, false, 0)
        val failure = ProviderEvent.Failed("MODEL_PROGRESS_TIMEOUT: The model stopped making progress.", progressDeadline = deadline)
        val wrapper = OutputLimitRecoverySession(session { emit(failure) }, recoverTransientFailures = true) { _, _ ->
            error("Nothing was produced to resume")
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(failure, events.filterIsInstance<ProviderEvent.Failed>().single())
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun watchdogStallWithSavedVisibleOutputCanStillResume() = runTest {
        val deadline = ProgressDeadlineOutcome("GENERATION_STALLED", ObservedProgressStage.ANSWER, 180_000, 90_000, true, 0)
        var continuations = 0
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta("Saved answer. "))
                emit(ProviderEvent.Failed("GENERATION_STALLED: The model stopped making progress.", progressDeadline = deadline))
            },
            recoverTransientFailures = true
        ) { draft, _ ->
            continuations++
            assertEquals("Saved answer. ", draft)
            session {
                emit(ProviderEvent.TextDelta("Remaining answer."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, continuations)
        assertEquals("Saved answer. Remaining answer.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun abortedSocketRetainsThePrefixAndUsesTextOnlyRecovery() = runTest {
        var attempts = 0
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta("Saved useful prefix. "))
                emit(ProviderEvent.Failed("Software caused connection abort"))
            },
            recoverTransientFailures = true
        ) { prefix, exchanges ->
            attempts++
            assertTrue(prefix.contains("Saved useful prefix."))
            assertTrue(exchanges.isEmpty())
            session {
                emit(ProviderEvent.TextDelta("The remaining answer."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, attempts)
        assertEquals("Saved useful prefix. The remaining answer.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it is ProviderEvent.Failed || it is ProviderEvent.ToolCall })
    }

    @Test fun restoredAnswerSurvivesContinuationAdmissionFailure() = runTest {
        val saved = mutableListOf<Pair<String, String>>()
        val prefix = "The original useful history remains available."
        val wrapper = OutputLimitRecoverySession(
            session { error("Initial generation must not run again") },
            initialDraft = prefix,
            saveProgress = { text, _, _, reason -> saved += text to reason }
        ) { _, _ -> error("The request exceeds its context allowance") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(prefix, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(listOf(prefix to "PARTIAL"), saved)
        assertEquals("OUTPUT_CONTINUATION_UNAVAILABLE", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun cancellationWhilePreparingRestoredContinuationSavesPrefixAndPropagates() = runTest {
        val saved = mutableListOf<Pair<String, String>>()
        val prefix = "Saved earlier sections."
        val wrapper = OutputLimitRecoverySession(
            session { error("Initial generation must not run") },
            initialDraft = prefix,
            saveProgress = { text, _, _, reason -> saved += text to reason }
        ) { _, _ -> throw CancellationException("Stopped while admitting continuation") }
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf(prefix to "CANCELLED"), saved)
    }

    @Test fun fourOutputLimitedPacketsProduceOne4096TokenFinalAnswer() = runTest {
        fun text(index: Int) = (0 until 256).joinToString(" ", postfix = " ") { "era${index}detail$it" }
        fun packet(index: Int) = session {
            emit(ProviderEvent.RequestConfigured(effectiveOutputTokens = 1024))
            emit(ProviderEvent.TextDelta(text(index)))
            emit(ProviderEvent.Usage(outputTokens = 1024, finishReason = if (index < 3) "length" else "stop"))
            emit(ProviderEvent.Completed)
        }
        var requests = 0
        val wrapper = OutputLimitRecoverySession(
            packet(0),
            maxContinuations = LongResponsePolicy.continuationLimit(1024, 1024),
            targetWords = 1024
        ) { draft, evidence ->
            requests++
            assertEquals((0 until requests).joinToString("") { text(it) }, draft)
            assertTrue(evidence.isEmpty())
            packet(requests)
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(3, requests)
        assertEquals((0..3).joinToString("") { text(it) }, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(4096, events.filterIsInstance<ProviderEvent.Usage>().sumOf { it.outputTokens ?: 0 })
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun smallOutputAllowancesCanFinishAnExplicitGoalBeyondSixteenRequests() = runTest {
        fun packet(index: Int) = session {
            emit(ProviderEvent.TextDelta((0 until 250).joinToString(" ", postfix = " ") { "era${index}detail$it" }))
            emit(ProviderEvent.Usage(outputTokens = 256, finishReason = if (index < 19) "length" else "stop"))
            emit(ProviderEvent.Completed)
        }
        var requests = 0
        val wrapper = OutputLimitRecoverySession(
            packet(0),
            maxContinuations = LongResponsePolicy.continuationLimit(5000, 256),
            targetWords = 5000
        ) { _, _ -> packet(++requests) }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(19, requests)
        assertEquals(5000, LongResponsePolicy.countWords(events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }))
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun shortNaturalStopsAdaptToRealYieldInsteadOfAcceptingTheAdvertisedOutputAllowance() = runTest {
        fun packet(index: Int) = session {
            emit(ProviderEvent.RequestConfigured(effectiveOutputTokens = 8192))
            emit(ProviderEvent.TextDelta((0 until 100).joinToString(" ", postfix = " ") { "section${index}detail$it" }))
            emit(ProviderEvent.Completed)
        }
        var requests = 0
        val wrapper = OutputLimitRecoverySession(
            packet(0),
            maxContinuations = LongResponsePolicy.continuationLimit(1000, 8192),
            targetWords = 1000,
            adaptToObservedOutput = true
        ) { _, _ -> packet(++requests) }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(8, requests)
        assertEquals(900, LongResponsePolicy.countWords(events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }))
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun repeatedTruncatedAnswerNeverBecomesSuccessfulJustBecauseRetryClaimsStop() = runTest {
        val text = "Portugal's history begins with prehistoric settlement and"
        var attempts = 0
        val triggers = mutableListOf<ContinuationTrigger>()
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta(text))
                emit(ProviderEvent.Usage(finishReason = "length"))
                emit(ProviderEvent.Completed)
            },
            maxContinuations = 20,
            onContinuationTrigger = { triggers += it }
        ) { _, _ ->
            attempts++
            session {
                emit(ProviderEvent.TextDelta(text))
                emit(ProviderEvent.Completed)
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, attempts)
        assertEquals(listOf(ContinuationTrigger.PROVIDER_OUTPUT_LIMIT, ContinuationTrigger.NO_PROGRESS), triggers)
        assertEquals(text, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals("OUTPUT_CONTINUATION_NO_PROGRESS", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertFalse(events.filterIsInstance<ProviderEvent.Failed>().single().transportComplete)
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun queuePacketAtItsSerializedCapContinuesWhileShortAnswersFinish() = runTest {
        val first = (0 until 13).joinToString(" ", postfix = " ") { "Portugal developed distinct institution$it under dynasty$it" }
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.RequestConfigured(effectiveOutputTokens = 8192, serializedOutputTokens = 256))
                emit(ProviderEvent.TextDelta(first))
                emit(ProviderEvent.Usage(finishReason = "queue_complete"))
                emit(ProviderEvent.Completed)
            },
            maxContinuations = 2
        ) { draft, _ ->
            assertEquals(first, draft)
            session {
                emit(ProviderEvent.TextDelta("until constitutional government emerged."))
                emit(ProviderEvent.Usage(finishReason = "queue_complete"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(first + "until constitutional government emerged.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun disabledContinuationReportsZeroActualRequestsAndDoesNotReclassifyThePartial() = runTest {
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta("Useful partial output."))
                emit(ProviderEvent.Usage(finishReason = "length"))
                emit(ProviderEvent.Completed)
            },
            maxContinuations = 0,
            targetWords = 5000,
            adaptToObservedOutput = true
        ) { _, _ -> error("Explicitly disabled") }
        val failure = wrapper.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("OUTPUT_CONTINUATION_DISABLED", failure.errorCode)
        assertTrue(failure.message.contains("after 0 continuation requests"))
    }

    @Test fun aRestoredDraftDoesNotBypassDisabledContinuation() = runTest {
        val prefix = "Saved partial answer."
        val wrapper = OutputLimitRecoverySession(
            session { error("Do not restart the initial request") },
            maxContinuations = 0,
            initialDraft = prefix
        ) { _, _ -> error("Automatic continuation is disabled") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(prefix, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals("OUTPUT_CONTINUATION_DISABLED", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun failedLengthExtensionPreservesTheCompletedAnswerAndItsActualTrigger() = runTest {
        val triggers = mutableListOf<ContinuationTrigger>()
        val saved = "The completed answer contains useful evidence."
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta(saved))
                emit(ProviderEvent.Usage(outputTokens = 2327, finishReason = "stop"))
                emit(ProviderEvent.Completed)
            },
            targetWords = 300,
            onContinuationTrigger = { triggers += it }
        ) { draft, _ ->
            assertEquals(saved, draft)
            session {
                emit(ProviderEvent.ThinkingDelta("Reasoning only"))
                emit(ProviderEvent.Usage(outputTokens = 4096, reasoningTokens = 4096, finishReason = "length"))
                emit(ProviderEvent.Failed("The response reached its output limit."))
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf(ContinuationTrigger.REQUESTED_LENGTH), triggers)
        assertEquals(saved, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().transportComplete)
        assertTrue(events.filterIsInstance<ProviderEvent.Notice>().any { it.persistent && it.message.contains("earlier answer") })
        assertFalse(events.contains(ProviderEvent.Completed))
    }

    @Test fun reasoningOnlyOrdinaryRequestDoesNotContinueAnAbsentAnswer() = runTest {
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.ThinkingDelta("reasoning"))
                emit(ProviderEvent.Usage(outputTokens = 4096, finishReason = "length"))
                emit(ProviderEvent.Failed("The response reached its output limit."))
            },
            continueEmptyOutput = false
        ) { _, _ -> error("No visible answer to continue") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun textOnlyNativeRecoveryResumesVerifiedPrefixWithoutSendingAnInitialRequest() = runTest {
        val native = object : AgentProviderSession {
            override val handlesToolsInternally = true
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow { error("Initial generation must not repeat") }
        }
        val wrapper = OutputLimitRecoverySession(native, initialDraft = "Saved prefix. ", allowInternalToolRecovery = true) { draft, _ ->
            assertEquals("Saved prefix. ", draft)
            session {
                emit(ProviderEvent.TextDelta("Remaining section."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals("Saved prefix. Remaining section.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, events.count { it == ProviderEvent.Completed })
    }

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
        fun part(number: Int) = "Era$number " + (0 until 299).joinToString(" ", postfix = " ") { "detail${number}part$it" }
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
        assertEquals(2, attempts)
        assertEquals(text, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun oneRegenerationCanReplaceAnEmptyOrDuplicatedContinuationWithoutRepeatingEvidenceOrTools() = runTest {
        val prefix = "A saved paragraph describing the earlier history. "
        val remainder = "The following era introduced new institutions."
        val call = ProviderEvent.ToolCall("completed-effect", "write", buildJsonObject {})
        val evidence = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("Completed result"), false)))
        for (repeated in listOf("", prefix)) {
            var attempts = 0
            val triggers = mutableListOf<ContinuationTrigger>()
            val saved = mutableListOf<String>()
            val wrapper = OutputLimitRecoverySession(
                session {
                    emit(ProviderEvent.TextDelta(prefix))
                    emit(ProviderEvent.Usage(outputTokens = 100, finishReason = "length"))
                    emit(ProviderEvent.Completed)
                },
                maxContinuations = 2,
                saveProgress = { text, _, _, _ -> saved += text },
                onContinuationTrigger = { triggers += it }
            ) { draft, exchanges ->
                attempts++
                assertEquals(prefix, draft)
                assertEquals(listOf(evidence), exchanges)
                val text = if (attempts == 1) repeated else remainder
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        assertTrue(tools.isEmpty())
                        assertTrue(exchanges.isEmpty())
                        if (text.isNotEmpty()) emit(ProviderEvent.TextDelta(text))
                        emit(ProviderEvent.Usage(outputTokens = 100, finishReason = "stop"))
                        emit(ProviderEvent.Completed)
                    }
                }
            }
            val events = wrapper.streamRound(listOf(AgentToolDefinition("write", "", buildJsonObject {})), listOf(evidence)).toList()
            assertEquals(2, attempts)
            assertEquals(listOf(ContinuationTrigger.PROVIDER_OUTPUT_LIMIT, ContinuationTrigger.NO_PROGRESS), triggers)
            assertEquals(prefix + remainder, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
            assertTrue(saved.all { it == prefix || it == prefix + remainder })
            assertEquals(300, events.filterIsInstance<ProviderEvent.Usage>().sumOf { it.outputTokens ?: 0 })
            assertEquals(1, events.count { it == ProviderEvent.Completed })
            assertTrue(events.none { it is ProviderEvent.Failed || it is ProviderEvent.ToolCall || it is ProviderEvent.ToolResult })
        }
    }

    @Test fun regenerationIsSessionWideAndDoesNotResetAfterAUsefulContinuation() = runTest {
        val prefix = "A saved paragraph covering the initial period. "
        val remainder = "The next period introduced a different government. "
        var attempts = 0
        val triggers = mutableListOf<ContinuationTrigger>()
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta(prefix))
                emit(ProviderEvent.Usage(finishReason = "length"))
                emit(ProviderEvent.Completed)
            },
            maxContinuations = 8,
            onContinuationTrigger = { triggers += it }
        ) { draft, _ ->
            attempts++
            val text = if (attempts == 2) remainder else draft
            session {
                emit(ProviderEvent.TextDelta(text))
                emit(ProviderEvent.Usage(finishReason = if (attempts == 2) "length" else "stop"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(3, attempts)
        assertEquals(listOf(ContinuationTrigger.PROVIDER_OUTPUT_LIMIT, ContinuationTrigger.NO_PROGRESS, ContinuationTrigger.PROVIDER_OUTPUT_LIMIT), triggers)
        assertEquals(prefix + remainder, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals("OUTPUT_CONTINUATION_NO_PROGRESS", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun regenerationCannotExceedAnExhaustedPositiveContinuationAllowance() = runTest {
        val prefix = "Saved answer still awaiting its next section."
        var attempts = 0
        val triggers = mutableListOf<ContinuationTrigger>()
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta(prefix))
                emit(ProviderEvent.Usage(finishReason = "length"))
                emit(ProviderEvent.Completed)
            },
            maxContinuations = 1,
            onContinuationTrigger = { triggers += it }
        ) { _, _ ->
            attempts++
            session {
                emit(ProviderEvent.TextDelta(prefix))
                emit(ProviderEvent.Completed)
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, attempts)
        assertEquals(listOf(ContinuationTrigger.PROVIDER_OUTPUT_LIMIT), triggers)
        assertEquals("OUTPUT_CONTINUATION_NO_PROGRESS", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
    }

    @Test fun regenerationRetainsTypedQuotaRejectionAndDoesNotTurnItIntoAnotherRetry() = runTest {
        val prefix = "Saved text before the provider exhausted its allowance."
        val limit = dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision("TEST", "profile", "model", 123456L, "scope", "PROVIDER_RATE_LIMITED", true, continuationPrefixDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(prefix))
        var attempts = 0
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta(prefix))
                emit(ProviderEvent.Usage(finishReason = "length"))
                emit(ProviderEvent.Completed)
            },
            maxContinuations = 8,
            recoverTransientFailures = true
        ) { _, _ ->
            attempts++
            session {
                if (attempts == 1) {
                    emit(ProviderEvent.TextDelta(prefix))
                    emit(ProviderEvent.Completed)
                } else {
                    emit(ProviderEvent.Failed("Provider rate limit", providerLimit = limit))
                }
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, attempts)
        assertEquals(limit, events.filterIsInstance<ProviderEvent.Failed>().single().providerLimit)
        assertEquals(prefix, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun cancellationWhilePreparingRegenerationRetainsThePrefixAndPropagates() = runTest {
        val prefix = "A saved paragraph that must remain available."
        val saved = mutableListOf<Pair<String, String>>()
        var attempts = 0
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta(prefix))
                emit(ProviderEvent.Usage(finishReason = "length"))
                emit(ProviderEvent.Completed)
            },
            maxContinuations = 8,
            saveProgress = { text, _, _, reason -> saved += text to reason }
        ) { _, _ ->
            attempts++
            if (attempts > 1) throw CancellationException("Stopped before regeneration dispatch")
            session {
                emit(ProviderEvent.TextDelta(prefix))
                emit(ProviderEvent.Completed)
            }
        }
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull() is CancellationException)
        assertEquals(2, attempts)
        assertEquals(prefix to "CANCELLED", saved.last())
        assertTrue(saved.all { it.first == prefix })
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
        assertEquals("n", continuationSuffix("Not all tiny text fragments are repeated. Napoleo", "n"))
    }

    private fun session(events: suspend kotlinx.coroutines.flow.FlowCollector<ProviderEvent>.() -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow(events)
    }

    @Test fun repeatedOutputLimitsWithNegligibleVisibleProgressStopBeforeAThirdRequest() = runTest {
        var continued = 0
        fun expensive(text: String) = session {
            emit(ProviderEvent.TextDelta(text))
            emit(ProviderEvent.Usage(outputTokens = 4096, finishReason = "length"))
            emit(ProviderEvent.Completed)
        }
        val wrapper = OutputLimitRecoverySession(expensive("Initial visible fragment."), maxContinuations = 12) { _, _ ->
            continued++
            expensive("A second small fragment.")
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, continued)
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.contains("OUTPUT_CONTINUATION_NO_VISIBLE_PROGRESS"))
        assertTrue(events.none { it == ProviderEvent.Completed })
        assertEquals("Initial visible fragment.A second small fragment.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(8192, events.filterIsInstance<ProviderEvent.Usage>().sumOf { it.outputTokens ?: 0 })
    }

    @Test fun finishAndRetryMetadataSurviveCumulativeUsageReduction() = runTest {
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.Usage(outputTokens = 12, retryDurationMs = 5000, retryAttempts = 1, actualRuntime = "fixture", reasoningTokens = 9, writerObservationKey = "served-route"))
                emit(ProviderEvent.Usage(outputTokens = 24, finishReason = "stop"))
                emit(ProviderEvent.Completed)
            }
        ) { _, _ -> error("No continuation") }
        val usage = wrapper.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(24, usage.outputTokens)
        assertEquals("stop", usage.finishReason)
        assertEquals(5000L, usage.retryDurationMs)
        assertEquals(1, usage.retryAttempts)
        assertEquals("fixture", usage.actualRuntime)
        assertEquals(9, usage.reasoningTokens)
        assertEquals("served-route", usage.writerObservationKey)
    }

    @Test fun capturedNameLoopCancelsUpstreamBeforeOneSafeTextOnlyRepair() = runTest {
        val names = "**Molière**, **Racine**, **Corneille**, **La Fontaine**, "
        val prefix = "French classical literature includes "
        val remainder = "\nThe Enlightenment introduced new political debates."
        var upstreamStopped = false
        var emittedCycles = 0
        var repairs = 0
        val triggers = mutableListOf<ContinuationTrigger>()
        val saved = mutableListOf<String>()
        val wrapper = OutputLimitRecoverySession(
            session {
                try {
                    emit(ProviderEvent.TextDelta(prefix))
                    repeat(331) {
                        emittedCycles++
                        emit(ProviderEvent.TextDelta(names))
                    }
                    error("Repetitive generation must be canceled before it runs to its limit")
                } finally {
                    upstreamStopped = true
                }
            },
            maxContinuations = 8,
            onContinuationTrigger = { triggers += it },
            saveProgress = { draft, _, _, _ -> saved += draft }
        ) { draft, _ ->
            repairs++
            assertTrue(upstreamStopped)
            assertEquals(prefix + names, draft)
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    assertTrue(tools.isEmpty())
                    assertTrue(exchanges.isEmpty())
                    emit(ProviderEvent.TextDelta(names + remainder))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, repairs)
        assertTrue(emittedCycles < 12)
        assertEquals(listOf(ContinuationTrigger.REPETITIVE_OUTPUT), triggers)
        assertEquals(prefix + names + remainder, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(saved.all { Regex("Molière").findAll(it).count() <= 1 })
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun repeatedRepairStopsWithoutAnotherRequestOrACompletedClaim() = runTest {
        var repairs = 0
        var stoppedRequests = 0
        fun looping() = session {
            try {
                repeat(200) { emit(ProviderEvent.TextDelta("The same phrase adds no new facts. ")) }
            } finally {
                stoppedRequests++
            }
        }
        val wrapper = OutputLimitRecoverySession(looping(), maxContinuations = 100) { _, _ ->
            repairs++
            looping()
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, repairs)
        assertEquals(2, stoppedRequests)
        assertEquals("OUTPUT_CONTINUATION_NO_PROGRESS", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun continuationPublishesBeforeCompletionAndKeepsItsInvocationLineage() = runTest {
        val prefix = "The saved historical introduction ends here. "
        val lineage = dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.MODEL_TEXT, "continuation-invocation")
        val visible = StringBuilder()
        val deltas = mutableListOf<ProviderEvent.TextDelta>()
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta(prefix))
                emit(ProviderEvent.Usage(finishReason = "length"))
            }
        ) { _, _ ->
            session {
                emit(ProviderEvent.TextDelta(prefix.takeLast(28), lineage))
                emit(ProviderEvent.TextDelta("The next era changed institutions. Additional material ", lineage))
                assertTrue("A live continuation must reach the collector before completion", visible.contains("The next era"))
                emit(ProviderEvent.TextDelta("finishes this account.", lineage))
                emit(ProviderEvent.Completed)
            }
        }
        wrapper.streamRound(emptyList(), emptyList()).collect { event ->
            if (event is ProviderEvent.TextDelta) {
                visible.append(event.text)
                deltas += event
            }
        }
        assertEquals(prefix + "The next era changed institutions. Additional material finishes this account.", visible.toString())
        assertTrue(deltas.filter { "next era" in it.text || "finishes" in it.text }.all { it.lineage == lineage })
    }

    @Test fun mixedInvocationBufferedTextDoesNotGuessItsOwner() = runTest {
        val first = dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.MODEL_TEXT, "first")
        val second = first.copy(invocationId = "second")
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta("A meaningful ", first))
                emit(ProviderEvent.TextDelta("answer with different ownership.", second))
                emit(ProviderEvent.Completed)
            }
        ) { _, _ -> error("No continuation") }
        val deltas = wrapper.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>()
        assertTrue(deltas.last().lineage == dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage())
    }

    @Test fun collectorFailureDuringIncrementalContinuationIsNotCaughtAsARepair() = runTest {
        val prefix = "The saved paragraph is complete. "
        val saved = mutableListOf<Pair<String, String>>()
        val downstream = IllegalStateException("Collector rejected publication")
        var requests = 0
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.TextDelta(prefix))
                emit(ProviderEvent.Usage(finishReason = "length"))
            },
            saveProgress = { draft, _, _, reason -> saved += draft to reason }
        ) { _, _ ->
            requests++
            session {
                emit(ProviderEvent.TextDelta("A new section preserves distinct details. "))
                error("Downstream failure must stop collection first")
            }
        }
        val caught = runCatching {
            wrapper.streamRound(emptyList(), emptyList()).collect { event ->
                if (event is ProviderEvent.TextDelta && event.text.contains("new section")) throw downstream
            }
        }.exceptionOrNull()
        assertTrue(caught === downstream)
        assertEquals(1, requests)
        assertEquals("PARTIAL", saved.last().second)
        assertTrue(saved.last().first.startsWith(prefix + "A new section"))
    }

    @Test fun midChunkRepetitionRetainsOnlySafeProgressAndAlreadyObservedUsage() = runTest {
        val names = "**Molière**, **Racine**, **Corneille**, **La Fontaine**, "
        val saved = mutableListOf<String>()
        var cleanedUp = false
        val wrapper = OutputLimitRecoverySession(
            session {
                try {
                    emit(ProviderEvent.Usage(outputTokens = 12, phase = ProviderUsagePhase.INTERIM))
                    emit(ProviderEvent.TextDelta("Useful history. " + names.repeat(331)))
                    error("The producer must be canceled at the guarded chunk")
                } finally {
                    cleanedUp = true
                }
            },
            maxContinuations = 0,
            saveProgress = { draft, _, _, _ -> saved += draft }
        ) { _, _ -> error("Continuation is disabled") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertTrue(cleanedUp)
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Useful history. $names", text)
        assertTrue(saved.all { it == text })
        val usage = events.filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(12, usage.outputTokens)
        assertEquals(ProviderUsagePhase.INTERIM, usage.phase)
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun internallyManagedToolsKeepTheirLiveEventsButRepetitiveAnswerCannotReplayThem() = runTest {
        val call = ProviderEvent.ToolCall("already-owned", "read", buildJsonObject {})
        val received = mutableListOf<ProviderEvent>()
        var stopped = false
        val provider = object : AgentProviderSession {
            override val handlesToolsInternally = true
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                try {
                    emit(call)
                    assertTrue(received.contains(call))
                    repeat(100) { emit(ProviderEvent.TextDelta("This repeating passage is not new evidence. ")) }
                } finally {
                    stopped = true
                }
            }
        }
        val wrapper = OutputLimitRecoverySession(provider, maxContinuations = 10) { _, _ -> error("Internal tool session must never replay") }
        wrapper.streamRound(emptyList(), emptyList()).collect { received += it }
        assertTrue(stopped)
        assertEquals(1, received.count { it == call })
        assertEquals("OUTPUT_CONTINUATION_NO_PROGRESS", received.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertTrue(received.none { it == ProviderEvent.Completed })
    }
}
