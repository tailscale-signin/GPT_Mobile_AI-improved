package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedDeliverySessionTest {
    @Test fun failedOrUnterminatedRepairCannotReplaceCompletedOriginal() = runTest {
        val original = "Original. ".repeat(20)
        for (terminal in listOf<ProviderEvent?>(ProviderEvent.Failed("timeout"), null)) {
            val repair = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    emit(ProviderEvent.TextDelta("Partial replacement"))
                    terminal?.let { emit(it) }
                }
            }
            val wrapper = CombinedDeliverySession(session(original), sources, 10000, validateAccess = {}, originalRequest = "One fact under 10 words") { _, _ -> repair }
            val events = wrapper.streamRound(emptyList(), emptyList()).toList()
            assertTrue(events.answer().startsWith("Original"))
            assertFalse(events.answer().contains("Partial replacement"))
            assertTrue(events.last() is ProviderEvent.Failed)
        }
    }

    private val sources = listOf(
        CombinedModelResponse("alpha", "Alpha", content = "The silver mines of Athens financed construction of naval ships."),
        CombinedModelResponse("beta", "Beta", content = "Persian merchants carried ceramics along the Silk Road during the Tang era.")
    )
    private fun session(text: String, terminal: ProviderEvent = ProviderEvent.Completed) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            assertTrue(tools.isEmpty())
            assertTrue(exchanges.isEmpty())
            emit(ProviderEvent.TextDelta(text))
            emit(terminal)
        }
    }
    private fun passage(text: String, profiles: List<String> = listOf("C1"), claims: List<String> = listOf("C1.1")) = buildJsonObject {
        put("type", "passage")
        put("text", text)
        put("profiles", JsonArray(profiles.map(::JsonPrimitive)))
        put("claims", JsonArray(claims.map(::JsonPrimitive)))
        put("sources", JsonArray(emptyList()))
    }.toString() + "\n"
    private fun List<ProviderEvent>.answer() = combinedAnswerText()
    private fun List<ProviderEvent>.quality() = combinedReport()

    @Test fun singleExampleScopeDoesNotRequireEveryContributorClaim() = runTest {
        var repairs = 0
        val answer = sources.first().content
        val wrapper = CombinedDeliverySession(session(answer), sources, 10000, validateAccess = {}, originalRequest = "Give one historical fact in under 100 words") { _, _ ->
            repairs++
            error("Lexical misses cannot prove an omission")
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(answer, events.answer())
        assertEquals(0, repairs)
        assertFalse(events.answer().contains(COMBINED_RECOVERY_MARKER))
        assertFalse(events.answer().contains("Further details"))
        assertEquals(ProviderEvent.Completed, events.last())
        assertTrue(events.quality().semanticCoverageVerified)
        assertFalse(events.quality().factualAccuracyVerified)
    }

    @Test fun oneInstructionRepairHonorsWordLimitRatherThanAllSourceSentences() = runTest {
        var repairs = 0
        val wrapper = CombinedDeliverySession(session("word ".repeat(40)), sources, 10000, validateAccess = {}, originalRequest = "One fact under 10 words") { _, missing ->
            repairs++
            assertTrue(missing.isEmpty())
            session("Silver mines funded Athenian ships.")
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, repairs)
        assertEquals("Silver mines funded Athenian ships.", events.answer())
        assertEquals(1, events.quality().repairRounds)
        assertTrue(events.quality().issues.none { it.startsWith("WORD_") })
    }

    @Test fun targetLengthRepairReceivesUnresolvedClaimsForBroadRequests() = runTest {
        var repairs = 0
        val draft = "The silver mines in Athens financed naval ships. Athens' regional naval history reflects the city's changing political and economic priorities across ancient Greek society. Researchers continue to examine these connections through material evidence, inscriptions, and comparison with neighboring city-states, placing the subject in a wider historical framework."
        val repaired = "Silver mines funded Athenian ships. Persian merchants carried ceramics along the Silk Road during the Tang era. These findings describe distinct historical settings. The first concerns silver and naval construction in Athens, while the second concerns Persian merchants, ceramics, and Silk Road exchange in the Tang era."
        val wrapper = CombinedDeliverySession(session(draft), sources, 10000, validateAccess = {}, originalRequest = "Summarize both findings in 50 words") { _, missing ->
            repairs++
            assertEquals(1, missing.size)
            assertEquals(setOf("beta"), missing.single().profileUids)
            session(repaired)
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, repairs)
        assertEquals(repaired, events.answer())
        assertEquals(1, events.quality().repairRounds)
    }

    @Test fun failedRepairEnforcesUserCeilingAndReportsQualitySeparately() = runTest {
        val draft = "word ".repeat(40)
        val wrapper = CombinedDeliverySession(session(draft), sources, 10000, validateAccess = {}, originalRequest = "Under 10 words") { _, _ ->
            error("Provider unavailable")
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertTrue(visibleWordCount(events.answer()) < 10)
        assertEquals("CORRECTION_REQUIRED", events.quality().status)
        assertTrue(events.quality().issues.contains("WORD_CEILING_ENFORCED_AFTER_REPAIR"))
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun progressivePassagesArriveBeforeProviderCompletesAndRetainJointOwnership() = runTest {
        var firstDisplayed = false
        val laterDetail = "The Tang dynasty ruled from 618 to 907."
        val sharedSources = listOf(sources.first(), sources[1].copy(content = sources[1].content + " " + laterDetail))
        val base = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta(passage(sources[0].content + " " + sources[1].content, listOf("C2", "C1"), listOf("C1.1", "C2.1"))))
                assertTrue(firstDisplayed)
                emit(ProviderEvent.TextDelta(passage(laterDetail, listOf("C2"), listOf("C2.2"))))
                emit(ProviderEvent.Completed)
            }
        }
        val wrapper = CombinedDeliverySession(base, sharedSources, 10000, validateAccess = {}, structured = true) { _, _ -> error("No rewrite after publication") }
        val events = mutableListOf<ProviderEvent>()
        wrapper.streamRound(emptyList(), emptyList()).collect {
            events += it
            if (it is ProviderEvent.TextDelta) firstDisplayed = true
        }
        val report = events.quality()
        assertEquals(2, report.passages.size)
        assertEquals(setOf("alpha", "beta"), report.passages.first().profileUids)
        assertEquals("alpha", report.passages.first().primaryProfileUid)
        assertTrue(report.passages.all { it.matches(events.answer()) })
        assertFalse(events.answer().contains("\"type\""))
    }

    @Test fun semanticParaphraseDuplicatesAreNotPublishedTwice() = runTest {
        val same = sources.first().content
        val wrapper = CombinedDeliverySession(session(passage(same) + passage(same)), sources, 10000, validateAccess = {}, structured = true) { _, _ -> error("No repair") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(same, events.answer())
        assertEquals(1, events.quality().duplicatePassagesRemoved)
    }

    @Test fun duplicateMergeRetainsBothProfilesWithoutRepeatingTheAnswer() = runTest {
        val same = sources.first().content
        val wrapper = CombinedDeliverySession(session(passage(same) + passage(same, listOf("C2"), listOf("C2.1"))), listOf(sources.first(), sources[1].copy(content = same)), 10000, validateAccess = {}, structured = true) { _, _ -> error("No repair") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(same, events.answer())
        assertEquals(setOf("alpha", "beta"), events.quality().passages.single().profileUids)
    }

    @Test fun latestNewsWithoutReadableDatedArticleDoesNotDispatchWriterOrInventHeadlines() = runTest {
        var dispatched = false
        val base = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                dispatched = true
                emit(ProviderEvent.TextDelta("Unverified Waymo headline"))
            }
        }
        val wrapper = CombinedDeliverySession(base, sources, 10000, validateAccess = {}, originalRequest = "Newest Toronto news story", structured = true) { _, _ -> error("No research in synthesis") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertFalse(dispatched)
        assertFalse(events.answer().contains("Waymo"))
        assertEquals(listOf("CURRENT_ARTICLE_EVIDENCE_UNAVAILABLE"), events.quality().issues)
        assertFalse(events.quality().complete)
        assertFalse(events.quality().semanticCoverageVerified)
        assertEquals("PARTIAL", events.quality().deliveredScopeStatus)
        CombinedAnswerPublication.validate(events.answer(), events.quality())
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun malformedProtocolAndUnknownIdsNeverBecomeRawControlTextOrCertifiedOwnership() = runTest {
        val payload = "{broken}\n" + passage("Unlinked candidate finding.", listOf("C99"), listOf("C99.1"))
        val wrapper = CombinedDeliverySession(session(payload), sources, 10000, validateAccess = {}, structured = true) { _, _ -> error("No repair") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertFalse(events.answer().contains("{broken}"))
        assertTrue(events.quality().issues.contains("INVALID_DELIVERY_RECORD"))
        assertTrue(events.quality().passages.single().profileUids.isEmpty())
    }

    @Test fun revocationDuringBufferedRepairDoesNotPublishRetainedText() = runTest {
        var allowed = true
        val wrapper = CombinedDeliverySession(session("word ".repeat(30)), sources, 10000, validateAccess = { check(allowed) }, originalRequest = "Under 10 words") { _, _ ->
            allowed = false
            session("A short fact.")
        }
        val events = mutableListOf<ProviderEvent>()
        val failure = runCatching { wrapper.streamRound(emptyList(), emptyList()).collect { events += it } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(events.none { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
    }

    @Test fun cancellationAndDownstreamFailureRemainTransparent() = runTest {
        val wrapper = CombinedDeliverySession(session("A fact."), sources, 10000, validateAccess = {}) { _, _ -> error("No repair") }
        val stop = CancellationException("stop")
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).collect { throw stop } }.exceptionOrNull() === stop)
        val failure = IllegalStateException("consumer")
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).collect { throw failure } }.exceptionOrNull() === failure)
    }

    @Test fun providerFailureKeepsPartialTextAndReportsInterruptedQuality() = runTest {
        val wrapper = CombinedDeliverySession(session("Partial answer.", ProviderEvent.Failed("Disconnected")), sources, 10000, validateAccess = {}) { _, _ -> error("No repair") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals("Partial answer.", events.answer())
        assertEquals("INTERRUPTED", events.quality().status)
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun committedPrefixCannotBeReplaced() = runTest {
        val wrapper = CombinedDeliverySession(session("Replacement."), sources, 10000, committedPrefix = "Saved.", validateAccess = {}) { _, _ -> error("No repair") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.answer().isEmpty())
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun decoderHandlesEveryChunkBoundaryIncludingSurrogatePairsAndHiddenDecisions() {
        val payload = passage("😀 Rewritten information.") + "{\"type\":\"decision\",\"claim\":\"C2.1\",\"disposition\":\"OUT_OF_SCOPE\",\"reason\":\"Only one fact requested\"}\n"
        for (size in 1..payload.length) {
            val decoder = CombinedPassageDecoder(sources, CombinedEvidenceLedger(), 10000)
            val deltas = payload.chunked(size).flatMap { decoder.accept(it) } + decoder.accept("", true)
            assertEquals("😀 Rewritten information.", deltas.joinToString(""))
            assertEquals(1, decoder.decisions.size)
            assertTrue(decoder.passages.single().matches(decoder.answer()))
        }
    }

    @Test fun decoderRendersProviderWrappedJsonArrayInsteadOfLeakingProtocolRecords() {
        val payload = """[
            {"type":"passage","text":"Canada's history begins with Indigenous peoples.\nThey built diverse societies.","profiles":["C1","C2"],"claims":["C1.1","C2.1"],"sources":[]},
            {"type":"decision","claim":"C2.1","disposition":"OUT_OF_SCOPE","reason":"The shared point is already covered."}
        ]"""
        val expected = "Canada's history begins with Indigenous peoples.\nThey built diverse societies."
        for (size in 1..payload.length) {
            val decoder = CombinedPassageDecoder(sources, CombinedEvidenceLedger(), 10000)
            val deltas = payload.chunked(size).flatMap { decoder.accept(it) } + decoder.accept("", true)
            assertEquals("Chunk size $size", expected, deltas.joinToString(""))
            assertFalse(decoder.answer().contains("\"type\""))
            assertFalse(decoder.answer().startsWith("["))
            assertEquals(1, decoder.passages.size)
            assertTrue(decoder.passages.single().profileUids.isEmpty())
            assertEquals(1, decoder.decisions.size)
        }
    }

    @Test fun splitOpeningFenceNeverLeaksStructuredRecordsIntoTheAnswer() {
        val answer = "Athenian ships were financed by silver mines."
        val payload = "```json\n" + passage(answer) + "```"
        for (size in 1..payload.length) {
            val decoder = CombinedPassageDecoder(sources, CombinedEvidenceLedger(), 10000)
            val deltas = payload.chunked(size).flatMap { decoder.accept(it) } + decoder.accept("", true)
            assertEquals("Chunk size $size", answer, deltas.joinToString(""))
            // The shared matcher now recognizes this conservative financed/funded paraphrase.
            assertEquals(setOf("alpha"), decoder.passages.single().profileUids)
            assertTrue(decoder.passages.single().links.all { it.matches(answer) })
        }
    }

    @Test fun splitInlineCodeStillUsesPlainTextFallback() {
        val answer = "`silver` financed Athenian ships."
        val decoder = CombinedPassageDecoder(sources, CombinedEvidenceLedger(), 10000)
        val deltas = answer.chunked(1).flatMap { decoder.accept(it) } + decoder.accept("", true)
        assertEquals(answer, deltas.joinToString(""))
        assertTrue(decoder.passages.isEmpty())
    }

    @Test fun fallbackNeverAppendsUnverifiedSourcePassages() {
        assertEquals(null, appendMissingCombinedPassages("Draft", listOf(CombinedContentCoverage.Unit("Missing", setOf("a"))), 1000))
    }
}
