package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardEntry
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardKind
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardSubmission
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardTrial
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRouteChoiceTest {
    val remote = PlatformV2(name = "Remote", uid = "r", model = "alpha", reasoning = true)
    val local = PlatformV2(name = "Local", uid = "l", model = "beta", compatibleType = ClientType.LITERT_LM)

    @Test fun firstMessageMatchesReasoningResearchAndQuickTasksLocally() {
        assertEquals(ChatRouteChoice.AUTO, routeForFirstMessage(""))
        assertEquals(ChatRouteChoice.FAST, routeForFirstMessage("What is a vertex?"))
        assertEquals(ChatRouteChoice.THINK, routeForFirstMessage("What is the root cause of this timeout?"))
        assertEquals(ChatRouteChoice.RESEARCH, routeForFirstMessage("Find recent papers on VR training."))
        assertEquals(ChatRouteChoice.AUTO, routeForFirstMessage("Rewrite my research notes"))
        assertEquals(ChatRouteChoice.THINK, routeForFirstMessage("Here is my code:\n```kotlin\nprintln(2)\n```"))
    }

    @Test fun fastUsesSuccessfulLatencyAndPreservesResearchEligibility() {
        fun insight(profile: PlatformV2, outcome: BenchmarkOutcome, latency: Long): ProfileInsight {
            val trial = ScoreboardTrial("test", outcome, 2000, firstTextMs = latency)
            val run = ScoreboardSubmission(id = "test", kind = ScoreboardKind.MODEL, plan = BenchmarkMode.QUICK, suiteVersion = 1, startedAt = 1, provider = "test", model = profile.model, trials = listOf(trial))
            return ProfileInsight(profile, ScoreboardEntry(run, 50.0, emptyList()), false, null, AppFeatureSettings(), 1)
        }
        val candidates = listOf(remote, local)
        val insights = mapOf(remote.uid to insight(remote, BenchmarkOutcome.FAILED, 1), local.uid to insight(local, BenchmarkOutcome.PASSED, 500))
        assertEquals(local, recommendedChatProfile(ChatRouteChoice.FAST, candidates, insights, emptyMap()) { true })
        assertEquals(remote, recommendedChatProfile(ChatRouteChoice.FAST, candidates, insights, emptyMap(), requiresResearch = true) { it == remote })
        val checking = mapOf(remote.uid to GatewayAvailability(GatewayAvailabilityStatus.CHECKING))
        assertNull(recommendedChatProfile(ChatRouteChoice.FAST, candidates, insights, checking, requiresResearch = true) { it == remote })
    }

    @Test fun choosesAmongEligibleProfilesAndKeepsResearchPrerequisites() {
        val candidates = listOf(remote, local)
        assertEquals(local, recommendedChatProfile(ChatRouteChoice.AUTO, candidates, emptyMap(), emptyMap()) { true })
        assertEquals(remote, recommendedChatProfile(ChatRouteChoice.THINK, candidates, emptyMap(), emptyMap()) { true })
        assertNull(recommendedChatProfile(ChatRouteChoice.RESEARCH, candidates, emptyMap(), emptyMap()) { false })
        val offline = mapOf(remote.uid to GatewayAvailability(GatewayAvailabilityStatus.OFFLINE))
        assertEquals(local, recommendedChatProfile(ChatRouteChoice.THINK, candidates, emptyMap(), offline) { true })
    }
}
