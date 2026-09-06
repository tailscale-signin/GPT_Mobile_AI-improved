package dev.melo.gptmobile.improved.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dev.melo.gptmobile.improved.data.database.entity.PlatformV2
import kotlinx.coroutines.flow.Flow

@Dao
interface PlatformV2Dao {

    @Query("SELECT * FROM platforms_v2 ORDER BY name, uid")
    fun getAll(): Flow<List<PlatformV2>>

    @Query("SELECT * FROM platforms_v2 ORDER BY name, uid")
    suspend fun getAllDirect(): List<PlatformV2>

    @Insert
    suspend fun insert(platform: PlatformV2)

    @Upsert
    suspend fun upsert(platform: PlatformV2)

    @Update
    suspend fun update(platform: PlatformV2)

    @Delete
    suspend fun delete(platform: PlatformV2)

    @Query("SELECT * FROM platforms_v2 WHERE uid = :uid")
    suspend fun get(uid: String): PlatformV2?
}
