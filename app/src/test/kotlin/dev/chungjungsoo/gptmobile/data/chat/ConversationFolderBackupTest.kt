package dev.chungjungsoo.gptmobile.data.chat

import dev.chungjungsoo.gptmobile.data.backup.DatabaseBackupPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationFolderBackupTest {
    @Test fun legacyBackupWithoutFoldersRemainsReadable() {
        val backup = Json.decodeFromString<DatabaseBackupPayload>("""{"version":2,"exportedAt":0}""")
        assertTrue(backup.conversationFolders.isEmpty())
        assertTrue(backup.conversationFolderMembers.isEmpty())
    }

    @Test fun oldFolderPayloadRetainsCustomColourAndNewFoldersFollowTheme() {
        val old = Json.decodeFromString<ConversationFolder>("""{"id":"old","name":"Old","color":4284895396,"createdAt":1}""")
        assertEquals(ConversationFolderStyle.CUSTOM, old.colorMode)
        assertEquals(ConversationFolderStyle.THEME, ConversationFolder.new("new", "New", 2).colorMode)
        assertEquals(ConversationFolderStyle.CUSTOM, ConversationFolder.new("custom", "Custom", 3, ConversationFolderStyle.colors.last()).colorMode)
    }

    @Test fun folderNamesColoursAndMembershipRoundTrip() {
        val backup = DatabaseBackupPayload(
            exportedAt = 123L,
            conversationFolders = listOf(ConversationFolder("folder", "Research", ConversationFolderStyle.colors.first(), 123), ConversationFolder.new("theme", "Theme", 124)),
            conversationFolderMembers = listOf(ConversationFolderMember(42, "folder"))
        )
        assertEquals(backup, Json.decodeFromString<DatabaseBackupPayload>(Json.encodeToString(backup)))
    }
}
