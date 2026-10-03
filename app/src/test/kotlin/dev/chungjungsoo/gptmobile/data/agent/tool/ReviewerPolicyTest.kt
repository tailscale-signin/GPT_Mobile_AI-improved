package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewerPolicyTest {
    private val valid = """{"review_score":70,"verdict":"PASS","issues":[],"corrections":null}"""

    @Test fun `only complete typed reviews are accepted`() {
        assertEquals(70, parseReviewerAssessment(valid)?.score)
        assertEquals(70, parseReviewerAssessment("```json\n$valid\n```")?.score)
        assertEquals(70, parseReviewerAssessment("```JSON\r\n$valid\r\n```")?.score)
        for (invalid in listOf(
            valid.replace("70", "101"), valid.replace("70", "-1"), valid.replace("70", "70.5"),
            valid.replace("70", "\"70\""), valid.replace("PASS", "APPROVED"),
            valid.replace("PASS", "CORRECTED"), valid.replace(",\"corrections\":null", ""),
            valid.replace("[]", "[1]"), "Here is my review: $valid", "$valid\n$valid",
            "REVIEW_SCORE: 100\nVERDICT: PASS", "{\"review_score\":80}"
        )) {
            assertNull(invalid, parseReviewerAssessment(invalid))
        }
    }

    @Test fun `automatic reviewer is reserved before selecting workers`() {
        val primary = PlatformV2(uid = "primary")
        val worker = PlatformV2(uid = "worker", model = "worker-model", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8080")
        val reviewer = worker.copy(uid = "reviewer", model = "reviewer-model")
        val config = ModelDelegationSettings(enabled = true, reviewerEnabled = true, targetProfileUid = worker.uid)
        val reserved = reservedReviewer(config, listOf(primary, worker, reviewer), primary)
        assertEquals(reviewer.uid, reserved?.uid)
        assertTrue(sameDelegationModel(reviewer.copy(uid = "alias"), reserved))
    }

    @Test fun `reasoning models receive a visible answer allowance within profile limit`() {
        val model = PlatformV2(model = "deepseek-r1", maxTokens = 8192)
        assertEquals(2048, delegationOutputBudget(model, 384))
        assertEquals(512, delegationOutputBudget(model.copy(maxTokens = 512), 384))
        assertEquals(384, delegationOutputBudget(model.copy(model = "plain-text"), 384))
    }
}
