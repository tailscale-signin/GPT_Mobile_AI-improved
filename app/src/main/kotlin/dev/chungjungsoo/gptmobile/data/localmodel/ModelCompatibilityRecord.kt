package dev.chungjungsoo.gptmobile.data.localmodel

import kotlinx.serialization.Serializable

/** A release-reviewed qualification applies only to the exact bytes and device stack. */
@Serializable
data class ModelCompatibilityIdentity(
    val artifactSha256: String,
    val runtimeVersion: String,
    val dispatchSha256: String,
    val soc: String,
    val firmwareFingerprint: String
) {
    val complete: Boolean get() = listOf(artifactSha256, dispatchSha256).all { it.matches(Regex("[a-f0-9]{64}")) } &&
        runtimeVersion.isNotBlank() &&
        soc.isNotBlank() &&
        firmwareFingerprint.isNotBlank()
}

@Serializable
data class ModelCompatibilityRecord(
    val id: String,
    val revision: Int,
    val state: String,
    val affectedFileNames: Set<String> = emptySet(),
    val identity: ModelCompatibilityIdentity? = null,
    val evidence: String,
    val correctiveAction: String
) {
    fun qualifies(actual: ModelCompatibilityIdentity?): Boolean = state == "QUALIFIED" &&
        actual?.complete == true &&
        identity == actual
}
