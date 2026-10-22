package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.CombinedClaimSemantics
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedRequestedPolicy
import dev.chungjungsoo.gptmobile.data.agent.CurrentRequestContract
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactCombinedInputTest {
    @Test fun greetingsAndTestsDoNotImportOldTopicEvenFromSameLine() {
        assertEquals("GREETING", CurrentRequestContract.from("Hello!").intent)
        assertEquals("TEST", CurrentRequestContract.from("test").intent)
        assertEquals("SUBSTANTIVE", CurrentRequestContract.from("Test the database migration").intent)
        assertEquals("Hello!", CombinedContributionQuality.currentRequestContribution("hello", "Hello! Hydrogen inhalation may have medical effects."))
        assertEquals("Test received.", CombinedContributionQuality.currentRequestContribution("test", "Test received. The prior study enrolled 25 participants."))
        assertEquals("", CombinedContributionQuality.currentRequestContribution("test", "The report's clinical conclusion is uncertain."))
        assertEquals("Useful detail.", CombinedContributionQuality.sanitizeContribution("Useful detail.\n[PARTIAL_DELEGATE_OUTPUT: truncated]\n<tool_call>{}"))
    }

    @Test fun compactProvenanceRetainsEveryUniquePropositionAndAllMaterialQualifiers() {
        val first = "In 2020, a pilot randomized trial of inhalation in 25 adult patients found no significant benefit."
        val second = "The evidence remains preliminary and does not establish clinical efficacy."
        val sources = listOf(CombinedModelResponse("p", "P", content = "$first $second"), CombinedModelResponse("h", "H", content = first))
        val context = CombinedExecutionContext(CombinedRequestedPolicy(participantUids = listOf("p", "h"), requestedModels = mapOf("p" to "m1", "h" to "m2"), requestedPolicyDigest = "policy", userPrompt = "Compare evidence"), rootBudgetId = "root", chatId = 1)
        val full = CombinedClaimSemantics.records(sources)
        val prompt = Json.parseToJsonElement(combinedSynthesisPrompt("Compare evidence", sources, combined = context)).jsonObject
        val claims = prompt.getValue("claim_inventory").jsonArray.map { it.jsonObject }
        assertEquals(full.map { it.proposition }.toSet(), claims.map { it.getValue("text").jsonPrimitive.content }.toSet())
        assertEquals(full.flatMap { it.candidateIds }.toSet(), claims.flatMap { it.getValue("aliases").jsonArray.map { alias -> alias.jsonPrimitive.content } }.toSet())
        assertTrue(prompt.toString().contains("NULL_EFFECT"))
        assertTrue(prompt.toString().contains("INHALATION"))
        assertTrue(prompt.toString().contains("25"))
        assertTrue(full.all { it.occurrences.all { occurrence -> occurrence.bodyDigest.length == 64 } })
        assertFalse(claims.any { it.containsKey("source_spans") })
        assertTrue(prompt.getValue("audit_provenance").jsonPrimitive.content.contains("immutable input snapshot"))
    }
}
