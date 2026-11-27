package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
                // A revoked captured parent cannot be resnapshotted as mutable child configuration.
                currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
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
        chatId?.let { capturedFeatures.conversationGitHub[it] }?.let { github ->
            if (!(github.mainProfileUid == platform.uid && registered != null && registered.model == platform.model)) {
                throw GitHubProfileSelectionRejected(
                    profileMatches = github.mainProfileUid == platform.uid,
                    registeredProfilePresent = registered != null,
                    modelMatches = registered?.let { it.model == platform.model },
                    requestedProfileHash = researchHash(platform.uid),
                    requestedModelHash = researchHash(platform.model),
                    selectedProfileHash = researchHash(github.mainProfileUid),
                    registeredModelHash = registered?.model?.let(::researchHash)
                )
            }
        }
        val capturedFields = policyFields(capturedFeatures, platform.uid, chatId)
        val capturedPolicyRevision = policyRevision(capturedFeatures, platform.uid, chatId)
        val revision = researchHash("$capturedProfileRevision|$capturedPolicyRevision|${inherited?.revision}")
        val authority = capturedProfileRevision?.let {
            CapturedProviderAuthority(
                platform.uid, platform.model, it, capturedPolicyRevision, privacyRevision,
                CapturedProviderAuthority.routeRevision(platform), chatId, listOfNotNull(inherited?.authority),
                acceptedTokenBudget = capturedFeatures.tokenBudget,
                acceptedProfileOutputLimit = registered?.maxTokens,
                profileWithoutOutputLimitRevision = registered?.let(::profileWithoutOutputLimitRevision),
                acceptedGitHubIntelligenceLevel = chatId?.let { capturedFeatures.conversationGitHub[it] }
                    ?.takeIf { it.mainProfileUid == platform.uid }
                    ?.let { (capturedFeatures.githubProfileOverrides[platform.uid] ?: GitHubChatProfileOverride.newProfile).intelligenceLevel }
            )
        }
        return RequestAuthorizationGuard("configured:$revision", authority) {
            inherited?.validate?.invoke()
            currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.permissions.PersonalDataContext]?.validateModel(platform)
            if (settings.providerActivationRequired(platform) || settings.providerPermissionRevision(platform) != privacyRevision) {
                throw DispatchPolicyChangedException(listOf("providerPermissions"))
            }
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
                it == "budget" &&
                    allowsAcceptedTokenPlan(capturedFeatures.tokenBudget, currentFeatures.tokenBudget, platform.uid) ||
                    it == "githubProfileOverride" &&
                    allowsAcceptedIntelligenceLevel(capturedFields, currentFields)
            }.sorted()
            if (changed.isNotEmpty()) {
                val details = githubOverrideChanges(capturedFields, currentFields) + profileBehaviorChanges(capturedFields, currentFields)
                throw DispatchPolicyChangedException(changed + details, gatewaySelectionChange(capturedFields, currentFields))
            }
            // Child settings reads can suspend after the captured parent passed.
            inherited?.validate?.invoke()
        }
    }

    /** Fixed subfield names diagnose a policy stop without exporting either setting value. */
    private fun allowsAcceptedIntelligenceLevel(before: JsonObject, after: JsonObject): Boolean {
        val previous = before["githubProfileOverride"] as? JsonObject ?: return false
        val current = after["githubProfileOverride"] as? JsonObject ?: return false
        if (previous - "intelligenceLevel" != current - "intelligenceLevel") return false
        fun level(value: JsonObject): dev.chungjungsoo.gptmobile.data.github.GitHubIntelligenceLevel? {
            val encoded = value["intelligenceLevel"] ?: return GitHubChatProfileOverride.newProfile.intelligenceLevel
            return dev.chungjungsoo.gptmobile.data.github.GitHubIntelligenceLevel.entries.firstOrNull { encoded == JsonPrimitive(it.name) }
        }
        val accepted = level(previous) ?: return false
        val configured = level(current) ?: return false
        return accepted < configured
    }

    internal fun githubOverrideChanges(before: JsonObject, after: JsonObject): List<String> {
        val previous = (before["githubProfileOverride"] as? JsonObject).orEmpty()
        val current = (after["githubProfileOverride"] as? JsonObject).orEmpty()
        return listOf("webSearch", "githubTools", "engineeringMode", "maximumContext", "contextVariant", "engineeringMemory", "allowCloudEngineeringMemory", "workflowPreset", "quickWorkflows", "engineeringGatewayProfileUid", "executionBatchMode", "sampling", "intelligenceLevel", "nextActionPolicy", "evidenceElaboration", "recoveryConsultConsent", "engineeringTeamConsent", "recoveryExecutionConsent", "primaryPatchReviewConsent")
            .filter { previous[it] != current[it] }.map { "githubProfileOverride.$it" }
    }

    private fun profileBehaviorChanges(before: JsonObject, after: JsonObject): List<String> {
        val previous = before["profile"] as? JsonObject ?: return emptyList()
        val current = after["profile"] as? JsonObject ?: return emptyList()
        return listOf("delegationEnabled", "delegationCloudPermission", "crawlersEnabled", "crawlerToolIds", "maxCrawlPages", "toolPluginStates", "researchDepthOverride", "researchEnabledOverride", "academicSourcesAllowed")
            .filter { previous[it] != current[it] }.map { "profileBehavior.$it" }
    }

    internal fun gatewaySelectionChange(before: JsonObject, after: JsonObject): String? {
        if (before["githubProfileSelection"] != after["githubProfileSelection"]) return null
        val previous = before["githubProfileOverride"] as? JsonObject ?: return null
        val current = after["githubProfileOverride"] as? JsonObject ?: return null
        fun selected(fields: JsonObject) = (fields["engineeringGatewayProfileUid"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val prior = selected(previous)
        val next = selected(current)
        return when {
            prior == next -> null
            prior == null -> "SELECTED"
            next == null -> "CLEARED"
            else -> "REPLACED"
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

    internal fun matchesPolicyRevision(revision: String, features: AppFeatureSettings, uid: String, chatId: Int?, scopeProfile: Boolean = true): Boolean {
        val fields = policyFields(features, uid, chatId, scopeProfile)
        val legacyTools = ToolPluginPolicyProjection.project(features.toolPluginStates + (ToolPluginId.VOICE_CHAT to true), ToolPluginId.optInServices)
        fun matches(candidate: JsonObject): Boolean = researchHash(candidate.toString()) == revision ||
            researchHash(JsonObject(candidate + ("tools" to legacyTools)).toString()) == revision
        if (matches(fields)) return true
        // Earlier checkpoints serialized an unset profile as null and kept collection insertion order.
        val rawProfile = Json.encodeToJsonElement(features.profileBehavior[uid])
        val previous = JsonObject(fields + ("profile" to rawProfile))
        if (matches(previous)) return true
        if ((features.profileBehavior[uid] ?: ProfileBehaviorSettings()) == ProfileBehaviorSettings() &&
            matches(JsonObject(fields + ("profile" to kotlinx.serialization.json.JsonNull)))
        ) {
            return true
        }
        // Waits captured before canonicalization included prompt shortcuts and plugin insertion order.
        val legacy = policyFields(features, uid, chatId, scopeProfile, legacy = true)
        return legacy.toString() != fields.toString() && matches(legacy)
    }

    internal fun policyFields(features: AppFeatureSettings, uid: String, chatId: Int?, scopeProfile: Boolean = true, legacy: Boolean = false) =
        buildJsonObject {
            put("budget", Json.encodeToJsonElement(features.tokenBudget.copy(profileContextCeilings = features.tokenBudget.profileContextCeilings.filterKeys { !scopeProfile || it == uid })))
            // Quote refresh timestamps and provenance are telemetry, not authority.
            // Keep the actual rates and limits so a cost-changing edit still blocks dispatch.
            put("spend", Json.encodeToJsonElement(features.spendBudget.copy(prices = features.spendBudget.prices.filterKeys { !scopeProfile || it == uid && features.spendBudget.enforced }.mapValues { (_, price) -> price.copy(checkedAt = 0, source = "") })))
            val behavior = features.profileBehavior[uid] ?: ProfileBehaviorSettings()
            val canonicalBehavior = behavior.copy(crawlerToolIds = behavior.crawlerToolIds.toSortedSet(), toolPluginStates = behavior.toolPluginStates.toSortedMap())
            put("profile", if (legacy) Json.encodeToJsonElement(features.profileBehavior[uid]) else Json.encodeToJsonElement(canonicalBehavior))
            val plugins = Json.encodeToJsonElement(features.pluginExecution) as JsonObject
            put("plugins", if (legacy) plugins else JsonObject(plugins.toSortedMap()))
            // Persisting an explicit default is not a permission change. Keep
            // every nondefault grant/revocation, including opt-in services.
            // Voice Chat gates microphone UI and supplies text through normal chat admission.
            put("tools", ToolPluginPolicyProjection.project(features.toolPluginStates - ToolPluginId.VOICE_CHAT, ToolPluginId.optInServices))
            put("remoteMcp", features.remoteMcpConnections)
            put("location", features.deviceLocationTool)
            put("assist", features.localAssist)
            put("assistPolicy", Json.encodeToJsonElement(features.localAssistPolicy))
            put("delegation", Json.encodeToJsonElement(features.delegation))
            put("conversationDelegation", Json.encodeToJsonElement(chatId?.let { features.conversationDelegation[it] }))
            put("conversationResearch", Json.encodeToJsonElement(chatId?.let { features.conversationResearch[it]?.copy(crawlerProfileUid = null) }))
            put("conversationGateway", Json.encodeToJsonElement(chatId?.let { features.conversationGateway[it] }))
            put("researchEnabled", features.researchFeature.enabled)
            put("researchReviewer", features.researchFeature.reviewerProfileUid)
            put("reasoning", Json.encodeToJsonElement(chatId?.let { features.conversationReasoning[it] }))
            val github = chatId?.let { features.conversationGitHub[it] }
            if (github != null) {
                put("githubProfileSelection", github.mainProfileUid)
                if (github.mainProfileUid == uid) {
                    val override = Json.encodeToJsonElement(features.githubProfileOverrides[uid] ?: GitHubChatProfileOverride.newProfile) as JsonObject
                    // Quick workflows populate the composer. A submitted task already owns its prompt.
                    put("githubProfileOverride", if (legacy) override else JsonObject(override - "quickWorkflows"))
                }
            }
            put("engineering", Json.encodeToJsonElement(features.engineeringProfiles[uid]))
            put("llamaReasoning", Json.encodeToJsonElement(features.llamaReasoningModes[uid]))
        }
}

/** Safe field names only; never include settings values, prompts or credentials. */
internal class DispatchPolicyChangedException(val fields: List<String>, val gatewaySelectionChange: String? = null, val blockedStage: String? = null) :
    IllegalStateException(
        "DISPATCH_POLICY_CHANGED: changed fields=${fields.joinToString(",")}. " +
            "The task is paused. Resume it to revalidate saved results under the current settings. Completed operations remain recorded."
    ) {
    /** Explain the changed contract without calling every settings edit a permission revocation. */
    fun recoveryMessage(): String {
        val change = when {
            fields.isNotEmpty() && fields.all { it in setOf("githubProfileOverride", "githubProfileOverride.nextActionPolicy") } && "githubProfileOverride.nextActionPolicy" in fields -> "The next action policy changed."
            "providerPermissions" in fields || "tools" in fields -> "The permissions or available tools changed."
            fields.any { it.startsWith("profile.") || it == "githubProfileSelection" } -> "The selected profile or model changed."
            "budget" in fields || "spend" in fields -> "The accepted execution limits changed."
            else -> "The task configuration changed."
        }
        return "DISPATCH_POLICY_CHANGED: $change Continue the saved task to revalidate its retained results and plan under the current settings. Completed operations remain recorded."
    }
}
