package dev.chungjungsoo.gptmobile.data.memory.v2

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Phase 1 foundation only: legacy vault remains authoritative until verified cutover. */
@Entity(tableName = "memory_facts", indices = [Index("scope"), Index("identityKey")])
data class MemoryFactEntity(
    @PrimaryKey val id: String,
    val revision: Long,
    val identityKey: ByteArray,
    val scope: String,
    val enabled: Boolean,
    val state: String,
    val importance: Double,
    val pinned: Boolean,
    val confirmationCount: Int,
    val lastConfirmedAt: Long?,
    val recordedAt: Long,
    val validFrom: Long?,
    val validTo: Long?,
    val payload: ByteArray
)

@Entity(
    tableName = "memory_fact_links",
    foreignKeys = [
        ForeignKey(entity = MemoryFactEntity::class, parentColumns = ["id"], childColumns = ["fromFactId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MemoryFactEntity::class, parentColumns = ["id"], childColumns = ["toFactId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("fromFactId"), Index("toFactId"), Index(value = ["fromFactId", "toFactId", "relation"], unique = true)]
)
data class MemoryFactLinkEntity(
    @PrimaryKey val id: String,
    val fromFactId: String,
    val toFactId: String,
    val relation: String,
    val recordedAt: Long,
    val validFrom: Long?,
    val validTo: Long?
)

@Entity(tableName = "memory_pending_changes", indices = [Index(value = ["operationKey"], unique = true)])
data class MemoryPendingChangeEntity(
    @PrimaryKey val id: String,
    val operationKey: String,
    val expectedFactId: String,
    val expectedRevision: Long,
    val payload: ByteArray,
    val state: String,
    val createdAt: Long,
    val expiresAt: Long?
)

@Entity(tableName = "memory_store_state")
data class MemoryStoreStateEntity(
    @PrimaryKey val id: Int,
    val formatVersion: Int,
    val generation: Long,
    val cutoverStatus: String,
    val payload: ByteArray,
    val importDigest: ByteArray?,
    val importedCount: Int
)
