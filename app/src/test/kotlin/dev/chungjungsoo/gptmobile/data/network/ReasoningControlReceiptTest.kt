package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningControlReceiptTest {
    @Test fun nativeWireControlsPreserveIntentWithoutConfusingVisibilityWithBackendDisable() {
        val config = ProviderRequestConfig(
            "https://private.example/v1",
            "credential-must-not-appear",
            profileRevision = "c".repeat(64),
            configuredProfileReasoning = true,
            allowReasoning = false,
            maximizeReasoning = true
        )
        val responses = ReasoningControlReceipt.fromPayload("""{"reasoning":{"effort":"high","secret":"private"},"input":"private prompt"}""", config)
        assertEquals("reasoning.effort=high", responses.transmittedControl)
        assertEquals("EFFORT_ONLY", responses.providerSupport)
        assertTrue(responses.disableRequested)
        assertFalse(responses.transmitsDisable)
        assertEquals(true, responses.requestedMaximizeReasoning)
        val anthropic = ReasoningControlReceipt.fromPayload("""{"thinking":{"type":"enabled","budget_tokens":1024,"display":"private"}}""", config)
        assertEquals("thinking.type=enabled;thinking.budget_tokens=1024", anthropic.transmittedControl)
        assertFalse(anthropic.transmitsDisable)
        val visibility = ReasoningControlReceipt.fromPayload("""{"generationConfig":{"thinkingConfig":{"includeThoughts":false}}}""", config)
        assertEquals("VISIBILITY_ONLY", visibility.providerSupport)
        assertFalse(visibility.transmitsDisable)
        val disabled = ReasoningControlReceipt.fromPayload("""{"generationConfig":{"thinkingConfig":{"thinkingBudget":0,"includeThoughts":false}}}""", config)
        assertTrue(disabled.transmitsDisable)
        for (receipt in listOf(responses, anthropic, visibility, disabled)) {
            val evidence = receipt.evidence()
            assertEquals(kotlinx.serialization.json.JsonNull, evidence["effective_reasoning"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive("NOT_MEASURED"), evidence["effective_settings_source"])
            assertFalse(evidence.toString().contains("private"))
            assertFalse(evidence.toString().contains("credential"))
        }
        val malformed = ReasoningControlReceipt.fromPayload("""{"reasoning":{"enabled":"false","effort":"secret"},"thinking":{"budget_tokens":"1024"},"generationConfig":{"thinkingConfig":{"thinkingBudget":"0","includeThoughts":"false"}}}""", config)
        assertNull(malformed.transmittedControl)
        assertFalse(malformed.transmitsDisable)
        assertEquals("NOT_TRANSMITTED", malformed.providerSupport)
        assertNull(ReasoningControlReceipt.fromPayload("""{"generationConfig":{"thinkingConfig":{"thinkingBudget":999999999999999999999}}}""", config).transmittedControl)
    }

    @Test fun explicitOffAndEitherSupportedTemplateParameterRemainObservableWithoutClaimingServerSupport() {
        for (parameter in listOf("enable_thinking", "thinking")) {
            val receipt = ReasoningControlReceipt.fromPayload("""{"chat_template_kwargs":{"$parameter":false}}""", allowReasoning = true, requestedMode = dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF)
            assertTrue(receipt.disableRequested)
            assertEquals("chat_template_kwargs.$parameter=false", receipt.transmittedControl)
            assertTrue(receipt.transmitsDisable)
            assertEquals("UNVERIFIED", receipt.providerSupport)
        }
        val absent = ReasoningControlReceipt.fromPayload("{}", allowReasoning = true, requestedMode = dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF)
        assertTrue(absent.disableRequested)
        assertFalse(absent.transmitsDisable)
        assertEquals("NOT_TRANSMITTED", absent.providerSupport)
    }

    @Test fun requestedDisableDoesNotInventATransmittedControl() {
        val receipt = ReasoningControlReceipt.fromPayload("""{"model":"automatic"}""", allowReasoning = false)
        assertTrue(receipt.disableRequested)
        assertNull(receipt.transmittedControl)
        assertFalse(receipt.transmitsDisable)
        assertEquals("NOT_TRANSMITTED", receipt.providerSupport)
    }

    @Test fun effortReductionAndDisableAreDifferentWireCapabilities() {
        val effort = ReasoningControlReceipt.fromPayload("""{"reasoning_effort":"low"}""", allowReasoning = false)
        assertEquals("reasoning_effort=low", effort.transmittedControl)
        assertFalse(effort.transmitsDisable)
        assertEquals("EFFORT_ONLY", effort.providerSupport)
        val disable = ReasoningControlReceipt.fromPayload("""{"reasoning":{"enabled":false}}""", allowReasoning = false)
        assertTrue(disable.transmitsDisable)
        assertEquals("UNVERIFIED", disable.providerSupport)
    }

    @Test fun requestSnapshotKeepsConfiguredIntentAndWireAcknowledgmentSeparateFromUnmeasuredEffectiveSetting() {
        val receipt = ReasoningControlReceipt.fromPayload(
            """{"reasoning_effort":"low"}""",
            allowReasoning = false,
            requestedMode = dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF,
            profileRevision = "a".repeat(64),
            configuredProfileReasoning = false
        )
        val evidence = receipt.evidence()
        assertEquals(kotlinx.serialization.json.JsonPrimitive("a".repeat(64)), evidence["profile_revision"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), evidence["configured_profile_reasoning"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("OFF"), evidence["requested_mode"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("reasoning_effort=low"), evidence["serialized_control"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), evidence["serialized_disable"])
        assertEquals(kotlinx.serialization.json.JsonNull, evidence["effective_reasoning"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("NOT_MEASURED"), evidence["effective_settings_source"])
        assertEquals(kotlinx.serialization.json.JsonNull, receipt.copy(profileRevision = "invalid-revision").evidence()["profile_revision"])
    }
}
