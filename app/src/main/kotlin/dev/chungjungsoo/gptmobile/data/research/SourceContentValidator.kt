package dev.chungjungsoo.gptmobile.data.research

/** Versioned admission of source bytes. Capture completeness is a separate, explicit fact. */
object SourceContentValidator {
    const val VERSION = 2
    enum class Kind { CHALLENGE, AUTH_REQUIRED, EMPTY, METADATA, ABSTRACT, PREVIEW, ARTICLE_PARTIAL, ARTICLE_COMPLETE }
    data class Result(val kind: Kind, val bodyHash: String, val reasons: List<String>, val validatorVersion: Int = VERSION) {
        val usable: Boolean get() = kind in setOf(Kind.ABSTRACT, Kind.ARTICLE_PARTIAL, Kind.ARTICLE_COMPLETE)
    }
    fun validate(text: String, completeArticle: Boolean = false, metadataOnly: Boolean = false, abstractOnly: Boolean = false): Result {
        val lower = text.lowercase()
        val kind = when {
            text.isBlank() -> Kind.EMPTY
            dev.chungjungsoo.gptmobile.data.agent.tool.looksLikeConsentOrScriptShell(text) -> Kind.CHALLENGE
            text.length < 2000 && listOf("sign in to access", "log in to access", "authentication required", "please sign in to continue").any(lower::contains) -> Kind.AUTH_REQUIRED
            metadataOnly -> Kind.METADATA
            abstractOnly -> Kind.ABSTRACT
            text.length < 12000 && listOf("preview of subscription content", "purchase this article", "access through your institution", "this is a preview").any(lower::contains) -> Kind.PREVIEW
            completeArticle -> Kind.ARTICLE_COMPLETE
            else -> Kind.ARTICLE_PARTIAL
        }
        return Result(kind, researchHash(text), listOf(kind.name))
    }
}
