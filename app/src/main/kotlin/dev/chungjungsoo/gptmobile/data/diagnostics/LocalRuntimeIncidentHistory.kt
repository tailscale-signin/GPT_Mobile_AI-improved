package dev.chungjungsoo.gptmobile.data.diagnostics

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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

    @Synchronized fun record(event: String, fields: Map<String, String> = emptyMap()) {
        val folder = directory ?: return
        if (android.os.Looper.getMainLooper()?.let { it == android.os.Looper.myLooper() } == true) {
            scope.launch { record(event, fields) }
            return
        }
        runCatching {
            check(folder.isDirectory || folder.mkdirs())
            RandomAccessFile(File(folder, "history.lock"), "rw").use { lock ->
                lock.channel.lock().use {
                    val file = File(folder, "history.jsonl")
                    val previous = if (file.isFile && file.length() <= 512 * 1024) file.readLines().takeLast(95) else emptyList()
                    val row = buildJsonObject {
                        put("time", System.currentTimeMillis())
                        put("event", event.take(80))
                        put("process", android.os.Process.myPid())
                        put("fields", JsonObject(fields.entries.take(40).associate { it.key.take(80) to JsonPrimitive(redactLogMessage(it.value).take(2000)) }))
                    }.toString()
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
        val file = directory?.let { File(it, "history.jsonl") } ?: return emptyList()
        val history = if (!file.isFile || file.length() > 512 * 1024) emptyList() else file.readLines().takeLast(96).mapNotNull { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val native = File(file.parentFile, "native-error.log").takeIf { it.isFile && it.length() in 1..4096 }?.let {
            buildJsonObject {
                put("time", it.lastModified())
                put("event", "LAST_NATIVE_ERROR")
                put("originalError", redactLogMessage(it.readText()))
            }
        }
        history + listOfNotNull(native)
    }.getOrDefault(emptyList())
}
