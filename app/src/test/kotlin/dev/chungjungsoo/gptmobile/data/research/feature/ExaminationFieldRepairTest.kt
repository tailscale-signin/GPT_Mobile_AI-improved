package dev.chungjungsoo.gptmobile.data.research.feature

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaminationFieldRepairTest {
    private val original = """{"fields":[{"name":"good"},{"name":"bad"}]}"""

    @Test fun onlyFailedRowsChangeAndUnrelatedValidDataSurvivesRepair() = runBlocking {
        val result = validatedExaminationOutput("source", { prompt, attempt ->
            if (attempt == 1) {
                original
            } else {
                assertTrue(prompt.contains("EXAMINATION_FIELD_REPAIR_V1"))
                """{"repairs":[{"path":"fields[1]","value":{"name":"fixed"}}]}"""
            }
        }, { _, _, _ -> }) { reply ->
            val root = Json.parseToJsonElement(reply) as JsonObject
            if (root.toString().contains("bad")) throw ExaminationFieldValidationException(listOf(ExaminationFieldIssue("fields[1]", "unsupported name")))
            root
        }
        val rows = result["fields"] as JsonArray
        assertEquals((Json.parseToJsonElement(original) as JsonObject)["fields"]?.let { (it as JsonArray)[0] }, rows[0])
        assertTrue(rows[1].toString().contains("fixed"))
    }

    @Test fun aRepairCannotChangeOtherRowsOrSmuggleExtraFields() {
        val issues = listOf(ExaminationFieldIssue("fields[1]", "invalid"))
        assertTrue(runCatching { applyExaminationFieldRepair(original, issues, """{"repairs":[{"path":"fields[0]","value":{"name":"changed"}}]}""") }.isFailure)
        assertTrue(runCatching { applyExaminationFieldRepair(original, issues, """{"repairs":[{"path":"fields[1]","value":{}}],"tools":true}""") }.isFailure)
    }
}
