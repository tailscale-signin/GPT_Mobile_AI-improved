package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Singleton
class AppAuditSelectedModelServices @Inject constructor(
    private val settings: SettingRepository,
    private val secretVault: SecretVault
) {
    internal suspend fun inventory(selectedIds: List<String>): List<AppAuditSelectedModelTarget> {
        if (selectedIds.isEmpty()) return emptyList()
        val profiles = settings.fetchPlatformV2s().associateBy { it.uid }
        return selectedIds.distinct().sorted().map { uid -> snapshot(uid, profiles[uid]) }
    }

    internal suspend fun target(uid: String): AppAuditSelectedModelTarget? = settings.fetchPlatformV2s().singleOrNull { it.uid == uid }?.let { snapshot(uid, it) }

    private suspend fun snapshot(uid: String, profile: PlatformV2?): AppAuditSelectedModelTarget {
        val credential = profile?.let { credentialRevision(it) }
        // The vault's opaque record revision binds rotation without serializing or hashing a token.
        val fingerprint = appAuditHash("selected-model-v1:$uid:${profile?.copy(token = null)}:$credential")
        return AppAuditSelectedModelTarget(
            key = AppAuditSelectedModelProbeSource.TARGET_PREFIX + uid,
            configured = profile != null && profile.model.isNotBlank() && credential != null,
            enabled = profile?.enabled == true,
            fingerprint = fingerprint,
            configurationRevision = appAuditConfigurationRevision(fingerprint).takeIf { profile != null && credential != null },
            local = profile?.compatibleType == ClientType.LITERT_LM,
            profileSnapshotHash = profile?.let(::appAuditBenchmarkProfileHash)
        )
    }

    private suspend fun credentialRevision(profile: PlatformV2): String? {
        return try {
            val epoch = ReadinessInvalidation.epoch
            val owner = profile.providerConnectionUid?.let { settings.getProviderConnection(it) ?: return null }
            if (owner != null && (owner.apiUrl != profile.apiUrl || owner.secretRef != profile.secretRef)) return null
            if (!profile.token.isNullOrBlank() && profile.secretRef == null) return null
            val revision = profile.secretRef?.let { secretVault.recordRevision(it)?.takeIf { revision -> revision.matches(Regex("[a-f0-9]{64}")) } } ?: "no-credential".takeIf { profile.secretRef == null }
            if (revision == null) return null
            val currentOwner = profile.providerConnectionUid?.let { settings.getProviderConnection(it) ?: return null }
            if (profile.secretRef != null && secretVault.recordRevision(profile.secretRef) != revision) return null
            val current = settings.fetchPlatformV2s().singleOrNull { it.uid == profile.uid }
            revision.takeIf { owner == currentOwner && current == profile && ReadinessInvalidation.epoch == epoch }
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
    }
}
