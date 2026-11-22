package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

    @Test fun switchingQueuedChoicesKeepsEachDraftWithItsOwnQuestion() {
        val second = question.copy(id = "second", question = "Which colour?")
        val submitted = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GitHubDecisionQueue(listOf(question, second)) { current ->
                        GitHubDecisionCard(current, true, { _, text -> submitted.add(current.id to text) }, {}, {}, compact = true)
                    }
                }
            }
        }
        fun click(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()
        click("Write my own answer")
        compose.onNodeWithTag("github_decision_custom").performScrollTo().performTextInput("Keep it compact")
        compose.onNodeWithTag("github_decision_next").performScrollTo().performClick()
        compose.onNodeWithTag("github_decision_custom").assertDoesNotExist()
        click("Write my own answer")
        compose.onNodeWithTag("github_decision_custom").performScrollTo().performTextInput("Use teal")
        compose.onNodeWithTag("github_decision_previous").performScrollTo().performClick()
        click("Use my answer")
        compose.onNodeWithTag("github_decision_next").performScrollTo().performClick()
        click("Use my answer")
        compose.runOnIdle { assertEquals(listOf(question.id to "Keep it compact", second.id to "Use teal"), submitted) }
    }

    @Test fun compactQueueShowsOneChoiceAndKeepsDetailsAndAllChoicesReachableAtLargeFont() {
        val second = question.copy(id = "second", question = "Which colour?")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme {
                    Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                        GitHubDecisionQueue(listOf(question, second)) { decision -> GitHubDecisionCard(decision, true, { _, _ -> }, {}, {}, compact = true) }
                    }
                }
            }
        }
        val firstOption = compose.onNodeWithTag("github_decision_option_0").fetchSemanticsNode()
        val secondOption = compose.onNodeWithTag("github_decision_option_1").fetchSemanticsNode()
        org.junit.Assert.assertTrue(secondOption.positionInRoot.y >= firstOption.positionInRoot.y + firstOption.size.height)
        compose.onNodeWithText("Choice 1 of 2").assertExists()
        compose.onNodeWithTag("github_decision_test").assertExists()
        compose.onNodeWithTag("github_decision_second").assertDoesNotExist()
        compose.onNodeWithText(question.context).assertDoesNotExist()
        compose.onNodeWithTag("github_decision_details").performScrollTo().performClick()
        compose.onNodeWithText(question.context).assertExists()
        compose.onNodeWithTag("github_decision_next").performScrollTo().performClick()
        compose.onNodeWithTag("github_decision_second").assertExists()
        compose.onNodeWithTag("github_decision_test").assertDoesNotExist()
    }
}
