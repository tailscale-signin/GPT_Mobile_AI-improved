package dev.chungjungsoo.gptmobile.data.research.academic

import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

@Serializable
data class AcademicPaper(
    val paperId: String,
    val title: String,
    val canonicalUrl: String,
    val doi: String = "",
    val identifiers: Map<String, String> = emptyMap(),
    val authors: List<String> = emptyList(),
    val year: Int? = null,
    val publicationType: String = "unknown",
    val reviewStatus: String = "unknown",
    val isPreprint: Boolean? = null,
    val isRetracted: Boolean? = null,
    val sourceAccess: String = "metadata_only",
    val openAccessUrl: String? = null,
    val sourceProviders: List<String> = emptyList(),
    val providerReceipts: List<String> = emptyList(),
    val versionGroup: String = paperId,
    val versionId: String = paperId,
    val abstract: String = "",
    val contentLicense: String? = null,
    val relatedVersions: List<String> = emptyList()
)

@Serializable
data class AcademicProviderStatus(val id: String, val state: String, val fetched: Int = 0, val cacheHit: Boolean = false, val retryAtMs: Long? = null, val limitation: String? = null, val receiptId: String = "", val owner: String = "native")

@Serializable
data class AcademicSearchResult(
    val schemaVersion: Int = 1,
    val searchId: String,
    val query: String,
    val status: String,
    val papers: List<AcademicPaper>,
    val providers: List<AcademicProviderStatus>,
    val truncated: Boolean = false,
    val nextCursor: String? = null,
    val totalDistinctPapers: Int = papers.size
)

@Serializable
data class AcademicEvidence(
    val paperId: String,
    val versionId: String,
    val sourceUrl: String,
    val retrievedAt: Long,
    val textHash: String,
    val text: String,
    val retrievalStatus: String,
    val section: String = "Extracted text",
    val contentLicense: String? = null,
    val providerReceipts: List<String> = emptyList()
) {
    fun verify(quote: String, requestedVersion: String): String = when {
        requestedVersion != versionId -> "version_mismatch"
        retrievalStatus !in setOf("read", "partially_read", "abstract_only") -> "not_read"
        textHash != researchHash(text) -> "snapshot_integrity_failed"
        quote.isBlank() || quote.length > 4000 -> "invalid_quote"
        text.indexOf(quote) < 0 -> "quote_not_found"
        else -> "quote_verified"
    }
}

internal fun academicPaperId(doi: String = "", ids: Map<String, String> = emptyMap(), url: String): String {
    SourceIdentity.normalizeDoi(doi).takeIf(String::isNotBlank)?.let { return "doi:$it" }
    listOf("pmcid", "pmid", "arxiv", "acl", "openreview", "zenodo").forEach { key -> ids[key]?.takeIf(String::isNotBlank)?.let { return "$key:$it" } }
    return "url:${SourceIdentity.canonicalResearchUrl(url)}"
}

/** Exact version keys only: title similarity never merges a preprint with published text. */
internal fun mergeAcademicPapers(papers: List<AcademicPaper>): List<AcademicPaper> = papers.groupBy { it.paperId to it.versionId }.values.map { group ->
    val first = group.first()
    first.copy(
        sourceProviders = group.flatMap { it.sourceProviders }.distinct(),
        providerReceipts = group.flatMap { it.providerReceipts }.distinct(),
        openAccessUrl = group.firstNotNullOfOrNull { it.openAccessUrl },
        isRetracted = if (group.any { it.isRetracted == true }) true else first.isRetracted,
        relatedVersions = group.flatMap { it.relatedVersions }.distinct()
    )
}
