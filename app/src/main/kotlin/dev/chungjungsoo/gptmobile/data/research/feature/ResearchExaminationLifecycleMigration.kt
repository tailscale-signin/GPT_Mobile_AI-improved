package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Unknown historical times stay unknown; a migration never invents an active owner. */
object ResearchExaminationLifecycleMigration : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE research_jobs ADD COLUMN queuedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE research_jobs ADD COLUMN startedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE research_jobs ADD COLUMN lastHeartbeatAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE research_jobs ADD COLUMN stageStartedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE research_jobs ADD COLUMN deadlineAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE research_jobs ADD COLUMN workerId TEXT")
        db.execSQL("ALTER TABLE research_jobs ADD COLUMN cancellationOrigin TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS research_job_transitions (sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, jobId TEXT NOT NULL, lease INTEGER NOT NULL, stage TEXT NOT NULL, at INTEGER NOT NULL, workerId TEXT, reason TEXT, cancellationOrigin TEXT, FOREIGN KEY(jobId) REFERENCES research_jobs(jobId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_research_job_transitions_jobId ON research_job_transitions(jobId)")
    }
}
