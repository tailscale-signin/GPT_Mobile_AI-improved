package dev.chungjungsoo.gptmobile.data.research

import android.util.AtomicFile
import android.util.JsonReader
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Small metadata sidecars select an authorized session before its evidence bodies are decoded. */
internal class ResearchSessionIndex(private val directory: File) {
    data class Entry(val file: File, val runId: String, val chatId: Int, val turnId: String, val authority: String, val complete: Boolean, val updatedAt: Long)

    fun entries(chatId: Int? = null, turnIds: Set<String>? = null, authorities: Set<String>? = null): List<Entry> =
        directory.listFiles().orEmpty().asSequence().filter { it.extension == "json" }
            .mapNotNull(::metadata)
            .filter { (chatId == null || it.chatId == chatId) && (turnIds == null || it.turnId in turnIds) && (authorities == null || it.authority in authorities) }
            .sortedByDescending { it.updatedAt }.toList()

    fun metadata(file: File): Entry? = runCatching {
        require(file.extension == "json" && file.length() in 1..512 * 1024)
        val sidecar = sidecar(file)
        val cached = if (sidecar.isFile && sidecar.length() in 1..8192) runCatching {
            val value = Json.parseToJsonElement(AtomicFile(sidecar).openRead().bufferedReader().use { it.readText() }).jsonObject
            if (value.text("modified").toLongOrNull() != file.lastModified() || value.text("bytes").toLongOrNull() != file.length()) null else {
                Entry(file, value.text("runId"), value.number("chatId"), value.text("turnId"), value.text("authority"), value.text("complete") == "true", value.text("updatedAt").toLong())
            }
        }.getOrNull() else null
        cached ?: scan(file).also(::write)
    }.getOrNull()

    fun record(session: ResearchSession, file: File) = write(Entry(file, session.runId, session.chatId, session.turnId, session.authority, session.snapshot.complete, session.snapshot.updatedAt))
    fun remove(file: File) = AtomicFile(sidecar(file)).delete()

    private fun scan(file: File): Entry {
        var runId = ""
        var chatId: Int? = null
        var turnId = ""
        var authority = "legacy"
        var complete = false
        var updatedAt = file.lastModified()
        JsonReader(AtomicFile(file).openRead().bufferedReader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "runId" -> runId = reader.nextString().also { require(it.length <= 512) }
                    "chatId" -> chatId = reader.nextInt()
                    "turnId" -> turnId = reader.nextString().also { require(it.length <= 512) }
                    "authority" -> authority = reader.nextString().also { require(it.length <= 4096) }
                    "snapshot" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "complete" -> complete = reader.nextBoolean()
                                "updatedAt" -> updatedAt = reader.nextLong()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        require(runId.isNotBlank())
        return Entry(file, runId, requireNotNull(chatId), turnId.ifBlank { runId }, authority.ifBlank { "legacy" }, complete, updatedAt)
    }

    private fun sidecar(file: File) = File(directory, file.nameWithoutExtension + ".sessionmeta")
    private fun write(entry: Entry) {
        val bytes = buildJsonObject {
            put("runId", entry.runId)
            put("chatId", entry.chatId)
            put("turnId", entry.turnId)
            put("authority", entry.authority)
            put("complete", entry.complete)
            put("updatedAt", entry.updatedAt)
            put("modified", entry.file.lastModified())
            put("bytes", entry.file.length())
        }.toString().toByteArray()
        require(bytes.size <= 8192)
        val atomic = AtomicFile(sidecar(entry.file))
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}
