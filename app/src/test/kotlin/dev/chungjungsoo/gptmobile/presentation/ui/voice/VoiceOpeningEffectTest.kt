package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.voice.VoiceAdmissionStage
import dev.chungjungsoo.gptmobile.data.voice.VoiceResponsePolicy
import dev.chungjungsoo.gptmobile.data.voice.VoiceResponsePresentation
import dev.chungjungsoo.gptmobile.data.voice.VoiceTurnAdmission
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceOpeningEffectTest {
    private val run = AgentRun("r", 5, 1, 2, "p", "provider", "model", AgentRunStatus.RUNNING, ownershipFence = 7)
    private val user = MessageV2(id = 1, chatId = 5, content = "Compare the options.", platformType = null)
    private val assistant = MessageV2(id = 2, chatId = 5, content = "", currentRunId = "r", platformType = "test", ownershipFence = 7)
    private val accepted = VoiceTurnAdmission("request", 5, "p", VoiceAdmissionStage.ACCEPTED, 1, listOf("r"))

    private fun plan(typed: Boolean = false, prompt: MessageV2 = user, answer: MessageV2 = assistant, transcript: String = user.content, admission: VoiceTurnAdmission? = accepted, room: Int = 5, selected: String = "p") =
        voiceOpeningPlan(admission, transcript, typed, room, selected, ChatViewModel.GroupedMessages(listOf(prompt), listOf(listOf(answer))), mapOf("r" to run), emptyMap(), "en-US")

    @Test fun theActualAnswerOrAChangeOfOwnershipAlwaysWinsTheGracePeriod() {
        assertEquals("Let me compare those.", plan()?.cue?.text)
        assertNull(plan(answer = assistant.copy(content = "First answer token")))
        assertNull(plan(answer = assistant.copy(ownershipFence = 8)))
        assertNull(plan(answer = assistant.copy(currentRunId = "new")))
        assertNull(plan(room = 8))
        assertNull(plan(selected = "other"))
        assertNull(plan(transcript = "Stale prompt"))
        assertNull(plan(admission = accepted.copy(stage = VoiceAdmissionStage.QUEUED)))
    }

    @Test fun translationsOfLookupRequestsNeverSpeakALocalAcknowledgment() {
        val marked = VoiceResponsePresentation.attach(user, VoiceResponsePolicy.forLanguage("fr", translate = true))
        assertNull(plan(prompt = marked))
        assertNull(plan(typed = true, prompt = marked))
    }

    @Test fun onlyTypedRequestsWithVoicePresentationCanGetAnOpening() {
        assertNull(plan(typed = true))
        val marked = VoiceResponsePresentation.attach(user, VoiceResponsePolicy.RESPONSIVE)
        assertNotNull(plan(typed = true, prompt = marked, transcript = "", admission = null))
    }
}
