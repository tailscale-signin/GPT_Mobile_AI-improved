package dev.chungjungsoo.gptmobile.data.agent

import java.net.URI

/** Turn-scoped reader circuits. Failures from one reader never suppress a healthy fallback. */
class ResearchReaderHealth(private val now: () -> Long = System::currentTimeMillis) {
    private val disabled = mutableSetOf<String>()
    private val malformed = mutableMapOf<String, Int>()
    private val negatives = linkedMapOf<Pair<String, String>, Long>()

    @Synchronized
    fun available(reader: String, url: String): Boolean {
        val timestamp = now()
        negatives.entries.removeAll { it.value <= timestamp }
        return reader !in disabled &&
            (reader to "host:${host(url)}") !in negatives &&
            (reader to "page:${page(url)}") !in negatives
    }

    @Synchronized
    fun record(reader: String, url: String, outcome: ToolOutcome, billingFailure: Boolean = false, pageFailure: Boolean = false, transientFailure: Boolean = false) {
        // A device DNS outage says nothing about this reader or publication.
        if (transientFailure) return
        when {
            billingFailure || outcome == ToolOutcome.AUTH_FAILED -> disabled += reader
            outcome == ToolOutcome.INVALID_RESPONSE -> {
                malformed[reader] = (malformed[reader] ?: 0) + 1
                if (malformed.getValue(reader) >= 2) disabled += reader
            }
            outcome == ToolOutcome.SUCCESS || outcome == ToolOutcome.PARTIAL -> malformed.remove(reader)
        }
        if (outcome in setOf(ToolOutcome.BLOCKED, ToolOutcome.RATE_LIMITED, ToolOutcome.EMPTY, ToolOutcome.FAILED)) {
            // Empty pages, consent shells and individual request failures do not
            // establish that every other article on this host is unavailable.
            val scope = if (!pageFailure && outcome in setOf(ToolOutcome.BLOCKED, ToolOutcome.RATE_LIMITED)) {
                "host:${host(url)}"
            } else {
                "page:${page(url)}"
            }
            negatives[reader to scope] = now() + 5 * 60 * 1000L
            // Bounded negative cache; eviction changes retry efficiency, never access policy.
            while (negatives.size > 256) negatives.remove(negatives.keys.first())
        }
    }

    private fun page(url: String): String = url.substringBefore('#').take(2048)

    private fun host(url: String): String = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: url.take(2048)
}
