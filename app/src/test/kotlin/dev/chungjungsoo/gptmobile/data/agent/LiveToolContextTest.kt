package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.isRecentLocation
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.mcp.McpLocationToolSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveToolContextTest {
    @Test
    fun enabledNativeLocationIsNamedAndPermissionIsNotAssumed() {
        val prompt = liveToolSystemPrompt("User profile instructions", listOf("current_date", BuiltInAgentTool.DEVICE_LOCATION))
        assertTrue(prompt.startsWith("User profile instructions\n\n"))
        assertTrue(prompt.contains("call device_location"))
        assertTrue(prompt.contains("Registration does not prove permission"))
        assertFalse(prompt.contains("native device_location tool is not enabled"))
    }

    @Test
    fun disabledLocationExplainsRealControlsWithoutInventingCapability() {
        val prompt = liveToolSystemPrompt(null, listOf("current_date"))
        assertTrue(prompt.contains("native device_location tool is not enabled"))
        assertTrue(prompt.contains("AI profile's tool settings"))
        assertTrue(prompt.contains("per-chat tool exclusions"))
        assertFalse(prompt.contains("call device_location"))
        assertTrue(prompt.contains("timezone or remembered address does not establish"))
    }

    @Test
    fun contextUsesStableLiveNamesAndDoesNotDeclareGatewayToolsUnavailable() {
        val prompt = liveToolSystemPrompt(null, listOf("z_tool", "a_tool", "z_tool"))
        assertTrue(prompt.contains("a_tool, z_tool."))
        assertTrue(prompt.contains("Gateway may supply additional tools"))
        assertTrue(liveToolSystemPrompt(null, emptyList()).contains("no mobile tools enabled"))
    }

    @Test
    fun locationCatalogueMatchesExecutableNativeName() {
        assertEquals(listOf(BuiltInAgentTool.DEVICE_LOCATION), McpLocationToolSet.tools.map { it.name })
    }

    @Test
    fun currentLocationRejectsStaleAndFutureFixes() {
        val now = 1_000_000L
        assertTrue(isRecentLocation(now, now))
        assertTrue(isRecentLocation(now - 120_000, now))
        assertFalse(isRecentLocation(now - 120_001, now))
        assertFalse(isRecentLocation(now + 1, now))
    }
}
