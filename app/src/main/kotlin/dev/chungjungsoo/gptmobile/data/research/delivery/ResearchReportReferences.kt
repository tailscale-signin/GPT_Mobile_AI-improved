package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.research.SourceIdentity

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
    fun render(text: String, packet: ResearchDeliveryPacket): String {
        val normalized = normalize(text)
        val cited = cited(normalized.substringBefore(Regex("(?im)^#{1,6}\\s+references\\s*$").find(normalized)?.value ?: "\u0000"), packet)
        val missing = (packet.studies + packet.unread).filter { it.id in cited && it.url !in normalized && it.canonicalUrl !in normalized }
        if (missing.isEmpty()) return normalized
        return normalized + "\n\n### Source links\n\n" + missing.joinToString("\n") { "[${it.id}] ${it.url}" }
    }
}
