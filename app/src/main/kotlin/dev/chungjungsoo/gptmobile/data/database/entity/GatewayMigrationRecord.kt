package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gateway_migrations")
data class GatewayMigrationRecord(@PrimaryKey val key: String, val checksum: String, val importedAtMillis: Long)
