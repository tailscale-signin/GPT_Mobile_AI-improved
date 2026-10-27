package dev.chungjungsoo.gptmobile.data.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompletionUsageNormalizerTest {
    private fun json(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject

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
            CompletionUsageNormalizer.parse(json("""{"usage":{"prompt_tokens":12}}""")),
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
            CompletionUsageNormalizer.parse(json("""{"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}""")),
        )
    }

    @Test
    fun completeUsageIsPreserved() {
        assertEquals(
            CompletionUsageNormalizer.Usage(10, 5, 15),
            CompletionUsageNormalizer.parse(json("""{"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""")),
        )
    }
}
