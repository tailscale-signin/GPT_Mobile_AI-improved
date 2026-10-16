package dev.chungjungsoo.gptmobile.data.readiness

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerActivityTest {
    @Test fun focusAndDraftSignalsNeverExposeTheDraft() {
        ReadinessHints.clearComposer()
        ReadinessHints.composerDraftChanged(true)
        assertEquals(ComposerActivity.IDLE, ReadinessHints.composerActivity.value)
        ReadinessHints.composerFocusChanged(true)
        assertEquals(ComposerActivity.TYPING, ReadinessHints.composerActivity.value)
        ReadinessHints.composerDraftChanged(false)
        assertEquals(ComposerActivity.FOCUSED, ReadinessHints.composerActivity.value)
        ReadinessHints.composerFocusChanged(false)
        assertEquals(ComposerActivity.IDLE, ReadinessHints.composerActivity.value)
        ReadinessHints.clearComposer()
    }
    @Test fun selectedProfilesAreStableDeduplicatedAndBounded() {
        ReadinessHints.selectProfiles(listOf("next","first","next","second","third","fourth"))
        assertEquals(listOf("next","first","second","third"), ReadinessHints.profiles.value)
        ReadinessHints.selectProfiles(emptyList())
    }
}
