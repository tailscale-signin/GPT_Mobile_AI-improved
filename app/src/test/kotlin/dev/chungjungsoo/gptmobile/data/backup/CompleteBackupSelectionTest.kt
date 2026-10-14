package dev.chungjungsoo.gptmobile.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteBackupSelectionTest {
    @Test fun groupsCoverEveryArchiveSectionExactlyOnce() {
        assertTrue(CompleteBackupGroup.entries.size <= 5)
        val grouped = CompleteBackupGroup.entries.flatMap { it.sections }
        assertEquals(CompleteBackupSection.entries.toSet(), grouped.toSet())
        assertEquals(grouped.size, grouped.distinct().size)
    }

    @Test fun conversationGroupIncludesMediaHistoryAndStatisticsAndCanBeClearedTogether() {
        val selected = CompleteBackupSelection(emptySet()).toggled(CompleteBackupGroup.CONVERSATIONS, true)
        assertTrue(selected.sections.containsAll(CompleteBackupGroup.CONVERSATIONS.sections))
        assertTrue(selected.includes(CompleteBackupSection.PLATFORMS))
        val cleared = selected.toggled(CompleteBackupGroup.CONVERSATIONS, false)
        assertTrue(cleared.sections.intersect(CompleteBackupGroup.CONVERSATIONS.sections).isEmpty())
        assertTrue(cleared.includes(CompleteBackupSection.PLATFORMS))
    }

    @Test fun platformGroupIncludesCredentialsAndModelsWithoutChangingUnrelatedSettings() {
        val original = CompleteBackupSelection(setOf(CompleteBackupSection.SETTINGS))
        val selected = original.toggled(CompleteBackupGroup.AI_PLATFORMS, true)
        assertTrue(selected.sections.containsAll(CompleteBackupGroup.AI_PLATFORMS.sections))
        assertEquals(original, selected.toggled(CompleteBackupGroup.AI_PLATFORMS, false))
    }

    @Test fun statisticsIncludesEveryDependentRecordAndCanBeDeselected() {
        val selection = CompleteBackupSelection(setOf(CompleteBackupSection.STATISTICS)).normalized()
        assertTrue(selection.includes(CompleteBackupSection.AGENT_HISTORY))
        assertTrue(selection.includes(CompleteBackupSection.PLATFORMS))
        assertTrue(selection.includes(CompleteBackupSection.CONVERSATIONS))
        assertFalse(selection.toggled(CompleteBackupSection.AGENT_HISTORY, false).includes(CompleteBackupSection.STATISTICS))
    }

    @Test fun clearingSelectAllAlsoClearsDependentConversationSections() {
        var selection = CompleteBackupSelection.ALL
        CompleteBackupSection.entries.forEach { selection = selection.toggled(it, false) }
        assertTrue(selection.sections.isEmpty())
    }

    @Test fun healthDataIsASeparateSensitiveEncryptedBackupSection() {
        val selection = CompleteBackupSelection.DEFAULT_SECTIONS
        assertFalse(selection.contains(CompleteBackupSection.HEALTH_DATA))
        val health = CompleteBackupSelection(emptySet()).toggled(CompleteBackupSection.HEALTH_DATA, true)
        assertTrue(health.requiresEncryption)
        assertTrue(CompleteBackupGroup.HEALTH_DATA.sections.contains(CompleteBackupSection.HEALTH_DATA))
    }

    @Test fun deselectingConversationsDeselectsAttachmentsAndHistory() {
        val selection = CompleteBackupSelection.ALL.toggled(CompleteBackupSection.CONVERSATIONS, false)
        assertFalse(selection.includes(CompleteBackupSection.ATTACHMENTS))
        assertFalse(selection.includes(CompleteBackupSection.AGENT_HISTORY))
    }
    @Test fun personalDataGroupsMemoryAndHealthWithoutPullingInConversations() {
        val selection = CompleteBackupSelection(emptySet()).toggled(CompleteBackupGroup.HEALTH_DATA, true)
        assertEquals(setOf(CompleteBackupSection.MEMORY, CompleteBackupSection.HEALTH_DATA), selection.sections)
        assertTrue(selection.requiresEncryption)
        assertFalse(selection.includes(CompleteBackupSection.CONVERSATIONS))
    }

}
