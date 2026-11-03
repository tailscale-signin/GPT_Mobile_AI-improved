package dev.chungjungsoo.gptmobile.presentation.ui.chat

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Packages one already-redacted frozen export. No source is queried while writing the archive. */
internal object DebugExportBundle {
    private const val MAX_BYTES = 64 * 1024 * 1024
    private val files = setOf("manifest.json", "conversation.json", "events.jsonl", "context-manifests.json", "provenance.json")

    fun conversation(body: String): JsonObject {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Diagnostic snapshot exceeds the export memory limit; no data was truncated." }
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("CORRUPT_EXPORT: conversation must be an object")
        require((root["schema"] as? JsonPrimitive)?.content in DebugExportSchema.supportedConversations) { "UNSUPPORTED_EXPORT_SCHEMA" }
        require(root["messages"] is JsonArray && root["profiles"] is JsonArray) { "CORRUPT_EXPORT: missing required conversation records" }
        return root
    }

    fun write(redactedConversation: String, destination: OutputStream) {
        val root = conversation(redactedConversation)
        val journal = root["runJournal"] as? JsonObject
        val events = (journal?.get("events") as? JsonArray).orEmpty()
        val captureSummary = dev.chungjungsoo.gptmobile.data.diagnostics.JournalCaptureSummary.describe(journal, events.size, root["capture"] as? JsonObject)
        val context = (root["workspace"] as? JsonArray).orEmpty().filter { row ->
            ((row as? JsonObject)?.get("kind") as? JsonPrimitive)?.content == "context"
        }
        val provenance = buildJsonObject {
            put("schema", "gptmobile.provenance.v1")
            put("responseMapping", root["responseMapping"] ?: JsonArray(emptyList()))
            put("profileDependencies", root["profileDependencies"] ?: JsonArray(emptyList()))
            put("semanticCoverageVerified", false)
            put("incidentScope", "Retained runs in this conversation; process-session counters are not attributed to this chat")
            put("terminalDiagnostics", root["runs"] ?: JsonArray(emptyList()))
            put("effectivePolicies", root["policySnapshots"] ?: JsonObject(emptyMap()))
            put(
                "taskCheckpoints",
                JsonArray(
                    (root["workspace"] as? JsonArray).orEmpty().filter { row ->
                        val id = ((row as? JsonObject)?.get("id") as? JsonPrimitive)?.content.orEmpty()
                        id.startsWith("github-evidence:") && (":checklist_" in id || ":verification_" in id) || id.startsWith("github-active-task:")
                    }
                )
            )
            put("missingDataReasons", JsonArray(listOf("Provider wire payloads were not captured", "Protected memory chunks are references, not a replayable source snapshot", "Absent policies or checkpoints are unavailable, not implicit defaults", "Runtime warnings without surrounding thread evidence have no established cause").map(::JsonPrimitive)))
            put("clockReconciliation", dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticClockReconciliation.summarize(root["appLogSnapshot"] as? JsonArray ?: JsonArray(emptyList())))
        }
        val parts = linkedMapOf<String, (java.io.Writer) -> Unit>(
            "conversation.json" to { it.write(redactedConversation) },
            "events.jsonl" to { writer ->
                events.forEach {
                    writer.write(it.toString())
                    writer.write("\n")
                }
            },
            "context-manifests.json" to { writer ->
                writer.write("[")
                context.forEachIndexed { index, item ->
                    if (index > 0) writer.write(",")
                    writer.write(item.toString())
                }
                writer.write("]")
            },
            "provenance.json" to { it.write(provenance.toString()) }
        )
        val manifestFiles = mutableListOf<JsonObject>()
        var writtenBytes = 0L
        ZipOutputStream(destination).use { zip ->
            for ((name, write) in parts) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                val stream = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(value: ByteArray, offset: Int, length: Int) {
                        writtenBytes += length
                        bytes += length
                        require(writtenBytes <= MAX_BYTES) { "Diagnostic bundle exceeds the export byte limit; destination must be discarded" }
                        digest.update(value, offset, length)
                        zip.write(value, offset, length)
                    }
                    override fun close() = Unit
                }
                java.io.OutputStreamWriter(stream, Charsets.UTF_8).use(write)
                zip.closeEntry()
                manifestFiles += buildJsonObject {
                    put("name", name)
                    put("bytes", bytes)
                    put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                    put("digestRepresentation", "redacted-export-file-utf8")
                }
            }
            val manifest = buildJsonObject {
                put("schema", DebugExportSchema.BUNDLE)
                put("conversationSchema", root.getValue("schema"))
                put("eventSchema", dev.chungjungsoo.gptmobile.data.diagnostics.RUN_EVENT_SCHEMA)
                put(
                    "capture",
                    root["capture"] ?: buildJsonObject {
                        put("level", "legacy-unknown")
                        put("capturedFully", false)
                        put("replayableReducer", false)
                        put("completeProviderWireCapture", false)
                    }
                )
                put("journalAvailability", captureSummary.getValue("availability"))
                put("journalCapture", captureSummary)
                put("activeRunIds", (root["capture"] as? JsonObject)?.get("activeRunIds") ?: JsonArray(emptyList()))
                put("taskClosure", taskClosure(root, events))
                put(
                    "sourceCoverage",
                    buildJsonObject {
                        listOf("runs", "toolEvents", "runJournal", "providerFailures", "appLogSnapshot", "localRuntimeIncidents").forEach { name ->
                            put(name, if (name in root) "RETAINED_SNAPSHOT" else "NOT_CAPTURED")
                        }
                        put("providerWirePayloads", "NOT_CAPTURED")
                        put("vendorLogs", "UNAVAILABLE_TO_APP")
                        put("androidExitHistory", "NOT_INCLUDED_IN_CONVERSATION_EXPORT")
                        put("appLogWindow", root["appLogSnapshotAvailability"] ?: JsonObject(emptyMap()))
                    }
                )
                put("completionState", "Inspect activeRunIds and retained run terminal statuses; answer text alone is not completion")
                put("buildIdentity", JsonObject(root.filterKeys { it in setOf("version", "build", "appCommit", "nativeRuntimeCommit", "buildVariant", "package") }))
                put("dataScopes", root["dataScopes"] ?: JsonObject(emptyMap()))
                put(
                    "journalGaps",
                    buildJsonObject {
                        listOf("droppedEvents", "missingBatches", "missingSequences", "storageFailed").forEach { name ->
                            journal?.get(name)?.let { put(name, it) }
                        }
                    }
                )
                put("payloads", "No additional payload capture; permitted retained content is in conversation.json")
                put("files", JsonArray(manifestFiles))
            }
            zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0L })
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    internal fun taskClosure(root: JsonObject, events: List<kotlinx.serialization.json.JsonElement>): JsonObject {
        val terminalEvents = events.mapNotNull { it as? JsonObject }.filter { it["kind"] == JsonPrimitive("RUN_TERMINAL") }.mapNotNull { (it["runId"] as? JsonPrimitive)?.content }.toSet()
        val terminalStatuses = dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus.terminal
        return buildJsonObject {
            put(
                "runs",
                JsonArray(
                    (root["runs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { run ->
                        val id = (run["runId"] as? JsonPrimitive)?.content.orEmpty()
                        val status = (run["status"] as? JsonPrimitive)?.content.orEmpty()
                        buildJsonObject {
                            put("runId", id)
                            put("status", status)
                            put("terminalEventCaptured", id in terminalEvents)
                            put(
                                "closure",
                                when {
                                    id in terminalEvents -> "TERMINAL_EVENT_CAPTURED"
                                    status in terminalStatuses -> "TERMINAL_STATUS_WITHOUT_RETAINED_EVENT"
                                    else -> "ACTIVE_OR_WAITING_AT_EXPORT"
                                }
                            )
                        }
                    }
                )
            )
            put("coverage", "Missing terminal events describe capture coverage; they do not prove a failed task.")
        }
    }

    /** Bounded reader for offline inspection. Unknown optional JSON keys survive round trips. */
    fun read(source: InputStream): Map<String, String> {
        val parts = linkedMapOf<String, String>()
        var remaining = MAX_BYTES
        ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in files && entry.name !in parts && !entry.isDirectory) { "CORRUPT_EXPORT: unexpected or duplicate archive entry" }
                val buffer = java.io.ByteArrayOutputStream()
                val block = ByteArray(8192)
                while (true) {
                    val read = zip.read(block)
                    if (read < 0) break
                    remaining -= read
                    require(remaining >= 0) { "CORRUPT_EXPORT: archive exceeds the memory limit" }
                    buffer.write(block, 0, read)
                }
                parts[entry.name] = buffer.toByteArray().decodeToString(throwOnInvalidSequence = true)
            }
        }
        require(parts.keys == files) { "CORRUPT_EXPORT: missing bundle file" }
        val manifest = Json.parseToJsonElement(parts.getValue("manifest.json")) as? JsonObject ?: error("CORRUPT_EXPORT: invalid manifest")
        require((manifest["schema"] as? JsonPrimitive)?.content == DebugExportSchema.BUNDLE) { "UNSUPPORTED_EXPORT_SCHEMA" }
        val entries = manifest["files"] as? JsonArray ?: error("CORRUPT_EXPORT: missing file manifest")
        require(entries.size == files.size - 1) { "CORRUPT_EXPORT: file manifest mismatch" }
        val verified = mutableSetOf<String>()
        for (row in entries) {
            val record = row as? JsonObject ?: error("CORRUPT_EXPORT: invalid file record")
            val name = (record["name"] as? JsonPrimitive)?.content ?: error("CORRUPT_EXPORT: missing file name")
            require(name != "manifest.json" && verified.add(name)) { "CORRUPT_EXPORT: duplicate file record" }
            val text = parts[name] ?: error("CORRUPT_EXPORT: missing file")
            require((record["sha256"] as? JsonPrimitive)?.content == debugBodyDigest(text)) { "CORRUPT_EXPORT: digest mismatch" }
            require((record["bytes"] as? JsonPrimitive)?.content?.toLongOrNull() == text.toByteArray(Charsets.UTF_8).size.toLong()) { "CORRUPT_EXPORT: byte count mismatch" }
        }
        val root = conversation(parts.getValue("conversation.json"))
        require(manifest["conversationSchema"] == root["schema"]) { "CORRUPT_EXPORT: conversation schema mismatch" }
        return parts
    }
}
