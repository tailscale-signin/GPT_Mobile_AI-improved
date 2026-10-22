package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime

internal enum class RuntimeAvailability { PACKAGED, UNAVAILABLE_FOR_ABI, DISABLED, UNQUALIFIED, LOAD_FAILED }
internal data class RuntimeCapability(val state: RuntimeAvailability, val backends: Set<String>, val reason: String, val revision: String)

/** Build inventory, not proof of a successful model load or hardware qualification. */
internal object RuntimeCapabilityRegistry {
    const val GGUF_REVISION = "620d2225cc436ab0b001e3200e590e3b286ef9ab"
    fun gguf(
        abis: List<String>,
        enabled: Boolean = BuildConfig.GGUF_ENABLED,
        vulkanEnabled: Boolean = BuildConfig.GGUF_VULKAN_ENABLED
    ): RuntimeCapability = when {
        !enabled -> RuntimeCapability(RuntimeAvailability.DISABLED, emptySet(), "This app build does not include an enabled GGUF runtime", GGUF_REVISION)
        abis.none { it in setOf("arm64-v8a", "x86_64") } -> RuntimeCapability(RuntimeAvailability.UNAVAILABLE_FOR_ABI, emptySet(), "GGUF runtime unavailable for this device ABI", GGUF_REVISION)
        else -> {
            val backends = if (vulkanEnabled) setOf("cpu", "gpu") else setOf("cpu")
            RuntimeCapability(RuntimeAvailability.PACKAGED, backends, "GGUF runtime packaged · ${backends.joinToString(" / ") { it.uppercase() }}; model load and device qualification are separate", GGUF_REVISION)
        }
    }
    val ggufPackaged: Boolean get() = gguf(android.os.Build.SUPPORTED_ABIS?.toList().orEmpty()).state == RuntimeAvailability.PACKAGED
    fun requireRuntime(runtime: ArtifactRuntime) {
        when (runtime) {
            ArtifactRuntime.LLAMA_CPP -> check(ggufPackaged) { "This app build does not include a compatible GGUF runtime" }
            ArtifactRuntime.GENIEX_LLAMA_CPP -> check(BuildConfig.GENIEX_ENABLED) { "This is a GenieX artifact. Install the separate GenieX preview APK to use the GenieX SDK; the standard app's llama.cpp runtime is not GenieX." }
            ArtifactRuntime.GENIEX_QAIRT -> check(BuildConfig.GENIEX_ENABLED) { "QAIRT bundles require the separate GenieX preview build" }
            else -> error("Unsupported artifact runtime")
        }
    }
}
