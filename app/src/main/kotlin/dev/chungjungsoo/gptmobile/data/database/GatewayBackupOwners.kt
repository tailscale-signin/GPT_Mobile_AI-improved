package dev.chungjungsoo.gptmobile.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/** Retain only the original local owner; a backup never grants permission to resume it. */
internal object GatewayBackupOwners {
    private data class Owner(val runId: String, val chatId: Int, val userId: Int, val assistantId: Int, val profile: String, val model: String, val jobId: String?, val endpoint: String?)

    fun runIds(database: SupportSQLiteDatabase): Set<String> {
        val tables = database.query("SELECT name FROM sqlite_master WHERE type='table'").use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
        if (!tables.containsAll(listOf("gateway_submissions", "gateway_jobs", "gateway_conversations"))) return emptySet()
        return buildSet {
            database.query(
                """
                SELECT r.run_id,r.chat_id,r.user_message_id,r.assistant_message_id,r.profile_uid,r.model_snapshot,r.gateway_job_id,r.gateway_base_url
                FROM agent_runs r
                JOIN messages_v2 m ON m.message_id=r.assistant_message_id AND m.chat_id=r.chat_id
                    AND m.platform_type=r.profile_uid AND m.current_run_id=r.run_id AND m.ownership_fence=r.ownership_fence
                JOIN messages_v2 u ON u.message_id=r.user_message_id AND u.chat_id=r.chat_id AND u.platform_type IS NULL
                WHERE LENGTH(TRIM(r.profile_uid))>0 AND LENGTH(TRIM(r.model_snapshot))>0 AND r.synthesis_key IS NULL
                """.trimIndent()
            ).use { rows ->
                while (rows.moveToNext()) {
                    val owner = Owner(rows.getString(0), rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getString(4), rows.getString(5), rows.getString(6)?.takeIf(String::isNotBlank), rows.getString(7)?.trimEnd('/')?.takeIf(String::isNotBlank))
                    val commandKind = submissionKind(database, owner)
                    if (owner.jobId == null) {
                        if (commandKind != null) add(owner.runId)
                        continue
                    }
                    if (owner.endpoint == null) continue
                    val known = database.query("SELECT COUNT(*) FROM gateway_jobs WHERE remoteJobId=?", arrayOf<Any?>(owner.jobId)).use {
                        it.moveToFirst()
                        it.getInt(0) > 0
                    }
                    if (!known || matchesCatalog(database, owner, commandKind ?: "generation")) add(owner.runId)
                }
            }
        }
    }

    private fun submissionKind(database: SupportSQLiteDatabase, owner: Owner): String? = database.query(
        "SELECT s.payload,s.endpoint,c.snapshot,c.endpoint FROM gateway_submissions s LEFT JOIN gateway_conversations c ON c.`key`=s.conversationKey AND c.localChatId=s.chatId AND c.executionProfileUid=s.executionProfileUid WHERE s.runId=? AND s.chatId=? AND s.userMessageId=? AND s.assistantMessageId=? AND s.executionProfileUid=?",
        arrayOf<Any?>(owner.runId, owner.chatId, owner.userId, owner.assistantId, owner.profile)
    ).use { rows ->
        while (rows.moveToNext()) {
            val endpoint = rows.getString(1)?.trimEnd('/') ?: continue
            if (endpoint.isBlank() || owner.endpoint != null && owner.endpoint != endpoint || !rows.isNull(3) && rows.getString(3).trimEnd('/') != endpoint) continue
            val payload = runCatching { JSONObject(rows.getString(0)) }.getOrNull()
            val snapshot = if (rows.isNull(2)) null else runCatching { JSONObject(rows.getString(2)) }.getOrNull()
            val model = payload?.optString("model")?.takeIf(String::isNotBlank) ?: snapshot?.optString("model")
            if (model != owner.model) continue
            return@use if (payload?.has("repository") == true || snapshot?.optString("repository")?.takeIf { it.isNotBlank() && it != "null" } != null) "engineering" else "generation"
        }
        null
    }

    private fun matchesCatalog(database: SupportSQLiteDatabase, owner: Owner, kind: String): Boolean = database.query(
        """
        SELECT c.snapshot,j.snapshot FROM gateway_jobs j
        JOIN gateway_conversations c ON c.`key`=j.conversationKey AND c.gatewayId=j.gatewayId AND c.principalId=j.principalId
            AND c.endpoint=j.endpoint AND c.connectionProfileUid=j.connectionProfileUid AND c.executionProfileUid=j.executionProfileUid
        WHERE j.remoteJobId=? AND j.kind=? AND c.localChatId=? AND c.executionProfileUid=? AND RTRIM(j.endpoint,'/')=?
        """.trimIndent(),
        arrayOf<Any?>(owner.jobId, kind, owner.chatId, owner.profile, owner.endpoint)
    ).use { rows ->
        while (rows.moveToNext()) {
            val snapshot = runCatching { JSONObject(rows.getString(0)) }.getOrNull() ?: continue
            val job = runCatching { JSONObject(rows.getString(1)) }.getOrNull() ?: continue
            if (snapshot.optString("model") == owner.model && job.optString("model", owner.model) == owner.model) return@use true
        }
        false
    }
}
