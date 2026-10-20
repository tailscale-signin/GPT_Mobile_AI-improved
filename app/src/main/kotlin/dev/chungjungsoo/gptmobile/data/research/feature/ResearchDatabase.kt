package dev.chungjungsoo.gptmobile.data.research.feature

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Database(entities = [ResearchReportSection::class, ResearchSaveReceipt::class, ResearchStudyReview::class, WorkStudyRelationship::class, ResearchOrganizationOverride::class, ResearchDiscovery::class, ResearchOutcome::class, ResearchRecallExclusion::class, ResearchHandoff::class, ResearchWork::class, ResearchAlias::class, ResearchWorkVersion::class, ResearchCategory::class, ResearchTopic::class, ArchiveMembership::class, WorkTopic::class, ResearchStudyFamily::class, EvidencePassage::class, StudyField::class, EvidenceAppraisal::class, MessageEvidenceBinding::class, ExaminationJob::class, ExaminationTransition::class, ResearchSourceValidation::class, ResearchIdentityResolution::class, ResearchRepairCursor::class, BodyReference::class, ResearchDeletionTombstone::class, ResearchOutbox::class, ResearchSequence::class, ResearchImportReceipt::class, dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt::class, dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchStatisticsEpoch::class], version = 6, exportSchema = true)
abstract class ResearchDatabase : RoomDatabase() {
    abstract fun research(): ResearchDao
    abstract fun statistics(): dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchStatisticsDao
}

@Module
@InstallIn(SingletonComponent::class)
object ResearchDatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): ResearchDatabase = Room.databaseBuilder(context, ResearchDatabase::class.java, "research-feature.db").addMigrations(ResearchWorkspaceMigration, ResearchStudyCardsMigration, ResearchRetentionMigration, ResearchExaminationLifecycleMigration, ResearchRepairMigration).build().also {
        dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchTelemetryStore.attach(java.io.File(context.filesDir, "research-telemetry"), it.statistics())
    }
}

data class ArchiveRow(val workId: String, val title: String, val canonicalUrl: String, val publicationYear: Int?, val peerReview: PublicationReview, val retracted: Boolean?, val latestVersionId: String, val archiveSequence: Long, val primaryTopicId: String, val coverage: ArticleCoverage, val retrievedAt: Long, val textBytes: Long, val reviewScore: Int? = null, val reviewStatus: String? = null, val protectionReason: String? = null, val holdUntil: Long = 0)

@Dao
interface ResearchDao {
    @Upsert suspend fun sourceValidation(value: ResearchSourceValidation)

    @Query("SELECT * FROM research_source_validations WHERE validationId=:id")
    suspend fun sourceValidation(id: String): ResearchSourceValidation?

    @Query("SELECT * FROM research_source_validations WHERE versionId=:id ORDER BY validatorRevision DESC,classifiedAt DESC")
    suspend fun sourceValidations(id: String): List<ResearchSourceValidation>

    @Query("SELECT * FROM research_source_validations")
    suspend fun allSourceValidations(): List<ResearchSourceValidation>

    @Upsert suspend fun identityResolution(value: ResearchIdentityResolution)

    @Query("SELECT * FROM research_identity_resolutions WHERE workId=:id OR relatedWorkId=:id")
    suspend fun identityResolutions(id: String): List<ResearchIdentityResolution>

    @Query("SELECT * FROM research_identity_resolutions")
    suspend fun allIdentityResolutions(): List<ResearchIdentityResolution>

    @Upsert suspend fun repairCursor(value: ResearchRepairCursor)

    @Query("SELECT * FROM research_repair_cursors WHERE kind=:kind")
    suspend fun repairCursor(kind: String): ResearchRepairCursor?

    @Query("SELECT * FROM research_versions WHERE versionId>:after ORDER BY versionId LIMIT :limit")
    suspend fun repairVersions(after: String, limit: Int): List<ResearchWorkVersion>

    @Query("UPDATE research_study_reviews SET overallScore=NULL,reliabilityScore=NULL,accuracyScore=NULL,status='SOURCE_REVALIDATION_REQUIRED' WHERE versionId=:id")
    suspend fun staleVersionAssessments(id: String)

    @Upsert suspend fun reportSection(value: ResearchReportSection)

    @Query("SELECT * FROM research_report_sections WHERE chatId=:chatId AND owner=:owner AND packetDigest=:digest ORDER BY updatedAt")
    suspend fun reportSections(chatId: Int, owner: String, digest: String): List<ResearchReportSection>

    @Query("SELECT * FROM research_report_sections")
    suspend fun allReportSections(): List<ResearchReportSection>

    @Query("SELECT * FROM research_save_receipts")
    suspend fun allSaveReceipts(): List<ResearchSaveReceipt>

    @Query("DELETE FROM research_report_sections WHERE chatId=:chatId")
    suspend fun deleteReportSections(chatId: Int)

    @Upsert suspend fun saveReceipt(value: ResearchSaveReceipt)

    @Query("SELECT * FROM research_save_receipts WHERE chatId=:chatId ORDER BY updatedAt DESC")
    suspend fun saveReceipts(chatId: Int): List<ResearchSaveReceipt>

    @Query("SELECT * FROM research_save_receipts WHERE chatId=:chatId ORDER BY updatedAt DESC")
    fun observeSaveReceipts(chatId: Int): kotlinx.coroutines.flow.Flow<List<ResearchSaveReceipt>>

    @Query("SELECT * FROM research_outbox WHERE id LIKE 'ingest:%' ORDER BY createdAt")
    suspend fun pendingIngestions(): List<ResearchOutbox>

    @Query("SELECT * FROM research_archive")
    fun observeMemberships(): Flow<List<ArchiveMembership>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun studyReview(value: ResearchStudyReview): Long

    @Upsert suspend fun workStudy(value: WorkStudyRelationship)

    @Query("SELECT * FROM research_work_studies")
    suspend fun allWorkStudies(): List<WorkStudyRelationship>

    @Query("SELECT * FROM research_study_reviews")
    suspend fun allStudyReviews(): List<ResearchStudyReview>

    @Query("SELECT * FROM research_study_reviews WHERE versionId=:id ORDER BY createdAt DESC, reviewId DESC")
    suspend fun studyReviews(id: String): List<ResearchStudyReview>

    @Query("SELECT r.* FROM research_study_reviews r WHERE r.reviewId=COALESCE((SELECT acceptedReviewId FROM research_archive WHERE workId=r.workId),(SELECT reviewId FROM research_study_reviews WHERE workId=r.workId ORDER BY createdAt DESC, reviewId DESC LIMIT 1)) ORDER BY createdAt DESC")
    fun observeStudyReviews(): Flow<List<ResearchStudyReview>>

    @Query("SELECT * FROM research_works")
    fun observeWorks(): Flow<List<ResearchWork>>

    @Upsert suspend fun organizationOverride(value: ResearchOrganizationOverride)

    @Query("SELECT EXISTS(SELECT 1 FROM research_organization_overrides WHERE workId=:id)")
    suspend fun manuallyOrganized(id: String): Boolean

    @Query("SELECT * FROM research_organization_overrides")
    suspend fun allOrganizationOverrides(): List<ResearchOrganizationOverride>

    @Upsert suspend fun discovery(value: ResearchDiscovery)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun outcome(value: ResearchOutcome): Long

    @Upsert suspend fun excludeRecall(value: ResearchRecallExclusion)

    @Upsert suspend fun handoff(value: ResearchHandoff)

    @Query("SELECT * FROM research_discoveries WHERE workId=:id ORDER BY observedAt DESC")
    suspend fun discoveries(id: String): List<ResearchDiscovery>

    @Query("SELECT * FROM research_discoveries")
    suspend fun allDiscoveries(): List<ResearchDiscovery>

    @Query("SELECT * FROM research_outcomes")
    suspend fun allOutcomes(): List<ResearchOutcome>

    @Query("SELECT * FROM research_recall_exclusions")
    suspend fun allRecallExclusions(): List<ResearchRecallExclusion>

    @Query("SELECT * FROM research_handoffs WHERE handoffId=:id")
    suspend fun handoff(id: String): ResearchHandoff?

    @Query("SELECT EXISTS(SELECT 1 FROM research_recall_exclusions WHERE workId=:id)")
    suspend fun recallExcluded(id: String): Boolean

    @Query("SELECT o.* FROM research_outcomes o JOIN research_archive a ON a.workId=o.workId AND a.latestVersionId=o.versionId JOIN research_works w ON w.workId=o.workId WHERE COALESCE(w.retracted,0)=0 AND o.state NOT IN ('WITHDRAWN','SUPERSEDED') AND NOT EXISTS(SELECT 1 FROM research_recall_exclusions x WHERE x.workId=o.workId) AND NOT EXISTS(SELECT 1 FROM research_tombstones t WHERE t.`key`='archive:' || o.workId) ORDER BY o.createdAt DESC LIMIT 100")
    fun observeOutcomes(): Flow<List<ResearchOutcome>>

    @Query("SELECT * FROM research_outcomes WHERE versionId=:id ORDER BY createdAt DESC")
    suspend fun outcomes(id: String): List<ResearchOutcome>

    @Query("SELECT * FROM research_outcomes WHERE outcomeId=:id LIMIT 1")
    suspend fun outcomeById(id: String): ResearchOutcome?

    @Query("SELECT primaryTopicId AS topicId, COUNT(*) AS count FROM research_archive GROUP BY primaryTopicId")
    fun topicCounts(): Flow<List<ResearchTopicCount>>

    @Query("UPDATE research_outcomes SET state='WITHDRAWN' WHERE outcomeId=:id")
    suspend fun forgetOutcome(id: String)

    @Upsert suspend fun work(value: ResearchWork)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun alias(value: ResearchAlias)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun version(value: ResearchWorkVersion): Long

    @Upsert suspend fun category(value: ResearchCategory)

    @Upsert suspend fun topic(value: ResearchTopic)

    @Upsert suspend fun membership(value: ArchiveMembership)

    @Upsert suspend fun relatedTopic(value: WorkTopic)

    @Upsert suspend fun studyFamily(value: ResearchStudyFamily)

    @Upsert suspend fun passage(value: EvidencePassage)

    @Upsert suspend fun field(value: StudyField)

    @Upsert suspend fun appraisal(value: EvidenceAppraisal)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun binding(value: MessageEvidenceBinding): Long

    @Upsert suspend fun job(value: ExaminationJob)

    @Upsert suspend fun root(value: BodyReference)

    @Upsert suspend fun tombstone(value: ResearchDeletionTombstone)

    @Upsert suspend fun outbox(value: ResearchOutbox)

    @Query("SELECT * FROM research_outbox WHERE id = :id")
    suspend fun outboxById(id: String): ResearchOutbox?

    @Upsert suspend fun sequence(value: ResearchSequence)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importReceipt(value: ResearchImportReceipt): Long

    @Query("SELECT * FROM research_works WHERE workId = :id")
    suspend fun work(id: String): ResearchWork?

    @Query("SELECT workId FROM research_aliases WHERE alias = :alias")
    suspend fun aliasWork(alias: String): String?

    @Query("SELECT * FROM research_aliases WHERE workId = :id")
    suspend fun aliases(id: String): List<ResearchAlias>

    @Query("SELECT * FROM research_versions WHERE versionId = :id")
    suspend fun version(id: String): ResearchWorkVersion?

    @Query("SELECT * FROM research_versions WHERE workId = :workId ORDER BY retrievedAt DESC LIMIT 100")
    suspend fun versions(workId: String): List<ResearchWorkVersion>

    @Query("SELECT * FROM research_versions WHERE workId = :workId ORDER BY retrievedAt DESC LIMIT 1")
    suspend fun latestVersion(workId: String): ResearchWorkVersion?

    @Query("SELECT * FROM research_archive WHERE workId = :id")
    suspend fun membership(id: String): ArchiveMembership?

    @Query("SELECT * FROM research_sequence WHERE id = 1")
    suspend fun sequence(): ResearchSequence?

    @Query("DELETE FROM research_tombstones WHERE `key` = :key")
    suspend fun removeTombstone(key: String)

    @Query("SELECT epoch FROM research_tombstones WHERE `key` = :key")
    suspend fun deleted(key: String): Long?

    @Query("SELECT COUNT(*) FROM research_archive")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM research_archive")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM research_categories ORDER BY label")
    fun categories(): Flow<List<ResearchCategory>>

    @Query("SELECT * FROM research_topics ORDER BY categoryId, label")
    fun topics(): Flow<List<ResearchTopic>>

    @Query("SELECT * FROM research_topics WHERE topicId = :id")
    suspend fun topic(id: String): ResearchTopic?

    @Query("SELECT w.workId,w.title,w.canonicalUrl,w.publicationYear,w.peerReview,w.retracted,a.latestVersionId,a.archiveSequence,a.primaryTopicId,v.coverage,v.retrievedAt,v.textBytes,r.overallScore AS reviewScore,r.status AS reviewStatus,a.protectionReason,a.holdUntil FROM research_archive a JOIN research_works w ON w.workId=a.workId JOIN research_versions v ON v.versionId=a.latestVersionId LEFT JOIN research_study_reviews r ON r.reviewId=COALESCE(a.acceptedReviewId,(SELECT reviewId FROM research_study_reviews WHERE workId=w.workId ORDER BY createdAt DESC,reviewId DESC LIMIT 1)) WHERE (:topic='' OR a.primaryTopicId=:topic OR EXISTS(SELECT 1 FROM research_work_topics t WHERE t.workId=w.workId AND t.topicId=:topic)) AND (:query='' OR w.title LIKE '%' || :query || '%' ESCAPE '\\' OR w.workId IN (SELECT workId FROM research_aliases WHERE alias LIKE '%' || :query || '%' ESCAPE '\\')) AND (:reviewed=0 OR w.peerReview='CONFIRMED') AND (:assessed=0 OR r.reviewId IS NOT NULL) AND (:state='' OR (:state='UNEXAMINED' AND r.reviewId IS NULL AND a.holdUntil<=:now) OR (:state='PROCESSING' AND EXISTS(SELECT 1 FROM research_jobs j WHERE j.workId=w.workId AND j.stage IN ('QUEUED','RESOLVING','READING','EXTRACTING','APPRAISING','REVIEWING'))) OR (:state='EXAMINED' AND r.reviewId IS NOT NULL) OR (:state='EVIDENCE_CHECKED' AND a.protectionReason='EVIDENCE_CHECKED')) ORDER BY CASE WHEN :rank=1 AND COALESCE(w.retracted,0)=0 THEN r.overallScore ELSE NULL END DESC,a.archiveSequence DESC LIMIT :limit OFFSET :offset")
    suspend fun page(query: String, topic: String, reviewed: Boolean, limit: Int = 40, offset: Int = 0, assessed: Boolean = false, rank: Boolean = false, state: String = "", now: Long = System.currentTimeMillis()): List<ArchiveRow>

    @Query("SELECT w.workId,w.title,w.canonicalUrl,w.publicationYear,w.peerReview,w.retracted,a.latestVersionId,a.archiveSequence,a.primaryTopicId,v.coverage,v.retrievedAt,v.textBytes,r.overallScore AS reviewScore,r.status AS reviewStatus,a.protectionReason,a.holdUntil FROM research_archive a JOIN research_works w ON w.workId=a.workId JOIN research_versions v ON v.versionId=a.latestVersionId LEFT JOIN research_study_reviews r ON r.reviewId=COALESCE(a.acceptedReviewId,(SELECT reviewId FROM research_study_reviews WHERE workId=w.workId ORDER BY createdAt DESC,reviewId DESC LIMIT 1)) WHERE (:topic='' OR a.primaryTopicId=:topic OR EXISTS(SELECT 1 FROM research_work_topics t WHERE t.workId=w.workId AND t.topicId=:topic)) AND (:query='' OR w.title LIKE '%' || :query || '%' ESCAPE '\\' OR w.workId IN (SELECT workId FROM research_aliases WHERE alias LIKE '%' || :query || '%' ESCAPE '\\')) AND (:reviewed=0 OR w.peerReview='CONFIRMED') AND (:assessed=0 OR r.reviewId IS NOT NULL) AND (:state='' OR (:state='UNEXAMINED' AND r.reviewId IS NULL AND a.holdUntil<=:now) OR (:state='PROCESSING' AND EXISTS(SELECT 1 FROM research_jobs j WHERE j.workId=w.workId AND j.stage IN ('QUEUED','RESOLVING','READING','EXTRACTING','APPRAISING','REVIEWING'))) OR (:state='EXAMINED' AND r.reviewId IS NOT NULL) OR (:state='EVIDENCE_CHECKED' AND a.protectionReason='EVIDENCE_CHECKED')) ORDER BY CASE WHEN :rank=1 AND COALESCE(w.retracted,0)=0 THEN r.overallScore ELSE NULL END DESC,a.archiveSequence DESC LIMIT :limit OFFSET :offset")
    fun observePage(query: String, topic: String, reviewed: Boolean, limit: Int = 40, offset: Int = 0, assessed: Boolean = false, rank: Boolean = false, state: String = "", now: Long = System.currentTimeMillis()): Flow<List<ArchiveRow>>

    @Query("SELECT * FROM research_archive WHERE holdUntil>0 AND holdUntil<=:now")
    suspend fun expiredHolds(now: Long): List<ArchiveMembership>

    @Query("SELECT * FROM research_jobs WHERE workId=:workId")
    suspend fun jobsForWork(workId: String): List<ExaminationJob>

    @Query("SELECT * FROM research_archive WHERE protectionReason IS NULL AND holdUntil <= :now ORDER BY archiveSequence,workId LIMIT MAX(0, (SELECT COUNT(*) FROM research_archive WHERE protectionReason IS NULL AND holdUntil <= :now)-:keep)")
    suspend fun oldestExcess(keep: Int, now: Long = System.currentTimeMillis()): List<ArchiveMembership>

    @Transaction
    suspend fun chatlessFence(id: String, now: Long) {
        jobsForWork(id).forEach { old ->
            job(old.copy(stage = ExaminationStage.CANCELED, lease = old.lease + 1, terminalReason = "STUDY_MANUALLY_DELETED", updatedAt = now, deadlineAt = 0, cancellationOrigin = "USER_DELETE"))
            transition(ExaminationTransition(0, old.jobId, old.lease, ExaminationStage.CANCELED, now, old.workerId, "STUDY_MANUALLY_DELETED", "USER_DELETE"))
        }
    }

    @Query("SELECT COUNT(*) FROM research_archive WHERE protectionReason IS NOT NULL")
    fun observeProtectedCount(): Flow<Int>

    @Query("DELETE FROM research_archive WHERE workId=:id")
    suspend fun removeMembership(id: String)

    @Query("DELETE FROM research_body_roots WHERE rootKind='import' AND versionId IN (SELECT versionId FROM research_versions WHERE workId=:workId)")
    suspend fun removeImportRootsForWork(workId: String)

    @Query("DELETE FROM research_body_roots WHERE rootKind=:kind AND rootId=:id")
    suspend fun removeRoots(kind: String, id: String)

    @Query("DELETE FROM research_body_roots WHERE rootKind IN ('chat','citation') AND rootId LIKE :prefix || '%'")
    suspend fun deleteChatRoots(prefix: String)

    @Query("SELECT DISTINCT v.bodyChunksJson FROM research_versions v JOIN research_body_roots r ON r.versionId=v.versionId")
    suspend fun retainedBodies(): List<String>

    @Query("UPDATE research_archive SET primaryTopicId=:to WHERE primaryTopicId=:from")
    suspend fun moveTopic(from: String, to: String)

    @Query("INSERT OR IGNORE INTO research_work_topics(workId,topicId) SELECT workId,:to FROM research_work_topics WHERE topicId=:from")
    suspend fun mergeRelatedTopics(from: String, to: String)

    @Query("DELETE FROM research_work_topics WHERE topicId=:id")
    suspend fun deleteRelatedTopic(id: String)

    @Query("DELETE FROM research_topics WHERE topicId=:id")
    suspend fun deleteTopic(id: String)

    @Query("SELECT * FROM research_passages WHERE versionId=:id ORDER BY start")
    suspend fun passages(id: String): List<EvidencePassage>

    @Query("SELECT * FROM research_study_fields WHERE versionId=:id")
    suspend fun fields(id: String): List<StudyField>

    @Query("SELECT * FROM research_appraisals WHERE versionId=:id ORDER BY createdAt DESC")
    suspend fun appraisals(id: String): List<EvidenceAppraisal>

    @Query("SELECT * FROM research_bindings WHERE messageId=:id AND responseRevision=:revision")
    suspend fun bindings(id: String, revision: String): List<MessageEvidenceBinding>

    @Query("SELECT * FROM research_bindings WHERE chatId=:id")
    suspend fun chatBindings(id: Int): List<MessageEvidenceBinding>

    @Query("DELETE FROM research_bindings WHERE chatId=:id")
    suspend fun deleteBindings(id: Int)

    @Query("SELECT * FROM research_jobs WHERE jobId=:id")
    suspend fun job(id: String): ExaminationJob?

    @Query("SELECT * FROM research_jobs WHERE jobId=:id")
    fun observeJob(id: String): Flow<ExaminationJob?>

    @Query("SELECT * FROM research_jobs WHERE dedupKey=:key")
    suspend fun jobForKey(key: String): ExaminationJob?

    @Query("SELECT * FROM research_jobs ORDER BY updatedAt DESC LIMIT 100")
    fun jobs(): Flow<List<ExaminationJob>>

    @Query("SELECT * FROM research_jobs WHERE chatId=:id")
    suspend fun chatJobs(id: Int): List<ExaminationJob>

    @Insert suspend fun transition(value: ExaminationTransition)

    @Query("SELECT * FROM research_job_transitions WHERE jobId=:id ORDER BY sequence")
    suspend fun transitions(id: String): List<ExaminationTransition>

    @Query("UPDATE research_jobs SET lastHeartbeatAt=:at WHERE jobId=:id AND lease=:lease AND workerId=:worker AND stage IN ('RESOLVING','READING','EXTRACTING','APPRAISING','REVIEWING')")
    suspend fun heartbeat(id: String, lease: Long, worker: String, at: Long): Int

    @Transaction
    suspend fun recoverJobs() {
        allJobs().filter { it.stage in ExaminationLifecycle.active }.forEach {
            interruptJob(it.jobId, it.lease, ExaminationStage.PAUSED, "Process interrupted; explicit resume required", "PROCESS_RECOVERY", System.currentTimeMillis())
        }
    }

    @Transaction
    suspend fun interruptJob(id: String, lease: Long, stage: ExaminationStage, reason: String, origin: String, at: Long): Int {
        val old = job(id) ?: return 0
        if (old.lease != lease || old.stage !in ExaminationLifecycle.active && old.stage != ExaminationStage.PAUSED && old.stage != ExaminationStage.FAILED) return 0
        job(old.copy(stage = stage, lease = lease + 1, terminalReason = reason, updatedAt = at, deadlineAt = 0, cancellationOrigin = origin))
        transition(ExaminationTransition(0, id, lease, stage, at, old.workerId, reason, origin))
        return 1
    }

    @Transaction
    suspend fun advanceJob(id: String, lease: Long, stage: ExaminationStage, checkpoint: String, reason: String?, at: Long, cancellationOrigin: String? = null): Int {
        val old = job(id) ?: return 0
        if (old.lease != lease || old.stage !in ExaminationLifecycle.active) return 0
        val origin = if (stage == ExaminationStage.PAUSED) cancellationOrigin ?: old.cancellationOrigin ?: "EXECUTION_INTERRUPTED" else null
        val phase = ExaminationLifecycle.phaseMillis(stage)
        job(
            old.copy(
                stage = stage, checkpointJson = checkpoint, terminalReason = reason, updatedAt = at,
                startedAt = if (old.startedAt == 0L && stage in ExaminationLifecycle.active && stage != ExaminationStage.QUEUED) at else old.startedAt,
                stageStartedAt = at, deadlineAt = if (phase > 0) at + phase else 0,
                lastHeartbeatAt = if (stage in ExaminationLifecycle.active) at else old.lastHeartbeatAt, cancellationOrigin = origin
            )
        )
        transition(ExaminationTransition(0, id, lease, stage, at, old.workerId, reason, origin))
        return 1
    }

    @Query("SELECT * FROM research_outbox WHERE id LIKE 'auto-examine:%' AND id>:after ORDER BY id LIMIT 100")
    suspend fun pendingExaminations(after: String = ""): List<ResearchOutbox>

    @Query("SELECT * FROM research_outbox ORDER BY createdAt LIMIT 100")
    suspend fun pendingOutbox(): List<ResearchOutbox>

    @Query("DELETE FROM research_outbox WHERE id=:id AND createdAt=:createdAt")
    suspend fun acknowledge(id: String, createdAt: Long)

    @Query("SELECT * FROM research_works")
    suspend fun allWorks(): List<ResearchWork>

    @Query("SELECT * FROM research_aliases")
    suspend fun allAliases(): List<ResearchAlias>

    @Query("SELECT * FROM research_versions")
    suspend fun allVersions(): List<ResearchWorkVersion>

    @Query("SELECT * FROM research_categories")
    suspend fun allCategories(): List<ResearchCategory>

    @Query("SELECT * FROM research_topics")
    suspend fun allTopics(): List<ResearchTopic>

    @Query("SELECT * FROM research_archive")
    suspend fun allMemberships(): List<ArchiveMembership>

    @Query("SELECT * FROM research_work_topics")
    suspend fun allRelatedTopics(): List<WorkTopic>

    @Query("SELECT * FROM research_study_families")
    suspend fun allFamilies(): List<ResearchStudyFamily>

    @Query("SELECT * FROM research_passages")
    suspend fun allPassages(): List<EvidencePassage>

    @Query("SELECT * FROM research_study_fields")
    suspend fun allFields(): List<StudyField>

    @Query("SELECT * FROM research_appraisals")
    suspend fun allAppraisals(): List<EvidenceAppraisal>

    @Query("SELECT * FROM research_bindings")
    suspend fun allBindings(): List<MessageEvidenceBinding>

    @Query("SELECT * FROM research_jobs")
    suspend fun allJobs(): List<ExaminationJob>

    @Query("SELECT * FROM research_body_roots")
    suspend fun allRoots(): List<BodyReference>

    @Query("SELECT * FROM research_tombstones")
    suspend fun allTombstones(): List<ResearchDeletionTombstone>

    @Query("SELECT EXISTS(SELECT 1 FROM research_imports WHERE importId=:id)")
    suspend fun imported(id: String): Boolean

    @Query("SELECT * FROM research_imports")
    suspend fun allImportReceipts(): List<ResearchImportReceipt>

    /** Room serializes writers; FIFO sequence never changes on reuse or clear. */
    @Transaction suspend fun archive(workId: String, versionId: String, topicId: String, now: Long, expectedEpoch: Long, unexaminedLimit: Int = 1000): Boolean {
        val seq = sequence() ?: ResearchSequence()
        if (seq.archiveEpoch != expectedEpoch || deleted("archive:$workId") != null) return false
        val version = requireNotNull(version(versionId))
        require(version.workId == workId)
        requireNotNull(topic(topicId))
        val existing = membership(workId)
        val retainedVersion = existing?.let { version(it.latestVersionId) }
        val selectedVersion = if (retainedVersion != null && (existing.protectionReason != null || retentionCoverageRank(retainedVersion.coverage) > retentionCoverageRank(version.coverage))) retainedVersion.versionId else versionId
        val next = existing?.copy(latestVersionId = selectedVersion, primaryTopicId = topicId) ?: ArchiveMembership(workId, seq.next, now, versionId, topicId, lifecycleGeneration = now)
        if (existing == null) sequence(seq.copy(next = Math.addExact(seq.next, 1)))
        membership(next)
        removeRoots("archive", workId)
        root(BodyReference("archive", workId, next.latestVersionId))
        trimUnexamined(unexaminedLimit, now)
        outbox(ResearchOutbox("archive-gc", "reconcile", now))
        // Protected and processing records are additional to the unexamined allowance.
        return true
    }

    @Transaction suspend fun trimUnexamined(keep: Int, now: Long) {
        require(keep >= 0)
        expiredHolds(now).forEach { expired ->
            jobsForWork(expired.workId).filter { it.stage in setOf(ExaminationStage.QUEUED, ExaminationStage.RESOLVING, ExaminationStage.READING, ExaminationStage.EXTRACTING, ExaminationStage.APPRAISING, ExaminationStage.REVIEWING, ExaminationStage.PAUSED) }.forEach { job ->
                interruptJob(job.jobId, job.lease, ExaminationStage.PAUSED, "RECOVERY_HOLD_EXPIRED: Resume requires checkpoint and body revalidation", "RETENTION_HOLD_EXPIRED", now)
                removeRoots("job", job.jobId)
            }
            membership(expired.copy(holdUntil = 0))
        }
        oldestExcess(keep, now).forEach { candidate ->
            val live = membership(candidate.workId) ?: return@forEach
            if (live.protectionReason == null && live.holdUntil <= now) {
                removeMembership(live.workId)
                removeRoots("archive", live.workId)
                removeImportRootsForWork(live.workId)
            }
        }
    }

    @Transaction suspend fun clearUnexamined(now: Long) {
        // Reconcile expired holds before selecting manual cleanup candidates.
        trimUnexamined(Int.MAX_VALUE, now)
        oldestExcess(0, now).forEach { membership ->
            tombstone(ResearchDeletionTombstone("archive:${membership.workId}", now))
            chatlessFence(membership.workId, now)
            jobsForWork(membership.workId).forEach { removeRoots("job", it.jobId) }
            removeMembership(membership.workId)
            removeRoots("archive", membership.workId)
            removeImportRootsForWork(membership.workId)
        }
        outbox(ResearchOutbox("archive-gc", "reconcile", now))
    }

    @Transaction suspend fun clearArchive(now: Long) {
        val seq = sequence() ?: ResearchSequence()
        sequence(seq.copy(archiveEpoch = Math.addExact(seq.archiveEpoch, 1)))
        // Retain a clear epoch; neither a late job nor repeated history import can repopulate it.
        tombstone(ResearchDeletionTombstone("archive-clear", now))
        allMemberships().forEach {
            tombstone(ResearchDeletionTombstone("archive:${it.workId}", now))
            chatlessFence(it.workId, now)
            jobsForWork(it.workId).forEach { job -> removeRoots("job", job.jobId) }
            removeRoots("assessment", it.workId)
            removeMembership(it.workId)
            removeRoots("archive", it.workId)
            removeImportRootsForWork(it.workId)
        }
        outbox(ResearchOutbox("archive-gc", "reconcile", now))
    }
}
