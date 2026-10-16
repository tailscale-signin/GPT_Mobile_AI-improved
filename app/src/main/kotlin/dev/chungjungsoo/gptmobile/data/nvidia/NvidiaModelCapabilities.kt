package dev.chungjungsoo.gptmobile.data.nvidia

/**
 * Conservative hosted NVIDIA NIM capability information. An unknown field is not a negative
 * capability: /models enumerates identities, but does not certify every inference feature.
 */
enum class NvidiaReasoningControl { EFFORT, TEMPLATE, UNKNOWN }

data class NvidiaModelCapabilities(
    val modelId: String,
    val reasoningControl: NvidiaReasoningControl = NvidiaReasoningControl.UNKNOWN,
    val supportsTools: Boolean? = null,
    val supportsImages: Boolean? = null
)

object NvidiaModelCapabilities {
    /** Suggestions only. A model is available to an account only after live discovery/inference. */
    val suggestedModelIds: List<String> = listOf(
        "openai/gpt-oss-20b",
        "openai/gpt-oss-120b",
        "nvidia/nemotron-3-nano-30b-a3b",
        "nvidia/nemotron-3-super-120b-a12b",
        "nvidia/nemotron-3-ultra-550b-a55b"
    )

    fun forModel(modelId: String): NvidiaModelCapabilities {
        val id = modelId.trim().lowercase()
        return when (id) {
            "openai/gpt-oss-20b", "openai/gpt-oss-120b" ->
                NvidiaModelCapabilities(modelId, NvidiaReasoningControl.EFFORT, supportsTools = true, supportsImages = false)
            "nvidia/nemotron-3-nano-30b-a3b",
            "nvidia/nemotron-3-super-120b-a12b",
            "nvidia/nemotron-3-ultra-550b-a55b" ->
                NvidiaModelCapabilities(modelId, NvidiaReasoningControl.TEMPLATE, supportsTools = true, supportsImages = false)
            else -> NvidiaModelCapabilities(modelId)
        }
    }
}
