package dev.chungjungsoo.gptmobile.data.agent.tool

/**
 * Implementations must authenticate to GitHub and return check receipts for
 * the exact requested commit. No synthesized success receipts are permitted.
 */
internal interface GitHubEngineeringCiReceiptGateway {
    suspend fun fetch(
        repositoryOwner: String,
        repositoryName: String,
        commitSha: String,
    ): List<EngineeringCheckReceipt>
}

internal sealed interface EngineeringCiInspection {
    data class Verified(val verdict: EngineeringCiVerdict) : EngineeringCiInspection
    data class Unavailable(val reason: String) : EngineeringCiInspection
}

internal class GitHubEngineeringCiInspector(
    private val gateway: GitHubEngineeringCiReceiptGateway,
) {
    suspend fun inspect(
        owner: String,
        repository: String,
        sha: String,
        requiredChecks: Set<String>,
    ): EngineeringCiInspection = try {
        EngineeringCiInspection.Verified(
            GitHubEngineeringCiPolicy.evaluate(sha, requiredChecks, gateway.fetch(owner, repository, sha)),
        )
    } catch (error: Exception) {
        EngineeringCiInspection.Unavailable(error.javaClass.simpleName)
    }
}
