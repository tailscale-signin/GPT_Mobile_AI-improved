package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.DebugMetric
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedTextMatchVisibilityTest {
    @Test fun matchTextIsHiddenByDefaultEvenInDebugMode() {
        val defaults = AppFeatureSettings()
        assertFalse(defaults.debugShowCombinedTextMatches)
        assertFalse(shouldShowCombinedTextMatchDiagnostics(true, false, defaults))
        assertFalse(shouldShowCombinedTextMatchDiagnostics(true, true, defaults))
    }

    @Test fun textMatchSettingOnlyShowsInDebugCombinedAnswer() {
        val enabled = AppFeatureSettings().withDebugMetric(DebugMetric.COMBINED_TEXT_MATCHES, true)
        assertTrue(shouldShowCombinedTextMatchDiagnostics(true, true, enabled))
        assertFalse(shouldShowCombinedTextMatchDiagnostics(true, false, enabled))
        assertFalse(shouldShowCombinedTextMatchDiagnostics(false, true, enabled))
        assertFalse(shouldShowCombinedTextMatchDiagnostics(false, false, enabled))
        assertFalse(shouldShowCombinedTextMatchDiagnostics(true, true, enabled.withDebugMetric(DebugMetric.COMBINED_TEXT_MATCHES, false)))
    }

    @Test fun settingPersistsAcrossFeatureSettingsSerialization() {
        val enabled = AppFeatureSettings().withDebugMetric(DebugMetric.COMBINED_TEXT_MATCHES, true)
        val decoded = Json.decodeFromString<AppFeatureSettings>(Json.encodeToString(enabled))
        assertTrue(decoded.debugShowCombinedTextMatches)
        val oldSettings = Json.decodeFromString<AppFeatureSettings>("{}")
        assertFalse(oldSettings.debugShowCombinedTextMatches)
    }
}
