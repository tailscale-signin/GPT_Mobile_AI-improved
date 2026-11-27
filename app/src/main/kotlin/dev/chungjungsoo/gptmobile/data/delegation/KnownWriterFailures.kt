package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.error.ProviderBudgetEvidence
import dev.chungjungsoo.gptmobile.data.network.error.ProviderBudgetKind
import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure
import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailureKind
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A known denial is a blocker; absence of a denial means unknown, never proven ready. */
class KnownWriterFailures(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private fun file(profile: PlatformV2) = File(directory, researchHash("${profile.uid}|${profile.compatibleType}|${profile.apiUrl}|${profile.model}|${profile.providerConnectionUid}|${profile.secretRef}|${profile.token}|${profile.openRouterRouting}") + ".json")

    @Synchronized fun blocked(profile: PlatformV2): ProviderFailure? = runCatching {
        file(profile).takeIf { it.isFile }?.let { stored ->
            val failure = Json.decodeFromString<ProviderFailure>(stored.readText())
            val lifetime = when {
                failure.budget?.kind == ProviderBudgetKind.PROVIDER_QUOTA -> 300_000L
                failure.code == "model_load_failed" -> failure.retryAfterMillis ?: 30_000L
                profile.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.FREE -> 60_000L
                else -> Long.MAX_VALUE
            }
            val age = now() - stored.lastModified()
            if (age < 0 || age >= lifetime) {
                stored.delete()
                null
            } else {
                failure
            }
        }
    }.getOrNull()

    @Synchronized fun recheck(profile: PlatformV2) {
        file(profile).delete()
    }

    @Synchronized fun observe(profile: PlatformV2, event: ProviderEvent.Failed) {
        val details = event.failureDetails
        if (event.errorCode != "BILLING_UNAVAILABLE" || details?.get("provider_budget_kind") != JsonPrimitive(ProviderBudgetKind.PROVIDER_QUOTA.name)) {
            observe(profile, event.message)
            return
        }
        val status = (details["http_status"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 100..599 } ?: return
        if (details["requires_reconciliation"] == JsonPrimitive(true) || details["provider_failure_kind"] != JsonPrimitive(ProviderFailureKind.BUDGET.name)) return
        persist(profile, ProviderFailure(status, ProviderFailureKind.BUDGET, "insufficient_quota", budget = ProviderBudgetEvidence(ProviderBudgetKind.PROVIDER_QUOTA)))
    }

    @Synchronized fun observe(profile: PlatformV2, message: String) {
        val status = Regex("(?i)HTTP\\s+(401|402|403|500|503)\\b").find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return
        val loadFailed = profile.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA && status in setOf(500, 503) && message.contains("failed to load", ignoreCase = true)
        if (status >= 500 && !loadFailed) return
        val failure = if (loadFailed) ProviderFailure(status, dev.chungjungsoo.gptmobile.data.network.error.ProviderFailureKind.UNAVAILABLE, "model_load_failed", 30_000L) else ProviderFailure.parse(status)
        persist(profile, failure)
    }

    private fun persist(profile: PlatformV2, failure: ProviderFailure) {
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
