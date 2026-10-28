package dev.chungjungsoo.gptmobile.data.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompletionUsageNormalizerTest {
    private fun json(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject

    @Test
    fun normalizeRetainsChoicesWhenUsageHasTheWrongShape() {
        for (usage in listOf("null", "[]", "false", "42", "\"unavailable\"")) {
            val original = json("""{"choices":[{"message":{"content":"assessment"}}],"usage":$usage}""")
            val normalized = CompletionUsageNormalizer.normalize(original)
            assertEquals(original["choices"], normalized["choices"])
            assertEquals(kotlinx.serialization.json.JsonNull, normalized["usage"])
        }
    }

    @Test
    fun normalizeKeepsValidCountersAndCostsButDiscardsMalformedOptionalFields() {
        val normalized = CompletionUsageNormalizer.normalize(json("""{"usage":{"prompt_tokens":"10","completion_tokens":5,"total_tokens":{},"cost":0.01,"cost_details":{"fee":0.01},"prompt_tokens_details":[],"completion_tokens_details":{"reasoning_tokens":2}}}"""))
        assertEquals(
            json("""{"usage":{"completion_tokens":5,"cost":0.01,"cost_details":{"fee":0.01},"completion_tokens_details":{"reasoning_tokens":2}}}"""),
            normalized
        )
        assertEquals(normalized, CompletionUsageNormalizer.normalize(normalized))
    }

    @Test
    fun normalizeDoesNotInventUsageOrRepairMalformedAnswerStructure() {
        val original = json("""{"choices":42}""")
        assertEquals(original, CompletionUsageNormalizer.normalize(original))
    }

    @Test
    fun missingUsageDoesNotInvalidateResponse() {
        assertNull(CompletionUsageNormalizer.parse(json("""{"choices":[{"message":{"content":"ok"}}]}""")))
    }

    @Test
    fun nullUsageIsOptional() {
        assertNull(CompletionUsageNormalizer.parse(json("""{"usage":null}""")))
    }

    @Test
    fun incompleteUsageRetainsValidCounters() {
        assertEquals(
            CompletionUsageNormalizer.Usage(12, null, null),
            CompletionUsageNormalizer.parse(json("""{"usage":{"prompt_tokens":12}}"""))
        )
    }

    @Test
    fun malformedAndNegativeCountersAreIgnored() {
        assertNull(CompletionUsageNormalizer.parse(json("""{"usage":{"prompt_tokens":-1,"completion_tokens":"unknown"}}""")))
    }

    @Test
    fun quotedCountersAreNotTreatedAsMeasuredUsage() {
        assertNull(CompletionUsageNormalizer.parse(json("""{"usage":{"prompt_tokens":"12","total_tokens":"15"}}""")))
    }

    @Test
    fun fractionalAndBooleanCountersAreIgnored() {
        assertNull(CompletionUsageNormalizer.parse(json("""{"usage":{"prompt_tokens":1.5,"completion_tokens":true}}""")))
    }

    @Test
    fun zeroCountersAreValid() {
        assertEquals(
            CompletionUsageNormalizer.Usage(0, 0, 0),
            CompletionUsageNormalizer.parse(json("""{"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}"""))
        )
    }

    @Test
    fun completeUsageIsPreserved() {
        assertEquals(
            CompletionUsageNormalizer.Usage(10, 5, 15),
            CompletionUsageNormalizer.parse(json("""{"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""))
        )
    }
}
