package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.agent.ActiveAgentRun
import dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeProgress
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.data.research.ResearchClaim
import dev.chungjungsoo.gptmobile.data.research.ResearchSession
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.capturedResearchVersion
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w400dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResearchRecoveryRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun retainedEvidenceHasSeparateWritingRecoveryAndActualRuntimeProgress() {
        val source = ResearchSource("S1", "https://example.org/source", "Captured study", "Read", "The trial enrolled 20 participants.", versionId = "v1")
        val snapshot = ResearchSnapshot(
            "Review the captured trial",
            phase = "Writing report",
            targetSources = 12,
            sources = listOf(source),
            claims = listOf(ResearchClaim("Enrollment", "S1", source.passage, exactQuoteVerified = true, reviewedSourceVersion = capturedResearchVersion(source)))
        )
        var writing by mutableStateOf(false)
        val recovery = mutableListOf<String>()
        var canceled = 0
        val report = "The captured evidence is retained for another writing attempt."
        val receipt = ResearchDeliveryReceipt(ResearchReportValidity.EVIDENCE_ONLY, true, "packet", researchHash(report), retryEligible = true)
        val native = ActiveAgentRun(
            "local-run",
            7,
            "local-profile",
            phase = LocalInferencePhase.PREFILL,
            nativeProgress = GgufNativeProgress(42, "PREFILL", promptTokens = 1891, processedTokens = 175, sampledTokens = 0, effectiveBatch = 32, prefillTokensPerSecond = 4.0, modelName = "Qwen-1.5B-Q4_K_M.gguf", actualRuntime = "llama.cpp · CPU")
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                GPTMobileTheme(themeMode = ThemeMode.DARK) {
                    Surface {
                        Column(Modifier.width(360.dp).testTag("research_recovery_surface")) {
                            ResearchProgressCard(
                                listOf(ResearchSession("research-run", 7, snapshot)),
                                if (writing) setOf("research-run") else emptySet(),
                                {},
                                writerFailure = if (writing) null else "Report needs correction",
                                onRecovery = { recovery += it },
                                deliveryReceipt = receipt,
                                cancelWriting = { canceled++ },
                                deliveryRevisionText = report
                            )
                            CompactAgentActivityBar(native, Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("1 of 12 read", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("1 readable source", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("1 readable source", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("1 claim checked against passages", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("0 independently reviewed", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Evidence only · report not accepted", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Retry writing").performClick()
        compose.onNodeWithText("Continue research").performClick()
        compose.runOnIdle { assertEquals(listOf("Retry writing", "Continue research"), recovery) }
        compose.onNodeWithText("Processing prompt · 175 / 1891 tokens · 4.0 tok/s", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Qwen-1.5B-Q4_K_M.gguf · llama.cpp · CPU", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("manifest.json").assertDoesNotExist()
        val bitmap = compose.onNodeWithTag("research_recovery_surface", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width > 300 && bitmap.height > 200)
        val file = File("build/reports/reliability-ui/research-recovery-dark.png").apply { parentFile.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        compose.runOnIdle { writing = true }
        compose.onNodeWithText("Writing answer", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Retry writing").assertDoesNotExist()
        compose.onNodeWithText("Continue research").assertDoesNotExist()
        compose.onNodeWithText("Cancel writing").performClick()
        compose.runOnIdle { assertEquals(1, canceled) }
    }

    @Test fun acceptanceIsOnlyShownForTheExactDisplayedReportRevision() {
        val report = "The captured study supports this limited finding."
        var displayed by mutableStateOf(report)
        val receipt = ResearchDeliveryReceipt(ResearchReportValidity.ACCEPTED, true, "packet", researchHash(report))
        val session = ResearchSession("research-run", 7, ResearchSnapshot("Review the captured trial", phase = "Complete", complete = true))
        compose.setContent {
            GPTMobileTheme(themeMode = ThemeMode.LIGHT) {
                Surface {
                    ResearchProgressCard(listOf(session), emptySet(), {}, writerFailure = "An earlier writer failed", onRecovery = {}, deliveryReceipt = receipt, deliveryRevisionText = displayed)
                }
            }
        }
        compose.onNodeWithText("Research complete").assertIsDisplayed()
        compose.onNodeWithText("Retry writing").assertDoesNotExist()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Report accepted").assertIsDisplayed()
        compose.runOnIdle { displayed = "$report\nAn unchecked addition." }
        compose.onNodeWithText("Current report not checked").assertIsDisplayed()
        compose.onNodeWithText("Research complete").assertDoesNotExist()
        compose.onNodeWithText("Retry writing").assertIsDisplayed()
        compose.onNodeWithText("Report accepted").assertDoesNotExist()
        compose.runOnIdle { displayed = report }
        compose.onNodeWithText("Report accepted").assertIsDisplayed()
        compose.onNodeWithText("Retry writing").assertDoesNotExist()
    }
}
