package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedDeliverySessionTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/combined/great-emu-war.json")).readText()).jsonObject
    private val sources = fixture.getValue("sources").jsonArray.map { row ->
        val value = row.jsonObject
        CombinedModelResponse(value.getValue("platformUid").jsonPrimitive.content, value.getValue("platformName").jsonPrimitive.content, content = value.getValue("content").jsonPrimitive.content)
    }
    private val winner = fixture.getValue("selectedAnswer").jsonPrimitive.content

    // A source-preserving test response, grouped by topic with exact duplicate sentences removed.
    private val complete = sources.groupBy { ConversationSubject.extract(it.content) }.entries.joinToString("\n\n") { (topic, rows) ->
        "## $topic\n\n" + rows.flatMap { CombinedContentCoverage.sentences(ConversationSubject.withoutMetadata(it.content)) }.distinct().joinToString(" ")
    }
    private fun session(text: String, terminal: ProviderEvent = ProviderEvent.Completed) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            assertTrue(tools.isEmpty())
            assertTrue(exchanges.isEmpty())
            emit(ProviderEvent.TextDelta(text))
            emit(terminal)
        }
    }

    @Test fun greatEmuWarWinnerFailsCoverageAgainstAllFiveFrozenInputs() {
        val coverage = CombinedContentCoverage(sources)
        val missing = coverage.check(winner)
        assertFalse(missing.accepted)
        assertTrue(missing.missing.flatMap { it.profileUids }.containsAll(listOf("profile-2", "profile-3", "profile-4", "profile-5")))
        assertTrue(coverage.check(complete).accepted)
        assertTrue(complete.contains("Commodore Perry"))
        assertTrue(complete.contains("constitutional government"))
        val before = fixture.getValue("before").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content
        assertEquals(117, sources[1].content.length - before.length)
    }

    @Test fun losingTopicsRepairsOnceWithoutPublishingTheWinningDraft() = runTest {
        var repairs = 0
        val wrapper = CombinedDeliverySession(session(winner), sources, 100_000, validateAccess = {}) { _, missing ->
            repairs++
            assertTrue(missing.isNotEmpty())
            session(complete)
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, repairs)
        assertEquals(complete, events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun repeatedSingleTopicNeverBecomesCompletedResearchOrCombinedDelivery() = runTest {
        var repairs = 0
        val wrapper = CombinedDeliverySession(session(winner), sources, 100_000, validateAccess = {}) { _, _ ->
            repairs++
            session(winner)
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, repairs)
        assertTrue(events.none { it == ProviderEvent.Completed })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.startsWith("COMBINED_COVERAGE_INCOMPLETE"))
    }

    @Test fun revocationDuringRepairDoesNotPublishRetainedContent() = runTest {
        var allowed = true
        val wrapper = CombinedDeliverySession(session(winner), sources, 100_000, validateAccess = { check(allowed) { "PERMISSION_REVOKED" } }) { _, _ ->
            allowed = false
            session(complete)
        }
        val emitted = mutableListOf<ProviderEvent>()
        val failure = runCatching { wrapper.streamRound(emptyList(), emptyList()).collect { emitted += it } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(emitted.none { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
    }

    @Test fun cancellationAndDownstreamFailuresRemainTransparent() = runTest {
        val wrapper = CombinedDeliverySession(session(complete), sources, 100_000, validateAccess = {}) { _, _ -> error("No repair") }
        val consumerFailure = IllegalStateException("consumer stopped")
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).collect { throw consumerFailure } }.exceptionOrNull() === consumerFailure)
        val stop = CancellationException("stopped")
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).collect { throw stop } }.exceptionOrNull() === stop)
    }

    @Test fun repairPreparationFailureRetainsAnHonestIncompleteAnswer() = runTest {
        val wrapper = CombinedDeliverySession(session(winner), sources, 100_000, validateAccess = {}) { _, _ -> error("Context cannot fit repair") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(winner, events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertTrue(events.none { it == ProviderEvent.Completed })
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
    }

    @Test fun repairCannotRewriteAnAlreadyCommittedPrefix() = runTest {
        val wrapper = CombinedDeliverySession(session("Committed prefix." + winner), sources, 100_000, committedPrefix = "Committed prefix.", validateAccess = {}) { _, _ -> session(complete) }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.none { it == ProviderEvent.Completed })
        assertEquals("Committed prefix." + winner, events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun sharedTextNamesEverySubmitterAndParaphrasesStayNeutral() {
        val sentence = "The new government created a conscript army and built railways."
        val rows = listOf(CombinedModelResponse("one", "One", content = sentence), CombinedModelResponse("two", "Two", content = sentence), CombinedModelResponse("three", "Three", content = "Different information."))
        assertEquals(setOf("one", "two"), combinedVerbatimProfiles(sentence, rows))
        assertTrue(combinedVerbatimProfiles("Rail transport and national conscription were introduced.", rows).isEmpty())
        assertFalse(comprehensiveCombinedEnvelope("{}"))
        assertTrue(comprehensiveCombinedEnvelope("{\"combination_policy\":\"COMPREHENSIVE\"}"))
    }
}
