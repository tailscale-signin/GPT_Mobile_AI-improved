package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Repository-scoped destinations for references in assistant prose. */
internal data class GitHubAssistantReferenceContext(
    val owner: String,
    val repository: String,
    val webOrigin: String,
    val activeBranch: String? = null,
    val receiptUrlsByReference: Map<String, String?> = emptyMap()
) {
    val repositoryUrl: String? = runCatching {
        val uri = java.net.URI(webOrigin)
        require(uri.scheme == "https" && uri.host.isNotBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        "${uri.scheme}://${uri.rawAuthority}/${encodePath(owner)}/${encodePath(repository)}"
    }.getOrNull()

    private fun encodePath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
}

/** The small receipt fields used to bind prose links to the repository that produced them. */
internal data class GitHubReferenceReceipt(val toolName: String, val arguments: String, val result: String?)

private const val MAX_REFERENCE_RECEIPTS = 128

internal fun boundedGitHubReferenceReceipts(receipts: List<GitHubReferenceReceipt>): List<GitHubReferenceReceipt> = receipts.takeLast(MAX_REFERENCE_RECEIPTS)
    .filter { it.arguments.length <= 8_192 && it.result.orEmpty().length <= 32_768 }

/** Each visible response row owns a bounded cache; there is no global receipt retention. */
internal class GitHubReferenceProjectionCache(
    private val projectReceipt: (GitHubAssistantReferenceContext, GitHubReferenceReceipt) -> Map<String, String?> = { context, receipt ->
        context.withReceiptReferences(listOf(receipt)).receiptUrlsByReference
    }
) {
    private var previousContext: GitHubAssistantReferenceContext? = null
    private var previousReceipts: List<GitHubReferenceReceipt> = emptyList()
    private var receiptLinks = emptyMap<GitHubReferenceReceipt, Map<String, String?>>()
    private var previousProjection: GitHubAssistantReferenceContext? = null

    // Cancelled Compose producers may briefly overlap on Default. No caller holds this lock on the UI thread.
    @Synchronized
    fun project(context: GitHubAssistantReferenceContext, receipts: List<GitHubReferenceReceipt>): GitHubAssistantReferenceContext {
        val bounded = boundedGitHubReferenceReceipts(receipts)
        if (context == previousContext && bounded == previousReceipts) previousProjection?.let { return it }
        val reusable = if (context == previousContext) receiptLinks else emptyMap()
        val retained = linkedMapOf<GitHubReferenceReceipt, Map<String, String?>>()
        val links = linkedMapOf<String, String?>()
        for (receipt in bounded) {
            val projected = retained.getOrPut(receipt) { reusable[receipt] ?: projectReceipt(context, receipt) }
            for ((reference, repository) in projected) {
                if (reference !in links) {
                    links[reference] = repository
                } else if (links[reference] != repository) {
                    links[reference] = null
                }
            }
        }
        val projected = if (context.repositoryUrl == null) context else context.copy(receiptUrlsByReference = links)
        val result = previousProjection?.takeIf { it == projected } ?: projected
        previousContext = context
        previousReceipts = bounded
        receiptLinks = retained
        previousProjection = result
        return result
    }
}

internal data class GitHubReceiptReferenceRequest(val context: GitHubAssistantReferenceContext?, val receipts: List<GitHubReferenceReceipt>)

internal data class PreparedGitHubReceiptReferences(val request: GitHubReceiptReferenceRequest, val context: GitHubAssistantReferenceContext) {
    fun forRequest(current: GitHubReceiptReferenceRequest): GitHubAssistantReferenceContext? = context.takeIf { request == current }
}

/** Parsing a growing receipt history must not run inside lazy-list composition or measurement. */
@Composable
internal fun rememberGitHubReceiptReferences(context: GitHubAssistantReferenceContext?, receipts: List<GitHubReferenceReceipt>): GitHubAssistantReferenceContext? {
    val cache = remember { GitHubReferenceProjectionCache() }
    val request = remember(context, receipts) { GitHubReceiptReferenceRequest(context, boundedGitHubReferenceReceipts(receipts)) }
    val prepared by produceState<PreparedGitHubReceiptReferences?>(null, request) {
        value = request.context?.let { selected ->
            withContext(Dispatchers.Default) {
                PreparedGitHubReceiptReferences(request, cache.project(selected, request.receipts))
            }
        }
    }
    // Until the current projection is ready, show plain references instead of an old repository binding.
    return prepared?.forRequest(request)
}

internal fun GitHubAssistantReferenceContext.withReceiptReferences(receipts: List<GitHubReferenceReceipt>): GitHubAssistantReferenceContext {
    val selected = repositoryUrl ?: return this
    val links = linkedMapOf<String, String?>()
    val origins = runCatching { java.net.URI(webOrigin) }.getOrNull() ?: return this
    val sha = Regex("(?i)[a-f0-9]{7,40}")
    fun fieldValues(element: JsonElement, keys: Set<String>): List<String> {
        val budget = intArrayOf(512)
        fun visit(node: JsonElement, depth: Int): List<String> {
            if (depth > 8 || budget[0]-- <= 0) return emptyList()
            return when (node) {
                is JsonObject -> buildList {
                    node.forEach { (key, value) ->
                        if (key in keys && value is JsonPrimitive) value.contentOrNull?.takeIf(String::isNotBlank)?.let(::add)
                        if (value is JsonObject || value is JsonArray) addAll(visit(value, depth + 1))
                    }
                }
                is JsonArray -> buildList { node.take(128).forEach { addAll(visit(it, depth + 1)) } }
                else -> emptyList()
            }
        }
        return visit(element, 0)
    }
    fun repositoryBase(arguments: JsonObject, result: JsonObject): String {
        val fullName = (
            fieldValues(arguments, setOf("full_name", "repository_full_name", "repository")) +
                fieldValues(result, setOf("full_name", "repository_full_name", "repository"))
            )
            .firstOrNull { it.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) }
        val owner = (fieldValues(arguments, setOf("owner", "repository_owner")) + fieldValues(result, setOf("owner", "repository_owner"))).firstOrNull()
        val name = (fieldValues(arguments, setOf("repo", "repository_name")) + fieldValues(result, setOf("repo", "repository_name"))).firstOrNull()
        val path = fullName?.split('/') ?: if (owner != null && name != null) listOf(owner, name) else null
        val urlFromReceipt = fieldValues(result, setOf("html_url")).firstNotNullOfOrNull { raw ->
            val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return@firstNotNullOfOrNull null
            val segments = uri.path.orEmpty().trim('/').split('/')
            if (uri.scheme == "https" &&
                uri.host.equals(origins.host, ignoreCase = true) &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                segments.size >= 4 &&
                segments[2] in setOf("pull", "pulls", "commit", "tree", "blob")
            ) {
                "${uri.scheme}://${uri.rawAuthority}/${segments.take(2).joinToString("/") { encodeRepositoryPath(it) }}"
            } else {
                null
            }
        }
        return urlFromReceipt ?: path?.let { "${origins.scheme}://${origins.rawAuthority}/${it.joinToString("/") { part -> encodeRepositoryPath(part) }}" } ?: selected
    }
    fun add(key: String, url: String?) {
        if (key !in links) {
            links[key] = url
        } else if (links[key] != url) {
            links[key] = null
        }
    }
    for (receipt in receipts.takeLast(MAX_REFERENCE_RECEIPTS)) {
        if (receipt.arguments.length > 8_192 || receipt.result.orEmpty().length > 32_768) continue
        val args = runCatching { Json.parseToJsonElement(receipt.arguments) as? JsonObject }.getOrNull() ?: continue
        val result = runCatching { Json.parseToJsonElement(receipt.result.orEmpty()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val repositoryBase = repositoryBase(args, result)
        val branchValues = (
            fieldValues(args, setOf("branch", "ref", "head", "base", "source_ref")) +
                fieldValues(result, setOf("branch", "ref", "head_ref", "base_ref", "source_ref"))
            )
            .map { it.removePrefix("refs/heads/") }
            .filter { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}")) }
        branchValues.forEach { branch -> add("branch:$branch", repositoryBase) }

        val commitValues = mutableSetOf<String>()
        val commitFields = setOf("commit_sha", "source_commit_sha", "expected_head_sha", "head_sha", "sha", "tree_sha")
        (fieldValues(args, commitFields) + fieldValues(result, commitFields)).filterTo(commitValues) { sha.matches(it) }
        commitValues.forEach { commit -> add("commit:${commit.lowercase()}", repositoryBase) }

        val isPr = receipt.toolName.substringAfterLast("__").equals("github_pr", true) || receipt.toolName.contains("github_pr", true)
        val prNumber = if (isPr) {
            (fieldValues(args, setOf("pull_number", "pr_number")) + fieldValues(result, setOf("number", "pull_number", "pr_number")))
                .firstOrNull { it.matches(Regex("[1-9][0-9]{0,8}")) }
        } else {
            null
        }
        prNumber?.let { add("pr:$it", repositoryBase) }
    }
    return copy(receiptUrlsByReference = links)
}

private fun encodeRepositoryPath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
private fun encodeRepositoryRef(value: String): String = value.split('/').joinToString("/") { encodeRepositoryPath(it) }

internal val LocalGitHubAssistantReferences = staticCompositionLocalOf<GitHubAssistantReferenceContext?> { null }

/** Linkify repository references in prose while leaving fenced code, existing links and URLs intact. */
internal fun linkifyGitHubAssistantReferences(markdown: String, context: GitHubAssistantReferenceContext): String {
    val fallbackRepositoryUrl = context.repositoryUrl ?: return markdown
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
    fun repositoryUrl(type: String, value: String): String? = if ("$type:$value" in context.receiptUrlsByReference) {
        context.receiptUrlsByReference["$type:$value"]
    } else {
        fallbackRepositoryUrl
    }
    fun link(label: String, url: String) = "[$label]($url)"
    fun makePlainLinks(input: String): String {
        var result = input
        val savedLinks = mutableListOf<String>()
        fun hold(value: String): String = "__GITHUB_ASSISTANT_LINK_${savedLinks.size}__".also { savedLinks += value }
        result = pullRequest.replace(result) { match ->
            val number = match.groupValues[1].drop(1)
            val url = repositoryUrl("pr", number)
            if (url == null) match.value else match.value.replace(match.groupValues[1], hold(link("#$number", "$url/pull/$number")))
        }
        result = branchWord.replace(result) { match ->
            val ref = match.groupValues[1].trimEnd('.', ',', ':', ';')
            val token = match.groupValues[1]
            if (ref.isBlank() || ref.lowercase() in setOf("the", "a", "an", "is", "was", "will", "should", "can", "could", "to", "from", "on", "currently", "still", "being", "used")) {
                match.value
            } else {
                val url = repositoryUrl("branch", ref)
                if (url == null) match.value else match.value.replace(token, hold(link(ref, "$url/tree/${encodeRef(ref)}") + token.drop(ref.length)))
            }
        }
        result = namedBeforeBranch.replace(result) { match ->
            val ref = match.value
            val url = repositoryUrl("branch", ref)
            if (url == null) match.value else hold(link(ref, "$url/tree/${encodeRef(ref)}"))
        }
        result = knownBranch.replace(result) { match ->
            val raw = match.value
            val ref = raw.trimEnd('.', ',', ':', ';', '!')
            val url = repositoryUrl("branch", ref)
            if (url == null) raw else hold(link(ref, "$url/tree/${encodeRef(ref)}") + raw.drop(ref.length))
        }
        if (activeBranch != null) {
            val activeBranchPattern = Regex("(?<![A-Za-z0-9._/-])${Regex.escape(activeBranch)}(?![A-Za-z0-9/_-])(?!\\.[A-Za-z0-9])")
            result = activeBranchPattern.replace(result) { match ->
                val url = repositoryUrl("branch", activeBranch)
                if (url == null) match.value else hold(link(match.value, "$url/tree/${encodeRef(activeBranch)}"))
            }
        }
        result = fullCommit.replace(result) { match ->
            val commit = match.value
            val url = repositoryUrl("commit", commit.lowercase())
            if (url == null) commit else hold(link(commit, "$url/commit/$commit"))
        }
        result = shortCommit.replace(result) { match ->
            val commit = match.value
            if (hasCommitLabelBefore(result.substring(0, match.range.first))) {
                val url = repositoryUrl("commit", commit.lowercase())
                if (url == null) commit else hold(link(commit, "$url/commit/$commit"))
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
                        isBranch && ref.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")) && repositoryUrl("branch", ref) != null -> link("`$ref`", "${repositoryUrl("branch", ref)}/tree/${encodeRef(ref)}")
                        isCommit && repositoryUrl("commit", ref.lowercase()) != null -> link("`$ref`", "${repositoryUrl("commit", ref.lowercase())}/commit/$ref")
                        pr && ref.matches(Regex("#\\d+")) && repositoryUrl("pr", ref.drop(1)) != null -> link("`$ref`", "${repositoryUrl("pr", ref.drop(1))}/pull/${ref.drop(1)}")
                        knownBranch.matches(ref) && repositoryUrl("branch", ref) != null -> link("`$ref`", "${repositoryUrl("branch", ref)}/tree/${encodeRef(ref)}")
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
