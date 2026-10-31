package dev.chungjungsoo.gptmobile.data.backup

import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import java.util.Base64
import kotlinx.serialization.json.Json

/** Access/refresh credentials travel only in explicitly encrypted credential exports. */
internal object GitHubBackupCredentials {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun requireRevalidation(values: Map<String, String>): Map<String, String> = values.mapValues { (reference, encoded) ->
        if (!reference.startsWith("github-oauth-session-")) return@mapValues encoded
        val bytes = Base64.getDecoder().decode(encoded)
        try {
            val session = json.decodeFromString<GitHubPublicAuthCoordinator.Session>(bytes.decodeToString())
            // Preserve interrupted refresh status; restoring must not replay a token rotation.
            Base64.getEncoder().encodeToString(json.encodeToString(GitHubPublicAuthCoordinator.Session.serializer(), session.copy(verifyAfterRestore = true)).encodeToByteArray())
        } finally {
            bytes.fill(0)
        }
    }
}
