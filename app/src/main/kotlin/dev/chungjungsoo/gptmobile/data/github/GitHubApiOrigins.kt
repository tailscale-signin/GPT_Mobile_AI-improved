package dev.chungjungsoo.gptmobile.data.github

import java.net.URI

/** Fixed, credential-scoped API/auth origins; remote paging never chooses a new host. */
@ConsistentCopyVisibility
data class GitHubApiOrigins private constructor(val rest: String, val graphQl: String, val authorization: String) {
    val hostIdentity: String get() = URI(authorization).rawAuthority.lowercase()

    fun acceptsApiUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val expected = URI(rest)
        uri.scheme == "https" &&
            uri.rawAuthority == expected.rawAuthority &&
            uri.userInfo == null &&
            uri.fragment == null &&
            (uri.rawPath == expected.rawPath || uri.rawPath.startsWith(expected.rawPath.trimEnd('/') + "/"))
    }.getOrDefault(false)

    companion object {
        val GITHUB_COM = GitHubApiOrigins("https://api.github.com", "https://api.github.com/graphql", "https://github.com")

        fun fromEndpoint(value: String?): GitHubApiOrigins {
            if (value.isNullOrBlank()) return GITHUB_COM
            val uri = URI(value.trim().trimEnd('/'))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)) { "GitHub requires a fixed HTTPS API origin without credentials or query parameters." }
            if (uri.host.lowercase() in setOf("github.com", "api.github.com")) {
                require(uri.path.isNullOrEmpty()) { "Use the GitHub API origin, not a repository URL." }
                require(uri.port in setOf(-1, 443)) { "GitHub.com requires its standard HTTPS origin." }
                return GITHUB_COM
            }
            require(uri.path in setOf("", "/api/v3")) { "Enterprise GitHub API must use its host or /api/v3 root." }
            val origin = URI("https", null, uri.host.lowercase(), uri.port.takeIf { it != 443 } ?: -1, null, null, null).toASCIIString()
            return GitHubApiOrigins("$origin/api/v3", "$origin/api/graphql", origin)
        }
    }
}
