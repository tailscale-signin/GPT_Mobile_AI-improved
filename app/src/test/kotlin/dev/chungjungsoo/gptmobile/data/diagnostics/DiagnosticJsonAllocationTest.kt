package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DiagnosticJsonAllocationTest {
    @Test
    fun alreadyValidLargeReceiptRetainsItsTreeWithoutCopies() {
        val source = Json.parseToJsonElement("""{"items":[{"count":30,"verified":true,"payload":"${"evidence ".repeat(10000)}"}],"missing":null}""")
        assertSame(source, diagnosticJsonValue(source))
    }

    @Test
    fun legacyBareValuesCopyOnlyTheirChangedAncestors() {
        val untouched = Json.parseToJsonElement("""{"count":30,"complete":true}""")
        val legacy = Json.parseToJsonElement("[profile-a,profile-b]")
        val source = JsonObject(mapOf("receipt" to untouched, "legacy" to legacy))
        val normalized = diagnosticJsonValue(source).jsonObject
        assertSame(untouched, normalized["receipt"])
        assertEquals(JsonArray(listOf(JsonPrimitive("profile-a"), JsonPrimitive("profile-b"))), normalized["legacy"])
        assertEquals(normalized, Json.parseToJsonElement(normalized.toString()))
    }
}
