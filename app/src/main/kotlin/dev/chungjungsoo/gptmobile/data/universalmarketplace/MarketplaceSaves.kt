package dev.chungjungsoo.gptmobile.data.universalmarketplace

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
@Entity(tableName = "marketplace_saved_searches")
data class MarketplaceSavedSearch(@PrimaryKey val id: String, val title: String, val requestJson: String, val createdAt: Long)

@Serializable
@Entity(tableName = "marketplace_bookmarks")
data class MarketplaceBookmark(@PrimaryKey val id: String, val provider: String, val url: String, val note: String, val createdAt: Long)

@Dao
interface MarketplaceSavesDao {
    @Query("SELECT * FROM marketplace_saved_searches ORDER BY createdAt DESC, id")
    fun observeSearches(): Flow<List<MarketplaceSavedSearch>>

    @Query("SELECT * FROM marketplace_bookmarks ORDER BY createdAt DESC, id")
    fun observeBookmarks(): Flow<List<MarketplaceBookmark>>

    @Query("SELECT * FROM marketplace_saved_searches ORDER BY createdAt DESC, id")
    suspend fun searches(): List<MarketplaceSavedSearch>

    @Query("SELECT * FROM marketplace_bookmarks ORDER BY createdAt DESC, id")
    suspend fun bookmarks(): List<MarketplaceBookmark>

    @Upsert suspend fun save(value: MarketplaceSavedSearch)

    @Upsert suspend fun save(value: MarketplaceBookmark)

    @Query("DELETE FROM marketplace_saved_searches WHERE id = :id")
    suspend fun deleteSearch(id: String)

    @Query("DELETE FROM marketplace_bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: String)

    @Query("DELETE FROM marketplace_saved_searches")
    suspend fun clearSearches()

    @Query("DELETE FROM marketplace_bookmarks")
    suspend fun clearBookmarks()
}

@Singleton
class MarketplaceSavesRepository @Inject constructor(private val database: ChatDatabaseV2) {
    private val dao = database.marketplaceSavesDao()
    val searches = dao.observeSearches()
    val bookmarks = dao.observeBookmarks()
    suspend fun saveSearch(request: MarketplaceSearchRequest) {
        val normalized = request.normalized()
        dao.save(MarketplaceSavedSearch(normalized.fingerprint(), normalized.query, MarketplaceContract.json.encodeToString(normalized), System.currentTimeMillis()))
    }
    suspend fun saveBookmark(provider: String, url: String, note: String) {
        require(MarketplaceContract.safeUrl(provider, url)) { "Choose an HTTPS URL on the selected marketplace." }
        require(note.length <= 2000 && note.none { it.isISOControl() && it != '\n' }) { "Notes must be at most 2000 characters." }
        dao.save(MarketplaceBookmark(UUID.nameUUIDFromBytes("$provider\u0000$url".toByteArray(Charsets.UTF_8)).toString(), provider, url, note, System.currentTimeMillis()))
    }
    suspend fun deleteSearch(id: String) = dao.deleteSearch(id)
    suspend fun deleteBookmark(id: String) = dao.deleteBookmark(id)
    suspend fun clear() = database.withTransaction {
        dao.clearSearches()
        dao.clearBookmarks()
    }
}
