package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn
import dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerRevisionTarget
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver
import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport
import dev.chungjungsoo.gptmobile.data.agent.CombinedRecoveryFailure
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h1200dp")
@OptIn(ExperimentalTestApi::class)
class CombinedAnswerRevisionActionTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    private val question = MessageV2(id = 11, chatId = 7, platformType = null, content = "Compare the explanations in detail.")
    private val sources = listOf(CombinedModelResponse("writer", "Writer", content = "A retained source explanation."))

    @Test fun currentCompletedEditorialAnswerStartsDistinctRevisionAction() {
        val answer = retainedAnswer("current", "The answer is complete but too brief.")
        val run = run(answer, "current-root", AgentRunStatus.COMPLETED)
        val actions = display(answer, listOf(run))

        clickRevision()

        compose.runOnIdle {
            val target = actions.onlyRevision()
            assertTarget(target, answer, run, answer.content, "report-current", "current-root", ACTIVE_REVISION_LATEST)
            assertEquals(run.runId, target.currentRunId)
            assertNull(target.manifest)
        }
    }

    @Test fun archivedAnswerUsesSelectedTerminalAssessmentButKeepsLatestOwnerFence() {
        val archived = retainedAnswer("archived", "The archived answer is complete but too brief.", legacy = true).copy(ownershipFence = 4)
        val latest =
            retainedAnswer("latest", "The latest answer stopped partway through.").copy(
                ownershipFence = 81,
                timeline =
                listOf(
                    AssistantTimelineItem(
                        AssistantTimelineItemType.NOTICE,
                        combinedFailure = CombinedRecoveryFailure.fromCode("AUTHENTICATION_REJECTED")
                    )
                ),
                revisions =
                listOf(
                    AssistantRevision(
                        content = archived.content,
                        createdAt = 1,
                        runId = archived.currentRunId,
                        timeline = archived.timeline,
                        combinedSources = archived.combinedSources
                    )
                ),
                activeRevisionIndex = 0
            )
        val archivedRun = run(archived, "archived-root", AgentRunStatus.FAILED, "COMBINED_PARTIAL: requested length was unmet")
        val latestRun = run(latest, "latest-root", AgentRunStatus.FAILED, "AUTHENTICATION_REJECTED: writer access changed")
        val actions = display(latest, listOf(latestRun, archivedRun))

        showAnswer(archived.content)
        clickRevision()

        compose.runOnIdle {
            val target = actions.onlyRevision()
            assertTarget(target, latest, archivedRun, archived.content, "report-archived", "archived-root", 0)
            assertEquals(latestRun.runId, target.currentRunId)
            assertEquals(81L, target.ownershipFence)
            assertNotEquals(target.currentRunId, target.runId)
            assertNotEquals(researchHash(latest.content), target.answerDigest)
            assertNotEquals(archivedRun.ownershipFence, target.ownershipFence)
        }
    }

    @Test fun legacyTerminalErrorOnlyAssessmentOffersFreshRevisionWithoutRetryingDelivery() {
        val answer = retainedAnswer("legacy", "The legacy answer is complete but too brief.", legacy = true)
        val run = run(answer, "legacy-root", AgentRunStatus.FAILED, "COMBINED_PARTIAL: required checks")
        assertTrue(answer.timeline.none { it.combinedFailure != null })
        val actions = display(answer, listOf(run))

        clickRevision()

        compose.runOnIdle {
            val target = actions.onlyRevision()
            assertTarget(target, answer, run, answer.content, "report-legacy", "legacy-root", ACTIVE_REVISION_LATEST)
            assertEquals(run.runId, target.currentRunId)
        }
    }

    @Test fun bubbleMenuUsesTheSameSelectedRevisionAction() {
        val answer = retainedAnswer("menu", "The completed answer needs a longer explanation.", legacy = true)
        val run = run(answer, "menu-root", AgentRunStatus.FAILED, "COMBINED_PARTIAL")
        val actions = display(answer, listOf(run))

        showAnswer(answer.content)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and (hasText(answer.content) or hasAnyDescendant(hasText(answer.content))), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNode(hasText("Create revised answer") and hasAnyAncestor(isPopup())).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()

        compose.runOnIdle {
            assertTarget(actions.onlyRevision(), answer, run, answer.content, "report-menu", "menu-root", ACTIVE_REVISION_LATEST)
        }
    }

    private fun showAnswer(text: String) {
        // Answer presentation is prepared off the main thread, and the retained
        // response can start below the correction controls in the scroll viewport.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun clickRevision() {
        compose.onNodeWithText("Answer needs correction").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try another writer").assertDoesNotExist()
        compose
            .onNodeWithText("Create revised answer")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
    }

    private fun assertTarget(
        target: CombinedAnswerRevisionTarget,
        owner: MessageV2,
        selectedRun: AgentRun,
        answer: String,
        reportRevisionId: String,
        parentRootBudgetId: String,
        revisionIndex: Int
    ) {
        assertEquals(owner.id, target.messageId)
        assertEquals(question.chatId, target.chatId)
        assertEquals(question.id, target.userMessageId)
        assertEquals(owner.ownershipFence, target.ownershipFence)
        assertEquals(selectedRun.runId, target.runId)
        assertEquals(researchHash(answer), target.answerDigest)
        assertEquals(reportRevisionId, target.reportRevisionId)
        assertEquals(parentRootBudgetId, target.parentRootBudgetId)
        assertEquals(revisionIndex, target.selectedRevisionIndex)
        assertTrue(target.assessmentDigest.matches(Regex("[a-f0-9]{64}")))
    }

    private fun retainedAnswer(
        suffix: String,
        content: String,
        legacy: Boolean = false
    ): MessageV2 {
        val report =
            CombinedQualityReport(
                answerDigest = researchHash(content),
                revisionId = "report-$suffix",
                complete = false,
                status = "CORRECTION_REQUIRED",
                correctionRequired = true,
                finalAssessment = true,
                lengthCompliance = "UNMET",
                protocolComplete = true
            )
        return MessageV2(
            id = 22,
            chatId = question.chatId,
            linkedMessageId = question.id,
            platformType = "writer",
            content = content,
            currentRunId = "combined-synthesis:$suffix",
            ownershipFence = 17,
            combinedSources = sources,
            timeline =
            buildList {
                add(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = report))
                if (!legacy) {
                    add(
                        AssistantTimelineItem(
                            AssistantTimelineItemType.NOTICE,
                            combinedFailure = CombinedRecoveryFailure.fromCode("COMBINED_REQUIREMENTS_UNMET")
                        )
                    )
                }
            }
        )
    }

    private fun run(
        message: MessageV2,
        root: String,
        status: String,
        terminalError: String? = null
    ): AgentRun {
        val requested =
            CombinedPolicyResolver.accept(
                ConversationCombinedSettings(),
                listOf("writer"),
                mapOf("writer" to "model"),
                question.content,
                acceptedAtMillis = 1
            )
        val context = CombinedExecutionContext(requested, rootBudgetId = root, chatId = question.chatId)
        return AgentRun(
            runId = requireNotNull(message.currentRunId),
            chatId = question.chatId,
            userMessageId = question.id,
            assistantMessageId = message.id,
            profileUid = "writer",
            providerSnapshot = "CUSTOM",
            modelSnapshot = "model",
            status = status,
            terminalError = terminalError,
            ownershipFence = message.ownershipFence,
            synthesisSnapshot = CombinedAcceptedTurn(context = context, tools = ChatMcpToolConfig(combined = context)).encode()
        )
    }

    private class Actions {
        val revisions = mutableListOf<Pair<Int, CombinedAnswerRevisionTarget>>()
        val otherActions = mutableListOf<String>()

        fun onlyRevision(): CombinedAnswerRevisionTarget {
            assertEquals(1, revisions.size)
            assertEquals(3, revisions.single().first)
            assertEquals(emptyList<String>(), otherActions)
            return revisions.single().second
        }
    }

    private fun display(
        answer: MessageV2,
        runs: List<AgentRun>
    ): Actions {
        val actions = Actions()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ChatMessagePair(
                        replyRevision = 0,
                        replyVisible = true,
                        onAnswerDisplayed = {},
                        currentResearchTools = { ChatMcpToolConfig() },
                        messageIndex = 3,
                        message = question,
                        assistantMessages = listOf(answer),
                        agentRunsById = runs.associateBy { it.runId },
                        activeAgentRuns = emptyMap(),
                        runNoticesById = emptyMap(),
                        toolEventsByRun = emptyMap(),
                        platformIndexState = 0,
                        loadingStates = emptyList(),
                        enabledPlatformsInChat = listOf("writer"),
                        enabledPlatformLookup = mapOf("writer" to PlatformV2(uid = "writer", name = "Writer", enabled = true, model = "model")),
                        disabledPlatformUids = emptySet(),
                        activePlatformUids = setOf("writer"),
                        canUseChat = true,
                        isIdle = true,
                        isActiveMessage = false,
                        maximumUserChatBubbleWidth = 400.dp,
                        maximumOpponentChatBubbleWidth = 440.dp,
                        combinedMode = true,
                        smartSuggestionsEnabled = false,
                        debugSettings = AppFeatureSettings(researchFeature = ResearchFeatureSettings(articleCards = false)),
                        onEditQuestion = {},
                        onEditAssistant = { _, _ -> },
                        onCopyText = {},
                        onPlatformClick = { _, _ -> },
                        onPlatformLongPress = {},
                        onSelectText = {},
                        onRetry = { _, _ -> actions.otherActions += "generic retry" },
                        onRetryCombinedMerge = { actions.otherActions += "unfinished delivery retry" },
                        onTryAnotherCombinedWriter = { actions.otherActions += "another writer" },
                        onRegenerateWithSelectedPrimary = { actions.otherActions += "selected primary regeneration" },
                        onReviseCombinedAnswer = { index, target -> actions.revisions += index to target },
                        onFavoriteClick = { _, _ -> },
                        onFavoriteLongPress = {},
                        onShowPreviousRevision = { _, _ -> },
                        onShowNextRevision = { _, _ -> },
                        loadCombinedBlocks = { _, _, _ -> error("Inline answer revision action must not load a durable document") },
                        resolveCombinedMessage = { error("Answer revision action must not hydrate the Combined message") }
                    )
                }
            }
        }
        return actions
    }
}
