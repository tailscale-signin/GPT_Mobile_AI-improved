package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettingsSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSynthesisPreferencesTest {
    @Test fun controlsRoundTripWithoutChangingLegacyDefaultsOrGrantingTools() {
        val original = ConversationCombinedSettings()
        assertFalse(Json.encodeToString(ConversationCombinedSettingsSerializer, original).contains("synthesisCompleteness"))
        val preferences = CombinedSynthesisPreferences(100, 75, 100, true)
        val saved = preferences.applyTo(original)
        val restored = Json.decodeFromString(ConversationCombinedSettingsSerializer, Json.encodeToString(ConversationCombinedSettingsSerializer, saved))
        assertEquals(preferences, CombinedSynthesisPreferences.from(restored))
        assertTrue(preferences.instruction().contains("Original user requirements take precedence"))
        assertTrue(preferences.instruction().contains("grants no new tool"))
    }
}
