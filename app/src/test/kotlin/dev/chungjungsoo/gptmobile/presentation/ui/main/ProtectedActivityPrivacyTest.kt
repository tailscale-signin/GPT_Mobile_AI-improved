package dev.chungjungsoo.gptmobile.presentation.ui.main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.chungjungsoo.gptmobile.data.security.DevicePrivacySettings
import dev.chungjungsoo.gptmobile.data.security.DevicePrivacySnapshot
import dev.chungjungsoo.gptmobile.presentation.common.PrivateConversationScreenProtection
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtectedActivityPrivacyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val state = MutableStateFlow<DevicePrivacySnapshot?>(DevicePrivacySnapshot(secureScreen = false, appLock = false, privacyMode = true))

    @Before fun setUp() {
        mockkObject(DevicePrivacySettings)
        every { DevicePrivacySettings.state } returns state
        every { DevicePrivacySettings.initialize(any()) } returns Unit
    }

    @After fun tearDown() {
        unmockkObject(DevicePrivacySettings)
    }

    @Test fun optionsAvailabilityNeverSecuresOrdinaryChatsButVisiblePrivateContentDoes() {
        val controller = Robolectric.buildActivity(ProtectedActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertFalse(activity.secured())
            val first = activity.protectPrivateConversation()
            val second = activity.protectPrivateConversation()
            assertTrue(activity.secured())

            state.value = DevicePrivacySnapshot(secureScreen = false, appLock = false, privacyMode = false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.secured())
            first()
            first()
            assertTrue(activity.secured())
            second()
            assertFalse(activity.secured())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun releasingPrivateContentPreservesExplicitSecureScreenAndAppLockProtection() {
        for (privacy in listOf(DevicePrivacySnapshot(true, false), DevicePrivacySnapshot(false, true), null)) {
            state.value = privacy
            val controller = Robolectric.buildActivity(ProtectedActivity::class.java).create()
            try {
                val activity = controller.get()
                val release = activity.protectPrivateConversation()
                assertTrue(activity.secured())
                release()
                assertTrue(activity.secured())
            } finally {
                controller.destroy()
            }
        }
    }

    @Test fun restoredCaptionsStaySecureUntilTheRoomPolicyLoadsAndReleaseOnlyTheirOwnLease() {
        val controller = Robolectric.buildActivity(ProtectedActivity::class.java).setup().visible()
        val activity = controller.get()
        val previousCallback = activity.window.callback
        val drawnFrames = mutableListOf<Pair<String, Boolean>>()
        val loadingWindowFlags = mutableListOf<Boolean>()
        var observeLoadingTransition = false
        var chatId by mutableIntStateOf(-1)
        var privacyLocked by mutableStateOf(false)
        var caption by mutableStateOf("Restored private caption")
        var visible by mutableStateOf(true)
        var otherScreenRelease: (() -> Unit)? = null
        activity.window.callback = object : Window.Callback by previousCallback {
            override fun onWindowAttributesChanged(attributes: WindowManager.LayoutParams) {
                if (observeLoadingTransition) loadingWindowFlags += attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                previousCallback.onWindowAttributesChanged(attributes)
            }
        }
        try {
            // Process restoration can recover captions before the asynchronous Room read.
            // Mount them in the same real window whose policy the production helper owns.
            compose.runOnUiThread {
                activity.setContent {
                    if (visible) {
                        PrivateConversationScreenProtection(chatId, privacyLocked)
                        Box(
                            Modifier.fillMaxSize().drawWithContent {
                                drawnFrames += caption to activity.secured()
                                drawContent()
                            }
                        ) { BasicText(caption) }
                    }
                }
            }
            fun assertFrame(text: String, secure: Boolean) {
                compose.onNodeWithText(text).assertIsDisplayed()
                compose.runOnIdle {
                    val view = activity.window.decorView
                    val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                    } finally {
                        bitmap.recycle()
                    }
                    assertTrue("Expected a rendered caption frame", drawnFrames.isNotEmpty())
                    assertEquals("The rendered caption must use its window's secure flag", text to secure, drawnFrames.last())
                }
            }

            assertFrame("Restored private caption", secure = true)
            compose.runOnIdle {
                observeLoadingTransition = true
                chatId = 0
            }
            assertFrame("Restored private caption", secure = true)
            compose.runOnIdle {
                chatId = 17
                privacyLocked = true
            }
            assertFrame("Restored private caption", secure = true)
            compose.runOnIdle {
                assertTrue("Loading a private room must never clear its window protection", loadingWindowFlags.all { it })
                observeLoadingTransition = false
                otherScreenRelease = activity.protectPrivateConversation()
                chatId = 18
                privacyLocked = false
                caption = "Ordinary conversation"
            }
            assertFrame("Ordinary conversation", secure = true)
            compose.runOnIdle {
                otherScreenRelease?.invoke()
                otherScreenRelease = null
            }
            assertFrame("Ordinary conversation", secure = false)
            compose.runOnIdle {
                chatId = 19
                privacyLocked = true
                caption = "Another private caption"
            }
            assertFrame("Another private caption", secure = true)
            compose.runOnIdle { visible = false }
            compose.onNodeWithText("Another private caption").assertDoesNotExist()
            compose.runOnIdle {
                assertFalse("Removing the private composition must release its lease", activity.secured())
                assertTrue("No private caption frame may be rendered without protection", drawnFrames.filter { it.first != "Ordinary conversation" }.all { it.second })
            }
        } finally {
            otherScreenRelease?.invoke()
            activity.window.callback = previousCallback
            controller.pause().stop().destroy()
        }
    }

    private fun ProtectedActivity.secured(): Boolean = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}
