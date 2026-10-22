package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal object ResearchReportReferences {
    private val grouped = Regex("\\[(S[0-9a-f]+(?:\\s*[,;]\\s*S[0-9a-f]+)+)\\]")
    private val ids = Regex("S[0-9a-f]+")
    private val citation = Regex("\\[(S[0-9a-f]+)\\]")
    private val urls = Regex("https?://[^\\s)<>]+")
    fun normalize(text: String): String = grouped.replace(text) { match -> ids.findAll(match.groupValues[1]).joinToString(" ") { "[${it.value}]" } }
    fun canonical(url: String): String = SourceIdentity.canonicalResearchUrl(url.trimEnd('.', ',', ';'))
    fun cited(text: String, packet: ResearchDeliveryPacket): Set<String> {
        val explicit = citation.findAll(normalize(text)).map { it.groupValues[1] }.toSet()
        val linked = urls.findAll(text).map { canonical(it.value) }.toSet()
        return explicit + (packet.studies + packet.unread).filter { source ->
            listOf(source.url, source.canonicalUrl, source.doi.takeIf(String::isNotBlank)?.let { "https://doi.org/$it" }.orEmpty()).filter(String::isNotBlank).any { canonical(it) in linked }
        }.map { it.id }
    }

    /** Only exact registered bibliography is navigation; a References heading cannot hide later findings. */
    fun body(text: String, packet: ResearchDeliveryPacket): String {
        val known = (packet.studies + packet.unread).associateBy { it.id }
        return normalize(text).lineSequence().joinToString("\n") { line ->
            val source = cited(line, packet).singleOrNull()?.let(known::get)
            if (source == null) return@joinToString line
            val allowedUrls = listOf(source.url, source.canonicalUrl, source.doi.takeIf(String::isNotBlank)?.let { "https://doi.org/$it" }.orEmpty())
                .filter(String::isNotBlank).map(::canonical).toSet()
            if (urls.findAll(line).any { canonical(it.value) !in allowedUrls }) return@joinToString line
            var remaining = line.trim().replace(Regex("^(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)"), "")
            remaining = citation.replace(urls.replace(remaining, " "), " ")
            val metadata = ResearchDeliveryPacket.bibliography(source).filterKeys { it in setOf("authors", "journal", "publicationDate", "year") }.values.flatMap { value ->
                when (value) {
                    is JsonPrimitive -> listOf(value.content)
                    is JsonArray -> value.filterIsInstance<JsonPrimitive>().map { it.content }
                    else -> emptyList()
                }
            }
            val fragments = (metadata + listOf(source.title, source.doi, source.pmid, source.pmcid, source.publishedAt, source.publicationYear?.toString().orEmpty()))
                .filter(String::isNotBlank).distinct().sortedByDescending(String::length)
            fragments.forEach { fragment ->
                remaining = Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(fragment)}(?![\\p{L}\\p{N}])").replace(remaining, " ")
            }
            remaining = remaining.replace(Regex("(?i)\\b(?:DOI|PMID|PMCID)\\s*:"), " ")
            if (remaining.none(Char::isLetterOrDigit)) "" else line
        }
    }

    fun render(text: String, packet: ResearchDeliveryPacket): String {
        val normalized = normalize(text)
        val cited = cited(body(normalized, packet), packet)
        val missing = (packet.studies + packet.unread).filter { it.id in cited && it.url !in normalized && it.canonicalUrl !in normalized }
        if (missing.isEmpty()) return normalized
        return normalized + "\n\n### Source links\n\n" + missing.joinToString("\n") { "[${it.id}] ${it.url}" }
    }
}
