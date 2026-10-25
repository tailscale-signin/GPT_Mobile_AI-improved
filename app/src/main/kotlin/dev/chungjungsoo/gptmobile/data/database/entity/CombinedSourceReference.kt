package dev.chungjungsoo.gptmobile.data.database.entity

import kotlinx.serialization.Serializable

/** Immutable original contributor text; UI reads explicit bounded ranges. */
@Serializable
data class CombinedSourceReference(
    val runId: String,
    val payloadId: String,
    val blockCount: Int,
    val length: Int,
    val digest: String,
    val storageVersion: Int = 1,
    val blocks: List<CombinedPayloadBlockReference> = emptyList(),
    val framePayloads: List<String> = emptyList(),
    val frameIndexes: List<Int> = emptyList()
)

/** Run-scoped content identity; positions belong to the immutable enclosing payload index. */
@Serializable
data class CombinedPayloadBlockReference(val digest: String, val length: Int) {
    val payloadId: String get() = "page:$digest"
}
