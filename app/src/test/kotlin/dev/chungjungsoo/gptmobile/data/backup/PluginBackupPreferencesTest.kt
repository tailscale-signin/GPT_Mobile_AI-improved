package dev.chungjungsoo.gptmobile.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBackupPreferencesTest {
    @Test fun restoringReusedIdentifiersCannotAttachAnOldGitHubWorkspaceToAnotherChat() {
        val raw = """{"conversationGitHub":{"12":{"connectionUid":"old-account","owner":"private-owner","repo":"private-repo","ref":"main","commitSha":"${"a".repeat(40)}","mainProfileUid":"old-profile"}},"githubMainProfiles":{"old-account":"old-profile"},"githubPrepareIndex":["old-account"],"remoteMcpConnections":false}"""
        val incoming = mapOf("advanced_feature_settings_json" to BackupValue("string", raw))
        val restored = PluginBackupPreferences.sanitizeAcademic(incoming).getValue("advanced_feature_settings_json").value
        val settings = Json.decodeFromString<dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings>(restored)
        assertTrue(settings.conversationGitHub.isEmpty())
        assertTrue(settings.githubMainProfiles.isEmpty())
        assertTrue(settings.githubPrepareIndex.isEmpty())
        assertEquals(false, settings.remoteMcpConnections)
        assertTrue(!restored.contains("private-owner"))
    }

    @Test fun academicRestorePreservesDepthAndSourcesButRequiresFreshConsent() {
        val incoming = mapOf("advanced_feature_settings_json" to BackupValue("string", """{"toolPluginStates":{"deep_research":true},"conversationResearch":{"12":{"researchToggle":true,"depth":80,"gatewayConnectionUid":"private","allowNativeFallback":true,"enabledProviders":["arxiv"]}}}"""))
        val restored = PluginBackupPreferences.sanitizeAcademic(incoming).getValue("advanced_feature_settings_json").value
        val settings = Json.decodeFromString<dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings>(restored)
        val selection = settings.conversationResearch.getValue(12)
        assertEquals(80, selection.depth)
        assertEquals(setOf("arxiv"), selection.enabledProviders)
        assertEquals(false, selection.researchToggle)
        assertEquals(false, selection.allowNativeFallback)
        assertEquals(null, selection.gatewayConnectionUid)
        assertEquals(false, settings.isToolPluginEnabled("deep_research"))
    }

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
