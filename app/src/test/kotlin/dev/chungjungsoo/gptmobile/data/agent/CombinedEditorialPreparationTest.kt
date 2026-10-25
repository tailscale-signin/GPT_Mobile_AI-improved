package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisPrompt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedEditorialPreparationTest {
    @Test fun acceptedSinglePassContributorsHaveComplementaryEmphasisAndStillOwnTheWholeRequest() {
        val request = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("a", "b", "c"), emptyMap(), "List 10 reasons", singlePass = true)
        val instructions = request.participantUids.map { uid ->
            combinedContributorInstruction(PlatformV2(uid = uid, name = uid), CombinedExecutionContext(request, rootBudgetId = "turn", profileUid = uid))
        }
        assertEquals(3, instructions.distinct().size)
        assertTrue(instructions[0].contains("ordered outline"))
        assertTrue(instructions[1].contains("causes, consequences"))
        assertTrue(instructions[2].contains("material qualifications"))
        instructions.forEach {
            assertTrue(it.contains("still cover every required topic"))
            assertTrue(it.contains("Requested items: 10"))
        }
    }

    @Test fun editorialRangesKeepOriginalUtf16BodiesAndDoNotEraseFactualCorrectionsOrHeadingScope() {
        val repeated = "The estimate for Café 🌍 is 42 ± 3."
        val correction = "Actually, the estimate is 45 ± 3, and the evidence remains limited."
        val raw = "## France\n\n$repeated\n\nLet me rewrite that.\n\n$repeated\n\n$correction\n\n## Portugal\n\n$repeated"
        val view = CombinedEditorialView.prepare(raw)
        assertEquals(listOf("STANDALONE_SELF_EDITING", "EXACT_REPETITION"), view.omissions.map { it.reason })
        assertEquals(2, Regex(Regex.escape(repeated)).findAll(view.text).count())
        assertTrue(view.text.contains(correction))
        assertFalse(view.text.contains("Let me rewrite"))
        view.spans.forEach { span -> assertEquals(raw.substring(span.sourceStart, span.sourceEnd), view.text.substring(span.viewStart, span.viewEnd)) }
        val duplicate = view.omissions.last()
        assertEquals(raw.substring(duplicate.start, duplicate.end), raw.substring(duplicate.duplicateStart!!, duplicate.duplicateEnd!!))
        assertEquals("Portugal", view.spans.last().topic)
    }

    @Test fun activeWriterReceivesFullHashedBodiesPlusTheMappedEditorialViewAndSectionBudgets() {
        val request = "Write the history of Portugal in exactly 500 words."
        val raw = "Portugal became a kingdom in 1139.\n\nLet me rewrite that.\n\nPortugal became a kingdom in 1139.\n\nPortugal adopted the euro in 1999."
        val source = CombinedModelResponse("a", "A", content = raw, claimExtractionVersion = 3)
        val accepted = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("a"), emptyMap(), request, singlePass = true)
        val prompt = Json.parseToJsonElement(combinedSynthesisPrompt(request, listOf(source), combined = CombinedExecutionContext(accepted, rootBudgetId = "turn"))).jsonObject
        val body = prompt.getValue("contributions").jsonArray.single().jsonObject
        assertEquals(raw, body.getValue("response").jsonPrimitive.content)
        assertEquals(researchHash(raw), body.getValue("body_sha256").jsonPrimitive.content)
        val view = body.getValue("editorial_view").jsonObject
        assertEquals(2, view.getValue("omissions").jsonArray.size)
        val plan = prompt.getValue("section_word_targets").jsonArray
        assertEquals(6, plan.size)
        assertEquals(500, plan.sumOf { it.jsonObject.getValue("target_words").jsonPrimitive.int })
        assertTrue(plan.all { it.jsonObject.getValue("alignment").jsonPrimitive.content == "PERIOD_CANDIDATES_ONLY" })
        assertTrue(prompt.getValue("outline_support_gaps").jsonArray.isNotEmpty())
        assertFalse(CombinedClaimSemantics.records(listOf(source)).any { it.proposition.contains("Let me rewrite") })
        assertTrue(CombinedClaimSemantics.records(listOf(source.copy(claimExtractionVersion = 2))).any { it.proposition.contains("Let me rewrite") })
    }

    @Test fun allocationsRespectMaximumItemsAndUnboundedRequestsWithoutAddingAFillerQuota() {
        val maximum = CombinedAnswerPolicy.from("Give 3 reasons in under 100 words")
        val bounded = CombinedSectionPlan.create(maximum, emptyList())
        assertEquals(3, bounded.size)
        assertEquals(99, bounded.sumOf { it.targetWords!! })
        assertTrue(CombinedSectionPlan.create(CombinedAnswerPolicy.from("Explain the findings"), emptyList()).all { it.targetWords == null })
        val explicit = CombinedSectionPlan.create(CombinedAnswerPolicy.from("Use 101 words; cover these topics: causes, effects"), emptyList())
        assertEquals(listOf("causes", "effects"), explicit.map { it.label })
        assertEquals(listOf(51, 50), explicit.map { it.targetWords })
    }

    @Test fun legacyPromptDoesNotAcquireTheNewEditorialContract() {
        val source = CombinedModelResponse("a", "A", content = "A retained factual detail.")
        val accepted = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("a"), emptyMap(), "Explain the findings", singlePass = false)
        val prompt = Json.parseToJsonElement(combinedSynthesisPrompt(accepted.userPrompt, listOf(source), combined = CombinedExecutionContext(accepted, rootBudgetId = "turn"))).jsonObject
        assertFalse("section_word_targets" in prompt)
        assertFalse("editorial_view_instruction" in prompt)
        assertFalse("editorial_view" in prompt.getValue("contributions").jsonArray.single().jsonObject)
    }
}
