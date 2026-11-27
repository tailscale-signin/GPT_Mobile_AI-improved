package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VoiceCaptionRouteIdentityTest {
    @Test fun differentBluetoothDevicesAndTransportTypesHaveIndependentCalibration() {
        val first = VoiceCaptionRouteIdentity.key("bluetooth", 8, "00:11:22:33:44:55", "Headphones", 24_000)
        val second = VoiceCaptionRouteIdentity.key("bluetooth", 8, "00:11:22:33:44:66", "Headphones", 24_000)
        val lowEnergy = VoiceCaptionRouteIdentity.key("bluetooth", 26, "00:11:22:33:44:55", "Headphones", 24_000)
        assertNotEquals(first, second)
        assertNotEquals(first, lowEnergy)
        assertFalse(first.contains("00:11"))
        assertFalse(first.contains("Headphones"))
    }

    @Test fun aStableAddressSurvivesAProductRenameAndFormatChangesInvalidateTheKey() {
        val first = VoiceCaptionRouteIdentity.key("usb", 11, "card=1;device=0", "Old name", 24_000)
        assertEquals(first, VoiceCaptionRouteIdentity.key("usb", 11, "card=1;device=0", "New name", 24_000))
        assertNotEquals(first, VoiceCaptionRouteIdentity.key("usb", 11, "card=1;device=0", "Old name", 48_000))
    }

    @Test fun withheldAddressesUseOnlyAnOpaqueProductIdentity() {
        val key = VoiceCaptionRouteIdentity.key("wired", 4, "", "My headset", 24_000)
        assertEquals(key, VoiceCaptionRouteIdentity.key("wired", 4, "  ", "My headset", 24_000))
        assertNotEquals(key, VoiceCaptionRouteIdentity.key("wired", 4, "", "Another headset", 24_000))
        assertFalse(checkNotNull(VoiceCaptionRouteIdentity.observedRoute(key, "", "My headset")).contains("My headset"))
        assertEquals(null, VoiceCaptionRouteIdentity.observedRoute(key, "", ""))
    }
}
