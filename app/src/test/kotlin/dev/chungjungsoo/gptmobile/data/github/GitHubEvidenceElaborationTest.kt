package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEvidenceElaborationTest {
    private val head = "a".repeat(40)
    private val next = "b".repeat(40)
    private val context = GitHubConversationContext("connection", "owner", "repo", "main", head, "profile")
    private var clock = 0L
    private val progress = GitHubChatProgress("Summarize how voice capture resumes after interruption", context, "turn-19") { clock }

    @Test fun subjectAndSourceActivityRemainDistinctFromProvenFindings() {
        assertTrue(summaries().single().contains("how voice capture resumes after interruption"))
        capture(
            "read",
            "github_read",
            """{"path":"Voice.kt","commit_sha":"$head"}""",
            """{"commit_sha":"$head","items":[{"path":"Voice.kt","start_line":1,"end_line":3,"range_complete":true,"content":"fun capture()"}]}"""
        )
        clock = 45_000
        assertTrue(summaries().isEmpty())
        assertNull(progress.forward(ApiState.ActivitySummary("Voice.kt resumes playback and all checks passed.", modelAuthored = true)))
        capture("issue", "github_issue", """{"issue_number":74}""", """{"number":74,"state":"open","title":"Playback after interruption"}""")
        val supported = progress.summarySnapshot().recentSteps.single()
        assertNull(progress.forward(ApiState.ActivitySummary("$supported This passed testing.", modelAuthored = true)))
        val accepted = progress.forward(ApiState.ActivitySummary(supported, modelAuthored = true))
        assertEquals(supported, (accepted as ApiState.ActivitySummary).text)
        capture("issue-other-worker", "github_issue", """{"issue_number":74}""", """{"number":74,"state":"open","title":"Playback after interruption"}""")
        clock = 180_000
        assertTrue(summaries().isEmpty())
    }

    @Test fun newBranchHeadRetractsOldResultsAndTheFinalAnswerClosesPublication() {
        summaries()
        val job = """{"id":14,"run_id":3,"head_sha":"$head","status":"completed","conclusion":"failure","steps":[{"name":"Compile Kotlin","status":"completed","conclusion":"failure"}]}"""
        capture("ci", "github_ci", """{"action":"jobs","run_id":3}""", """{"jobs":[$job]}""")
        val failure = summaries().single()
        assertTrue(failure.contains("Job 14") && failure.contains("Compile Kotlin") && failure.contains(head.take(8)))
        capture("head", "github_context", """{"action":"resolve","ref":"main"}""", """{"owner":"owner","repo":"repo","ref":"main","commit_sha":"$next","tree_sha":"${"d".repeat(40)}"}""")
        val corrected = summaries().single()
        assertTrue(corrected.contains(head.take(8)) && corrected.contains(next.take(8)) && corrected.contains("verification"))
        assertFalse(progress.summarySnapshot().recentSteps.any { it.contains("Compile Kotlin") })
        capture("stale-ci", "github_ci", """{"action":"jobs","run_id":3}""", """{"jobs":[$job]}""")
        assertFalse(progress.summarySnapshot().recentSteps.any { it.contains("Compile Kotlin") })
        progress.forward(ApiState.Success("Here is the result."))
        progress.forward(ApiState.GitHubRoundStarted)
        clock = 180_000
        assertTrue(progress.tick().isEmpty())
    }

    @Test fun nativeBranchDetailCanAdvanceOnlyTheSelectedBranch() {
        summaries()
        fun branch(id: String, name: String) = capture(
            id,
            "github_context",
            """{"action":"branch","branch":"$name"}""",
            """{"branch":{"name":"$name","commit":{"sha":"$next"}}}"""
        )
        branch("other", "another-branch")
        assertTrue(summaries().isEmpty())
        branch("selected", "main")
        assertTrue(summaries().single().contains("moved from ${head.take(8)} to ${next.take(8)}"))
    }

    @Test fun nativePullRequestChecksRequireTheirObservedHeadAndOwningRepository() {
        summaries()
        fun pull(id: String, repo: String, observed: String) = capture(
            id,
            "github_pr",
            """{"action":"context","pull_number":12}""",
            """{"pull_request":{"number":12,"state":"open","head":{"ref":"main","repo":{"full_name":"$repo"},"sha":"$next"}},"expected_head":"$observed","checks":{"check_runs":[],"total_count":0}}"""
        )
        pull("mismatch", "owner/repo", head)
        pull("fork", "another/repo", next)
        clock = 45_000
        assertTrue(summaries().isEmpty())
        pull("selected", "owner/repo", next)
        assertTrue(summaries().single().contains("moved from ${head.take(8)} to ${next.take(8)}"))
    }

    @Test fun androidAdmitsTheSharedDesktopTranscript() {
        val path = "gateway/desktop/native/tests/public-preview-v1.json"
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, path).isFile }
        val fixtures = Json.parseToJsonElement(File(root, path).readText()).jsonObject.getValue("projection_cases").jsonArray
        for (fixture in fixtures.map { it.jsonObject }) {
            val ledger = GitHubPublicFindingLedger(fixture.getValue("scope_id").jsonPrimitive.content)
            for (event in fixture.getValue("events").jsonArray.map { it.jsonObject }) {
                val expected = event.getValue("expected").jsonPrimitive.content
                val actual = if (!ledger.acceptSequence("job", event.getValue("sequence").jsonPrimitive.int)) {
                    "suppressed"
                } else {
                    when (event.getValue("event_type").jsonPrimitive.content) {
                        "final" -> {
                            ledger.finish()
                            "final"
                        }
                        "activity" -> "activity"
                        "finding" -> {
                            val fact = event.getValue("fact").jsonObject
                            fun field(name: String) = fact.getValue(name).jsonPrimitive.content
                            ledger.observe(GitHubPublicFinding(field("scope_id"), field("criterion_id"), field("subject_id"), field("revision_id"), field("value"), field("evidence_id"))).name.lowercase(java.util.Locale.ROOT)
                        }
                        else -> "suppressed"
                    }
                }
                assertEquals(fixture.getValue("name").jsonPrimitive.content, expected, actual)
            }
        }
    }

    @Test fun queuedHeadAdvancePreventsOldResultsFromReappearingAsLivePreview() {
        summaries()
        fun queued(target: GitHubChatProgress, id: String, tool: String, request: String, body: String): Pair<ApiState.ProgressCheckpoint, ApiState.GatewayProgressChanged> {
            val args = Json.parseToJsonElement(request).jsonObject
            fun event(type: String) = ApiState.GatewayProgressChanged(GatewayProgress(event = type, toolName = tool, toolCallId = id, toolArgs = args))
            target.forward(event("tool_started"))
            val upstream = AgentToolResult(id, ToolResultContent.Json(Json.parseToJsonElement(body)), false)
            val result = GitHubResultViews().present("turn-19", tool, tool, upstream, args)
            val preview = requireNotNull(target.observeResult(ProviderEvent.ToolCall(id, tool, args), result))
            return ApiState.ProgressCheckpoint(preview) to event("tool_completed")
        }
        fun job(revision: String) = """{"jobs":[{"id":14,"run_id":3,"head_sha":"$revision","status":"completed","conclusion":"failure","steps":[{"name":"Compile Kotlin","status":"completed","conclusion":"failure"}]}]}"""
        val moved = queued(
            progress,
            "head",
            "github_context",
            """{"action":"resolve","ref":"main"}""",
            """{"owner":"owner","repo":"repo","ref":"main","commit_sha":"$next","tree_sha":"${"d".repeat(40)}"}"""
        )
        // The producer captures both results before the consumer applies either
        // completion. Consumption remains FIFO, just as in channelFlow.
        val stale = queued(progress, "old-ci", "github_ci", """{"action":"jobs","run_id":3}""", job(head))
        assertFalse(stale.first.text.contains("Compile Kotlin"))
        assertFalse(moved.first.text.contains(next.take(8)))
        progress.forward(moved.first)
        progress.forward(moved.second)
        assertTrue(summaries().single().contains("moved from ${head.take(8)} to ${next.take(8)}"))
        progress.forward(stale.first)
        progress.forward(stale.second)
        assertFalse(progress.summarySnapshot().activity.contains("Compile Kotlin"))
        assertFalse(progress.summarySnapshot().recentSteps.any { it.contains("Compile Kotlin") })
        clock = 45_000
        assertTrue(
            progress.tick().none { event ->
                when (event) {
                    is ApiState.ProgressCheckpoint -> event.text.contains("Compile Kotlin")
                    is ApiState.ActivitySummary -> event.text.contains("Compile Kotlin")
                    else -> false
                }
            }
        )

        val current = queued(progress, "current-ci", "github_ci", """{"action":"jobs","run_id":3}""", job(next))
        assertFalse(current.first.text.contains("Compile Kotlin"))
        progress.forward(current.first)
        progress.forward(current.second)
        assertTrue(progress.summarySnapshot().activity.contains("Compile Kotlin"))
        assertTrue(summaries().any { it.contains("Compile Kotlin") && it.contains(next.take(8)) })

        val legacy = GitHubChatProgress("Inspect CI") { clock }
        val legacyResult = queued(legacy, "legacy-ci", "github_ci", """{"action":"jobs","run_id":3}""", job(head))
        assertTrue(legacyResult.first.text.contains("Compile Kotlin"))
        legacy.forward(legacyResult.first)
        legacy.forward(legacyResult.second)
        assertTrue(legacy.summarySnapshot().activity.contains("Compile Kotlin"))
    }

    private fun summaries() = progress.tick().filterIsInstance<ApiState.ActivitySummary>().map { it.text }

    private fun capture(id: String, tool: String, request: String, body: String) {
        val args = Json.parseToJsonElement(request).jsonObject
        fun event(type: String) = ApiState.GatewayProgressChanged(GatewayProgress(event = type, toolName = tool, toolCallId = id, toolArgs = args))
        progress.forward(event("tool_started"))
        val upstream = AgentToolResult(id, ToolResultContent.Json(Json.parseToJsonElement(body)), false)
        val result = GitHubResultViews().present("turn-19", tool, tool, upstream, args)
        progress.observeResult(ProviderEvent.ToolCall(id, tool, args), result)
        progress.forward(event("tool_completed"))
    }
}
