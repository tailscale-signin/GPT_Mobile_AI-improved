package dev.chungjungsoo.gptmobile.data.research.engines

import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicSearchRequest
import dev.chungjungsoo.gptmobile.data.research.academic.academicPaperId
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** NCBI ESearch + batched EFetch. Jsoup never resolves external entities or DTDs. */
internal object PubMedEngineAdapter {
    const val ORIGIN = "https://eutils.ncbi.nlm.nih.gov"
    const val RETRIEVABLE_LIMIT = 10_000
    data class SearchPage(val ids: List<String>, val total: Long)

    fun searchUrl(request: AcademicSearchRequest): String {
        val size = minOf(request.maxResults, 20)
        val start = Math.multiplyExact(request.page.toLong() - 1, size.toLong())
        require(start < RETRIEVABLE_LIMIT) { "PubMed retrieves only the first 10,000 matches; refine or partition the query" }
        val query = buildString {
            append('(').append(request.query).append(')')
            if (request.fromYear != null || request.toYear != null) {
                append(" AND (").append(request.fromYear ?: 1800).append(':').append(request.toYear ?: 3000).append("[dp])")
            }
        }
        return "$ORIGIN/entrez/eutils/esearch.fcgi?db=pubmed&retmode=json&tool=GPTMobile&term=${encode(query)}&retstart=$start&retmax=${minOf(size.toLong(), RETRIEVABLE_LIMIT - start)}"
    }

    fun parsePage(body: String): SearchPage {
        val root = Json.parseToJsonElement(body).jsonObject
        require(root["error"] == null) { "PubMed rejected the search" }
        val result = requireNotNull(root["esearchresult"]).jsonObject
        require(result["ERROR"] == null && result["errorlist"] == null) { "PubMed query is invalid" }
        val ids = result.getValue("idlist").jsonArray.map { it.jsonPrimitive.content }
        require(ids.size <= 20 && ids.all { it.matches(Regex("[0-9]{1,12}")) }) { "Invalid PubMed identifier page" }
        return SearchPage(ids.distinct(), result.getValue("count").jsonPrimitive.content.toLong().also { require(it >= 0) })
    }

    fun fetchUrl(ids: List<String>): String {
        require(ids.isNotEmpty() && ids.size <= 20 && ids.all { it.matches(Regex("[0-9]{1,12}")) })
        return "$ORIGIN/entrez/eutils/efetch.fcgi?db=pubmed&retmode=xml&tool=GPTMobile&id=${ids.joinToString(",") }"
    }

    fun parseRecords(body: String): List<AcademicPaper> {
        require(body.toByteArray().size <= 2 * 1024 * 1024 && !body.contains("<!ENTITY", ignoreCase = true)) { "Unsafe or oversized PubMed XML" }
        val document = Jsoup.parse(body, "", Parser.xmlParser())
        require(document.selectFirst("PubmedArticleSet") != null && document.selectFirst("ERROR") == null) { "Invalid PubMed XML" }
        return document.select("PubmedArticle").mapNotNull { record ->
            val citation = record.selectFirst("MedlineCitation") ?: return@mapNotNull null
            val pmid = citation.selectFirst("PMID")?.text().orEmpty()
            val article = citation.selectFirst("Article") ?: return@mapNotNull null
            val title = article.selectFirst("ArticleTitle")?.text().orEmpty()
            if (!pmid.matches(Regex("[0-9]{1,12}")) || title.isBlank()) return@mapNotNull null
            val ids = record.select("PubmedData > ArticleIdList > ArticleId").associate { it.attr("IdType").lowercase() to it.text() }
            val doi = SourceIdentity.normalizeDoi(ids["doi"].orEmpty())
            val pmcid = ids["pmc"].orEmpty().uppercase().takeIf { it.matches(Regex("PMC[0-9]+")) }
            val canonical = "https://pubmed.ncbi.nlm.nih.gov/$pmid/"
            val identifiers = buildMap {
                put("pmid", pmid)
                pmcid?.let { put("pmcid", it) }
            }
            val date = article.selectFirst("Journal > JournalIssue > PubDate")
            val year = date?.selectFirst("Year")?.text()?.toIntOrNull()
                ?: Regex("[12][0-9]{3}").find(date?.text().orEmpty())?.value?.toIntOrNull()
            val types = article.select("PublicationType").map { it.text() }
            val abstract = article.select("Abstract > AbstractText").joinToString("\n\n") {
                val label = it.attr("Label")
                (if (label.isBlank()) "" else "$label: ") + it.text()
            }
            AcademicPaper(
                paperId = academicPaperId(doi, identifiers, canonical), title = title, canonicalUrl = canonical,
                doi = doi, identifiers = identifiers,
                authors = article.select("AuthorList > Author").map { author ->
                    author.selectFirst("CollectiveName")?.text()
                        ?: listOfNotNull(author.selectFirst("ForeName")?.text(), author.selectFirst("LastName")?.text()).joinToString(" ")
                }.filter(String::isNotBlank),
                year = year, publicationType = types.joinToString("; ").ifBlank { "unknown" },
                isRetracted = true.takeIf { types.any { it.equals("Retracted Publication", true) } },
                sourceAccess = if (abstract.isBlank()) "metadata_only" else "abstract_only", abstract = abstract,
                journal = article.selectFirst("Journal > Title")?.text().orEmpty(),
                subjects = citation.select("MeshHeading > DescriptorName, KeywordList > Keyword").map { it.text() }.distinct(),
                publicationDate = date?.text().orEmpty(), providerRecordId = "MED:$pmid",
                relatedVersions = citation.select("CommentsCorrectionsList > CommentsCorrections").mapNotNull { relation ->
                    relation.selectFirst("PMID")?.text()?.let { "${relation.attr("RefType")}:pmid:$it" }
                }
            )
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
