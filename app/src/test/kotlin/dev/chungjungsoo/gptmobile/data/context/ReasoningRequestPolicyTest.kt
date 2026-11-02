package dev.chungjungsoo.gptmobile.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReasoningRequestPolicyTest {
    @Test fun autoPreferencesRemainUsableWithoutAVerifiedToggle() {
        val primary = ReasoningPolicy.forRequest(ReasoningMode.AUTO, true, true, false, null)
        val helper = ReasoningPolicy.forRequest(ReasoningMode.AUTO, false, false, true, null)
        assertEquals(ReasoningMode.AUTO, primary.effective)
        assertEquals(ReasoningMode.AUTO, helper.effective)
        assertNull(helper.transmittedControl)
        assertEquals(ReasoningMode.AUTO, ReasoningPolicy.forRequest(ReasoningMode.AUTO, false, false, false, null).effective)
    }

    @Test fun explicitOffCannotBeOverriddenByEngineeringEffortOrHelperPreferences() {
        val explicit = ReasoningPolicy.forRequest(ReasoningMode.OFF, true, true, false, ReasoningTemplateControl.ENABLE_THINKING)
        assertEquals(ReasoningMode.OFF, explicit.effective)
        assertEquals(ReasoningMode.ON, ReasoningPolicy.forRequest(ReasoningMode.ON, false, false, true, ReasoningTemplateControl.ENABLE_THINKING).effective)
        for (mode in listOf(ReasoningMode.OFF, ReasoningMode.ON)) {
            assertThrows(IllegalStateException::class.java) { ReasoningPolicy.forRequest(mode, true, true, true, null) }
        }
    }
}
