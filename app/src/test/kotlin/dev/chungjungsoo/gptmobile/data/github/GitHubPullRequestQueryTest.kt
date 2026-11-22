package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPullRequestQueryTest {
    @Test fun branchFiltersSurviveEncodingAndPaginationIdentity() {
        val arguments = Json.parseToJsonElement("""{"action":"list","state":"open","head":"contributor:feature/issue&state=closed","base":"release/2026+stable","sort":"updated","direction":"asc","per_page":1}""").jsonObject
        val parameters = GitHubPullRequestQuery.parameters(arguments)
        assertEquals(arguments["head"], parameters["head"])
        assertEquals(arguments["base"], parameters["base"])
        assertTrue(GitHubPullRequestQuery.encode(parameters).contains("head=contributor%3Afeature%2Fissue%26state%3Dclosed"))
        assertTrue(GitHubPullRequestQuery.encode(parameters).contains("base=release%2F2026%2Bstable"))
        val next = JsonObject(parameters + mapOf("action" to JsonPrimitive("list_pull_requests"), "page" to JsonPrimitive(2)))
        assertEquals(GitHubInventoryQuery.arguments("github_pr", arguments), GitHubInventoryQuery.arguments("github_pr", next))
        assertNotEquals(GitHubInventoryQuery.arguments("github_pr", arguments), GitHubInventoryQuery.arguments("github_pr", JsonObject(next + ("head" to JsonPrimitive("another:branch")))))
    }

    @Test fun omittedDefaultsAndExplicitDefaultsDescribeTheSameCollection() {
        for (sort in listOf("created", "updated", "popularity", "long-running")) {
            val arguments = Json.parseToJsonElement("""{"action":"list","sort":"$sort"}""").jsonObject
            val parameters = GitHubPullRequestQuery.parameters(arguments)
            assertEquals(JsonPrimitive(if (sort == "created") "desc" else "asc"), parameters["direction"])
            assertEquals(GitHubInventoryQuery.arguments("github_pr", arguments), GitHubInventoryQuery.arguments("github_pr", JsonObject(parameters + ("action" to JsonPrimitive("list")))))
            val descending = GitHubPullRequestQuery.parameters(JsonObject(arguments + ("direction" to JsonPrimitive("desc"))))
            assertEquals(JsonPrimitive("desc"), descending["direction"])
        }
    }

    @Test fun malformedFiltersAndPaginationAreRejectedBeforeAnHttpQueryExists() {
        for (input in listOf(
            """{"head":""}""",
            """{"base":" main"}""",
            """{"head":42}""",
            """{"sort":"comments"}""",
            """{"direction":"descending"}""",
            """{"page":"2"}""",
            """{"page":0}""",
            """{"per_page":101}"""
        )) {
            assertTrue(input, runCatching { GitHubPullRequestQuery.parameters(Json.parseToJsonElement(input).jsonObject) }.isFailure)
        }
    }
}
