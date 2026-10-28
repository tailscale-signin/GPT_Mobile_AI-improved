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
        dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
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
        check(dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.fromApiUrl(platform.apiUrl)?.isRetired != true) { "PROVIDER_RETIRED: Choose another profile. Saved conversations remain available." }
        check(!settings.providerActivationRequired(platform)) { "PROVIDER_ACTIVATION_REQUIRED: Review this provider's data policy in AI profile settings before sending." }
        val privacyRevision = settings.providerPermissionRevision(platform)
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
        val capturedFeatures = settings.getFeatureSettings()
        val capturedFields = policyFields(capturedFeatures, platform.uid, chatId)
        val capturedPolicyRevision = policyRevision(capturedFeatures, platform.uid, chatId)
        val revision = researchHash("$capturedProfileRevision|$capturedPolicyRevision|${inherited?.revision}")
        val authority = capturedProfileRevision?.let {
            CapturedProviderAuthority(platform.uid, platform.model, it, capturedPolicyRevision, privacyRevision, CapturedProviderAuthority.routeRevision(platform), chatId, listOfNotNull(inherited?.authority), acceptedTokenBudget = capturedFeatures.tokenBudget, acceptedProfileOutputLimit = registered?.maxTokens, profileWithoutOutputLimitRevision = registered?.let(::profileWithoutOutputLimitRevision))
        }
        return RequestAuthorizationGuard("configured:$revision", authority) {
            inherited?.validate?.invoke()
            currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.permissions.PersonalDataContext]?.validateModel(platform)
            check(!settings.providerActivationRequired(platform) && settings.providerPermissionRevision(platform) == privacyRevision) { "DISPATCH_POLICY_CHANGED: Data permissions changed; prepare a new request." }
            val currentMatches = settings.fetchPlatformV2s().filter { it.uid == platform.uid }
            check(currentMatches.size <= 1) { "PROFILE_IDENTITY_AMBIGUOUS: multiple profiles use the same identity." }
            val current = currentMatches.singleOrNull()
            if (registered != null) {
                if (current == null) throw DispatchPolicyChangedException(listOf("profile.removed"))
                if (!current.enabled) throw DispatchPolicyChangedException(listOf("profile.enabled"))
                if (profileRevision(current) != capturedProfileRevision && !allowsAcceptedProfile(registered, current)) {
                    val before = Json.encodeToJsonElement(registered) as kotlinx.serialization.json.JsonObject
                    val after = Json.encodeToJsonElement(current) as kotlinx.serialization.json.JsonObject
                    throw DispatchPolicyChangedException((before.keys + after.keys).filter { before[it] != after[it] }.sorted().map { "profile.$it" })
                }
            } else {
                check(current == null) { "PROFILE_IDENTITY_CHANGED: an ephemeral identity became a configured profile." }
            }
            val currentFeatures = settings.getFeatureSettings()
            val currentFields = policyFields(currentFeatures, platform.uid, chatId)
            val changed = (capturedFields.keys + currentFields.keys).filter { capturedFields[it] != currentFields[it] }.filterNot {
                it == "budget" && allowsAcceptedTokenPlan(capturedFeatures.tokenBudget, currentFeatures.tokenBudget, platform.uid)
            }.sorted()
            if (changed.isNotEmpty()) throw DispatchPolicyChangedException(changed)
        }
    }

    internal fun profileRevision(profile: PlatformV2): String = researchHash(Json.encodeToString(profile.copy(name = "", labels = "", isFavorite = false)))

    internal fun outputLimit(value: Int?): Int = value?.takeIf { it > 0 } ?: Int.MAX_VALUE
    internal fun profileWithoutOutputLimitRevision(profile: PlatformV2): String = profileRevision(profile.copy(maxTokens = null))
    internal fun allowsAcceptedProfile(before: PlatformV2, after: PlatformV2): Boolean =
        outputLimit(after.maxTokens) >= outputLimit(before.maxTokens) && profileWithoutOutputLimitRevision(before) == profileWithoutOutputLimitRevision(after)

    /** Increasing limits preserves an already smaller accepted allocation; reductions require revalidation. */
    internal fun allowsAcceptedTokenPlan(before: TokenBudgetSettings, after: TokenBudgetSettings, uid: String): Boolean {
        val previous = before.normalized()
        val current = after.normalized()
        fun cap(value: Int) = if (value == 0) Int.MAX_VALUE else value
        return current.contextTokens >= previous.contextTokens &&
            current.totalRunTokens >= previous.totalRunTokens &&
            cap(current.outputTokens) >= cap(previous.outputTokens) &&
            cap(current.generationTimeoutMinutes) >= cap(previous.generationTimeoutMinutes) &&
            cap(current.responseCharacterLimit) >= cap(previous.responseCharacterLimit) &&
            (current.profileContextCeilings[uid] ?: Int.MAX_VALUE) >= (previous.profileContextCeilings[uid] ?: Int.MAX_VALUE) &&
            previous.copy(
                contextTokens = current.contextTokens,
                totalRunTokens = current.totalRunTokens,
                outputTokens = current.outputTokens,
                generationTimeoutMinutes = current.generationTimeoutMinutes,
                responseCharacterLimit = current.responseCharacterLimit,
                profileContextCeilings = current.profileContextCeilings
            ) == current
    }

    /** Display toggles, colors and wait settings apply independently and never invalidate answer bytes. */
    internal fun policyRevision(features: AppFeatureSettings, uid: String, chatId: Int?): String = researchHash(policyFields(features, uid, chatId).toString())

    /** Scope accounting to this writer; another contributor's quote/ceiling cannot revoke it. */
    internal fun legacyPolicyRevision(features: AppFeatureSettings, uid: String, chatId: Int?): String = researchHash(policyFields(features, uid, chatId, scopeProfile = false).toString())

    internal fun policyFields(features: AppFeatureSettings, uid: String, chatId: Int?, scopeProfile: Boolean = true) =
        buildJsonObject {
            put("budget", Json.encodeToJsonElement(features.tokenBudget.copy(profileContextCeilings = features.tokenBudget.profileContextCeilings.filterKeys { !scopeProfile || it == uid })))
            // Quote refresh timestamps and provenance are telemetry, not authority.
            // Keep the actual rates and limits so a cost-changing edit still blocks dispatch.
            put("spend", Json.encodeToJsonElement(features.spendBudget.copy(prices = features.spendBudget.prices.filterKeys { !scopeProfile || it == uid && features.spendBudget.enforced }.mapValues { (_, price) -> price.copy(checkedAt = 0, source = "") })))
            put("profile", Json.encodeToJsonElement(features.profileBehavior[uid]))
            put("plugins", Json.encodeToJsonElement(features.pluginExecution))
            put("tools", Json.encodeToJsonElement(features.toolPluginStates))
            put("remoteMcp", features.remoteMcpConnections)
            put("location", features.deviceLocationTool)
            put("assist", features.localAssist)
            put("assistPolicy", Json.encodeToJsonElement(features.localAssistPolicy))
            put("delegation", Json.encodeToJsonElement(features.delegation))
            put("conversationDelegation", Json.encodeToJsonElement(chatId?.let { features.conversationDelegation[it] }))
            put("conversationResearch", Json.encodeToJsonElement(chatId?.let { features.conversationResearch[it]?.copy(crawlerProfileUid = null) }))
            put("researchEnabled", features.researchFeature.enabled)
            put("researchReviewer", features.researchFeature.reviewerProfileUid)
            put("reasoning", Json.encodeToJsonElement(chatId?.let { features.conversationReasoning[it] }))
            put("engineering", Json.encodeToJsonElement(features.engineeringProfiles[uid]))
            put("llamaReasoning", Json.encodeToJsonElement(features.llamaReasoningModes[uid]))
        }
}

/** Safe field names only; never include settings values, prompts or credentials. */
internal class DispatchPolicyChangedException(val fields: List<String>) :
    IllegalStateException(
        "DISPATCH_POLICY_CHANGED: changed fields=${fields.joinToString(",")}. " +
            "Resume final synthesis to revalidate the retained contributions; no stale request was sent."
    )
