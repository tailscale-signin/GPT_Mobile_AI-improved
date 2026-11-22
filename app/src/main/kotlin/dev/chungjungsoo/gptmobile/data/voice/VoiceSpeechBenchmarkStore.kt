package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The last five runs survive navigation/process restarts. Storage is app-private and excluded from backup. */
internal class VoiceSpeechBenchmarkStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "voice-speech-benchmarks"))
    private val file = AtomicFile(File(directory, "results-v1.json"))

    fun read(): List<VoiceSpeechBenchmarkReport> = synchronized(lock) { readLocked() }

    fun save(report: VoiceSpeechBenchmarkReport): Boolean = synchronized(lock) {
        val reports = (listOf(report) + readLocked().filterNot { it.startedAtMillis == report.startedAtMillis }).take(5)
        runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val bytes = Json.encodeToString(JsonArray.serializer(), JsonArray(reports.map { speechBenchmarkJson(it, local = true) })).toByteArray()
            check(bytes.size <= MAX_BYTES)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }.isSuccess
    }

    private fun readLocked(): List<VoiceSpeechBenchmarkReport> = runCatching {
        file.openRead().use { input ->
            // readNBytes is unavailable on Android 12 (our minimum supported version).
            val buffer = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) {
                    val next = input.read()
                    if (next < 0) break
                    buffer[count++] = next.toByte()
                } else {
                    count += read
                }
            }
            if (count > MAX_BYTES) return emptyList()
            val bytes = buffer.copyOf(count)
            val values = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray
            if (values.size > 5) return emptyList()
            values.mapNotNull { speechBenchmarkReportFromJson(it.jsonObject) }.map { report ->
                if (report.isRunning) {
                    report.copy(
                        state = VoiceSpeechBenchmarkRunState.INTERRUPTED,
                        results = report.results.map { result ->
                            if (result.state in setOf(VoiceSpeechBenchmarkBackendState.PENDING, VoiceSpeechBenchmarkBackendState.PREPARING, VoiceSpeechBenchmarkBackendState.RUNNING)) {
                                result.copy(state = VoiceSpeechBenchmarkBackendState.NOT_RUN)
                            } else {
                                result
                            }
                        }
                    )
                } else {
                    report
                }
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
        private val lock = Any()
    }
}
