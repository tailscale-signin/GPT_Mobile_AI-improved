package dev.chungjungsoo.gptmobile.data.amazon

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Installation-local operational state. Never exported or replaced by a backup restore. */
@Entity(tableName = "amazon_request_budget")
data class AmazonBudgetEntity(
    @PrimaryKey val id: Int = 1,
    val stateJson: String
)
