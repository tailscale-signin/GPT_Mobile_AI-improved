package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real production Compose surface, rendered with the app's theme and native Android graphics. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w600dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GitHubRepositoryInsightsRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightSessionShowsRevisionCoverageAndActions() = render("workspace-light", 392, 1f, ThemeMode.LIGHT)

    @Test fun narrowDarkSessionWrapsLargeTextAndKeepsControlsReachable() = render("workspace-dark-large", 320, 1.4f, ThemeMode.DARK)

    private fun render(name: String, width: Int, fontScale: Float, theme: ThemeMode) {
        var explainCalls = 0
        var reviewCalls = 0
        val state = GitHubWorkspaceState(
            login = "tailscale-signin",
            selection = GitHubRepositoryContext("tailscale-signin", "GPT_Mobile_AI-improved", "fix/research-delivery"),
            headSha = "5ca4cead455f61742fb223501063d6690f0a9c9a",
            canWrite = true,
            observedAt = 1_792_526_400_000,
            indexStatus = Json.parseToJsonElement("""{"commit_sha":"5ca4cead455f61742fb223501063d6690f0a9c9a","indexed_blobs":127,"total_paths":3008,"complete":false}""").jsonObject,
            efficiency = Json.parseToJsonElement("""{"requests":{"physical_requests":18,"cache_hits":42,"singleflight_joins":3,"downloaded_bytes":49152,"active_requests":0,"queued_requests":0}}""").jsonObject,
            reviewBaseline = "4433221100abcdef"
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                GPTMobileTheme(themeMode = theme) {
                    Surface {
                        Column(Modifier.width(width.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                            GitHubRepositoryInsights(state, { _, _ -> }, {}, { explainCalls++ }, { reviewCalls++ }, {}, {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("127 of 3008 source blobs indexed · partial coverage", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Explain failed CI").performClick()
        compose.onNodeWithText("Session details").performClick()
        compose.onNodeWithText("Mark revision reviewed").performClick()
        compose.runOnIdle {
            assertEquals(1, explainCalls)
            assertEquals(1, reviewCalls)
        }
        compose.onNodeWithText("18 API requests · 42 cache reuses · 3 shared reads", useUnmergedTree = true).assertIsDisplayed()
        val surface = compose.onNodeWithTag("github_repository_session", useUnmergedTree = true).assertIsDisplayed()
        val bounds = surface.fetchSemanticsNode().boundsInRoot
        assertEquals((width - 24).toFloat(), bounds.width, 1f)
        val textNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(textNodes.isNotEmpty())
        compose.runOnIdle {
            for (node in textNodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
                for (layout in layouts) {
                    val input = layout.layoutInput
                    val measured = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(
                        text = input.text,
                        style = input.style,
                        overflow = input.overflow,
                        softWrap = input.softWrap,
                        maxLines = input.maxLines,
                        placeholders = input.placeholders,
                        constraints = Constraints(maxWidth = layout.size.width, maxHeight = layout.size.height)
                    )
                    assertFalse("Session text must wrap without clipping: ${input.text}", measured.hasVisualOverflow)
                    assertTrue("Text must remain inside the session", node.boundsInRoot.left >= bounds.left && node.boundsInRoot.right <= bounds.right)
                }
            }
        }
        val bitmap = surface.captureToImage().asAndroidBitmap()
        val colors = buildSet {
            for (x in 0 until bitmap.width step 5) for (y in 0 until bitmap.height step 5) add(bitmap.getPixel(x, y))
        }
        assertTrue("The actual renderer must produce nonblank pixels", colors.size > 20)
        val output = File("build/reports/reliability-ui/$name.png").apply { parentFile.mkdirs() }
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        println("Workspace native render: ${output.absolutePath}")
    }
}
