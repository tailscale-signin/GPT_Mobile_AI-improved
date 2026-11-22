package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubAcceptanceExplanationTest {
    private val empty = buildJsonObject {
        put("binding", "task")
        put("acceptance_ledger", buildJsonObject { put("requirements", JsonArray(emptyList())) })
    }
    private fun contract(criteria: JsonArray) = buildJsonObject {
        put("schema", "github.task-contract.v1")
        put("binding", "task")
        put("acceptance_criteria", criteria)
    }

    @Test fun absentContractEmptyPlanAndEmptyEvaluationRemainDifferentObservations() {
        val absent = GitHubAcceptanceExplanation.record(empty)
        assertEquals(JsonPrimitive("MISSING_CONTRACT"), absent["acceptance_empty_reason"])
        val noPlan = GitHubAcceptanceExplanation.record(empty, contract(JsonArray(emptyList())))
        assertEquals(JsonPrimitive("MISSING_PLAN"), noPlan["acceptance_empty_reason"])
        val noEvaluation = GitHubAcceptanceExplanation.record(empty, contract(JsonArray(listOf(JsonPrimitive("Review the source")))))
        assertEquals(JsonPrimitive("NO_REQUIRED_CRITERIA"), noEvaluation["acceptance_empty_reason"])
        assertEquals(JsonPrimitive("UNDETERMINED"), noEvaluation["acceptance_applicability"])
        assertEquals(JsonPrimitive("NOT_RECORDED"), noEvaluation["acceptance_coverage_state"])
    }

    @Test fun originalFailureTakesPrecedenceWhileFailedExtractionRemainsVisible() {
        val report = JsonObject(empty + (GitHubAcceptancePublicationEvidence.KEY to GitHubAcceptancePublicationEvidence.record(GitHubAcceptancePublicationEvidence.Source.CLAIM_EXTRACTION, 5, "draft", GitHubAcceptancePublicationEvidence.Extraction.FAILED, 1)))
        assertEquals(JsonPrimitive("EXTRACTION_FAILED"), GitHubAcceptanceExplanation.record(report)["acceptance_empty_reason"])
        val failed = JsonObject(report + ("original_failure" to buildJsonObject { put("code", "BILLING_UNAVAILABLE") }))
        val observed = GitHubAcceptanceExplanation.record(failed)
        assertEquals(JsonPrimitive("PRIMARY_FAILURE"), observed["acceptance_empty_reason"])
        assertEquals(JsonPrimitive("FAILED"), observed["acceptance_extraction_state"])
        assertEquals(JsonPrimitive("RECEIPTS_RETAINED"), observed["acceptance_coverage_state"])
        assertEquals(JsonPrimitive(true), observed["acceptance_original_failure_present"])
    }

    @Test fun publicExplanationRejectsFreeTextWrongTypesAndWrongScope() {
        val retained = GitHubAcceptanceExplanation.record(empty)
        assertEquals(retained, GitHubAcceptanceExplanation.retained(retained))
        for (field in GitHubAcceptanceExplanation.fields) {
            assertNull(field, GitHubAcceptanceExplanation.validValue(field, JsonPrimitive("private instructions")))
            assertNull(field, GitHubAcceptanceExplanation.retained(JsonObject(retained + (field to JsonPrimitive("private instructions")))))
        }
        assertNull(GitHubAcceptanceExplanation.validValue("requested_actions", JsonPrimitive("private instructions")))
        assertNull(GitHubAcceptanceExplanation.validValue("acceptance_original_failure_present", JsonPrimitive(1)))
    }
}
