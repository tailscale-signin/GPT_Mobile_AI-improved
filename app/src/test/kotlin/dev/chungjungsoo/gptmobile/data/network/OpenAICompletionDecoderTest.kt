package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAICompletionDecoderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun successfulRepairRetainsBothAttemptReceiptsWithoutChangingFinalUsage() {
        val metadata = """{"schema_version":1,"attempt_receipts":[{"attempt":1,"output_allowance":50,"finish_reason":"LENGTH","visible_output_bytes":0,"reasoning_output_bytes":11,"terminal_error_code":"empty_completion","terminal_status_code":200,"backend_response_observed":true,"status":"COMPLETED","text":"private reasoning"},{"attempt":2,"output_allowance":39,"finish_reason":"STOP","visible_output_bytes":9,"reasoning_output_bytes":0,"terminal_error_code":null,"terminal_status_code":200,"backend_response_observed":true,"status":"COMPLETED"}],"attempt_usage":[{"completion_tokens":11},{"completion_tokens":7}]}"""
        val body = """{"choices":[{"message":{"content":"Recovered"},"finish_reason":"stop"}],"gateway_recovery":$metadata} """
        val unknown = decodeOpenAICompletion(json, body)
        assertEquals("Recovered", unknown.choices!!.single().effectiveDelta.content)
        assertNull(unknown.usage)
        val receipts = unknown.gatewayRecovery!!.getValue("attempt_receipts") as kotlinx.serialization.json.JsonArray
        assertEquals(2, receipts.size)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(11), (receipts.first() as kotlinx.serialization.json.JsonObject)["reasoning_output_bytes"])
        org.junit.Assert.assertFalse(unknown.gatewayRecovery.toString().contains("private"))
        val measured = decodeOpenAICompletion(json, body.trim().dropLast(1) + """, "usage":{"prompt_tokens":12,"completion_tokens":18,"total_tokens":30}}""")
        assertEquals(12, measured.usage?.promptTokens)
        assertEquals(18, measured.usage?.completionTokens)
        assertEquals(30, measured.usage?.totalTokens)
        assertEquals(unknown.gatewayRecovery, measured.gatewayRecovery)
    }

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
        val legacy = decodeOpenAICompletion(json, """{"choices":[]}""")
        assertNull(legacy.usage)
        assertNull(legacy.gatewayRecovery)
        val usage = decodeOpenAICompletion(json, """{"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":12}}""").usage
        assertNull(usage.trustedTotalTokens())
    }

    @Test fun malformedOptionalGatewayObservationsDoNotDiscardAnswerOrToolCalls() {
        val chunk = decodeOpenAICompletion(json, """{"choices":[{"message":{"content":"retained","tool_calls":[{"id":"call_1","type":"function","function":{"name":"search","arguments":"{}"}}]}}],"gateway_timing":{"backend_first_output_ms":{}},"gateway_provenance":{"schema_version":[]},"gateway_recovery":{"schema_version":1,"attempt_receipts":"invalid"}}""")
        assertEquals("retained", chunk.choices!!.single().effectiveDelta.content)
        assertEquals("search", chunk.choices!!.single().effectiveDelta.toolCalls!!.single().function?.name)
        assertNull(chunk.gatewayTiming)
        assertNull(chunk.gatewayProvenance)
        assertNull(chunk.gatewayRecovery)
    }
}
