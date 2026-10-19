package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve ambiguous legacy assessments without granting evidence-checked status. */
object ResearchRetentionMigration : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS research_report_sections (checkpointId TEXT NOT NULL PRIMARY KEY, chatId INTEGER NOT NULL, owner TEXT NOT NULL, packetDigest TEXT NOT NULL, heading TEXT NOT NULL, body TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS research_save_receipts (receiptId TEXT NOT NULL PRIMARY KEY, runId TEXT NOT NULL, sourceId TEXT NOT NULL, chatId INTEGER NOT NULL, workId TEXT, versionId TEXT, disposition TEXT NOT NULL, reason TEXT, updatedAt INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE research_archive ADD COLUMN protectionReason TEXT")
        db.execSQL("ALTER TABLE research_archive ADD COLUMN acceptedReviewId TEXT")
        db.execSQL("ALTER TABLE research_archive ADD COLUMN verifiedVersionId TEXT")
        db.execSQL("ALTER TABLE research_archive ADD COLUMN holdUntil INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE research_archive ADD COLUMN lifecycleGeneration INTEGER NOT NULL DEFAULT 0")
        db.execSQL("INSERT OR IGNORE INTO research_categories(categoryId,label,revision) VALUES ('uncategorized','Uncategorized',1)")
        db.execSQL("INSERT OR IGNORE INTO research_topics(topicId,categoryId,label,revision) VALUES ('uncategorized:needs-classification','uncategorized','Needs classification',1)")
        db.execSQL("INSERT OR IGNORE INTO research_archive(workId,archiveSequence,firstArchivedAt,latestVersionId,primaryTopicId,protectionReason,holdUntil,lifecycleGeneration) SELECT r.workId,(SELECT COALESCE(MAX(archiveSequence),0) FROM research_archive)+ROW_NUMBER() OVER (ORDER BY r.createdAt,r.workId),r.createdAt,r.versionId,'uncategorized:needs-classification','LEGACY_HOLD',0,0 FROM research_study_reviews r WHERE r.reviewId=(SELECT reviewId FROM research_study_reviews WHERE workId=r.workId ORDER BY createdAt DESC,reviewId DESC LIMIT 1) AND NOT EXISTS(SELECT 1 FROM research_tombstones t WHERE t.`key`='archive:' || r.workId) AND r.createdAt>COALESCE((SELECT epoch FROM research_tombstones WHERE `key`='archive-clear'),0)")
        db.execSQL("INSERT OR IGNORE INTO research_sequence(id,next,archiveEpoch) VALUES (1,1,0)")
        db.execSQL("UPDATE research_sequence SET next=MAX(next,(SELECT COALESCE(MAX(archiveSequence),0)+1 FROM research_archive)) WHERE id=1")
        db.execSQL("UPDATE research_archive SET protectionReason='LEGACY_HOLD' WHERE EXISTS (SELECT 1 FROM research_study_reviews r WHERE r.workId=research_archive.workId)")
        db.execSQL("INSERT OR IGNORE INTO research_body_roots(rootKind,rootId,versionId) SELECT 'assessment',r.workId,r.versionId FROM research_study_reviews r JOIN research_archive a ON a.workId=r.workId")
    }
}

internal fun retentionCoverageRank(coverage: ArticleCoverage): Int = when (coverage) {
    ArticleCoverage.UNAVAILABLE -> 0
    ArticleCoverage.METADATA_ONLY -> 1
    ArticleCoverage.ABSTRACT_ONLY -> 2
    ArticleCoverage.PARTIAL_TEXT -> 3
    ArticleCoverage.FULL_TEXT_CAPTURED -> 4
    ArticleCoverage.FULL_ARTICLE_EXAMINED -> 5
}
