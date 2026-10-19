package dev.chungjungsoo.gptmobile.data.agent

import java.net.URI
import java.util.Locale

/** Derives finite evidence questions from the accepted request, never from newly discovered URLs. */
object CombinedWorkInventory {
    private val sourceCount = Regex("(?i)\\b(\\d{1,7})\\s+(?:distinct\\s+|different\\s+|independent\\s+|reliable\\s+|primary\\s+)*(?:sources?|papers?|studies|articles?|reports?|references?)\\b")
    private val urlPattern = Regex("https?://[^\\s<>\\\"\\[\\]{}]+", RegexOption.IGNORE_CASE)
    private val numbered = Regex("^\\s*(?:\\d{1,4}[.)]|[-*•])\\s+(.{8,})$")
    private val words = Regex("[\\p{L}\\p{N}]{3,}")
    private val ignored = setOf("the", "and", "with", "for", "that", "this", "from", "what", "which", "when", "where", "how", "are", "can", "you", "please", "find", "search", "research", "sources", "source", "compare", "explain", "about", "provide", "give", "all", "into", "using", "include", "use", "more", "most", "than", "their", "then")

    fun derive(requested: CombinedRequestedPolicy, toolsAllowed: Boolean): List<CombinedWorkItem> {
        if (!toolsAllowed && requested.acceptedResearch == null) return emptyList()
        val prompt = requested.userPrompt.trim()
        if (prompt.isEmpty()) return emptyList()
        val explicitUrls = urls(prompt)
        val scopes = prompt.lineSequence().mapNotNull { numbered.matchEntire(it)?.groupValues?.get(1) }.toList()
            .ifEmpty { prompt.split(Regex("(?<=[?])\\s+|\\n{2,}")).map(String::trim).filter { it.length >= 8 } }
            .ifEmpty { listOf(prompt) }
        // A long request is grouped without dropping its remaining clauses or inventing new goals.
        val grouped = if (scopes.size > 32) scopes.chunked((scopes.size + 31) / 32).map { it.joinToString("\n") } else scopes
        val explicitCount = listOfNotNull(sourceCount.findAll(prompt).maxOfOrNull { it.groupValues[1].toLong() }, requested.acceptedResearch?.sourceGoal).maxOrNull()
        val defaultUnits = listOf(1L, 2L, 4L, 8L, 12L)[requested.settings.normalized().effort / 25]
        val perScope = explicitCount?.let { (it + grouped.size - 1) / grouped.size } ?: defaultUnits
        return grouped.mapIndexed { index, description ->
            val scopeUrls = urls(description).ifEmpty { if (grouped.size == 1) explicitUrls else emptyList() }
            CombinedWorkItem(
                id = "evidence:${index + 1}:${combinedDigest(description).take(16)}",
                role = CombinedRunRole.RESEARCH,
                remainingUnits = maxOf(1, perScope, scopeUrls.size.toLong()),
                required = explicitCount != null || scopeUrls.isNotEmpty(),
                reason = when {
                    requested.acceptedResearch != null -> "ACCEPTED_RESEARCH_REQUIREMENT"
                    explicitCount != null || scopeUrls.isNotEmpty() -> "EXPLICIT_SOURCE_REQUIREMENT"
                    else -> "BOUNDED_REQUEST_QUESTION"
                },
                kind = CombinedWorkKind.EVIDENCE,
                description = description,
                sourceUrls = scopeUrls
            )
        }
    }

    fun terms(text: String): Set<String> = words.findAll(text.lowercase(Locale.ROOT)).map { it.value }.filterNot { it in ignored }.toSet()

    fun urls(text: String): List<String> = urlPattern.findAll(text).mapNotNull { canonicalUrl(it.value) }.distinct().toList()

    fun canonicalUrl(value: String): String? = runCatching {
        val uri = URI(value.trimEnd('.', ',', ';', ':', ')', '!', '?'))
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        URI(uri.scheme.lowercase(Locale.ROOT), uri.userInfo, uri.host.lowercase(Locale.ROOT), uri.port, uri.path.ifBlank { "/" }, uri.query, null).toASCIIString()
    }.getOrNull()
}
