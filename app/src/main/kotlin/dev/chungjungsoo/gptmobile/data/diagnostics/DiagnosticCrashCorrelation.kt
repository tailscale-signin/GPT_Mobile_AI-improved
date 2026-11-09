package dev.chungjungsoo.gptmobile.data.diagnostics

import java.time.Instant

/** A bounded export pre-pass links redundant crash emitters only after their stacks agree. */
internal class DiagnosticCrashCorrelation {
    private data class Crash(
        val id: String,
        val process: String,
        val session: String?,
        val source: String,
        val at: Long,
        var thread: String? = null,
        var type: String? = null,
        val frames: MutableList<String> = mutableListOf()
    )

    private val crashes = linkedMapOf<String, Crash>()
    private val aliases = linkedMapOf<String, String>()
    private val sessions = linkedMapOf<String, String>()
    var limited = false
        private set

    fun observe(incidentId: String, line: String) {
        val match = record.matchEntire(line)
        val fields = match?.groupValues?.get(4)?.let(::diagnosticFields)
        val content = match?.groupValues?.get(4)?.let(::diagnosticEventMessage)
            ?: line.replace(continuationPrefix, "").trimStart()
        if (match != null && fields != null) {
            val tag = match.groupValues[3]
            val android = diagnosticAndroidSource(match.groupValues[4])
            val source = when {
                tag == "AppCrash" -> "AppCrash"
                android?.startsWith("Android:ComposeInternal:") == true -> "ComposeInternal"
                android?.startsWith("Android:AndroidRuntime:") == true -> "AndroidRuntime"
                else -> null
            }
            val pid = android?.substringAfterLast(':')?.toLongOrNull()?.takeIf { it > 0 }
                ?: (fields.diagnosticText("processId") ?: fields.diagnosticText("process"))?.toLongOrNull()?.takeIf { it > 0 }
            val process = pid?.let { "${fields.diagnosticText("processName").orEmpty()}:$it" }
            if (tag == "Application" && process != null) {
                sessions[process] = incidentId
                while (sessions.size > 64) sessions.remove(sessions.keys.first())
            }
            val at = runCatching { Instant.parse(match.groupValues[1]).toEpochMilli() }.getOrNull()
            if (source != null && process != null && at != null && incidentId !in crashes) {
                crashes[incidentId] = Crash(incidentId, process, fields.diagnosticText("processSessionId") ?: sessions[process], source, at)
                while (crashes.size > 128) {
                    crashes.remove(crashes.keys.first())
                    limited = true
                }
            }
        }
        val crash = crashes[incidentId] ?: return
        var changed = false
        if (crash.type == null) {
            (exceptionType.find(content)?.groupValues?.get(1) ?: fields?.diagnosticText("type"))?.let {
                crash.type = it
                changed = true
            }
        }
        if (crash.thread == null) {
            (fields?.diagnosticText("thread") ?: fatalThread.matchEntire(content)?.groupValues?.get(1))?.let {
                crash.thread = it
                changed = true
            }
        }
        if (crash.frames.size < 2) {
            frame.find(content)?.groupValues?.get(1)?.let {
                crash.frames += it
                changed = true
            }
        }
        if (!changed || crash.type == null || crash.frames.size < 2) return
        val ownId = canonical(crash.id)
        val ownSources = crashes.values.filter { canonical(it.id) == ownId }.mapTo(hashSetOf()) { it.source }
        val ownThreads = crashes.values.filter { canonical(it.id) == ownId }.mapNotNullTo(hashSetOf()) { it.thread }
        val candidates = crashes.values.filter { other ->
            val otherId = canonical(other.id)
            otherId != ownId &&
                other.process == crash.process &&
                other.session == crash.session &&
                kotlin.math.abs(other.at - crash.at) <= 1000 &&
                other.type == crash.type &&
                other.frames == crash.frames &&
                (other.thread == null || crash.thread == null || other.thread == crash.thread) &&
                crashes.values.none { canonical(it.id) == otherId && it.thread != null && ownThreads.isNotEmpty() && it.thread !in ownThreads } &&
                crashes.values.none { canonical(it.id) == otherId && it.source in ownSources }
        }.map { canonical(it.id) }.distinct()
        // Ambiguous simultaneous exceptions remain separate. PID, error prose or
        // proximity alone never establishes an episode, and one emitter cannot
        // contribute two different exception occurrences to the same episode.
        if (candidates.size == 1) {
            if (aliases.size < 2048) aliases[ownId] = candidates.single() else limited = true
        }
    }

    fun canonical(id: String): String {
        var current = id
        repeat(3) { current = aliases[current] ?: return current }
        return current
    }

    fun finish() {
        crashes.clear()
        sessions.clear()
    }

    private companion object {
        val record = Regex("^(\\S+) ([VDIWEF])/([^:]+): (.*)$")
        val exceptionType = Regex("^((?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error))(?::|$)")
        val frame = Regex("(?:^| · )at ([A-Za-z0-9_.$<>-]{1,400})\\(")
        val fatalThread = Regex("^FATAL EXCEPTION:\\s*([A-Za-z0-9_.:-]{1,128})$")
        val continuationPrefix = Regex("^parentEventId=[A-Za-z0-9_.:-]+ · sequence=[0-9]+ · ")
    }
}
