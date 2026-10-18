package dev.chungjungsoo.gptmobile.data.chat

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplyPublicationDao {
    @Query("SELECT * FROM reply_publications WHERE committedRevision > acknowledgedRevision ORDER BY publicationSequence DESC")
    fun observeUnread(): Flow<List<ReplyPublication>>

    @Query("SELECT * FROM reply_publications ORDER BY publicationSequence")
    suspend fun all(): List<ReplyPublication>

    @Upsert suspend fun restore(rows: List<ReplyPublication>)

    @Query("UPDATE reply_publications SET acknowledgedRevision = :revision WHERE logicalReplyKey = :key AND committedRevision = :revision AND contentDigest = :digest")
    suspend fun acknowledge(key: String, revision: Long, digest: String): Int
}
