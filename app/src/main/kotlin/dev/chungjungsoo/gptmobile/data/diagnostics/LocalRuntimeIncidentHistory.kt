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

    @Volatile private var directory: Lazy<File>? = null
    private val persistenceFailures = java.util.concurrent.atomic.AtomicLong()
    fun initialize(context: Context) {
        directory = lazy { File(context.noBackupFilesDir, "local-runtime-incidents") }
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
        val location = directory ?: return
        runCatching {
            val folder = location.value
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
        }.onFailure { persistenceFailures.incrementAndGet() }
    }

    fun snapshot(): List<JsonObject> = capture().records

    fun capture(): RuntimeIncidentCapture {
        val capturedAt = System.currentTimeMillis()
        val location = directory ?: return RuntimeIncidentCapture("UNAVAILABLE", capturedAt, emptyList(), persistenceFailures.get())
        return runCatching {
            RuntimeIncidentCapture(if (persistenceFailures.get() > 0) "PARTIAL" else "AVAILABLE", capturedAt, readSnapshot(location.value), persistenceFailures.get())
        }.getOrElse { RuntimeIncidentCapture("FAILED", capturedAt, emptyList(), persistenceFailures.get()) }
    }

    private fun readSnapshot(folder: File): List<JsonObject> {
        val file = File(folder, "history.jsonl")
        check(!file.isFile || file.length() <= 512 * 1024) { "INCIDENT_HISTORY_BOUND_EXCEEDED" }
        val history = if (!file.isFile) emptyList() else file.readLines().takeLast(96).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it) as JsonObject }
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
        return history + native + terminations
    }
}

internal data class RuntimeIncidentCapture(val availability: String, val capturedAtMillis: Long, val records: List<JsonObject>, val persistenceFailures: Long) {
    val metadata: JsonObject get() = buildJsonObject {
        put("kind", "runtime_incident_capture")
        put("availability", if (availability == "AVAILABLE" && records.isEmpty()) "EMPTY_RETAINED" else availability)
        put("collectionInitialized", availability != "UNAVAILABLE")
        put("capturedAtMillis", capturedAtMillis)
        put("retainedRecords", records.size)
        put("persistenceFailures", persistenceFailures)
        put("scope", "Retained local model runtime, native operation and worker termination history; application crashes are captured separately in critical-app-logs.jsonl. Empty runtime history does not establish an absence of app crashes or other session failures.")
        put("applicationCrashEvidence", "critical-app-logs.jsonl")
    }
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
        val identity = DiagnosticRecordChunks.sha256(raw)
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
