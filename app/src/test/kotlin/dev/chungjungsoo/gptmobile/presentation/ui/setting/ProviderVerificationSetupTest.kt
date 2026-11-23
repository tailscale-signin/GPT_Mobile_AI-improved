package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.privacy.OpenRouterPrivacySetup
import dev.chungjungsoo.gptmobile.data.privacy.ProviderApprovalSource
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProviderVerificationSetupTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun savedAccountReadFailureLeavesPageUsableAndRetryLoadsSetup() {
        val source = mockk<ProviderApprovalSource>()
        every { source.managedSetup() } answers {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            throw IOException("Saved setup is temporarily unavailable")
        }
        val setup = setup(source)
        compose.setContent { MaterialTheme { ProviderVerificationSetup(source, setup, listOf(profile)) } }
        waitFor("privacy_setup_read_error")
        compose.onNodeWithTag("privacy_setup_read_error").assertIsDisplayed()
        compose.onNodeWithTag("privacy_setup_loading").assertDoesNotExist()

        every { source.managedSetup() } answers {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            false
        }
        every { source.personalSetupSummary(profile) } returns null
        compose.onNodeWithTag("privacy_setup_retry_read").performClick()
        waitFor("privacy_setup_management_key")
        compose.onNodeWithTag("privacy_setup_management_key").assertIsDisplayed()
        compose.onNodeWithTag("privacy_setup_read_error").assertDoesNotExist()
    }

    @Test
    fun changingProfilesWhileSavedReadIsBlockedDoesNotPublishItsStaleSummary() {
        val source = mockk<ProviderApprovalSource>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val replacement = profile.copy(uid = "replacement", name = "Replacement profile")
        every { source.managedSetup() } answers {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            false
        }
        every { source.personalSetupSummary(profile) } answers {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            entered.countDown()
            try {
                check(release.await(10, TimeUnit.SECONDS))
                "Old account summary"
            } finally {
                finished.countDown()
            }
        }
        every { source.personalSetupSummary(replacement) } returns "Current account summary"
        val setup = setup(source)
        var profiles by mutableStateOf(listOf(profile))
        try {
            compose.setContent { MaterialTheme { ProviderVerificationSetup(source, setup, profiles) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.onNodeWithTag("privacy_setup_loading").assertIsDisplayed()
            compose.runOnIdle { profiles = listOf(replacement) }
            waitFor("privacy_setup_saved_status")
            compose.onNodeWithText("Current account summary").assertIsDisplayed()
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            compose.waitForIdle()
            compose.onNodeWithText("Current account summary").assertIsDisplayed()
            compose.onNodeWithText("Old account summary").assertDoesNotExist()
        } finally {
            release.countDown()
        }
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun setup(source: ProviderApprovalSource) = OpenRouterPrivacySetup(
        authority = source,
        transport = { _, _ -> error("Reading saved setup must not send account requests") },
        currentPolicyDigest = { error("Reading saved setup must not fetch policy documents") }
    )

    private val profile = PlatformV2(
        uid = "saved-profile",
        name = "Saved OpenRouter",
        compatibleType = ClientType.OPENROUTER,
        apiUrl = "https://openrouter.ai/api/v1",
        token = "test-inference-key"
    )
}
