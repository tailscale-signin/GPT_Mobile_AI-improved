package dev.chungjungsoo.gptmobile.data.diagnostics

/**
 * Bounded, allowlisted projection of Android's public tombstone protobuf. No generated
 * message retains command lines, abort messages, memory dumps, log buffers or file paths.
 * Field numbers: AOSP system/core debuggerd/proto/tombstone.proto at de173eae2f54be6cb480b74acb2b5940e7cf4e95.
 */
internal fun nativeTombstoneProto(bytes: ByteArray, truncated: Boolean, expectedProcessId: Int?): NativeCrashTraceEvidence? {
    var processId: Long? = null
    var threadId: Long? = null
    var signal: String? = null
    var recognized = false
    var malformed = false
    val root = TombstoneCursor(bytes)
    try {
        while (root.hasNext()) {
            val field = root.next()
            when (field.number) {
                5 -> processId = field.scalar
                6 -> threadId = field.scalar
                10 -> if (field.wire == 2) {
                    val info = root.child(field)
                    while (info.hasNext()) {
                        val part = info.next()
                        if (part.number == 2) signal = info.text(part, 24)?.takeIf { it.matches(Regex("SIG[A-Z0-9]{1,16}")) }
                    }
                    recognized = signal != null
                }
            }
        }
    } catch (_: InvalidTombstone) {
        malformed = true
    }
    if (!recognized && processId == null && threadId == null) return null
    if (malformed && !truncated) return NativeCrashTraceEvidence("unavailable", "malformed_protobuf", format = "protobuf")
    if (expectedProcessId != null && processId != null && processId != expectedProcessId.toLong()) {
        return NativeCrashTraceEvidence("unavailable", "trace_process_mismatch", format = "protobuf")
    }
    val frames = mutableListOf<String>()
    val locations = mutableListOf<String>()
    val builds = mutableListOf<String>()
    val threads = TombstoneCursor(bytes)
    try {
        while (threads.hasNext() && frames.size < 16) {
            val field = threads.next()
            if (field.number != 16 || field.wire != 2 || threadId == null || threadId <= 0) continue
            val entry = threads.child(field)
            var key: Long? = null
            var thread: TombstoneField? = null
            while (entry.hasNext()) {
                val part = entry.next()
                if (part.number == 1) key = part.scalar
                if (part.number == 2 && part.wire == 2) thread = part
            }
            // Other threads can contain unrelated native engines. Only the OS's crashed TID is evidence.
            if (key != threadId || thread == null) continue
            val trace = entry.child(thread)
            while (trace.hasNext() && frames.size < 16) {
                val part = trace.next()
                if (part.number != 4 || part.wire != 2) continue
                val frame = trace.child(part)
                var library: String? = null
                var symbol: String? = null
                var relativePc: Long? = null
                var buildId: String? = null
                while (frame.hasNext()) {
                    val value = frame.next()
                    when (value.number) {
                        1 -> relativePc = value.scalar
                        4 -> symbol = frame.text(value, 200)?.takeIf { it.matches(nativeSafeSymbol) }
                        6 -> library = frame.text(value, 2048)?.substringAfterLast('/')?.takeIf { it.matches(nativeSafeLibrary) }
                        8 -> buildId = frame.text(value, 128)?.takeIf { it.matches(Regex("[a-fA-F0-9]{8,128}")) }
                    }
                }
                library?.let { name ->
                    frames += name + (symbol?.let { "!$it" } ?: "")
                    relativePc?.let { locations += "$name:${java.lang.Long.toUnsignedString(it, 16)}" }
                    buildId?.let { builds += "$name:${it.lowercase()}" }
                }
            }
            break
        }
    } catch (_: InvalidTombstone) {
        if (!truncated) return NativeCrashTraceEvidence("unavailable", "malformed_protobuf", format = "protobuf")
    }
    return if (signal == null && frames.isEmpty()) {
        NativeCrashTraceEvidence("unavailable", if (truncated) "trace_limit_before_evidence" else "no_recognized_protobuf_fields", format = "protobuf")
    } else {
        NativeCrashTraceEvidence(
            if (truncated) "sanitized_partial" else "sanitized",
            if (truncated) "protobuf_prefix_limit" else "protobuf_crashed_thread",
            signal,
            frames,
            format = "protobuf",
            relativePcs = locations,
            buildIds = builds.distinct()
        )
    }
}

internal val nativeSafeLibrary = Regex("[A-Za-z0-9_.+-]{1,96}[.]so")
internal val nativeSafeSymbol = Regex("[A-Za-z0-9_.:<>~+-]{1,160}")

private class InvalidTombstone : Exception()
private data class TombstoneField(val number: Int, val wire: Int, val scalar: Long?, val start: Int, val end: Int)

/** Slices share the bounded input; unknown fields are skipped without decoding or allocating them. */
private class TombstoneCursor(private val bytes: ByteArray, private var position: Int = 0, private val end: Int = bytes.size) {
    fun hasNext(): Boolean = position < end
    fun child(field: TombstoneField) = TombstoneCursor(bytes, field.start, field.end)
    fun text(field: TombstoneField, maximum: Int): String? =
        if (field.wire == 2 && field.end - field.start in 1..maximum) String(bytes, field.start, field.end - field.start, Charsets.UTF_8) else null

    fun next(): TombstoneField {
        val tag = varint()
        if (tag <= 0 || tag ushr 3 > 536870911L) throw InvalidTombstone()
        val wire = (tag and 7).toInt()
        val number = (tag ushr 3).toInt()
        if (number == 0) throw InvalidTombstone()
        var scalar: Long? = null
        val length = when (wire) {
            0 -> {
                scalar = varint()
                0
            }
            1 -> 8
            2 -> varint().takeIf { it in 0..(end - position).toLong() }?.toInt() ?: throw InvalidTombstone()
            5 -> 4
            else -> throw InvalidTombstone()
        }
        if (length > end - position) throw InvalidTombstone()
        val start = position
        position += length
        return TombstoneField(number, wire, scalar, start, position)
    }

    private fun varint(): Long {
        var result = 0L
        for (shift in 0..63 step 7) {
            if (position >= end) throw InvalidTombstone()
            val value = bytes[position++].toInt() and 0xff
            if (shift == 63 && value > 1) throw InvalidTombstone()
            result = result or ((value and 0x7f).toLong() shl shift)
            if (value and 0x80 == 0) return result
        }
        throw InvalidTombstone()
    }
}
