package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubHealthPaginationTest {
    private fun page(id: String, conclusion: String, more: Boolean): JsonObject = Json.parseToJsonElement(
        """{"data":{"repository":{"pullRequest":{"headRefOid":"head","baseRefOid":"base","commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"SUCCESS","contexts":{"totalCount":2,"pageInfo":{"hasNextPage":$more,"endCursor":"cursor"},"nodes":[{"__typename":"CheckRun","id":"$id","name":"Required verification","isRequired":true,"status":"COMPLETED","conclusion":"$conclusion"}]}}}}]}}}}}"""
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
}
