package dev.chungjungsoo.gptmobile.data.database

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPrivacySerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test fun legacyPrivacyHistoryBecomesAPermanentPolicyOnImport() {
        val legacyPrivate = json.decodeFromString<ChatRoomV2>("""{"title":"Legacy private","privacyModeEnabled":true}""")
        val legacyOrdinary = json.decodeFromString<ChatRoomV2>("""{"title":"Legacy ordinary"}""")

        assertTrue(legacyPrivate.privacyModeEnabled)
        assertTrue(legacyPrivate.privacyModeLocked)
        assertFalse(legacyOrdinary.privacyModeEnabled)
        assertFalse(legacyOrdinary.privacyModeLocked)
    }

    @Test fun permanentPolicyAndHistoricalEvidenceRoundTripIndependently() {
        for (locked in listOf(false, true)) {
            for (used in listOf(false, true)) {
                val room = ChatRoomV2(title = "Saved", privacyModeEnabled = used, privacyModeLocked = locked)
                val restored = json.decodeFromString<ChatRoomV2>(json.encodeToString(ChatRoomV2.serializer(), room))

                assertEquals(room, restored)
                assertEquals(locked, restored.privacyModeLocked)
                assertEquals(used, restored.privacyModeEnabled)
            }
        }
    }

    @Test fun recordingPrivacyUseDoesNotChangeAnExistingPolicy() {
        val ordinary = ChatRoomV2(title = "Ordinary")
        val used = ordinary.copy(privacyModeEnabled = true)

        assertFalse(used.privacyModeLocked)
        assertTrue(used.privacyModeEnabled)
    }
}
