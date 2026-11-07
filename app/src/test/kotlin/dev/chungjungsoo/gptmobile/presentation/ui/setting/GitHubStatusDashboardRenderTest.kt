package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubLiveProcess
import dev.chungjungsoo.gptmobile.data.github.GitHubLiveStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardObservation
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardPhase
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardState
import dev.chungjungsoo.gptmobile.data.network.gateway.dashboardFixture
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayDashboardTarget
import dev.chungjungsoo.gptmobile.data.network.gateway.parseGatewayDashboard
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.ui.chat.GraphMiniMap
import dev.chungjungsoo.gptmobile.presentation.ui.chat.RepositoryGraphStatusContent
import dev.chungjungsoo.gptmobile.presentation.ui.chat.RepositoryGraphUiState
import java.io.File
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real production summary, check bubbles, and live gateway content; no replacement screen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w600dp-h4200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GitHubStatusDashboardRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightDashboardKeepsMeasuredStatusAndActionsVisible() = render("status-light", 392, 1f, ThemeMode.LIGHT)

    @Test fun compactDarkDashboardKeepsUnknownValuesAndDeviceActionsReadable() = render("status-dark-compact-large", 320, 1.4f, ThemeMode.DARK)

    @Test fun realThemeNetworkVisualRendersAndBubbleTapSelectsExactSourceNode() {
        val nodes = listOf(
            Json.parseToJsonElement("""{"id":"app","name":"Application","qualified":"Application","kind":"class"}""") as JsonObject,
            Json.parseToJsonElement("""{"id":"gateway","name":"Gateway","qualified":"Gateway","kind":"community"}""") as JsonObject,
            Json.parseToJsonElement("""{"id":"tests","name":"Tests","qualified":"Tests","kind":"method"}""") as JsonObject
        )
        val edges = listOf(Json.parseToJsonElement("""{"source":"app","target":"gateway","relation":"calls"}""") as JsonObject)
        var selected = ""
        compose.setContent {
            GPTMobileTheme(themeMode = ThemeMode.DARK) {
                Surface {
                    GraphMiniMap(nodes, edges) { selected = it["id"]?.jsonPrimitive?.content.orEmpty() }
                }
            }
        }
        compose.onNodeWithTag("github_repository_bubble_network").assertIsDisplayed()
        compose.onNodeWithContentDescription("Interactive repository network with 3 source-backed bubbles").assertIsDisplayed()
        compose.onNodeWithTag("github_repository_bubble_network").performTouchInput { click(percentOffset(.01f, .01f)) }
        compose.runOnIdle { assertEquals("", selected) }
        compose.onNodeWithTag("github_repository_node_app").performClick()
        compose.runOnIdle { assertEquals("app", selected) }
    }

    @Test fun statusArchitectureMapPagesSearchesAndInspectsRealFilesAtLargeFontSize() {
        val paths = (0..12).joinToString(",") { "\"app/File${it.toString().padStart(2, '0')}.kt\"" }
        val map = Json.parseToJsonElement("""{"communities":[{"id":"app","name":"app","files":[$paths]}],"dependencies":{"app/File00.kt":["app/File01.kt"]}}""") as JsonObject
        var context by mutableStateOf(GitHubConversationContext("github", "mobile", "app", "main", "a".repeat(40), "selected-profile"))
        var selected = ""
        var refreshes = 0
        compose.setContent {
            GPTMobileTheme(themeMode = ThemeMode.DARK) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.4f)) {
                    Surface(Modifier.width(320.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            RepositoryGraphStatusContent(
                                context,
                                RepositoryGraphUiState(architecture = map),
                                onRefresh = { refreshes++ },
                                onIndex = {},
                                onStop = {},
                                onSelect = { selected = it["id"]?.jsonPrimitive?.content.orEmpty() }
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Interactive repository network with 12 source-backed bubbles").assertIsDisplayed()
        compose.onNodeWithTag("github_repository_node_app/File00.kt").performClick()
        compose.runOnIdle { assertEquals("app/File00.kt", selected) }
        compose.onNodeWithTag("github_repository_map_selection").assertExists()
        compose.onNodeWithTag("github_repository_map_next").performScrollTo().performClick()
        compose.onNodeWithTag("github_repository_node_app/File12.kt").assertExists()
        compose.onNodeWithTag("github_repository_map_search").performScrollTo().performTextInput("File00")
        compose.onNodeWithContentDescription("Interactive repository network with 1 source-backed bubbles").assertExists()
        compose.runOnIdle { context = context.copy(commitSha = "b".repeat(40)) }
        compose.onNodeWithTag("github_repository_map_selection").assertDoesNotExist()
        compose.onNodeWithContentDescription("Interactive repository network with 12 source-backed bubbles").assertExists()
        compose.onNodeWithText("Refresh").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        val directory = File("build/reports/reliability-ui").apply { mkdirs() }
        File(directory, "repository-map-dark-large.png").outputStream().use { output ->
            compose.onNodeWithTag("github_status_repository_map").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun render(name: String, width: Int, fontScale: Float, theme: ThemeMode) {
        val now = 100_000L
        val sha = "a".repeat(40)
        val rows = listOf(
            GitHubLiveProcess("failed", "Build and test", "failure", 10, 20, headSha = sha),
            GitHubLiveProcess("passed", "Code quality", "success", 10, 20, headSha = sha),
            GitHubLiveProcess("pending", "Android package", "in_progress", 10, null, headSha = sha)
        )
        val status = GitHubLiveStatus(rows = rows, phase = "READY", checkedAt = now)
        val selection = GitHubRepositoryContext("mobile", "gateway-app", "feature/status")
        val pull = Json.parseToJsonElement("""{"number":42,"title":"Refresh the mobile dashboard","draft":true,"head":{"sha":"$sha"},"html_url":"https://github.com/mobile/gateway-app/pull/42"}""") as JsonObject
        val profile = PlatformV2(uid = "selected", name = "Windows gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.2:8090/v1")
        val fixture = dashboardFixture()
        val usage = fixture["usage"] as JsonObject
        val days = JsonArray(
            (0..6).map { day ->
                buildJsonObject {
                    put("day", LocalDate.of(2026, 10, 30).plusDays(day.toLong()).toString())
                    put("outputTokens", 1000 + day * 400)
                    put("missingMeasurements", 0)
                }
            }
        )
        val snapshot = parseGatewayDashboard(JsonObject(fixture + ("usage" to JsonObject(usage + mapOf("tokensPerSecond" to JsonNull, "days" to days)))))
        val gateway = GatewayDashboardState(gatewayDashboardTarget(profile.uid, listOf(profile), null), GatewayDashboardPhase.LIVE, GatewayDashboardObservation(snapshot, now))
        var repositoryRefreshes = 0
        var gatewayRefreshes = 0
        var inspected = ""
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                GPTMobileTheme(themeMode = theme) {
                    Surface {
                        Column(Modifier.width(width.dp).verticalScroll(rememberScrollState()).padding(12.dp).testTag("status_dashboard_render"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            GitHubRepositoryStatusSummary(selection, gitHubStatusSummary(status, now), 1, profile.name, { repositoryRefreshes++ }, {})
                            GitHubGatewayStatusContent(gateway, now) { gatewayRefreshes++ }
                            GitHubStatusOverview(listOf(pull), rows, selection.ref, false, {}) { inspected = it.id }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Checks need attention").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh repository status").performClick()
        compose.onNodeWithContentDescription("Refresh gateway").performScrollTo().performClick()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Not measured", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val unknownNode = compose.onNodeWithText("Not measured", useUnmergedTree = true).fetchSemanticsNode()
        compose.runOnIdle {
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(unknownNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
            layouts.forEach { layout ->
                val input = layout.layoutInput
                val measured = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(input.text, input.style, maxLines = input.maxLines, constraints = Constraints(maxWidth = layout.size.width, maxHeight = layout.size.height))
                assertFalse("Unknown measurements must remain fully readable", measured.hasVisualOverflow)
            }
        }
        compose.onNodeWithText("GPT Mobile · Android").performScrollTo().performClick()
        compose.onNodeWithText("Observed connection", substring = true).assertIsDisplayed()
        compose.onNodeWithText("GPT Mobile · Android").performClick()
        compose.onNodeWithText("Build and test").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, repositoryRefreshes)
            assertEquals(1, gatewayRefreshes)
            assertEquals("failed", inspected)
        }
        compose.onNodeWithTag("github_status_summary").performScrollTo()
        val image = compose.onNodeWithTag("status_dashboard_render").captureToImage().asAndroidBitmap()
        val colors = buildSet { for (x in 0 until image.width step 8) for (y in 0 until image.height step 8) add(image.getPixel(x, y)) }
        assertTrue("Native rendering must contain actual content", colors.size > 20)
        val file = File("build/reports/reliability-ui/$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        println("Status native render: ${file.absolutePath}")
    }
}
