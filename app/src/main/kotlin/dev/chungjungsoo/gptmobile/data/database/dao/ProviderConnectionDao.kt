package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderConnectionDao {
    @Query("SELECT * FROM provider_connections ORDER BY compatible_type, name, created_at")
    suspend fun getConnections(): List<ProviderConnection>

    @Query("SELECT * FROM provider_connections ORDER BY compatible_type, name, created_at")
    fun observeConnections(): Flow<List<ProviderConnection>>

    @Query("SELECT * FROM provider_connections WHERE connection_uid = :uid LIMIT 1")
    suspend fun getConnection(uid: String): ProviderConnection?

    @Query("SELECT * FROM provider_connections WHERE connection_uid = :uid LIMIT 1")
    fun observeConnection(uid: String): Flow<ProviderConnection?>

    @Upsert
    suspend fun upsert(connection: ProviderConnection)

    @Delete
    suspend fun delete(connection: ProviderConnection)

    @Query("SELECT COUNT(*) FROM platform_v2 WHERE provider_connection_uid = :connectionUid")
    suspend fun profileCount(connectionUid: String): Int
}
