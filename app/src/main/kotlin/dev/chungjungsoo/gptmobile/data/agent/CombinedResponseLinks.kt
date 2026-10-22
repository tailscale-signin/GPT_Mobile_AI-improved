package dev.chungjungsoo.gptmobile.data.agent

import java.net.URI

internal enum class CombinedLinkRole { API_ENDPOINT_EXAMPLE, DOCUMENTATION_LINK, EVIDENCE_CITATION, UNCLASSIFIED_LINK }

internal data class CombinedResponseLink(val url: String, val role: CombinedLinkRole, val warning: String? = null)

/** Link purpose never grants retrieval permission or establishes factual support. */
internal object CombinedResponseLinks {
    private val urls = Regex("https?://[^\\s<>\\[\\]{}\"`]+", RegexOption.IGNORE_CASE)
    private val markdown = Regex("\\[([^]\\n]+)]\\((https?://[^\\s)]+)\\)", RegexOption.IGNORE_CASE)
    private val citationLabel = Regex("(?i)^(?:\\d+|[SE]\\d+|sources?|evidence|citations?|references?)(?:\\s+\\d+)?$")
    private val documentation = Regex("(?i)\\b(?:docs?|documentation|developer guide|API reference)\\b")

    fun classify(text: String, registeredUrls: Set<String>): List<CombinedResponseLink> {
        val labels = markdown.findAll(text).associate { it.groupValues[2] to it.groupValues[1] }
        return urls.findAll(text).map { match ->
            val url = match.value.trimEnd('.', ',', ';', ':', ')')
            val uri = runCatching { URI(url) }.getOrNull()
            val label = labels[url].orEmpty()
            val lineStart = text.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            val prefix = text.substring(lineStart, match.range.first)
            val role = when {
                citationLabel.matches(label) || url in registeredUrls -> CombinedLinkRole.EVIDENCE_CITATION
                documentation.containsMatchIn(label) || uri?.host?.startsWith("docs.") == true || uri?.path?.matches(Regex(".*/(?:docs|documentation|reference)(?:/.*)?")) == true -> CombinedLinkRole.DOCUMENTATION_LINK
                uri?.host in setOf("localhost", "127.0.0.1", "[::1]", "::1") ||
                    uri?.path?.matches(Regex(".*/(?:v[0-9]+|api)(?:/.*)?")) == true ||
                    Regex("(?i)\\b(?:endpoint|base[_ ]?url|curl|POST|GET)\\b").containsMatchIn(prefix) -> CombinedLinkRole.API_ENDPOINT_EXAMPLE
                else -> CombinedLinkRole.UNCLASSIFIED_LINK
            }
            CombinedResponseLink(url, role, if (uri?.host == null || uri.rawUserInfo != null) "LINK_REQUIRES_REVIEW" else null)
        }.distinct().toList()
    }

    fun issues(text: String, registeredUrls: Set<String>): Set<String> = buildSet {
        classify(text, registeredUrls).forEach { link ->
            link.warning?.let(::add)
            if (link.url !in registeredUrls) {
                add(
                    when (link.role) {
                        CombinedLinkRole.EVIDENCE_CITATION -> "UNRESOLVED_CITATION_URL"
                        CombinedLinkRole.API_ENDPOINT_EXAMPLE -> "UNVERIFIED_API_ENDPOINT_EXAMPLE"
                        CombinedLinkRole.DOCUMENTATION_LINK -> "UNVERIFIED_DOCUMENTATION_LINK"
                        CombinedLinkRole.UNCLASSIFIED_LINK -> "UNVERIFIED_RESPONSE_LINK"
                    }
                )
            }
        }
    }
}
