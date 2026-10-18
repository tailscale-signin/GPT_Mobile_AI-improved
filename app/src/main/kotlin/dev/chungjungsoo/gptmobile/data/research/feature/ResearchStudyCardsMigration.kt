package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ResearchStudyCardsMigration : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS research_study_reviews (reviewId TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, versionId TEXT NOT NULL, jobId TEXT NOT NULL, jobLease INTEGER NOT NULL, profileUid TEXT NOT NULL, model TEXT NOT NULL, configHash TEXT NOT NULL, sourceHash TEXT NOT NULL, summaryHash TEXT NOT NULL, artifactJson TEXT NOT NULL, reliabilityScore INTEGER, accuracyScore INTEGER, overallScore INTEGER, status TEXT NOT NULL, createdAt INTEGER NOT NULL, rubricVersion INTEGER NOT NULL, coverageJson TEXT NOT NULL, design TEXT NOT NULL, FOREIGN KEY(versionId) REFERENCES research_versions(versionId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_research_study_reviews_versionId ON research_study_reviews(versionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_research_study_reviews_workId ON research_study_reviews(workId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS research_work_studies (workId TEXT NOT NULL, studyId TEXT NOT NULL, provenance TEXT NOT NULL, revisedAt INTEGER NOT NULL, PRIMARY KEY(workId,studyId), FOREIGN KEY(workId) REFERENCES research_works(workId) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(studyId) REFERENCES research_study_families(familyId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_research_work_studies_studyId ON research_work_studies(studyId)")
        db.execSQL("INSERT OR IGNORE INTO research_work_studies SELECT w.workId,w.studyFamilyId,f.provenance,f.revisedAt FROM research_works w JOIN research_study_families f ON f.familyId=w.studyFamilyId")
    }
}
