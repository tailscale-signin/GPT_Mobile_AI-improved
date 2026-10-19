package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Configuration is frozen before preparation and rechecked after queueing, before every attempt. */
internal object ConfiguredDispatchAuthority {
    suspend fun capture(settings: SettingRepository, platform: PlatformV2, chatId: Int? = null): RequestAuthorizationGuard {
        // These attempts only read configuration. Never re-run provider or tool actions.
        repeat(3) { attempt ->
            val guard = captureOnce(settings, platform, chatId)
            try {
                guard.validate()
                return guard
            } catch (stale: IllegalStateException) {
                if (attempt == 2 || stale.message?.startsWith("DISPATCH_POLICY_CHANGED:") != true) throw stale
            }
        }
        error("DISPATCH_POLICY_CHANGED: configuration kept changing; retry after settings settle.")
    }

    private suspend fun captureOnce(settings: SettingRepository, platform: PlatformV2, chatId: Int?): RequestAuthorizationGuard {
        val inherited = currentCoroutineContext()[RequestAuthorizationGuard]
        val matches = settings.fetchPlatformV2s().filter { it.uid == platform.uid }
        check(matches.size <= 1) { "PROFILE_IDENTITY_AMBIGUOUS: multiple profiles use the same identity." }
        val registered = matches.singleOrNull()
        // Ephemeral benchmark profiles have no persisted ID. Persisted profiles cannot disappear.
        check(registered != null || platform.id == 0) { "PROFILE_REMOVED: the selected profile is no longer available." }
        check(registered?.enabled != false && platform.enabled) { "PROFILE_DISABLED: the selected profile is disabled." }
        check(registered == null || (registered.compatibleType == platform.compatibleType && registered.apiUrl == platform.apiUrl && registered.token == platform.token && registered.secretRef == platform.secretRef && registered.providerConnectionUid == platform.providerConnectionUid)) {
            "PROFILE_CONFIGURATION_CHANGED: the queued destination or credentials changed before preparation."
        }
        val capturedProfileRevision = registered?.let(::profileRevision)
        val capturedPolicyRevision = policyRevision(settings.getFeatureSettings(), platform.uid, chatId)
        val revision = researchHash("$capturedProfileRevision|$capturedPolicyRevision|${inherited?.revision}")
        return RequestAuthorizationGuard("configured:$revision") {
            inherited?.validate?.invoke()
            val currentMatches = settings.fetchPlatformV2s().filter { it.uid == platform.uid }
            check(currentMatches.size <= 1) { "PROFILE_IDENTITY_AMBIGUOUS: multiple profiles use the same identity." }
            val current = currentMatches.singleOrNull()
            if (registered != null) {
                check(current != null && current.enabled && profileRevision(current) == capturedProfileRevision) {
                    "PROFILE_CONFIGURATION_CHANGED: the profile, credentials or inference configuration changed; prepare a new request."
                }
            } else {
                check(current == null) { "PROFILE_IDENTITY_CHANGED: an ephemeral identity became a configured profile." }
            }
            check(policyRevision(settings.getFeatureSettings(), platform.uid, chatId) == capturedPolicyRevision) {
                "DISPATCH_POLICY_CHANGED: permissions or budgets changed after preparation; no stale request was sent."
            }
        }
    }

    internal fun profileRevision(profile: PlatformV2): String = researchHash(Json.encodeToString(profile.copy(name = "", labels = "", isFavorite = false)))

    /** Display toggles, colors and wait settings apply independently and never invalidate answer bytes. */
    internal fun policyRevision(features: AppFeatureSettings, uid: String, chatId: Int?): String = researchHash(
        buildJsonObject {
            put("budget", Json.encodeToJsonElement(features.tokenBudget))
            // Quote refresh timestamps and provenance are telemetry, not authority.
            // Keep the actual rates and limits so a cost-changing edit still blocks dispatch.
            put("spend", Json.encodeToJsonElement(features.spendBudget.copy(prices = features.spendBudget.prices.mapValues { (_, price) -> price.copy(checkedAt = 0, source = "") })))
            put("profile", Json.encodeToJsonElement(features.profileBehavior[uid]))
            put("plugins", Json.encodeToJsonElement(features.pluginExecution))
            put("tools", Json.encodeToJsonElement(features.toolPluginStates))
            put("remoteMcp", features.remoteMcpConnections)
            put("location", features.deviceLocationTool)
            put("assist", features.localAssist)
            put("assistPolicy", Json.encodeToJsonElement(features.localAssistPolicy))
            put("delegation", Json.encodeToJsonElement(features.delegation))
            put("conversationDelegation", Json.encodeToJsonElement(chatId?.let { features.conversationDelegation[it] }))
            put("conversationResearch", Json.encodeToJsonElement(chatId?.let { features.conversationResearch[it] }))
            put("reasoning", Json.encodeToJsonElement(chatId?.let { features.conversationReasoning[it] }))
            put("llamaReasoning", Json.encodeToJsonElement(features.llamaReasoningModes[uid]))
        }.toString()
    )
}
