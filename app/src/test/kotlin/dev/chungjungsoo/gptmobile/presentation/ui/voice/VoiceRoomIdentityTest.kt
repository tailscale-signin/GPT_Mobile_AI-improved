package dev.chungjungsoo.gptmobile.presentation.ui.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceRoomIdentityTest {
    @Test fun restoredIdentityMatchesOnlyTheSameRoomCreation() {
        val original = VoiceRoomIdentity.persistent(7, 100, temporary = false)!!
        assertEquals(original, VoiceRoomIdentity.fromPreference(original.preferenceKey, true))
        assertNotEquals(original, VoiceRoomIdentity.persistent(7, 200, temporary = false))
        assertNotEquals(original, VoiceRoomIdentity.persistent(8, 100, temporary = false))
    }

    @Test fun temporaryUnsavedAndInvalidRoomsDoNotPersistPresentation() {
        assertNull(VoiceRoomIdentity.persistent(7, 100, temporary = true))
        assertNull(VoiceRoomIdentity.persistent(0, 100, temporary = false))
        assertNull(VoiceRoomIdentity.persistent(7, 0, temporary = false))
        for (key in listOf("voice_room_7", "other_7_100", "voice_room_-1_100", "voice_room_7_100_extra")) {
            assertNull(VoiceRoomIdentity.fromPreference(key, true))
        }
        assertNull(VoiceRoomIdentity.fromPreference("voice_room_7_100", false))
        assertNull(VoiceRoomIdentity.fromPreference("voice_room_7_100", "true"))
    }
}
