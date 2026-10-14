package dev.chungjungsoo.gptmobile.data.research.engines

import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup

internal data class CitationPreview(val title: String, val identifier: String = "", val url: String = "", val verified: Boolean = false)

/** User-selected text only. No clipboard polling, browser cookies, scripts or network calls. */
internal object ScholarCitationImport {
    fun scholarUrl(query: String): String {
        require(query.trim().length in 1..500)
        return "https://scholar.google.com/scholar?q=${URLEncoder.encode(query.trim(), "UTF-8")}"
    }

    fun parse(text: String): List<CitationPreview> {
        require(text.toByteArray().size in 1..262144) { "Citation import must be at most 256 KiB" }
        require(!Regex("(?is)<script|<!ENTITY").containsMatchIn(text)) { "Executable or unsafe citation content is not supported" }
        val input = text.trim()
        val records = when {
            input.startsWith("@") -> bibtex(input)
            Regex("(?m)^TY  -").containsMatchIn(input) -> input.split(Regex("(?m)^ER  -.*$")).filter(String::isNotBlank).map { record ->
                val fields = record.lines().mapNotNull { line -> Regex("^([A-Z0-9]{2})  - (.*)$").find(line)?.let { it.groupValues[1] to it.groupValues[2].trim() } }.toMap()
                preview(fields["TI"] ?: fields["T1"].orEmpty(), fields["DO"].orEmpty(), fields["UR"].orEmpty())
            }
            else -> listOf(preview(input, input, input.takeIf { it.startsWith("https://") }.orEmpty()))
        }
        require(records.isNotEmpty() && records.size <= 100 && records.all { it.title.isNotBlank() }) { "No valid citations found (maximum 100 per import)" }
        return records.distinctBy { it.identifier.ifBlank { it.url.ifBlank { it.title.lowercase() } } }
    }

    private fun preview(title: String, identifier: String, url: String): CitationPreview {
        val clean = Jsoup.parse(title).text().trim().take(2000)
        val doi = SourceIdentity.normalizeDoi(identifier)
        val pmid = identifier.trim().removePrefix("PMID:").removePrefix("pmid:").trim()
        val normalized = when {
            doi.isNotBlank() -> "doi:$doi"
            pmid.matches(Regex("[0-9]{1,12}")) -> "pmid:$pmid"
            pmid.uppercase().matches(Regex("PMC[0-9]{1,12}")) -> "pmcid:${pmid.uppercase()}"
            else -> ""
        }
        if (url.isNotBlank()) {
            val uri = URI(url)
            require(
                uri.scheme == "https" &&
                    uri.userInfo == null &&
                    uri.port in setOf(-1, 443) &&
                    dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveIngestor.publicUrl(url)
            ) { "Citation URL must be a public HTTPS URL without credentials" }
        }
        return CitationPreview(clean, normalized, url)
    }

    private fun bibtex(input: String): List<CitationPreview> {
        val records = mutableListOf<CitationPreview>()
        var offset = 0
        while (offset < input.length) {
            val start = input.indexOf('@', offset)
            if (start < 0) break
            val open = input.indexOf('{', start)
            require(open > start) { "Malformed BibTeX record" }
            var depth = 1
            var end = open + 1
            while (end < input.length && depth > 0) {
                if (input[end] == '{' && input[end - 1] != '\\') depth++
                if (input[end] == '}' && input[end - 1] != '\\') depth--
                end++
            }
            require(depth == 0) { "Unclosed BibTeX record" }
            val body = input.substring(open + 1, end - 1)
            fun field(name: String): String {
                val match = Regex("(?i)(?:^|,)\\s*$name\\s*=\\s*").find(body) ?: return ""
                val pos = match.range.last + 1
                if (pos >= body.length) return ""
                val delimiter = body[pos]
                if (delimiter == '{') {
                    var nesting = 1
                    var cursor = pos + 1
                    while (cursor < body.length && nesting > 0) {
                        if (body[cursor] == '{' && body[cursor - 1] != '\\') nesting++
                        if (body[cursor] == '}' && body[cursor - 1] != '\\') nesting--
                        cursor++
                    }
                    require(nesting == 0)
                    return body.substring(pos + 1, cursor - 1).replace("{", "").replace("}", "")
                }
                if (delimiter == '"') return body.substring(pos + 1).substringBefore('"')
                return body.substring(pos).substringBefore(',').trim()
            }
            records += preview(field("title"), field("doi"), field("url"))
            require(records.size <= 100)
            offset = end
        }
        return records
    }
}
