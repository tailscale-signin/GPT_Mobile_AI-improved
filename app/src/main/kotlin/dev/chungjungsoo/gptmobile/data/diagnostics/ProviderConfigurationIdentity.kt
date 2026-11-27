package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.UUID

/** Export only a random process-local identity; credential-derived comparisons stay in memory. */
internal object ProviderConfigurationIdentity {
    private class Captured(val revision: String, val opaqueHash: String)
    private val profiles = LinkedHashMap<String, Captured>(16, 0.75f, true)
    private const val MAX_PROFILES = 128

    @Synchronized
    fun capture(profile: PlatformV2): String {
        val revision = ConfiguredDispatchAuthority.profileRevision(profile)
        profiles[profile.uid]?.takeIf { it.revision == revision }?.let { return it.opaqueHash }
        val captured = Captured(revision, researchHash(UUID.randomUUID().toString()))
        profiles[profile.uid] = captured
        if (profiles.size > MAX_PROFILES) profiles.remove(profiles.keys.first())
        return captured.opaqueHash
    }
}
