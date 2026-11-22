package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubComparisonCoverageTest {
    private val base = "a".repeat(40)
    private val head = "b".repeat(40)
    private fun arguments(page: Int) = buildJsonObject {
        put("action", "changes")
        put("base_sha", base)
        put("commit_sha", head)
        put("page", page)
    }
    private fun call(arguments: JsonObject) = buildJsonObject {
        put("tool", "github_context")
        put("arguments", arguments)
    }
    private fun row(arguments: JsonObject, result: JsonObject, binding: String = "task") = buildJsonObject {
        put("binding", binding)
        put("repository", "owner/repo")
        put("tool", "github_context")
        put("trusted", true)
        put("success", true)
        put("arguments", arguments)
        put("result", result)
    }
    private fun rows() = linkedMapOf<String, JsonObject>().apply {
        for (page in 1..4) {
            put(
                "p$page",
                row(
                    arguments(page),
                    buildJsonObject {
                        put("base_sha", base)
                        put("head_sha", head)
                        put("page", page)
                        put("has_more", page < 4)
                        if (page < 4) put("continuation_call", call(JsonObject(arguments(page + 1) + mapOf("per_page" to JsonPrimitive(100), "include_patches" to JsonPrimitive(false)))))
                    }
                )
            )
        }
    }
    private fun coverage(rows: Map<String, JsonObject>) = requireNotNull(
        GitHubComparisonCoverage.observe(rows) {
            val args = it["arguments"] as? JsonObject
            it["binding"] == JsonPrimitive("task") && args?.get("action") == JsonPrimitive("changes") && args["base_sha"] == JsonPrimitive(base) && args["commit_sha"] == JsonPrimitive(head)
        }
    )

    @Test fun repeatedFirstPageKeepsCompleteCoverageAndNormalizesNativeDefaults() {
        val rows = rows()
        val before = coverage(rows)
        assertTrue(before.complete)
        rows["repeat"] = rows.getValue("p1")
        val after = coverage(rows.toMap())
        assertTrue(after.complete)
        assertTrue(after.pending.isEmpty())
        assertEquals(before.identity, after.identity)
        assertEquals(before.generation, after.generation)
        rows["explicit-repository"] = JsonObject(rows.getValue("p1") + ("arguments" to JsonObject(arguments(1) + mapOf("owner" to JsonPrimitive("OWNER"), "repo" to JsonPrimitive("repo")))))
        val explicit = coverage(rows)
        assertTrue(explicit.complete)
        assertEquals(before.identity, explicit.identity)
    }

    @Test fun missingPageOrForeignAccountCannotSatisfyExactTraversal() {
        for (foreign in listOf(false, true)) {
            val rows = rows()
            val missing = rows.remove("p2")!!
            if (foreign) rows["foreign"] = JsonObject(missing + ("binding" to JsonPrimitive("foreign")))
            val observed = coverage(rows)
            assertFalse(observed.complete)
            assertEquals(1, observed.pending.size)
        }
    }

    @Test fun explicitRefreshAndChangedQueryCannotBorrowPreviousCompletion() {
        for (change in listOf("verify_live" to JsonPrimitive(true), "include_patches" to JsonPrimitive(true), "owner" to JsonPrimitive("foreign"))) {
            val rows = rows()
            val before = coverage(rows)
            val old = rows.getValue("p1")
            rows["new"] = JsonObject(old + ("arguments" to JsonObject(arguments(1) + change)))
            val after = coverage(rows)
            assertFalse(after.complete)
            assertTrue(before.identity != after.identity || before.generation != after.generation)
        }
    }

    @Test fun exactOpaqueCursorDescendantCompletesButAlteredCursorDoesNot() {
        val cursor = buildJsonObject { put("view_cursor", "bound:2:8192") }
        val first = row(
            arguments(1),
            buildJsonObject {
                put("base_sha", base)
                put("head_sha", head)
                put("has_more", false)
                put("local_page_complete", false)
                put("continuation_call", call(cursor))
            }
        )
        val last = row(cursor, buildJsonObject { put("local_page_complete", true) })
        val altered = JsonObject(last + ("arguments" to buildJsonObject { put("view_cursor", "altered:2:8192") }))
        assertFalse(coverage(mapOf("root" to first, "altered" to altered)).complete)
        assertTrue(coverage(mapOf("root" to first, "last" to last)).complete)
    }

    @Test fun legacyRequestedSizeKeepsActualHundredRowIdentityButEffectiveSizesStaySeparate() {
        val receipts = rows()
        val legacy = coverage(receipts)
        val first = receipts.getValue("p1")
        val requested = JsonObject(arguments(1) + ("per_page" to JsonPrimitive(50)))
        receipts["legacy-repeat"] = JsonObject(first + ("arguments" to requested))
        assertTrue(coverage(receipts).complete)
        assertEquals(legacy.identity, coverage(receipts).identity)
        val sizedResult = JsonObject((first["result"] as JsonObject) + ("effective_per_page" to JsonPrimitive(50)))
        receipts["sized"] = JsonObject(first + mapOf("arguments" to requested, "result" to sizedResult))
        val current = coverage(receipts)
        assertFalse(current.complete)
        assertNotEquals(legacy.identity, current.identity)
        assertEquals(setOf("sized"), current.receipts.keys)
    }

    @Test fun invalidEffectivePageSizeCannotQualifyAContinuation() {
        val receipts = rows()
        val first = receipts.getValue("p1")
        val result = first["result"] as JsonObject
        for (invalid in listOf(JsonPrimitive(0), JsonPrimitive(101), JsonPrimitive("50"))) {
            val broken = JsonObject(first + ("result" to JsonObject(result + ("effective_per_page" to invalid))))
            assertFalse(coverage(receipts + ("invalid" to broken)).complete)
            assertFalse(GitHubTestChecklist.sameCall(broken, call(arguments(1)), first))
        }
    }

    @Test fun immutableRevisionAndReportedInconsistencyRemainSeparateCoverage() {
        val rows = rows()
        val before = coverage(rows)
        val first = rows.getValue("p1")
        val result = first["result"] as JsonObject
        rows["inconsistent"] = JsonObject(first + ("result" to JsonObject(result + ("pagination_progress" to buildJsonObject { put("upstream_changed_during_traversal", true) }))))
        assertFalse(coverage(rows).complete)
        val changedHead = JsonObject(first + ("arguments" to JsonObject(arguments(1) + ("commit_sha" to JsonPrimitive("c".repeat(40))))))
        val other = requireNotNull(GitHubComparisonCoverage.observe(mapOf("changed" to changedHead)) { true })
        assertNotEquals(before.identity, other.identity)
        assertFalse(other.complete)
    }
}
