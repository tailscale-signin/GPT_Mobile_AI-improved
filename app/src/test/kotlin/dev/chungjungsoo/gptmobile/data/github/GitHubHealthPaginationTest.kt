package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubHealthPaginationTest {
    private fun page(id: String, conclusion: String, more: Boolean, attempt: Int = 1): JsonObject = Json.parseToJsonElement(
        """{"data":{"repository":{"pullRequest":{"headRefOid":"head","baseRefOid":"base","commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"SUCCESS","contexts":{"totalCount":2,"pageInfo":{"hasNextPage":$more,"endCursor":"cursor"},"nodes":[{"__typename":"CheckRun","id":"$id","name":"Required verification","isRequired":true,"status":"COMPLETED","conclusion":"$conclusion","checkSuite":{"workflowRun":{"runAttempt":$attempt}}}]}}}}]}}}}}"""
    ) as JsonObject

    @Test fun finalRefreshCannotReplaceChangedRequiredCheckWithOlderSuccess() = runBlocking {
        val first = page("one", "SUCCESS", true)
        val second = page("two", "SUCCESS", false)
        for (changed in listOf("SKIPPED", "CANCELLED", "FAILURE")) {
            val refreshed = page("one", changed, true)
            var reads = 0
            val result = collectGitHubHealth { listOf(first, second, refreshed)[reads++] }
            assertEquals(3, reads)
            assertEquals(refreshed, result)
        }
    }

    @Test fun finalRefreshCannotReplaceChangedWorkflowAttemptWithOldEvidence() = runBlocking {
        val first = page("one", "SUCCESS", true)
        val second = page("two", "SUCCESS", false)
        val refreshed = page("one", "SUCCESS", true, attempt = 2)
        var reads = 0
        assertEquals(refreshed, collectGitHubHealth { listOf(first, second, refreshed)[reads++] })
        assertEquals(3, reads)
    }

    @Test fun sameDisplayNameOnDifferentPagesRetainsBothProviderIdentities() = runBlocking {
        val first = page("one", "SUCCESS", true)
        val second = page("two", "FAILURE", false)
        var reads = 0
        val result = collectGitHubHealth { listOf(first, second, first)[reads++] }
        val commits = result.getValue("data").jsonObject.getValue("repository").jsonObject.getValue("pullRequest").jsonObject.getValue("commits").jsonObject.getValue("nodes") as JsonArray
        val nodes = commits.single().jsonObject.getValue("commit").jsonObject.getValue("statusCheckRollup").jsonObject.getValue("contexts").jsonObject.getValue("nodes") as JsonArray
        assertEquals(listOf("one", "two"), nodes.map { it.jsonObject.getValue("id").toString().trim('"') })
    }

    @Test fun anIntermediateGraphQlErrorCannotDisappearDuringAggregation() = runBlocking {
        val first = page("one", "SUCCESS", true)
        val failed = JsonObject(page("two", "SUCCESS", false) + ("errors" to Json.parseToJsonElement("""[{"message":"denied"}]""")))
        var reads = 0
        assertEquals(failed, collectGitHubHealth { listOf(first, failed)[reads++] })
        assertEquals(2, reads)
    }
}
