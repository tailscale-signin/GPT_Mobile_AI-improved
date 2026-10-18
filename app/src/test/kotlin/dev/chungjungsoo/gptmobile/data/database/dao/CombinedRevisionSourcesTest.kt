package dev.chungjungsoo.gptmobile.data.database.dao

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.containsLocalOnlyMemory
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveCombinedSources
import dev.chungjungsoo.gptmobile.data.database.entity.snapshotLatestAssistantRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedRevisionSourcesTest {
    @Test fun selectingAnArchivedAnswerUsesOnlyItsOwnContributions() {
        val source = CombinedModelResponse("old", "Old profile", content = "Original contribution", localOnly = true)
        val original = MessageV2(chatId = 1, platformType = "owner", content = "Original answer", combinedSources = listOf(source))
        val archived = requireNotNull(original.snapshotLatestAssistantRevision())
        val latest = original.copy(
            content = "New answer",
            combinedSources = listOf(source.copy(platformUid = "new", localOnly = false)),
            revisions = listOf(archived),
            activeRevisionIndex = 0
        )
        assertEquals(listOf(source), latest.effectiveCombinedSources())
        assertTrue(latest.containsLocalOnlyMemory())
        assertEquals("new", latest.copy(activeRevisionIndex = -1).effectiveCombinedSources().single().platformUid)
    }

    @Test fun legacyArchivedAnswersCannotBorrowCurrentAttribution() {
        val current = MessageV2(
            chatId = 1,
            platformType = "owner",
            content = "Current answer",
            combinedSources = listOf(CombinedModelResponse("new", "New", content = "Current source")),
            revisions = listOf(AssistantRevision("Legacy answer", createdAt = 1)),
            activeRevisionIndex = 0
        )
        assertTrue(current.effectiveCombinedSources().isEmpty())
    }
}
