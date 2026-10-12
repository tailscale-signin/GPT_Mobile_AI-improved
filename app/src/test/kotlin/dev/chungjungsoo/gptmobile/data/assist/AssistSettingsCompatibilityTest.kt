package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AssistSettingsCompatibilityTest {
    @Test fun unknownModesDisableAssistWithoutResettingOtherSettings() {
        val restored = Json.decodeFromString<AppFeatureSettings>("""{"localAssist":true,"smoothStreaming":false,"localAssistPolicy":{"mode":"FUTURE_MODE"}}""")
        assertFalse(restored.smoothStreaming)
        assertEquals(AssistMode.OFF, restored.effectiveAssistPolicy("profile", 1).mode)
    }

    @Test fun legacyEnableDoesNotBroadenToDocumentExtraction() {
        val restored = Json.decodeFromString<AppFeatureSettings>("""{"localAssist":true}""")
        assertEquals(AssistMode.PREPARATION_ONLY, restored.effectiveAssistPolicy("profile", 1).mode)
        assertFalse(restored.localAssistPolicy.extractEvidence)
    }

    @Test fun unknownPolicyVersionCannotGrantWork() {
        val restored = AppFeatureSettings(localAssist = true, localAssistPolicy = AssistPolicy(mode = AssistMode.LOCAL_FIRST, version = 99))
        assertEquals(AssistMode.OFF, restored.effectiveAssistPolicy("profile", 1).mode)
    }
}
