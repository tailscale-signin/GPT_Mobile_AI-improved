package dev.chungjungsoo.gptmobile.data.github

import java.net.URI

/** Normalize a full repository reference without overriding a conflicting explicit owner. */
internal fun normalizeGitHubRepository(owner: String?, repo: String?): Pair<String?, String?> {
    val cleanOwner = owner?.trim()?.takeIf { it.isNotEmpty() }
    val input = repo?.trim()?.takeIf { it.isNotEmpty() } ?: return cleanOwner to null
    val fullName = if (input.startsWith("https://")) {
        val uri = URI(input)
        require(uri.host == "github.com" && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null) {
            "Use a github.com repository URL without credentials, query or fragment."
        }
        uri.path.trim('/')
    } else {
        input
    }
    val parts = fullName.split('/')
    if (parts.size == 1) return cleanOwner to input.removeSuffix(".git")
    require(parts.size == 2 && parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) {
        "Use owner and repository name separately, or repo='owner/name'."
    }
    require(cleanOwner == null || cleanOwner.equals(parts[0], ignoreCase = true)) { "Repository reference conflicts with the supplied owner." }
    return (cleanOwner ?: parts[0]) to parts[1].removeSuffix(".git")
}
