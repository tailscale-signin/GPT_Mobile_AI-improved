package dev.chungjungsoo.gptmobile.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningRequestPolicyTest {
    @Test fun qualifiedChatMlSystemTogglePreservesTheSyntheticTurnAndWritesExplicitOff() {
        val turn = "<|im_start|>user\nTemplate capability check.<|im_end|>\n<|im_start|>assistant\n"
        val enabled = "<|im_start|>system\nThink.<|im_end|>\n$turn<think>"
        val disabled = "<|im_start|>system\nAnswer.<|im_end|>\n$turn<think>\n</think>\n"
        assertTrue(ReasoningPolicy.verifiesThinkingToggle(enabled, disabled))
        val decision = ReasoningPolicy.resolve(ReasoningMode.OFF, true, ReasoningTemplateControl.ENABLE_THINKING)
        val request = ReasoningPolicy.apply(kotlinx.serialization.json.JsonObject(emptyMap()), decision)
        val kwargs = request["chat_template_kwargs"] as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), kwargs["enable_thinking"])
        assertEquals(ReasoningMode.OFF, decision.effective)
        assertNull(decision.observedComputationEnabled)
        for (invalid in listOf(
            disabled.replace("Template capability check.", "A different request."),
            disabled.replace("<|im_start|>user", "<|im_start|>assistant"),
            "<|im_start|>assistant\nEarlier output.<|im_end|>\n$disabled",
            disabled + "Generated output.",
            disabled.replace("</think>", "")
        )) {
            assertFalse(ReasoningPolicy.verifiesThinkingToggle(enabled, invalid))
        }
        assertFalse(ReasoningPolicy.verifiesThinkingToggle("Unknown prefix<think>", "Another prefix<think></think>"))
    }

    @Test fun absentModesRespectTheLegacySwitchAndExplicitModesRemainAuthoritative() {
        assertEquals(ReasoningMode.OFF, ReasoningPolicy.preference(false, null))
        assertEquals(ReasoningMode.AUTO, ReasoningPolicy.preference(true, null))
        assertEquals(ReasoningMode.AUTO, ReasoningPolicy.preference(false, ReasoningMode.AUTO))
        assertEquals(ReasoningMode.ON, ReasoningPolicy.preference(false, ReasoningMode.ON))
        assertEquals(ReasoningMode.OFF, ReasoningPolicy.preference(true, ReasoningMode.ON, false))
        assertEquals(ReasoningMode.ON, ReasoningPolicy.preference(false, ReasoningMode.OFF, true))
        assertEquals(ReasoningMode.OFF, ReasoningPolicy.forRequest(ReasoningMode.ON, false, true, false, ReasoningTemplateControl.THINKING).effective)
    }

    @Test fun autoPreferencesRemainUsableWithoutAVerifiedToggle() {
        val primary = ReasoningPolicy.forRequest(ReasoningMode.AUTO, true, true, false, null)
        val helper = ReasoningPolicy.forRequest(ReasoningMode.AUTO, true, false, true, null)
        assertEquals(ReasoningMode.AUTO, primary.effective)
        assertEquals(ReasoningMode.AUTO, helper.effective)
        assertNull(helper.transmittedControl)
        assertThrows(IllegalStateException::class.java) { ReasoningPolicy.forRequest(ReasoningMode.AUTO, false, false, false, null) }
    }

    @Test fun hardReasoningDisableNeverFallsBackToAutoForHelpersOrContinuation() {
        for (mode in ReasoningMode.entries) {
            for (helper in listOf(false, true)) {
                val failure = assertThrows(IllegalStateException::class.java) { ReasoningPolicy.forRequest(mode, false, true, helper, null) }
                org.junit.Assert.assertTrue(failure.message.orEmpty().startsWith("REASONING_OFF_UNSUPPORTED"))
                for (control in ReasoningTemplateControl.entries) {
                    val decision = ReasoningPolicy.forRequest(mode, false, true, helper, control)
                    assertEquals(ReasoningMode.OFF, decision.effective)
                    assertEquals(control.parameter, decision.transmittedControl)
                    assertNull(decision.observedComputationEnabled)
                }
            }
        }
    }

    @Test fun autoSavedPreferenceIsRetainedWhenTheRequestUsesAVerifiedOverride() {
        val helper = ReasoningPolicy.forRequest(ReasoningMode.AUTO, true, false, true, ReasoningTemplateControl.ENABLE_THINKING)
        assertEquals(ReasoningMode.AUTO, helper.requested)
        assertEquals(ReasoningMode.OFF, helper.effective)
        assertEquals("enable_thinking", helper.transmittedControl)
        val primary = ReasoningPolicy.forRequest(ReasoningMode.AUTO, true, true, false, ReasoningTemplateControl.THINKING)
        assertEquals(ReasoningMode.AUTO, primary.requested)
        assertEquals(ReasoningMode.ON, primary.effective)
        assertEquals("thinking", primary.transmittedControl)
    }

    @Test fun explicitOffCannotBeOverriddenByEngineeringEffortOrHelperPreferences() {
        val explicit = ReasoningPolicy.forRequest(ReasoningMode.OFF, true, true, false, ReasoningTemplateControl.ENABLE_THINKING)
        assertEquals(ReasoningMode.OFF, explicit.effective)
        assertEquals(ReasoningMode.OFF, ReasoningPolicy.forRequest(ReasoningMode.ON, false, false, true, ReasoningTemplateControl.ENABLE_THINKING).effective)
        for (mode in listOf(ReasoningMode.OFF, ReasoningMode.ON)) {
            assertThrows(IllegalStateException::class.java) { ReasoningPolicy.forRequest(mode, true, true, true, null) }
        }
    }
}
