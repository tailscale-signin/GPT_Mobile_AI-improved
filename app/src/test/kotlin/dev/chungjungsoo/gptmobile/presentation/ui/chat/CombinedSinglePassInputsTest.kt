package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.model.ProfileColorRegistry
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSinglePassInputsTest {
    private fun reply(uid: String, content: String) = MessageV2(id = uid.hashCode(), chatId = 1, linkedMessageId = 1, content = content, platformType = uid, currentRunId = uid)
    private fun run(uid: String, status: String, error: String? = null) = AgentRun(uid, 1, 1, uid.hashCode(), uid, "CUSTOM", "model", status, terminalError = error)

    @Test fun everyFullBodyIncludingLastParagraphIsSentWithItsOwnHash() {
        val sources = (1..6).map { CombinedModelResponse("profile-$it", "Profile $it", content = "Long response $it. " + "Unique detail $it. ".repeat(100) + "Final qualification $it.") }
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), sources.map { it.platformUid }, emptyMap(), "Explain the findings", singlePass = true)
        val ctx = CombinedExecutionContext(requested, rootBudgetId = "root")
        val prompt = Json.parseToJsonElement(combinedSynthesisPrompt(requested.userPrompt, sources, combined = ctx)).jsonObject
        assertEquals("FULL_CONTRIBUTIONS_V1", prompt.getValue("input_representation").jsonPrimitive.content)
        assertEquals("INTEGRATED_RELEVANT_CLAIMS_V2", prompt.getValue("source_preservation").jsonPrimitive.content)
        val bodies = prompt.getValue("contributions").jsonArray
        assertEquals(6, bodies.size)
        sources.forEachIndexed { index, source ->
            val body = bodies[index].jsonObject
            assertEquals(source.platformUid, body.getValue("profile_uid").jsonPrimitive.content)
            assertEquals(source.content, body.getValue("response").jsonPrimitive.content)
            assertEquals(researchHash(source.content), body.getValue("body_sha256").jsonPrimitive.content)
            assertTrue(body.getValue("included_in_input").jsonPrimitive.boolean)
        }
        assertTrue(prompt.getValue("answer_outline").jsonArray.isNotEmpty())
        val claims = prompt.getValue("claim_inventory").jsonArray.map { it.jsonObject }
        assertTrue(claims.isNotEmpty())
        claims.forEach { claim ->
            assertTrue(claim.getValue("text").jsonPrimitive.content.isNotBlank())
            assertTrue("qualifiers" in claim)
            assertTrue(claim.getValue("contributors").jsonArray.isNotEmpty())
            assertTrue(claim.getValue("source_ranges_utf16").jsonArray.isNotEmpty())
        }
        assertTrue(prompt.getValue("preservation_instruction").jsonPrimitive.content.contains("one coherent answer"))
        assertTrue(prompt.getValue("preservation_instruction").jsonPrimitive.content.contains("Explicit user"))
    }

    @Test fun legacyPoliciesDoNotSilentlyOptIntoSourceAssembly() {
        val sources = listOf(CombinedModelResponse("p", "Profile", content = "A saved contribution."))
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("p"), emptyMap(), "Explain the findings", singlePass = false)
        val ctx = CombinedExecutionContext(requested, rootBudgetId = "root")
        val prompt = Json.parseToJsonElement(combinedSynthesisPrompt(requested.userPrompt, sources, combined = ctx)).jsonObject
        assertTrue("source_preservation" !in prompt)
        assertTrue("preservation_instruction" !in prompt)
    }

    @Test fun twoFailuresBlockPrimaryWhileSuccessSurvivesRetryAndRemoval() {
        val success = reply("p", "A useful complete response.")
        val failed = reply("q", "")
        val failedTwo = reply("r", "")
        val runs = mapOf("p" to run("p", "COMPLETED"), "q" to run("q", "FAILED", "HTTP 502"), "r" to run("r", "FAILED", "No answer returned"))
        val responses = listOf(success, failed, failedTwo)
        val failures = combinedProfileFailures(1, "Explain the world", responses, runs, emptyMap(), emptyMap())
        assertEquals(setOf("q", "r"), failures.map { it.profileUid }.toSet())
        assertNull(combinedSynthesisInputs(responses, runs, emptyMap(), emptyMap(), "Explain the world", requireComplete = true))
        val retried = listOf(success, reply("q", "A recovered complete response."))
        val ready = combinedSynthesisInputs(retried, runs + ("q" to run("q", "COMPLETED")), emptyMap(), emptyMap(), "Explain the world", requireComplete = true)!!
        assertEquals(success.content, ready.first().content)
        assertEquals(listOf("p", "q"), ready.map { it.platformUid })
    }

    @Test fun transportCompleteWithEightOfTenItemsRequiresUserDecision() {
        val message = reply("p", (1..8).joinToString("\n") { "$it. Reason $it" })
        val runs = mapOf("p" to run("p", "COMPLETED"))
        assertEquals("The answer is incomplete.", combinedProfileFailures(1, "List 10 reasons", listOf(message), runs, emptyMap(), emptyMap()).single().reason)
        assertNull(combinedSynthesisInputs(listOf(message), runs, emptyMap(), emptyMap(), "List 10 reasons", requireComplete = true))
    }

    @Test fun removedTabCannotReturnFromHistoricalSourceOrLateResponse() {
        val removed = reply("gone", "Late historical result.")
        val primary = reply("p", "Combined answer").copy(currentRunId = "combined-synthesis:one", combinedSources = listOf(CombinedModelResponse("gone", "Removed", content = removed.content), CombinedModelResponse("p", "Primary", content = "Original")))
        val tabs = combinedResponseProfiles(listOf(primary, removed), listOf("p", "gone"), emptyMap(), setOf("p"), false, emptyMap())
        assertEquals(listOf("p"), tabs.map { it.uid })
        assertEquals(2, primary.combinedSources.size)
    }

    @Test fun paletteSurvivesRemovalReorderAndRestorationWithoutRenumbering() {
        val registry = ProfileColorRegistry.ensure(emptyMap(), listOf("a", "b", "c"))
        val all = ProfileAttributionPalette.forSelection(listOf("c", "b", "a"), registry)
        val remaining = ProfileAttributionPalette.forSelection(listOf("c", "b"), registry)
        assertEquals(all.getValue("b"), remaining.getValue("b"))
        assertEquals(all.getValue("c"), remaining.getValue("c"))
    }
}
