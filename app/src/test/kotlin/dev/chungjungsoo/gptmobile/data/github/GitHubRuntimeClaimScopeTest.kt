package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRuntimeClaimScopeTest {
    private val task = "Fix the repository's GitHub chat output and run tests"
    private val finding = "The application's resource notice derives the remaining tool-call budget from the supplied runtime resource object."
    private val recommendation = "Keep the application's tool-call budget visible in the recovery notice."
    private val source = buildJsonObject {
        put("trusted", true)
        put("success", true)
        put("observed_at_ms", 1000)
        put("epoch", 0)
        put("source_head_sha", "a".repeat(40))
        put(
            "result",
            buildJsonObject {
                put("path", "app/GitHubAnswerPresentation.kt")
                put("start_line", 27)
                put("content", "val remaining = resources[\"remaining_calls\"]\nappend(\"Tool calls remaining: \").append(remaining)")
            }
        )
    }

    private fun claim(kind: String, text: String, receipt: JsonObject = source): JsonObject {
        val excerpt = GitHubEvidenceExcerpts.collect(receipt).entries.single()
        return buildJsonObject {
            put("kind", kind)
            put("text", text)
            put(
                "sources",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("receipt_id", "source")
                            put("pointer", excerpt.key)
                            put("value", excerpt.value.getValue("value"))
                        }
                    )
                )
            )
        }
    }

    private fun render(claim: JsonObject, receipt: JsonObject = source, questions: List<String> = emptyList()): Pair<String, JsonObject> =
        GitHubEvidencePolicy.render(
            buildJsonObject {
                put("claims", JsonArray(listOf(claim)))
                put("questions", JsonArray(questions.map(::JsonPrimitive)))
            },
            mapOf("source" to receipt),
            1000,
            0,
            task
        )

    @Test fun sourceBackedApplicationFindingsAndRecommendationsSurviveRuntimeVocabulary() {
        for ((kind, text) in listOf("inferred" to finding, "recommendation" to recommendation, "inferred" to "The application displays its remaining tool-call budget.")) {
            val (rendered, report) = render(claim(kind, text))
            assertTrue(rendered, rendered.contains(text))
            val checked = report.getValue("claims").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive("INFERRED"), checked["status"])
            assertEquals(JsonPrimitive("VERIFIED"), checked.getValue("sources").jsonArray.single().jsonObject["status"])
        }
    }

    @Test fun sourceBackedCiEnvironmentRecommendationsSurviveTheSameGuard() {
        val ci = JsonObject(
            source + (
                "result" to buildJsonObject {
                    put("path", ".github/workflows/android.yml")
                    put("content", "steps:\n  - uses: android-actions/setup-android")
                }
                )
        )
        val text = "Use a CI environment with the Android SDK when running the project's tests."
        val (rendered, report) = render(claim("recommendation", text, ci), ci)
        assertTrue(rendered, rendered.contains(text))
        assertEquals(JsonPrimitive("INFERRED"), report.getValue("claims").jsonArray.single().jsonObject["status"])
    }

    @Test fun applicationScopeDoesNotReplaceMissingStaleUntrustedOrContradictedEvidence() {
        val supported = claim("inferred", finding)
        val cases = listOf(
            JsonObject(supported - "sources") to source,
            supported to JsonObject(source + ("observed_at_ms" to JsonPrimitive(0))),
            supported to JsonObject(source + ("trusted" to JsonPrimitive(false))),
            supported to JsonObject(
                source + (
                    "result" to buildJsonObject {
                        put("path", "app/GitHubAnswerPresentation.kt")
                        put("start_line", 27)
                        put("content", "unrelated replacement\nsource")
                    }
                    )
            )
        )
        for ((candidate, receipt) in cases) {
            val (rendered, report) = render(candidate, receipt)
            assertFalse(rendered, rendered.contains(finding))
            assertEquals(JsonPrimitive("NOT_MEASURED"), report.getValue("claims").jsonArray.single().jsonObject["status"])
        }
    }

    @Test fun unrelatedCitationsOrScopeLabelsCannotAuthorizeCurrentSessionAssertions() {
        val texts = listOf(
            "The tool-call budget was exhausted.",
            "My tool-call budget was exhausted.",
            "The tool-call budget was exhausted while fixing the application.",
            "While fixing the application, the tool-call budget was exhausted.",
            "The repository's current session has no tool calls remaining.",
            "The application's source means that I cannot run tests.",
            "The application has a resource notice. We have no tool calls left."
        )
        for (text in texts) {
            val labeled = JsonObject(claim("inferred", text) + mapOf("scope" to JsonPrimitive("application"), "subject" to JsonPrimitive("application source")))
            val (rendered, report) = render(labeled)
            assertFalse(rendered, rendered.contains(text))
            assertEquals(JsonPrimitive("RUNTIME_STATE_REQUIRES_LEDGER"), report.getValue("claims").jsonArray.single().jsonObject["reason"])
        }
    }

    @Test fun applicationQuestionsSurviveButRepermissionAndCurrentSessionQuestionsDoNot() {
        val applicationQuestion = "Should the application's tool-call budget be configurable per repository?"
        val ciQuestion = "Should the CI environment support native-device tests?"
        val blocked = listOf(
            "Should the changes be performed in an environment with write, test-execution, branch and pull-request capabilities?",
            "Should the repository changes be performed in an environment with write capabilities?",
            "Can we use more tool calls to fix the application?",
            "For repository work, can we use more tool calls?",
            "Should we move the repository task to an environment with write capabilities?",
            "In this repository, can we use more tool calls?"
        )
        val (rendered, _) = render(claim("inferred", finding), questions = listOf(applicationQuestion, ciQuestion) + blocked)
        assertTrue(rendered, rendered.contains(applicationQuestion))
        assertTrue(rendered, rendered.contains(ciQuestion))
        for (question in blocked) assertFalse(rendered, rendered.contains(question))
    }

    @Test fun unsupportedCurrentSessionLimitationsRemainSuppressed() {
        val limitation = buildJsonObject {
            put("kind", "not_measured")
            put("subject", "The tool-call budget was exhausted")
        }
        val (rendered, report) = render(limitation)
        assertFalse(rendered.contains("budget was exhausted"))
        assertEquals(JsonPrimitive("RUNTIME_STATE_REQUIRES_LEDGER"), report.getValue("claims").jsonArray.single().jsonObject["reason"])
    }
}
