package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.chungjungsoo.gptmobile.data.research.AcceptedResearchTask
import dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState
import dev.chungjungsoo.gptmobile.data.research.ResearchExecutionRecord
import dev.chungjungsoo.gptmobile.data.research.ResearchProfileStatistics
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w400dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResearchProfileStatisticsRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun debugStatisticsStayVisibleAfterPartialCompletionAndHideWhenDisabled() {
        val task = AcceptedResearchTask(
            taskId = researchHash("task:root"), rootWorkId = "root", chatId = 1, originatingUserMessageId = 1,
            taskText = "Research sources", intent = "REQUIRED", participantUids = listOf("a"), primaryUid = "a",
            settingsRevision = "settings", sourceAuthorityRevision = "scope", acceptedAt = 1, targetSources = 3
        )
        var execution by mutableStateOf(ResearchExecutionRecord.accept(task))
        var debug by mutableStateOf(true)
        val stats = listOf(ResearchProfileStatistics("a", sources = 2, sourcesRead = 1, modelRequests = 2, completedRequests = 1, failedRequests = 1, failedStages = 1, skippedStages = 3, lastFailureCategory = "ADMISSION"))
        compose.setContent {
            MaterialTheme { ResearchAttemptProgressCard(execution, {}, {}, {}, debugMode = debug, profileStatistics = stats, profileNames = mapOf("a" to "Free profile")) }
        }
        compose.onNodeWithText("AI profile statistics").assertIsDisplayed()
        compose.onNodeWithText("Free profile").assertIsDisplayed()
        compose.onNodeWithText("Retained stages: 0 accepted · 1 failed · 3 skipped").assertIsDisplayed()
        compose.runOnIdle {
            execution = execution.transition(execution.attempt.attemptId, 0, ResearchAttemptState.PARTIAL, 2, checkpointId = "saved-report")
        }
        compose.onNodeWithText("Partial research report delivered").assertIsDisplayed()
        compose.onNodeWithText("AI profile statistics").assertIsDisplayed()
        compose.runOnIdle { debug = false }
        compose.onNodeWithText("AI profile statistics").assertDoesNotExist()
        compose.onNodeWithText("Last issue: ADMISSION").assertDoesNotExist()
    }
}
