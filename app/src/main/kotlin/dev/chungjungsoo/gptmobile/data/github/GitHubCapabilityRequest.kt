package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale

/** Questions about the interface have no execution target, including when they name a write action. */
internal data class GitHubCapabilityRequest(
    val scope: Scope,
    val filter: String = "",
    val page: Int = 1,
    val pullRequestReads: Boolean = false
) {
    enum class Scope { TOOLS, GITHUB, FEATURES, ALL }

    companion object {
        private val pageSuffix = Regex("""(?:,?\s+page\s+([1-9]\d{0,3}))$""")
        private val queryFilter = Regex(""" (?:matching|related to|about|for|named|containing) ([\w -]{1,70})$""")
        private val collection = Regex("""(?:what|which|list|show|describe)(?: me)? (?:all |the |available |supported |enabled )*(?:(github|app|application) )?(tools and features|features and tools|tools|features|capabilities|actions)(?: (?:are available|are supported|are enabled|are exposed|are there|can you use|do you (?:support|have)|can i use))?(?: here| (?:in|for|on) (?:this |the |my )?(chat|conversation|app|github mode|github chat|github))?""")
        private val topicFirst = Regex("""(?:what|which|list|show)(?: me)? (?:the )?([\w-]+(?: [\w-]+){0,2}) (features|tools) (?:are available|are supported|are enabled|can i use|can you use)(?: here| in this chat)?""")
        private val prInspection = Regex("""(?:can (?:you|this app|github mode)|does (?:this app|github mode) (?:support|allow)) (?:read|inspect|review|list|reading|inspecting|reviewing) (?:my |our |repository )?(?:pull requests|prs)(?: in this chat)?""")

        fun preserveCatalogQuery(text: String): Boolean = from(text) != null || GitHubCodeResearch.matches(text)

        fun from(text: String): GitHubCapabilityRequest? {
            val raw = GitHubTaskContinuity.request(text)
            if (raw.length !in 1..240 || raw.any { it == '\n' || it == '\r' }) return null
            val question = raw.lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim().removePrefix("please ").replace(Regex("\\s+"), " ")
            val pageMatch = pageSuffix.find(question)
            val page = pageMatch?.groupValues?.get(1)?.toInt() ?: 1
            val base = if (pageMatch == null) question else question.substring(0, pageMatch.range.first)
            if (prInspection.matches(base)) return GitHubCapabilityRequest(Scope.GITHUB, page = page, pullRequestReads = true)
            if (base in setOf("what can github mode do", "what can i do in github mode", "what can you do in github mode")) return GitHubCapabilityRequest(Scope.GITHUB, page = page)
            if (base in setOf("what can you do", "what can you do in this chat")) return GitHubCapabilityRequest(Scope.TOOLS, page = page)
            fun parse(phrase: String, filter: String): GitHubCapabilityRequest? {
                val (qualifier, kind, location) = collection.matchEntire(phrase)?.destructured ?: return null
                val scope = when {
                    kind.contains(" and ") -> Scope.ALL
                    qualifier == "github" || location.startsWith("github") -> Scope.GITHUB
                    kind == "features" || qualifier in setOf("app", "application") && kind == "capabilities" -> Scope.FEATURES
                    else -> Scope.TOOLS
                }
                return GitHubCapabilityRequest(scope, filter, page)
            }
            parse(base, "")?.let { return it }
            topicFirst.matchEntire(base)?.let { match ->
                val (topic, kind) = match.destructured
                return GitHubCapabilityRequest(if (kind == "features") Scope.FEATURES else Scope.TOOLS, topic, page)
            }
            val filter = queryFilter.find(base) ?: return null
            val terms = filter.groupValues[1].trim()
            if (terms.isEmpty() || Regex("""\b(?:and|then|also|but|instead|before|after|please)\b""").containsMatchIn(terms)) return null
            return parse(base.substring(0, filter.range.first), terms)
        }
    }
}

internal object GitHubCodeResearch {
    private val inquiry = Regex("""(?i)^(?:(?:please |can you |could you |show me |tell me )?)(?:where (?:in (?:this|the|our) (?:repository|repo|codebase) )?(?:is|are|does)|how (?:does|do|is|are)|which (?:files?|functions?|classes?|methods?|symbols?)|(?:find|locate|trace) (?:the )?(?:implementation|definition|callers?|references?|call sites?|source code))\b""")
    private val source = Regex("""(?i)\b(?:implemented|defined|declared|implementation|definition|callers?|call sites?|source code|codebase|wired|wiring|classes?|methods?|functions?|files?|symbols?)\b""")
    private val additionalTask = Regex("""(?i)(?:[?!.;,]\s*|\b(?:and|then|also|before|after)\s+)(?:(?:please|then|now|can you|could you|would you|will you)\s+)*(?:add|create|fix|implement|edit|update|run|build|test|commit|deploy|release|publish|push|merge|delete|change|rename|replace|remove|patch|repair|refactor|search the web|look online)\b""")

    fun matches(text: String): Boolean {
        val task = GitHubTaskContinuity.request(text)
        return task.length <= 600 && !task.contains('\n') && inquiry.containsMatchIn(task) && source.containsMatchIn(task) && !additionalTask.containsMatchIn(task)
    }
}
