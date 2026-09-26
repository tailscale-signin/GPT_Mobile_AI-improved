package dev.chungjungsoo.gptmobile.data.agent

/** Deterministic activity summary; never invents model reasoning or copies tool payloads. */
class ToolProgressTracker {
    private val completed = linkedMapOf<String, Pair<String, Boolean>>()
    fun complete(callId: String, name: String, failed: Boolean): String? {
        if (completed.putIfAbsent(callId, name to failed) != null || completed.size % INTERVAL != 0) return null
        val recent = completed.values.toList().takeLast(INTERVAL)
        val names = recent.map { it.first.replace('_', ' ').take(64) }.distinct().take(4).joinToString(", ")
        val failures = recent.count { it.second }
        return "${completed.size} tool calls finished. Recent work: $names." +
            (if (failures > 0) " $failures of the last $INTERVAL calls failed." else "") +
            " Reviewing these results before the next step."
    }
    companion object {
        const val INTERVAL = 10
        const val SUMMARY_INSTRUCTION = "Progress checkpoint: before calling more tools, write one or two short user-facing sentences describing the work completed and your next action. Wrap only this public update in <progress_update>...</progress_update>. Do not include private chain-of-thought or tool credentials. Then continue the task."
    }
}

/** Separates public progress tags across arbitrary streaming chunk boundaries. */
class PublicProgressParser {
    private var pending = ""
    private var inside = false
    fun accept(chunk: String, flush: Boolean = false): List<Pair<Boolean, String>> {
        pending += chunk
        val result = mutableListOf<Pair<Boolean, String>>()
        while (pending.isNotEmpty()) {
            val marker = if (inside) "</progress_update>" else "<progress_update>"
            val index = pending.indexOf(marker)
            if (index >= 0) {
                if (index > 0) result += inside to pending.substring(0, index)
                pending = pending.substring(index + marker.length)
                inside = !inside
            } else {
                val keep = if (flush) 0 else (1 until marker.length).lastOrNull { pending.endsWith(marker.take(it)) } ?: 0
                val length = pending.length - keep
                if (length > 0) result += inside to pending.take(length)
                pending = pending.takeLast(keep)
                break
            }
        }
        return result
    }
}
