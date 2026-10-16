package dev.chungjungsoo.gptmobile.data.diagnostics

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
