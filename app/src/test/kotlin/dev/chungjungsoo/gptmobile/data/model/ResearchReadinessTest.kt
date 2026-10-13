package dev.chungjungsoo.gptmobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ResearchReadinessTest {
    private val enabled = AppFeatureSettings().withToolPluginEnabled(ToolPluginId.DEEP_RESEARCH, true).withProfileToolPluginEnabled("profile", ToolPluginId.DEEP_RESEARCH, true)
    private val selection = MultiSearchResearchSelection(researchToggle = true, depth = 25, scope = ResearchContentScope.WEB_ONLY)

    @Test fun `web research does not require academic consent or delegation`() {
        assertNull(researchStartBlockReason(selection, enabled, "profile", true))
        assertNull(researchReadinessReason(selection, enabled, "profile", true))
        assertEquals("Web-only scope selected", academicUnlockReason(selection, enabled, "profile", true))
        assertEquals("Enable Papers and studies consent", academicUnlockReason(selection.copy(scope = ResearchContentScope.PAPERS_ONLY), enabled, "profile", true))
    }

    @Test fun `blocked network and profile permissions remain visible and authoritative`() {
        assertEquals("Network tools are disabled for this profile", researchStartBlockReason(selection, enabled, "profile", false))
        assertNotNull(researchStartBlockReason(selection, enabled.withToolPluginEnabled(ToolPluginId.DEEP_RESEARCH, false), "profile", true))
        assertEquals("Increase Research Depth to unlock", researchStartBlockReason(selection.copy(depth = 0), enabled, "profile", true))
    }
}
