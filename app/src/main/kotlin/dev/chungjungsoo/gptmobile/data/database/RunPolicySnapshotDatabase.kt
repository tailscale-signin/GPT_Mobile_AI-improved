package dev.chungjungsoo.gptmobile.data.database

import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunPolicyRef
import dev.chungjungsoo.gptmobile.data.database.entity.RunPolicySnapshot

/** The same validation boundary is used by migration and complete/section backup import. */
object RunPolicySnapshotDatabase {
    val callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            createCleanupTrigger(db)
        }
    }

    fun createTables(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `run_policy_snapshots` (`chatId` INTEGER NOT NULL, `digest` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `kind` TEXT NOT NULL, `policyRevision` TEXT NOT NULL, `taskDigest` TEXT NOT NULL, `authorityDigest` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`chatId`, `digest`), FOREIGN KEY(`chatId`) REFERENCES `chats_v2`(`chat_id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `agent_run_policy_refs` (`runId` TEXT NOT NULL, `chatId` INTEGER NOT NULL, `digest` TEXT NOT NULL, PRIMARY KEY(`runId`, `digest`), FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`chatId`, `digest`) REFERENCES `run_policy_snapshots`(`chatId`, `digest`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_run_policy_refs_chatId_digest` ON `agent_run_policy_refs` (`chatId`, `digest`)")
        createCleanupTrigger(db)
    }

    fun createCleanupTrigger(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TRIGGER IF NOT EXISTS `run_policy_remove_orphan` AFTER DELETE ON `agent_run_policy_refs` BEGIN DELETE FROM run_policy_snapshots WHERE chatId = OLD.chatId AND digest = OLD.digest AND NOT EXISTS (SELECT 1 FROM agent_run_policy_refs r WHERE r.chatId = OLD.chatId AND r.digest = OLD.digest); END")
    }

    /** Unknown inline schemas and invalid legacy seals remain intact for explicit recovery. */
    fun normalizeInlineRecords(db: SupportSQLiteDatabase) {
        forEachRun(db) { run, temporary ->
            if (temporary || RunPolicySnapshotCodec.isReference(run.synthesisSnapshot)) return@forEachRun
            val normalized = RunPolicySnapshotCodec.normalize(run) ?: return@forEachRun
            db.execSQL("DELETE FROM agent_run_policy_refs WHERE runId = ?", arrayOf(run.runId))
            normalized.policies.forEach { row ->
                db.execSQL("INSERT OR IGNORE INTO run_policy_snapshots(chatId,digest,schemaVersion,kind,policyRevision,taskDigest,authorityDigest,payload) VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any>(row.chatId, row.digest, row.schemaVersion, row.kind, row.policyRevision, row.taskDigest, row.authorityDigest, row.payload))
                val retained = policyRows(db, run.chatId, row.digest).singleOrNull()
                if (retained != row) throw RunPolicySnapshotException("POLICY_SNAPSHOT_INVALID")
                db.execSQL("INSERT INTO agent_run_policy_refs(runId,chatId,digest) VALUES(?,?,?)", arrayOf<Any>(run.runId, run.chatId, row.digest))
            }
            db.execSQL("UPDATE agent_runs SET synthesis_snapshot = ? WHERE run_id = ?", arrayOf(normalized.snapshot, run.runId))
        }
    }

    /** Called before destination rows are removed. No missing reference is filled from current settings. */
    fun validateReferences(db: SupportSQLiteDatabase) {
        forEachRun(db) { run, temporary ->
            // Temporary conversations are removed from the backup copy. A damaged private
            // history must not block exporting unrelated persistent conversations.
            if (temporary) return@forEachRun
            val refs = db.query("SELECT runId,chatId,digest FROM agent_run_policy_refs WHERE runId = ?", arrayOf(run.runId)).use { rows ->
                buildList { while (rows.moveToNext()) add(AgentRunPolicyRef(rows.getString(0), rows.getInt(1), rows.getString(2))) }
            }
            val policies = refs.flatMap { policyRows(db, it.chatId, it.digest) }
            RunPolicySnapshotCodec.hydrate(run, policies, refs)
        }
    }

    private fun forEachRun(db: SupportSQLiteDatabase, block: (AgentRun, Boolean) -> Unit) {
        // The row-copy backup utility also supports databases with unrelated application tables.
        // If run storage exists, every normal policy/reference validation below still applies.
        if (!db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'agent_runs'").use { it.moveToFirst() }) return
        db.query("SELECT r.run_id,r.chat_id,r.user_message_id,r.assistant_message_id,r.profile_uid,r.provider_snapshot,r.model_snapshot,r.synthesis_key,r.synthesis_snapshot,c.is_temporary FROM agent_runs r JOIN chats_v2 c ON c.chat_id = r.chat_id WHERE r.synthesis_snapshot IS NOT NULL OR EXISTS (SELECT 1 FROM agent_run_policy_refs ref WHERE ref.runId = r.run_id)").use { rows ->
            while (rows.moveToNext()) {
                block(AgentRun(rows.getString(0), rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getString(4), rows.getString(5), rows.getString(6), synthesisKey = if (rows.isNull(7)) null else rows.getString(7), synthesisSnapshot = rows.getString(8)), rows.getInt(9) != 0)
            }
        }
    }

    private fun policyRows(db: SupportSQLiteDatabase, chatId: Int, digest: String): List<RunPolicySnapshot> =
        db.query("SELECT chatId,digest,schemaVersion,kind,policyRevision,taskDigest,authorityDigest,payload FROM run_policy_snapshots WHERE chatId = ? AND digest = ?", arrayOf<Any>(chatId, digest)).use { rows ->
            buildList { while (rows.moveToNext()) add(policy(rows)) }
        }

    private fun policy(rows: Cursor) = RunPolicySnapshot(rows.getInt(0), rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7))
}
