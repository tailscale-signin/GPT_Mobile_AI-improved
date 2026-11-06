package dev.chungjungsoo.gptmobile.data.diagnostics

/** Count repetitive noise without dropping original incidents or logging raw credentials. */
internal class DiagnosticCoalescer(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Group(
        val tag: String,
        val sample: String,
        val level: String,
        var at: Long,
        var suppressed: Int = 0,
        var firstSuppressedAt: Long? = null,
        var lastSuppressedAt: Long? = null,
        val maxima: MutableMap<String, Long> = mutableMapOf()
    )
    private val groups = linkedMapOf<String, Group>()
    private val replayMetrics = Regex("(?:^|[ ·])(?<metric>rawTokens|replayTokens|compactionUs)=(?<value>[0-9]+)")

    @Synchronized fun accept(tag: String, message: String, level: String): List<Triple<String, String, String>> {
        val key = when {
            level in setOf("E", "F") -> null
            tag == "ToolCatalog" && "PERSONAL_DATA_LOCAL_ONLY" in message -> "privacy-local-only"
            tag == "Agent" && level == "I" && message.startsWith("PRIMARY_REPLAY_COMPACTED ·") -> "primary-replay-compacted"
            tag == "Android" && listOf("OpenGLRenderer", "libEGL", "BufferQueue", "Feedback manager requires a model with a single signature").any(message::contains) -> message.take(240)
            else -> null
        } ?: return listOf(Triple(tag, message, level))
        val at = now()
        val previous = groups[key]
        if (previous == null) {
            val overflow = if (groups.size >= 128) groups.remove(groups.keys.first())?.let(::summary) else null
            val sample = if (message.length > 512) message.take(512) + " · coalescedSampleTruncated=true" else message
            groups[key] = Group(tag, sample, level, at)
            return listOfNotNull(overflow, Triple(tag, message, level))
        }
        previous.suppressed++
        if (previous.firstSuppressedAt == null) previous.firstSuppressedAt = at
        previous.lastSuppressedAt = at
        if (key == "primary-replay-compacted") {
            replayMetrics.findAll(message).forEach { match ->
                val name = match.groups["metric"]?.value ?: return@forEach
                val value = match.groups["value"]?.value?.toLongOrNull() ?: return@forEach
                previous.maxima[name] = maxOf(previous.maxima[name] ?: 0, value)
            }
        }
        if (at - previous.at < 15_000) return emptyList()
        previous.at = at
        return listOfNotNull(summary(previous))
    }
    private fun summary(group: Group): Triple<String, String, String>? {
        if (group.suppressed == 0) return null
        val peaks = group.maxima.entries.joinToString("") { (name, value) -> " · max_$name=$value" }
        val line = "Repeated diagnostic count=${group.suppressed} · firstMonotonicMs=${group.firstSuppressedAt} · lastMonotonicMs=${group.lastSuppressedAt}$peaks · sample=${group.sample}"
        group.suppressed = 0
        group.firstSuppressedAt = null
        group.lastSuppressedAt = null
        group.maxima.clear()
        return Triple(group.tag, line, group.level)
    }

    @Synchronized fun drain(): List<Triple<String, String, String>> = groups.values.mapNotNull(::summary)

    @Synchronized fun clear() = groups.clear()
}
