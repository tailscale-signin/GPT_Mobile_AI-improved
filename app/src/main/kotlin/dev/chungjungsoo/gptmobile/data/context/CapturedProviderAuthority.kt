package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Frozen configuration evidence for a known provider rejection; it contains no credentials. */
@Serializable
data class CapturedProviderAuthority(
    val profileUid: String,
    val model: String,
    val registeredRevision: String,
    val policyRevision: String,
    val permissionRevision: String,
    val routeRevision: String,
    val chatId: Int? = null,
    val parents: List<CapturedProviderAuthority> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val acceptedTokenBudget: TokenBudgetSettings? = null
) {
    internal suspend fun validate(settings: SettingRepository) {
        parents.forEach { it.validate(settings) }
        val current = settings.fetchPlatformV2s().singleOrNull { it.uid == profileUid }
        if (current == null || !current.enabled || ConfiguredDispatchAuthority.profileRevision(current) != registeredRevision) throw ProviderWaitAuthorityChanged("PROVIDER_WAIT_AUTHORITY_CHANGED")
        val admitted = current.copy(model = model)
        if (routeRevision(admitted) != routeRevision) throw ProviderWaitAuthorityChanged("PROVIDER_WAIT_ROUTE_CHANGED")
        val live = settings.getFeatureSettings()
        val effective = acceptedTokenBudget?.takeIf { ConfiguredDispatchAuthority.allowsAcceptedTokenPlan(it, live.tokenBudget, profileUid) }?.let { live.copy(tokenBudget = it) } ?: live
        if (ConfiguredDispatchAuthority.policyRevision(effective, profileUid, chatId) != policyRevision || settings.providerPermissionRevision(admitted) != permissionRevision || settings.providerActivationRequired(admitted)) throw ProviderWaitAuthorityChanged("PROVIDER_WAIT_PERMISSION_CHANGED")
    }

    /** Reinstall the saved authority when work crosses an independent coroutine owner. */
    internal fun requestGuard(settings: SettingRepository): RequestAuthorizationGuard =
        RequestAuthorizationGuard("provider-wait:${researchHash(Json.encodeToString(this))}", this) { validate(settings) }

    internal fun matches(platform: PlatformV2): Boolean = profileUid == platform.uid && model == platform.model && routeRevision == routeRevision(platform)

    companion object {
        internal fun routeRevision(platform: PlatformV2): String = researchHash("${platform.compatibleType}|${platform.apiUrl}|${platform.model}|${platform.providerConnectionUid.orEmpty()}")
    }
}

/** A revoked saved wait requires review rather than an automatic writer fallback. */
internal class ProviderWaitAuthorityChanged(reason: String) : IllegalStateException(reason)
