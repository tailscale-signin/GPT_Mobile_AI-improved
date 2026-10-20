package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSection
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSelection
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ResearchPortableSnapshot(
    val schemaVersion: Int = 5,
    val exportedAt: Long,
    val works: List<ResearchWork>,
    val aliases: List<ResearchAlias>,
    val versions: List<ResearchWorkVersion>,
    val categories: List<ResearchCategory>,
    val topics: List<ResearchTopic>,
    val memberships: List<ArchiveMembership>,
    val relatedTopics: List<WorkTopic>,
    val families: List<ResearchStudyFamily>,
    val passages: List<EvidencePassage>,
    val fields: List<StudyField>,
    val appraisals: List<EvidenceAppraisal>,
    val bindings: List<MessageEvidenceBinding>,
    val jobs: List<ExaminationJob>,
    val roots: List<BodyReference>,
    val tombstones: List<ResearchDeletionTombstone>,
    val sequence: ResearchSequence,
    val receipts: List<ResearchImportReceipt>,
    val discoveries: List<ResearchDiscovery> = emptyList(),
    val outcomes: List<ResearchOutcome> = emptyList(),
    val recallExclusions: List<ResearchRecallExclusion> = emptyList(),
    val organizationOverrides: List<ResearchOrganizationOverride> = emptyList(),
    val studyReviews: List<ResearchStudyReview> = emptyList(),
    val workStudies: List<WorkStudyRelationship> = emptyList(),
    val reportSections: List<ResearchReportSection> = emptyList(),
    val saveReceipts: List<ResearchSaveReceipt> = emptyList(),
    val acceptedTasks: List<dev.chungjungsoo.gptmobile.data.research.AcceptedResearchTask> = emptyList(),
    val sourceValidations: List<ResearchSourceValidation> = emptyList(),
    val identityResolutions: List<ResearchIdentityResolution> = emptyList(),
    val jobTransitions: List<ExaminationTransition> = emptyList()
)

/** Portable metadata, not a live WAL copy. The enclosing backup requires encryption. */
@Singleton
class ResearchBackup @Inject constructor(private val archive: ResearchArchiveRepository, private val bodies: ResearchSessionStore, private val chats: ChatDatabaseV2) {
    suspend fun export(selection: CompleteBackupSelection, directory: File): Map<String, File> {
        require(selection.requiresEncryption)
        val includeArchive = selection.includes(CompleteBackupSection.RESEARCH_ARCHIVE)
        val includeChat = selection.includes(CompleteBackupSection.CHAT_RESEARCH)
        val includeAppraisal = includeArchive || selection.includes(CompleteBackupSection.RESEARCH_APPRAISALS)
        val dao = archive.dao
        val rows = archive.database.withTransaction {
            val roots = dao.allRoots().filter { root ->
                when (root.rootKind) {
                    "archive", "import", "assessment" -> includeArchive
                    "job" -> includeAppraisal
                    "chat", "citation" -> includeChat
                    else -> false
                }
            }.filter { root ->
                if (root.rootKind in setOf("chat", "citation")) root.rootId.substringBefore(':').toIntOrNull()?.let { id -> chats.chatRoomDao().getChatRoomsByIds(listOf(id)).singleOrNull()?.isTemporary == false } == true else true
            }
            val ids = roots.map { it.versionId }.toSet()
            val versions = dao.allVersions().filter { it.versionId in ids }
            val workIds = versions.map { it.workId }.toSet()
            val works = dao.allWorks().filter { it.workId in workIds }
            val jobs = if (includeAppraisal) dao.allJobs().filter { it.workId in workIds && (it.chatId == 0 || includeChat) }.map { it.copy(stage = ExaminationStage.PAUSED, terminalReason = "Restored job requires explicit authorization to resume", lease = it.lease + 1) } else emptyList()
            ResearchPortableSnapshot(
                exportedAt = System.currentTimeMillis(), works = works, aliases = dao.allAliases().filter { it.workId in workIds }, versions = versions,
                categories = dao.allCategories(), topics = dao.allTopics(), memberships = if (includeArchive) dao.allMemberships().filter { it.latestVersionId in ids } else emptyList(),
                relatedTopics = dao.allRelatedTopics().filter { it.workId in workIds }, families = dao.allFamilies().filter { family -> works.any { it.studyFamilyId == family.familyId } || dao.allWorkStudies().any { it.workId in workIds && it.studyId == family.familyId } },
                passages = if (includeAppraisal) dao.allPassages().filter { it.versionId in ids } else emptyList(), fields = if (includeAppraisal) dao.allFields().filter { it.versionId in ids } else emptyList(),
                appraisals = if (includeAppraisal) dao.allAppraisals().filter { it.versionId in ids } else emptyList(), bindings = if (includeChat) dao.allBindings().filter { it.versionId in ids } else emptyList(),
                jobs = jobs, roots = roots, tombstones = dao.allTombstones(), sequence = dao.sequence() ?: ResearchSequence(), receipts = dao.allImportReceipts(), discoveries = dao.allDiscoveries().filter { it.workId in workIds },
                outcomes = if (includeAppraisal) dao.allOutcomes().filter { it.versionId in ids } else emptyList(),
                recallExclusions = dao.allRecallExclusions().filter { it.workId in workIds },
                organizationOverrides = dao.allOrganizationOverrides().filter { it.workId in workIds },
                studyReviews = if (includeAppraisal) dao.allStudyReviews().filter { it.versionId in ids } else emptyList(),
                workStudies = dao.allWorkStudies().filter { it.workId in workIds },
                reportSections = if (includeChat) dao.allReportSections().filter { section -> chats.chatRoomDao().getChatRoomsByIds(listOf(section.chatId)).singleOrNull()?.isTemporary == false } else emptyList(),
                saveReceipts = dao.allSaveReceipts().filter { it.workId in workIds },
                sourceValidations = dao.allSourceValidations().filter { it.versionId in ids },
                identityResolutions = dao.allIdentityResolutions().filter { it.workId in workIds && it.relatedWorkId in workIds },
                jobTransitions = jobs.flatMap { dao.transitions(it.jobId) },
                acceptedTasks = if (includeChat) bodies.exportAcceptedTasks((chats.chatRoomDao().getChatRooms() + chats.chatRoomDao().getArchivedChatRooms()).filterNot { it.isTemporary }.map { it.id }.toSet()) else emptyList()
            )
        }
        val refs = rows.versions.flatMap { Json.decodeFromString<List<String>>(it.bodyChunksJson) }.toSet()
        val files = bodies.snapshotFeatureFiles(refs, File(directory, "research-chunks"))
        val metadata = File(directory, "research-feature.json").apply { writeText(Json.encodeToString(rows)) }
        require(metadata.length() <= 64L * 1024 * 1024) { "Research metadata exceeds the portable snapshot limit" }
        return files.mapKeys { "internal/research-history/${it.key}" } + ("research-feature.json" to metadata)
    }
    fun validate(file: File, staging: File): ResearchPortableSnapshot {
        require(file.length() in 1..64L * 1024 * 1024)
        val value = Json.decodeFromString<ResearchPortableSnapshot>(file.readText())
        require(value.schemaVersion in 1..5 && value.exportedAt > 0 && value.works.size <= 50000 && value.versions.size <= 100000 && value.memberships.size <= 50000)
        require(value.works.map { it.workId }.distinct().size == value.works.size && value.versions.map { it.versionId }.distinct().size == value.versions.size)
        require(value.aliases.map { it.alias }.distinct().size == value.aliases.size)
        require(value.acceptedTasks.size <= 50000 && value.acceptedTasks.all { it.chatId > 0 && it.taskId == researchHash("task:${it.rootWorkId}") && it.taskText.isNotBlank() && it.participantUids.isNotEmpty() })
        val works = value.works.map { it.workId }.toSet()
        val versions = value.versions.associateBy { it.versionId }
        require(value.sourceValidations.all { it.versionId in versions && it.bodyHash == versions[it.versionId]?.contentHash && it.validatorRevision > 0 })
        require(value.identityResolutions.all { it.workId in works && it.relatedWorkId in works })
        require(value.jobTransitions.all { event -> value.jobs.any { it.jobId == event.jobId } })
        require(value.aliases.all { it.workId in works } && value.roots.all { it.versionId in versions && it.rootKind in setOf("archive", "job", "chat", "citation", "import", "assessment") })
        value.versions.forEach { version ->
            require(version.workId in works && version.versionId == researchHash("${version.workId}|${version.contentHash}|${version.parserVersion}|${version.sourceVersion}"))
            val refs = Json.decodeFromString<List<String>>(version.bodyChunksJson)
            require(refs.size <= 128)
            val text = buildString {
                refs.forEach { name ->
                    require(name.matches(Regex("[a-f0-9]{64}\\.chunk")))
                    val chunk = File(staging, "internal/research-history/$name")
                    require(chunk.isFile && chunk.length() <= 128 * 1024)
                    val body = chunk.readBytes().decodeToString(throwOnInvalidSequence = true)
                    require(researchHash(body) + ".chunk" == name)
                    append(body)
                }
            }
            if (refs.isNotEmpty()) require(researchHash(text) == version.contentHash && text.toByteArray().size.toLong() == version.textBytes)
        }
        require(value.memberships.map { it.workId }.distinct().size == value.memberships.size)
        require(value.memberships.all { it.workId in works && versions[it.latestVersionId]?.workId == it.workId && it.protectionReason in setOf(null, "LEGACY_HOLD", "EVIDENCE_CHECKED") && (it.verifiedVersionId == null || versions[it.verifiedVersionId]?.workId == it.workId) && (it.acceptedReviewId == null || value.studyReviews.any { review -> review.reviewId == it.acceptedReviewId && review.workId == it.workId && review.versionId == it.verifiedVersionId }) })
        require(value.bindings.all { versions[it.versionId]?.workId == it.workId })
        require(value.jobs.all { it.workId in works && it.stage == ExaminationStage.PAUSED })
        value.passages.forEach { passage ->
            val version = requireNotNull(versions[passage.versionId])
            val text = Json.decodeFromString<List<String>>(version.bodyChunksJson).joinToString("") { File(staging, "internal/research-history/$it").readText() }
            require(passage.start >= 0 && passage.end >= passage.start && passage.end <= text.length && text.substring(passage.start, passage.end) == passage.quote && researchHash(passage.quote) == passage.textHash)
        }
        require(value.fields.all { it.versionId in versions } && value.appraisals.all { it.versionId in versions && it.independentReview in setOf("not_requested", "unavailable", "passed", "rejected", "incomplete") })
        require(value.discoveries.all { it.workId in works && it.metadataJson.toByteArray().size <= 65536 })
        require(value.organizationOverrides.all { it.workId in works })
        require(value.recallExclusions.all { it.workId in works })
        require(value.outcomes.all { outcome -> versions[outcome.versionId]?.workId == outcome.workId && outcome.state in setOf("GROUNDED", "DRAFT", "WITHDRAWN", "NEEDS_REVIEW", "SUPERSEDED") && Json.decodeFromString<List<String>>(outcome.passageIdsJson).all { id -> value.passages.any { it.passageId == id && it.versionId == outcome.versionId } } })
        require(value.workStudies.all { it.workId in works && value.families.any { f -> f.familyId == it.studyId } })
        require(value.studyReviews.map { it.reviewId }.distinct().size == value.studyReviews.size)
        value.studyReviews.forEach { review ->
            val version = requireNotNull(versions[review.versionId])
            require(version.workId == review.workId && version.contentHash == review.sourceHash && review.rubricVersion in ResearchReviewRubric.supportedVersions)
            val outcome = value.outcomes.firstOrNull { it.versionId == review.versionId && it.outcomeKey == Json.decodeFromString<StudyReviewArtifact>(review.artifactJson).outcomeKey } ?: error("Review outcome missing")
            val artifact = ResearchReviewScoreCalculator.validate(review.artifactJson, outcome, value.passages, review.rubricVersion)
            val scope = Json.decodeFromString<ArticleSectionCoverage>(review.coverageJson)
            val scores = ResearchReviewScoreCalculator.calculate(artifact, scope.complete, review.design, false, review.rubricVersion)
            require(review.reliabilityScore == scores.reliability && review.accuracyScore == scores.accuracy)
            require(review.overallScore == null || review.overallScore == scores.overall)
            require(ResearchReviewScoreCalculator.summaryHash(artifact) == review.summaryHash)
            val evidence = value.passages.filter { it.versionId == version.versionId }.map { it.passageId }.toSet()
            require(artifact.passageIds.isNotEmpty() && (artifact.passageIds + (artifact.reliability + artifact.accuracy).flatMap { it.passageIds }).all { it in evidence })
            require(listOfNotNull(review.reliabilityScore, review.accuracyScore, review.overallScore).all { it in 0..100 && it % 5 == 0 })
        }
        require(value.reportSections.all { it.body.length <= 100_000 && it.heading.length <= 200 && it.packetDigest.matches(Regex("[a-f0-9]{64}")) })
        require(value.saveReceipts.all { it.workId == null || it.workId in works })
        return value
    }
    suspend fun restore(value: ResearchPortableSnapshot, selection: CompleteBackupSelection, snapshotId: String, staging: File) = bodies.importFeatureFiles(
        value.versions.flatMap { Json.decodeFromString<List<String>>(it.bodyChunksJson) }.distinct().associateWith { File(staging, "internal/research-history/$it") }
    ) {
        archive.database.withTransaction {
            val dao = archive.dao
            if (dao.allImportReceipts().any { it.importId == snapshotId }) return@withTransaction
            check(dao.allJobs().none { it.stage in setOf(ExaminationStage.QUEUED, ExaminationStage.RESOLVING, ExaminationStage.READING, ExaminationStage.EXTRACTING, ExaminationStage.APPRAISING, ExaminationStage.REVIEWING) }) { "Pause article examinations before restoring research" }
            value.tombstones.forEach { old ->
                val readded = old.key.takeIf { it.startsWith("archive:") && it != "archive-clear" }?.removePrefix("archive:")?.let { dao.membership(it) }
                if (readded != null && readded.lifecycleGeneration > old.epoch) return@forEach
                dao.tombstone(old.copy(epoch = maxOf(old.epoch, dao.deleted(old.key) ?: 0)))
                if (old.key.startsWith("archive:") && old.key != "archive-clear") {
                    val workId = old.key.removePrefix("archive:")
                    dao.chatlessFence(workId, System.currentTimeMillis())
                    dao.removeMembership(workId)
                    dao.removeRoots("archive", workId)
                    dao.removeRoots("assessment", workId)
                    dao.removeImportRootsForWork(workId)
                }
            }
            value.categories.forEach { incoming -> if (dao.allCategories().none { it.categoryId == incoming.categoryId }) dao.category(incoming) }
            value.topics.forEach { if (dao.topic(it.topicId) == null) dao.topic(it) }
            value.families.forEach { dao.studyFamily(it) }
            value.works.forEach { incoming ->
                val existing = dao.work(incoming.workId)
                if (existing == null || (incoming.metadataCheckedAt ?: 0) > (existing.metadataCheckedAt ?: 0)) dao.work(incoming)
            }
            value.aliases.forEach { alias ->
                val existing = dao.aliasWork(alias.alias)
                require(existing == null || existing == alias.workId) { "Research identity conflict; current archive preserved" }
                if (existing == null) dao.alias(alias)
            }
            value.versions.forEach { incoming ->
                val old = dao.version(incoming.versionId)
                require(old == null || old.contentHash == incoming.contentHash && old.authority == incoming.authority && old.bodyChunksJson == incoming.bodyChunksJson) { "Immutable research version conflict" }
                dao.version(incoming)
            }
            value.discoveries.forEach { dao.discovery(it) }
            value.recallExclusions.forEach { dao.excludeRecall(it) }
            value.organizationOverrides.forEach { if (!dao.manuallyOrganized(it.workId)) dao.organizationOverride(it) }
            value.relatedTopics.forEach { dao.relatedTopic(it) }
            value.workStudies.forEach { dao.workStudy(it) }
            if (selection.includes(CompleteBackupSection.RESEARCH_APPRAISALS) || selection.includes(CompleteBackupSection.RESEARCH_ARCHIVE)) {
                value.passages.forEach { dao.passage(it) }
                value.fields.forEach { dao.field(it) }
                value.appraisals.forEach { dao.appraisal(it) }
                // Restored evidence is historical; require a fresh integrity check before ranking.
                value.studyReviews.forEach { dao.studyReview(it.copy(overallScore = null, status = "Restored review · re-examine")) }
                value.outcomes.forEach { dao.outcome(it.copy(state = if (it.state in setOf("WITHDRAWN", "SUPERSEDED")) it.state else "NEEDS_REVIEW")) }
                value.jobs.filter { dao.deleted("chat:${it.chatId}") == null && dao.deleted("archive:${it.workId}") == null }.forEach { old ->
                    if (dao.jobForKey(old.dedupKey) == null) dao.job(old.copy(stage = ExaminationStage.PAUSED, terminalReason = "Restored; reauthorize before resuming", lease = old.lease + 1, workerId = null, lastHeartbeatAt = 0, deadlineAt = 0, cancellationOrigin = "RESTORE"))
                }
            }
            if (selection.includes(CompleteBackupSection.CHAT_RESEARCH)) value.bindings.filter { dao.deleted("chat:${it.chatId}") == null && chats.messageDao().rawMessage(it.messageId.toIntOrNull() ?: -1)?.chatId == it.chatId }.forEach { dao.binding(it) }
            value.roots.filter { root ->
                when (root.rootKind) {
                    "assessment" -> selection.includes(CompleteBackupSection.RESEARCH_ARCHIVE) && dao.deleted("archive:${root.rootId}") == null && (dao.deleted("archive-clear") ?: 0) <= value.exportedAt
                    "import" -> selection.includes(CompleteBackupSection.RESEARCH_ARCHIVE) && (dao.deleted("archive-clear") ?: 0) <= value.exportedAt && dao.version(root.versionId)?.let { dao.deleted("archive:${it.workId}") == null } == true
                    "job" -> (selection.includes(CompleteBackupSection.RESEARCH_APPRAISALS) || selection.includes(CompleteBackupSection.RESEARCH_ARCHIVE)) && dao.job(root.rootId)?.let { it.stage != ExaminationStage.CANCELED && dao.deleted("archive:${it.workId}") == null } == true
                    "chat", "citation" -> selection.includes(CompleteBackupSection.CHAT_RESEARCH) && root.rootId.substringBefore(':').toIntOrNull()?.let { dao.deleted("chat:$it") == null && chats.chatRoomDao().getChatRoomsByIds(listOf(it)).singleOrNull()?.isTemporary == false } == true
                    else -> false
                }
            }.forEach { dao.root(it) }
            val seq = dao.sequence() ?: ResearchSequence()
            dao.sequence(seq.copy(next = maxOf(seq.next, value.sequence.next)))
            if (selection.includes(CompleteBackupSection.RESEARCH_ARCHIVE) && (dao.deleted("archive-clear") ?: 0) <= value.exportedAt) {
                value.memberships.sortedBy { it.archiveSequence }.forEach { membership ->
                    if (dao.deleted("archive:${membership.workId}") == null) {
                        // Stage protection before pruning; never pass a protected import through FIFO.
                        val existing = dao.membership(membership.workId)
                        val incoming = if (existing?.protectionReason != null) existing else membership
                        val held = incoming.copy(archiveSequence = existing?.archiveSequence ?: (dao.sequence()?.next ?: seq.next), protectionReason = existing?.protectionReason ?: membership.protectionReason ?: if (value.studyReviews.any { it.workId == membership.workId }) "LEGACY_HOLD" else null, holdUntil = System.currentTimeMillis() + 24 * 60 * 60_000L)
                        if (existing == null) {
                            val current = dao.sequence() ?: seq
                            dao.sequence(current.copy(next = Math.addExact(current.next, 1)))
                        }
                        dao.membership(held)
                        dao.root(BodyReference("archive", held.workId, held.latestVersionId))
                    }
                }
            }
            dao.trimUnexamined(1000, System.currentTimeMillis())
            value.saveReceipts.forEach { receipt -> if (dao.deleted("chat:${receipt.chatId}") == null) dao.saveReceipt(receipt) }
            if (selection.includes(CompleteBackupSection.CHAT_RESEARCH)) value.reportSections.filter { section -> dao.deleted("chat:${section.chatId}") == null && chats.chatRoomDao().getChatRoomsByIds(listOf(section.chatId)).singleOrNull()?.isTemporary == false }.forEach { dao.reportSection(it) }
            value.receipts.forEach { dao.importReceipt(it) }
            value.sourceValidations.forEach { dao.sourceValidation(it) }
            value.identityResolutions.forEach { dao.identityResolution(it) }
            value.jobTransitions.sortedBy { it.sequence }.forEach { event ->
                if (dao.job(event.jobId) != null && dao.transitions(event.jobId).none { it.lease == event.lease && it.stage == event.stage && it.at == event.at && it.reason == event.reason }) dao.transition(event.copy(sequence = 0))
            }
            dao.importReceipt(ResearchImportReceipt(snapshotId, seq.archiveEpoch, System.currentTimeMillis()))
            dao.repairCursor(ResearchRepairCursor("source-classifier", "", dev.chungjungsoo.gptmobile.data.research.SourceContentValidator.VERSION, System.currentTimeMillis()))
            dao.outbox(ResearchOutbox("archive-gc", "reconcile", System.currentTimeMillis()))
        }
        if (selection.includes(CompleteBackupSection.CHAT_RESEARCH)) bodies.restoreAcceptedTasks(value.acceptedTasks.filter { chats.chatRoomDao().getChatRoomsByIds(listOf(it.chatId)).singleOrNull()?.isTemporary == false })
    }
    fun exportStatistics(file: File): File {
        file.bufferedWriter().use { archive.database.statistics().writeSnapshot(it) }
        return file
    }
    fun validateStatistics(file: File) {
        require(file.length() in 1..512L * 1024 * 1024)
        file.bufferedReader().use { reader ->
            val header = Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchStatisticsHeader>(requireNotNull(reader.readLine()).also { require(it.length <= 4096) })
            require(header.schemaVersion == 1 && header.definitionVersion == 2 && header.clearedAt >= 0)
            reader.lineSequence().forEach { line ->
                require(line.length <= 4096)
                val event = Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt>(line)
                require(event.eventId.matches(Regex("[a-zA-Z0-9-]{1,80}")) && event.schemaVersion in 1..2 && event.recordedAt >= 0 && event.durationMs >= 0 && event.engineId.length in 1..180 && event.operation.length in 1..80)
            }
        }
    }
    suspend fun restoreStatistics(file: File) = archive.database.withTransaction {
        val dao = archive.database.statistics()
        file.bufferedReader().use { reader ->
            val header = Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchStatisticsHeader>(requireNotNull(reader.readLine()))
            dao.clearEpoch(dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchStatisticsEpoch(clearedAt = maxOf(header.clearedAt, dao.clearedAt() ?: 0)))
            reader.lineSequence().forEach { dao.record(Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt>(it)) }
        }
    }
    companion object {
        val SECTIONS = setOf(CompleteBackupSection.RESEARCH_ARCHIVE, CompleteBackupSection.CHAT_RESEARCH, CompleteBackupSection.RESEARCH_APPRAISALS)
    }
}
