package dev.chungjungsoo.gptmobile.data.model

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolServicePolicyTest {
    @Test
    fun nativeAmazonIsSeparateFromPaidServiceAndDefaultsOffForOldSettings() {
        val old = Json.decodeFromString<AppFeatureSettings>("{}")
        assertFalse(old.isToolPluginEnabled(ToolPluginId.AMAZON_FREE))
        assertFalse(old.isToolPluginSelected("one", ToolPluginId.AMAZON_FREE))
        val selected = old.withToolPluginEnabled(ToolPluginId.AMAZON_FREE, true)
            .withProfileToolPluginEnabled("one", ToolPluginId.AMAZON_FREE, true)
        assertTrue(selected.isToolPluginEnabledForProfile("one", ToolPluginId.AMAZON_FREE))
        assertFalse(selected.isToolPluginEnabledForProfile("two", ToolPluginId.AMAZON_FREE))
        assertFalse(selected.isToolPluginEnabled(ToolPluginId.AMAZON_SEARCH))
        assertTrue(ToolServiceCatalog.integrated.any { it.id == ToolPluginId.AMAZON_FREE && it.usesNetwork })
    }

    @Test
    fun amazonRequiresBothGlobalActivationAndProfileOptIn() {
        val defaults = AppFeatureSettings()
        assertFalse(defaults.isToolPluginEnabled(ToolPluginId.AMAZON_SEARCH))
        assertTrue(defaults.isToolPluginEnabled(ToolPluginId.CALCULATOR))
        val active = defaults.withToolPluginEnabled(ToolPluginId.AMAZON_SEARCH, true)
        assertFalse(active.isToolPluginEnabledForProfile("research", ToolPluginId.AMAZON_SEARCH))
        val chosen = active.withProfileToolPluginEnabled("research", ToolPluginId.AMAZON_SEARCH, true)
        assertTrue(chosen.isToolPluginEnabledForProfile("research", ToolPluginId.AMAZON_SEARCH))
        assertFalse(chosen.isToolPluginEnabledForProfile("writing", ToolPluginId.AMAZON_SEARCH))
        val stopped = chosen.withToolPluginEnabled(ToolPluginId.AMAZON_SEARCH, false)
        assertFalse(stopped.isToolPluginEnabledForProfile("research", ToolPluginId.AMAZON_SEARCH))
        assertTrue(stopped.withToolPluginEnabled(ToolPluginId.AMAZON_SEARCH, true).isToolPluginEnabledForProfile("research", ToolPluginId.AMAZON_SEARCH))
    }

    @Test
    fun profileChoicesSurviveSerializationAndDoNotChangeOtherProfiles() {
        val settings = AppFeatureSettings()
            .withProfileToolPluginEnabled("one", ToolPluginId.CALCULATOR, false)
            .withProfileToolPluginEnabled("one", "service:openstreetmap", false)
            .withProfileToolPluginEnabled("two", ToolPluginId.AMAZON_SEARCH, true)
        val restored = Json.decodeFromString<AppFeatureSettings>(Json.encodeToString(settings))
        assertFalse(restored.isToolPluginEnabledForProfile("one", ToolPluginId.CALCULATOR))
        assertTrue(restored.isToolPluginEnabledForProfile("two", ToolPluginId.CALCULATOR))
        assertFalse(restored.isToolPluginEnabledForProfile("one", "service:openstreetmap"))
        assertTrue(restored.isToolPluginEnabledForProfile("two", "service:openstreetmap"))
        assertFalse(restored.isToolPluginEnabledForProfile("two", ToolPluginId.AMAZON_SEARCH))
        assertFalse(Json.decodeFromString<AppFeatureSettings>("{}").isToolPluginEnabled(ToolPluginId.AMAZON_SEARCH))
    }

    @Test
    fun knownProviderConnectionsShareAServiceWithoutLosingTheirIdentity() {
        val native = connection("native", "GitHub API", "https://api.github.com", ToolConnectionType.GITHUB)
        val remote = connection("remote", "GitHub Official", "https://api.githubcopilot.com/mcp/")
        assertEquals(ToolPluginId.GITHUB, ToolServiceCatalog.forConnection(native).id)
        assertEquals(ToolServiceCatalog.forConnection(native).id, ToolServiceCatalog.forConnection(remote).id)
        val nominatim = connection("geocoding", "Nominatim", "https://maps.example/geocode")
        val overpass = connection("restrooms", "OpenStreetMap Restrooms", "https://maps.example/restrooms")
        assertEquals("service:openstreetmap", ToolServiceCatalog.forConnection(nominatim).id)
        assertEquals(ToolServiceCatalog.forConnection(nominatim).id, ToolServiceCatalog.forConnection(overpass).id)
        assertFalse(nominatim.connectionUid == overpass.connectionUid)
        // Unrelated servers behind one private host must stay independently controllable.
        val first = connection("files", "File Service", "http://localhost:8102/mcp")
        val second = connection("memory", "Memory Service", "http://localhost:8103/mcp")
        assertFalse(ToolServiceCatalog.forConnection(first).id == ToolServiceCatalog.forConnection(second).id)
    }

    private fun connection(uid: String, name: String, endpoint: String, type: String = ToolConnectionType.MCP) = ToolConnection(
        connectionUid = uid,
        name = name,
        alias = uid,
        type = type,
        endpointUrl = endpoint,
        authType = ToolConnectionAuthType.NONE,
        secretRef = null,
        oauthClientId = null
    )
}
