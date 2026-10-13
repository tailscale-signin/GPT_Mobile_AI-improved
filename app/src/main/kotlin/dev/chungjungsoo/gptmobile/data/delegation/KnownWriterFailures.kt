package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A known denial is a blocker; absence of a denial means unknown, never proven ready. */
class KnownWriterFailures(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private fun file(profile: PlatformV2) = File(directory, researchHash("${profile.uid}|${profile.compatibleType}|${profile.apiUrl}|${profile.model}|${profile.providerConnectionUid}|${profile.secretRef}|${profile.token}|${profile.openRouterRouting}") + ".json")

    @Synchronized fun blocked(profile: PlatformV2): ProviderFailure? = runCatching {
        file(profile).takeIf { it.isFile }?.let { stored ->
            val failure = Json.decodeFromString<ProviderFailure>(stored.readText())
            if (failure.code == "model_load_failed" && now() - stored.lastModified() >= (failure.retryAfterMillis ?: 30_000L)) null else failure
        }
    }.getOrNull()

    @Synchronized fun recheck(profile: PlatformV2) {
        file(profile).delete()
    }

    @Synchronized fun observe(profile: PlatformV2, message: String) {
        val status = Regex("(?i)HTTP\\s+(401|402|403|500|503)\\b").find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return
        val loadFailed = profile.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA && status in setOf(500, 503) && message.contains("failed to load", ignoreCase = true)
        if (status >= 500 && !loadFailed) return
        val failure = if (loadFailed) ProviderFailure(status, dev.chungjungsoo.gptmobile.data.network.error.ProviderFailureKind.UNAVAILABLE, "model_load_failed", 30_000L) else ProviderFailure.parse(status)
        if (!directory.mkdirs() && !directory.isDirectory) return
        val target = file(profile)
        val temporary = File.createTempFile("writer-", ".tmp", directory)
        try {
            temporary.writeText(Json.encodeToString(failure))
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            target.setLastModified(now())
        } finally {
            temporary.delete()
        }
    }
}
