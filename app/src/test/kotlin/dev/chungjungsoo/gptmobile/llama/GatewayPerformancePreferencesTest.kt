package dev.chungjungsoo.gptmobile.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPerformancePreferencesTest {
    @Test
    fun `pre gateway settings acquire defaults without losing existing choices`() {
        val settings = AdvancedSettingsJson.decode(
            """{"serverUrl":"http://gateway.local:8081/v1","nCtx":131072,"useMmap":false}"""
        )

        assertEquals("http://gateway.local:8081/v1", settings.serverUrl)
        assertEquals(131072, settings.nCtx)
        assertFalse(settings.useMmap)
        assertEquals("true", headers(settings)["X-Gateway-Stable-Tool-Surface"])
        assertEquals("1024", headers(settings)["X-Gateway-Intermediate-Max-Tokens"])
        assertEquals("24", headers(settings)["X-Gateway-Soft-Synthesis-Round"])
        assertEquals("24000", headers(settings)["X-Gateway-Result-Char-Limit"])
    }

    @Test
    fun `v10 settings preserve explicit false and zero while adding v10_1 defaults`() {
        val settings = AdvancedSettingsJson.decode(
            """{"gatewayPerformanceProfile":"balanced","gatewayCachePrompt":false,"gatewayToolSurfaceLimit":0,"gatewayCacheReuse":0}"""
        )

        assertEquals("balanced", headers(settings)["X-Gateway-Performance-Profile"])
        assertEquals("false", headers(settings)["X-Gateway-Cache-Prompt"])
        assertEquals("0", headers(settings)["X-Gateway-Tool-Surface-Limit"])
        assertEquals("0", headers(settings)["X-Gateway-Cache-Reuse"])
        assertEquals("true", headers(settings)["X-Gateway-Stable-Tool-Surface"])
        assertEquals("1024", headers(settings)["X-Gateway-Intermediate-Max-Tokens"])
        assertEquals("24", headers(settings)["X-Gateway-Soft-Synthesis-Round"])
        assertEquals("24000", headers(settings)["X-Gateway-Result-Char-Limit"])
    }

    @Test
    fun `v10_1 choices survive saving and reopening settings`() {
        val chosen = AdvancedSettings(
            gatewayStableToolSurface = false,
            gatewayIntermediateMaxTokens = 2048,
            gatewaySoftSynthesisRound = 40,
            gatewayResultCharLimit = 64000
        )
        val reopened = AdvancedSettingsJson.decode(AdvancedSettingsJson.encode(chosen))

        assertEquals(chosen, reopened)
        assertEquals("false", headers(reopened)["X-Gateway-Stable-Tool-Surface"])
        assertEquals("2048", headers(reopened)["X-Gateway-Intermediate-Max-Tokens"])
        assertEquals("40", headers(reopened)["X-Gateway-Soft-Synthesis-Round"])
        assertEquals("64000", headers(reopened)["X-Gateway-Result-Char-Limit"])
    }

    @Test
    fun `null values and damaged JSON recover safe settings`() {
        for (raw in listOf(null, "", "null", "[]", "{broken")) {
            assertEquals(AdvancedSettings(), AdvancedSettingsJson.decode(raw))
        }
        val settings = AdvancedSettingsJson.decode(
            """{"gatewayPerformanceProfile":null,"gatewayStableToolSurface":null,"gatewayIntermediateMaxTokens":null,"nCtx":32768}"""
        )
        assertEquals("turbo", headers(settings)["X-Gateway-Performance-Profile"])
        assertTrue(settings.gatewayStableToolSurface)
        assertEquals(1024, settings.gatewayIntermediateMaxTokens)
        assertEquals(32768, settings.nCtx)
    }

    @Test
    fun `out of range saved budgets cannot escape header limits`() {
        val settings = AdvancedSettingsJson.decode(
            """{"gatewaySlotCount":0,"gatewayCacheReuse":-1,"gatewayToolSurfaceLimit":999,"gatewayIntermediateMaxTokens":2147483647,"gatewaySoftSynthesisRound":-5,"gatewayResultCharLimit":2147483647}"""
        )
        assertEquals("1", headers(settings)["X-Gateway-Slot-Count"])
        assertEquals("0", headers(settings)["X-Gateway-Cache-Reuse"])
        assertEquals("128", headers(settings)["X-Gateway-Tool-Surface-Limit"])
        assertEquals("16384", headers(settings)["X-Gateway-Intermediate-Max-Tokens"])
        assertEquals("4", headers(settings)["X-Gateway-Soft-Synthesis-Round"])
        assertEquals("200000", headers(settings)["X-Gateway-Result-Char-Limit"])
    }

    private fun headers(settings: AdvancedSettings) = GatewayPerformancePreferences.headersForSettings(settings)
}
