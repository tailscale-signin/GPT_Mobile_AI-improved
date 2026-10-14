package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutputAllowanceSerializationTest {
    @Test fun physicalProviderFieldsReceiveResolvedPositiveCap() {
        val samples = listOf(
            Triple("OPENROUTER", "{\"messages\":[],\"max_tokens\":0}", "max_tokens"),
            Triple("ANTHROPIC", "{\"messages\":[],\"max_tokens\":16000}", "max_tokens"),
            Triple("OPENAI", "{\"input\":[]}", "max_output_tokens"),
            Triple("OPENAI", "{\"messages\":[]}", "max_completion_tokens"),
            Triple("CUSTOM", "{\"messages\":[]}", "max_tokens")
        )
        for ((provider, payload, field) in samples) {
            val root = Json.parseToJsonElement(payload) as JsonObject
            val bounded = applyOutputAllowance(root, provider, 4096)
            assertEquals(JsonPrimitive(4096L), bounded[field])
            assertEquals(root["messages"], bounded["messages"])
            assertEquals(root["input"], bounded["input"])
        }
    }

    @Test fun geminiPreservesSamplerAndTools() {
        val original = Json.parseToJsonElement("{\"generationConfig\":{\"temperature\":0.5},\"tools\":[]}") as JsonObject
        val bounded = applyOutputAllowance(original, "GOOGLE", 512)
        val generation = bounded["generationConfig"] as JsonObject
        assertEquals(JsonPrimitive(512L), generation["maxOutputTokens"])
        assertEquals(JsonPrimitive(0.5), generation["temperature"])
        assertEquals(original["tools"], bounded["tools"])
        assertFalse(bounded.containsKey("max_tokens"))
    }
}
