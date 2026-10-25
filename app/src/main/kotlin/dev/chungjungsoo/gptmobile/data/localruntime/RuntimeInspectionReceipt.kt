package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.serialization.Serializable

/** Preparation measurements only. A metadata/cache hit is never an accelerator qualification. */
@Serializable
data class RuntimeInspectionReceipt(
    val inspectionId: String,
    val runtimeIdentity: String,
    val deviceIdentity: String,
    val firmwareDigest: String,
    val backend: String,
    val configurationDigest: String,
    val startedAtElapsedMs: Long,
    val observedAtElapsedMs: Long,
    val outcome: String,
    val artifactDigest: String? = null,
    val cacheKeyDigest: String? = null,
    val strongFileIdentityAvailable: Boolean? = null,
    val metadataCacheDisposition: String = "not_reached",
    val digestCacheDisposition: String = "not_reached",
    val fileResolutionMs: Long? = null,
    val fileValidationMs: Long? = null,
    val digestValidationMs: Long? = null,
    val digestLockWaitMs: Long? = null,
    val modelBytesHashed: Long? = null,
    val metadataCacheWaitMs: Long? = null,
    val opaqueMetadataMs: Long? = null,
    val tokenizerMetadataMs: Long? = null,
    val tokenizerTimingStatus: String = "not_separately_exposed_by_runtime",
    val compatibilityValidationMs: Long? = null,
    val journalMs: Long? = null,
    val serviceBindingMs: Long? = null,
    val serviceBindingDisposition: String? = null,
    val transportRoundTripMs: Long? = null,
    val admissionLeaseWaitMs: Long? = null,
    val totalMs: Long? = null,
    val failureClass: String? = null,
    val runtimeRequestId: String? = null,
    val workerGenerationId: String? = null,
    val schemaVersion: Int = 1
)
