package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File
import java.io.InputStream
import java.io.StringWriter
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** File-backed version of the same five-file diagnostic bundle contract. */
internal object StreamingDebugBundle {
    private const val MAX_BYTES = 64L * 1024 * 1024
    private val names = setOf("manifest.json", "conversation.json", "events.jsonl", "context-manifests.json", "provenance.json")
    private data class Digest(val bytes: Long, val sha256: String)

    fun write(conversation: File, destination: File) {
        require(conversation.length() in 1..MAX_BYTES) { "Diagnostic export exceeds the storage limit; no data was truncated." }
        val temporary = File(destination.parentFile, "debug-parts-${java.util.UUID.randomUUID()}")
        check(temporary.mkdirs())
        try {
            val events = File(temporary, "events.jsonl")
            val context = File(temporary, "context-manifests.json")
            val response = File(temporary, "response.json").apply { writeText("[]") }
            val profiles = File(temporary, "profiles.json").apply { writeText("[]") }
            var schema = ""
            var messages = false
            var profileArray = false
            var capture: JsonElement = buildJsonObject {
                put("level", "legacy-unknown")
                put("capturedFully", false)
            }
            var journalPresent = false
            val gaps = mutableMapOf<String, JsonElement>()
            var clock: JsonElement = JsonObject(emptyMap())
            events.bufferedWriter().use { eventWriter ->
                context.bufferedWriter().use { contextWriter ->
                    val contextJson = JsonWriter(contextWriter).apply { beginArray() }
                    JsonReader(conversation.bufferedReader()).use { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "schema" -> schema = reader.nextString()
                                "messages" -> {
                                    messages = reader.peek() == JsonToken.BEGIN_ARRAY
                                    reader.skipValue()
                                }
                                "profiles" -> {
                                    profileArray = reader.peek() == JsonToken.BEGIN_ARRAY
                                    reader.skipValue()
                                }
                                "capture" -> capture = element(reader)
                                "responseMapping" -> response.bufferedWriter().use { copyValue(reader, JsonWriter(it)) }
                                "profileDependencies" -> profiles.bufferedWriter().use { copyValue(reader, JsonWriter(it)) }
                                "appLogSnapshot" -> {
                                    val rows = element(reader) as? JsonArray ?: JsonArray(emptyList())
                                    clock = dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticClockReconciliation.summarize(rows)
                                }
                                "workspace" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        val row = element(reader)
                                        if (((row as? JsonObject)?.get("kind") as? JsonPrimitive)?.content == "context") {
                                            JsonReader(row.toString().reader()).use { copyValue(it, contextJson) }
                                        }
                                    }
                                    reader.endArray()
                                }
                                "runJournal" -> {
                                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                                        reader.skipValue()
                                        continue
                                    }
                                    journalPresent = true
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        val key = reader.nextName()
                                        when {
                                            key == "events" -> {
                                                reader.beginArray()
                                                while (reader.hasNext()) {
                                                    val writer = JsonWriter(eventWriter).apply { isLenient = true }
                                                    copyValue(reader, writer)
                                                    writer.flush()
                                                    eventWriter.write("\n")
                                                }
                                                reader.endArray()
                                            }
                                            key in setOf("droppedEvents", "missingBatches", "missingSequences", "storageFailed") -> gaps[key] = element(reader)
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        require(reader.peek() == JsonToken.END_DOCUMENT) { "CORRUPT_EXPORT: trailing JSON" }
                    }
                    contextJson.endArray()
                    contextJson.flush()
                }
            }
            require(schema in DebugExportSchema.supportedConversations && messages && profileArray) { "CORRUPT_EXPORT: incomplete conversation" }
            val provenance = File(temporary, "provenance.json")
            provenance.bufferedWriter().use { output ->
                output.write("{\"schema\":\"gptmobile.provenance.v1\",\"responseMapping\":")
                response.reader().use { it.copyTo(output) }
                output.write(",\"profileDependencies\":")
                profiles.reader().use { it.copyTo(output) }
                output.write(",\"semanticCoverageVerified\":false,\"clockReconciliation\":$clock}")
            }
            val parts = linkedMapOf("conversation.json" to conversation, "events.jsonl" to events, "context-manifests.json" to context, "provenance.json" to provenance)
            val digests = parts.mapValues { (_, file) -> file.inputStream().use { digest(it, MAX_BYTES) } }
            val manifest = buildJsonObject {
                put("schema", DebugExportSchema.BUNDLE)
                put("conversationSchema", schema)
                put("eventSchema", dev.chungjungsoo.gptmobile.data.diagnostics.RUN_EVENT_SCHEMA)
                put("capture", capture)
                put("journalAvailability", if (journalPresent) "retained" else "not-captured-or-unavailable")
                put("journalGaps", JsonObject(gaps))
                put("payloads", "No additional payload capture; permitted retained content is in conversation.json")
                put(
                    "files",
                    JsonArray(
                        digests.map { (name, digest) ->
                            buildJsonObject {
                                put("name", name)
                                put("bytes", digest.bytes)
                                put("sha256", digest.sha256)
                                put("digestRepresentation", "redacted-export-file-utf8")
                            }
                        }
                    )
                )
            }.toString().toByteArray()
            require(manifest.size <= 64 * 1024 && digests.values.sumOf { it.bytes } + manifest.size <= MAX_BYTES) { "Diagnostic bundle exceeds the storage limit; no data was truncated." }
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0 })
                zip.write(manifest)
                zip.closeEntry()
                parts.forEach { (name, file) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                    file.inputStream().use { it.copyTo(zip, 8192) }
                    zip.closeEntry()
                }
            }
        } finally {
            temporary.deleteRecursively()
        }
    }

    /** Verifies names, bounds, exact bytes and hashes without retaining archive payloads. */
    fun verify(file: File) {
        val observed = mutableMapOf<String, Digest>()
        var manifest: JsonObject? = null
        var remaining = MAX_BYTES
        var conversationSchema: String? = null
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in names && entry.name !in observed && !entry.isDirectory) { "CORRUPT_EXPORT: unexpected or duplicate archive entry" }
                if (entry.name == "manifest.json") {
                    val bytes = zip.readBytesBounded(minOf(remaining, 64 * 1024L).toInt())
                    manifest = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
                    observed[entry.name] = Digest(bytes.size.toLong(), debugBodyDigest(bytes.decodeToString()))
                } else if (entry.name == "conversation.json") {
                    val input = DigestingInput(zip, remaining)
                    conversationSchema = verifyConversation(JsonReader(input.reader()))
                    observed[entry.name] = input.snapshot()
                } else {
                    observed[entry.name] = digest(zip, remaining)
                }
                remaining -= observed.getValue(entry.name).bytes
                require(remaining >= 0) { "CORRUPT_EXPORT: oversized bundle" }
            }
        }
        require(observed.keys == names) { "CORRUPT_EXPORT: missing bundle file" }
        val contract = requireNotNull(manifest)
        require((contract["schema"] as? JsonPrimitive)?.content == DebugExportSchema.BUNDLE) { "UNSUPPORTED_EXPORT_SCHEMA" }
        require((contract["conversationSchema"] as? JsonPrimitive)?.content == conversationSchema) { "CORRUPT_EXPORT: conversation schema mismatch" }
        val records = contract["files"] as? JsonArray ?: error("CORRUPT_EXPORT: missing manifest records")
        require(records.size == 4)
        val seen = hashSetOf<String>()
        records.forEach { value ->
            val row = value.jsonObject
            val name = (row["name"] as? JsonPrimitive)?.content ?: error("CORRUPT_EXPORT: missing name")
            require(name != "manifest.json" && seen.add(name))
            val actual = requireNotNull(observed[name])
            require((row["bytes"] as? JsonPrimitive)?.content?.toLongOrNull() == actual.bytes && (row["sha256"] as? JsonPrimitive)?.content == actual.sha256) { "CORRUPT_EXPORT: checksum mismatch" }
        }
    }

    private fun verifyConversation(reader: JsonReader): String {
        var schema: String? = null
        var messages = false
        var profiles = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "schema" -> schema = reader.nextString()
                "messages" -> {
                    messages = reader.peek() == JsonToken.BEGIN_ARRAY
                    reader.skipValue()
                }
                "profiles" -> {
                    profiles = reader.peek() == JsonToken.BEGIN_ARRAY
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT && schema in DebugExportSchema.supportedConversations && messages && profiles) { "CORRUPT_EXPORT: incomplete conversation" }
        return requireNotNull(schema)
    }

    /** Does not own/close the ZIP stream; one entry is the bounded EOF for this reader. */
    private class DigestingInput(private val source: InputStream, private val limit: Long) : InputStream() {
        private val hash = MessageDigest.getInstance("SHA-256")
        private var count = 0L
        override fun read(): Int {
            val value = source.read()
            if (value >= 0) {
                checkSize(1)
                hash.update(value.toByte())
            }
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val amount = source.read(buffer, offset, length)
            if (amount > 0) {
                checkSize(amount)
                hash.update(buffer, offset, amount)
            }
            return amount
        }
        private fun checkSize(amount: Int) {
            count += amount
            require(count <= limit) { "CORRUPT_EXPORT: payload exceeds limit" }
        }
        fun snapshot(): Digest = Digest(count, hash.digest().joinToString("") { "%02x".format(it) })
    }

    private fun digest(input: InputStream, limit: Long): Digest {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(8192)
        var bytes = 0L
        while (true) {
            val count = input.read(block)
            if (count < 0) break
            bytes += count
            require(bytes <= limit) { "CORRUPT_EXPORT: payload exceeds limit" }
            digest.update(block, 0, count)
        }
        return Digest(bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }
    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (true) {
            val count = read(block)
            if (count < 0) break
            require(buffer.size() + count <= limit)
            buffer.write(block, 0, count)
        }
        return buffer.toByteArray()
    }
    private fun element(reader: JsonReader): JsonElement {
        val output = StringWriter()
        // A nested journal field can be a scalar (for example a gap count or
        // storageFailed). Android's writer otherwise requires an object/array
        // at its root, even though this is just one value from a strict reader.
        copyValue(reader, JsonWriter(output).apply { isLenient = true })
        return Json.parseToJsonElement(output.toString())
    }
    private fun copyValue(reader: JsonReader, writer: JsonWriter) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                writer.beginObject()
                while (reader.hasNext()) {
                    writer.name(reader.nextName())
                    copyValue(reader, writer)
                }
                reader.endObject()
                writer.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                writer.beginArray()
                while (reader.hasNext()) copyValue(reader, writer)
                reader.endArray()
                writer.endArray()
            }
            JsonToken.STRING -> writer.value(reader.nextString())
            JsonToken.NUMBER -> writer.value(java.math.BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                writer.nullValue()
            }
            else -> error("CORRUPT_EXPORT: invalid JSON token")
        }
    }
}
