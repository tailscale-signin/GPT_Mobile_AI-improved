package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDocumentationTest {
    @Test fun documentationIndexRanksGroundingFilesPaginatesAndExcludesSymlinks() {
        val tree = Json.parseToJsonElement(
            """{"truncated":true,"tree":[
            {"path":"docs/guide.md","type":"blob","mode":"100644","sha":"guide"},
            {"path":"llms-full.txt","type":"blob","mode":"100644","sha":"full"},
            {"path":"README.md","type":"blob","mode":"100644","sha":"readme"},
            {"path":"llms.txt","type":"blob","mode":"100644","sha":"llms"},
            {"path":"docs/link.md","type":"blob","mode":"120000","sha":"link"},
            {"path":"docs/logo.png","type":"blob","mode":"100644","sha":"image"},
            {"path":"src/code.kt","type":"blob","mode":"100644","sha":"code"}
        ]}"""
        ).jsonObject
        val first = GitHubRepositoryIndex.documentation(tree, 1, 2)
        assertEquals(listOf("llms.txt", "README.md"), first["documents"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        assertEquals("4", first["total_indexed"]!!.jsonPrimitive.content)
        assertEquals("true", first["truncated"]!!.jsonPrimitive.content)
        assertEquals("true", first["has_more"]!!.jsonPrimitive.content)
        val second = GitHubRepositoryIndex.documentation(tree, 2, 2)
        assertEquals(listOf("llms-full.txt", "docs/guide.md"), second["documents"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        assertEquals("false", second["has_more"]!!.jsonPrimitive.content)
    }

    @Test fun docsDiscoveryUsesSelectedBranchAndReturnsCommitPinnedReadInstructions() = runTest {
        val commit = "a".repeat(40)
        val tree = "b".repeat(40)
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                assertEquals("api.github.com", request.url.host)
                paths += request.url.encodedPath
                val response = when (paths.size) {
                    1 -> """{"sha":"$commit","commit":{"tree":{"sha":"$tree"}}}"""
                    2 -> """{"sha":"$tree","truncated":false,"tree":[{"path":"README.md","type":"blob","mode":"100644","sha":"doc"}]}"""
                    else -> error("Unexpected request")
                }
                respond(response, HttpStatusCode.OK)
            }
        )
        try {
            val result = GitHubWorkspaceClient("private-token", http).execute(
                "repo_docs",
                buildJsonObject {
                    put("owner", "owner")
                    put("repo", "repo")
                    put("ref", "feature")
                }
            ).jsonObject
            assertEquals(listOf("/repos/owner/repo/commits/feature", "/repos/owner/repo/git/trees/$tree"), paths)
            assertEquals(commit, result["commit_sha"]!!.jsonPrimitive.content)
            assertEquals("feature", result["ref"]!!.jsonPrimitive.content)
            assertTrue(result["read_with"]!!.jsonPrimitive.content.contains("ref=$commit"))
        } finally {
            http.close()
        }
    }
}
