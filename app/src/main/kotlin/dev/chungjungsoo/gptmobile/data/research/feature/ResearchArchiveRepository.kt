package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class ResearchArchiveRepository @Inject constructor(val database: ResearchDatabase, private val bodies: ResearchSessionStore) {
    val dao = database.research()
    suspend fun initialize() = ResearchArchiveIngestor.seed(database)
    suspend fun page(query: String = "", topic: String = "", peerReviewedOnly: Boolean = false, offset: Int = 0): List<ArchiveRow> {
        initialize()
        return dao.page(query.trim().take(300).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"), topic, peerReviewedOnly, 40, offset.coerceAtLeast(0))
    }

    /** Metadata leads only. Every factual reuse still goes through a newly authorized source read. */
    suspend fun preflight(query: String, allowed: (String) -> Boolean, notBefore: Long? = null, peerReviewedOnly: Boolean = false): List<ArchiveRow> {
        initialize()
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.distinct().take(20)
        val candidates = (page(query.take(200), peerReviewedOnly = peerReviewedOnly) + words.take(5).flatMap { page(it, peerReviewedOnly = peerReviewedOnly) }).distinctBy { it.workId }
        return candidates.filter { allowed(it.canonicalUrl) && (notBefore == null || it.retrievedAt >= notBefore) }
            .sortedWith(compareByDescending<ArchiveRow> { row -> words.count { it in row.title.lowercase() } }.thenByDescending { it.archiveSequence }).take(30)
    }
    suspend fun text(version: ResearchWorkVersion, authority: String): String? {
        require(version.publicSource || version.authority == authority) { "Evidence authority changed" }
        if (version.coverage == ArticleCoverage.UNAVAILABLE) return null
        val refs = runCatching { Json.decodeFromString<List<String>>(version.bodyChunksJson) }.getOrNull() ?: return null
        val text = bodies.readFeatureBody(refs) ?: return null
        return text.takeIf { researchHash(it) == version.contentHash }
    }
    suspend fun capture(source: ResearchSource, text: String, authority: String, publicSource: Boolean, chatId: Int, rootId: String, archive: Boolean, epoch: Long): ResearchWorkVersion =
        bodies.publishFeatureBody(text) { chunks ->
            ResearchArchiveIngestor.capture(database, source.copy(bodyChunks = chunks, passage = text.take(8000)), authority, publicSource, chatId, rootId, archive, epoch, researchHash(text), text.toByteArray().size.toLong())
        }
    suspend fun remove(workId: String) = database.withTransaction {
        dao.removeMembership(workId)
        dao.removeRoots("archive", workId)
        dao.tombstone(ResearchDeletionTombstone("archive:$workId", System.currentTimeMillis()))
        dao.outbox(ResearchOutbox("archive-gc", "reconcile", System.currentTimeMillis()))
    }
    suspend fun renameTopic(id: String, label: String) = database.withTransaction {
        require(label.trim().length in 1..100)
        val old = requireNotNull(dao.topic(id))
        dao.topic(old.copy(label = label.trim(), revision = old.revision + 1))
    }
    suspend fun move(workId: String, topicId: String) = database.withTransaction {
        requireNotNull(dao.topic(topicId))
        dao.membership(requireNotNull(dao.membership(workId)).copy(primaryTopicId = topicId))
    }
    suspend fun mergeTopics(from: String, to: String) = database.withTransaction {
        require(from != to)
        requireNotNull(dao.topic(to))
        dao.moveTopic(from, to)
        dao.deleteRelatedTopic(from)
        dao.deleteTopic(from)
    }
}

internal object ResearchArchiveIngestor {
    const val DEFAULT_TOPIC = "uncategorized:needs-classification"
    private val taxonomy = linkedMapOf("technology" to "Technology", "health-life" to "Health & life", "environment" to "Environment", "society-humanities" to "Society & humanities", "business-economics" to "Business & economics", "methods" to "Methods", "uncategorized" to "Uncategorized")
    suspend fun seed(database: ResearchDatabase) = database.withTransaction {
        val dao = database.research()
        if (dao.topic(DEFAULT_TOPIC) == null) {
            taxonomy.forEach { (id, label) ->
                dao.category(ResearchCategory(id, label))
                dao.topic(ResearchTopic("$id:needs-classification", id, "Needs classification"))
            }
        }
    }
    fun publicUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme in setOf("https", "http") &&
            uri.rawUserInfo == null &&
            host.contains('.') &&
            !host.endsWith(".local") &&
            !host.endsWith(".localhost") &&
            !host.matches(Regex("[0-9.]+")) &&
            !host.contains(':') &&
            uri.rawQuery.orEmpty().split('&').none { it.substringBefore('=').lowercase() in setOf("token", "key", "api_key", "sig", "signature", "authorization", "credential", "session", "x-amz-signature", "x-goog-signature") }
    }.getOrDefault(false)

    suspend fun capture(database: ResearchDatabase, source: ResearchSource, authority: String, publicEvidence: Boolean, chatId: Int, rootId: String, archive: Boolean, epoch: Long, contentHash: String, textBytes: Long): ResearchWorkVersion {
        seed(database)
        return database.withTransaction {
            val dao = database.research()
            check(dao.deleted("chat:$chatId") == null) { "Conversation was deleted" }
            val public = publicEvidence && publicUrl(source.canonicalUrl)
            val scope = if (public) "public" else researchHash(authority)
            val identities = buildList {
                SourceIdentity.normalizeDoi(source.doi).takeIf { it.isNotBlank() }?.let { add("doi:$it") }
                source.pmid.takeIf { it.matches(Regex("[0-9]+")) }?.let { add("pmid:$it") }
                source.pmcid.takeIf { it.matches(Regex("PMC[0-9]+")) }?.let { add("pmcid:$it") }
                if (source.paperId.startsWith("arxiv:")) add(source.paperId.lowercase())
                add("url:${source.canonicalUrl}")
            }.map { "$scope|$it" }
            val resolved = identities.mapNotNull { dao.aliasWork(it) }.distinct()
            require(resolved.size <= 1) { "Conflicting strong research identifiers require review" }
            val workId = resolved.singleOrNull() ?: researchHash(identities.first())
            val previous = dao.work(workId)
            val review = when (source.reviewStatus.lowercase()) {
                "peer_reviewed", "confirmed_peer_reviewed" -> PublicationReview.CONFIRMED
                "not_peer_reviewed", "preprint" -> PublicationReview.NOT_PEER_REVIEWED
                else -> previous?.peerReview ?: PublicationReview.UNKNOWN
            }
            dao.work(ResearchWork(workId, source.title.take(2000), source.canonicalUrl, source.publicationYear ?: previous?.publicationYear, source.publicationType.takeUnless { it == "unknown" } ?: previous?.publicationType ?: "unknown", review, if (source.isRetracted == true || previous?.retracted == true) true else source.isRetracted ?: previous?.retracted, source.isPreprint ?: previous?.preprint, previous?.studyFamilyId, source.retrievedAt.takeIf { it > 0 }))
            identities.forEach { if (dao.aliasWork(it) == null) dao.alias(ResearchAlias(it, workId)) }
            val versionId = researchHash("$workId|$contentHash|1|${source.versionId}")
            val captured = ResearchWorkVersion(
                versionId, workId, contentHash, sourceVersion = source.versionId, sourceUrl = source.accessUrl.ifBlank { source.canonicalUrl }, retrievedAt = source.retrievedAt, validatedAt = source.retrievedAt.takeIf { it > 0 },
                coverage = when {
                    source.bodyChunks.isNotEmpty() && source.status == "Partially read" -> ArticleCoverage.PARTIAL_TEXT
                    source.bodyChunks.isNotEmpty() && source.readable -> ArticleCoverage.FULL_TEXT_CAPTURED
                    source.retrievalQuality == "ABSTRACT" || source.status == "Abstract" -> ArticleCoverage.ABSTRACT_ONLY
                    else -> ArticleCoverage.METADATA_ONLY
                },
                bodyChunksJson = Json.encodeToString(source.bodyChunks), authority = authority, publicSource = public, textBytes = textBytes
            )
            dao.version(captured)
            dao.root(BodyReference("chat", "$chatId:$rootId", versionId))
            if (archive && public) dao.archive(workId, versionId, dao.membership(workId)?.primaryTopicId ?: DEFAULT_TOPIC, System.currentTimeMillis(), epoch)
            requireNotNull(dao.version(versionId))
        }
    }
}
