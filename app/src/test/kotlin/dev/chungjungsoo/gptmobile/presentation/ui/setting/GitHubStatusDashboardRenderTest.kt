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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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

    @Test fun draftFailureMenuBindsActionsToTheExactCheckAndOtherTilesStillTrack() {
        val head = "a".repeat(40)
        val pull = Json.parseToJsonElement("""{"number":42,"title":"Repair Android","draft":true,"check_state":"FAILED","head":{"sha":"$head"}}""") as JsonObject
        val failed = GitHubLiveProcess("91", "Android failure", "failure", 10, 20, runId = 91, attempt = 3, headSha = head)
        val passed = GitHubLiveProcess("92", "Repository contracts", "success", 10, 20, runId = 92, headSha = head)
        val repaired = mutableListOf<GitHubStatusActionTarget>()
        val tracked = mutableListOf<Pair<GitHubStatusActionTarget, Boolean>>()
        compose.setContent {
            GPTMobileTheme(themeMode = ThemeMode.DARK) {
                Column {
                    val actions = GitHubStatusActions(emptyList(), { target, enabled -> tracked += target to enabled }, { repaired += it })
                    GitHubStatusOverview(listOf(pull), listOf(failed, passed), "main", false, {}, actions) {}
                    GitHubPullRequestActionTile(pull, actions, enabled = true, inspect = {}, open = null)
                }
            }
        }
        compose.onNodeWithText(failed.title).performClick()
        compose.onNodeWithText("Fix").assertIsDisplayed().performClick()
        compose.onNodeWithText(failed.title).performClick()
        compose.onNodeWithText("Disable").assertIsDisplayed().performClick()
        compose.onNodeWithText(passed.title).performClick()
        compose.onNodeWithText("Fix").assertDoesNotExist()
        compose.onNodeWithText("Track").assertIsDisplayed().performClick()
        compose.onNodeWithText("Repair Android").performClick()
        compose.onNodeWithText("Fix").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(GitHubStatusActionTarget(head, 42, 91, 3, true, true), GitHubStatusActionTarget(head, 42, null, null, true, true)),
                repaired
            )
            assertEquals(listOf(91L to false, 92L to true), tracked.map { it.first.runId to it.second })
        }
    }

    @Test fun realThemeNetworkVisualRendersAndBubbleTapSelectsExactSourceNode() {
        val nodes = listOf(
            Json.parseToJsonElement("""{"id":"app","name":"Application","qualified":"Application","kind":"class"}""") as JsonObject,
            Json.parseToJsonElement("""{"id":"gateway","name":"Gateway","qualified":"Gateway","kind":"community"}""") as JsonObject,
            Json.parseToJsonElement("""{"id":"tests","name":"Tests","qualified":"Tests","kind":"method"}""") as JsonObject
        )
        val edges = listOf(Json.parseToJsonElement("""{"source":"app","target":"gateway","relation":"calls"}""") as JsonObject)
        var selected = ""
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent {
            GPTMobileTheme(themeMode = ThemeMode.DARK) {
                Surface {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        GraphMiniMap(nodes, edges) { selected = it["id"]?.jsonPrimitive?.content.orEmpty() }
                    }
                }
            }
        }
        compose.onNodeWithTag("github_repository_bubble_network").assertIsDisplayed()
        compose.onNodeWithContentDescription("Interactive repository network with 3 source-backed bubbles").assertIsDisplayed()
        compose.onNodeWithTag("github_repository_bubble_network").performTouchInput { click(percentOffset(.01f, .01f)) }
        compose.runOnIdle { assertEquals("", selected) }
        compose.onNodeWithTag("github_repository_node_app").performClick()
        compose.runOnIdle { assertEquals("app", selected) }
        val ids = listOf("app", "gateway", "tests")
        val positions = ids.map { compose.onNodeWithTag("github_repository_node_$it").fetchSemanticsNode().boundsInRoot }
        compose.runOnIdle { direction = LayoutDirection.Rtl }
        ids.forEachIndexed { index, id ->
            assertEquals("$id must stay at its Canvas edge coordinate in RTL", positions[index], compose.onNodeWithTag("github_repository_node_$id").fetchSemanticsNode().boundsInRoot)
        }
        val rtlLabels = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Gateway", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(rtlLabels) }
        assertEquals(LayoutDirection.Rtl, rtlLabels.single().layoutInput.layoutDirection)
        compose.onNodeWithTag("github_repository_node_gateway").performClick()
        compose.runOnIdle { assertEquals("gateway", selected) }
    }

    @Test fun statusMapUsesServerPagesAndSymbolSearchAtLargeFontSize() {
        val paths = (0..11).joinToString(",") { "\"app/File${it.toString().padStart(2, '0')}.kt\"" }
        val map = Json.parseToJsonElement("""{"communities":[{"files":[$paths]}],"dependencies":{"app/File00.kt":["app/File01.kt"]},"has_more":true,"next_cursor":"second-page"}""") as JsonObject
        val next = Json.parseToJsonElement("""{"nodes":[{"id":"last-file","path":"app/File12.kt","kind":"file"}],"has_more":false}""") as JsonObject
        val symbols = Json.parseToJsonElement("""{"nodes":[{"id":"hidden-symbol","qualified":"Hidden.method","path":"app/File00.kt","kind":"function"}],"has_more":false}""") as JsonObject
        var context by mutableStateOf(GitHubConversationContext("github", "mobile", "app", "main", "a".repeat(40), "selected-profile"))
        var gatewayUid by mutableStateOf("gateway-one")
        var state by mutableStateOf(RepositoryGraphUiState(architecture = map, mapNextCursor = "second-page"))
        var selected = ""
        var refreshes = 0
        var searches = 0
        var pages = 0
        compose.setContent {
            GPTMobileTheme(themeMode = ThemeMode.DARK) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.4f)) {
                    Surface(Modifier.width(320.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            RepositoryGraphStatusContent(
                                context,
                                state.copy(gatewayProfileUid = gatewayUid),
                                onRefresh = { refreshes++ },
                                onIndex = {},
                                onStop = {},
                                onSelect = { selected = it["id"]?.jsonPrimitive?.content.orEmpty() },
                                onNextPage = {
                                    pages++
                                    state = state.copy(architecture = next, mapPage = 1, mapNextCursor = null)
                                },
                                onPreviousPage = { state = state.copy(architecture = map, mapPage = 0, mapNextCursor = "second-page") },
                                onSearch = { term ->
                                    searches++
                                    assertEquals("Hidden", term)
                                    state = state.copy(searchResult = symbols, mapQuery = term, mapPage = 0, mapNextCursor = null)
                                }
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
        compose.onNodeWithTag("github_repository_node_last-file").assertExists()
        compose.runOnIdle { assertEquals(1, pages) }
        compose.onNodeWithTag("github_repository_map_search").performScrollTo().performTextInput("Hidden")
        compose.runOnIdle { assertEquals(0, searches) }
        compose.onNodeWithTag("github_repository_map_search_submit").performScrollTo().performClick()
        compose.onNodeWithTag("github_repository_node_hidden-symbol").assertExists()
        compose.runOnIdle {
            assertEquals(1, searches)
            context = context.copy(commitSha = "b".repeat(40))
            state = RepositoryGraphUiState(architecture = map, mapNextCursor = "second-page")
        }
        compose.onNodeWithTag("github_repository_map_selection").assertDoesNotExist()
        compose.onNodeWithContentDescription("Interactive repository network with 12 source-backed bubbles").assertExists()
        compose.onNodeWithTag("github_repository_node_app/File00.kt").performScrollTo().performClick()
        compose.runOnIdle { gatewayUid = "gateway-two" }
        compose.onNodeWithTag("github_repository_map_selection").assertDoesNotExist()
        compose.onNodeWithText("Refresh").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        val directory = File("build/reports/reliability-ui").apply { mkdirs() }
        File(directory, "repository-map-dark-large.png").outputStream().use { output ->
            compose.onNodeWithTag("github_status_repository_map").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    @Test fun blockedRepositoryMapOffersHostRepairWithoutAnIndexOrInventedRelationships() {
        val context = GitHubConversationContext("github", "mobile", "app", "main", "a".repeat(40), "selected-profile")
        val error = "GATEWAY_ENGINEERING_GITHUB_REQUIRED: Configure the GitHub connection on the selected gateway host."
        var repairs = 0
        var refreshes = 0
        var builds = 0
        lateinit var clipboard: ClipboardManager
        renderRepositoryMap(
            context,
            { RepositoryGraphUiState(error = error, repairAction = "CONFIGURE_SELECTED_GATEWAY_HOST_GITHUB", gatewayName = "My desktop gateway", gatewayProfileUid = "gateway") },
            ThemeMode.DARK,
            onRefresh = { refreshes++ },
            onIndex = { builds++ },
            onConfigure = { repairs++ },
            clipboard = { clipboard = it }
        )
        compose.onNodeWithText("Connect GitHub on your gateway").assertIsDisplayed()
        compose.onNodeWithTag("github_repository_map_index").assertDoesNotExist()
        compose.onNodeWithTag("github_repository_bubble_network").assertDoesNotExist()
        compose.onNodeWithTag("github_repository_map_legend").assertDoesNotExist()
        compose.onNodeWithContentDescription("Repository revision ${context.commitSha}").assertExists()
        compose.onNodeWithText("Revision ${context.commitSha.take(10)}").assertExists()
        compose.onNodeWithTag("github_repository_map_copy_revision").performClick()
        compose.runOnIdle { assertEquals(context.commitSha, clipboard.getText()?.text) }
        compose.onNodeWithTag("github_repository_map_configure").performScrollTo().performClick()
        compose.onNodeWithTag("github_repository_map_recheck").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, repairs)
            assertEquals(1, refreshes)
            assertEquals(0, builds)
        }
        compose.onNodeWithTag("github_repository_map_error_details").performScrollTo().performClick()
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.onNodeWithTag("github_repository_map_error_details").performClick()
        captureRepositoryMap("repository-map-blocked-dark")
    }

    @Test fun repositoryMapBuildProgressUsesOnlyMeasuredFilesAndKeepsStopAvailable() {
        val context = GitHubConversationContext("github", "mobile", "app", "main", "b".repeat(40), "selected-profile")
        var state by mutableStateOf(RepositoryGraphUiState(busy = true, operation = "index", result = Json.parseToJsonElement("""{"graph":{"processed_files":3,"expected_files":10}}""") as JsonObject))
        var stops = 0
        renderRepositoryMap(context, { state }, ThemeMode.LIGHT, onStop = { stops++ })
        compose.onNodeWithText("Building the source map").assertIsDisplayed()
        compose.onNodeWithText("3 of 10 files processed").assertIsDisplayed()
        assertEquals(.3f, compose.onNodeWithTag("github_repository_map_progress_bar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        compose.onNodeWithTag("github_repository_map_index").assertDoesNotExist()
        compose.onNodeWithTag("github_repository_bubble_network").assertDoesNotExist()
        compose.onNodeWithTag("github_repository_map_stop").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
        captureRepositoryMap("repository-map-building-light")
        compose.runOnIdle { state = state.copy(result = Json.parseToJsonElement("""{"graph":{"processed_files":"3","expected_files":"10"}}""") as JsonObject) }
        compose.onNodeWithText("3 of 10 files processed").assertDoesNotExist()
        assertEquals(ProgressBarRangeInfo.Indeterminate, compose.onNodeWithTag("github_repository_map_progress_bar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
    }

    @Test fun anUnknownIndexOutcomeRequiresAStatusReadBeforeAnotherBuild() {
        val context = GitHubConversationContext("github", "mobile", "app", "main", "c".repeat(40), "selected-profile")
        var state by mutableStateOf(RepositoryGraphUiState(result = Json.parseToJsonElement("""{"status":"NOT_INDEXED"}""") as JsonObject, resultOperation = "status"))
        var builds = 0
        var reads = 0
        renderRepositoryMap(context, { state }, ThemeMode.LIGHT, onIndex = { builds++ }, onRefresh = { reads++ })
        compose.onNodeWithTag("github_repository_map_index").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, builds)
            state = state.copy(indexOutcomeUnknown = true, error = "Index response was interrupted.")
        }
        compose.onNodeWithText("Check the saved index first").assertIsDisplayed()
        compose.onNodeWithTag("github_repository_map_index").assertDoesNotExist()
        compose.onNodeWithTag("github_repository_map_configure").assertDoesNotExist()
        compose.onNodeWithText("Check saved index").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, builds)
            assertEquals(1, reads)
        }
    }

    @Test fun failedGatewayJobShowsItsReasonAndKeepsCoverageCategoriesDistinct() {
        val context = GitHubConversationContext("github", "mobile", "app", "main", "c".repeat(40), "selected-profile")
        val status = Json.parseToJsonElement("""{"has_more":false,"graph":{"processed_files":10,"expected_files":10,"processing_complete":true,"coverage_complete":false,"retryable_files":2,"file_statuses":{"indexed":6,"partial":1,"unsupported":2,"failed":1}}}""") as JsonObject
        val job = Json.parseToJsonElement("""{"id":"saved-job","generation":2,"state":"FAILED","error_code":"INDEX_RETRY_OUTCOME_UNKNOWN"}""") as JsonObject
        renderRepositoryMap(context, { RepositoryGraphUiState(result = status, indexStatus = status, indexJob = job, durableIndexJobs = true) }, ThemeMode.DARK)
        compose.onNodeWithText("Indexing needs attention").assertIsDisplayed()
        compose.onNodeWithTag("github_repository_map_job_state").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The gateway could not confirm that the failed-file retry ran. Review the retained coverage before retrying those files. · INDEX_RETRY_OUTCOME_UNKNOWN").assertIsDisplayed()
        compose.onNodeWithText("6 indexed · 1 partial · 2 unsupported · 1 failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Processing finished with incomplete source coverage. Missing relationships remain unknown.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("github_repository_map_retry_failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry 2 failed or recoverable files").assertIsDisplayed()
    }

    @Test fun anUnselectedGatewayKeepsTheMapVisibleWithOnlyItsSetupAction() {
        val context = GitHubConversationContext("github", "mobile", "app", "main", "d".repeat(40), "cloud-inference-profile")
        var repairs = 0
        renderRepositoryMap(context, { RepositoryGraphUiState() }, ThemeMode.DARK, configured = false, onConfigure = { repairs++ })
        compose.onNodeWithText("Choose an engineering gateway").assertIsDisplayed()
        compose.onNodeWithTag("github_repository_map_refresh").assertIsNotEnabled()
        compose.onNodeWithTag("github_repository_map_index").assertDoesNotExist()
        compose.onNodeWithTag("github_repository_map_progress").assertDoesNotExist()
        compose.onNodeWithTag("github_repository_map_configure").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, repairs) }
    }

    private fun renderRepositoryMap(context: GitHubConversationContext, state: () -> RepositoryGraphUiState, theme: ThemeMode, configured: Boolean = true, onRefresh: () -> Unit = {}, onIndex: () -> Unit = {}, onStop: () -> Unit = {}, onConfigure: () -> Unit = {}, clipboard: (ClipboardManager) -> Unit = {}) {
        compose.setContent {
            GPTMobileTheme(themeMode = theme) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.4f)) {
                    clipboard(LocalClipboardManager.current)
                    Surface(Modifier.width(320.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            RepositoryGraphStatusContent(context, state(), onRefresh, onIndex, onStop, {}, onConfigure, configured)
                        }
                    }
                }
            }
        }
    }

    private fun captureRepositoryMap(name: String) {
        val file = File("build/reports/reliability-ui/$name.png").apply { parentFile.mkdirs() }
        val bitmap = compose.onNodeWithTag("github_status_repository_map").captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width >= 300 && bitmap.height > 200)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
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
