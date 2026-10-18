package dev.chungjungsoo.gptmobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCombinedLifecycleTest {
    private val original = AppFeatureSettings(conversationCombined = mapOf(4 to ConversationCombinedSettings(effort = 100, creativity = 0.7f)), conversationIdentities = mapOf(4 to "source"))

    @Test fun duplicateCopiesPolicyAndUsesANewStableIdentityIdempotently() {
        val copied = ConversationCombinedLifecycle.copy(original, 4, 5)
        assertEquals(original.conversationCombined[4], copied.conversationCombined[5])
        assertNotEquals(copied.conversationIdentities[4], copied.conversationIdentities[5])
        assertEquals(copied, ConversationCombinedLifecycle.copy(copied, 4, 5))
    }

    @Test fun adoptionChecksDraftIdentityAndCannotCopyAnotherUnsavedChat() {
        val draft = original.copy(conversationIdentities = mapOf(-20 to "draft-a", -21 to "draft-b"), conversationCombined = mapOf(-20 to ConversationCombinedSettings(effort = 75), -21 to ConversationCombinedSettings(effort = 0)))
        assertTrue(runCatching { ConversationCombinedLifecycle.adopt(draft, -20, 8, "draft-b") }.isFailure)
        val adopted = ConversationCombinedLifecycle.adopt(draft, -20, 8, "draft-a")
        assertEquals(75, adopted.conversationCombined.getValue(8).effort)
        assertEquals(0, adopted.conversationCombined.getValue(-21).effort)
        assertFalse(-20 in adopted.conversationCombined)
        assertEquals(adopted, ConversationCombinedLifecycle.adopt(adopted, -20, 8, "draft-a"))
    }

    @Test fun staleSaveDoesNotOverwriteNewerRevisionOrOtherFeatureChanges() {
        val saved = ConversationCombinedLifecycle.save(original.copy(edgeFades = false), 4, ConversationCombinedSettings(effort = 0), 0)
        assertEquals(1L, saved.conversationCombined.getValue(4).revision)
        assertFalse(saved.edgeFades)
        assertTrue(runCatching { ConversationCombinedLifecycle.save(saved, 4, ConversationCombinedSettings(effort = 75), 0) }.isFailure)
    }

    @Test fun interruptedDeletionFinishesOnlyForMissingIdentity() {
        val pending = ConversationCombinedLifecycle.prepareDeletion(original, 4)
        val canceled = ConversationCombinedLifecycle.recoverDeletions(pending, setOf(4))
        assertEquals(original, canceled)
        val deleted = ConversationCombinedLifecycle.recoverDeletions(pending, emptySet())
        assertTrue(deleted.conversationCombined.isEmpty())
        assertTrue(deleted.conversationIdentities.isEmpty())
        val reused = pending.copy(conversationIdentities = mapOf(4 to "new-room"))
        assertTrue(4 in ConversationCombinedLifecycle.delete(reused, 4).conversationCombined)
    }

    @Test fun invalidTemporaryAndDuplicateIdentitiesAreRejected() {
        assertTrue(runCatching { ConversationCombinedLifecycle.ensureIdentity(original, 0, "zero") }.isFailure)
        assertTrue(runCatching { ConversationCombinedLifecycle.ensureIdentity(original, 5, "source") }.isFailure)
    }
}
