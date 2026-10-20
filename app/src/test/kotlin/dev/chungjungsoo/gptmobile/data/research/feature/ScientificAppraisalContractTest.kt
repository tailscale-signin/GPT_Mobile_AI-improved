package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.data.research.researchTextChunks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

    @Test fun fieldGapsPreserveExactPassagesWithoutPromotingUnsupportedValues() {
        val retained = ScientificAppraisalContract.validate(appraisal("12"), version, source, retainFieldGaps = true)
        assertEquals(source, retained.passages.single().quote)
        val field = retained.fields.single()
        assertEquals(Missingness.UNAVAILABLE, field.missingness)
        assertEquals(null, field.value)
        assertEquals(null, field.unit)
        assertTrue(retained.limitations.any { it.contains("analyzed_n") && it.contains("discarded") })
        assertTrue(retained.judgments.all { it.concern == ScientificConcern.UNKNOWN })
        assertTrue(runCatching { ScientificAppraisalContract.validate(appraisal(quote = source.replace("10", "99")), version, source, retainFieldGaps = true) }.isFailure)
        assertTrue(runCatching { ScientificAppraisalContract.validate(appraisal(), version.copy(contentHash = "changed"), source, retainFieldGaps = true) }.isFailure)
        assertEquals("10", ScientificAppraisalContract.validate(appraisal(), version, source, retainFieldGaps = true).fields.single().value)
    }

    @Test fun formattingRecoveryStoresSourceSpellingAndStillRejectsParaphrases() {
        val text = "The analyzed sample included 10 participants. The outcome was Pain  relief."
        val exactVersion = version.copy(contentHash = researchHash(text))
        val formatted = appraisal("pain relief", text).replace("analyzed_n", "outcome")
        val recovered = ScientificAppraisalContract.validate(formatted, exactVersion, text, retainFieldGaps = true)
        assertEquals("Pain  relief", recovered.fields.single().value)
        assertEquals(Missingness.PRESENT, recovered.fields.single().missingness)
        val paraphrase = appraisal("Less pain", text).replace("analyzed_n", "outcome")
        assertEquals(Missingness.UNAVAILABLE, ScientificAppraisalContract.validate(paraphrase, exactVersion, text, retainFieldGaps = true).fields.single().missingness)
    }

    @Test fun repeatedFieldsAreMergedWithoutOverwritingConflictingSourceValues() {
        val text = "Pain scores were 10 in one group and 12 in another."
        val exactVersion = version.copy(contentHash = researchHash(text))
        val first = Json.parseToJsonElement(appraisal("10", text).replace("analyzed_n", "effect")) as JsonObject
        val second = Json.parseToJsonElement(appraisal("12", text).replace("analyzed_n", "effect")) as JsonObject
        val duplicate = JsonObject(first + ("fields" to JsonArray((first["fields"] as JsonArray) + (second["fields"] as JsonArray)))).toString()
        assertTrue(runCatching { ScientificAppraisalContract.validate(duplicate, exactVersion, text) }.isFailure)
        val retained = ScientificAppraisalContract.validate(duplicate, exactVersion, text, retainFieldGaps = true)
        assertEquals(Missingness.CONFLICTING, retained.fields.single().missingness)
        assertEquals(null, retained.fields.single().value)
        assertTrue(retained.passages.isNotEmpty())
    }

    @Test fun formattingRecoveryPreservesExactValuesAndCaseSensitiveUnits() {
        val text = "Pain improved; pain persisted in some patients. Concentration was 10 mM."
        val exactVersion = version.copy(contentHash = researchHash(text))
        val exact = appraisal("pain", text).replace("analyzed_n", "outcome")
        assertEquals("pain", ScientificAppraisalContract.validate(exact, exactVersion, text, retainFieldGaps = true).fields.single().value)
        val root = Json.parseToJsonElement(appraisal("10", text).replace("analyzed_n", "effect")) as JsonObject
        val field = (root["fields"] as JsonArray).single() as JsonObject
        fun withUnit(unit: String) = JsonObject(root + ("fields" to JsonArray(listOf(JsonObject(field + ("unit" to JsonPrimitive(unit))))))).toString()
        val mismatched = ScientificAppraisalContract.validate(withUnit("mm"), exactVersion, text, retainFieldGaps = true).fields.single()
        assertEquals(Missingness.UNAVAILABLE, mismatched.missingness)
        assertEquals(null, mismatched.unit)
        assertEquals("mM", ScientificAppraisalContract.validate(withUnit("mM"), exactVersion, text, retainFieldGaps = true).fields.single().unit)
    }

    @Test fun readableTextDoesNotImplyFullArticleExamination() {
        assertFalse(ArticleSectionCoverage(inspected = setOf("methods", "results", "limitations")).complete)
        assertFalse(ArticleSectionCoverage(inspected = setOf("methods"), figuresAvailable = true, supplementsAvailable = true).complete)
        assertFalse(ArticleSectionCoverage(inspected = setOf("methods", "results", "limitations"), figuresAvailable = true, supplementsAvailable = true).complete)
        assertTrue(ArticleSectionCoverage(inspected = setOf("methods", "results", "limitations"), figuresInspection = ContentInspection.INSPECTED, supplementsInspection = ContentInspection.NOT_APPLICABLE, supplementsReason = "No supplement declared by the complete structured article").complete)
    }

    @Test fun countsCannotUseUnrelatedNumbersOrSubstringsFromTheSamePassage() {
        val text = "The analyzed sample included 100 participants. A product cost 10 dollars. Follow-up was 6 months."
        val exactVersion = version.copy(contentHash = researchHash(text))
        assertEquals("100", ScientificAppraisalContract.validate(appraisal("100", text), exactVersion, text).fields.single().value)
        for (unrelated in listOf("10", "6", "1")) {
            assertTrue(runCatching { ScientificAppraisalContract.validate(appraisal(unrelated, text), exactVersion, text) }.isFailure)
        }
        val grouped = "The analyzed sample included 1,100 participants."
        assertTrue(runCatching { ScientificAppraisalContract.validate(appraisal("100", grouped), version.copy(contentHash = researchHash(grouped)), grouped) }.isFailure)
        for (reported in listOf("The analysis (n=10) was prespecified.", "10 participants were analyzed.")) {
            assertEquals("10", ScientificAppraisalContract.validate(appraisal("10", reported), version.copy(contentHash = researchHash(reported)), reported).fields.single().value)
        }
    }

    @Test fun bodyChunkBoundariesPreserveEmojiAndRoundTripUtf8() {
        val text = "a".repeat(16383) + "🧪" + "b".repeat(16384)
        assertEquals(text, researchTextChunks(text).joinToString("") { it.toByteArray().decodeToString() })
    }

    @Test fun reExaminationCannotOverwriteHistoricalPassageIdentity() {
        val original = ScientificAppraisalContract.validate(appraisal(), version, source)
        val previousIds = original.passages.map { it.passageId }
        val next = namespaceScientificPassages(original, "new-review")
        assertEquals(previousIds, original.passages.map { it.passageId })
        assertTrue(next.passages.none { it.passageId in previousIds })
        assertEquals(original.passages.map { it.quote }, next.passages.map { it.quote })
        val ids = next.passages.map { it.passageId }.toSet()
        next.fields.forEach { field -> assertTrue(Json.decodeFromString<List<String>>(field.passageIdsJson).all { it in ids }) }
        next.judgments.forEach { judgment -> assertTrue(judgment.passageIds.all { it in ids }) }
    }
}
