package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity

@Entity(tableName = "gateway_engineering_events", primaryKeys = ["requestId", "revision"])
data class GatewayEngineeringEventRecord(
    val requestId: String,
    val revision: Int,
    val state: String,
    val message: String,
    val createdAt: Double
)
