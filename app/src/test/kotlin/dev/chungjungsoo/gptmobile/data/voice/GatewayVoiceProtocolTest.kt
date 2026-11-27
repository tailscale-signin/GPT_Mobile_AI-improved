package dev.chungjungsoo.gptmobile.data.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class GatewayVoiceProtocolTest {
    private val model = "a".repeat(64)
    private val words = intArrayOf(1, 2)

    @Test fun `speech connection retains selected origin but rejects credential and query URLs`() {
        assertEquals("https://pc.example:8443", GatewayVoiceProtocol.endpoint("https://pc.example:8443/v1/"))
        for (url in listOf("https://user:token@pc.example", "https://pc.example?key=secret", "https://pc.example/unknown", "file:///tmp/model", "https://pc.example#token")) {
            assertThrows(IllegalArgumentException::class.java) { GatewayVoiceProtocol.endpoint(url) }
        }
    }

    @Test fun `available CUDA installation without executed kernels cannot qualify`() {
        val capable = envelope().put("ready", true).put("executed_cuda_nodes", 0)
        assertThrows(IllegalStateException::class.java) { GatewayVoiceProtocol.capabilityModel(capable) }
        capable.put("executed_cuda_nodes", 2)
        assertEquals(model, GatewayVoiceProtocol.capabilityModel(capable))
        capable.put("execution_provider", "CPUExecutionProvider")
        assertThrows(IllegalStateException::class.java) { GatewayVoiceProtocol.capabilityModel(capable) }
    }

    @Test fun `unready capabilities keep only an allowlisted readiness reason`() {
        val unavailable = envelope().put("ready", false).put("reason", "VOICE_WARMING")
        unavailable.remove("model_sha256")
        val failure = assertThrows(GatewayVoiceCapabilityUnavailable::class.java) { GatewayVoiceProtocol.capabilityModel(unavailable) }
        assertEquals("VOICE_WARMING", failure.reason)
        unavailable.put("reason", "PRIVATE_REMOTE_TEXT")
        assertEquals("VOICE_UNAVAILABLE", assertThrows(GatewayVoiceCapabilityUnavailable::class.java) { GatewayVoiceProtocol.capabilityModel(unavailable) }.reason)
    }

    @Test fun `string readiness cannot claim successful CUDA qualification`() {
        assertThrows(IllegalStateException::class.java) { GatewayVoiceProtocol.capabilityModel(envelope().put("ready", "true")) }
    }

    @Test fun `aligned PCM and durations survive the Gateway wire format`() {
        val result = GatewayVoiceProtocol.response(response(), "turn", model, 3, words)
        assertEquals(1800, result.samples.size)
        assertEquals(3L, result.durations.sum())
        assertEquals(0.25f, result.samples[0])
    }

    @Test fun `a different frontend mapping or duration scale cannot qualify`() {
        for (bad in listOf(envelope().put("frontend_symbols_sha256", "b".repeat(64)), envelope().put("frames_per_duration", 300))) {
            bad.put("ready", true)
            assertThrows(IllegalStateException::class.java) { GatewayVoiceProtocol.capabilityModel(bad) }
        }
    }

    @Test fun `changed model request identity and caption ownership are rejected`() {
        for (bad in listOf(response().put("model_sha256", "b".repeat(64)), response().put("request_id", "another-turn"), response().put("word_tokens", JSONArray(listOf(1, 3))), response().put("executed_cuda_nodes", 0))) {
            assertThrows(IllegalStateException::class.java) { GatewayVoiceProtocol.response(bad, "turn", model, 3, words) }
        }
    }

    @Test fun `inconsistent negative fractional and oversized durations are rejected`() {
        for (durations in listOf(listOf(1, 1, 2), listOf(1, -1, 3), listOf(1, 1.5, 1), listOf(1, 1201, 1))) {
            val bad = response().put("durations", JSONArray(durations))
            assertThrows(IllegalStateException::class.java) { GatewayVoiceProtocol.response(bad, "turn", model, 3, words) }
        }
    }

    @Test fun `nonfinite and incomplete PCM is rejected before AudioTrack`() {
        val invalid = pcm().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN) }
        for (bytes in listOf(invalid, pcm().copyOf(7199))) {
            val bad = response().put("pcm_f32le", Base64.getEncoder().encodeToString(bytes))
            assertThrows(IllegalStateException::class.java) { GatewayVoiceProtocol.response(bad, "turn", model, 3, words) }
        }
    }

    @Test fun `malformed pronunciation spans and unsupported speed never leave the phone`() {
        assertThrows(IllegalArgumentException::class.java) { GatewayVoiceProtocol.request("turn", model, longArrayOf(0, 1, 0), intArrayOf(2, 1), 0, 1f) }
        assertThrows(IllegalArgumentException::class.java) { GatewayVoiceProtocol.request("turn", model, longArrayOf(0, 1, 0), words, 0, Float.NaN) }
    }

    private fun envelope() = JSONObject().put("protocol", GatewayVoiceProtocol.PROTOCOL).put("frontend_id", GatewayVoiceProtocol.FRONTEND)
        .put("frontend_symbols_sha256", GatewayVoiceProtocol.FRONTEND_SYMBOLS_SHA256).put("frames_per_duration", 600)
        .put("sample_rate", 24_000).put("model_sha256", model).put("execution_provider", "CUDAExecutionProvider").put("executed_cuda_nodes", 2)
    private fun response() = envelope().put("request_id", "turn").put("durations", JSONArray(listOf(1, 1, 1)))
        .put("word_tokens", JSONArray(words.toList())).put("pcm_f32le", Base64.getEncoder().encodeToString(pcm()))
    private fun pcm() = ByteBuffer.allocate(1800 * 4).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(1800) { putFloat(0.25f) } }.array()
}
