package dev.chungjungsoo.gptmobile.data.network

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Exact saved provider boundary; restoring a receipt journal never relaxes this digest. */
@Serializable
data class GitHubProviderCheckpoint(val binding: String, val digest: String) {
    init {
        require(binding.matches(Regex("[a-f0-9]{64}")) && digest.matches(Regex("[a-f0-9]{64}")))
    }

    internal fun matches(checkpoint: JsonObject): Boolean =
        checkpoint["state"] == JsonPrimitive("WAITING_PROVIDER_LIMIT") &&
            checkpoint["resume_allowed"] == JsonPrimitive(true) &&
            MessageDigest.getInstance("SHA-256").digest(checkpoint.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } == digest
}
