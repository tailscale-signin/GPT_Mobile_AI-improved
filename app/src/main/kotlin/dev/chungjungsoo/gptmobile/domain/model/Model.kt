package dev.chungjungsoo.gptmobile.domain.model

/**
 * Unified model provider type supporting all AI providers
 */
enum class ModelProviderType {
    OLLAMA,
    OPENROUTER,
    LOCAL_RUNTIME,
    HUGGINGFACE,
    CUSTOM
}

/**
 * Represents an AI model available for use
 */
data class Model(
    val id: String,
    val name: String,
    val provider: ModelProviderType,
    val description: String? = null,
    val contextWindow: Int? = null,
    val isFavorite: Boolean = false,
    val isAvailable: Boolean = true,
    val category: ModelCategory = ModelCategory.GENERAL
) {
    /**
     * Get display name combining provider and model name
     */
    fun getDisplayName(): String {
        return when (provider) {
            ModelProviderType.OLLAMA -> "Ollama: $name"
            ModelProviderType.OPENROUTER -> "OpenRouter: $name"
            ModelProviderType.LOCAL_RUNTIME -> "Local: $name"
            ModelProviderType.HUGGINGFACE -> "HuggingFace: $name"
            ModelProviderType.CUSTOM -> name
        }
    }

    /**
     * Get provider display name
     */
    fun getProviderDisplayName(): String {
        return when (provider) {
            ModelProviderType.OLLAMA -> "Ollama"
            ModelProviderType.OPENROUTER -> "OpenRouter"
            ModelProviderType.LOCAL_RUNTIME -> "Local Runtime"
            ModelProviderType.HUGGINGFACE -> "HuggingFace"
            ModelProviderType.CUSTOM -> "Custom"
        }
    }
}

/**
 * Model categories for filtering and organization
 */
enum class ModelCategory {
    GENERAL,
    CODING,
    CREATIVE,
    ANALYTICS,
    MATH,
    SCIENCE,
    OTHER
}
