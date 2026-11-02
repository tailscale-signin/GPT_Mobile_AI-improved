package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePluginDefaultsTest {
    @Test fun `new and migrated settings keep voice off`() {
        assertFalse(AppFeatureSettings().isToolPluginEnabled(ToolPluginId.VOICE_CHAT))
        assertFalse(Json.decodeFromString<AppFeatureSettings>("{}").isToolPluginEnabled(ToolPluginId.VOICE_CHAT))
        assertTrue(ToolServiceCatalog.integrated.any { it.id == ToolPluginId.VOICE_CHAT })
    }

    @Test fun `explicit opt in and selected voice profile survive serialization`() {
        val enabled = AppFeatureSettings().withToolPluginEnabled(ToolPluginId.VOICE_CHAT, true).copy(voiceProfileUid = "profile-1")
        val restored = Json.decodeFromString<AppFeatureSettings>(Json.encodeToString(enabled))
        assertTrue(restored.isToolPluginEnabled(ToolPluginId.VOICE_CHAT))
        assertEquals("profile-1", restored.voiceProfileUid)
        assertFalse(restored.withToolPluginEnabled(ToolPluginId.VOICE_CHAT, false).isToolPluginEnabled(ToolPluginId.VOICE_CHAT))
    }
}
