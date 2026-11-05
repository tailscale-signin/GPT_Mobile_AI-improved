package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStageBudgetTest {
    @Test fun compactReviewDoesNotConsumeTheFinalClaimRenderingAllowance() {
        assertEquals(512, GitHubStageBudget.resolve(32768, null, "github-review-preflight").output)
        assertEquals(1536, GitHubStageBudget.resolve(32768, null, "github-review").output)
        assertEquals(4096, GitHubStageBudget.resolve(32768, null, "github-review-repair").output)
        assertEquals(4096, GitHubStageBudget.resolve(32768, null, "github-claim-extraction").output)
    }

    @Test fun everyStageHonorsSelectedProfileCapAndLeavesInputOutputAndSafetyWithinContext() {
        for (context in listOf(0, 512, 2048, 8192, 32768)) {
            for (role in listOf("github-review", "github-review-preflight", "github-review-repair", "github-claim-extraction")) {
                val budget = GitHubStageBudget.resolve(context, 256, role)
                assertTrue(budget.output <= 256)
                assertTrue(budget.input >= 0 && budget.output >= 0)
                assertTrue(budget.input + budget.output <= context)
                if (context >= 2048) assertTrue(budget.input + budget.output + 1536 <= context)
            }
        }
    }
}
