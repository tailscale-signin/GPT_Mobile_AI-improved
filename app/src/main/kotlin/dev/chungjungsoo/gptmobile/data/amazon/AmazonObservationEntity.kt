package dev.chungjungsoo.gptmobile.data.amazon

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A local listing observation. Unknown offer context is never treated as a comparable offer. */
@Entity(
    tableName = "amazon_observations",
    indices = [
        Index(value = ["ownerProfileUid", "requestId", "seriesKey"], unique = true),
        Index(value = ["ownerProfileUid", "marketplace", "asin", "observedAt"]),
        Index(value = ["seriesKey", "observedAt"])
    ]
)
data class AmazonObservationEntity(
    @PrimaryKey val id: String,
    val ownerProfileUid: String,
    val requestId: String,
    val seriesKey: String,
    val marketplace: String,
    val asin: String,
    val title: String,
    val amount: String,
    val currency: String,
    val sourceType: String,
    val observedAt: Long,
    val priceBasis: String = "base_item",
    val contextQuality: String = "incomplete_listing"
)
