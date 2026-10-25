package dev.chungjungsoo.gptmobile.data.diagnostics

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Small device/model receipts survive chat disposal, process exits and log rotation. No prompts. */
internal object LocalRuntimeIncidentHistory {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    @Volatile private var directory: File? = null
    fun initialize(context: Context) {
        directory = File(context.noBackupFilesDir, "local-runtime-incidents")
    }

    fun record(event: String, fields: Map<String, String> = emptyMap()) {
        if (directory == null) return
        // Capture before scheduling or lock acquisition; persistence latency is not event time.
        val occurredAt = System.currentTimeMillis()
        val row = buildJsonObject {
            put("eventId", java.util.UUID.randomUUID().toString())
            put("time", occurredAt)
            put("occurredAtMillis", occurredAt)
            put("capturedAtMillis", occurredAt)
            put("monotonicNanos", System.nanoTime())
            put("bootMs", android.os.SystemClock.elapsedRealtime())
            put("event", event.take(80))
            put("process", android.os.Process.myPid())
            put("processName", runCatching { android.app.Application.getProcessName() }.getOrNull())
            put("fields", JsonObject(fields.entries.take(40).associate { it.key.take(80) to JsonPrimitive(redactLogMessage(it.value).take(2000)) }))
        }.toString()
        if (android.os.Looper.getMainLooper()?.let { it == android.os.Looper.myLooper() } == true) {
            scope.launch { persist(row) }
        } else {
            persist(row)
        }
    }

    @Synchronized private fun persist(row: String) {
        val folder = directory ?: return
        runCatching {
            check(folder.isDirectory || folder.mkdirs())
            RandomAccessFile(File(folder, "history.lock"), "rw").use { lock ->
                lock.channel.lock().use {
                    val file = File(folder, "history.jsonl")
                    val previous = if (file.isFile && file.length() <= 512 * 1024) file.readLines().takeLast(95) else emptyList()
                    val temporary = File(folder, "history.tmp")
                    temporary.outputStream().use { output ->
                        output.write((previous + row).joinToString("\n").toByteArray())
                        output.fd.sync()
                    }
                    java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                }
            }
        }
    }
    fun snapshot(): List<JsonObject> = runCatching {
        val folder = directory ?: return emptyList()
        val file = File(folder, "history.jsonl")
        val history = if (!file.isFile || file.length() > 512 * 1024) emptyList() else file.readLines().takeLast(96).mapNotNull { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val native = readNativeIncidentRecords(folder)
        val terminations = folder.parentFile?.let { parent ->
            dev.chungjungsoo.gptmobile.data.localruntime.NativeWorkerTermination.snapshot(File(parent, "native-worker"))
        }.orEmpty().map { receipt ->
            buildJsonObject {
                put("event", "WORKER_TERMINATION_INTENT")
                put("time", receipt.getValue("time").toLong())
                put("process", receipt.getValue("process").toInt())
                put("fields", JsonObject(receipt.filterKeys { it !in setOf("time", "process") }.mapValues { JsonPrimitive(it.value) }))
                put("terminationRequested", true)
                put("exitConfirmed", JsonNull)
                put("workerExitConfirmed", JsonNull)
                put("workerExitEvidence", JsonNull)
            }
        }
        history + native + terminations
    }.getOrDefault(emptyList())
}

/** The native writer bounds errors and warnings independently; JSON escaping can expand both. */
internal fun readNativeIncidentRecords(directory: File): List<JsonObject> {
    fun parse(raw: String, archived: Boolean, capturedAt: Long?): JsonObject {
        val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        fun safe(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { safe(it.value) })
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(value.map(::safe))
            is JsonPrimitive -> if (value.isString) JsonPrimitive(redactLogMessage(value.content)) else value
        }
        val preserved = parsed?.let { safe(it) as JsonObject }
        val errors = (preserved?.get("errors") as? JsonPrimitive)?.content.orEmpty()
        val warnings = (preserved?.get("warnings") as? JsonPrimitive)?.content.orEmpty()
        val occurredAt = (preserved?.get("occurredAtMillis") as? JsonPrimitive)?.let { it.content.toLongOrNull() }
        val identity = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        return buildJsonObject {
            preserved?.forEach { (key, value) -> put(key, value) }
            put(
                "event",
                if (parsed == null || errors.isNotBlank()) {
                    "LAST_NATIVE_ERROR"
                } else if (warnings.isNotBlank()) {
                    "NATIVE_WARNING"
                } else {
                    "NATIVE_OPERATION_OBSERVATION"
                }
            )
            put("eventId", "native:$identity")
            put("time", occurredAt)
            put("occurredAtMillis", occurredAt)
            put("capturedAtMillis", capturedAt)
            put("occurrenceTimeSource", if (occurredAt != null) "native_operation_recorded" else "unknown_legacy_native_record")
            put("captureTimeSource", "native_record_file_modified")
            put("archivedOperation", archived)
            if (parsed == null) {
                put("stage", "LEGACY_UNKNOWN")
                put("originalError", redactLogMessage(raw))
            }
        }
    }
    val archived = File(directory, "native-error.log.history").takeIf { it.isFile && it.length() in 1..160 * 1024L }?.let { file ->
        runCatching { file.readLines().takeLast(8).filter(String::isNotBlank).map { parse(it, true, file.lastModified().takeIf { time -> time > 0 }) } }.getOrDefault(emptyList())
    }.orEmpty()
    val current = File(directory, "native-error.log").takeIf { it.isFile && it.length() in 1..20 * 1024L }?.let { file ->
        runCatching { parse(file.readText(), false, file.lastModified().takeIf { time -> time > 0 }) }.getOrNull()
    }
    return archived + listOfNotNull(current)
}
