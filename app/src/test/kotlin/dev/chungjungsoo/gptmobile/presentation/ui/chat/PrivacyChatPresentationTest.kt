package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProtectedRange
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProtectionReceipt
import dev.chungjungsoo.gptmobile.data.privacy.effectivePrivacyProtectedValues
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class PrivacyChatPresentationTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val yellow = Color(0xFFFFD740)

    @Test
    fun hiddenReasoningCannotColourAnUnprotectedWordAssembledByFiltering() {
        val raw = "Al<think>Alice</think>ice and Bob"
        val alice = raw.indexOf("Alice")
        val bob = raw.indexOf("Bob")
        val message = MessageV2(
            content = raw,
            platformType = "remote",
            timeline = listOf(
                AssistantTimelineItem(AssistantTimelineItemType.TEXT, raw),
                AssistantTimelineItem(AssistantTimelineItemType.NOTICE, privacyProtection = PrivacyProtectionReceipt.create(raw, listOf(PrivacyProtectedRange(alice, alice + 5), PrivacyProtectedRange(bob, bob + 3))))
            )
        )
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
                AssistantChronologicalContent(
                    timeline = message.timeline,
                    toolEvents = emptyList(),
                    fallbackText = raw,
                    fallbackThoughts = "",
                    contentIdentity = "filtered-private-answer",
                    isLoading = false,
                    privacyProtectedValues = message.effectivePrivacyProtectedValues(),
                    animateResponse = false,
                    responseSurface = Color.Black,
                    debugMode = false,
                    showReasoning = false,
                    expanded = false,
                    onExpandedChange = {}
                )
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Alice and Bob", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val text = compose.onAllNodesWithText("Alice and Bob", useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }.first { it.text == "Alice and Bob" }
        assertEquals(listOf("Bob"), text.spanStyles.filter { it.item.color == yellow }.map { text.text.substring(it.start, it.end) })
    }

    @Test
    fun receiptColoursTheUserBubbleAndDisappearsWhenEvidenceIsRemoved() {
        var values by mutableStateOf(listOf("Alice"))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primaryContainer = Color.Black)) {
                UserChatBubble(text = "Ask Alice", privacyProtectedValues = values)
            }
        }
        compose.waitUntil(5000) { yellow in colours("Ask Alice") }
        compose.runOnIdle { values = emptyList() }
        compose.waitUntil(5000) { yellow !in colours("Ask Alice") }
    }

    @Test
    fun protectedAnswerNeverColoursMatchingProgressOrReasoningText() {
        var values by mutableStateOf(listOf("Alice"))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
                AssistantChronologicalContent(
                    timeline = listOf(
                        AssistantTimelineItem(AssistantTimelineItemType.THINKING, "Alice in reasoning"),
                        AssistantTimelineItem(AssistantTimelineItemType.NOTICE, "Alice in progress"),
                        AssistantTimelineItem(AssistantTimelineItemType.TEXT, "Hello **Alice**")
                    ),
                    toolEvents = emptyList(),
                    fallbackText = "Hello **Alice**",
                    fallbackThoughts = "",
                    contentIdentity = "private-answer",
                    isLoading = false,
                    privacyProtectedValues = values,
                    animateResponse = false,
                    responseSurface = Color.Black,
                    debugMode = true,
                    showReasoning = true,
                    expanded = true,
                    onExpandedChange = {},
                    beforeAnswer = { ChatMarkdown(content = "Alice in the activity header") }
                )
            }
        }
        compose.waitUntil(5000) {
            yellow in colours("Hello Alice") &&
                compose.onAllNodesWithText("Alice in the activity header", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        for (text in listOf("Alice in reasoning", "Alice in progress", "Alice in the activity header")) {
            assertFalse("Activity has no local protection receipt: $text", yellow in colours(text))
        }
        compose.runOnIdle { values = emptyList() }
        compose.waitUntil(5000) { yellow !in colours("Hello Alice") }
    }

    @Test
    fun aProtectedFormattedPhraseColoursItsVisibleWordsAfterMarkdownRendering() {
        val raw = "Your partner **Alice** called."
        val message = MessageV2(
            content = raw,
            platformType = "remote",
            timeline = listOf(
                AssistantTimelineItem(AssistantTimelineItemType.TEXT, raw),
                AssistantTimelineItem(
                    AssistantTimelineItemType.NOTICE,
                    privacyProtection = PrivacyProtectionReceipt.create(raw, listOf(PrivacyProtectedRange(5, raw.lastIndex)))
                )
            )
        )
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
                AssistantChronologicalContent(
                    timeline = message.timeline,
                    toolEvents = emptyList(),
                    fallbackText = message.content,
                    fallbackThoughts = "",
                    contentIdentity = "formatted-private-answer",
                    isLoading = false,
                    privacyProtectedValues = message.effectivePrivacyProtectedValues(),
                    animateResponse = false,
                    responseSurface = Color.Black,
                    debugMode = false,
                    showReasoning = false,
                    expanded = false,
                    onExpandedChange = {}
                )
            }
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Your partner Alice called.", useUnmergedTree = true).fetchSemanticsNodes()
                .flatMap { it.config[SemanticsProperties.Text] }.any { text ->
                    text.spanStyles.any { it.item.color == yellow && text.text.substring(it.start, it.end) == "Alice" }
                }
        }
    }

    @Test
    fun markdownCannotBorrowProofFromAnotherLiteralOrHiddenLinkDestination() {
        val raw = "Alice and A**li**ce\n\n[A**li**ce](https://example.com/Alice)"
        val hidden = raw.lastIndexOf("Alice")
        val message = MessageV2(
            content = raw,
            platformType = "remote",
            timeline = listOf(
                AssistantTimelineItem(
                    AssistantTimelineItemType.NOTICE,
                    privacyProtection = PrivacyProtectionReceipt.create(raw, listOf(PrivacyProtectedRange(0, 5), PrivacyProtectedRange(hidden, hidden + 5)))
                )
            )
        )
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
                CompositionLocalProvider(LocalAnswerSurface provides Color.Black, LocalPrivacyProtectedValues provides message.effectivePrivacyProtectedValues()) {
                    ChatMarkdown(content = raw, animateStreaming = false)
                }
            }
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Alice and Alice", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Alice", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val paragraph = compose.onAllNodesWithText("Alice and Alice", useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }.first { it.text == "Alice and Alice" }
        assertEquals(listOf(0 to 5), paragraph.spanStyles.filter { it.item.color == yellow }.map { it.start to it.end })
        assertFalse(yellow in colours("Alice"))
    }

    private fun colours(text: String): Set<Color> = compose.onAllNodesWithText(text, useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.flatMap { it.spanStyles }.map { it.item.color }.toSet()
}
