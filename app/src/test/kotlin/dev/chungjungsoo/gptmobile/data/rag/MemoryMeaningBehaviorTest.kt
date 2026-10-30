package dev.chungjungsoo.gptmobile.data.rag

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMeaningBehaviorTest {
    private fun fact(id: String, text: String, scope: String = "project:a") = VaultFact(id, MemoryLearning.observation(text), scope = scope, meaning = MemoryLearning.meaning(text))

    @Test fun explicitDecisionKeepsItsStatedReason() {
        val text = "I decided to use SQLite because it works offline"
        val meaning = requireNotNull(MemoryLearning.meaning(text))
        assertEquals("DECISION", meaning.kind)
        assertEquals("it works offline", meaning.rationale)
        assertEquals(text, meaning.evidenceQuote)
        assertNull(MemoryLearning.meaning("I decided to use SQLite")?.rationale)
    }

    @Test fun temporalSinceDoesNotBecomeAnInventedReason() {
        assertNull(MemoryLearning.meaning("I decided to use SQLite since 2020")?.rationale)
    }

    @Test fun genericWordsQuotesQuestionsAndSecretsDoNotBecomeEpisodes() {
        listOf("The issue is interesting", "Actually coffee is nice", "> The toolbar should be blue", "Should the toolbar be blue?", "My password must be secret123", "Imagine the toolbar should be blue", "Do not remember the toolbar should be blue").forEach {
            assertNull(it, MemoryLearning.meaning(it))
        }
    }

    @Test fun requirementsPreserveNegationAndQuantity() {
        val text = "Combined Mode should produce exactly 1 answer without repeating contributions"
        assertEquals(text, MemoryLearning.meaning(text)?.expectedBehavior)
        assertTrue(MemoryLearning.extract(text, 75).any { it.relation.relationType == "MEMORY_EPISODE" })
        assertFalse(MemoryLearning.extract(text, 75, false).any { it.relation.relationType == "MEMORY_EPISODE" })
    }

    @Test fun correctionsLinkOnlyOneOlderSubjectInSameScope() {
        val original = fact("a", "The toolbar should appear at the top").let { it.copy(fact = it.fact.copy(relation = it.fact.relation.copy(relationType = "MEMORY_EPISODE")), sourceMessageId = 1) }
        val correction = MemoryLearning.meaning("Actually, the toolbar should appear at the bottom")
        assertEquals(listOf("a"), MemoryMeaningPolicy.correctionTargets(correction, listOf(original), "project:a", 2).map { it.id })
        assertTrue(MemoryMeaningPolicy.correctionTargets(correction, listOf(original), "project:b", 2).isEmpty())
        assertTrue(MemoryMeaningPolicy.correctionTargets(correction, listOf(original.copy(sourceMessageId = 3)), "project:a", 2).isEmpty())
        assertTrue(MemoryMeaningPolicy.correctionTargets(correction, listOf(original, original.copy(id = "b")), "project:a", 2).isEmpty())
        assertTrue(MemoryMeaningPolicy.correctionTargets(correction, listOf(original.copy(localOnly = true)), "project:a", 2).isEmpty())
    }

    @Test fun paraphraseRetrievesRequirementInLexicalFallback() {
        val requirement = fact("a", "Combined Mode should produce one unified answer without repeating contributions")
        val coffee = fact("b", "I prefer coffee")
        assertEquals(listOf("a"), MemoryRecallPolicy.select("Why is the multi-model response stitching individual answers together?", listOf(requirement, coffee), FactVaultSettings()).map { it.id })
    }

    @Test fun denseScoreCanRetrieveWithoutSharedWords() {
        val memory = fact("a", "I enjoy astronomy")
        assertEquals(listOf("a"), MemoryRecallPolicy.rank("Observing celestial objects", listOf(memory), semanticScores = mapOf("a" to 0.9)).map { it.id })
    }

    @Test fun reasonTypeAloneCannotAdmitUnrelatedMemory() {
        val memory = fact("a", "I decided to use SQLite because it works offline")
        assertTrue(MemoryRecallPolicy.select("Why did the Roman empire fall?", listOf(memory), FactVaultSettings()).isEmpty())
    }

    @Test fun graphExpansionIsOneHopAndNeverUsesUserHub() {
        val a = fact("a", "The toolbar should be blue")
        val b = fact("b", "The toolbar is broken")
        val c = fact("c", "I prefer coffee")
        assertEquals(listOf("b"), MemoryGraphRecall.expand(listOf(a), listOf(a, b, c, b.copy(id = "foreign", scope = "project:b"), b.copy(id = "pending", awaitingReview = true))).map { it.id })
        assertTrue(MemoryGraphRecall.expand(listOf(c), listOf(a, b)).isEmpty())
    }

    @Test fun invalidAndSupersededMemoriesCannotWinEvenWithPerfectScores() {
        val memory = fact("a", "I prefer coffee")
        listOf(memory.copy(enabled = false), memory.copy(awaitingReview = true), memory.copy(supersededBy = "b"), memory.copy(validUntilMillis = 10), memory.copy(validFromMillis = 30)).forEach {
            assertTrue(MemoryRecallPolicy.select("coffee", listOf(it), FactVaultSettings(), now = 20, semanticScores = mapOf("a" to 1.0)).isEmpty())
        }
    }

    @Test fun legacyPayloadAndMeaningRoundTrip() {
        val memory = fact("a", "I decided to use SQLite because it works offline")
        assertEquals(memory, Json.decodeFromString<VaultFact>(Json.encodeToString(memory)))
        assertNull(Json.decodeFromString<VaultFact>(Json.encodeToString(memory.copy(meaning = null))).meaning)
    }

    @Test fun changedMeaningInvalidatesPreparedRecallAndOldValuesStayOutOfPrompt() {
        val memory = fact("a", "I decided to use SQLite because it works offline")
        assertFalse(memory.hasSameRecallContent(memory.copy(meaning = memory.meaning?.copy(rationale = "changed"))))
        assertFalse(FactRecall(listOf(memory.copy(previousValues = listOf("removed private detail")))).prefix().contains("removed private detail"))
    }

    @Test fun tokenBudgetIsAHardLimit() {
        val memory = fact("a", "The toolbar should " + "remain blue ".repeat(100))
        assertTrue(MemoryRecallPolicy.select("toolbar", listOf(memory), FactVaultSettings(recallTokens = 128)).isEmpty())
    }

    @Test fun sharedEligibilityEnforcesCloudLocalOnlyChatAndReviewGates() {
        val memory = fact("a", "The toolbar should be blue").copy(sourceChatId = 7, sourceMessageId = 8)
        fun allowed(value: VaultFact = memory, settings: FactVaultSettings = FactVaultSettings()) = MemoryEligibility.accepts(value, settings, 1, 0, false, true, 1000)
        assertTrue(allowed())
        assertFalse(allowed(memory.copy(localOnly = true)))
        assertFalse(allowed(memory.copy(awaitingReview = true)))
        assertFalse(allowed(settings = FactVaultSettings(allowCloudRecall = false)))
        assertFalse(allowed(settings = FactVaultSettings(sameChatOnly = true)))
        assertFalse(MemoryEligibility.accepts(memory, FactVaultSettings(), 7, 8, true, true, 1000))
    }

    @Test fun explicitHistoryCanRecallSupersededButNotDisabledOrDeletedRecords() {
        val old = fact("old", "The toolbar should be at the top").copy(enabled = false, supersededBy = "new")
        assertTrue(MemoryRecallPolicy.select("toolbar", listOf(old), FactVaultSettings()).isEmpty())
        val result = MemoryRecallPolicy.select("previous toolbar requirement", listOf(old), FactVaultSettings())
        assertEquals(listOf("old"), result.map { it.id })
        assertTrue(FactRecall(result, includesHistory = true).prefix().contains("SUPERSEDED"))
        assertTrue(MemoryRecallPolicy.select("previous toolbar requirement", listOf(old.copy(supersededBy = null)), FactVaultSettings()).isEmpty())
        assertTrue(MemoryRecallPolicy.select("previous toolbar requirement", emptyList(), FactVaultSettings()).isEmpty())
    }

    @Test fun evaluationDistinguishesFallbackFromNativeResults() = runBlocking {
        val report = MemoryGoldenEvaluation.run()
        assertEquals(0, report.semanticQueries)
        assertEquals(0, report.policyViolations)
        assertTrue(report.queries >= 14)
        assertEquals(report.abstentionQueries, report.abstentionsCorrect)
    }

    @Test fun localEnrichmentCanProposeNewWordingButCannotInventEvidence() {
        val source = "Let's keep the app usable offline"
        val proposal = Json.parseToJsonElement("""{"observations":[{"quote":"Let's keep the app usable offline","kind":"decision"}]}""") as kotlinx.serialization.json.JsonObject
        val fact = MemoryLearning.modelObservations(source, proposal, 75).single()
        assertEquals(source, fact.relation.context)
        assertEquals("DECISION", fact.target.attributes["memoryKind"])
        assertNull(MemoryLearning.meaning(source))
        assertTrue(MemoryLearning.modelObservations("I prefer coffee", proposal, 75).isEmpty())
    }
}
