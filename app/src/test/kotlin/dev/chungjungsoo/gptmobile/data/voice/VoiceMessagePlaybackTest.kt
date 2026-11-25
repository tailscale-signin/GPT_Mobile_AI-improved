package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessagePlaybackTest {
    private fun reply(id: Int, run: String = "run-$id", text: String = "Here is the reply.", complete: Boolean = false) =
        VoiceMessageReply(VoiceMessageIdentity(id, run), text, complete)

    @Test fun onlyAFreshAudibleGenerationCanOwnALookupBeforeAnswerText() {
        val playback = VoiceMessagePlayback()
        val historical = reply(1, text = "")
        val muted = reply(2, text = "")
        val eligible = reply(3, text = "")
        playback.observe(listOf(historical), true)
        assertNull(playback.next(historical.identity))
        playback.observe(listOf(muted), false)
        assertNull(playback.next(muted.identity))
        playback.observe(listOf(eligible), true)
        assertEquals(eligible, playback.next(eligible.identity))
        playback.finish()
        assertNull(playback.next(eligible.identity))
    }

    @Test fun terminalDurableReadReplacesAStaleBlankOrPrefixBeforeConsumingTheReply() {
        for (stale in listOf("", "Here is")) {
            val playback = VoiceMessagePlayback()
            playback.observe(emptyList(), true)
            playback.observe(listOf(reply(1, text = stale, complete = true)), true)
            val pending = requireNotNull(playback.next())
            // A missing/unavailable read does not call terminalUpdate or mark delivered.
            assertEquals(pending, playback.active)
            val durable = requireNotNull(playback.terminalUpdate(pending, "Here is the complete durable reply.", failed = false))
            assertEquals("Here is the complete durable reply.", durable.text)
            assertTrue(durable.complete)
            assertNull(playback.update())
            playback.finish()
            playback.observe(listOf(durable), true)
            assertNull(playback.next())
        }
    }

    @Test fun suspendedTerminalReadCannotTakeOwnershipFromManualReplayReplacementOrStop() {
        for (action in listOf("manual", "replacement", "stop")) {
            val playback = VoiceMessagePlayback()
            playback.observe(emptyList(), true)
            playback.observe(listOf(reply(1, text = "", complete = true)), true)
            val pending = requireNotNull(playback.next())
            when (action) {
                "manual" -> playback.manual(VoiceMessageIdentity(3, "historical", 0))
                "replacement" -> {
                    playback.observe(listOf(reply(1, "replacement", "New response", complete = true)), true)
                    playback.retireSuperseded()
                    playback.next()
                }
                else -> playback.finish()
            }
            val owner = playback.active
            assertNull(playback.terminalUpdate(pending, "Late old durable reply", failed = false))
            assertEquals(owner, playback.active)
            if (action == "replacement") assertEquals("replacement", playback.update()?.identity?.runId)
        }
    }

    @Test fun authoritativeEmptyAndFailedTerminalsRemainObservableWithoutEnablingMutedAutoplay() {
        for (failed in listOf(false, true)) {
            val playback = VoiceMessagePlayback()
            playback.observe(emptyList(), true)
            playback.observe(listOf(reply(1, text = "", complete = true)), false)
            val pending = requireNotNull(playback.next())
            val terminal = requireNotNull(playback.terminalUpdate(pending, "", failed))
            assertEquals("", terminal.text)
            assertEquals(failed, terminal.failed)
            assertFalse(terminal.readAloud)
            assertNull(playback.update())
        }
    }

    @Test fun retainedHistoryAndLaterPaginationNeverAutoplay() {
        val playback = VoiceMessagePlayback()
        playback.observe(listOf(reply(50, complete = true)), true)
        assertNull(playback.next())
        playback.observe(listOf(reply(10, complete = true), reply(50, complete = true)), true)
        assertNull(playback.next())
        assertTrue(playback.initialized)
    }

    @Test fun newTypedReplyStreamsAndCompletesOnlyOnce() {
        val playback = VoiceMessagePlayback()
        playback.observe(emptyList(), true)
        val opening = reply(1, text = "Here are four words ")
        playback.observe(listOf(opening), true)
        assertEquals(opening, playback.next())
        assertEquals(opening, playback.update())
        playback.observe(listOf(opening), true)
        assertNull(playback.update())
        val final = opening.copy(text = opening.text + "and the ending.", complete = true)
        playback.observe(listOf(final), true)
        assertEquals(final, playback.update())
        assertNull(playback.update())
        playback.finish()
        playback.observe(listOf(final), true)
        assertNull(playback.next())
    }

    @Test fun turningAutoplayOnDoesNotReadResponsesReceivedWhileOff() {
        val playback = VoiceMessagePlayback()
        playback.observe(emptyList(), true)
        playback.observe(listOf(reply(1)), false)
        playback.observe(listOf(reply(1, complete = true)), true)
        val silent = requireNotNull(playback.next())
        assertFalse(silent.readAloud)
        assertTrue(silent.complete)
        playback.finish()
        playback.observe(listOf(reply(1, complete = true), reply(2)), true)
        assertEquals(2, playback.next()?.identity?.messageId)
    }

    @Test fun explicitSelectionConsumesTheCurrentAutomaticGeneration() {
        val playback = VoiceMessagePlayback()
        playback.observe(listOf(reply(1, complete = true)), true)
        playback.observe(listOf(reply(1, complete = true), reply(2)), true)
        playback.next()
        playback.manual(VoiceMessageIdentity(1, "run-1"))
        playback.observe(listOf(reply(1, complete = true), reply(2, complete = true)), true)
        assertNull(playback.next())
    }

    @Test fun regeneratedMessageUsesItsNewRunWithoutReplayingThePriorGeneration() {
        val playback = VoiceMessagePlayback()
        playback.observe(listOf(reply(5, complete = true)), true)
        val regenerated = reply(5, run = "revised")
        playback.observe(listOf(regenerated), true)
        assertEquals(regenerated, playback.next())
        playback.observe(listOf(regenerated), true)
        playback.finish()
        assertNull(playback.next())
    }

    @Test fun supersededActiveRunTerminatesBeforeItsReplacementCanPlay() {
        val playback = VoiceMessagePlayback()
        playback.observe(emptyList(), true)
        playback.observe(listOf(reply(1)), true)
        playback.next()
        playback.update()
        val replacement = reply(1, run = "replacement")
        playback.observe(listOf(replacement), true)
        assertNull(playback.update())
        assertNull(playback.next())
        assertEquals(VoiceMessageIdentity(1, "run-1"), playback.retireSuperseded())
        assertNull(playback.retireSuperseded())
        assertEquals(replacement, playback.next())
    }

    @Test fun multipleResponsesRemainSequentialAndMuteKeepsOnlySilentCompletion() {
        val playback = VoiceMessagePlayback()
        playback.observe(emptyList(), true)
        playback.observe(listOf(reply(1), reply(2)), true)
        assertEquals(1, playback.next()?.identity?.messageId)
        assertTrue(playback.hasPending)
        assertNull(playback.next())
        playback.observe(listOf(reply(1), reply(2)), false)
        playback.finish()
        val silent = requireNotNull(playback.next())
        assertEquals(2, silent.identity.messageId)
        assertFalse(silent.readAloud)
    }

    @Test fun recreationPrimesAnInProgressResponseWithoutReplayingItsEnding() {
        val playback = VoiceMessagePlayback()
        playback.observe(listOf(reply(3)), true)
        playback.observe(listOf(reply(3, complete = true)), true)
        assertNull(playback.next())
    }

    @Test fun emptyPlaceholderWaitsForSpeakableTextAndFailedTerminalRemainsObservable() {
        val playback = VoiceMessagePlayback()
        playback.observe(emptyList(), true)
        playback.observe(listOf(reply(1, text = "")), true)
        assertNull(playback.next())
        val failure = reply(1, text = "", complete = true).copy(failed = true)
        playback.observe(listOf(failure), true)
        assertEquals(failure, playback.next())
    }
}
