package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ResearchUserLimits(
    val maxRequests: Long? = null,
    val maxDownloadedBytes: Long? = null,
    val maxActiveMillis: Long? = null
) {
    fun validate() {
        require(listOfNotNull(maxRequests, maxDownloadedBytes, maxActiveMillis).all { it > 0 }) { "User limits must be positive" }
    }
}
