package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.CombinedLayoutContract
import dev.chungjungsoo.gptmobile.data.agent.CombinedWriterPolicy
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedDesignContractTest {
    private val table = "| Topic | Main point | Qualification |\n| --- | --- | --- |\n| First | The first finding is useful. | Evidence is limited. |\n| Second | The second finding is useful. | Only in adults. |\n| Third | The third finding is useful. | Results remain preliminary. |"
    private val sources = listOf(CombinedModelResponse("p", "Primary", content = table, claimExtractionVersion = 2))

    @Test fun comparableRecordsFreezeATablePreferenceWithoutOverridingUserFormat() {
        val plan = CombinedLayoutContract.from("Summarize the findings", sources)
        assertTrue(plan.tableExpected)
        assertEquals(3, plan.columns.size)
        assertEquals("EXPECTED_COMPARISON_TABLE_MISSING", plan.checks("A readable prose answer.").single().id)
        assertFalse(plan.checks("A readable prose answer.").single().material)
        assertEquals("PASS", plan.checks(table).single().result)
        assertFalse(CombinedLayoutContract.from("Summarize in one paragraph", sources).tableExpected)
        assertFalse(CombinedLayoutContract.from("Use bullets in under 60 words", sources).tableExpected)
        val prompt = combinedSynthesisPrompt("Summarize the findings", sources)
        assertEquals(plan, CombinedLayoutContract.fromPrompt(prompt))
        assertEquals(null, CombinedLayoutContract.fromPrompt("Legacy frozen input"))
    }

    @Test fun greetingPromptRetainsFullContributorsWithoutLargeClaimInventory() {
        val replies = listOf(CombinedModelResponse("alpha", "Alpha", content = "Hello!"), CombinedModelResponse("beta", "Beta", content = "Hi! How can I help you today?"))
        val prompt = combinedSynthesisPrompt("hi", replies, deliveryProtocol = "PASSAGES_V3", targetRevisionId = "revision")
        val parsed = Json.parseToJsonElement(prompt).jsonObject
        assertTrue(prompt.length < 2500)
        assertFalse("claim_inventory" in parsed)
        assertFalse("potential_conflicts" in parsed)
        assertEquals(replies.map { it.content }, parsed.getValue("contributions").jsonArray.map { it.jsonObject.getValue("response").jsonPrimitive.content })
        assertTrue(combinedEditorialInstruction("PASSAGES_V3", "hi").length < 1000)
        assertTrue(combinedEditorialInstruction("PASSAGES_V3", "hi").contains("\"type\":\"end\""))
        val unsuitable = PlatformV2(uid = "horde", name = "Horde", compatibleType = ClientType.FREE, apiUrl = FreeAiProvider.AI_HORDE.apiUrl, model = FreeAiProvider.AI_HORDE.model, maxTokens = 256)
        assertNotNull(CombinedWriterPolicy.unavailableReason(unsuitable, "hi"))
    }

    @Test fun failedLatestRetrySelectsThePriorCompleteContributionAndBindsBothAttempts() {
        val prior = "The earlier complete response preserves the finding and its qualification."
        val current = MessageV2(content = "", platformType = "p", currentRunId = "latest", revisions = listOf(AssistantRevision(prior, createdAt = 1, runId = "best")))
        val runs = mapOf("latest" to AgentRun("latest", 1, 1, 2, "p", "CUSTOM", "model", "FAILED"), "best" to AgentRun("best", 1, 1, 2, "p", "CUSTOM", "model", "COMPLETED"))
        val result = selectCombinedContribution(current, runs, "Explain the finding")
        assertEquals(prior, result.message.content)
        assertEquals("latest", result.latestAttemptId)
        assertEquals("best", result.bestUsableAttemptId)
        val frozen = combinedSynthesisInputs(listOf(current), runs, emptyMap(), emptyMap(), "Explain the finding")!!.single()
        assertEquals(prior, frozen.content)
        assertEquals("latest", frozen.latestAttemptId)
        assertEquals("best", frozen.selectedContributionAttemptId)
    }

    @Test fun completeLatestResponseStillWinsOverTheInspectedHistoricalRevision() {
        val current = MessageV2(content = "The new complete finding retains its qualification.", platformType = "p", currentRunId = "latest", revisions = listOf(AssistantRevision("Old complete finding.", createdAt = 1, runId = "best")), activeRevisionIndex = 0)
        val runs = listOf("latest", "best").associateWith { AgentRun(it, 1, 1, 2, "p", "CUSTOM", "model", "COMPLETED") }
        assertEquals(current.content, selectCombinedContribution(current, runs, "Explain the finding").message.content)
    }

    @Test fun queueCompletionWithAClippedTailRemainsOnlyPartial() {
        val result = CombinedContributionUsability.assess("COMPLETED", "The council approved a comprehensive plan for new homes but the final decision depended on", true, "Explain the decision", finishReason = "queue_complete")
        assertTrue(result.admitted)
        assertTrue(result.usablePartial)
        assertTrue(result.structuralUsability <= 70)
        assertEquals("PARTIAL", result.completeness)
    }

    @Test fun paletteAndMappingDiagnosticsEmitOnlyOnIdentityOrDigestChanges() {
        val identity = "test-${java.util.UUID.randomUUID()}"
        assertTrue(CombinedAttributionDiagnostics.changed(identity, "first"))
        assertFalse(CombinedAttributionDiagnostics.changed(identity, "first"))
        assertTrue(CombinedAttributionDiagnostics.changed(identity, "edited"))
    }
}
