package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubMergeSafetyTest {
    @Test fun unavailableOrBlockedRulesNeverReachMergeMutation() = runTest {
        val head = "a".repeat(40)
        for (health in listOf(
            """{"errors":[{"message":"permission denied"}]}""",
            """{"data":{"repository":{"pullRequest":{"state":"OPEN","isDraft":false,"headRefOid":"$head","baseRefOid":"${"b".repeat(40)}","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"BLOCKED"}}}}"""
        )) {
            var mergeCalled = false
            val http = HttpClient(
                MockEngine { request ->
                    when {
                        request.url.encodedPath == "/graphql" -> respond(health)
                        request.method == HttpMethod.Put -> {
                            mergeCalled = true
                            respond("""{"merged":true}""")
                        }
                        else -> respond("""{"state":"open","draft":false,"head":{"sha":"$head"}}""")
                    }
                }
            )
            try {
                val result = runCatching {
                    GitHubWorkspaceClient("token", http).execute(
                        "merge_pull_request",
                        buildJsonObject {
                            put("owner", "owner")
                            put("repo", "repo")
                            put("pull_number", 758)
                            put("expected_head_sha", head)
                        }
                    )
                }
                assertTrue(result.isFailure)
                assertFalse(mergeCalled)
            } finally {
                http.close()
            }
        }
    }
}
