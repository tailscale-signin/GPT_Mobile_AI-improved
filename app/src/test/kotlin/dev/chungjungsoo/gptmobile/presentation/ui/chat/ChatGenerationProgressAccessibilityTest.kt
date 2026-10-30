package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatGenerationProgressAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun statusAnnouncementStaysStableWhileDotsAnimateAndUpdatesWithActivity() {
        var status by mutableStateOf("Checking workflows")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { ChatGenerationProgress(emptyList(), emptyList(), statusOverride = status) }
        }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithContentDescription("Checking workflows").assertExists()
        compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithContentDescription("Checking workflows").assertExists()
        compose.runOnIdle { status = "Reviewing changes" }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithContentDescription("Reviewing changes").assertExists()
    }
}
