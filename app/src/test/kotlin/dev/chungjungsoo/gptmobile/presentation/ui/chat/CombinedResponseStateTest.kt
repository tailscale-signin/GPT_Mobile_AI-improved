package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.COMBINED_RECOVERY_MARKER
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CombinedResponseStateTest {
    @Test fun pendingRemoteJobIsPausedAndOffersStatusWithoutDuplicateRetry() {
        val message = response("lead", "", "waiting")
        val waiting = run("waiting", AgentRunStatus.WAITING_REMOTE_JOB).copy(terminalError = "QUEUE_JOB_PENDING: Job receipt saved")
        val runs = mapOf(waiting.runId to waiting)
        assertEquals(CombinedResponseStatus.PAUSED, combinedResponseStatus(message, waiting))
        val decision = combinedProfileFailures(1, "Question", listOf(message), runs, emptyMap(), emptyMap()).single()
        assertEquals("QUEUED_JOB", decision.category)
        assertEquals("Waiting for remote job", decision.shortStatus(0))
        assertFalse(decision.retryAllowed)
        assertEquals(listOf(ChatViewModel.LoadingState.Idle), loadingStatesForLatestAssistant(1, listOf(message), runs, setOf(waiting.runId)))
    }

    @Test fun selectedHistoricalAnswerUsesItsOwnOutcomeWhileLatestAttemptFailsRunsOrWaits() {
        val synthesis = response("lead", "New unfinished answer", "combined-synthesis:new").copy(
            activeRevisionIndex = 0,
            revisions = listOf(AssistantRevision("The saved complete answer.", createdAt = 1, runId = "combined-synthesis:old"))
        )
        val latestStates = mapOf(
            AgentRunStatus.FAILED to CombinedResponseStatus.FAILED,
            AgentRunStatus.RUNNING to CombinedResponseStatus.GENERATING,
            AgentRunStatus.WAITING_PROVIDER_LIMIT to CombinedResponseStatus.PAUSED
        )
        latestStates.forEach { (state, expected) ->
            val runs = mapOf("combined-synthesis:old" to run("combined-synthesis:old", AgentRunStatus.COMPLETED), "combined-synthesis:new" to run("combined-synthesis:new", state))
            assertEquals(CombinedResponseStatus.COMPLETED, combinedAnswerStatus(synthesis, emptyList(), runs))
            assertEquals(expected, combinedAnswerStatus(synthesis.copy(activeRevisionIndex = -1), emptyList(), runs))
        }
        val failedHistory = mapOf("combined-synthesis:old" to run("combined-synthesis:old", AgentRunStatus.FAILED), "combined-synthesis:new" to run("combined-synthesis:new", AgentRunStatus.COMPLETED))
        assertEquals(CombinedResponseStatus.FAILED, combinedAnswerStatus(synthesis, emptyList(), failedHistory))
    }

    @Test
    fun `archived Combined source tabs use only that revision snapshot`() {
        val archived = CombinedModelResponse("old", "Old profile", content = "Original contribution", attemptId = "original-run")
        val lead = response("lead", "Latest combined", "combined-synthesis:latest").copy(
            combinedSources = listOf(CombinedModelResponse("other", "New profile", content = "New contribution")),
            activeRevisionIndex = 0,
            revisions = listOf(AssistantRevision("Old combined", createdAt = 1, runId = "combined-synthesis:old", combinedSources = listOf(archived)))
        )
        val current = response("other", "New contribution", "new-run")

        val profiles = combinedResponseProfiles(listOf(lead, current), listOf("old"), emptyMap(), setOf("old"), false, emptyMap())

        assertEquals(listOf("old"), profiles.map { it.uid })
        assertEquals("Original contribution", profiles.single().message?.content)
        assertEquals("original-run", profiles.single().message?.currentRunId)
    }

    @Test
    fun `selected profiles without responses retain empty swipe slots`() {
        val profiles = combinedResponseProfiles(
            responses = emptyList(),
            platformUids = listOf("first", "second", "third"),
            profileNames = mapOf("first" to "First", "second" to "Second", "third" to "Third"),
            participatingUids = setOf("first", "second", "third"),
            preparing = false,
            runsById = emptyMap()
        )

        assertEquals(listOf("first", "second", "third"), profiles.map { it.uid })
        assertTrue(profiles.all { it.message == null })
        assertTrue(profiles.all { it.status == CombinedResponseStatus.FAILED })
    }

    @Test
    fun `legacy archived Combined without snapshots never borrows current sources`() {
        val lead = response("lead", "Latest combined", "combined-synthesis:latest").copy(
            combinedSources = listOf(CombinedModelResponse("other", "Other", content = "New contribution")),
            activeRevisionIndex = 0,
            revisions = listOf(AssistantRevision("Old combined", createdAt = 1, runId = "combined-synthesis:old"))
        )

        assertEquals(emptyList<CombinedResponseProfile>(), profiles(listOf(lead, response("other", "New contribution", "new-run"))))
    }

    @Test
    fun `identical text from a newer attempt cannot replace an archived source identity`() {
        val lead = response("lead", "Combined", "combined-synthesis:final").copy(
            combinedSources = listOf(CombinedModelResponse("other", "Other", content = "Same wording", attemptId = "original", completionStatus = AgentRunStatus.INTERRUPTED))
        )
        val newer = response("other", "Same wording", "newer").copy(thoughts = "Different reasoning")

        val profile = profiles(listOf(lead, newer)).first { it.uid == "other" }

        assertEquals("original", profile.message?.currentRunId)
        assertEquals("", profile.message?.thoughts)
        assertEquals(CombinedResponseStatus.FAILED, profile.status)
    }

    @Test
    fun `lead source keeps its original answer and completed status while synthesis is running`() {
        val lead = response("lead", "Combined answer in progress", "combined-synthesis:final").copy(
            revisions = listOf(AssistantRevision("Lead answer", thoughts = "Lead reasoning", createdAt = 1, runId = "lead-run")),
            combinedSources = listOf(CombinedModelResponse("lead", "Lead 🚀", content = "Lead answer"))
        )
        val runs = mapOf(
            "lead-run" to run("lead-run", AgentRunStatus.COMPLETED),
            "combined-synthesis:final" to run("combined-synthesis:final", AgentRunStatus.RUNNING)
        )

        val profiles = profiles(listOf(lead), runs = runs)

        assertEquals("Lead answer", profiles.single().message?.content)
        assertEquals("Lead reasoning", profiles.single().message?.thoughts)
        assertEquals("lead-run", profiles.single().message?.currentRunId)
        assertEquals("Lead", profiles.single().name)
        assertEquals(CombinedResponseStatus.COMPLETED, profiles.single().status)
        assertEquals(CombinedResponseStatus.GENERATING, combinedAnswerStatus(lead, profiles, runs))
    }

    @Test
    fun `saved source cannot be replaced by a later failed retry`() {
        val lead = response("lead", "Combined", "combined-synthesis:final").copy(
            combinedSources = listOf(CombinedModelResponse("other", "Other", content = "Original answer"))
        )
        val other = response("other", "Error: retry failed", "retry").copy(
            revisions = listOf(AssistantRevision("Original answer", createdAt = 1, runId = "original"))
        )
        val runs = mapOf("retry" to run("retry", AgentRunStatus.FAILED), "original" to run("original", AgentRunStatus.COMPLETED))

        val profile = profiles(listOf(lead, other), runs = runs).first { it.uid == "other" }

        assertEquals("Original answer", profile.message?.content)
        assertEquals("original", profile.message?.currentRunId)
        assertEquals(CombinedResponseStatus.COMPLETED, profile.status)
    }

    @Test
    fun `removed failed and canceled participants do not reappear after reopening`() {
        val responses = listOf(response("lead", "Error: unavailable", "failed"), response("other", "Partial answer", "canceled"))
        val runs = mapOf("failed" to run("failed", AgentRunStatus.FAILED), "canceled" to run("canceled", AgentRunStatus.CANCELED))

        val profiles = profiles(responses, participating = emptySet(), runs = runs)

        assertEquals(emptyList<CombinedResponseProfile>(), profiles)
        assertEquals("Partial answer", responses.last().content)
        assertEquals("canceled", responses.last().currentRunId)
    }

    @Test
    fun `newly selected profiles appear while dispatching but unused slots stay out of history`() {
        val responses = listOf(response("lead", "", null), response("other", "", null), response("paused", "", null))

        val pending = profiles(responses, preparing = true)
        val historical = profiles(responses, preparing = false)

        assertEquals(listOf("lead", "other"), pending.map { it.uid })
        assertEquals(listOf(CombinedResponseStatus.GENERATING, CombinedResponseStatus.GENERATING), pending.map { it.status })
        assertEquals(emptyList<CombinedResponseProfile>(), historical)
    }

    @Test
    fun `empty or error completions never get a green status`() {
        val completed = run("run", AgentRunStatus.COMPLETED)

        assertEquals(CombinedResponseStatus.FAILED, combinedResponseStatus(response("lead", "", "run"), completed))
        assertEquals(CombinedResponseStatus.FAILED, combinedResponseStatus(response("lead", "Error: disconnected", "run"), completed))
        assertEquals(CombinedResponseStatus.GENERATING, combinedResponseStatus(response("lead", "Partial", "run"), run("run", AgentRunStatus.RUNNING)))
    }

    @Test
    fun `snapshot without a matching revision never inherits the synthesis run`() {
        val lead = response("lead", "Combined", "combined-synthesis:final").copy(
            combinedSources = listOf(CombinedModelResponse("lead", "Lead", content = "Saved source"))
        )

        val profile = profiles(listOf(lead)).single()

        assertEquals("Saved source", profile.message?.content)
        assertEquals(null, profile.message?.currentRunId)
        assertFalse(profile.message!!.isCombinedSynthesis())
        assertEquals(CombinedResponseStatus.COMPLETED, profile.status)
    }

    @Test
    fun `profile labels retain international letters and numbers while stripping symbols and emoji`() {
        assertEquals("Qwen3 5 Pro", combinedProfileLabel("🚀 Qwen3.5 — Pro™", "AI 1"))
        assertEquals("Élodie 模型 9", combinedProfileLabel("E\u0301lodie✨ 模型#9", "AI 1"))
        assertEquals("AI 1", combinedProfileLabel("🚀 ★ ---", "AI 1"))
    }

    @Test
    fun `previously saved combined footer hides source labels without losing facts`() {
        val saved = """
            Original synthesis.

            ## Additional source details

            The passages below were not fully integrated by the synthesis model. They are retained from individual AI profiles, not independently verified facts.

            **From Gemini, Claude:**

            Unique fact about Tang China.

            **From Perplexity:**

            Another distinct event.
        """.trimIndent()
        val normal = combinedVisibleAnswer(saved, debugMode = false)
        assertEquals("Original synthesis.", normal)
        val visible = combinedVisibleAnswer(saved, debugMode = true)
        assertEquals(
            """
                Original synthesis.

                ## Further details

                Unique fact about Tang China.

                Another distinct event.
            """.trimIndent(),
            visible
        )
        assertFalse(visible.contains("From Gemini"))
        assertFalse(visible.contains("From Perplexity"))
    }

    @Test
    fun `combined response text without a legacy footer is unchanged`() {
        val alreadyClean = "A source-independent finding from Tang China.\n\n## Further details\n\nImportant example."
        assertEquals(alreadyClean, combinedVisibleAnswer(alreadyClean, debugMode = false))
    }

    @Test
    fun `marked recovered passages appear only in Debug Mode and marker never leaks`() {
        val stored = "Primary combined answer.\n\n$COMBINED_RECOVERY_MARKER\n\n## Further details\n\nAdditional fact retained from another response."
        val normal = combinedVisibleAnswer(stored, debugMode = false)
        val debug = combinedVisibleAnswer(stored, debugMode = true)

        assertEquals("Primary combined answer.", normal)
        assertEquals(
            "Primary combined answer.\n\n## Further details\n\nAdditional fact retained from another response.",
            debug
        )
        assertFalse(normal.contains("Additional fact"))
        assertFalse(debug.contains(COMBINED_RECOVERY_MARKER))
    }

    @Test
    fun `normal Combined headings are not mistakenly treated as recovered material`() {
        val answer = "This is the final answer.\n\n## Further details\n\nA legitimate part of the final synthesis."
        assertEquals(answer, combinedVisibleAnswer(answer, debugMode = false))
        assertEquals(answer, combinedVisibleAnswer(answer, debugMode = true))
    }

    @Test
    fun `chronological timeline does not bypass filtered Combined source material`() {
        val timeline = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, "Partial text"),
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, "Completed editorial review"),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, "Additional source material")
        )
        val result = combinedVisibleTimeline(timeline, "Only the main combined answer")
        assertEquals(2, result.size)
        assertEquals(AssistantTimelineItemType.NOTICE, result.first().type)
        assertEquals("Completed editorial review", result.first().content)
        assertEquals(AssistantTimelineItemType.TEXT, result.last().type)
        assertEquals("Only the main combined answer", result.last().content)
        assertFalse(result.any { it.content.contains("Additional source material") })
    }

    @Test
    fun `legacy or absent timeline continues to use safely filtered fallback text`() {
        val unavailable = listOf(AssistantTimelineItem(AssistantTimelineItemType.LEGACY_ORDER))
        assertEquals(unavailable, combinedVisibleTimeline(unavailable, "filtered"))
        assertEquals(emptyList<AssistantTimelineItem>(), combinedVisibleTimeline(emptyList(), "filtered"))
    }

    private fun profiles(
        responses: List<MessageV2>,
        participating: Set<String> = setOf("lead", "other"),
        preparing: Boolean = false,
        runs: Map<String, AgentRun> = emptyMap()
    ) = combinedResponseProfiles(responses, listOf("lead", "other", "paused"), emptyMap(), participating, preparing, runs)

    private fun response(uid: String, content: String, runId: String?) = MessageV2(content = content, platformType = uid, currentRunId = runId)

    private fun run(id: String, status: String) = AgentRun(
        runId = id,
        chatId = 1,
        userMessageId = 1,
        assistantMessageId = 2,
        profileUid = "lead",
        providerSnapshot = "test",
        modelSnapshot = "test",
        status = status
    )

    @org.junit.Test fun durableProviderWaitStaysPausedAndOffersATurnExclusionDecision() {
        val message = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(id = 2, chatId = 1, content = "", platformType = "p", currentRunId = "waiting")
        val run = dev.chungjungsoo.gptmobile.data.database.entity.AgentRun("waiting", 1, 1, 2, "p", "FREE", "model", status = "WAITING_PROVIDER_LIMIT")
        org.junit.Assert.assertEquals(CombinedResponseStatus.PAUSED, combinedResponseStatus(message, run))
        val decision = combinedProfileFailures(1, "Question", listOf(message), mapOf(run.runId to run), emptyMap(), emptyMap()).single()
        org.junit.Assert.assertEquals("RATE_LIMIT", decision.category)
        org.junit.Assert.assertEquals("Paused by provider limit", decision.shortStatus(0))
        org.junit.Assert.assertTrue(decision.reason.contains("without this profile for this turn"))
    }
}
