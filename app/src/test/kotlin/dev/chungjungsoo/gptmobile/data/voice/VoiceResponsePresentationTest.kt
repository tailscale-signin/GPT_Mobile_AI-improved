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

    @Test fun everyVoiceLanguageFreezesADistinctReplyAndTranslationInstruction() {
        for (language in SpeechLanguages.synthesis) {
            val reply = VoiceResponsePolicy.forLanguage(language.tag)
            val translation = VoiceResponsePolicy.forLanguage(language.tag, translate = true)
            assertTrue(reply.fixesReplyLanguage && !reply.translationOnly)
            assertTrue(translation.fixesReplyLanguage && translation.translationOnly)
            assertTrue(reply.instruction().contains("entire reply"))
            val source = user(10, "What time is it?")
            val saved = Json.decodeFromString<MessageV2>(Json.encodeToString(VoiceResponsePresentation.attach(source, translation)))
            assertEquals(translation, VoiceResponsePresentation.resolve(listOf(saved), reply))
            assertEquals(source.content, saved.content)
            assertNull(VoiceResponsePresentation.resolve(listOf(saved, user(11, "continue"))))
            val tools = ChatMcpToolConfig(enabledToolIds = setOf("search"), reasoning = true).withVoicePolicy(translation)
            assertFalse(tools.isToolEnabled("search"))
            assertEquals(false, tools.delegation?.enabled)
            assertEquals(false, tools.research?.researchToggle)
            assertEquals(translation.instruction(), VoiceResponsePresentation.compose("Answer every question in English", translation))
        }
        assertEquals(VoiceResponsePolicy.TRANSLATE_FRENCH, VoiceResponsePolicy.forLanguage("fr-CA", true))
        assertEquals(VoiceResponsePolicy.REPLY_BRAZILIAN_PORTUGUESE, VoiceResponsePolicy.forLanguage("pt"))
        assertEquals(VoiceResponsePolicy.REPLY_AMERICAN_ENGLISH, VoiceResponsePolicy.forLanguage("en-US"))
        assertTrue(Json.decodeFromString<VoiceResponsePolicy>("\"FRENCH\"").instruction().contains("unless the user asks"))
    }

    @Test fun oldTextSnapshotsDoNotAcquireVoicePresentation() {
        val restored = Json.decodeFromString<PendingPromptPayload>("{\"tools\":{},\"profileUids\":[\"selected\"]}")
        assertNull(restored.tools.voiceResponsePolicy)
        assertEquals("Profile instruction", VoiceResponsePresentation.compose("Profile instruction", null))
        assertFalse(Json.encodeToString(ChatMcpToolConfig()).contains("voiceResponsePolicy"))
    }

    @Test fun aTranslationPolicyCannotRegainToolsThroughSavedOrLaterToggleSettings() {
        val translation = VoiceResponsePolicy.TRANSLATE_FRENCH
        val direct = ChatMcpToolConfig(enabledToolIds = setOf("search", "send_email"), allowAllByDefault = true, voiceResponsePolicy = translation)
        for (tools in listOf(direct, direct.withToolEnabled("search"), direct.copy(allToolsDisabled = false, maxTools = 99, maxToolCalls = 99))) {
            assertFalse(tools.isToolEnabled("search"))
            assertFalse(tools.isToolEnabled(listOf("search", "send_email")))
            assertTrue(tools.limitTools(listOf("search", "send_email")).isEmpty())
            val delegation = tools.effectiveDelegation(dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings(enabled = true, reviewerEnabled = true, researchEnabled = true, automaticResearch = true))
            assertFalse(delegation.enabled)
            assertFalse(delegation.reviewerEnabled)
            assertFalse(delegation.researchEnabled)
            assertFalse(delegation.automaticResearch)
        }
        assertTrue(ChatMcpToolConfig().isToolEnabled("search"))
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
