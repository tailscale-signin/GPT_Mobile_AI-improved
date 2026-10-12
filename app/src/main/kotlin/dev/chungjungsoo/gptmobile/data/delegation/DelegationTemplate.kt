package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A portable tuning recipe contains no profile IDs, endpoints, prompts, secrets or grants. */
@Serializable
data class DelegationTemplate(
    val schema: Int = 1,
    val role: Role,
    val inputTokens: Int = 4000,
    val outputTokens: Int = 512,
    val calls: Int = 8,
    val concurrency: Int = 1,
    val reviewRequested: Boolean = true
) {
    @Serializable enum class Role { RESEARCH, DOCUMENT_EXTRACTION, REPOSITORY_ANALYSIS, STRUCTURED_COMPARISON }
    val description: String get() = when (role) {
        Role.RESEARCH -> "Read-only evidence gathering; return claims, sources and unresolved questions."
        Role.DOCUMENT_EXTRACTION -> "Extract supported facts with document references; identify missing fields."
        Role.REPOSITORY_ANALYSIS -> "Read-only repository inspection; return findings and file references."
        Role.STRUCTURED_COMPARISON -> "Compare supplied evidence using consistent criteria; label uncertainty."
    }
    fun validate() {
        require(schema == 1) { "Unsupported template version" }
        require(inputTokens in 1000..12000 && outputTokens in 128..4096 && calls in 1..48 && concurrency in 1..4) { "Template budgets exceed supported limits" }
    }
    fun applyTo(settings: ModelDelegationSettings): ModelDelegationSettings {
        validate()
        return settings.normalized().copy(templateRole = role, maxInputTokensPerDelegate = inputTokens, maxOutputTokens = outputTokens, maxLocalModelCalls = calls, maxConcurrentDelegates = concurrency, reviewerEnabled = settings.processingOwnership == 0 || reviewRequested)
    }
    companion object {
        private val codec = Json { encodeDefaults = true }
        fun decode(raw: String): DelegationTemplate {
            require(raw.length <= 8192) { "Template exceeds 8 KiB" }
            return codec.decodeFromString<DelegationTemplate>(raw).also { it.validate() }
        }
        fun encode(template: DelegationTemplate): String = codec.encodeToString(template.also { it.validate() })
        fun from(settings: ModelDelegationSettings) = DelegationTemplate(role = settings.templateRole ?: Role.RESEARCH, inputTokens = settings.maxInputTokensPerDelegate, outputTokens = settings.maxOutputTokens, calls = settings.maxLocalModelCalls, concurrency = settings.maxConcurrentDelegates, reviewRequested = settings.reviewerEnabled)
    }
}
