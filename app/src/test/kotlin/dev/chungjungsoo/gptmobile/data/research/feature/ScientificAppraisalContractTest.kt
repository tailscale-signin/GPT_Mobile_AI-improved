package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.data.research.researchTextChunks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScientificAppraisalContractTest {
    private val source = "Twelve participants were randomized. The analyzed sample included 10 participants. Follow-up was 6 months."
    private val version = ResearchWorkVersion("version", "work", researchHash(source), sourceUrl = "https://example.org", retrievedAt = 1, coverage = ArticleCoverage.PARTIAL_TEXT, authority = "public", publicSource = true)
    private fun appraisal(value: String = "10", quote: String = source) = buildJsonObject {
        put("schemaVersion", 1)
        put("design", "RANDOMIZED_TRIAL")
        put(
            "passages",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "P1")
                        put("quote", quote)
                        put("locator", "Methods, paragraph 1")
                        put("start", 0)
                    }
                )
            )
        )
        put(
            "fields",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("name", "analyzed_n")
                        put("outcomeKey", "overall")
                        put("value", value)
                        put("missingness", "PRESENT")
                        put("passageIds", JsonArray(listOf(JsonPrimitive("P1"))))
                    }
                )
            )
        )
        put("judgments", JsonArray(emptyList()))
        put("limitations", JsonArray(listOf(JsonPrimitive("Randomized and analyzed counts differ; missing data explanation requires review"))))
    }.toString()

    @Test fun extractsRequireExactVersionQuotesAndValues() {
        val parsed = ScientificAppraisalContract.validate(appraisal(), version, source)
        assertEquals("10", parsed.fields.single().value)
        assertTrue(parsed.judgments.all { it.concern == ScientificConcern.UNKNOWN })
        assertTrue(runCatching { ScientificAppraisalContract.validate(appraisal("12"), version, source) }.isFailure)
        assertTrue(runCatching { ScientificAppraisalContract.validate(appraisal(quote = source.replace("10", "99")), version, source) }.isFailure)
        assertTrue(runCatching { ScientificAppraisalContract.validate(appraisal(), version.copy(contentHash = "changed"), source) }.isFailure)
    }

    @Test fun readableTextDoesNotImplyFullArticleExamination() {
        assertFalse(ArticleSectionCoverage(inspected = setOf("methods", "results", "limitations")).complete)
        assertFalse(ArticleSectionCoverage(inspected = setOf("methods"), figuresAvailable = true, supplementsAvailable = true).complete)
        assertTrue(ArticleSectionCoverage(inspected = setOf("methods", "results", "limitations"), figuresAvailable = true, supplementsAvailable = true).complete)
    }

    @Test fun bodyChunkBoundariesPreserveEmojiAndRoundTripUtf8() {
        val text = "a".repeat(16383) + "🧪" + "b".repeat(16384)
        assertEquals(text, researchTextChunks(text).joinToString("") { it.toByteArray().decodeToString() })
    }
}
