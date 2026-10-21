package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class ResearchArchiveRepository @Inject constructor(val database: ResearchDatabase, private val bodies: ResearchSessionStore, private val chats: dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2? = null) {
    private val repairStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val migrationStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val migrationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val dao = database.research()
    suspend fun initialize() {
        ResearchArchiveIngestor.seed(database)
        if (chats != null && migrationStarted.compareAndSet(false, true)) {
            migrationScope.launch {
                try {
                    val allowed = (chats.chatRoomDao().getChatRooms() + chats.chatRoomDao().getArchivedChatRooms()).filterNot { it.isTemporary }.map { it.id }.toSet()
                    bodies.migrateFeatureHistory(allowed)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    migrationStarted.set(false)
                }
            }
        }
        if (repairStarted.compareAndSet(false, true)) {
            migrationScope.launch {
                try {
                    while (repairBatch()) kotlinx.coroutines.yield()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Research", "Historical source repair paused; cursor and protected studies retained", "W")
                } finally {
                    repairStarted.set(false)
                }
            }
        }
        reconcileOutbox()
    }
    suspend fun repairBatch(): Boolean = ResearchSourceRepair(database, bodies).batch()

    suspend fun reconcileOutbox() {
        val rows = dao.pendingOutbox().filter { it.operation == "reconcile" }
        if (rows.isEmpty()) return
        try {
            bodies.reconcileFeatureBodies()
            rows.forEach { dao.acknowledge(it.id, it.createdAt) }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Durable outbox remains pending; a missing or corrupt manifest cannot cause body deletion.
        }
    }
    fun observePage(query: String = "", topic: String = "", peerReviewedOnly: Boolean = false, offset: Int = 0, assessed: Boolean = false, rank: Boolean = false, state: String = "") =
        dao.observePage(query.trim().take(300).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"), topic, peerReviewedOnly, 40, offset.coerceAtLeast(0), assessed, rank, state)

    suspend fun page(query: String = "", topic: String = "", peerReviewedOnly: Boolean = false, offset: Int = 0): List<ArchiveRow> {
        initialize()
        return dao.page(query.trim().take(300).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"), topic, peerReviewedOnly, 40, offset.coerceAtLeast(0))
    }

    /** Metadata leads only. Every factual reuse still goes through a newly authorized source read. */
    suspend fun preflight(query: String, allowed: (String) -> Boolean, notBefore: Long? = null, peerReviewedOnly: Boolean = false): List<ArchiveRow> {
        initialize()
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.distinct().take(20)
        val candidates = (page(query.take(200), peerReviewedOnly = peerReviewedOnly) + words.take(5).flatMap { page(it, peerReviewedOnly = peerReviewedOnly) }).distinctBy { it.workId }
        return candidates.filter { dao.version(it.latestVersionId)?.publicSource == true && allowed(it.canonicalUrl) && (notBefore == null || it.retrievedAt >= notBefore) }
            .sortedWith(compareByDescending<ArchiveRow> { row -> words.count { it in row.title.lowercase() } }.thenByDescending { it.archiveSequence }).take(30)
    }
    suspend fun text(version: ResearchWorkVersion, authority: String): String? {
        require(version.publicSource || version.authority == authority) { "Evidence authority changed" }
        val refs = runCatching { Json.decodeFromString<List<String>>(version.bodyChunksJson) }.getOrNull() ?: return null
        val text = bodies.readFeatureBody(refs) ?: return null
        if (researchHash(text) != version.contentHash) return null
        return text.takeIf { ResearchSourceRepair(database, bodies).classify(version, it).usable }
    }
    suspend fun capture(source: ResearchSource, text: String, authority: String, publicSource: Boolean, chatId: Int, rootId: String, archive: Boolean, epoch: Long, ownerKind: String = "chat", ownerId: String = "$chatId:$rootId"): ResearchWorkVersion =
        bodies.publishFeatureBody(text) { chunks ->
            ResearchArchiveIngestor.capture(database, source.copy(bodyChunks = chunks, passage = text.take(8000)), authority, publicSource, chatId, rootId, archive, epoch, researchHash(text), text.toByteArray().size.toLong(), ownerKind, ownerId)
        }

    /** Explicit standalone imports use their own owner; they do not invent a conversation. */
    suspend fun ingestPaper(paper: dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper, ownerId: String, epoch: Long, organize: Boolean, permitted: suspend () -> Boolean): ResearchWorkVersion {
        require(paper.title.isNotBlank() && ResearchArchiveIngestor.publicUrl(paper.canonicalUrl))
        check(permitted()) { "Research permission changed" }
        val source = ResearchSource(
            id = paper.paperId, url = paper.canonicalUrl, title = paper.title, paperId = paper.paperId,
            doi = paper.doi, pmid = paper.identifiers["pmid"].orEmpty(), pmcid = paper.identifiers["pmcid"].orEmpty(),
            publicationYear = paper.year, publicationType = paper.publicationType, reviewStatus = paper.reviewStatus,
            isRetracted = paper.isRetracted, isPreprint = paper.isPreprint, engines = paper.sourceProviders,
            versionId = paper.versionId, retrievedAt = System.currentTimeMillis(),
            bibliographyJson = Json.encodeToString(paper.copy(abstract = "")),
            status = if (paper.abstract.isBlank()) "Metadata only" else "Abstract",
            retrievalQuality = if (paper.abstract.isBlank()) "METADATA" else "ABSTRACT"
        )
        return bodies.publishFeatureBody(paper.abstract) { chunks ->
            check(permitted()) { "Research permission changed" }
            database.withTransaction {
                val version = ResearchArchiveIngestor.capture(database, source.copy(bodyChunks = chunks), "public", true, -1, ownerId, true, epoch, researchHash(paper.abstract), paper.abstract.toByteArray().size.toLong(), "import", ownerId, organize)
                if (organize) organize(version.workId, paper.title, paper.subjects)
                version
            }
        }
    }
    private suspend fun organize(workId: String, title: String, subjects: List<String>) {
        val membership = dao.membership(workId) ?: return
        if (membership.primaryTopicId != ResearchArchiveIngestor.DEFAULT_TOPIC || dao.manuallyOrganized(workId)) return
        val match = ResearchOrganization.topic(title, subjects) ?: return
        val id = "${match.first}:automatic"
        if (dao.topic(id) == null) dao.topic(ResearchTopic(id, match.first, match.second))
        dao.membership(membership.copy(primaryTopicId = id))
    }
    suspend fun remove(workId: String) = database.withTransaction {
        dao.chatlessFence(workId, System.currentTimeMillis())
        dao.jobsForWork(workId).forEach { dao.removeRoots("job", it.jobId) }
        dao.removeRoots("assessment", workId)
        dao.removeMembership(workId)
        dao.removeRoots("archive", workId)
        dao.removeImportRootsForWork(workId)
        dao.tombstone(ResearchDeletionTombstone("archive:$workId", System.currentTimeMillis()))
        dao.outbox(ResearchOutbox("archive-gc", "reconcile", System.currentTimeMillis()))
    }
    suspend fun renameTopic(id: String, label: String) = database.withTransaction {
        require(label.trim().length in 1..100)
        val old = requireNotNull(dao.topic(id))
        dao.topic(old.copy(label = label.trim(), revision = old.revision + 1))
    }
    suspend fun createTopic(categoryId: String, label: String) = database.withTransaction {
        require(label.trim().length in 1..100)
        require(dao.allCategories().any { it.categoryId == categoryId })
        dao.topic(ResearchTopic(java.util.UUID.randomUUID().toString(), categoryId, label.trim()))
    }
    suspend fun renameCategory(id: String, label: String) = database.withTransaction {
        require(label.trim().length in 1..100)
        val old = requireNotNull(dao.allCategories().firstOrNull { it.categoryId == id })
        dao.category(old.copy(label = label.trim(), revision = old.revision + 1))
    }
    suspend fun move(workId: String, topicId: String) = database.withTransaction {
        requireNotNull(dao.topic(topicId))
        dao.membership(requireNotNull(dao.membership(workId)).copy(primaryTopicId = topicId))
        dao.organizationOverride(ResearchOrganizationOverride(workId, System.currentTimeMillis()))
    }
    suspend fun mergeTopics(from: String, to: String) = database.withTransaction {
        require(from != to)
        requireNotNull(dao.topic(to))
        dao.allMemberships().filter { it.primaryTopicId == from }.forEach { dao.organizationOverride(ResearchOrganizationOverride(it.workId, System.currentTimeMillis())) }
        dao.moveTopic(from, to)
        dao.mergeRelatedTopics(from, to)
        dao.deleteRelatedTopic(from)
        dao.deleteTopic(from)
    }
}

internal object ResearchArchiveIngestor {
    const val DEFAULT_TOPIC = "uncategorized:needs-classification"
    private val taxonomy = linkedMapOf("technology" to "Technology", "health-life" to "Health & life", "environment" to "Environment", "society-humanities" to "Society & humanities", "business-economics" to "Business & economics", "methods" to "Methods", "uncategorized" to "Uncategorized")
    suspend fun seed(database: ResearchDatabase) = database.withTransaction {
        val dao = database.research()
        val existing = dao.allCategories().mapTo(hashSetOf()) { it.categoryId }
        taxonomy.filterKeys { it !in existing }.forEach { (id, label) ->
            dao.category(ResearchCategory(id, label))
            if (dao.topic("$id:needs-classification") == null) dao.topic(ResearchTopic("$id:needs-classification", id, "Needs classification"))
        }
        if (dao.topic(DEFAULT_TOPIC) == null) dao.topic(ResearchTopic(DEFAULT_TOPIC, "uncategorized", "Needs classification"))
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

    suspend fun capture(database: ResearchDatabase, source: ResearchSource, authority: String, publicEvidence: Boolean, chatId: Int, rootId: String, archive: Boolean, epoch: Long, contentHash: String, textBytes: Long, ownerKind: String = "chat", ownerId: String = "$chatId:$rootId", automaticOrganization: Boolean = true, unexaminedLimit: Int = 1000): ResearchWorkVersion {
        seed(database)
        return database.withTransaction {
            val dao = database.research()
            require(ownerKind in setOf("chat", "import", "job"))
            if (ownerKind == "job") check(dao.job(ownerId)?.stage?.let { it !in setOf(ExaminationStage.CANCELED, ExaminationStage.FAILED) } == true) { "Examination owner is unavailable" }
            if (ownerKind == "chat") check(chatId > 0 && dao.deleted("chat:$chatId") == null) { "Conversation was deleted" }
            if (ownerKind == "import") {
                require(ownerId.isNotBlank())
                check((dao.sequence()?.archiveEpoch ?: 0L) == epoch) { "Archive was cleared; start a new import" }
            }
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
            if (ownerKind == "import") check(dao.deleted("archive:$workId") == null) { "This work was removed from the archive" }
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
                    !dev.chungjungsoo.gptmobile.data.research.SourceContentValidator.validate(source.passage).usable && source.passage.isNotBlank() -> ArticleCoverage.UNAVAILABLE
                    source.retrievalQuality == "METADATA" || source.status == "Metadata only" -> ArticleCoverage.METADATA_ONLY
                    source.host.equals("pubmed.ncbi.nlm.nih.gov", true) -> ArticleCoverage.ABSTRACT_ONLY
                    source.retrievalQuality == "ABSTRACT" || source.status == "Abstract" -> ArticleCoverage.ABSTRACT_ONLY
                    source.bodyChunks.isNotEmpty() && source.status == "Partially read" -> ArticleCoverage.PARTIAL_TEXT
                    source.bodyChunks.isNotEmpty() && source.readable && source.structuredFullText && source.captureTruncated == false && source.sectionNames.any { it.contains("method", true) || it.contains("experimental", true) } && source.sectionNames.any { it.contains("result", true) } -> ArticleCoverage.FULL_TEXT_CAPTURED
                    source.bodyChunks.isNotEmpty() && source.readable -> ArticleCoverage.PARTIAL_TEXT
                    else -> ArticleCoverage.METADATA_ONLY
                },
                bodyChunksJson = Json.encodeToString(source.bodyChunks), authority = authority, publicSource = public, textBytes = textBytes
            )
            dao.version(captured)
            if (captured.coverage == ArticleCoverage.FULL_TEXT_CAPTURED) {
                val validator = dev.chungjungsoo.gptmobile.data.research.SourceContentValidator
                val proofId = researchHash("capture-proof:${captured.versionId}:${captured.contentHash}:${validator.VERSION}")
                dao.sourceValidation(ResearchSourceValidation(proofId, captured.versionId, captured.contentHash, validator.VERSION, "STRUCTURED_ARTICLE", Json.encodeToString(listOf("COMPLETE_STRUCTURED_BODY", "NOT_TRUNCATED") + source.sectionNames.sorted()), captured.coverage, "ARTICLE", System.currentTimeMillis()))
            }
            dao.work(workId)?.studyFamilyId?.let { familyId ->
                dao.allFamilies().firstOrNull { it.familyId == familyId }?.let { family -> dao.workStudy(WorkStudyRelationship(workId, familyId, family.provenance, family.revisedAt)) }
            }
            dao.root(BodyReference(ownerKind, ownerId, versionId))
            if (archive) dao.archive(workId, versionId, dao.membership(workId)?.primaryTopicId ?: DEFAULT_TOPIC, System.currentTimeMillis(), epoch, unexaminedLimit)
            val membership = dao.membership(workId)
            if (automaticOrganization && membership?.primaryTopicId == DEFAULT_TOPIC && !dao.manuallyOrganized(workId)) {
                val subjects = runCatching { Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper>(source.bibliographyJson).subjects }.getOrDefault(emptyList())
                ResearchOrganization.topic(source.title, subjects)?.let { (category, label) ->
                    val topicId = "$category:automatic"
                    if (dao.topic(topicId) == null) dao.topic(ResearchTopic(topicId, category, label))
                    dao.membership(membership.copy(primaryTopicId = topicId))
                }
            }
            source.engines.distinct().forEach { engine ->
                val record = runCatching { Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper>(source.bibliographyJson).providerRecordId }.getOrDefault("").ifBlank { source.paperId.ifBlank { source.canonicalUrl } }
                dao.discovery(ResearchDiscovery(researchHash("$workId|$engine|$record"), workId, engine, record, source.bibliographyJson, System.currentTimeMillis()))
            }
            requireNotNull(dao.version(versionId))
        }
    }
}
