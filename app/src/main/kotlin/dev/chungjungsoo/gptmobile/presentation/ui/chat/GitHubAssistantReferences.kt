package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Repository-scoped destinations for references in assistant prose. */
internal data class GitHubAssistantReferenceContext(
    val owner: String,
    val repository: String,
    val webOrigin: String,
    val activeBranch: String? = null
) {
    val repositoryUrl: String? = runCatching {
        val uri = java.net.URI(webOrigin)
        require(uri.scheme == "https" && uri.host.isNotBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        "${uri.scheme}://${uri.rawAuthority}/${encodePath(owner)}/${encodePath(repository)}"
    }.getOrNull()

    private fun encodePath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
}

internal val LocalGitHubAssistantReferences = staticCompositionLocalOf<GitHubAssistantReferenceContext?> { null }

/** Linkify repository references in prose while leaving fenced code, existing links and URLs intact. */
internal fun linkifyGitHubAssistantReferences(markdown: String, context: GitHubAssistantReferenceContext): String {
    val repositoryUrl = context.repositoryUrl ?: return markdown
    val fenced = Regex("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)")
    val inlineAndLinks = Regex("`[^`\\n]*`|!?\\[[^\\]]*\\]\\([^\\n)]*\\)|<https?://[^>\\n]+>|https?://[^\\s<>]+")
    val branchWord = Regex("(?i)\\bbranch\\s*(?:(?:named|name|called|is|to|from|on)\\s*)?[:=]?\\s*`?([A-Za-z0-9][A-Za-z0-9._/-]*)`?")
    val branchPrefix = Regex("(?i)\\bbranch\\s*(?:(?:named|name|called|is|to|from|on)\\s*)?[:=]?\\s*$")
    val commonBranchName = Regex("(?i)(?:main|master|develop|development|dev|trunk)")
    val namedBeforeBranch = Regex("(?i)(?<![A-Za-z0-9._/-])(?:main|master|develop|development|dev|trunk)(?=\\s+branch\\b)")
    val knownBranch = Regex("(?i)(?<![A-Za-z0-9._/-])(?:fix|feat|feature|bugfix|hotfix|chore|release|codex-self-test|test|tests|build|ci|docs|perf|refactor|revert|support|wip|experiment|research|ui|ux|maintenance|patch|spike)/[A-Za-z0-9._/-]+(?![A-Za-z0-9/_-])(?!\\.[A-Za-z0-9])")
    val activeBranch = context.activeBranch?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")) }
    val fullCommit = Regex("(?i)(?<![A-Za-z0-9])[a-f0-9]{40}(?![A-Za-z0-9])")
    val shortCommit = Regex("(?i)(?<![A-Za-z0-9])[a-f0-9]{7,39}(?![A-Za-z0-9])")
    val pullRequest = Regex("(?i)\\b(?:PR|pull request)\\s*(#\\d+)\\b")

    fun hasCommitLabelBefore(value: String): Boolean = Regex("(?i)\\b(?:commit|revision|changeset)(?:\\s+(?:sha|id|hash))?\\s*[:=#]?\\s*$")
        .containsMatchIn(value.takeLast(72))

    fun encodeRef(ref: String): String = ref.split('/').joinToString("/") {
        URLEncoder.encode(it, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
    fun link(label: String, url: String) = "[$label]($url)"
    fun makePlainLinks(input: String): String {
        var result = input
        val savedLinks = mutableListOf<String>()
        fun hold(value: String): String = "__GITHUB_ASSISTANT_LINK_${savedLinks.size}__".also { savedLinks += value }
        result = pullRequest.replace(result) { match ->
            val number = match.groupValues[1].drop(1)
            match.value.replace(match.groupValues[1], hold(link("#$number", "$repositoryUrl/pull/$number")))
        }
        result = branchWord.replace(result) { match ->
            val ref = match.groupValues[1].trimEnd('.', ',', ':', ';')
            val token = match.groupValues[1]
            if (ref.isBlank() || ref.lowercase() in setOf("the", "a", "an", "is", "was", "will", "should", "can", "could", "to", "from", "on", "currently", "still", "being", "used")) {
                match.value
            } else {
                match.value.replace(token, hold(link(ref, "$repositoryUrl/tree/${encodeRef(ref)}") + token.drop(ref.length)))
            }
        }
        result = namedBeforeBranch.replace(result) { match ->
            val ref = match.value
            hold(link(ref, "$repositoryUrl/tree/${encodeRef(ref)}"))
        }
        result = knownBranch.replace(result) { match ->
            val raw = match.value
            val ref = raw.trimEnd('.', ',', ':', ';', '!')
            hold(link(ref, "$repositoryUrl/tree/${encodeRef(ref)}") + raw.drop(ref.length))
        }
        if (activeBranch != null) {
            val activeBranchPattern = Regex("(?<![A-Za-z0-9._/-])${Regex.escape(activeBranch)}(?![A-Za-z0-9/_-])(?!\\.[A-Za-z0-9])")
            result = activeBranchPattern.replace(result) { match ->
                hold(link(match.value, "$repositoryUrl/tree/${encodeRef(activeBranch)}"))
            }
        }
        result = fullCommit.replace(result) { match ->
            val commit = match.value
            hold(link(commit, "$repositoryUrl/commit/$commit"))
        }
        result = shortCommit.replace(result) { match ->
            val commit = match.value
            if (hasCommitLabelBefore(result.substring(0, match.range.first))) {
                hold(link(commit, "$repositoryUrl/commit/$commit"))
            } else {
                commit
            }
        }
        savedLinks.forEachIndexed { index, saved -> result = result.replace("__GITHUB_ASSISTANT_LINK_${index}__", saved) }
        return result
    }

    fun plainChunk(input: String): String {
        // Explicitly named refs take precedence over generic branch and SHA matches.
        var cursor = 0
        val output = StringBuilder()
        for (match in inlineAndLinks.findAll(input)) {
            if (match.range.first < cursor) continue
            output.append(makePlainLinks(input.substring(cursor, match.range.first)))
            val token = match.value
            if (token.startsWith("`") && token.endsWith("`")) {
                val ref = token.removeSurrounding("`")
                val prefix = output.toString().takeLast(96)
                val isBranch = branchPrefix.containsMatchIn(prefix) || knownBranch.matches(ref) || commonBranchName.matches(ref)
                val isCommit = fullCommit.matches(ref) || shortCommit.matches(ref) && hasCommitLabelBefore(prefix)
                val pr = Regex("(?i)(?:PR|pull request)\\s*$").containsMatchIn(prefix)
                output.append(
                    when {
                        isBranch && ref.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")) -> link("`$ref`", "$repositoryUrl/tree/${encodeRef(ref)}")
                        isCommit -> link("`$ref`", "$repositoryUrl/commit/$ref")
                        pr && ref.matches(Regex("#\\d+")) -> link("`$ref`", "$repositoryUrl/pull/${ref.drop(1)}")
                        knownBranch.matches(ref) -> link("`$ref`", "$repositoryUrl/tree/${encodeRef(ref)}")
                        else -> token
                    }
                )
            } else {
                output.append(token)
            }
            cursor = match.range.last + 1
        }
        output.append(makePlainLinks(input.substring(cursor)))
        return output.toString()
    }

    val output = StringBuilder()
    var cursor = 0
    for (match in fenced.findAll(markdown)) {
        output.append(plainChunk(markdown.substring(cursor, match.range.first)))
        output.append(match.value)
        cursor = match.range.last + 1
    }
    output.append(plainChunk(markdown.substring(cursor)))
    return output.toString()
}
