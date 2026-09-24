package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.util.UUID

@Entity(
    tableName = "provider_connections",
    indices = [
        Index(value = ["compatible_type"]),
        Index(value = ["compatible_type", "api_url"])
    ]
)
data class ProviderConnection(
    @PrimaryKey
    @ColumnInfo(name = "connection_uid")
    val uid: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "compatible_type")
    val compatibleType: ClientType,

    @ColumnInfo(name = "api_url")
    val apiUrl: String = "",

    @ColumnInfo(name = "secret_ref")
    val secretRef: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000
) {
    val hasCredential: Boolean
        get() = !secretRef.isNullOrBlank()
}
