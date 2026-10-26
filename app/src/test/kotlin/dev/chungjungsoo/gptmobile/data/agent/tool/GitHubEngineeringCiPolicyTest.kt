package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubEngineeringCiPolicyTest {
    private val sha = "a".repeat(40)
    private val required = setOf("unit-tests", "android-build")

    private fun success(name: String, head: String = sha) = EngineeringCheckReceipt(
        name,
        EngineeringCheckStatus.COMPLETED,
        EngineeringCheckConclusion.SUCCESS,
        head,
    )

    @Test
    fun noRequiredChecksIsNotSuccess() {
        assertEquals(EngineeringCiVerdict.PENDING, GitHubEngineeringCiPolicy.evaluate(sha, emptySet(), emptyList()))
    }

    @Test
    fun missingCheckIsPending() {
        assertEquals(
            EngineeringCiVerdict.PENDING,
            GitHubEngineeringCiPolicy.evaluate(sha, required, listOf(success("unit-tests"))),
        )
    }

    @Test
    fun allRequiredChecksPass() {
        assertEquals(
            EngineeringCiVerdict.PASSED,
            GitHubEngineeringCiPolicy.evaluate(sha, required, listOf(success("unit-tests"), success("android-build"))),
        )
    }

    @Test
    fun wrongCommitDoesNotPass() {
        assertEquals(
            EngineeringCiVerdict.PENDING,
            GitHubEngineeringCiPolicy.evaluate(sha, required, listOf(success("unit-tests"), success("android-build", "b".repeat(40)))),
        )
    }

    @Test
    fun failureIsNotIgnored() {
        val failed = EngineeringCheckReceipt(
            "android-build",
            EngineeringCheckStatus.COMPLETED,
            EngineeringCheckConclusion.FAILURE,
            sha,
        )
        assertEquals(
            EngineeringCiVerdict.FAILED,
            GitHubEngineeringCiPolicy.evaluate(sha, required, listOf(success("unit-tests"), failed)),
        )
    }
}
