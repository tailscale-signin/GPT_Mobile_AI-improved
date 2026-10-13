package dev.chungjungsoo.gptmobile.data.research.academic

import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import java.net.URI
import org.jsoup.Jsoup

/** Follow at most two explicit same-origin full-text HTML leads, never an access refusal. */
internal fun targetedArticleLinks(html: String, sourceUrl: String, doi: String): List<String> {
    val origin = runCatching { URI(sourceUrl) }.getOrNull() ?: return emptyList()
    if (origin.scheme != "https" || origin.userInfo != null || origin.host.isNullOrBlank()) return emptyList()
    val document = Jsoup.parse(html, sourceUrl)
    if (Regex("(?i)verify you are human|enable javascript and cookies|access denied|captcha").containsMatchIn(document.text().take(4000))) return emptyList()
    if ((document.selectFirst("article") ?: document.selectFirst("main"))?.text().orEmpty().length >= 500) return emptyList()
    val declaredDoi = document.selectFirst("meta[name=citation_doi]")?.attr("content").orEmpty()
    if (doi.isNotBlank() && declaredDoi.isNotBlank() && SourceIdentity.normalizeDoi(doi) != SourceIdentity.normalizeDoi(declaredDoi)) return emptyList()
    val declared = document.select("meta[name=citation_fulltext_html_url]").map { it.attr("content") }
    val linked = document.select("a[href]").filter { Regex("(?i)^\\s*(full[- ]?text|read (the )?(full )?article)( \\(html\\))?\\s*$").matches(it.text()) }.map { it.attr("href") }
    return (declared + linked).mapNotNull { raw ->
        runCatching { origin.resolve(raw).normalize() }.getOrNull()?.takeIf {
            it.scheme == origin.scheme &&
                it.host == origin.host &&
                it.port == origin.port &&
                it.userInfo == null &&
                it.fragment == null &&
                it != origin &&
                !it.path.endsWith(".pdf", true) &&
                !Regex("(?i)token|signature|password|authorization").containsMatchIn(it.rawQuery.orEmpty())
        }?.toString()
    }.distinct().take(2)
}
