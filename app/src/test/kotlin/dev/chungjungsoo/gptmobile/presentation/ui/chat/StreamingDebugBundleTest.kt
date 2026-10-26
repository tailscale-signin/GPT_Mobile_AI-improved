package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.util.JsonReader
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class StreamingDebugBundleTest {
    private val json = Json { encodeDefaults = true }
    private val secret = "configured-private-export-credential"
    private val files = setOf("manifest.json", "conversation.json", "events.jsonl", "context-manifests.json", "provenance.json")

    private suspend fun produce(directory: File, messages: List<MessageV2>): File {
        val conversation = File(directory, "conversation-source.json")
        val records = buildJsonObject {
            put(
                "workspace",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("kind", "context")
                            put("text", "Private $secret")
                        }
                    )
                )
            )
            put(
                "runJournal",
                buildJsonObject {
                    put(
                        "events",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("sequence", 1)
                                    // Retained diagnostic trees from build 129 may contain
                                    // log-style lists accepted by the tree parser only.
                                    put("profiles", Json.parseToJsonElement("[builtin-free-ovhcloud,builtin-free-kilo]"))
                                    put("token", secret)
                                    put("text", "Private $secret")
                                }
                            )
                        )
                    )
                    put("missingSequences", 2)
                    put("storageFailed", false)
                }
            )
        }
        conversation.bufferedWriter().use { writer ->
            writeChatDebugExport(ChatRoomV2(id = 1, title = "Synthetic"), messages, listOf(PlatformV2(uid = "p", name = "Profile", token = secret, secretRef = "private-reference")), ChatMcpToolConfig(), AppFeatureSettings(), "Draft $secret", records, emptyList(), json, destination = writer)
        }
        return File(directory, "export.zip").also {
            StreamingDebugBundle.write(conversation, it)
            StreamingDebugBundle.verify(it)
        }
    }

    @Test fun actualProducerStreamsIntoLegacyCompatibleFiveFileBundleWithRedactedDigests() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("debug-stream-").toFile()
        try {
            val archive = produce(directory, listOf(MessageV2(id = 2, chatId = 1, platformType = "p", content = "Retained answer $secret", currentRunId = "run")))
            val parts = archive.inputStream().use(DebugExportBundle::read)
            assertEquals(files, parts.keys)
            assertEquals(File(directory, "conversation-source.json").readText(), parts.getValue("conversation.json"))
            assertTrue(parts.values.all { secret !in it && "private-reference" !in it })
            assertTrue(parts.getValue("events.jsonl").contains("[redacted]"))
            assertTrue(parts.getValue("events.jsonl").contains("\"builtin-free-ovhcloud\""))
            val manifest = Json.parseToJsonElement(parts.getValue("manifest.json")).jsonObject
            assertEquals("2", manifest.getValue("journalGaps").jsonObject.getValue("missingSequences").jsonPrimitive.content)
            assertEquals(JsonPrimitive(false), manifest.getValue("journalGaps").jsonObject.getValue("storageFailed"))
            assertEquals("PARTIAL", manifest.getValue("journalAvailability").jsonPrimitive.content)
            assertEquals("1", manifest.getValue("journalCapture").jsonObject.getValue("eventCount").jsonPrimitive.content)
            assertTrue(manifest.getValue("buildIdentity").jsonObject.containsKey("appCommit"))
            assertTrue(manifest.getValue("buildIdentity").jsonObject.containsKey("nativeRuntimeCommit"))
            assertEquals("app_wide_recent", manifest.getValue("dataScopes").jsonObject.getValue("appLogSnapshot").jsonPrimitive.content)
            assertFalse(manifest.getValue("capture").jsonObject.getValue("capturedFully").jsonPrimitive.content.toBoolean())
            assertEquals(0, directory.listFiles().orEmpty().count { it.name.startsWith("debug-parts-") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun emptyDisabledJournalHasTheSameStatusInStreamingAndMemoryBundles() {
        val directory = kotlin.io.path.createTempDirectory("debug-empty-capture-").toFile()
        try {
            val body = buildJsonObject {
                put("schema", DebugExportSchema.CONVERSATION)
                put("messages", JsonArray(emptyList()))
                put("profiles", JsonArray(emptyList()))
                put("capture", buildJsonObject { put("diagnosticsEnabled", false) })
                put("runJournal", buildJsonObject { put("events", JsonArray(emptyList())) })
            }.toString()
            val memory = java.io.ByteArrayOutputStream()
            DebugExportBundle.write(body, memory)
            val memoryManifest = Json.parseToJsonElement(DebugExportBundle.read(memory.toByteArray().inputStream()).getValue("manifest.json")).jsonObject
            val source = File(directory, "conversation.json").apply { writeText(body) }
            val zip = File(directory, "export.zip")
            StreamingDebugBundle.write(source, zip)
            StreamingDebugBundle.verify(zip)
            val streamingManifest = Json.parseToJsonElement(zip.inputStream().use(DebugExportBundle::read).getValue("manifest.json")).jsonObject
            assertEquals("DISABLED", streamingManifest.getValue("journalAvailability").jsonPrimitive.content)
            assertEquals(memoryManifest.getValue("journalCapture"), streamingManifest.getValue("journalCapture"))
            assertEquals("0", streamingManifest.getValue("journalCapture").jsonObject.getValue("eventCount").jsonPrimitive.content)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun corruptDigestAndWellHashedIncompleteConversationAreRejected(): Unit = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("debug-corrupt-").toFile()
        try {
            val parts = produce(directory, emptyList()).inputStream().use(DebugExportBundle::read)
            fun archive(name: String, content: Map<String, String>): File = File(directory, name).also { output ->
                ZipOutputStream(output.outputStream()).use { zip ->
                    content.forEach { (path, body) ->
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(body.toByteArray())
                        zip.closeEntry()
                    }
                }
            }
            val changed = archive("changed.zip", parts + ("events.jsonl" to "{}\n"))
            assertThrows(IllegalArgumentException::class.java) { StreamingDebugBundle.verify(changed) }
            val incomplete = """{"schema":"${DebugExportSchema.CONVERSATION}","profiles":[]}"""
            val manifest = Json.parseToJsonElement(parts.getValue("manifest.json")).jsonObject
            val altered = JsonObject(
                manifest + (
                    "files" to JsonArray(
                        (manifest.getValue("files") as JsonArray).map { value ->
                            val row = value.jsonObject
                            if (row.getValue("name").jsonPrimitive.content != "conversation.json") row else JsonObject(row + mapOf("sha256" to JsonPrimitive(debugBodyDigest(incomplete)), "bytes" to JsonPrimitive(incomplete.toByteArray().size)))
                        }
                    )
                    )
            )
            val forged = archive("incomplete.zip", parts + mapOf("conversation.json" to incomplete, "manifest.json" to altered.toString()))
            assertThrows(IllegalArgumentException::class.java) { StreamingDebugBundle.verify(forged) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun fortyMiBTwoThousandMessageExportCompletesUnder256MiBHeapWithoutConversationTree() = runBlocking {
        assumeTrue("The large export gate requires scripts/heap-stress.init.gradle", java.lang.Boolean.getBoolean("gptmobile.heapStress"))
        assertTrue("This gate must run in the constrained heap worker", Runtime.getRuntime().maxMemory() <= 256L * 1024 * 1024)
        val directory = kotlin.io.path.createTempDirectory("debug-heap-").toFile()
        try {
            val body = "Retained source sentence with exact useful evidence. ".repeat(440)
            val messages = (1..2000).map { index -> MessageV2(id = index, chatId = 1, platformType = null, content = "Record $index: $body", createdAt = index.toLong()) }
            val archive = produce(directory, messages)
            val conversation = File(directory, "conversation-source.json")
            assertTrue(conversation.length() > 40L * 1024 * 1024)
            var rows = 0
            JsonReader(conversation.bufferedReader()).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "messages") {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.skipValue()
                            rows++
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
            assertEquals(2000, rows)
            assertTrue(archive.length() > 0)
            StreamingDebugBundle.verify(archive)
        } finally {
            directory.deleteRecursively()
        }
    }
}
