package dev.chungjungsoo.gptmobile.data.github

/** GitHub's issue search requires an explicit issue or pull-request discriminator. */
internal fun normalizeGitHubIssueQuery(query: String): String {
    val normalized = query.trim()
    require(normalized.isNotEmpty()) { "Issue search query is required." }
    val withoutQuotedText = normalized.replace(Regex("\"[^\"]*\""), " ")
    return if (Regex("(?i)(?:^|\\s)(?:is|type):(?:issue|pr|pull-request)(?=\\s|$)").containsMatchIn(withoutQuotedText)) {
        normalized
    } else {
        "$normalized is:issue"
    }
}
