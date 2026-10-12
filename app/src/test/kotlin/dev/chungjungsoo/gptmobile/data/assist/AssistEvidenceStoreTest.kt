package dev.chungjungsoo.gptmobile.data.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistEvidenceStoreTest {
    @Test fun originalEvidenceRequiresMatchingScopeRevisionAndLiveHandle() {
        var now = 0L
        val store = AssistEvidenceStore("run", { now }, lifetimeMs = 10)
        val source = checkNotNull(store.retain("Mass: 12 kg; NOT approved."))
        assertEquals("Mass: 12 kg", store.read("run", source.handle, source.revision, 0, 11))
        assertTrue(runCatching { store.read("other", source.handle, source.revision, 0, 11) }.isFailure)
        assertTrue(runCatching { store.read("run", source.handle, "fabricated", 0, 11) }.isFailure)
        now = 10
        assertTrue(runCatching { store.read("run", source.handle, source.revision, 0, 11) }.isFailure)
    }

    @Test fun evictionAndInvalidRangesCannotExposeAnotherSource() {
        val store = AssistEvidenceStore("run", maxCharacters = 20)
        val first = checkNotNull(store.retain("first evidence"))
        val second = checkNotNull(store.retain("second evidence"))
        assertTrue(runCatching { store.read("run", first.handle, first.revision, 0, 5) }.isFailure)
        assertTrue(runCatching { store.read("run", second.handle, second.revision, -1, 5) }.isFailure)
        assertTrue(runCatching { store.read("run", second.handle, second.revision, 0, 40) }.isFailure)
        store.clear()
        assertTrue(runCatching { store.read("run", second.handle, second.revision, 0, 5) }.isFailure)
    }

    @Test fun globalDisableCannotBeOverriddenByAConversation() {
        val settings = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(
            localAssist = false,
            conversationAssistModes = mapOf(5 to AssistMode.LOCAL_FIRST)
        )
        assertEquals(AssistMode.OFF, settings.effectiveAssistPolicy("profile", 5).mode)
    }
}
