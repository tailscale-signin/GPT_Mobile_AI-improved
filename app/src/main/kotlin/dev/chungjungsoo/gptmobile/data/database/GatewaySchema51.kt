package dev.chungjungsoo.gptmobile.data.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationSnapshot
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayJobSummary
import dev.chungjungsoo.gptmobile.data.github.gatewayIdentityKey
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Two development builds used version 50. Preserve both without destructive fallback. */
internal object GatewaySchema51 {
    private val json = Json { ignoreUnknownKeys = true }
    private const val NOTICE = "Retained from the earlier gateway history format. Reconnect to verify the complete canonical transcript. Original cached evidence remains available."
    private val oldTables = listOf("gateway_scopes", "gateway_conversations", "gateway_jobs", "gateway_messages", "gateway_replay_cursors", "gateway_commands", "gateway_engineering_bindings", "gateway_engineering_updates")

    fun migrate(db: SupportSQLiteDatabase) {
        val alternate = db.query("PRAGMA table_info(gateway_conversations)").use { rows -> generateSequence { if (rows.moveToNext()) rows.getString(1) else null }.any { it == "scopeId" } }
        if (alternate) {
            for (table in oldTables) db.execSQL("ALTER TABLE `$table` RENAME TO `retained_v50_$table`")
            // Renamed indexes keep their original names; release only those names, never evidence rows.
            val indexes = db.query("SELECT name FROM sqlite_master WHERE type='index' AND sql IS NOT NULL AND tbl_name LIKE 'retained_v50_%'").use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
            for (index in indexes) db.execSQL("DROP INDEX `${index.replace("`", "``")}`")
            ChatDatabaseV2Migrations.createCanonicalGateway50(db)
        }
        db.execSQL("ALTER TABLE gateway_conversations RENAME TO gateway_conversations_before51")
        db.execSQL("DROP INDEX IF EXISTS index_gateway_conversations_localChatId")
        db.execSQL("CREATE TABLE gateway_conversations (`key` TEXT NOT NULL, gatewayId TEXT NOT NULL, principalId TEXT NOT NULL, conversationId TEXT NOT NULL, connectionProfileUid TEXT NOT NULL, endpoint TEXT NOT NULL, executionProfileUid TEXT NOT NULL, localChatId INTEGER, snapshot TEXT NOT NULL, revision INTEGER NOT NULL, messageCursor TEXT, syncedAtMillis INTEGER NOT NULL, syncHasMore INTEGER NOT NULL, historyComplete INTEGER NOT NULL, historyLimitation TEXT, PRIMARY KEY(`key`))")
        db.execSQL("INSERT INTO gateway_conversations SELECT *,1,NULL FROM gateway_conversations_before51")
        db.execSQL("DROP TABLE gateway_conversations_before51")
        db.execSQL("CREATE INDEX index_gateway_conversations_localChatId ON gateway_conversations(localChatId)")
        db.execSQL("CREATE TABLE gateway_message_parts (conversationKey TEXT NOT NULL, logicalMessageId TEXT NOT NULL, revision INTEGER NOT NULL, partIndex INTEGER NOT NULL, partCount INTEGER NOT NULL, messageId TEXT NOT NULL, turnId TEXT NOT NULL, role TEXT NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, publicationId TEXT, commandId TEXT, contentSha256 TEXT, createdAt REAL NOT NULL, contentVerified INTEGER NOT NULL, PRIMARY KEY(conversationKey,logicalMessageId,revision,partIndex))")
        db.execSQL("CREATE INDEX index_gateway_message_parts_conversationKey_createdAt_logicalMessageId_partIndex ON gateway_message_parts(conversationKey,createdAt,logicalMessageId,partIndex)")
        if (alternate) importAlternate(db) else splitCanonicalMessages(db)
    }

    private data class Scope(val connection: String, val endpoint: String, val gateway: String, val principal: String)

    private fun importAlternate(db: SupportSQLiteDatabase) {
        val scopes = mutableMapOf<String, Scope>()
        db.query("SELECT id,connectionUid,endpoint,gatewayId,principalId FROM retained_v50_gateway_scopes").use { rows ->
            while (rows.moveToNext()) scopes[rows.text("id")] = Scope(rows.text("connectionUid"), rows.text("endpoint").trimEnd('/'), rows.text("gatewayId"), rows.text("principalId"))
        }
        val keys = mutableMapOf<String, String>()
        val profiles = mutableMapOf<String, String>()
        db.query("SELECT * FROM retained_v50_gateway_conversations").use { rows ->
            while (rows.moveToNext()) {
                val scope = scopes[rows.text("scopeId")] ?: continue
                if (scope.gateway.isBlank() || scope.principal.isBlank() || rows.text("conversationId").startsWith("legacy_")) continue
                val id = rows.text("conversationId")
                val key = gatewayIdentityKey(scope.gateway, scope.principal, id)
                val profile = rows.text("profileUid")
                val localId = rows.nullLong("localChatId")?.toInt()?.takeIf { candidate -> db.query("SELECT 1 FROM chats_v2 WHERE chat_id=? AND is_temporary=0", arrayOf<Any?>(candidate)).use { it.moveToFirst() } }
                val snapshot = GatewayConversationSnapshot(gatewayId = scope.gateway, principalId = scope.principal, conversationId = id, title = rows.text("title"), profileId = profile.takeIf(String::isNotBlank), model = rows.text("model"), repository = rows.optional("repository"), branch = rows.optional("branch"), revision = rows.getLong(rows.getColumnIndexOrThrow("revision")), updatedAt = rows.getLong(rows.getColumnIndexOrThrow("updatedAt")) / 1000.0, sourceBranch = rows.optional("branch"), sourceSha = rows.optional("sourceSha"), historyComplete = false, historyLimitation = NOTICE)
                db.execSQL("INSERT OR IGNORE INTO gateway_conversations VALUES(?,?,?,?,?,?,?,?,?,-1,NULL,0,1,0,?)", arrayOf<Any?>(key, scope.gateway, scope.principal, id, scope.connection, scope.endpoint, profile, localId, json.encodeToString(snapshot), NOTICE))
                keys[rows.text("id")] = key
                profiles[rows.text("id")] = profile
            }
        }
        db.query("SELECT * FROM retained_v50_gateway_jobs").use { rows ->
            while (rows.moveToNext()) {
                val scope = scopes[rows.text("scopeId")] ?: continue
                val key = keys[rows.text("conversationKey")]
                val payload = runCatching { JSONObject(rows.text("payload")) }.getOrNull() ?: continue
                val kind = if (rows.text("kind") == "chat") "generation" else rows.text("kind")
                if (kind !in setOf("engineering", "generation")) continue
                val jobId = rows.text("jobId")
                val summary = GatewayJobSummary(jobId, kind, payload.stringOrNull("conversation_id"), payload.optString("title"), payload.stringOrNull("profile_id"), payload.optString("model"), payload.stringOrNull("repository"), payload.stringOrNull("branch"), payload.stringOrNull("base"), payload.optString("scheduling_state", "needs_attention"), phase = payload.stringOrNull("phase"), revision = payload.optLong("revision"), acceptedAt = rows.nullLong("acceptedAt")?.div(1000.0) ?: 0.0, finishedAt = rows.nullLong("finishedAt")?.div(1000.0), sourceBranch = payload.stringOrNull("source_branch"), sourceSha = payload.stringOrNull("source_sha"), workingBranch = payload.stringOrNull("working_branch"))
                db.execSQL("INSERT OR IGNORE INTO gateway_jobs VALUES(?,?,?,?,?,?,?,?,?,?,0,-1)", arrayOf<Any?>(gatewayIdentityKey(scope.gateway, scope.principal, kind, jobId), scope.gateway, scope.principal, kind, jobId, key, scope.connection, scope.endpoint, profiles[rows.text("conversationKey")], json.encodeToString(summary)))
            }
        }
        db.query("SELECT * FROM retained_v50_gateway_engineering_bindings").use { rows ->
            while (rows.moveToNext()) {
                val scope = scopes[rows.text("scopeId")] ?: continue
                val requestId = rows.text("requestId")
                val decoded = runCatching {
                    val raw = JSONObject(rows.text("binding"))
                    val request = raw.getJSONObject("request")
                    request.put("task_scope", request.optString("scope", "implement"))
                    if (!request.has("conversation_id") && raw.has("conversationId")) request.put("conversation_id", raw.get("conversationId"))
                    for ((old, current) in listOf("userMessageId" to "localUserMessageId", "assistantMessageId" to "localAssistantMessageId")) if (raw.has(old)) raw.put(current, raw.get(old))
                    raw.put("localChatId", rows.getInt(rows.getColumnIndexOrThrow("localChatId")))
                    raw.put("syncedRevision", -1)
                    raw.put("seenRevision", rows.getInt(rows.getColumnIndexOrThrow("seenRevision")))
                    raw.put("notifiedRevision", rows.getInt(rows.getColumnIndexOrThrow("notifiedRevision")))
                    val jobId = rows.optional("jobId")
                    if (jobId != null) db.query("SELECT engineeringPayload FROM retained_v50_gateway_jobs WHERE scopeId=? AND kind='engineering' AND jobId=?", arrayOf<Any?>(rows.text("scopeId"), jobId)).use { job -> if (job.moveToFirst() && !job.isNull(0)) raw.put("job", JSONObject(job.getString(0))) }
                    json.decodeFromString<GatewayEngineeringBinding>(raw.toString()).also { require(it.request.requestId == requestId && it.endpoint.trimEnd('/') == scope.endpoint && it.profileUid == scope.connection) }
                }.getOrNull() ?: continue
                db.execSQL("INSERT OR IGNORE INTO gateway_engineering_bindings VALUES(?,?,?,?,0)", arrayOf<Any?>(requestId, decoded.chatId, decoded.job?.id, json.encodeToString(decoded)))
            }
        }
        db.execSQL("INSERT OR IGNORE INTO gateway_engineering_events SELECT requestId,revision,state,message,createdAt FROM retained_v50_gateway_engineering_updates")
        db.query("SELECT * FROM retained_v50_gateway_commands").use { rows ->
            while (rows.moveToNext()) {
                val scope = scopes[rows.text("scopeId")] ?: continue
                val oldKey = rows.optional("conversationKey")
                val profile = oldKey?.let(profiles::get) ?: db.query("SELECT binding FROM gateway_engineering_bindings WHERE requestId=?", arrayOf<Any?>(rows.text("id"))).use { b -> if (b.moveToFirst()) runCatching { json.decodeFromString<GatewayEngineeringBinding>(b.getString(0)).request.profileId }.getOrNull() else null } ?: scope.connection
                val state = when (rows.text("state")) {
                    "ACCEPTED" -> "ACCEPTED"
                    "REJECTED" -> "REJECTED:400"
                    else -> "MIGRATED_REVIEW"
                }
                db.execSQL("INSERT OR IGNORE INTO gateway_submissions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(rows.text("id"), rows.getInt(rows.getColumnIndexOrThrow("localChatId")), rows.nullLong("userMessageId"), rows.nullLong("assistantMessageId"), rows.optional("localRunId"), scope.connection, scope.endpoint, profile, rows.text("payloadDigest"), rows.text("payload"), state, oldKey?.let(keys::get), null, rows.getLong(rows.getColumnIndexOrThrow("createdAt"))))
            }
        }
        db.execSQL("INSERT OR REPLACE INTO gateway_migrations VALUES('retained-upstream-v50','Original rows retained; incompatible cursors reset; pending commands require authenticated receipt reconciliation',0)")
    }

    /** Read bounded substrings, even when the old row exceeds Android CursorWindow capacity. */
    private fun splitCanonicalMessages(db: SupportSQLiteDatabase) {
        db.query("SELECT conversationKey,messageId,turnId,role,status,revision,publicationId,createdAt,localMessageId,length(content) AS characters FROM gateway_messages").use { rows ->
            while (rows.moveToNext()) {
                val key = rows.text("conversationKey")
                val id = rows.text("messageId")
                val chars = rows.getInt(rows.getColumnIndexOrThrow("characters"))
                val count = maxOf(1, (chars + 65535) / 65536)
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                for (index in 0 until count) {
                    val content = db.query("SELECT substr(content,?,65536) FROM gateway_messages WHERE conversationKey=? AND messageId=?", arrayOf<Any?>(index * 65536 + 1, key, id)).use { value ->
                        check(value.moveToFirst())
                        value.getString(0)
                    }
                    val encoded = content.toByteArray(Charsets.UTF_8)
                    digest.update(encoded)
                    bytes += encoded.size
                    db.execSQL("INSERT INTO gateway_message_parts VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)", arrayOf<Any?>(key, id, rows.getLong(rows.getColumnIndexOrThrow("revision")), index, count, "$id:$index", rows.text("turnId"), rows.text("role"), "legacy_cache", content, rows.text("status"), rows.optional("publicationId"), null, null, rows.getDouble(rows.getColumnIndexOrThrow("createdAt"))))
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                db.execSQL("UPDATE gateway_message_parts SET contentSha256=? WHERE conversationKey=? AND logicalMessageId=?", arrayOf<Any?>(hash, key, id))
                if (bytes > 512 * 1024) db.execSQL("UPDATE gateway_messages SET content=? WHERE conversationKey=? AND messageId=?", arrayOf<Any?>("This complete gateway message contains $bytes UTF-8 bytes. Open Saved transcript from this conversation in Gateway or Jobs to read every retained part.", key, id))
                rows.nullLong("localMessageId")?.let { migrateLargeMirror(db, key, it, rows.text("role")) }
            }
        }
    }

    /** A private50 mirror could contain the same huge text and a huge timeline. Preserve both. */
    private fun migrateLargeMirror(db: SupportSQLiteDatabase, conversationKey: String, messageId: Long, role: String) {
        db.query("SELECT m.chat_id,m.ownership_fence,m.current_run_id,m.created_at,length(CAST(m.content AS BLOB)),length(CAST(m.timeline AS BLOB)) FROM messages_v2 m JOIN gateway_conversations c ON c.localChatId=m.chat_id WHERE c.`key`=? AND m.message_id=? AND ((?='user' AND m.platform_type IS NULL) OR (?='assistant' AND m.platform_type=c.executionProfileUid))", arrayOf<Any?>(conversationKey, messageId, role, role)).use { owner ->
            if (!owner.moveToFirst()) return
            val largeContent = owner.getLong(4) > 512 * 1024
            val largeTimeline = owner.getLong(5) > 512 * 1024
            if (!largeContent && !largeTimeline) return
            val fence = owner.getLong(1)
            for (field in listOfNotNull("content".takeIf { largeContent }, "timeline".takeIf { largeTimeline })) {
                val logicalId = "retained-local:$messageId:$field"
                val characters = db.query("SELECT length($field) FROM messages_v2 WHERE message_id=?", arrayOf<Any?>(messageId)).use {
                    check(it.moveToFirst())
                    it.getInt(0)
                }
                val count = maxOf(1, (characters + 65535) / 65536)
                val digest = MessageDigest.getInstance("SHA-256")
                for (part in 0 until count) {
                    val content = db.query("SELECT substr($field,?,65536) FROM messages_v2 WHERE message_id=?", arrayOf<Any?>(part * 65536 + 1, messageId)).use {
                        check(it.moveToFirst())
                        it.getString(0)
                    }
                    digest.update(content.toByteArray(Charsets.UTF_8))
                    db.execSQL("INSERT OR IGNORE INTO gateway_message_parts VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)", arrayOf<Any?>(conversationKey, logicalId, fence, part, count, "$logicalId:$part", "retained-local:$messageId", "system", "retained_local_$field", content, "retained", null, null, null, owner.getLong(3).toDouble()))
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                db.execSQL("UPDATE gateway_message_parts SET contentSha256=? WHERE conversationKey=? AND logicalMessageId=? AND revision=?", arrayOf<Any?>(hash, conversationKey, logicalId, fence))
            }
            val notice = "This earlier cached message is too large to render inline. Its complete original text and timeline are retained in Saved transcript from Gateway or Jobs."
            val timeline = "[{\"type\":\"TEXT\",\"content\":" + JSONObject.quote(notice) + "}]"
            db.execSQL("UPDATE messages_v2 SET content=CASE WHEN ? THEN ? ELSE content END,timeline=CASE WHEN ? THEN ? ELSE timeline END WHERE message_id=? AND chat_id=? AND ownership_fence=? AND current_run_id IS ?", arrayOf<Any?>(if (largeContent) 1 else 0, notice, if (largeTimeline) 1 else 0, timeline, messageId, owner.getInt(0), fence, owner.getString(2)))
        }
    }

    private fun Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
    private fun Cursor.optional(name: String): String? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    private fun Cursor.nullLong(name: String): Long? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
    private fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)
}
