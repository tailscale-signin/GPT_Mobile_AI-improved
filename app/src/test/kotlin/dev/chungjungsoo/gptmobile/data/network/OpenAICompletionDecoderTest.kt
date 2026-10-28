package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAICompletionDecoderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun optionalUsageDoesNotDiscardToolCallsOrValidCostMetadata() {
        val chunk = decodeOpenAICompletion(
            json,
            """{"choices":[{"message":{"tool_calls":[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":-1,"completion_tokens":7,"cost":0.01,"cost_details":{"upstream_inference_cost":0.01},"prompt_tokens_details":[],"completion_tokens_details":{"reasoning_tokens":2}}}"""
        )
        assertEquals("web_search", chunk.choices!!.single().effectiveDelta.toolCalls!!.single().function?.name)
        assertNull(chunk.usage?.promptTokens)
        assertEquals(7, chunk.usage?.completionTokens)
        assertEquals("0.01", chunk.usage?.cost?.content)
        assertEquals("0.01", chunk.usage?.costDetails?.get("upstream_inference_cost").toString())
        assertNull(chunk.usage?.promptTokensDetails)
        assertEquals("2", chunk.usage?.completionTokensDetails?.get("reasoning_tokens").toString())
    }

    @Test
    fun invalidAnswerAndToolStructureStillFail() {
        for (payload in listOf("[]", """{"choices":{}}""", """{"choices":[{"message":{"tool_calls":42}}],"usage":[]} """)) {
            assertThrows(SerializationException::class.java) { decodeOpenAICompletion(json, payload) }
        }
    }

    @Test
    fun missingUsageRemainsUnknownAndInconsistentCountersRemainUntrusted() {
        assertNull(decodeOpenAICompletion(json, """{"choices":[]}""").usage)
        val usage = decodeOpenAICompletion(json, """{"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":12}}""").usage
        assertNull(usage.trustedTotalTokens())
    }
}
