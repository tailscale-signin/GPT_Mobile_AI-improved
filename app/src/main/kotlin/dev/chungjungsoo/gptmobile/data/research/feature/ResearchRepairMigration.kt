package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive, resumable repair. Classification never removes a protected archive record. */
object ResearchRepairMigration : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS research_source_validations (validationId TEXT NOT NULL PRIMARY KEY, versionId TEXT NOT NULL, bodyHash TEXT NOT NULL, validatorRevision INTEGER NOT NULL, kind TEXT NOT NULL, reasonsJson TEXT NOT NULL, previousCoverage TEXT NOT NULL, workKind TEXT NOT NULL, classifiedAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_research_source_validations_versionId ON research_source_validations(versionId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS research_identity_resolutions (resolutionId TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, relatedWorkId TEXT NOT NULL, canonicalWorkId TEXT NOT NULL, authorityScope TEXT NOT NULL, relation TEXT NOT NULL, evidence TEXT NOT NULL, disposition TEXT NOT NULL, recordedAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_research_identity_resolutions_workId ON research_identity_resolutions(workId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_research_identity_resolutions_relatedWorkId ON research_identity_resolutions(relatedWorkId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS research_repair_cursors (kind TEXT NOT NULL PRIMARY KEY, position TEXT NOT NULL, revision INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
    }
}
