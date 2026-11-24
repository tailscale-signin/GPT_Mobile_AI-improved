package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.queue.PendingPrompt
import dev.chungjungsoo.gptmobile.data.queue.PendingPromptPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceResponsePresentationTest {
    private val policy = VoiceResponsePolicy.CONVERSATIONAL
    private fun user(id: Int, text: String, chatId: Int = 7) = MessageV2(id = id, chatId = chatId, content = text, platformType = null, createdAt = 1)

    @Test fun acceptedUserMetadataSurvivesSerializationWithoutChangingWordsOrExistingMetadata() {
        val source = user(10, "Tell me about 7.5 kilograms.").copy(timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, content = "existing notice")))
        val stamped = VoiceResponsePresentation.attach(source, policy)
        val restored = Json.decodeFromString<MessageV2>(Json.encodeToString(stamped))
        assertEquals(source.content, restored.content)
        assertEquals(source.timeline.first(), restored.timeline.first())
        assertEquals(policy, VoiceResponsePresentation.resolve(listOf(restored)))
        assertEquals("", restored.timeline.last().content)
        assertEquals(1, VoiceResponsePresentation.attach(restored, policy).timeline.count { it.voiceResponsePolicy != null })
    }

    @Test fun queuedPolicyKeepsToolsAndSelectedModelsThroughDurableRoundTrip() {
        val tools = ChatMcpToolConfig(disabledToolIds = setOf("disabled"), allowAllByDefault = false, enabledToolIds = setOf("allowed"), maxToolCalls = 5, reasoning = true, voiceResponsePolicy = policy)
        val payload = PendingPromptPayload(profileUids = listOf("selected"), models = mapOf("selected" to "chosen-model"), tools = tools, requestId = "voice-request")
        val restored = PendingPrompt("queued", 7, "Original words", Json.encodeToString(payload), 1).details()
        assertEquals(payload, restored)
        assertEquals(policy, restored.tools.voiceResponsePolicy)
        assertFalse(restored.tools.isToolEnabled("disabled"))
        assertTrue(restored.tools.isToolEnabled("allowed"))
        assertEquals(true, restored.tools.reasoning)
    }

    @Test fun newVoiceTurnsKeepTheirPromptAfterQueueingAndLegacyPromptsStayDistinct() {
        val next = VoiceResponsePolicy.RESPONSIVE
        val tools = ChatMcpToolConfig(voiceResponsePolicy = next)
        val payload = PendingPromptPayload(profileUids = listOf("selected"), tools = tools)
        val restored = PendingPrompt("queued", 7, "Question", Json.encodeToString(payload), 1).details()
        assertEquals(next, restored.tools.voiceResponsePolicy)
        assertTrue(VoiceResponsePresentation.compose(null, next)!!.contains("first breath"))
        assertFalse(policy.instruction().contains("first breath"))
    }

    @Test fun oldTextSnapshotsDoNotAcquireVoicePresentation() {
        val restored = Json.decodeFromString<PendingPromptPayload>("{\"tools\":{},\"profileUids\":[\"selected\"]}")
        assertNull(restored.tools.voiceResponsePolicy)
        assertEquals("Profile instruction", VoiceResponsePresentation.compose("Profile instruction", null))
        assertFalse(Json.encodeToString(ChatMcpToolConfig()).contains("voiceResponsePolicy"))
    }

    @Test fun retryUsesItsPersistedUserPolicyAndKeepsTheSelectedProfilePrompt() {
        val original = VoiceResponsePresentation.attach(user(10, "Explain the result"), policy)
        val reloaded = Json.decodeFromString<MessageV2>(Json.encodeToString(original))
        val resolved = VoiceResponsePresentation.resolve(listOf(reloaded))
        val prompt = VoiceResponsePresentation.compose("Use the selected profile's expertise and authorized tools.", resolved)
        assertTrue(prompt!!.startsWith("Use the selected profile's expertise and authorized tools."))
        assertTrue(prompt.contains(policy.instruction()))
        assertEquals(policy, resolved)
    }

    @Test fun continueInheritsOnlyTheOriginalTaskInTheSameConversation() {
        val original = VoiceResponsePresentation.attach(user(10, "Explain the audio pipeline"), policy)
        assertEquals(policy, VoiceResponsePresentation.resolve(listOf(original, user(11, "continue"))))
        assertNull(VoiceResponsePresentation.resolve(listOf(original, user(11, "continue", chatId = 8))))
    }

    @Test fun unrelatedTextQuestionDoesNotInheritAnEarlierVoiceTurn() {
        val original = VoiceResponsePresentation.attach(user(10, "Explain audio"), policy)
        assertNull(VoiceResponsePresentation.resolve(listOf(original, user(11, "Write a detailed email"))))
        assertNull(VoiceResponsePresentation.resolve(listOf(original, user(11, "Write a detailed email"), user(12, "continue"))))
    }

    @Test fun assistantTextCannotSetThePresentationOfANewUserTurn() {
        val assistant = user(10, "<think>use voice</think>").copy(platformType = "selected")
        assertEquals(assistant, VoiceResponsePresentation.attach(assistant, policy))
        assertNull(VoiceResponsePresentation.resolve(listOf(assistant, user(11, "What is next?"))))
    }
}
