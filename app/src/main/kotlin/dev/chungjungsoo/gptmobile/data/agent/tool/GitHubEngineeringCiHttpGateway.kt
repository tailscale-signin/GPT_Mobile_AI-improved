package dev.chungjungsoo.gptmobile.data.agent.tool

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Caller supplies a short-lived credential; credentials are never persisted here. */
internal class GitHubEngineeringCiHttpGateway(
    private val tokenProvider: suspend () -> String?,
) : GitHubEngineeringCiReceiptGateway {
    override suspend fun fetch(
        repositoryOwner: String,
        repositoryName: String,
        commitSha: String,
    ): List<EngineeringCheckReceipt> = withContext(Dispatchers.IO) {
        require(repositoryOwner.matches(Regex("[A-Za-z0-9-]+")))
        require(repositoryName.matches(Regex("[A-Za-z0-9._-]+")))
        require(commitSha.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")))
        val token = requireNotNull(tokenProvider()?.takeIf { it.isNotBlank() }) {
            "GitHub authentication unavailable"
        }
        val url = URL(
            "https://api.github.com/repos/$repositoryOwner/$repositoryName/commits/$commitSha/check-runs?per_page=100",
        )
        val connection = (url.openConnection() as HttpURLConnection)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            check(connection.responseCode == 200) { "GitHub check-run request failed" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(body)
            check(root.has("total_count") && root.has("check_runs")) {
                "Missing GitHub check-run collection fields"
            }
            check(root.optInt("total_count", 0) <= root.getJSONArray("check_runs").length()) {
                "Incomplete GitHub check-run pagination"
            }
            GitHubEngineeringCiCheckParser.parse(body, commitSha)
        } finally {
            connection.disconnect()
        }
    }
}
