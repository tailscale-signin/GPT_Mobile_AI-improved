package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.chungjungsoo.gptmobile.data.chat.CompletedGeneration
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationProgressPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val progressNode = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test fun completionFlashesOnlyAfterVisibleActivityAndTerminalBarsDoNotReplay() {
        val complete = ConversationWorkProgress(1f, "Complete", running = false, userMessageId = 10)
        var work by mutableStateOf<ConversationWorkProgress?>(complete)
        compose.setContent { MaterialTheme { ConversationProgressBar(work, animate = false) } }
        compose.onNode(progressNode).assertDoesNotExist()

        compose.runOnIdle { work = ConversationWorkProgress(0.7f, "Writing", running = true, userMessageId = 10) }
        compose.onNode(progressNode).assertExists()
        // Active-run release can reach Home before the exact delivery receipt does.
        compose.runOnIdle { work = null }
        compose.onNode(progressNode).assertDoesNotExist()
        compose.runOnIdle { work = complete }
        compose.onNode(progressNode).assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f)))
        compose.waitUntil(3_000) { compose.onAllNodes(progressNode).fetchSemanticsNodes().isEmpty() }

        // A terminal snapshot arriving again cannot recreate the dismissed indicator.
        compose.runOnIdle { work = null }
        compose.runOnIdle { work = complete }
        compose.onNode(progressNode).assertDoesNotExist()
        compose.runOnIdle { work = ConversationWorkProgress(0.4f, "Generating", running = true, userMessageId = 20) }
        compose.onNode(progressNode).assertExists()
        compose.runOnIdle { work = null }
        compose.onNode(progressNode).assertDoesNotExist()
    }

    @Test fun progressUsesTheTextColumnAndAcknowledgementUpdatesTheResearchIcon() {
        val room = ChatRoomV2(id = 1, title = "Conversation", isFavorite = true)
        val published = CompletedGeneration("reply", 1, 11, 100, revision = 1, contentDigest = "digest")
        var unread by mutableStateOf(listOf(published))
        compose.setContent {
            MaterialTheme {
                ChatListItem(
                    chatRoom = room,
                    idx = 0,
                    chatListState = HomeViewModel.ChatListState(chats = listOf(room)),
                    isGenerating = false,
                    hasUnreadResponse = homeUnreadReplies(unread, emptyMap()).any { it.visibleChatId == room.id },
                    usingPlatform = "AI profile",
                    profileLabels = emptyList(),
                    isResearch = true,
                    onItemClick = {},
                    onItemLongClick = {},
                    animateStatus = false,
                    workProgress = ConversationWorkProgress(0.5f, "Researching", running = true, userMessageId = 10)
                )
            }
        }
        val title = compose.onNodeWithText("Conversation", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val profiles = compose.onNodeWithText("AI profile", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bar = compose.onNode(progressNode, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(title.left, bar.left, 0.5f)
        assertEquals(profiles.right, bar.right, 0.5f)
        compose.onNodeWithContentDescription("Research Chat · unread reply", useUnmergedTree = true).assertExists()
        compose.runOnIdle { unread = emptyList() }
        compose.onNodeWithContentDescription("Research Chat · unread reply", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Research Chat · read", useUnmergedTree = true).assertExists()
    }
}
