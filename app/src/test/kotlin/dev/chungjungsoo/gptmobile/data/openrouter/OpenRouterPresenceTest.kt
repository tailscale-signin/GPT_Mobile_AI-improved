package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenRouterPresenceTest {
    @Test
    fun ix01RoutingOnlyDoesNotClampCombinedOutput() {
        val (options, routing) = OpenRouterPolicy.decodeProfile("""{"provider":{"allow_fallbacks":true,"sort":"throughput"}}""")
        assertEquals(16384, RequestConstraints(maxOutputTokens = 16384).outputLimit(options?.maxTokens))
        assertEquals("throughput", routing?.sort)
        assertEquals(4096, RequestConstraints(maxOutputTokens = 16384).outputLimit(OpenRouterPolicy.decodeProfile("""{"max_tokens":4096}""").first?.maxTokens))
    }

    @Test
    fun ix03RoutingOnlyDoesNotInjectSamplingDefaults() {
        val options = OpenRouterPolicy.decodeProfile("""{"provider":{"allow_fallbacks":false}}""").first!!
        assertNull(options.maxTokens)
        assertNull(options.stream)
        assertNull(options.temperature)
        assertNull(options.topP)
        assertNull(options.topK)
        assertNull(options.seed)
        assertNull(options.frequencyPenalty)
        assertNull(options.presencePenalty)
        assertNull(options.repetitionPenalty)
    }

    @Test
    fun ix02PresenceAndExplicitZerosSurviveRoundTrip() {
        val raw = """{"max_tokens":null,"stream":false,"temperature":0,"top_p":0,"top_k":0,"seed":0,"frequency_penalty":0,"provider":{"future_routing_field":true}}"""
        val patch = OpenRouterProfileOverrides.parse(raw)
        assertEquals(OpenRouterProfileOverrides.Presence.INHERIT, patch.presence("max_tokens"))
        assertEquals(OpenRouterProfileOverrides.Presence.ABSENT, patch.presence("presence_penalty"))
        assertEquals(OpenRouterProfileOverrides.Presence.EXPLICIT, patch.presence("stream"))
        assertEquals(patch, OpenRouterProfileOverrides.parse(patch.raw.toString()))
        assertEquals(JsonNull, patch.raw["max_tokens"])
        val options = OpenRouterPolicy.decodeProfile(raw).first!!
        assertNull(options.maxTokens)
        assertEquals(false, options.stream)
        assertEquals(0, options.seed)
        assertEquals(0f, options.temperature)
        assertEquals(options, Json.decodeFromString<OpenRouterOptions>(Json.encodeToString(options)))
        assertEquals(JsonPrimitive(false), patch.replacing("stream", JsonPrimitive(false)).raw["stream"])
    }

    @Test
    fun penaltyOnlyOverridesAreNotMistakenForRouting() {
        assertEquals(0f, OpenRouterPolicy.decodeProfile("""{"frequency_penalty":0}""").first?.frequencyPenalty)
        assertEquals(-1f, OpenRouterPolicy.decodeProfile("""{"presence_penalty":-1}""").first?.presencePenalty)
    }

    @Test
    fun malformedTypesFailBeforeDispatch() {
        for (raw in listOf("""{"max_tokens":"4096"}""", """{"stream":0}""", """{"temperature":false}""", """{"seed":1.5}""", """{"provider":[]} """)) {
            assertThrows(IllegalArgumentException::class.java) { OpenRouterPolicy.decodeProfile(raw) }
        }
    }
}
