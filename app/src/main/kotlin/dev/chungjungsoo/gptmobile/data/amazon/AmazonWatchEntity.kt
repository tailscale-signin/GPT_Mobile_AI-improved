package dev.chungjungsoo.gptmobile.data.amazon

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Manual, explicitly saved target. This milestone grants no background work or notifications. */
@Entity(tableName = "amazon_watches", indices = [Index(value = ["ownerProfileUid", "state"])])
data class AmazonWatchEntity(
    @PrimaryKey val id: String,
    val ownerProfileUid: String,
    val marketplace: String,
    val asin: String,
    val targetAmount: String,
    val currency: String,
    val state: String = "AWAITING_MATCHING_PRICE",
    val generation: Int = 1,
    val createdAt: Long,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastOutcome: String? = null,
    val conditionPolicy: String = "new",
    val variantPolicy: String = "exact_asin"
)
