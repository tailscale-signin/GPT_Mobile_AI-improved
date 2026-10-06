package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CombinedResponseStateTest {
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
    fun `failed and canceled participants remain inspectable after membership changes and reopening`() {
        val responses = listOf(response("lead", "Error: unavailable", "failed"), response("other", "Partial answer", "canceled"))
        val runs = mapOf("failed" to run("failed", AgentRunStatus.FAILED), "canceled" to run("canceled", AgentRunStatus.CANCELED))

        val profiles = profiles(responses, participating = emptySet(), runs = runs)

        assertEquals(listOf("lead", "other"), profiles.map { it.uid })
        assertEquals(listOf(CombinedResponseStatus.FAILED, CombinedResponseStatus.FAILED), profiles.map { it.status })
        assertEquals("Partial answer", profiles.last().message?.content)
        assertEquals(CombinedResponseStatus.FAILED, combinedAnswerStatus(null, profiles, runs))
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
}
