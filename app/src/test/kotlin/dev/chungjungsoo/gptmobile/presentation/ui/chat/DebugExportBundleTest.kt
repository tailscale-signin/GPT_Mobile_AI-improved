package dev.chungjungsoo.gptmobile.presentation.ui.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DebugExportBundleTest {
    private fun snapshot(version: Int = 3) = buildJsonObject {
        put("schema", "gptmobile.conversation.debug.v$version")
        put("messages", JsonArray(emptyList()))
        put("profiles", JsonArray(emptyList()))
        put("futureOptionalField", "retained")
        put(
            "runJournal",
            buildJsonObject {
                put("events", JsonArray(listOf(buildJsonObject { put("sequence", 1) })))
                put("missingSequences", 2)
            }
        )
    }.toString()

    @Test fun redactedFrozenBundleRoundTripsWithSeparateGapsAndFileDigests() {
        val bytes = ByteArrayOutputStream()
        DebugExportBundle.write(snapshot(), bytes)
        val parts = DebugExportBundle.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(setOf("manifest.json", "conversation.json", "events.jsonl", "context-manifests.json", "provenance.json"), parts.keys)
        assertEquals(snapshot(), parts["conversation.json"])
        assertEquals("{\"sequence\":1}\n", parts["events.jsonl"])
        val manifest = Json.parseToJsonElement(parts.getValue("manifest.json")).jsonObject
        assertEquals(JsonPrimitive(2), manifest.getValue("journalGaps").jsonObject["missingSequences"])
        assertFalse(manifest.getValue("capture").toString().contains("true"))
    }

    @Test fun legacyV2RetainsUnknownOptionalFieldsAndCorruptionIsNeverMissingTelemetry() {
        val root = DebugExportBundle.conversation(snapshot(2))
        assertEquals(JsonPrimitive("retained"), root["futureOptionalField"])
        assertThrows(IllegalArgumentException::class.java) { DebugExportBundle.conversation(JsonObject(root - "messages").toString()) }
        assertThrows(IllegalArgumentException::class.java) { DebugExportBundle.conversation(snapshot(99)) }
    }

    @Test fun modifiedFileAndUnexpectedArchivePathsAreRejected() {
        val bytes = ByteArrayOutputStream()
        DebugExportBundle.write(snapshot(), bytes)
        val original = DebugExportBundle.read(ByteArrayInputStream(bytes.toByteArray()))
        fun rewrite(parts: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
            java.util.zip.ZipOutputStream(output).use { zip ->
                parts.forEach { (name, text) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { DebugExportBundle.read(ByteArrayInputStream(rewrite(original + ("events.jsonl" to "{}")))) }
        assertThrows(IllegalArgumentException::class.java) { DebugExportBundle.read(ByteArrayInputStream(rewrite(original + ("../outside" to "bad")))) }
    }
}
