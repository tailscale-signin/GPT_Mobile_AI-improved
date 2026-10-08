package dev.chungjungsoo.gptmobile.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteBackupSelectionTest {
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

    @Test fun deselectingConversationsDeselectsAttachmentsAndHistory() {
        val selection = CompleteBackupSelection.ALL.toggled(CompleteBackupSection.CONVERSATIONS, false)
        assertFalse(selection.includes(CompleteBackupSection.ATTACHMENTS))
        assertFalse(selection.includes(CompleteBackupSection.AGENT_HISTORY))
    }
}
