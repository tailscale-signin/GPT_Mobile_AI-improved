package dev.chungjungsoo.gptmobile.data.diagnostics

/** One cursor per capture source, with an export-wide duplicate set. */
internal class DiagnosticCaptureCursor(private val source: String, private val seen: MutableSet<String>) {
    data class Record(val identity: String, val parent: String?, val sequence: Int, val duplicate: Boolean)
    private var parent: String? = null
    private var sequence = 0

    fun accept(line: String, index: Int): Record {
        val root = ROOT.containsMatchIn(line)
        if (root) {
            parent = EVENT.find(line)?.groupValues?.get(1) ?: "$source:$index"
            sequence = 0
        } else {
            PARENT.find(line)?.groupValues?.get(1)?.let { parent = it }
            sequence = SEQUENCE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: (sequence + 1)
        }
        val identity = if (root) requireNotNull(parent) else "${parent ?: source}:$sequence"
        return Record(identity, parent.takeUnless { root }, sequence, !seen.add(identity))
    }

    private companion object {
        val ROOT = Regex("^\\S+ [VDIWEF]/[^:]+: ")
        val EVENT = Regex("(?:^|\\s)eventId=([a-zA-Z0-9_.:-]+)")
        val PARENT = Regex("(?:^|\\s)parentEventId=([a-zA-Z0-9_.:-]+)")
        val SEQUENCE = Regex("(?:^|\\s)sequence=([0-9]+)")
    }
}
