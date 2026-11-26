package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPullFileNavigationTest {
    private val head = "b".repeat(40)
    private val base = "a".repeat(40)

    private fun files(start: Int, end: Int) = JsonArray(
        (start until end).map { index ->
            buildJsonObject {
                put("filename", "file/$index")
                put("sha", "c".repeat(40))
                if (index == 415) {
                    put("status", "renamed")
                    put("previous_filename", "old-name")
                }
            }
        }
    )

    @Test fun exactCompareUsesAll416PrFilesAndPreservesTheRename() = runTest {
        val pages = mutableListOf<Int>()
        val metadata = """{"number":2599,"changed_files":416,"head":{"sha":"$head"},"base":{"sha":"$base"}}"""
        val http = HttpClient(
            MockEngine { request ->
                val page = request.url.parameters["page"]?.toInt() ?: 1
                when {
                    request.url.encodedPath.contains("/compare/") -> respond(if (page == 1) """{"status":"ahead","files":${files(0, 300)}}""" else """{"status":"ahead"}""")
                    request.url.encodedPath.endsWith("/commits/$head/pulls") -> respond("[$metadata]")
                    request.url.encodedPath.endsWith("/pulls/2599") -> respond(metadata)
                    request.url.encodedPath.endsWith("/pulls/2599/files") -> {
                        pages += page
                        respond(files((page - 1) * 100, minOf(page * 100, 416)).toString())
                    }
                    else -> error(request.url.toString())
                }
            }
        )
        try {
            val client = GitHubWorkspaceClient("test", http)
            val repo = mapOf("owner" to JsonPrimitive("owner"), "repo" to JsonPrimitive("repo"))
            var args = buildJsonObject {
                repo.forEach { (key, value) -> put(key, value) }
                put("base", base)
                put("head", head)
                put("per_page", 100)
            }
            repeat(5) { index ->
                val result = client.execute("compare_refs", args).jsonObject
                val report = result.getValue("changed_file_coverage").jsonObject
                assertEquals(JsonPrimitive(minOf(416, (index + 1) * 100)), report["observed_unique"])
                assertEquals(JsonPrimitive(index == 4), report["complete"])
                if (index < 4) {
                    args = JsonObject(repo + result.getValue("continuation").jsonObject)
                } else {
                    assertTrue(result.getValue("files").toString().contains("old-name"))
                    assertFalse("continuation" in result)
                }
            }
            assertEquals((1..5).toList(), pages)
        } finally {
            http.close()
        }
    }
}
