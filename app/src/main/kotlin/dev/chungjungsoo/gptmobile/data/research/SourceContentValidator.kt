package dev.chungjungsoo.gptmobile.data.research

/** Coverage is a positive capture proof; a successful download or old badge is not that proof. */
object SourceContentValidator {
    const val VERSION = 3
    enum class Kind { CHALLENGE, AUTH_REQUIRED, EMPTY, ERROR_PAGE, SEARCH_RESULTS, APPLICATION_SHELL, METADATA, ABSTRACT, PREVIEW, PARTIAL_ARTICLE, STRUCTURED_ARTICLE, ARTICLE_PARTIAL, ARTICLE_COMPLETE }
    data class Result(val kind: Kind, val bodyHash: String, val reasons: List<String>, val validatorVersion: Int = VERSION) {
        val usable: Boolean get() = kind in setOf(Kind.ABSTRACT, Kind.PARTIAL_ARTICLE, Kind.STRUCTURED_ARTICLE, Kind.ARTICLE_PARTIAL, Kind.ARTICLE_COMPLETE)
        val fullTextProven: Boolean get() = kind == Kind.STRUCTURED_ARTICLE
    }
    fun validate(text: String, completeArticle: Boolean = false, metadataOnly: Boolean = false, abstractOnly: Boolean = false, structuredBody: Boolean = false, sectionNames: Set<String> = emptySet(), truncated: Boolean? = null, url: String = ""): Result {
        val lower = text.lowercase()
        val leading = lower.take(6000)
        val hasAbstract = Regex("\\babstract\\b").containsMatchIn(leading)
        val sectionProof = sectionNames.any { "method" in it.lowercase() || "experimental" in it.lowercase() } && sectionNames.any { "result" in it.lowercase() }
        val kind = when {
            text.isBlank() -> Kind.EMPTY
            dev.chungjungsoo.gptmobile.data.agent.tool.looksLikeConsentOrScriptShell(text) -> Kind.CHALLENGE
            Regex("(?:article|page|document) (?:was |is )?not found|requested (?:article|page) (?:could not|does not)|sorry.{0,60}(?:cannot find|could not find)|404 (?:not found|error)|403 forbidden|500 internal server error").containsMatchIn(leading) -> Kind.ERROR_PAGE
            text.length < 2000 && listOf("sign in to access", "log in to access", "authentication required", "please sign in to continue").any(leading::contains) -> Kind.AUTH_REQUIRED
            metadataOnly -> Kind.METADATA
            abstractOnly -> Kind.ABSTRACT
            !hasAbstract && ("scholar.google" in url && listOf("search results", "about results", "cited by", "advanced search").any(leading::contains) || Regex("(?:search results|results for your search).{0,80}(?:sort by|filter|page)").containsMatchIn(leading)) -> Kind.SEARCH_RESULTS
            !hasAbstract && text.length < 900 && (Regex("\\bglossary\\b|javascript is required|loading application|enable javascript to run").containsMatchIn(leading) || "clinicaltrials.gov" in leading && "search" in leading && !Regex("participants|randomi[sz]ed|outcome").containsMatchIn(leading)) -> Kind.APPLICATION_SHELL
            text.length < 12000 && listOf("preview of subscription content", "purchase this article", "access through your institution", "this is a preview").any(leading::contains) -> Kind.PREVIEW
            completeArticle && structuredBody && truncated == false && sectionProof -> Kind.STRUCTURED_ARTICLE
            hasAbstract && !Regex("\\bmethods?\\b.*\\bresults?\\b", RegexOption.DOT_MATCHES_ALL).containsMatchIn(lower.substringAfter("abstract")) -> Kind.ABSTRACT
            else -> Kind.PARTIAL_ARTICLE
        }
        return Result(kind, researchHash(text), listOf(kind.name) + if (completeArticle && kind != Kind.STRUCTURED_ARTICLE) listOf("FULL_TEXT_PROOF_MISSING") else emptyList())
    }
}
