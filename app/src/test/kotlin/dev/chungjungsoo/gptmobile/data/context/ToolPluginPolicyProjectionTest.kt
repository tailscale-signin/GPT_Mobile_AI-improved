package dev.chungjungsoo.gptmobile.data.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ToolPluginPolicyProjectionTest {
    private val optIn = setOf("voice")

    @Test fun emptyAndExplicitDefaultSettingsHaveIdenticalSerializableAuthority() {
        val empty = ToolPluginPolicyProjection.project(emptyMap(), optIn)
        val explicit = ToolPluginPolicyProjection.project(sortedMapOf("search" to true, "voice" to false), optIn)
        assertEquals("{}", Json.encodeToString(JsonObject.serializer(), empty))
        assertEquals(empty, explicit)
    }

    @Test fun revocationsAndOptInGrantsRemainInTheAuthorityAndHaveStableOrder() {
        val first = ToolPluginPolicyProjection.project(linkedMapOf("voice" to true, "search" to false), optIn)
        val reordered = ToolPluginPolicyProjection.project(sortedMapOf("search" to false, "voice" to true), optIn)
        assertEquals("{\"search\":false,\"voice\":true}", Json.encodeToString(JsonObject.serializer(), first))
        assertEquals(first, reordered)
        assertNotEquals(first, ToolPluginPolicyProjection.project(mapOf("voice" to true), optIn))
        assertNotEquals(first, ToolPluginPolicyProjection.project(mapOf("search" to false), optIn))
    }
}
