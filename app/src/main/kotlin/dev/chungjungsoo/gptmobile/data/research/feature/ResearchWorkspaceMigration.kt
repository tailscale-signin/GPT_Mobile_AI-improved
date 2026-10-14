package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive migration. Existing identifiers, immutable bodies and deletion epochs are untouched. */
object ResearchWorkspaceMigration : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS research_discoveries (discoveryId TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, engineId TEXT NOT NULL, providerRecordId TEXT NOT NULL, metadataJson TEXT NOT NULL, observedAt INTEGER NOT NULL, FOREIGN KEY(workId) REFERENCES research_works(workId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_research_discoveries_workId ON research_discoveries(workId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS research_outcomes (outcomeId TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, versionId TEXT NOT NULL, outcomeKey TEXT NOT NULL, reported TEXT NOT NULL, limitations TEXT NOT NULL, passageIdsJson TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, producerVersion TEXT NOT NULL, FOREIGN KEY(versionId) REFERENCES research_versions(versionId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_research_outcomes_versionId ON research_outcomes(versionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_research_outcomes_workId ON research_outcomes(workId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS research_recall_exclusions (workId TEXT NOT NULL PRIMARY KEY, excludedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS research_handoffs (handoffId TEXT NOT NULL PRIMARY KEY, query TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
    }
}
