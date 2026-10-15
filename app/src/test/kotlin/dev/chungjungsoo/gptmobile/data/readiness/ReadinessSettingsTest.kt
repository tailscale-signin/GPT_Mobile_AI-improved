package dev.chungjungsoo.gptmobile.data.readiness

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReadinessSettingsTest {
    @Test fun oldSettingsRemainConservativeAndPreferencesRoundTrip() {
        val legacy = Json.decodeFromString<ReadinessSettings>("{}")
        assertFalse(legacy.enabled)
        assertFalse(legacy.predictMemory)
        assertFalse(legacy.warmMeteredNetworks)
        val selected = legacy.copy(enabled = true, predictMemory = true, strategy = PreparationStrategy.MINIMAL_RESOURCES, cacheGeneration = 4)
        assertEquals(selected, Json.decodeFromString<ReadinessSettings>(Json.encodeToString(selected)))
    }
}
