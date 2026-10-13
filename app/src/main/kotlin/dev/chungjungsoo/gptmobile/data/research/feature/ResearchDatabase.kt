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

@Database(entities = [ResearchWork::class, ResearchAlias::class, ResearchWorkVersion::class, ResearchCategory::class, ResearchTopic::class, ArchiveMembership::class, WorkTopic::class, ResearchStudyFamily::class, EvidencePassage::class, StudyField::class, EvidenceAppraisal::class, MessageEvidenceBinding::class, ExaminationJob::class, BodyReference::class, ResearchDeletionTombstone::class, ResearchOutbox::class, ResearchSequence::class, ResearchImportReceipt::class], version = 1, exportSchema = true)
abstract class ResearchDatabase : RoomDatabase() {
    abstract fun research(): ResearchDao
}

@Module
@InstallIn(SingletonComponent::class)
object ResearchDatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): ResearchDatabase = Room.databaseBuilder(context, ResearchDatabase::class.java, "research-feature.db").build()
}

data class ArchiveRow(val workId: String, val title: String, val canonicalUrl: String, val publicationYear: Int?, val peerReview: PublicationReview, val retracted: Boolean?, val latestVersionId: String, val archiveSequence: Long, val primaryTopicId: String, val coverage: ArticleCoverage, val retrievedAt: Long, val textBytes: Long)

@Dao
interface ResearchDao {
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

    @Query("SELECT w.workId,w.title,w.canonicalUrl,w.publicationYear,w.peerReview,w.retracted,a.latestVersionId,a.archiveSequence,a.primaryTopicId,v.coverage,v.retrievedAt,v.textBytes FROM research_archive a JOIN research_works w ON w.workId=a.workId JOIN research_versions v ON v.versionId=a.latestVersionId WHERE (:topic='' OR a.primaryTopicId=:topic OR EXISTS(SELECT 1 FROM research_work_topics t WHERE t.workId=w.workId AND t.topicId=:topic)) AND (:query='' OR w.title LIKE '%' || :query || '%' ESCAPE '\\' OR w.workId IN (SELECT workId FROM research_aliases WHERE alias LIKE '%' || :query || '%' ESCAPE '\\')) AND (:reviewed=0 OR w.peerReview='CONFIRMED') ORDER BY a.archiveSequence DESC LIMIT :limit OFFSET :offset")
    suspend fun page(query: String, topic: String, reviewed: Boolean, limit: Int = 40, offset: Int = 0): List<ArchiveRow>

    @Query("SELECT * FROM research_archive ORDER BY archiveSequence LIMIT MAX(0, (SELECT COUNT(*) FROM research_archive)-:keep)")
    suspend fun oldestExcess(keep: Int): List<ArchiveMembership>

    @Query("DELETE FROM research_archive WHERE workId=:id")
    suspend fun removeMembership(id: String)

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

    @Query("SELECT * FROM research_jobs WHERE dedupKey=:key")
    suspend fun jobForKey(key: String): ExaminationJob?

    @Query("SELECT * FROM research_jobs ORDER BY updatedAt DESC LIMIT 100")
    fun jobs(): Flow<List<ExaminationJob>>

    @Query("SELECT * FROM research_jobs WHERE chatId=:id")
    suspend fun chatJobs(id: Int): List<ExaminationJob>

    @Query("UPDATE research_jobs SET stage='PAUSED',terminalReason='Process interrupted; explicit resume required' WHERE stage IN ('RESOLVING','READING','EXTRACTING','APPRAISING','REVIEWING')")
    suspend fun recoverJobs()

    @Query("UPDATE research_jobs SET stage=:stage, checkpointJson=:checkpoint,terminalReason=:reason,updatedAt=:at WHERE jobId=:id AND lease=:lease AND stage NOT IN ('CANCELED','COMPLETE_FULL','COMPLETE_PARTIAL','ABSTRACT_ONLY')")
    suspend fun advanceJob(id: String, lease: Long, stage: ExaminationStage, checkpoint: String, reason: String?, at: Long): Int

    @Query("SELECT * FROM research_outbox ORDER BY createdAt LIMIT 100")
    suspend fun pendingOutbox(): List<ResearchOutbox>

    @Query("DELETE FROM research_outbox WHERE id=:id")
    suspend fun acknowledge(id: String)

    /** Room serializes writers; FIFO sequence never changes on reuse or clear. */
    @Transaction suspend fun archive(workId: String, versionId: String, topicId: String, now: Long, expectedEpoch: Long): Boolean {
        val seq = sequence() ?: ResearchSequence()
        if (seq.archiveEpoch != expectedEpoch || deleted("archive:$workId") != null) return false
        val version = requireNotNull(version(versionId))
        require(version.workId == workId && version.publicSource)
        requireNotNull(topic(topicId))
        val existing = membership(workId)
        val next = existing?.copy(latestVersionId = versionId, primaryTopicId = topicId) ?: ArchiveMembership(workId, seq.next, now, versionId, topicId)
        if (existing == null) sequence(seq.copy(next = Math.addExact(seq.next, 1)))
        membership(next)
        removeRoots("archive", workId)
        root(BodyReference("archive", workId, versionId))
        oldestExcess(1000).forEach {
            removeMembership(it.workId)
            removeRoots("archive", it.workId)
        }
        outbox(ResearchOutbox("archive-gc", "reconcile", now))
        check(count() <= 1000)
        return true
    }

    @Transaction suspend fun clearArchive(now: Long) {
        val seq = sequence() ?: ResearchSequence()
        sequence(seq.copy(archiveEpoch = Math.addExact(seq.archiveEpoch, 1)))
        // Retain a clear epoch; neither a late job nor repeated history import can repopulate it.
        tombstone(ResearchDeletionTombstone("archive-clear", now))
        oldestExcess(0).forEach {
            removeMembership(it.workId)
            removeRoots("archive", it.workId)
        }
        outbox(ResearchOutbox("archive-gc", "reconcile", now))
    }
}
