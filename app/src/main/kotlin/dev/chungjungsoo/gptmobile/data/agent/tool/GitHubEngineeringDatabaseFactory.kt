package dev.chungjungsoo.gptmobile.data.agent.tool

import android.content.Context
import androidx.room.Room

internal object GitHubEngineeringDatabaseFactory {
    @Volatile
    private var instance: GitHubEngineeringDatabase? = null

    fun open(context: Context): GitHubEngineeringDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GitHubEngineeringDatabase::class.java,
                "engineering-ledger.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS engineering_runs (taskId TEXT NOT NULL PRIMARY KEY, " +
                    "connectionUid TEXT NOT NULL, patchJson TEXT NOT NULL, patchDigest TEXT NOT NULL, " +
                    "commitMessage TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, policyBranch TEXT NOT NULL, " +
                    "approvedActor TEXT, approvedAtEpochMs INTEGER, approvalExpiresAtEpochMs INTEGER, " +
                    "approvalRevoked INTEGER NOT NULL, ciStartedAtEpochMs INTEGER, ciDeadlineEpochMs INTEGER, " +
                    "retryAtEpochMs INTEGER, requiredChecksJson TEXT NOT NULL, verifiedChecksJson TEXT, hardwareReceiptJson TEXT, " +
                    "notice TEXT NOT NULL, FOREIGN KEY(taskId) REFERENCES engineering_tasks(taskId) ON UPDATE NO ACTION ON DELETE RESTRICT)"
            )
        }
    }
}
