package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Shared by device authorization and project-hosted code exchange. */
@Serializable
internal data class GitHubOAuthCredential(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val refreshExpiresAt: Long? = null
) {
    override fun toString() = "GitHub credential (redacted)"
}

internal fun decodeGitHubOAuthCredential(response: JsonObject, started: Long): GitHubOAuthCredential {
    fun text(key: String) = (response[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun valid(value: String) = value.length in 1..4096 && value.all { it.code in 33..126 }
    check(text("token_type").equals("bearer", true)) { "Unexpected GitHub credential type." }
    val access = text("access_token")
    check(valid(access)) { "GitHub did not return a usable credential." }
    val refresh = text("refresh_token").takeIf(String::isNotEmpty)
    check(refresh == null || valid(refresh)) { "GitHub did not return a usable refresh credential." }
    fun expiration(key: String): Long? = response[key]?.let {
        val seconds = (it as? JsonPrimitive)?.longOrNull?.takeIf { seconds -> seconds in 1..366 * 86400 } ?: error("GitHub returned an invalid credential expiration.")
        started + seconds * 1000
    }
    return GitHubOAuthCredential(access, refresh, expiration("expires_in"), expiration("refresh_token_expires_in"))
}
