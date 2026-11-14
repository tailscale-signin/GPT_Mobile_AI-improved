package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class OutputAllowanceSerializationTest {
    @Test fun rejectedEnvelopeKeepsTheResolvedDefaultOrProviderCeilingAndItsOriginalSourceObservation() {
        val runtime = dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint("profile", "route", "credential", "model", "model", "runtime", "template", "allocation")
        val payload = """{"messages":[{"role":"user","content":"${"unchanged input ".repeat(1500)}"}]}"""
        for ((requested, ceiling, expected) in listOf(Triple(null, null, 4096L), Triple(16000L, 1024L, 1024L))) {
            val capacity = dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot(runtime, 5632, outputTokens = ceiling)
            val observation = dev.chungjungsoo.gptmobile.data.context.CapacityAdmissionObservation(capacity, routeContextTokens = 5632, configuredContextTokens = 32768)
            val failure = assertThrows(dev.chungjungsoo.gptmobile.data.context.RequestCapacityException::class.java) {
                resolveOutputAllowance(payload, requested, capacity, observation)
            }
            assertEquals(expected, failure.outputTokens)
            assertEquals(expected, failure.budget?.outputAllowanceTokens)
            assertEquals(observation, failure.capacityObservation)
            assertEquals(dev.chungjungsoo.gptmobile.data.context.RequestAdmission.estimate(payload).promptTokens, failure.promptTokens)
        }
    }

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
