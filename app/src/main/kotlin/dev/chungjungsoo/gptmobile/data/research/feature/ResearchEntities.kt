package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "research_works", indices = [Index("title"), Index("studyFamilyId")])
data class ResearchWork(@PrimaryKey val workId: String, val title: String, val canonicalUrl: String, val publicationYear: Int? = null, val publicationType: String = "unknown", val peerReview: PublicationReview = PublicationReview.UNKNOWN, val retracted: Boolean? = null, val preprint: Boolean? = null, val studyFamilyId: String? = null, val metadataCheckedAt: Long? = null)

@Serializable
@Entity(tableName = "research_aliases", foreignKeys = [ForeignKey(entity = ResearchWork::class, parentColumns = ["workId"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE)], indices = [Index("workId")])
data class ResearchAlias(@PrimaryKey val alias: String, val workId: String)

@Serializable
@Entity(tableName = "research_versions", foreignKeys = [ForeignKey(entity = ResearchWork::class, parentColumns = ["workId"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE)], indices = [Index("workId"), Index(value = ["workId", "contentHash", "parserVersion", "sourceVersion"], unique = true)])
data class ResearchWorkVersion(@PrimaryKey val versionId: String, val workId: String, val contentHash: String, val parserVersion: Int = 1, val sourceVersion: String = "", val sourceUrl: String, val etag: String? = null, val lastModified: String? = null, val retrievedAt: Long, val validatedAt: Long? = null, val coverage: ArticleCoverage, val bodyChunksJson: String = "[]", val authority: String, val publicSource: Boolean, val textBytes: Long = 0, val failure: String? = null)

@Serializable
@Entity(tableName = "research_categories")
data class ResearchCategory(@PrimaryKey val categoryId: String, val label: String, val revision: Long = 1)

@Serializable
@Entity(tableName = "research_topics", foreignKeys = [ForeignKey(entity = ResearchCategory::class, parentColumns = ["categoryId"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("categoryId"), Index(value = ["categoryId", "label"], unique = true)])
data class ResearchTopic(@PrimaryKey val topicId: String, val categoryId: String, val label: String, val revision: Long = 1)

@Serializable
@Entity(tableName = "research_archive", foreignKeys = [ForeignKey(entity = ResearchWork::class, parentColumns = ["workId"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = ResearchWorkVersion::class, parentColumns = ["versionId"], childColumns = ["latestVersionId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = ResearchTopic::class, parentColumns = ["topicId"], childColumns = ["primaryTopicId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["archiveSequence"], unique = true), Index("latestVersionId"), Index("primaryTopicId")])
data class ArchiveMembership(@PrimaryKey val workId: String, val archiveSequence: Long, val firstArchivedAt: Long, val latestVersionId: String, val primaryTopicId: String, val protectionReason: String? = null, val acceptedReviewId: String? = null, val verifiedVersionId: String? = null, @androidx.room.ColumnInfo(defaultValue = "0") val holdUntil: Long = 0, @androidx.room.ColumnInfo(defaultValue = "0") val lifecycleGeneration: Long = 0)

@Serializable
@Entity(tableName = "research_work_topics", primaryKeys = ["workId", "topicId"], foreignKeys = [ForeignKey(entity = ResearchWork::class, parentColumns = ["workId"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = ResearchTopic::class, parentColumns = ["topicId"], childColumns = ["topicId"], onDelete = ForeignKey.CASCADE)], indices = [Index("topicId")])
data class WorkTopic(val workId: String, val topicId: String)

@Serializable
@Entity(tableName = "research_study_families")
data class ResearchStudyFamily(@PrimaryKey val familyId: String, val registryId: String, val provenance: String, val revisedAt: Long)

@Serializable
@Entity(tableName = "research_passages", foreignKeys = [ForeignKey(entity = ResearchWorkVersion::class, parentColumns = ["versionId"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("versionId")])
data class EvidencePassage(@PrimaryKey val passageId: String, val versionId: String, val locator: String, val start: Int, val end: Int, val textHash: String, val quote: String)

@Serializable
@Entity(tableName = "research_study_fields", foreignKeys = [ForeignKey(entity = ResearchWorkVersion::class, parentColumns = ["versionId"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("versionId"), Index(value = ["versionId", "outcomeKey", "name"], unique = true)])
data class StudyField(@PrimaryKey val fieldId: String, val versionId: String, val outcomeKey: String, val name: String, val value: String?, val unit: String?, val missingness: Missingness, val passageIdsJson: String, val provenance: String)

@Serializable
@Entity(tableName = "research_appraisals", foreignKeys = [ForeignKey(entity = ResearchWorkVersion::class, parentColumns = ["versionId"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("versionId"), Index(value = ["versionId", "outcomeKey", "rubricVersion", "configHash"], unique = true)])
data class EvidenceAppraisal(@PrimaryKey val appraisalId: String, val versionId: String, val outcomeKey: String, val rubricVersion: Int, val configHash: String, val design: StudyDesign, val status: String, val judgmentsJson: String, val independentReview: String = "not_requested", val reviewedRevision: String? = null, val createdAt: Long)

@Serializable
@Entity(tableName = "research_bindings", primaryKeys = ["messageId", "responseRevision", "citationKey"], foreignKeys = [ForeignKey(entity = ResearchWork::class, parentColumns = ["workId"], childColumns = ["workId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = ResearchWorkVersion::class, parentColumns = ["versionId"], childColumns = ["versionId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("chatId"), Index("workId"), Index("versionId")])
data class MessageEvidenceBinding(val messageId: String, val responseRevision: String, val citationKey: String, val chatId: Int, val workId: String, val versionId: String, val paragraphId: String, val snapshotJson: String, val authority: String)

@Serializable
@Entity(tableName = "research_jobs", foreignKeys = [ForeignKey(entity = ResearchWork::class, parentColumns = ["workId"], childColumns = ["workId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["dedupKey"], unique = true), Index("workId"), Index("chatId")])
data class ExaminationJob(@PrimaryKey val jobId: String, val dedupKey: String, val workId: String, val requestedVersionId: String?, val origin: ExaminationOrigin, val chatId: Int, val profileUid: String, val authority: String, val policyHash: String, val stage: ExaminationStage, val checkpointJson: String = "{}", val terminalReason: String? = null, val updatedAt: Long, val lease: Long = 0)

@Serializable
@Entity(tableName = "research_body_roots", primaryKeys = ["rootKind", "rootId", "versionId"], foreignKeys = [ForeignKey(entity = ResearchWorkVersion::class, parentColumns = ["versionId"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("versionId")])
data class BodyReference(val rootKind: String, val rootId: String, val versionId: String)

@Serializable
@Entity(tableName = "research_tombstones")
data class ResearchDeletionTombstone(@PrimaryKey val key: String, val epoch: Long)

@Serializable
@Entity(tableName = "research_outbox")
data class ResearchOutbox(@PrimaryKey val id: String, val operation: String, val createdAt: Long)

@Serializable
@Entity(tableName = "research_sequence")
data class ResearchSequence(@PrimaryKey val id: Int = 1, val next: Long = 1, val archiveEpoch: Long = 0)

@Serializable
@Entity(tableName = "research_imports")
data class ResearchImportReceipt(@PrimaryKey val importId: String, val epoch: Long, val importedAt: Long)

@Serializable
@Entity(tableName = "research_save_receipts")
data class ResearchSaveReceipt(@PrimaryKey val receiptId: String, val runId: String, val sourceId: String, val chatId: Int, val workId: String? = null, val versionId: String? = null, val disposition: String, val reason: String? = null, val updatedAt: Long)

@Serializable
@Entity(tableName = "research_report_sections")
data class ResearchReportSection(@PrimaryKey val checkpointId: String, val chatId: Int, val owner: String, val packetDigest: String, val heading: String, val body: String, val updatedAt: Long)
