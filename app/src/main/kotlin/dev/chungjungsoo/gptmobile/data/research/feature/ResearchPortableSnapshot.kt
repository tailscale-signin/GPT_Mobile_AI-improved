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
    val schemaVersion: Int = 2,
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
    val recallExclusions: List<ResearchRecallExclusion> = emptyList()
)

/** Portable metadata, not a live WAL copy. The enclosing backup requires encryption. */
@Singleton
class ResearchBackup @Inject constructor(private val archive: ResearchArchiveRepository, private val bodies: ResearchSessionStore, private val chats: ChatDatabaseV2) {
    suspend fun export(selection: CompleteBackupSelection, directory: File): Map<String, File> {
        require(selection.requiresEncryption)
        val includeArchive = selection.includes(CompleteBackupSection.RESEARCH_ARCHIVE)
        val includeChat = selection.includes(CompleteBackupSection.CHAT_RESEARCH)
        val includeAppraisal = selection.includes(CompleteBackupSection.RESEARCH_APPRAISALS)
        val dao = archive.dao
        val rows = archive.database.withTransaction {
            val roots = dao.allRoots().filter { root ->
                when (root.rootKind) {
                    "archive", "import" -> includeArchive
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
                relatedTopics = dao.allRelatedTopics().filter { it.workId in workIds }, families = dao.allFamilies().filter { family -> works.any { it.studyFamilyId == family.familyId } },
                passages = if (includeAppraisal) dao.allPassages().filter { it.versionId in ids } else emptyList(), fields = if (includeAppraisal) dao.allFields().filter { it.versionId in ids } else emptyList(),
                appraisals = if (includeAppraisal) dao.allAppraisals().filter { it.versionId in ids } else emptyList(), bindings = if (includeChat) dao.allBindings().filter { it.versionId in ids } else emptyList(),
                jobs = jobs, roots = roots, tombstones = dao.allTombstones(), sequence = dao.sequence() ?: ResearchSequence(), receipts = dao.allImportReceipts(), discoveries = dao.allDiscoveries().filter { it.workId in workIds },
                outcomes = if (includeAppraisal) dao.allOutcomes().filter { it.versionId in ids } else emptyList(),
                recallExclusions = dao.allRecallExclusions().filter { it.workId in workIds }
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
        require(value.schemaVersion in 1..2 && value.exportedAt > 0 && value.works.size <= 50000 && value.versions.size <= 100000 && value.memberships.size <= 1000)
        require(value.works.map { it.workId }.distinct().size == value.works.size && value.versions.map { it.versionId }.distinct().size == value.versions.size)
        require(value.aliases.map { it.alias }.distinct().size == value.aliases.size)
        val works = value.works.map { it.workId }.toSet()
        val versions = value.versions.associateBy { it.versionId }
        require(value.aliases.all { it.workId in works } && value.roots.all { it.versionId in versions && it.rootKind in setOf("archive", "job", "chat", "citation", "import") })
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
        require(value.memberships.all { it.workId in works && versions[it.latestVersionId]?.workId == it.workId })
        require(value.bindings.all { versions[it.versionId]?.workId == it.workId })
        require(value.jobs.all { it.workId in works && it.stage == ExaminationStage.PAUSED })
        value.passages.forEach { passage ->
            val version = requireNotNull(versions[passage.versionId])
            val text = Json.decodeFromString<List<String>>(version.bodyChunksJson).joinToString("") { File(staging, "internal/research-history/$it").readText() }
            require(passage.start >= 0 && passage.end >= passage.start && passage.end <= text.length && text.substring(passage.start, passage.end) == passage.quote && researchHash(passage.quote) == passage.textHash)
        }
        require(value.fields.all { it.versionId in versions } && value.appraisals.all { it.versionId in versions && it.independentReview in setOf("not_requested", "unavailable", "passed", "rejected", "incomplete") })
        require(value.discoveries.all { it.workId in works && it.metadataJson.toByteArray().size <= 65536 })
        require(value.recallExclusions.all { it.workId in works })
        require(value.outcomes.all { outcome -> versions[outcome.versionId]?.workId == outcome.workId && outcome.state in setOf("GROUNDED", "DRAFT", "WITHDRAWN", "NEEDS_REVIEW", "SUPERSEDED") && Json.decodeFromString<List<String>>(outcome.passageIdsJson).all { id -> value.passages.any { it.passageId == id && it.versionId == outcome.versionId } } })
        return value
    }
    suspend fun restore(value: ResearchPortableSnapshot, selection: CompleteBackupSelection, snapshotId: String, staging: File) = bodies.importFeatureFiles(
        value.versions.flatMap { Json.decodeFromString<List<String>>(it.bodyChunksJson) }.distinct().associateWith { File(staging, "internal/research-history/$it") }
    ) {
        archive.database.withTransaction {
            val dao = archive.dao
            if (dao.allImportReceipts().any { it.importId == snapshotId }) return@withTransaction
            check(dao.allJobs().none { it.stage in setOf(ExaminationStage.RESOLVING, ExaminationStage.READING, ExaminationStage.EXTRACTING, ExaminationStage.APPRAISING, ExaminationStage.REVIEWING) }) { "Pause article examinations before restoring research" }
            value.tombstones.forEach { old -> dao.tombstone(old.copy(epoch = maxOf(old.epoch, dao.deleted(old.key) ?: 0))) }
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
            value.relatedTopics.forEach { dao.relatedTopic(it) }
            if (selection.includes(CompleteBackupSection.RESEARCH_APPRAISALS)) {
                value.passages.forEach { dao.passage(it) }
                value.fields.forEach { dao.field(it) }
                value.appraisals.forEach { dao.appraisal(it) }
                value.outcomes.forEach { dao.outcome(it.copy(state = "NEEDS_REVIEW")) }
                value.jobs.filter { dao.deleted("chat:${it.chatId}") == null }.forEach { old ->
                    if (dao.jobForKey(old.dedupKey) == null) dao.job(old.copy(stage = ExaminationStage.PAUSED, terminalReason = "Restored; reauthorize before resuming", lease = old.lease + 1))
                }
            }
            if (selection.includes(CompleteBackupSection.CHAT_RESEARCH)) value.bindings.filter { dao.deleted("chat:${it.chatId}") == null && chats.messageDao().rawMessage(it.messageId.toIntOrNull() ?: -1)?.chatId == it.chatId }.forEach { dao.binding(it) }
            value.roots.filter { root ->
                when (root.rootKind) {
                    "import" -> selection.includes(CompleteBackupSection.RESEARCH_ARCHIVE) && (dao.deleted("archive-clear") ?: 0) <= value.exportedAt && dao.version(root.versionId)?.let { dao.deleted("archive:${it.workId}") == null } == true
                    "job" -> selection.includes(CompleteBackupSection.RESEARCH_APPRAISALS) && dao.job(root.rootId) != null
                    "chat", "citation" -> selection.includes(CompleteBackupSection.CHAT_RESEARCH) && root.rootId.substringBefore(':').toIntOrNull()?.let { dao.deleted("chat:$it") == null && chats.chatRoomDao().getChatRoomsByIds(listOf(it)).singleOrNull()?.isTemporary == false } == true
                    else -> false
                }
            }.forEach { dao.root(it) }
            val seq = dao.sequence() ?: ResearchSequence()
            dao.sequence(seq.copy(next = maxOf(seq.next, value.sequence.next)))
            if (selection.includes(CompleteBackupSection.RESEARCH_ARCHIVE) && (dao.deleted("archive-clear") ?: 0) <= value.exportedAt) {
                value.memberships.sortedBy { it.archiveSequence }.forEach { membership ->
                    if (dao.deleted("archive:${membership.workId}") == null) dao.archive(membership.workId, membership.latestVersionId, membership.primaryTopicId, membership.firstArchivedAt, seq.archiveEpoch)
                }
            }
            value.receipts.forEach { dao.importReceipt(it) }
            dao.importReceipt(ResearchImportReceipt(snapshotId, seq.archiveEpoch, System.currentTimeMillis()))
            dao.outbox(ResearchOutbox("archive-gc", "reconcile", System.currentTimeMillis()))
        }
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
