package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecision
import dev.chungjungsoo.gptmobile.data.github.GitHubDecisionOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubDecisionCardTest {
    @get:Rule val compose = createComposeRule()
    private val question = GitHubChatDecision("test", 1, 2, "profile", "repo", "Which layout?", "Choose how to present repository activity.", listOf(GitHubDecisionOption("Compact", "More results on screen"), GitHubDecisionOption("Spacious", "Larger previews")), 0)

    @Test fun suggestedChoiceDoesNotPreselectAndButtonsReturnTheSelectedOption() {
        var selected: Int? = null
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { GitHubDecisionCard(question, true, { option, _ -> selected = option }, {}, {}) } } }
        compose.runOnIdle { assertEquals(null, selected) }
        compose.onNodeWithTag("github_decision_option_1").assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @Test fun customAnswerAndStopRemainAvailableInDarkTheme() {
        var answer: String? = null
        var stops = 0
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Column(Modifier.verticalScroll(rememberScrollState())) { GitHubDecisionCard(question, true, { _, text -> answer = text }, {}, { stops++ }) } } }
        compose.onNodeWithText("Write my own answer").performScrollTo().performClick()
        compose.onNodeWithTag("github_decision_custom").performScrollTo().performTextInput("Compact with expandable details")
        compose.onNodeWithText("Use my answer").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Compact with expandable details", answer) }
        compose.onNodeWithText("Stop this task").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
}
