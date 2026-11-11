package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubGatewayConnectionCheckTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readinessActionUsesTheSelectedGatewayAndDisablesAfterItsRemoval() {
        var selected by mutableStateOf<String?>("engineering-host")
        val checked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column { GitHubGatewayConnectionCheck(selected) { checked += it } }
            }
        }
        compose.onNodeWithTag("github_check_gateway").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("engineering-host"), checked)
            selected = "other-selected-host"
        }
        compose.onNodeWithTag("github_check_gateway").performClick()
        compose.runOnIdle {
            assertEquals(listOf("engineering-host", "other-selected-host"), checked)
            selected = null
        }
        compose.onNodeWithTag("github_check_gateway").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(2, checked.size) }
    }
}
