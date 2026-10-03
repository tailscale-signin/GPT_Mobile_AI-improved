package dev.chungjungsoo.gptmobile.data.knowledge

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Persistent FTS4 index maintained by SQLite triggers, including cascaded document deletion. */
@Singleton
class DocumentSearchIndex @Inject constructor(private val database: ChatDatabaseV2) {
    private val mutex = Mutex()
    private var initialized = false

    suspend fun candidates(query: String, chatId: Int, projectId: String?): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = database.openHelper.writableDatabase
            if (!initialized) {
                db.beginTransaction()
                try {
                    val exists = db.query("SELECT name FROM sqlite_master WHERE name = 'knowledge_chunks_fts'").use { it.moveToFirst() }
                    db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_chunks_fts USING fts4(chunk_id, text, notindexed=chunk_id, tokenize=unicode61)")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS knowledge_chunks_fts_insert AFTER INSERT ON knowledge_chunks BEGIN INSERT INTO knowledge_chunks_fts(chunk_id, text) VALUES (new.id, new.text); END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS knowledge_chunks_fts_delete AFTER DELETE ON knowledge_chunks BEGIN DELETE FROM knowledge_chunks_fts WHERE chunk_id = old.id; END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS knowledge_chunks_fts_update AFTER UPDATE ON knowledge_chunks BEGIN DELETE FROM knowledge_chunks_fts WHERE chunk_id = old.id; INSERT INTO knowledge_chunks_fts(chunk_id, text) VALUES (new.id, new.text); END")
                    if (!exists) db.execSQL("INSERT INTO knowledge_chunks_fts(chunk_id, text) SELECT id, text FROM knowledge_chunks")
                    db.setTransactionSuccessful()
                    initialized = true
                } finally {
                    db.endTransaction()
                }
            }
            val match = expression(query) ?: return@withLock emptyList()
            db.query(
                SimpleSQLiteQuery(
                    "SELECT f.chunk_id FROM knowledge_chunks_fts f JOIN knowledge_chunks c ON c.id = f.chunk_id JOIN knowledge_documents d ON d.id = c.documentId WHERE knowledge_chunks_fts MATCH ? AND d.deleted = 0 AND (d.chatId = ? OR d.projectId = ?) ORDER BY d.updatedAt DESC, c.chunkIndex LIMIT 256",
                    arrayOf<Any?>(match, chatId, projectId)
                )
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        }
    }

    companion object {
        fun expression(query: String): String? = Regex("[\\p{L}\\p{N}_]+").findAll(query.lowercase(Locale.ROOT))
            .map { it.value }.filter { it.length > 1 }.distinct().take(12).toList()
            .takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "\"$it\"*" }
    }
}
