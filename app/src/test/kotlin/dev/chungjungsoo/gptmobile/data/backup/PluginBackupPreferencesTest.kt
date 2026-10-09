package dev.chungjungsoo.gptmobile.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBackupPreferencesTest {
    @Test fun toolsOnlyRestorePreservesUnrelatedPreferencesAndProfileBehavior() {
        val current = mapOf(
            "unrelated" to BackupValue("int", "77"),
            "advanced_feature_settings_json" to BackupValue("string", """{"theme":"current","futureFlag":42,"profileBehavior":{"owner":{"temperature":0.2,"toolPluginStates":{"amazon_free":false}},"other":{"toolPluginStates":{"airbnb":true}}}}""")
        )
        val incoming = mapOf("advanced_feature_settings_json" to BackupValue("string", """{"theme":"old","toolPluginStates":{"amazon_free":true},"pluginExecution":{"amazon_free":{"amazonMarketplace":"amazon.ca"}},"profileBehavior":{"owner":{"temperature":1.0,"toolPluginStates":{"amazon_free":true}}}}"""))
        val restored = PluginBackupPreferences.merge(current, incoming)
        val features = Json.parseToJsonElement(restored.getValue("advanced_feature_settings_json").value) as JsonObject
        assertEquals(JsonPrimitive("current"), features["theme"])
        assertEquals(JsonPrimitive(42), features["futureFlag"])
        val profiles = features["profileBehavior"] as JsonObject
        assertEquals(JsonPrimitive(0.2), (profiles["owner"] as JsonObject)["temperature"])
        assertTrue((profiles["owner"] as JsonObject)["toolPluginStates"].toString().contains("true"))
        assertTrue("other" in profiles)
        assertEquals(BackupValue("int", "77"), restored["unrelated"])
        assertTrue(features["pluginExecution"].toString().contains("amazon.ca"))
    }

    @Test fun missingLegacyPluginPreferencesLeaveCurrentChoicesAlone() {
        val current = mapOf("advanced_feature_settings_json" to BackupValue("string", "{}"))
        assertEquals(current, PluginBackupPreferences.merge(current, emptyMap()))
        assertEquals(current, PluginBackupPreferences.capture(current + ("unrelated" to BackupValue("int", "77"))))
    }
}
