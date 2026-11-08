package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "gateway_engineering_bindings", indices = [Index(value = ["chatId"]), Index(value = ["jobId"])])
data class GatewayEngineeringBindingRecord(
    @PrimaryKey val requestId: String,
    val chatId: Int,
    val jobId: String?,
    val binding: String,
    val updatedAtMillis: Long
)
