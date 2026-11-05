package dev.chungjungsoo.gptmobile.data.network.gateway

import android.os.Build
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Opt-in transport metadata only. App logs, prompts, device identifiers and credentials are never uploaded. */
internal object GatewayClientDiagnostics {
    const val CLIENT_HEADER = "X-Gateway-Client-Diagnostics"
    private val lock = Any()
    private data class Instance(val id: String, val revision: Long)
    private val instances = linkedMapOf<String, Instance>()
    private data class ProfileBinding(val route: String, val revision: Long)
    private val profiles = linkedMapOf<String, ProfileBinding>()
    private var revisionClock = 0L
    private val instancePattern = Regex("[a-f0-9]{32}")
    private val roles = setOf("primary", "github", "review", "synthesis", "diagnostic")

    /** Shared capability identity; two profiles on one authenticated route need only one probe. */
    fun routeIdentity(config: ProviderRequestConfig): String {
        val headers = config.extraHeaders.entries.filterNot { it.key.startsWith("X-Gateway-", true) }
            .sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key.lowercase()}:${it.value}" }
        return researchHash("${config.apiUrl.trim().trimEnd('/')}|${researchHash(config.token.orEmpty())}|${researchHash(headers)}")
    }

    fun runtimeEpoch(config: ProviderRequestConfig): String = synchronized(lock) { instances[routeIdentity(config)]?.id ?: "unobserved" }

    fun capabilityEpoch(config: ProviderRequestConfig): Long = synchronized(lock) { instances[routeIdentity(config)]?.revision ?: revisionClock }

    /** A selected profile can observe its bound route's restart without resolving a stored credential. */
    fun profileCapabilityEpoch(profileUid: String, apiUrl: String): Long = synchronized(lock) {
        profiles[profileKey(profileUid, apiUrl)]?.let { binding ->
            maxOf(binding.revision, instances[binding.route]?.revision ?: revisionClock)
        } ?: revisionClock
    }

    private fun profileKey(profileUid: String, apiUrl: String) = researchHash("$profileUid|${apiUrl.trim().trimEnd('/')}")

    private fun rememberRoute(config: ProviderRequestConfig): String {
        val route = routeIdentity(config)
        config.profileUid?.takeIf(String::isNotBlank)?.let { profileUid ->
            synchronized(lock) {
                val key = profileKey(profileUid, config.apiUrl)
                val previous = profiles[key]
                if (previous?.route != route) {
                    if (profiles.size >= 128 && key !in profiles) {
                        profiles.remove(profiles.keys.first())
                        revisionClock++
                    }
                    if (previous != null) revisionClock++
                    profiles[key] = ProfileBinding(route, revisionClock)
                }
            }
        }
        return route
    }

    fun observeInstance(config: ProviderRequestConfig, instance: String?) {
        val route = rememberRoute(config)
        if (instance == null || !instancePattern.matches(instance)) return
        synchronized(lock) {
            if (instances.size >= 128 && route !in instances) {
                instances.remove(instances.keys.first())
                revisionClock++
            }
            val previous = instances[route]
            if (previous != null && previous.id != instance) revisionClock++
            val revision = if (previous?.id == instance) previous.revision else revisionClock
            instances[route] = Instance(instance, revision)
        }
    }

    fun withHeaders(config: ProviderRequestConfig, model: String): ProviderRequestConfig {
        rememberRoute(config)
        val allowedRoute = config.providerType == "LLAMA" || config.extraHeaders.keys.any { it.startsWith("X-Gateway-", true) }
        val without = config.extraHeaders.filterKeys { !it.equals(CLIENT_HEADER, true) }
        if (!captureAllowed || !allowedRoute) return config.copy(extraHeaders = without)
        val metadata = metadata(config, model, Build.VERSION.SDK_INT, BuildConfig.VERSION_CODE, LocalServiceHealth.networkEpoch)
        return config.copy(extraHeaders = without + (CLIENT_HEADER to metadata))
    }

    private val captureAllowed: Boolean get() = LocalDiagnosticsPolicy.enabled && !AppLogRecorder.privateSessionActive

    /** Recheck at each physical dispatch; a request prepared before Debug was disabled must not upload metadata. */
    fun requestHeaders(config: ProviderRequestConfig): Map<String, String> =
        if (captureAllowed) config.extraHeaders else config.extraHeaders.filterKeys { !it.equals(CLIENT_HEADER, true) }

    private fun canonicalRole(role: String): String = when {
        role in roles -> role
        role.startsWith("github-review") -> "review"
        role.startsWith("github-") -> "github"
        else -> "other"
    }

    internal fun metadata(config: ProviderRequestConfig, model: String, androidSdk: Int, appVersionCode: Int, networkEpoch: Long): String = buildJsonObject {
        put("schemaVersion", 1)
        put("androidSdk", androidSdk.coerceAtLeast(0))
        put("appVersionCode", appVersionCode.coerceAtLeast(0))
        put("networkEpoch", networkEpoch.coerceAtLeast(0))
        put("profileDigest", researchHash(config.profileUid.orEmpty()))
        put("modelDigest", researchHash(model))
        put("requestRole", canonicalRole(config.requestRole))
    }.toString()

    /** Runs before HTTP error handling as restart/auth failures are useful evidence too. */
    fun observe(config: ProviderRequestConfig, status: Int, header: (String) -> String?) {
        val instance = header("X-Gateway-Instance-ID")
        observeInstance(config, instance)
        if (!captureAllowed || header("X-Gateway-Debug-Protocol") != "1") return
        val accepted = header("X-Gateway-Client-Diagnostics-Accepted") == "1"
        val debugEnabled = header("X-Gateway-Debug-Enabled") == "true"
        val requestDigest = header("X-Gateway-Request-ID")?.takeIf { it.length <= 128 }?.let(::researchHash) ?: "unavailable"
        AppLogRecorder.record("Gateway", "CLIENT_EXCHANGE · route=${routeIdentity(config)} · instance=${runtimeEpoch(config)} · http=$status · gatewayDebug=$debugEnabled · clientMetadataAccepted=$accepted · requestDigest=$requestDigest")
    }
}
