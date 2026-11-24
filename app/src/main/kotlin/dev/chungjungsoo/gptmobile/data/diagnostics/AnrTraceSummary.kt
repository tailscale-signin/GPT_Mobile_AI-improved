package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.InputStream

internal fun anrExitFields(input: InputStream?, pid: Int): Map<String, Any?> {
    if (input == null) return mapOf("anr_trace_state" to "UNAVAILABLE")
    val prefix = ByteArray(128 * 1024)
    var size = 0
    input.use {
        while (size < prefix.size) {
            val count = it.read(prefix, size, prefix.size - size)
            if (count <= 0) break
            size += count
        }
    }
    val lines = String(prefix, 0, size, Charsets.UTF_8).lineSequence()
    val header = Regex("----- pid $pid(?: .*)? -----")
    val process = lines.dropWhile { !header.matches(it.trim()) }.drop(1).takeWhile { !it.trimStart().startsWith("-----") }
    val frames = mainThreadAnrFrames(process)
    return buildMap {
        put("anr_trace_state", if (frames.isEmpty()) "NO_MATCHING_FRAMES" else "MAIN_THREAD_CAPTURED")
        put("anr_frame_count", frames.size)
        if (frames.isNotEmpty()) put("exception_fingerprint", IssueReportContext.anrFingerprint(frames))
    }
}

/**
 * Reduces platform ANR traces to bounded, non-sensitive main-thread stack frames.
 * The raw trace may contain private application data and is never persisted/exported.
 */
internal fun mainThreadAnrFrames(lines: Sequence<String>, maxLines: Int = 3_000, maxFrames: Int = 32): List<String> {
    val frames = ArrayList<String>(maxFrames)
    var inMain = false
    for (line in lines.take(maxLines)) {
        val trimmed = line.trim()
        if (trimmed.startsWith("\"")) {
            inMain = trimmed.startsWith("\"main\"")
        }
        if (!inMain || !trimmed.startsWith("at ")) continue
        val signature = trimmed.substringBefore('(').trim()
        if (!signature.matches(Regex("at (?:dev[.]chungjungsoo[.]gptmobile|androidx[.]compose|android[.]os|kotlinx[.]coroutines|java[.]lang)[.][\\w.$<>]+"))) continue
        // Keep only class/method and source location, not quoted log statements or arbitrary text.
        val sourceLocation = trimmed.substringAfter('(', "").substringBefore(')', "")
            .takeIf { it.matches(Regex("[A-Za-z0-9_.$-]+(?::[0-9]+)?|Native Method|Unknown Source")) }
        frames += signature + (sourceLocation?.let { "($it)" } ?: "")
        if (frames.size >= maxFrames) break
    }
    return frames
}
