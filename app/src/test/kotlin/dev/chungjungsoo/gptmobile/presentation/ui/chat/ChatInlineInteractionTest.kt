package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chungjungsoo.gptmobile.data.agent.tool.McpInputRequest
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import dev.chungjungsoo.gptmobile.data.permissions.ToolApproval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h1000dp-mdpi")
class ChatInlineInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exactApprovalInConversationCannotAcquireRememberedPermission() {
        var once = 0
        var persistent = 0
        val approval = ToolApproval("approval", "run", "GitHub", "merge_pull_request", "hash", GitHubAuthorizationBoundary.EXACT_APPROVAL_PREFIX + " owner/repo PR 7 at exact SHA")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ToolApprovalDialog(approval, {}, { once++ }, { persistent++ }, { persistent++ }, inline = true)
                }
            }
        }
        compose.onNodeWithTag("chat_inline_interaction").assertIsDisplayed()
        compose.onNodeWithText("Always allow this tool · all models").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, once) }
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, once)
            assertEquals(0, persistent)
        }
    }

    @Test fun inlineMultipleChoiceRequiresAnAnswerAndReturnsOnlySchemaValidatedInput() {
        var answer: JsonObject? = null
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"layout":{"type":"string","title":"Layout","enum":["Compact","Spacious"]}},"required":["layout"]}""") as JsonObject
        val request = McpInputRequest("request", "Repository tool", "Choose the layout", schema, CompletableDeferred())
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    McpInputDialog(request, inline = true) { id, value ->
                        assertEquals("request", id)
                        answer = value
                    }
                }
            }
        }
        val submit = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>().getString(dev.chungjungsoo.gptmobile.R.string.mcp_input_dialog_label_3)
        compose.onNodeWithText(submit).assertIsNotEnabled()
        compose.onNodeWithText("Compact").performScrollTo().performClick()
        compose.onNodeWithText(submit).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(JsonPrimitive("Compact"), answer?.get("layout")) }
    }
}
