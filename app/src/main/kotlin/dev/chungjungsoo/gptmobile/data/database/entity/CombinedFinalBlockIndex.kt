package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import kotlinx.serialization.Serializable

/** Small per-block source-map reference, loaded together with a visible text page. */
@Serializable
@Entity(
    tableName = "combined_final_block_index",
    primaryKeys = ["manifestId", "sequence"],
    foreignKeys = [ForeignKey(entity = CombinedFinalManifest::class, parentColumns = ["id"], childColumns = ["manifestId"], onDelete = ForeignKey.CASCADE)]
)
data class CombinedFinalBlockIndex(
    val manifestId: String,
    val sequence: Int,
    val start: Int,
    val end: Int,
    val textDigest: String,
    val attributionPayload: String,
    val digest: String
)
