package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDebugRedactorTest {
    @Test fun historicalBareLiteralsBecomeStringsWithoutChangingJsonScalars() {
        val retained = Json.parseToJsonElement("""{"profiles":[builtin-free-ovhcloud,builtin-free-kilo],"usage":[123,true,false,null,1.25,1e3],"authorization":"Bearer old-private-key"}""")
        val clean = redactChatDebugData(retained)
        assertEquals(Json.parseToJsonElement("""["builtin-free-ovhcloud","builtin-free-kilo"]"""), clean.jsonObject.getValue("profiles"))
        assertEquals(retained.jsonObject.getValue("usage"), clean.jsonObject.getValue("usage"))
        assertFalse(clean.toString().contains("old-private-key"))
        assertEquals("""["[redacted]"]""", redactChatDebugData(Json.parseToJsonElement("[configured-secret-value]"), listOf("configured-secret-value")).toString())
    }

    @Test fun largeConversationAndAccountingRemainCompleteWhileEmbeddedSecretsAreRemoved() {
        val text = "evidence ".repeat(10000)
        val payload = buildJsonObject {
            put("outputTokens", 131072)
            put("content", text)
            put("arguments", """{"authorization":"Bearer private-value","x-api-key":"another-secret","items":[1,2,3]}""")
            put("result", "See https://example.com/article?id=5&api_key=private-key and Bearer other-private-value")
            put("profileEcho", "configured-secret-value")
        }
        val clean = redactChatDebugData(payload, listOf("configured-secret-value")).jsonObject
        assertEquals(text, clean.getValue("content").jsonPrimitive.content)
        assertEquals("131072", clean.getValue("outputTokens").jsonPrimitive.content)
        val nested = Json.parseToJsonElement(clean.getValue("arguments").jsonPrimitive.content).jsonObject
        assertEquals("[redacted]", nested.getValue("authorization").jsonPrimitive.content)
        assertEquals("[redacted]", nested.getValue("x-api-key").jsonPrimitive.content)
        assertEquals(3, nested.getValue("items").jsonArray.size)
        assertFalse(clean.toString().contains("private-value"))
        assertFalse(clean.toString().contains("configured-secret-value"))
        assertTrue(clean.getValue("result").jsonPrimitive.content.contains("id=5"))
    }

    @Test fun longArraysAreNotCappedAtDiagnosticPreviewLimits() {
        val clean = redactChatDebugData(Json.parseToJsonElement((0..100).joinToString(",", "[", "]")))
        assertEquals(101, clean.jsonArray.size)
        assertEquals("plain", (redactChatDebugData(JsonPrimitive("plain")) as JsonPrimitive).content)
    }
}
