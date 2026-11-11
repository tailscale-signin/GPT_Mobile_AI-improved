package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.InputStream
import java.time.Instant

/** Privacy-safe projection of an Android native tombstone. Raw bytes are never persisted. */
internal data class NativeCrashTraceEvidence(
    val coverage: String,
    val reason: String,
    val signal: String? = null,
    val frames: List<String> = emptyList()
) {
    fun fields(): String = buildString {
        append("nativeTraceCoverage=").append(coverage)
        append(" · nativeTraceReason=").append(reason)
        append(" · nativeSignal=").append(signal ?: "unknown")
        append(" · nativeFrameCount=").append(frames.size)
        append(" · nativeFrames=").append(frames.joinToString(",").ifEmpty { "none" })
    }
}

internal fun nativeCrashTraceEvidence(input: InputStream?): NativeCrashTraceEvidence {
    if (input == null) return NativeCrashTraceEvidence("unavailable", "no_trace_stream")
    return try {
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var remaining = MAX_NATIVE_TRACE_BYTES
            while (remaining > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            output.toByteArray()
        }
        if (bytes.isEmpty()) return NativeCrashTraceEvidence("unavailable", "empty_trace")
        val printable = bytes.count { value ->
            val unsigned = value.toInt() and 0xff
            unsigned == 9 || unsigned == 10 || unsigned == 13 || unsigned in 32..126
        }
        if (printable * 10 < bytes.size * 7) {
            return NativeCrashTraceEvidence("unavailable", "binary_tombstone_not_text")
        }
        val text = String(bytes, Charsets.UTF_8)
        val signal = signalPattern.find(text)?.groupValues?.get(1)
        val frames = text.lineSequence().take(MAX_NATIVE_TRACE_LINES).mapNotNull { line ->
            val match = framePattern.find(line) ?: return@mapNotNull null
            val library = match.groupValues[1].takeIf { it.matches(safeLibrary) } ?: return@mapNotNull null
            val symbol = match.groupValues.getOrNull(2).orEmpty().substringBefore('+').trim()
                .takeIf { it.matches(safeSymbol) }
            library + (symbol?.let { "!$it" } ?: "")
        }.distinct().take(MAX_NATIVE_FRAMES).toList()
        if (signal == null && frames.isEmpty()) {
            NativeCrashTraceEvidence("unavailable", "no_recognized_tombstone_fields")
        } else {
            NativeCrashTraceEvidence("sanitized", "signal_and_library_frames", signal, frames)
        }
    } catch (_: Exception) {
        NativeCrashTraceEvidence("unavailable", "trace_read_failed")
    }
}

internal data class NativeVoiceBreadcrumb(
    val processId: Int,
    val occurredAtMillis: Long,
    val stage: String,
    val operationId: String
) {
    fun fields(exitAtMillis: Long): String =
        "voiceNativeCorrelation=recent_same_process · voiceNativeAttribution=temporal_only · voiceNativeStage=$stage · voiceNativeOperationId=$operationId · voiceNativeDeltaMs=${(exitAtMillis - occurredAtMillis).coerceAtLeast(0)}"
}

/** Bounded index used only while exporting retained logs. */
internal class NativeVoiceBreadcrumbIndex(private val maximum: Int = 128) {
    private val entries = ArrayDeque<NativeVoiceBreadcrumb>()

    fun observe(line: String) {
        val match = diagnosticLine.matchEntire(line) ?: return
        if (match.groupValues[2] != "VoiceEngine") return
        val fields = diagnosticFields(match.groupValues[3])
        val processId = fields.diagnosticText("processId")?.toIntOrNull()?.takeIf { it > 0 } ?: return
        val operation = (fields.diagnosticText("nativeOperationId") ?: fields.diagnosticText("engineId"))
            ?.takeIf { it.matches(safeOperation) } ?: return
        val stage = (fields.diagnosticText("stage") ?: diagnosticEventMessage(match.groupValues[3]).substringBefore(" · ").removePrefix("moonshine_"))
            .takeIf { it.matches(safeStage) } ?: return
        val timestamp = runCatching { Instant.parse(match.groupValues[1]).toEpochMilli() }.getOrNull() ?: return
        entries.addLast(NativeVoiceBreadcrumb(processId, timestamp, stage, operation))
        while (entries.size > maximum) entries.removeFirst()
    }

    fun correlate(processId: Int, exitAtMillis: Long): NativeVoiceBreadcrumb? = entries.asReversed().firstOrNull {
        it.processId == processId && it.occurredAtMillis <= exitAtMillis + 1_000 && exitAtMillis - it.occurredAtMillis <= MAX_VOICE_CORRELATION_MILLIS
    }
}

private const val MAX_NATIVE_TRACE_BYTES = 128 * 1024
private const val MAX_NATIVE_TRACE_LINES = 4_000
private const val MAX_NATIVE_FRAMES = 16
private const val MAX_VOICE_CORRELATION_MILLIS = 5 * 60 * 1_000L
private val signalPattern = Regex("signal\\s+[0-9]+\\s+\\((SIG[A-Z0-9]+)\\)")
private val framePattern = Regex("#[0-9]+\\s+pc\\s+[0-9a-fA-F]+\\s+[^\\s)]*/([A-Za-z0-9_.+-]+[.]so)(?:\\s+\\(([^)]{1,200})\\))?")
private val safeLibrary = Regex("[A-Za-z0-9_.+-]{1,96}[.]so")
private val safeSymbol = Regex("[A-Za-z0-9_.:<>~+-]{1,160}")
private val safeOperation = Regex("[A-Za-z0-9_.:-]{1,128}")
private val safeStage = Regex("[a-z0-9_]{1,64}")
private val diagnosticLine = Regex("^(\\S+) [VDIWEF]/([^:]+): (.*)$")
