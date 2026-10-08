package dev.chungjungsoo.gptmobile.data.amazon

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Missing/failed prices are check outcomes, never synthetic zero-price observations. */
@Entity(
    tableName = "amazon_check_events",
    indices = [Index(value = ["ownerProfileUid", "requestId", "marketplace", "asin"], unique = true), Index(value = ["attemptedAt"])]
)
data class AmazonCheckEventEntity(
    @PrimaryKey val id: String,
    val ownerProfileUid: String,
    val requestId: String,
    val marketplace: String,
    val asin: String,
    val attemptedAt: Long,
    val outcome: String
)
