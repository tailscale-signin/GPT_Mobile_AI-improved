package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CombinedManifestReference(
    val id: String,
    val runId: String,
    val revisionId: String,
    val bodyLength: Int,
    val blockCount: Int,
    val bodyDigest: String,
    @kotlinx.serialization.Transient val materialized: Boolean = false
)

/** One immutable document revision; the message row is its fenced publication pointer. */
@Serializable
@Entity(
    tableName = "combined_final_manifests",
    foreignKeys = [
        ForeignKey(entity = AgentRun::class, parentColumns = ["run_id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MessageV2::class, parentColumns = ["message_id"], childColumns = ["finalMessageId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("runId"), Index("finalMessageId")]
)
data class CombinedFinalManifest(
    @PrimaryKey val id: String,
    val runId: String,
    val finalMessageId: Int,
    val revisionId: String,
    val acceptedTurnRevision: String,
    val ownershipFence: Long,
    val bodyPayload: String,
    val metadataPayload: String,
    val blockMetadataPayload: String,
    val bodyLength: Int,
    val blockCount: Int,
    val bodyDigest: String,
    val provenanceDigest: String,
    val metadataDigest: String,
    val completeness: String,
    val committedAt: Long
) {
    fun reference() = CombinedManifestReference(id, runId, revisionId, bodyLength, blockCount, bodyDigest)
}

class CombinedManifestReferenceConverter {
    @TypeConverter
    fun encode(value: CombinedManifestReference?): String? = value?.let { Json.encodeToString(it) }

    @TypeConverter
    fun decode(value: String?): CombinedManifestReference? = value?.let { Json.decodeFromString(it) }
}
