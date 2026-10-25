package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock
import dev.chungjungsoo.gptmobile.data.agent.CombinedPassageLink
import dev.chungjungsoo.gptmobile.data.agent.CombinedPassageRecord
import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Rule
import org.junit.Test

class CombinedFinalizationLifecycleInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun finalizationWhilePointerIsDownAndScrollingDisposesThePreviousRenderer() {
        val body = "A preserved paragraph with contributor attribution and a stable document identity.\n\n".repeat(70)
        val digest = researchHash(body)
        val link = CombinedPassageLink(0, body.length, digest, setOf("profile"), setOf("claim"))
        val passage = CombinedPassageRecord(0, body.length, digest, setOf("profile"), links = listOf(link), linkVersion = 1, origin = "LOCAL_CLAIM_MAPPING_V2")
        var finalized by mutableStateOf(false)
        var revision by mutableIntStateOf(0)
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("answer")) {
                    if (visible) {
                        Box(Modifier.verticalScroll(rememberScrollState())) {
                            AssistantChronologicalContent(
                                timeline = listOf(
                                    AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = body),
                                    AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = CombinedQualityReport(answerDigest = digest, revisionId = "revision-$revision", passages = listOf(passage)))
                                ),
                                toolEvents = emptyList(),
                                fallbackText = body,
                                fallbackThoughts = "",
                                contentIdentity = "message-3",
                                isLoading = !finalized,
                                animateResponse = false,
                                debugMode = false,
                                showReasoning = false,
                                expanded = false,
                                onExpandedChange = {},
                                combinedWording = CombinedWordingPresentation(listOf(CombinedModelResponse("profile", "Profile", content = body)), mapOf("profile" to Color.Blue), listOf(passage), body),
                                combinedManifest = if (finalized) CombinedManifestReference("manifest-$revision", "run", "revision-$revision", body.length, 1, digest) else null,
                                loadCombinedBlocks = { _, _, _ -> listOf(CombinedDocumentBlock(0, 0, body, digest, listOf(passage))) }
                            )
                        }
                    }
                }
            }
        }
        repeat(12) {
            compose.waitForIdle()
            compose.onNodeWithTag("answer").performTouchInput { down(center) }
            compose.runOnIdle {
                finalized = !finalized
                revision++
            }
            compose.onNodeWithTag("answer").performTouchInput { up() }
            compose.onNodeWithTag("answer").performTouchInput { swipeUp() }
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            compose.runOnIdle { visible = true }
        }
        compose.waitForIdle()
    }
}
