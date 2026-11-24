package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Up to five complete runs survive navigation/process restarts within an app-private byte bound. */
internal class VoiceSpeechBenchmarkStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "voice-speech-benchmarks"))
    private val file = AtomicFile(File(directory, "results-v1.json"))

    fun read(): List<VoiceSpeechBenchmarkReport> = synchronized(lock) { readLocked() }

    fun save(report: VoiceSpeechBenchmarkReport): Boolean = synchronized(lock) {
        var reports = (listOf(report) + readLocked().filterNot { it.startedAtMillis == report.startedAtMillis }).take(5)
        runCatching {
            check(directory.isDirectory || directory.mkdirs())
            while (true) {
                var stream: java.io.FileOutputStream? = null
                try {
                    stream = file.startWrite()
                    val destination = stream
                    var bytes = 0L
                    val bounded = object : java.io.OutputStream() {
                        override fun write(value: Int) {
                            if (++bytes > MAX_BYTES) throw HistoryTooLarge()
                            destination.write(value)
                        }
                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            bytes += length
                            if (bytes > MAX_BYTES) throw HistoryTooLarge()
                            destination.write(buffer, offset, length)
                        }
                        override fun flush() = destination.flush()
                    }
                    bounded.write('['.code)
                    reports.forEachIndexed { index, result ->
                        if (index != 0) bounded.write(','.code)
                        writeVoiceSpeechBenchmarkExport(result, bounded, local = true)
                    }
                    bounded.write(']'.code)
                    file.finishWrite(stream)
                    break
                } catch (tooLarge: HistoryTooLarge) {
                    file.failWrite(stream)
                    // Evict complete older runs; never truncate the latest measured sample.
                    check(reports.size > 1) { "The latest speech benchmark exceeds the history limit." }
                    reports = reports.dropLast(1)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
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
                        energyObservations = report.energyObservations.map { if (it.end == null) it.copy(interrupted = true) else it },
                        results = report.results.map { result ->
                            if (result.state in setOf(VoiceSpeechBenchmarkBackendState.PENDING, VoiceSpeechBenchmarkBackendState.PREPARING, VoiceSpeechBenchmarkBackendState.RUNNING)) {
                                result.copy(state = VoiceSpeechBenchmarkBackendState.NOT_RUN)
                            } else {
                                result
                            }
                        },
                        engineResults = report.engineResults.map { result ->
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

    private class HistoryTooLarge : java.io.IOException()

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        private val lock = Any()
    }
}
