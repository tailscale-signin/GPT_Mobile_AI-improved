package dev.chungjungsoo.gptmobile.data.memory

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.VectorDistanceType

/** Rebuildable index: plaintext facts and source messages stay in the encrypted vault. */
@Entity
data class MemoryVector(
    @Id var id: Long = 0,
    @Index var factId: String = "",
    @Index var scope: String = "personal",
    var fingerprint: String = "",
    @HnswIndex(dimensions = 100, distanceType = VectorDistanceType.COSINE, vectorCacheHintSizeKB = 16384)
    var embedding: FloatArray = floatArrayOf()
)
