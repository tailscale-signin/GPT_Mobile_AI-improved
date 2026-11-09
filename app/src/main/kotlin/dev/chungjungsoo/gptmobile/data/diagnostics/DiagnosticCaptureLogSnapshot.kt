package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A session owns frozen, redacted log files until checkpoint pruning or explicit clearing. */
internal object DiagnosticCaptureLogSnapshot {
    private const val MAX_METADATA_BYTES = 16 * 1024
    private const val MAX_SOURCE_BYTES = 4L * 1024 * 1024
    private const val MAX_LINE_CHARACTERS = 64 * 1024
    private val sourceName = Regex("(?:critical(?:-previous)?|previous|current)\\.log")
    private class GuardIoFailure(val original: IOException) : RuntimeException()

    suspend fun open(
        session: File,
        beforeRead: suspend () -> Unit,
        redactLine: (String, (String) -> Unit) -> String?,
        capture: (File) -> DiagnosticLogSnapshot,
        critical: Boolean = true
    ): DiagnosticLogSnapshot {
        beforeRead()
        val directory = File(session, if (critical) "frozen-critical-logs" else "frozen-recent-logs")
        val guardedRead: suspend () -> Unit = {
            try {
                beforeRead()
            } catch (failure: IOException) {
                // File I/O omissions must not convert a sink budget rejection into success.
                throw GuardIoFailure(failure)
            }
        }
        val descriptor = File(directory, "snapshot.json")
        if (descriptor.isFile) {
            val retained = load(directory, descriptor, beforeRead)
            if (retained.recordCount > 0) return retained
            // No evidence is lost by refreshing a snapshot that contained no logs.
            directory.deleteRecursively()
        }
        check(directory.mkdirs() || directory.isDirectory)
        var finished = false
        try {
            val original = capture(File(directory, "source-copies"))
            original.use {
                require(original.sources.size <= 4)
                val failures = original.sourceFailures.filterKeys { it == "pending-records" || it.startsWith("critical") == critical }.toMutableMap()
                val selected = original.sources.filter { it.name.startsWith("critical") == critical }
                val sources = selected.mapNotNull { source ->
                    beforeRead()
                    require(sourceName.matches(source.name))
                    val destination = File(directory, source.name)
                    val part = File(directory, "${source.name}.partial")
                    var records = 0L
                    var bytes = 0L
                    try {
                        FileOutputStream(part).use { output ->
                            val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), 4096)
                            lines(source.file, guardedRead) { line ->
                                val redacted = redactLine(line) { reason -> failures.putIfAbsent(source.name, reason) } ?: return@lines
                                require(redacted.length <= MAX_LINE_CHARACTERS && '\n' !in redacted && '\r' !in redacted)
                                bytes += redacted.toByteArray(Charsets.UTF_8).size + 1
                                if (bytes > MAX_SOURCE_BYTES) throw IOException("LOG_SNAPSHOT_BYTE_LIMIT")
                                writer.append(redacted).append('\n')
                                records++
                            }
                            writer.flush()
                            output.fd.sync()
                        }
                        check(part.renameTo(destination))
                        DiagnosticLogSource(source.name, destination, records, destination.length(), source.skippedSourceBytes, source.truncatedRecords)
                    } catch (_: IOException) {
                        // This source failed; already frozen sources remain independently useful.
                        failures[source.name] = "SOURCE_READ_FAILED"
                        destination.delete()
                        null
                    } finally {
                        part.delete()
                    }
                }
                val omission = original.omissionReason ?: if (sources.none { it.recordCount > 0 }) {
                    if (critical) "NO_RETAINED_CRITICAL_LOGS" else "NO_RETAINED_APP_LOGS"
                } else {
                    null
                }
                val snapshot = DiagnosticLogSnapshot(sources, original.capturedAtMillis, original.trackingEnabled, original.droppedRecords, omission, failures, droppedCriticalRecords = original.droppedCriticalRecords)
                val checksums = sources.map { digest(it.file, beforeRead) }
                val metadata = buildJsonObject {
                    put("schema", 1)
                    put("capturedAtMillis", snapshot.capturedAtMillis)
                    put("trackingEnabled", snapshot.trackingEnabled)
                    put("droppedRecords", snapshot.droppedRecords)
                    put("droppedCriticalRecords", snapshot.droppedCriticalRecords)
                    put("omissionReason", snapshot.omissionReason)
                    put("sourceFailures", JsonObject(snapshot.sourceFailures.mapValues { JsonPrimitive(it.value) }))
                    put(
                        "sources",
                        JsonArray(
                            sources.mapIndexed { index, source ->
                                buildJsonObject {
                                    put("name", source.name)
                                    put("file", source.file.name)
                                    put("recordCount", source.recordCount)
                                    put("byteCount", source.byteCount)
                                    put("skippedSourceBytes", source.skippedSourceBytes)
                                    put("truncatedRecords", source.truncatedRecords)
                                    put("sha256", checksums[index])
                                }
                            }
                        )
                    )
                }.toString().toByteArray(Charsets.UTF_8)
                check(metadata.size <= MAX_METADATA_BYTES)
                beforeRead()
                val part = File(directory, "snapshot.partial")
                try {
                    FileOutputStream(part).use { output ->
                        output.write(metadata)
                        output.fd.sync()
                    }
                    check(part.renameTo(descriptor))
                } finally {
                    part.delete()
                }
                finished = true
                return snapshot
            }
        } catch (failure: GuardIoFailure) {
            throw failure.original
        } finally {
            if (!finished) directory.deleteRecursively()
        }
    }

    private suspend fun load(directory: File, descriptor: File, beforeRead: suspend () -> Unit): DiagnosticLogSnapshot {
        require(descriptor.length() in 1..MAX_METADATA_BYTES.toLong())
        val saved = Json.parseToJsonElement(descriptor.readText()) as? JsonObject ?: throw IOException("LOG_SNAPSHOT_METADATA_INVALID")
        require(saved["schema"] == JsonPrimitive(1))
        val rows = saved["sources"] as? JsonArray ?: throw IOException("LOG_SNAPSHOT_METADATA_INVALID")
        require(rows.size <= 4)
        val sources = rows.map { value ->
            beforeRead()
            val row = value as? JsonObject ?: throw IOException("LOG_SNAPSHOT_METADATA_INVALID")
            val name = row.text("name")
            require(sourceName.matches(name) && row.text("file") == name)
            val source = File(directory, name)
            val bytes = row.number("byteCount")
            require(bytes in 0..MAX_SOURCE_BYTES && source.isFile && source.length() == bytes)
            require(digest(source, beforeRead) == row.text("sha256")) { "LOG_SNAPSHOT_CHECKSUM_MISMATCH" }
            DiagnosticLogSource(name, source, row.number("recordCount"), bytes, row.number("skippedSourceBytes"), row.number("truncatedRecords"))
        }
        val failures = (saved["sourceFailures"] as? JsonObject).orEmpty().mapValues { (_, value) -> (value as? JsonPrimitive)?.content.orEmpty() }
        return DiagnosticLogSnapshot(
            sources,
            saved.number("capturedAtMillis"),
            (saved["trackingEnabled"] as? JsonPrimitive)?.booleanOrNull == true,
            saved.number("droppedRecords"),
            saved.text("omissionReason").takeIf { it.isNotBlank() && it != "null" },
            failures,
            droppedCriticalRecords = if ("droppedCriticalRecords" in saved) saved.number("droppedCriticalRecords") else 0
        )
    }

    suspend fun lines(source: File, beforeRead: suspend () -> Unit, consume: suspend (String) -> Unit) {
        source.bufferedReader(Charsets.UTF_8, 4096).use { reader ->
            val buffer = CharArray(4096)
            val line = StringBuilder(1024)
            while (true) {
                beforeRead()
                val size = reader.read(buffer)
                if (size < 0) break
                for (index in 0 until size) {
                    val character = buffer[index]
                    if (character == '\n') {
                        consume(redactLogMessage(line.toString().removeSuffix("\r")))
                        line.setLength(0)
                    } else {
                        if (line.length >= MAX_LINE_CHARACTERS) throw IOException("LOG_SNAPSHOT_LINE_LIMIT")
                        line.append(character)
                    }
                }
            }
            if (line.isNotEmpty()) consume(redactLogMessage(line.toString().removeSuffix("\r")))
        }
    }

    private suspend fun digest(source: File, beforeRead: suspend () -> Unit): String {
        val hash = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                beforeRead()
                val size = input.read(buffer)
                if (size < 0) break
                hash.update(buffer, 0, size)
            }
        }
        return DiagnosticRecordChunks.hex(hash.digest())
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: throw IOException("LOG_SNAPSHOT_METADATA_INVALID")
}
