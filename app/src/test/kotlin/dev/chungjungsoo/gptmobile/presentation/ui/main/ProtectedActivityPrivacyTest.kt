package dev.chungjungsoo.gptmobile.presentation.ui.main

import android.os.Looper
import android.view.WindowManager
import dev.chungjungsoo.gptmobile.data.security.DevicePrivacySettings
import dev.chungjungsoo.gptmobile.data.security.DevicePrivacySnapshot
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtectedActivityPrivacyTest {
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

    private fun ProtectedActivity.secured(): Boolean = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}
