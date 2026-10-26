package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEngineeringCiReceiptGatewayTest {
    private val sha = "a".repeat(40)

    @Test
    fun verifiedReceiptPasses() = runBlocking {
        val gateway = object : GitHubEngineeringCiReceiptGateway {
            override suspend fun fetch(
                repositoryOwner: String,
                repositoryName: String,
                commitSha: String,
            ) = listOf(
                EngineeringCheckReceipt(
                    "build",
                    EngineeringCheckStatus.COMPLETED,
                    EngineeringCheckConclusion.SUCCESS,
                    commitSha,
                ),
            )
        }
        val result = GitHubEngineeringCiInspector(gateway).inspect("owner", "repo", sha, setOf("build"))
        assertEquals(EngineeringCiInspection.Verified(EngineeringCiVerdict.PASSED), result)
    }

    @Test
    fun gatewayErrorNeverPasses() = runBlocking {
        val gateway = object : GitHubEngineeringCiReceiptGateway {
            override suspend fun fetch(
                repositoryOwner: String,
                repositoryName: String,
                commitSha: String,
            ): List<EngineeringCheckReceipt> = error("offline")
        }
        val result = GitHubEngineeringCiInspector(gateway).inspect("owner", "repo", sha, setOf("build"))
        assertTrue(result is EngineeringCiInspection.Unavailable)
    }
}
