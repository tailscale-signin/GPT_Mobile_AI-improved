package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.SourceContentValidator
import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Resume by immutable version ID; receipts and original capture bytes remain retained. */
internal class ResearchSourceRepair(private val database: ResearchDatabase, private val bodies: ResearchSessionStore) {
    private val dao = database.research()
    suspend fun classify(version: ResearchWorkVersion, text: String): SourceContentValidator.Result {
        require(researchHash(text) == version.contentHash)
        val pubmed = runCatching { java.net.URI(version.sourceUrl).host?.lowercase() == "pubmed.ncbi.nlm.nih.gov" }.getOrDefault(false)
        val proof = dao.sourceValidations(version.versionId).firstOrNull { it.bodyHash == version.contentHash && it.validatorRevision >= 3 && it.kind == "STRUCTURED_ARTICLE" }
        val reasons = proof?.let { runCatching { Json.decodeFromString<List<String>>(it.reasonsJson) }.getOrDefault(emptyList()) }.orEmpty()
        val result = SourceContentValidator.validate(text, completeArticle = proof != null, structuredBody = proof != null, sectionNames = reasons.toSet(), truncated = if (proof != null) false else null, metadataOnly = version.coverage == ArticleCoverage.METADATA_ONLY, abstractOnly = version.coverage == ArticleCoverage.ABSTRACT_ONLY || pubmed, url = version.sourceUrl)
        val id = researchHash("${version.versionId}:${result.validatorVersion}:${result.bodyHash}:${result.kind}")
        val recorded = dao.sourceValidation(id)
        database.withTransaction {
            val work = dao.work(version.workId)
            val publication = work?.publicationType.orEmpty().lowercase()
            val kind = when {
                publication.contains("protocol") -> "PROTOCOL"
                publication.contains("guideline") -> "GUIDELINE"
                work?.preprint == true -> "PREPRINT"
                publication.contains("review") || publication.contains("meta-analysis") -> "REVIEW"
                publication.contains("trial") || publication.contains("study") -> "STUDY"
                publication.contains("editorial") -> "EDITORIAL"
                publication in setOf("journal-article", "article") -> "ARTICLE"
                publication in setOf("web-page", "web_reference", "web reference") -> "WEB_REFERENCE"
                else -> "UNKNOWN"
            }
            if (recorded == null) dao.sourceValidation(ResearchSourceValidation(id, version.versionId, result.bodyHash, result.validatorVersion, result.kind.name, Json.encodeToString(result.reasons), version.coverage, kind, System.currentTimeMillis()))
            val coverage = when (result.kind) {
                SourceContentValidator.Kind.CHALLENGE, SourceContentValidator.Kind.AUTH_REQUIRED, SourceContentValidator.Kind.EMPTY, SourceContentValidator.Kind.PREVIEW, SourceContentValidator.Kind.ERROR_PAGE, SourceContentValidator.Kind.SEARCH_RESULTS, SourceContentValidator.Kind.APPLICATION_SHELL -> ArticleCoverage.UNAVAILABLE
                SourceContentValidator.Kind.METADATA -> ArticleCoverage.METADATA_ONLY
                SourceContentValidator.Kind.ABSTRACT -> ArticleCoverage.ABSTRACT_ONLY
                SourceContentValidator.Kind.STRUCTURED_ARTICLE -> version.coverage.takeIf { it == ArticleCoverage.FULL_ARTICLE_EXAMINED } ?: ArticleCoverage.FULL_TEXT_CAPTURED
                else -> ArticleCoverage.PARTIAL_TEXT
            }
            if (coverage != version.coverage) {
                if (dao.reclassifyVersion(version.versionId, version.contentHash, version.coverage, coverage, "CONTENT_RECLASSIFIED:${result.kind}") == 1) dao.staleVersionAssessments(version.versionId)
            }
            if (!result.usable) dao.staleVersionAssessments(version.versionId)
        }
        return result
    }
    suspend fun batch(limit: Int = 32): Boolean {
        require(limit in 1..128)
        val cursor = dao.repairCursor("source-classifier")?.takeIf { it.revision == SourceContentValidator.VERSION }
        val versions = dao.repairVersions(cursor?.position.orEmpty(), limit)
        if (versions.isEmpty()) return false
        val aliases = dao.allAliases().groupBy { alias ->
            val value = alias.alias.substringAfter('|')
            if (value.startsWith("doi:")) "doi:${SourceIdentity.normalizeDoi(value.removePrefix("doi:"))}" else value.lowercase()
        }
        for (version in versions) {
            yield()
            val refs = runCatching { Json.decodeFromString<List<String>>(version.bodyChunksJson) }.getOrNull()
            if (!refs.isNullOrEmpty()) {
                val text = bodies.readFeatureBody(refs)
                if (text != null && researchHash(text) == version.contentHash) classify(version, text)
            }
            for ((alias, rows) in aliases.filterValues { list -> list.any { it.workId == version.workId } && list.map { it.workId }.distinct().size > 1 }) {
                val peers = rows.map { it.workId }.distinct()
                val public = peers.all { id -> dao.versions(id).let { it.isNotEmpty() && it.all { capture -> capture.publicSource } } } && rows.all { it.alias.startsWith("public|") }
                val canonical = peers.sorted().first()
                peers.filter { it != version.workId }.forEach { related ->
                    val id = researchHash("${version.workId}:$related:$alias:${if (public) "public" else "isolated"}")
                    dao.identityResolution(ResearchIdentityResolution(id, version.workId, related, canonical, if (public) "public" else "isolated", "MANIFESTATION_OF", alias, if (public) "PROVEN_PUBLIC_IDENTITY_LINKED" else "AUTHORITY_ISOLATED_NO_MERGE", System.currentTimeMillis()))
                }
            }
            dao.repairCursor(ResearchRepairCursor("source-classifier", version.versionId, SourceContentValidator.VERSION, System.currentTimeMillis()))
        }
        return versions.size == limit
    }
}
