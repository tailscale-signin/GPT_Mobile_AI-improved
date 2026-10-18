package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FeatureSettingsJsonTest {
    @Test fun unrelatedMutationRetainsFutureTopLevelNestedAndCombinedPayloads() {
        val raw = """{"edgeFades":true,"futureTop":{"preserve":true},"delegation":{"enabled":false,"futureField":[1,2]},"conversationCombined":{"4":{"schemaVersion":8,"effort":{"unknown":true},"futureNested":{"v":3}}}}"""
        val current = FeatureSettingsJson.decode(raw)
        val next = current.copy(edgeFades = false, delegation = current.delegation.copy(enabled = true))
        val saved = Json.parseToJsonElement(FeatureSettingsJson.merge(raw, current, next)).jsonObject
        val original = Json.parseToJsonElement(raw).jsonObject
        assertEquals(original["futureTop"], saved["futureTop"])
        assertEquals(original["conversationCombined"], saved["conversationCombined"])
        assertEquals(original.getValue("delegation").jsonObject["futureField"], saved.getValue("delegation").jsonObject["futureField"])
        assertEquals("false", saved["edgeFades"].toString())
        assertEquals("true", saved.getValue("delegation").jsonObject["enabled"].toString())
    }

    @Test fun deletionDoesNotResurrectAnOwnedFutureEntry() {
        val raw = """{"futureTop":7,"conversationCombined":{"4":{"schemaVersion":8,"opaque":true},"5":{"effort":75}}}"""
        val current = FeatureSettingsJson.decode(raw)
        val saved = Json.parseToJsonElement(FeatureSettingsJson.merge(raw, current, current.copy(conversationCombined = current.conversationCombined - 4))).jsonObject
        assertFalse("4" in saved.getValue("conversationCombined").jsonObject)
        assertEquals("7", saved["futureTop"].toString())
        assertEquals(75, FeatureSettingsJson.decode(saved.toString()).conversationCombined.getValue(5).effort)
    }

    @Test fun normalizationRetainsUnknownSupportedFieldsAndAssistMigration() {
        val raw = """{"conversationCombined":{"4":{"collectionBias":82,"effort":-8,"futureField":9}},"localAssist":true}"""
        val normalized = FeatureSettingsJson.normalize(raw)
        assertEquals(normalized, FeatureSettingsJson.normalize(normalized))
        assertEquals(75, FeatureSettingsJson.decode(normalized).conversationCombined.getValue(4).collectionBias)
        assertEquals(0, FeatureSettingsJson.decode(normalized).conversationCombined.getValue(4).effort)
        val root = Json.parseToJsonElement(normalized).jsonObject
        assertEquals("9", root.getValue("conversationCombined").jsonObject.getValue("4").jsonObject["futureField"].toString())
        assertEquals("1", root["assistConfigVersion"].toString())
    }

    @Test fun reorderedStableListEntriesKeepTheirOwnFutureFields() {
        val original = Json.parseToJsonElement("""[{"uid":"a","enabled":true,"future":1},{"uid":"b","enabled":true,"future":2}]""")
        val before = Json.parseToJsonElement("""[{"uid":"a","enabled":true},{"uid":"b","enabled":true}]""")
        val after = Json.parseToJsonElement("""[{"uid":"b","enabled":false},{"uid":"a","enabled":true}]""")
        assertEquals(Json.parseToJsonElement("""[{"uid":"b","enabled":false,"future":2},{"uid":"a","enabled":true,"future":1}]"""), FeatureSettingsJson.mergeElements(original, before, after))
    }

    @Test fun addingStableListEntryPreservesExistingFutureFields() {
        val original = Json.parseToJsonElement("""[{"uid":"a","enabled":true,"future":1}]""")
        val before = Json.parseToJsonElement("""[{"uid":"a","enabled":true}]""")
        val after = Json.parseToJsonElement("""[{"uid":"a","enabled":true},{"uid":"new","enabled":false}]""")
        assertEquals(Json.parseToJsonElement("""[{"uid":"a","enabled":true,"future":1},{"uid":"new","enabled":false}]"""), FeatureSettingsJson.mergeElements(original, before, after))
    }
}
