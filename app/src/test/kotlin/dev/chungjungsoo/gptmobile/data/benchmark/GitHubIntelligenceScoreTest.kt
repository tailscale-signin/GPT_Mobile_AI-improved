package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubIntelligenceScoreTest {
    private val revision = "a".repeat(64)
    private fun response(partial: Boolean, claimedTotal: Double? = null): JsonObject {
        val rubric = linkedMapOf("task_completion" to 25, "code_correctness" to 20, "repository_understanding" to 15, "error_recovery" to 15, "context_retention" to 10, "tool_efficiency" to 5, "evidence_accuracy" to 5, "response_quality" to 5)
        return buildJsonObject {
            put("schema", "gateway.profile-intelligence-score.v1")
            put("profile_revision", revision)
            put(
                "report",
                buildJsonObject {
                    put("id", "qual_" + "1".repeat(32))
                    put("profile_uid", "selected")
                    put("model", "model")
                    put("base_sha", "b".repeat(40))
                    put(
                        "score",
                        buildJsonObject {
                            put("schema", "gateway.github-intelligence.v1")
                            put("pointsOutOf100", claimedTotal)
                            put(
                                "dimensions",
                                buildJsonObject {
                                    rubric.forEach { (name, weight) ->
                                        put(
                                            name,
                                            buildJsonObject {
                                                put("weight", weight)
                                                val missing = partial && name == "task_completion"
                                                put("observed", if (missing) 0 else 1)
                                                put("planned", 1)
                                                if (missing) put("points", JsonNull) else put("points", weight)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    @Test fun exactBindingRetainsEightDimensionsAndObservedZero() {
        val complete = requireNotNull(GitHubIntelligenceScore.read(response(false, 100.0), "selected", "model", revision))
        assertEquals(8, complete.dimensions.size)
        assertEquals(100.0, requireNotNull(complete.total), 0.001)
        val partial = requireNotNull(GitHubIntelligenceScore.read(response(true), "selected", "model", revision))
        assertNull(partial.total)
        assertEquals(0, partial.dimensions.first().observed)
        assertNull(partial.dimensions.first().points)
    }

    @Test fun changedIdentityAndUnsupportedTotalsCannotBeDisplayed() {
        val full = response(false, 100.0)
        assertThrows(IllegalArgumentException::class.java) { GitHubIntelligenceScore.read(full, "other", "model", revision) }
        assertThrows(IllegalArgumentException::class.java) { GitHubIntelligenceScore.read(full, "selected", "other", revision) }
        assertThrows(IllegalArgumentException::class.java) { GitHubIntelligenceScore.read(full, "selected", "model", "changed") }
        assertThrows(IllegalArgumentException::class.java) { GitHubIntelligenceScore.read(response(true, 75.0), "selected", "model", revision) }
    }
}
