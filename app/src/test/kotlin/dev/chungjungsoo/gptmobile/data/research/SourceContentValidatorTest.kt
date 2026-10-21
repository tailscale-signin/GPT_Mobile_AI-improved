package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceContentValidatorTest {
    @Test fun oldFullTextCookieCaptureCannotRegainReadableCredit() {
        val source = ResearchSource("S1", "https://pubmed.ncbi.nlm.nih.gov/1/", "Study", "Read", "Cookies must be enabled to view this page. Please enable cookies in your browser.")
        assertFalse(source.readable)
        assertEquals(SourceContentValidator.Kind.CHALLENGE, SourceContentValidator.validate(source.passage).kind)
        assertFalse(ResearchSnapshot.parse(ResearchSnapshot("task", sources = listOf(source)).json())!!.sources.single().readable)
    }

    @Test fun realArticleWithCookieFooterRemainsReadable() {
        val article = "Methods and results from a controlled trial. ".repeat(250) + "Accept all cookies. Cookie preferences."
        assertTrue(SourceContentValidator.validate(article).usable)
        assertEquals(SourceContentValidator.Kind.PARTIAL_ARTICLE, SourceContentValidator.validate(article).kind)
    }

    @Test fun strongerCaptureSurvivesNewerPartialRefresh() {
        val rich = ResearchSource("S1", "https://example.org/study", "Study", "Read", "Retained results with full supporting context. ".repeat(20), versionId = "v", retrievedAt = 1)
        val partial = rich.copy(status = "Partially read", retrievalQuality = "PARTIAL_TEXT", passage = "A short excerpt", retrievedAt = 2)
        val merged = mergeResearchSnapshots(listOf(ResearchSnapshot("task", sources = listOf(rich))), ResearchSnapshot("task", sources = listOf(partial)))
        assertEquals(rich.passage, merged.sources.single().passage)
    }

    @Test fun explicitNoResearchDecisionDiscardsExplanatoryQuestions() {
        val value = Json.parseToJsonElement("""{"questions":["A greeting needs no search"],"queries":[],"noResearchNeeded":true}""").jsonObject
        assertTrue(ResearchPlanContract.parse(value).noResearchNeeded)
        assertTrue(ResearchPlanContract.parse(value).questions.isEmpty())
        assertTrue(runCatching { ResearchPlanContract.parse(value, requiresEvidence = true) }.isFailure)
        assertTrue(isResearchConversationOnly("Hi!"))
        assertFalse(isResearchConversationOnly("Hi, research nicotine"))
    }

    @Test fun sharedCorpusKeepsTaskAndQuestionLineage() {
        val first = ResearchSnapshot("Same task", questionPlan = listOf(ResearchQuestion("Q1", "Question one")))
        val second = ResearchSnapshot("Same task", questionPlan = listOf(ResearchQuestion("Q1", "Question two")))
        val merged = mergeResearchSnapshots(listOf(first), second)
        assertEquals(2, merged.questionPlan.size)
        assertEquals(2, merged.questionPlan.map { it.id }.toSet().size)
        val greeting = mergeResearchSnapshots(listOf(first), ResearchSnapshot("Hi"))
        assertTrue(greeting.questionPlan.isEmpty())
    }
}
