package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSamplingPolicyTest {
    private val profile = PlatformV2(uid = "selected", name = "Selected", compatibleType = ClientType.LLAMA, model = "selected-model", temperature = 1.1f, topP = .6f, reasoning = true)

    @Test fun oldSettingsGainAdaptiveDefaultAndManualRoundTripsIndependently() {
        assertEquals(GitHubCreativityMode.ADAPTIVE, Json.decodeFromString<GitHubChatProfileOverride>("{}").sampling.mode)
        val settings = GitHubChatProfileOverride(sampling = GitHubSamplingSettings(mode = GitHubCreativityMode.MANUAL, creativity = .4f))
        assertEquals(settings, Json.decodeFromString<GitHubChatProfileOverride>(Json.encodeToString(settings)))
        assertSame(profile, GitHubSamplingPolicy.platform(profile, null, "primary"))
    }

    @Test fun stageTransitionsChangeSamplingWithoutChangingProfileIdentityOrReasoning() {
        val settings = GitHubSamplingSettings()
        val stages = mapOf("primary" to .05f, "github-design-options" to .94f, "github-architecture-options" to .64f, "github-decision-selection" to .1f, "github-coding" to .2f, "github-review" to .1f, "github-progress-summary" to .45f)
        for ((role, expected) in stages) {
            val constraints = RequestConstraints(githubSampling = settings, allowReasoning = true).forRole(role)
            val actual = GitHubSamplingPolicy.platform(profile, constraints.githubSampling, constraints.requestRole)
            assertEquals(expected, actual.temperature!!, .0001f)
            assertNull(actual.topP)
            assertEquals(profile.copy(temperature = actual.temperature, topP = null), actual)
            assertTrue(constraints.allowReasoning)
        }
        assertEquals(1.1f, profile.temperature)
        assertEquals(.6f, profile.topP)
    }

    @Test fun manualKeepsOneLevelAndUnsupportedModelsOmitControls() {
        val settings = GitHubSamplingSettings(mode = GitHubCreativityMode.MANUAL, creativity = .6f)
        for (stage in GitHubSamplingStage.entries) assertEquals(.8f, GitHubSamplingPolicy.temperature(settings, stage), .0001f)
        for (model in listOf("o3", "gpt-5")) {
            val actual = GitHubSamplingPolicy.platform(profile.copy(compatibleType = ClientType.OPENAI, model = model), settings, "primary")
            assertNull(actual.temperature)
            assertNull(actual.topP)
        }
        val routed = GitHubSamplingPolicy.platform(profile.copy(compatibleType = ClientType.OPENROUTER), settings, "primary")
        assertNull(routed.temperature)
    }

    @Test fun executionAndVerificationDoNotBecomeCreativeWhenSliderMoves() {
        for (level in listOf(0f, 1f)) {
            val settings = GitHubSamplingSettings(creativity = level)
            assertEquals(.05f, GitHubSamplingPolicy.temperature(settings, GitHubSamplingStage.OPERATIONS), 0f)
            assertEquals(.1f, GitHubSamplingPolicy.temperature(settings, GitHubSamplingStage.VERIFICATION), 0f)
        }
        assertNull(GitHubSamplingPolicy.explorationRole("Check PR 817 and repair its CI"))
        assertEquals("github-design-options", GitHubSamplingPolicy.explorationRole("Redesign the UI for Voice"))
        assertEquals("github-architecture-options", GitHubSamplingPolicy.explorationRole("Propose an architecture for saved jobs"))
    }
}
