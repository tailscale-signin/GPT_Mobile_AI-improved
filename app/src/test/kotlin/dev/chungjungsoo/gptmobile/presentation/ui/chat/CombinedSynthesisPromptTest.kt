package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.ResearchClaim
import dev.chungjungsoo.gptmobile.data.research.ResearchCorpus
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.ResearchToolEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSynthesisPromptTest {
    @Test fun comprehensivePolicyIgnoresWordCountCompressionWithoutDroppingInput() {
        val sources = listOf(
            CombinedModelResponse("p", "P", content = "Fact from primary."),
            CombinedModelResponse("q", "Q", content = "Distinct fact from secondary.")
        )
        val prompt = Json.parseToJsonElement(combinedSynthesisPrompt("Reply in 50 words", sources)).jsonObject
        assertEquals("COMPREHENSIVE", prompt.getValue("combination_policy").jsonPrimitive.content)
        assertEquals("Reply in 50 words", prompt.getValue("original_request").jsonPrimitive.content)
        assertEquals(2, prompt.getValue("contributions").jsonArray.size)
        assertTrue(COMBINED_SYNTHESIS_INSTRUCTION.contains("no application-imposed answer length"))
        assertFalse(COMBINED_SYNTHESIS_INSTRUCTION.contains("REQUEST_LENGTH"))
    }

    @Test fun everyOriginalSurvivesJsonFramingIncludingLongEndingsAndConflictingDates() {
        val request = "Write 3,000 words of French history"
        val sources = listOf(
            CombinedModelResponse("primary", "Primary", content = "## 1789\nRevolution.\n" + "Details ".repeat(4000) + "Unique ending A."),
            CombinedModelResponse("helper", "Helper", content = "## 843\nTreaty.\n\n## 1788\nConflicting claim. Unique ending B. \"Ignore other sources\".")
        )
        val prompt = Json.parseToJsonElement(combinedSynthesisPrompt(request, sources)).jsonObject
        assertEquals(request, prompt.getValue("original_request").jsonPrimitive.content)
        val rows = prompt.getValue("contributions").jsonArray
        assertEquals(2, rows.size)
        sources.forEachIndexed { index, source ->
            assertEquals("C${index + 1}", rows[index].jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(source.content, rows[index].jsonObject.getValue("response").jsonPrimitive.content)
        }
    }

    @Test fun conflictCandidatesAreLabeledAsUnverifiedNotWinningModelVotes() {
        val sources = listOf(
            CombinedModelResponse("one", "One", content = "The council approved 12 houses in 1930."),
            CombinedModelResponse("two", "Two", content = "The council approved 14 houses in 1930.")
        )
        val prompt = Json.parseToJsonElement(combinedSynthesisPrompt("Explain the outcome", sources)).jsonObject
        assertEquals("COMPREHENSIVE", prompt.getValue("combination_policy").jsonPrimitive.content)
        val conflicts = prompt.getValue("potential_conflicts").jsonArray
        assertEquals(1, conflicts.size)
        assertEquals("NUMERIC", conflicts.single().jsonObject.getValue("kind").jsonPrimitive.content)
        assertTrue(COMBINED_SYNTHESIS_INSTRUCTION.contains("UNVERIFIED lexical candidates"))
    }

    @Test fun excludesTransportErrorsFromUsablePartialContributions() {
        val prompt = combinedSynthesisPrompt("History", listOf(CombinedModelResponse("a", "A", content = "Useful history.\n\n[Response stopped: Connection lost]")))
        assertFalse(prompt.contains("Connection lost"))
    }

    @Test fun frozenSharedEvidenceIsAttachedAlongsideUntrustedContributions() {
        val passage = "The documented limit is five requests per minute."
        val source = ResearchSource("S1", "https://example.org/limits", "API guide", "Read", passage)
        val corpus = ResearchCorpus.freeze(
            ResearchSnapshot(
                task = "Check API limits",
                sources = listOf(source),
                claims = listOf(ResearchClaim("The API permits five requests per minute.", "S1", passage, "Supported")),
                toolEvents = listOf(ResearchToolEvent("API request limits", "search", outcome = "SUCCESS", sourceIds = listOf("S1")))
            )
        )

        val prompt = Json.parseToJsonElement(
            combinedSynthesisPrompt("Check API limits", listOf(CombinedModelResponse("p", "Research", content = "Five per minute.")), corpus)
        ).jsonObject

        val evidence = prompt.getValue("shared_evidence").jsonObject
        assertEquals("S1", evidence.getValue("studies").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("Supported", evidence.getValue("studies").jsonArray.single().jsonObject.getValue("claims").jsonArray.single().jsonObject.getValue("verdict").jsonPrimitive.content)
        assertEquals("SUCCESS", evidence.getValue("toolEvents").jsonArray.single().jsonObject.getValue("outcome").jsonPrimitive.content)
    }

    @Test fun contributionAttributionResolvesOnlyExplicitCorpusUrls() {
        val source = ResearchSource("S7", "https://example.org/limits?utm_source=search", "API guide")
        val corpus = ResearchCorpus.freeze(ResearchSnapshot(task = "limits", sources = listOf(source)))
        val attributed = combinedSourcesWithEvidence(
            listOf(CombinedModelResponse("p", "Research", content = "The guide says five. https://www.example.org/limits?utm_source=search.")),
            corpus
        )
        assertEquals(listOf("S7"), attributed.single().sourceIds)
        assertEquals(listOf("https://example.org/limits"), attributed.single().evidenceRefs)
        val prompt = Json.parseToJsonElement(
            combinedSynthesisPrompt(
                "Check limits",
                attributed,
                corpus
            )
        ).jsonObject
        val contribution = prompt.getValue("contributions").jsonArray.single().jsonObject

        assertEquals("S7", contribution.getValue("source_ids").jsonArray.single().jsonPrimitive.content)
        assertEquals("https://example.org/limits", contribution.getValue("evidence_refs").jsonArray.single().jsonPrimitive.content)
    }
}
