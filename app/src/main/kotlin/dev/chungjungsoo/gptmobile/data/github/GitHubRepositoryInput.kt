package dev.chungjungsoo.gptmobile.data.github

import java.net.URI

/** Normalize a full repository reference without overriding a conflicting explicit owner. */
internal fun normalizeGitHubRepository(owner: String?, repo: String?): Pair<String?, String?> {
    val cleanOwner = owner?.trim()?.takeIf { it.isNotEmpty() }
    fun safePart(value: String) = value.matches(Regex("[A-Za-z0-9_.-]+")) && value !in setOf(".", "..")
    require(cleanOwner == null || safePart(cleanOwner)) { "owner must be one GitHub account name, without paths, credentials or query parameters." }
    val input = repo?.trim()?.takeIf { it.isNotEmpty() } ?: return cleanOwner to null
    val fullName = if (input.startsWith("https://", ignoreCase = true)) {
        val uri = URI.create(input)
        require(uri.host.equals("github.com", ignoreCase = true) && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null && uri.rawPath == uri.path) {
            "Use a github.com repository URL without credentials, query or fragment."
        }
        uri.path.trim('/')
    } else {
        input
    }
    val parts = fullName.split('/')
    require(parts.size in 1..2 && parts.all(::safePart)) {
        "Use owner and repository name separately, or repo='owner/name'."
    }
    val name = parts.last().removeSuffix(".git")
    require(safePart(name)) { "repo must be a nonempty GitHub repository name." }
    if (parts.size == 1) return cleanOwner to name
    require(cleanOwner == null || cleanOwner.equals(parts[0], ignoreCase = true)) { "Repository reference conflicts with the supplied owner." }
    return (cleanOwner ?: parts[0]) to name
}

/** Branch inputs are names, never revision expressions or a different ref namespace. */
internal fun normalizeGitHubBranch(value: String): String {
    val branch = value.trim().removePrefix("refs/heads/")
    require(
        branch.isNotEmpty() &&
            !branch.startsWith("refs/") &&
            !branch.startsWith('-') &&
            branch != "HEAD" &&
            branch != "@" &&
            !branch.endsWith('.') &&
            ".." !in branch &&
            "@{" !in branch &&
            branch.none { it.code <= 32 || it.code == 127 || it in "~^:?*[\\" } &&
            branch.split('/').all { it.isNotEmpty() && !it.startsWith('.') && !it.endsWith(".lock") }
    ) { "Use a valid branch name or refs/heads/name; tags, revision expressions, whitespace and malformed refs cannot select a branch." }
    return branch
}
