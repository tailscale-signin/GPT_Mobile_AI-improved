package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubContextVariantWireTest {
    private val request = GatewayEngineeringRequest("owner/repo", "work", "main", "Inspect parser", "selected", chatId = 1, requestId = "command")

    @Test fun newTaskVariantIsExplicitAndRetainedWithTheRequest() {
        val wire = Json.encodeToString(request.copy(contextVariant = GitHubContextVariant.TASK))
        assertEquals(JsonPrimitive("TASK"), Json.parseToJsonElement(wire).jsonObject["context_variant"])
        assertEquals(GitHubContextVariant.TASK, Json.decodeFromString<GatewayEngineeringRequest>(wire).contextVariant)
    }

    @Test fun replayingALegacyRequestDoesNotAddAContextVariantToItsIdentity() {
        val legacy = Json.encodeToString(request)
        val retained = Json.decodeFromString<GatewayEngineeringRequest>(legacy)
        assertFalse(Json.parseToJsonElement(Json.encodeToString(retained)).jsonObject.containsKey("context_variant"))
    }
}
