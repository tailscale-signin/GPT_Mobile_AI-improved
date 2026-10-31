package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCommitDetailTest {
    private val sha = "a".repeat(40)
    private fun result(count: Int, patch: String? = null) = buildJsonObject {
        put("sha", sha)
        put(
            "files",
            JsonArray(
                (1..count).map { index ->
                    buildJsonObject {
                        put("filename", "src/$index.kt")
                        put("status", "modified")
                        put("sha", "b".repeat(40))
                        patch?.let { put("patch", it) }
                    }
                }
            )
        )
    }

    @Test fun largeCommitReturnsEveryFileSummaryWithoutRetainingItsPatches() {
        val page = GitHubCommitDetail.present(result(100, "x".repeat(20_000)), 1, 100, false)
        assertEquals(100, page["files"]!!.jsonArray.size)
        assertTrue(page["files"]!!.jsonArray.all { "patch" !in it.jsonObject })
        assertTrue(page.toString().toByteArray().size < 32 * 1024)
        val next = page["continuation"]!!.jsonObject
        assertEquals(JsonPrimitive(100), next["per_page"])
        assertEquals(JsonPrimitive(2), next["page"])
        assertEquals(JsonPrimitive(false), next["include_patches"])
        assertEquals(JsonPrimitive(sha), next["commit_sha"])
    }

    @Test fun optedInPatchesRespectTotalAndIndividualUtf8Budgets() {
        val patch = "🍁\n".repeat(3000)
        val page = GitHubCommitDetail.present(result(30, patch), 2, 30, true)
        val files = page["files"]!!.jsonArray.map { it.jsonObject }
        val excerpts = files.map { it["patch"]!!.jsonPrimitive.content }
        assertEquals(30, files.size)
        assertTrue(excerpts.sumOf { it.toByteArray().size } <= 32 * 1024)
        assertTrue(excerpts.all { it.toByteArray().size <= 2000 && patch.startsWith(it) })
        assertTrue(files.all { it["patch_truncated"] == JsonPrimitive(true) })
        assertEquals(JsonPrimitive(true), page["continuation"]!!.jsonObject["include_patches"])
    }

    @Test fun missingAndCompletePatchesHaveDistinctCoverageSignals() {
        val missing = GitHubCommitDetail.present(result(1), 1, 30, true)
        assertEquals(JsonPrimitive(true), missing["files"]!!.jsonArray.single().jsonObject["patch_unavailable"])
        val complete = GitHubCommitDetail.present(result(1, "small"), 1, 30, true)
        assertEquals(JsonPrimitive(false), complete["files"]!!.jsonArray.single().jsonObject["patch_truncated"])
        assertEquals(JsonPrimitive(false), complete["has_more"])
        assertFalse("continuation" in complete)
        val empty = GitHubCommitDetail.present(JsonObject(mapOf("sha" to JsonPrimitive(sha))), 2, 30, false)
        assertEquals(0, empty["files"]!!.jsonArray.size)
    }
}
