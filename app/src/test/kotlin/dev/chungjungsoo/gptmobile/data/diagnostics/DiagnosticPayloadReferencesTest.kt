package dev.chungjungsoo.gptmobile.data.diagnostics

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPayloadReferencesTest {
    @Test fun changedEventEnvelopeReusesOnlyItsEarlierImmutableBody() = runBlocking {
        val index = DiagnosticPayloadReferences()
        val body = buildJsonObject { put("states", JsonArray(List(300) { JsonPrimitive("verified-$it") })) }
        fun payload(snapshot: String) = buildJsonObject {
            put("snapshot_id", snapshot)
            put("capability_states", body)
        }
        val written = mutableListOf<JsonElement>()
        val timings = DiagnosticCaptureTimings()
        index.write(row(1, payload("one")), timings) { written += it }
        index.write(row(2, payload("two")), timings) { written += it }
        index.write(row(3, payload("three")), timings) { written += it }
        for (i in 1..2) {
            val fields = written[i].jsonObject.getValue("fields").jsonObject
            val envelope = fields.getValue("payload").jsonObject
            assertEquals(JsonPrimitive(if (i == 1) "two" else "three"), envelope["snapshot_id"])
            assertFalse(envelope.containsKey("capability_states"))
            val ref = fields.getValue("payload_body_refs").jsonObject.getValue("capability_states").jsonObject
            assertEquals(JsonPrimitive(1L), ref["rowId"])
            assertEquals(JsonPrimitive("/fields/payload/capability_states"), ref["pointer"])
            assertEquals(JsonPrimitive(DiagnosticRecordChunks.sha256(body.toString())), ref["redactedJsonSha256"])
        }
        assertEquals(JsonPrimitive(2L), timings.json()["payloadBodyReferenceReuseHits"])
    }

    @Test fun interruptedBodyCannotBeReferencedByALaterEvent() = runBlocking {
        val index = DiagnosticPayloadReferences()
        val body = JsonPrimitive("safe body ".repeat(400))
        fun payload(snapshot: Int) = buildJsonObject {
            put("snapshot_id", snapshot)
            put("result", body)
        }
        runCatching { index.write(row(1, payload(1))) { error("logical row rolled back") } }
        index.write(row(2, payload(2))) { result ->
            val fields = result.jsonObject.getValue("fields").jsonObject
            assertFalse(fields.containsKey("payload_body_refs"))
            assertEquals(body, fields.getValue("payload").jsonObject["result"])
        }
    }

    @Test fun encodedOriginalRetainsEveryFieldAndEscapedKeyExactly() = runBlocking {
        val payloads = listOf(
            JsonPrimitive("\"\\\n🙂".repeat(1000)),
            buildJsonObject { put("nested", JsonArray(List(1000) { JsonPrimitive("\"$it🙂") })) }
        )
        for (payload in payloads) {
            val index = DiagnosticPayloadReferences()
            val original = row(1, payload)
            val written = mutableListOf<String>()
            index.writeEncoded(original) { written += it }
            index.writeEncoded(row(2, payload)) { written += it }
            assertEquals(original.toString(), written[0])
            val duplicate = Json.parseToJsonElement(written[1]).jsonObject.getValue("fields").jsonObject
            assertFalse(duplicate.containsKey("payload"))
            assertEquals(JsonPrimitive(1L), duplicate.getValue("payload_ref").jsonObject["rowId"])
            assertEquals(JsonPrimitive(DiagnosticRecordChunks.sha256(payload.toString())), duplicate.getValue("payload_ref").jsonObject["redactedJsonSha256"])
            assertEquals(original.getValue("fields").jsonObject["quoted\"key"], duplicate["quoted\"key"])
        }
    }

    @Test fun interruptedOriginalNeverBecomesAReferenceAndSameRowRemainsCanonical() = runBlocking {
        val index = DiagnosticPayloadReferences()
        val payload = JsonPrimitive("large".repeat(1000))
        runCatching { index.writeEncoded(row(1, payload)) { error("rolled back logical row") } }
        val written = mutableListOf<String>()
        index.writeEncoded(row(2, payload)) { written += it }
        index.writeEncoded(row(2, payload)) { written += it }
        index.writeEncoded(row(3, payload)) { written += it }
        assertTrue(Json.parseToJsonElement(written[0]).jsonObject.getValue("fields").jsonObject.containsKey("payload"))
        assertEquals(written[0], written[1])
        assertEquals(JsonPrimitive(2L), Json.parseToJsonElement(written[2]).jsonObject.getValue("fields").jsonObject.getValue("payload_ref").jsonObject["rowId"])
    }

    @Test fun smallAndNonWorkspaceRowsKeepTheirOriginalJson() = runBlocking {
        val index = DiagnosticPayloadReferences()
        for (value in listOf(row(1, buildJsonObject { put("a", 1) }), JsonPrimitive("plain"), JsonArray(listOf(JsonPrimitive(1))))) {
            index.writeEncoded(value) { assertEquals(value.toString(), it) }
        }
    }

    @Test fun boundedUtf8HasherMatchesTheOriginalDigestAcrossEncoderAndSurrogateBoundaries() {
        val values = listOf("", "a".repeat(8191) + "🙂" + "λ".repeat(90_000), "a\uD800b\uDC00", "\n\"\\".repeat(20_000))
        values.forEach { value ->
            assertEquals(DiagnosticRecordChunks.hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))), DiagnosticRecordChunks.sha256(value))
        }
    }

    private fun row(id: Long, payload: JsonElement): JsonObject = buildJsonObject {
        put("table", "workspace_records")
        put("before", "escaped\nmetadata")
        put(
            "fields",
            buildJsonObject {
                put("quoted\"key", "\\value")
                put("payload", payload)
                put("after", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(true))))
            }
        )
        put("rowId", id)
    }
}
