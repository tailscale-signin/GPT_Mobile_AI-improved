package dev.chungjungsoo.gptmobile.data.research

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Stable, privacy-safe identity for deduplicating research records across readers and engines. */
data class SourceIdentity(
    val doi: String = "",
    val pmid: String = "",
    val pmcid: String = "",
    val canonicalUrl: String = "",
    val host: String = "",
    val publicationYear: Int? = null,
    val titleFingerprint: String = "",
    val contentFingerprint: String = ""
) {
    val equivalenceKey: String
        get() = when {
            doi.isNotBlank() -> "doi:$doi"
            pmid.isNotBlank() -> "pmid:$pmid"
            pmcid.isNotBlank() -> "pmcid:$pmcid"
            canonicalUrl.isNotBlank() -> "url:$canonicalUrl"
            titleFingerprint.isNotBlank() && publicationYear != null -> "title:$titleFingerprint:$publicationYear"
            titleFingerprint.isNotBlank() -> "title:$titleFingerprint"
            contentFingerprint.isNotBlank() -> "content:$contentFingerprint"
            else -> ""
        }

    companion object {
        fun create(
            url: String,
            title: String = "",
            passage: String = "",
            doi: String = "",
            pmid: String = "",
            pmcid: String = "",
            publicationYear: Int? = null
        ): SourceIdentity {
            val normalizedDoi = normalizeDoi(doi.ifBlank { doiFromUrl(url) })
            val normalizedPmid = pmid.trim().takeIf { it.matches(Regex("[0-9]{1,12}")) }.orEmpty()
            val normalizedPmcid = pmcid.trim().uppercase(Locale.ROOT).takeIf { it.matches(Regex("PMC[0-9]{1,12}")) }.orEmpty()
            val canonical = canonicalResearchUrl(url)
            val normalizedTitle = normalizeFingerprint(title)
            val normalizedPassage = normalizeFingerprint(passage)
            return SourceIdentity(
                doi = normalizedDoi,
                pmid = normalizedPmid,
                pmcid = normalizedPmcid,
                canonicalUrl = canonical,
                host = runCatching { URI(canonical).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault(""),
                publicationYear = publicationYear?.takeIf { it in 1500..3000 },
                titleFingerprint = normalizedTitle.takeIf(String::isNotBlank)?.let(::researchHash).orEmpty(),
                contentFingerprint = normalizedPassage.takeIf(String::isNotBlank)?.let(::researchHash).orEmpty()
            )
        }

        fun canonicalResearchUrl(raw: String): String {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return ""
            val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: return ""
            val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.takeIf(String::isNotBlank) ?: return ""
            if (uri.rawUserInfo != null) return ""
            val port = uri.port.takeUnless { (scheme == "https" && it == 443) || (scheme == "http" && it == 80) || it == -1 }
            val path = uri.rawPath.orEmpty().ifBlank { "/" }
            val query = uri.rawQuery.orEmpty().split('&').asSequence()
                .filter(String::isNotBlank)
                .filterNot { pair ->
                    val key = URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8).lowercase(Locale.ROOT)
                    key.startsWith("utm_") || key in setOf("fbclid", "gclid", "mc_cid", "mc_eid", "ref", "source")
                }
                .sorted()
                .toList()
                .joinToString("&")
            return buildString {
                append(scheme).append("://").append(host)
                if (port != null) append(':').append(port)
                append(path)
                if (query.isNotEmpty()) append('?').append(query)
            }
        }

        fun normalizeDoi(raw: String): String {
            val value = raw.trim().replace(Regex("(?i)^(doi:\\s*|https?://(dx\\.)?doi\\.org/)"), "")
                .trimEnd('.', ',', ';', ')', ']')
                .lowercase(Locale.ROOT)
            return value.takeIf { it.matches(Regex("10\\.[0-9]{4,9}/[^\\s]+")) }?.take(500).orEmpty()
        }

        private fun doiFromUrl(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return ""
            return if (uri.host.equals("doi.org", ignoreCase = true) || uri.host.equals("dx.doi.org", ignoreCase = true)) uri.path.orEmpty() else ""
        }

        private fun normalizeFingerprint(text: String): String = text
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }
}

