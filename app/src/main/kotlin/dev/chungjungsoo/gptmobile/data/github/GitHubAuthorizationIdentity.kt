package dev.chungjungsoo.gptmobile.data.github

/** Credentials and permission revisions are deliberately absent from durable write identity. */
internal object GitHubAuthorizationIdentity {
    fun scope(accountId: String, connectionUid: String, installation: String = "", host: String = "api.github.com"): String {
        require(accountId.isNotBlank() && connectionUid.isNotBlank())
        return GitHubRepositoryStateStore.digest(listOf(host.lowercase(), accountId, connectionUid, installation).joinToString("\u0000"))
    }

    fun permission(authType: String, policy: String, readTools: String, endpoint: String?): String =
        GitHubRepositoryStateStore.digest(listOf(authType, policy, readTools, endpoint.orEmpty()).joinToString("\u0000"))
}
