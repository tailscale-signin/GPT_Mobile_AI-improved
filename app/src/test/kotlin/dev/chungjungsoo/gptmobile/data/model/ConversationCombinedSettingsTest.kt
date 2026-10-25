package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCombinedSettingsTest {
    @Test fun defaultAndAllTwentyFiveCombinationsRemainIndependent() {
        assertEquals(25, ConversationCombinedSettings().collectionBias)
        assertEquals(50, ConversationCombinedSettings().effort)
        for (merge in ConversationCombinedSettings.STOPS) {
            for (effort in ConversationCombinedSettings.STOPS) {
                val settings = ConversationCombinedSettings(collectionBias = merge, effort = effort).normalized()
                assertEquals(merge, settings.collectionBias)
                assertEquals(effort, settings.effort)
            }
        }
    }

    @Test fun invalidValuesClampSnapAndNormalizeIdempotently() {
        for (value in -150..250) {
            val normalized = ConversationCombinedSettings(collectionBias = value, effort = 50).normalized()
            assertTrue(normalized.collectionBias in ConversationCombinedSettings.STOPS)
            assertEquals(50, normalized.effort)
            assertEquals(normalized, normalized.normalized())
        }
        assertEquals(0, ConversationCombinedSettings.nearestStop(12))
        assertEquals(25, ConversationCombinedSettings.nearestStop(13))
        assertEquals(null, ConversationCombinedSettings(creativity = Float.NaN).normalized().creativity)
        assertEquals(2f, ConversationCombinedSettings(creativity = 9f).normalized().creativity)
        assertEquals(0L, ConversationCombinedSettings(revision = -5).normalized().revision)
    }

    @Test fun missingFieldsUseDefaultsAndLegacySchemaMigrates() {
        assertEquals(ConversationCombinedSettings(continueLongAnswers = false), Json.decodeFromString(ConversationCombinedSettingsSerializer, "{}"))
        val result = Json.decodeFromString(ConversationCombinedSettingsSerializer, """{"schemaVersion":0,"collectionBias":99,"effort":-4}""")
        assertEquals(1, result.schemaVersion)
        assertEquals(100, result.collectionBias)
        assertEquals(0, result.effort)
    }

    @Test fun futureSchemaAndIncompatibleShapesRoundTripWithoutInventingEdits() {
        val raw = """{"schemaVersion":42,"collectionBias":{"future":true},"effort":"unbounded","nested":{"keep":[1,2]}}"""
        val decoded = Json.decodeFromString(ConversationCombinedSettingsSerializer, raw)
        assertFalse(decoded.isSupported)
        assertEquals(Json.parseToJsonElement(raw), Json.encodeToJsonElement(ConversationCombinedSettingsSerializer, decoded))
        assertEquals(decoded, decoded.normalized())
    }

    @Test fun knownSchemaRetainsUnknownNestedFieldsWhenAControlChanges() {
        val decoded = Json.decodeFromString(ConversationCombinedSettingsSerializer, """{"effort":50,"future":{"nested":["a"]}}""")
        val saved = Json.encodeToJsonElement(ConversationCombinedSettingsSerializer, decoded.copy(effort = 100)).jsonObject
        assertEquals(Json.parseToJsonElement("""{"nested":["a"]}"""), saved["future"])
        assertEquals("100", saved["effort"].toString())
    }
}
