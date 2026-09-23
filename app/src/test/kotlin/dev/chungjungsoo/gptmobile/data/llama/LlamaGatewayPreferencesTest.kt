package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.llama.AdvancedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaGatewayPreferencesTest {

    @Test
    fun `high performance defaults produce mobile Gateway headers`() {
        val headers = LlamaGatewayPreferences.headers(
            settings = AdvancedSettings(),
            platformUid = "llama-platform",
            chatId = 42,
            clientVersion = "test"
        )

        assertEquals("gpt-mobile-android", headers["X-Gateway-Client"])
        assertEquals("test", headers["X-Gateway-Client-Version"])
        assertEquals("high_performance", headers["X-Gateway-Performance-Profile"])
        assertEquals("512", headers["X-Gateway-Cache-Reuse"])
        assertEquals("true", headers["X-Gateway-Slot-Affinity"])
        assertEquals("160", headers["X-Gateway-Max-Rounds"])
        assertEquals("250", headers["X-Gateway-Progress-Poll-Ms"])
        assertFalse(headers.containsKey("X-Gateway-Slot-Count"))
    }

    @Test
    fun `explicit slot count and custom routing are forwarded`() {
        val headers = LlamaGatewayPreferences.headers(
            settings = AdvancedSettings(
                gatewaySlotCount = 4,
                gatewayToolRouting = "local",
                gatewayProgressDetail = "debug",
                gatewayCacheReuse = 1024
            ),
            platformUid = "llama-platform",
            chatId = 7,
            clientVersion = "test"
        )

        assertEquals("4", headers["X-Gateway-Slot-Count"])
        assertEquals("local", headers["X-Gateway-Tool-Routing"])
        assertEquals("debug", headers["X-Gateway-Progress-Detail"])
        assertEquals("1024", headers["X-Gateway-Cache-Reuse"])
    }

    @Test
    fun `disabled Gateway integration emits no control headers`() {
        val headers = LlamaGatewayPreferences.headers(
            settings = AdvancedSettings(gatewayIntegrationEnabled = false),
            platformUid = "llama-platform",
            chatId = 1,
            clientVersion = "test"
        )

        assertTrue(headers.isEmpty())
    }

    @Test
    fun `chat affinity is deterministic and isolated between chats`() {
        val a = LlamaGatewayPreferences.affinityKey("platform-a", 10)
        val b = LlamaGatewayPreferences.affinityKey("platform-a", 10)
        val c = LlamaGatewayPreferences.affinityKey("platform-a", 11)

        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(32, a.length)
    }

    @Test
    fun `unsafe values are clamped before leaving Android`() {
        val headers = LlamaGatewayPreferences.headers(
            settings = AdvancedSettings(
                gatewayCacheReuse = 99_999,
                gatewayMaxRounds = 99_999,
                gatewayProgressPollMs = 1,
                gatewayStreamHeartbeatSeconds = 0,
                gatewayJobTimeoutSeconds = 99_999
            ),
            platformUid = "llama-platform",
            chatId = 1,
            clientVersion = "test"
        )

        assertEquals("8192", headers["X-Gateway-Cache-Reuse"])
        assertEquals("500", headers["X-Gateway-Max-Rounds"])
        assertEquals("100", headers["X-Gateway-Progress-Poll-Ms"])
        assertEquals("1", headers["X-Gateway-Stream-Heartbeat"])
        assertEquals("14400", headers["X-Gateway-Job-Timeout"])
    }
}
