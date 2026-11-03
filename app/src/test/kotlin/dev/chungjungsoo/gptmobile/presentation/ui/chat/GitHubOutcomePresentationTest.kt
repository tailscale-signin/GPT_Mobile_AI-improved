package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GitHubOutcomePresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun incompleteOutcomesHaveDistinctLabels() {
        compose.setContent {
            MaterialTheme {
                Column {
                    for (status in listOf(AgentRunStatus.PARTIAL, AgentRunStatus.EVIDENCE_MISSING, AgentRunStatus.BLOCKED)) {
                        AgentRunStatusBlock(AgentRun(status, 1, 1, 2, "profile", "API", "model", status = status))
                    }
                }
            }
        }
        compose.onNodeWithText("Partially complete").assertIsDisplayed()
        compose.onNodeWithText("Evidence needed").assertIsDisplayed()
        compose.onNodeWithText("Work blocked").assertIsDisplayed()
    }
}
